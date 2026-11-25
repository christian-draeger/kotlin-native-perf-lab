#!/usr/bin/env python3
"""Verify the documented private HTTP/1.1 origin with local TLS ingress."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager, closing
from http.client import HTTPConnection, HTTPSConnection, HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
import time


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


@contextmanager
def start(command, environment, log):
    with log.open("wb") as output:
        process = subprocess.Popen(command, env=environment, stdout=output, stderr=subprocess.STDOUT)
        try:
            yield process
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError(f"Shutdown exceeded its deadline. See {log}")
            if process.returncode:
                raise AssertionError(f"{command[0]} exited with {process.returncode}. See {log}")


def request(connection, method, path, body=None, headers=None):
    connection.request(method, path, body, headers or {})
    response = connection.getresponse()
    return response.status, response.read()


def await_health(make_connection):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            with closing(make_connection()) as connection:
                if request(connection, "GET", "/health") == (200, b"ok"):
                    return
        except (OSError, HTTPException):
            pass
        time.sleep(0.1)
    raise AssertionError("Health did not recover within ten seconds")


def assert_private_listener(port):
    listeners = set()
    for name in ("tcp", "tcp6"):
        for line in Path(f"/proc/net/{name}").read_text().splitlines()[1:]:
            fields = line.split()
            address, local_port = fields[1].split(":")
            if int(local_port, 16) == port and fields[3] == "0A":
                listeners.add((name, address))
    assert listeners == {("tcp", "0100007F")}, listeners


class MetadataFixture(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        fields = ("Host", "X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-For",
                  "Forwarded", "X-Real-IP", "X-Forwarded-Port")
        body = json.dumps([self.headers.get(field) for field in fields]).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def verify_forwarding(port, make_connection):
    with ThreadingHTTPServer(("127.0.0.1", port), MetadataFixture) as origin:
        worker = Thread(target=origin.serve_forever)
        worker.start()
        try:
            with closing(make_connection()) as connection:
                status, body = request(connection, "GET", "/", headers={
                    "X-Forwarded-Proto": "http",
                    "X-Forwarded-Host": "untrusted.invalid",
                    "X-Forwarded-For": "192.0.2.4",
                    "Forwarded": "for=192.0.2.4",
                    "X-Real-IP": "192.0.2.4",
                    "X-Forwarded-Port": "80",
                })
                assert status == 200, status
                values = json.loads(body)
                assert values[0:2] == ["localhost", "https"], values
                assert values[2].split(":")[0] == "localhost", values
                assert values[3:] == ["127.0.0.1", None, None, None], values
        finally:
            origin.shutdown()
            worker.join()


def verify_origin(make_connection):
    with closing(make_connection()) as connection:
        assert request(connection, "GET", "/health") == (200, b"ok")
        same_socket = connection.sock
        assert request(connection, "GET", "/health") == (200, b"ok")
        assert connection.sock is same_socket, "Expected persistent HTTP/1.1"
        assert request(connection, "GET", "/missing")[0] == 404
        headers = {"Content-Type": "application/octet-stream"}
        assert request(connection, "POST", "/upload", b"x" * 1024, headers)[0] == 200
        assert request(connection, "POST", "/upload", b"x" * 65537, headers)[0] == 413
    with closing(make_connection()) as connection:
        assert request(connection, "GET", "/health", headers={"Host": "unmatched.invalid"})[0] in (404, 421)

    def health(_):
        with closing(make_connection()) as connection:
            return request(connection, "GET", "/health")

    with ThreadPoolExecutor(max_workers=12) as pool:
        assert list(pool.map(health, range(24))) == [(200, b"ok")] * 24
    await_health(make_connection)


def main(args):
    args.output.mkdir(parents=True, exist_ok=True)
    port, edge_port = free_port(), free_port()
    while edge_port == port:
        edge_port = free_port()
    with TemporaryDirectory(prefix="ktserve-ingress-") as temporary:
        tls = Path(temporary)
        cert, key = tls / "cert.pem", tls / "key.pem"
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
             "-nodes", "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
             "-keyout", key, "-out", cert],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        environment = {
            **os.environ, "KTSERVE_INGRESS_HOST": "localhost", "KTSERVE_INGRESS_PORT": str(edge_port),
            "KTSERVE_ORIGIN_PORT": str(port), "KTSERVE_INGRESS_BIND": "127.0.0.1",
            "KTSERVE_ORIGIN_CONNECTIONS": "8", "KTSERVE_INGRESS_CERT": str(cert), "KTSERVE_INGRESS_KEY": str(key),
            "XDG_CONFIG_HOME": str(tls / "config"), "XDG_DATA_HOME": str(tls / "data"),
        }
        caddy = [args.caddy, "run", "--config", args.config, "--adapter", "caddyfile"]
        validation = [args.caddy, "validate", *caddy[2:]]
        subprocess.run(validation, env=environment, check=True)
        context = ssl.create_default_context(cafile=cert)

        def edge():
            return HTTPSConnection("localhost", edge_port, context=context, timeout=4)

        origin_env = {
            **environment, "BIND_HOST": "127.0.0.1", "PORT": str(port),
            "KTSERVE_WORKERS": "1", "BENCHMARK_SUITE": "application-mix",
        }
        with start([args.origin], origin_env, args.output / "origin.log"):
            await_health(lambda: HTTPConnection("127.0.0.1", port, timeout=2))
            assert_private_listener(port)
            with start(caddy, environment, args.output / "ingress.log"):
                await_health(edge)
                verify_origin(edge)
        with start(caddy, environment, args.output / "forwarding.log"):
            deadline = time.monotonic() + 10
            while True:
                try:
                    with socket.create_connection(("127.0.0.1", edge_port), timeout=1):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.1)
            verify_forwarding(port, edge)
    print("TLS, private origin, HTTP/1.1 reuse, ingress policy, recovery and graceful shutdown passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin", type=Path)
    parser.add_argument("caddy", type=Path)
    parser.add_argument("config", type=Path)
    parser.add_argument("output", type=Path)
    main(parser.parse_args())
