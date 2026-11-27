"""Exercise the standalone native CLI's HTTP and HTTPS modes using local certificates."""

import argparse
import http.client
import os
import re
import socket
import ssl
import subprocess
import sys
import time
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory

from smoke import smoke as http_smoke


HOST = "127.0.0.1"
TIMEOUT = 5
HANDSHAKE_TIMEOUT = 10


def openssl(*args):
    subprocess.run(["openssl", *args], check=True, stdout=subprocess.DEVNULL, timeout=TIMEOUT)


def issue_certificate(directory, name, issuer, serial, extensions):
    request = directory / f"{name}.csr"
    cert = directory / f"{name}.pem"
    key = directory / f"{name}.key"
    config = directory / f"{name}.cnf"
    config.write_text(extensions, encoding="ascii")
    openssl("req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
            "-nodes", "-subj", f"/CN={name}", "-keyout", key, "-out", request)
    openssl("x509", "-req", "-in", request, "-CA", issuer[0], "-CAkey", issuer[1],
            "-set_serial", str(serial), "-days", "1", "-extfile", config, "-out", cert)
    return cert, key


def certificates(directory):
    ca_cert = directory / "ca.pem"
    ca_key = directory / "ca.key"
    openssl("req", "-new", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
            "-nodes", "-days", "1", "-subj", "/CN=ktServe smoke root", "-keyout", ca_key, "-out", ca_cert,
            "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    intermediate = issue_certificate(directory, "smoke-intermediate", (ca_cert, ca_key), 2, """
basicConstraints=critical,CA:TRUE,pathlen:0
keyUsage=critical,keyCertSign,cRLSign
authorityKeyIdentifier=keyid:always
subjectKeyIdentifier=hash
""")
    leaf, key = issue_certificate(directory, "localhost", intermediate, 3, """
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
authorityKeyIdentifier=keyid:always
subjectKeyIdentifier=hash
subjectAltName=DNS:localhost,IP:127.0.0.1
""")
    chain = directory / "chain.pem"
    chain.write_bytes(leaf.read_bytes() + intermediate[0].read_bytes())
    return ca_cert, ca_key, chain, key


def request(client, path, method="GET", headers=None):
    client.request(method, path, headers=headers or {})
    with closing(client.getresponse()) as response:
        assert response.version == 11
        return response.status, response.headers, response.read()


def await_listener(port, server):
    deadline = time.monotonic() + TIMEOUT
    while server.poll() is None and time.monotonic() < deadline:
        try:
            return socket.create_connection((HOST, port), timeout=TIMEOUT)
        except ConnectionRefusedError:
            time.sleep(0.02)
    raise AssertionError(f"The HTTPS listener did not start (exit: {server.poll()})")


def start_fragmented_handshake(tcp):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    outgoing = ssl.MemoryBIO()
    handshake = context.wrap_bio(ssl.MemoryBIO(), outgoing, server_hostname="localhost")
    try:
        handshake.do_handshake()
    except ssl.SSLWantReadError:
        hello = outgoing.read()
        assert len(hello) > 16
        tcp.sendall(hello[:len(hello) // 2])
    else:
        raise AssertionError("The client did not wait for a ServerHello")


def assert_rejects_certificate(port, context, hostname):
    with socket.create_connection((HOST, port), timeout=TIMEOUT) as tcp:
        try:
            with context.wrap_socket(tcp, server_hostname=hostname):
                raise AssertionError("The client accepted a certificate it must reject")
        except ssl.SSLCertVerificationError:
            pass


def check_https(port, ca_cert, content):
    assert_rejects_certificate(port, ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT), HOST)
    context = ssl.create_default_context(cafile=ca_cert)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    context.set_alpn_protocols(["h2", "http/1.1"])
    assert_rejects_certificate(port, context, "wrong.localhost")

    with closing(http.client.HTTPSConnection(HOST, port, timeout=TIMEOUT, context=context)) as client:
        client.connect()
        connection = client.sock
        assert connection.version() == "TLSv1.2"
        assert connection.selected_alpn_protocol() == "http/1.1"
        status, headers, body = request(client, "/")
        assert (status, headers["Cache-Control"], body) == (200, "no-cache", b"native HTTPS")
        status, headers, body = request(client, "/asset.bin", "HEAD")
        assert (status, headers["Content-Length"], body) == (200, str(len(content)), b"")
        status, headers, body = request(client, "/asset.bin", headers={"Range": "bytes=123-456"})
        assert (status, headers["Content-Range"], body) == (206, f"bytes 123-456/{len(content)}", content[123:457])
        assert request(client, "/missing")[0] == 404
        status, _, body = request(client, "/asset.bin")
        assert (status, body) == (200, content)
        assert client.sock is connection, "HTTPS requests did not share the same connection"

    if ssl.HAS_TLSv1_3:
        context = ssl.create_default_context(cafile=ca_cert)
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        with closing(http.client.HTTPSConnection(HOST, port, timeout=TIMEOUT, context=context)) as client:
            client.connect()
            assert client.sock.version() == "TLSv1.3"
            assert request(client, "/")[0] == 200


def check_access_log(path):
    expected = {("GET", "200"), ("HEAD", "200"), ("GET", "206"), ("GET", "404")}
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        completed = set(re.findall(r"^event=exchange method=(\w+) status=(\d+) duration_ns=\d+ outcome=COMPLETED$",
                                   path.read_text(), re.MULTILINE))
        if expected <= completed:
            return
        time.sleep(0.02)
    raise AssertionError(f"The HTTPS access log is missing completed exchanges: {expected - completed}")


def https_smoke(binary, directory, check_timeout):
    root = directory / "public"
    root.mkdir()
    (root / "index.html").write_bytes(b"native HTTPS")
    content = bytes(range(256)) * 2048
    (root / "asset.bin").write_bytes(content)
    ca_cert, ca_key, chain, key = certificates(directory)
    bad_pem = directory / "bad.pem"
    bad_pem.write_text("invalid PEM\n", encoding="ascii")

    with socket.socket() as reservation:
        reservation.bind((HOST, 0))
        port = reservation.getsockname()[1]
    env = os.environ | {"BIND_HOST": HOST, "PORT": str(port), "KTSERVE_WORKERS": "1"}
    command = [binary, "serve", root]
    for option, path in (("--tls-cert", chain), ("--tls-key", key)):
        result = subprocess.run(command + [option, path], capture_output=True, timeout=TIMEOUT, env=env)
        assert result.returncode == 2 and b"Usage: ktserve serve" in result.stderr, result
    for invalid_cert, invalid_key, diagnostic in (
        (bad_pem, key, b"TLS certificate chain"),
        (directory / "missing.pem", key, b"TLS certificate chain"),
        (chain, ca_key, b"TLS private key"),
        (chain, bad_pem, b"TLS private key"),
    ):
        result = subprocess.run(command + ["--tls-cert", invalid_cert, "--tls-key", invalid_key],
                                capture_output=True, timeout=TIMEOUT, env=env)
        assert result.returncode == 1 and diagnostic in result.stderr, result

    log_path = directory / "https.log"
    with log_path.open("w+") as log, subprocess.Popen(
        command + ["--cache-control", "no-cache", "--tls-cert", chain, "--tls-key", key, "--access-log"],
        env=env, stdout=log, stderr=log,
    ) as server:
        try:
            # A peer with a partial ClientHello shares the loop with complete HTTPS exchanges.
            with await_listener(port, server) as stalled:
                handshake_started = time.monotonic()
                start_fragmented_handshake(stalled)
                check_https(port, ca_cert, content)
                check_access_log(log_path)
                if check_timeout:
                    stalled.settimeout(HANDSHAKE_TIMEOUT + TIMEOUT)
                    try:
                        assert stalled.recv(1) == b"", "The partial handshake sent unexpected data"
                    except ConnectionResetError:
                        # A reset also releases the stalled peer.
                        pass
                    assert time.monotonic() - handshake_started >= HANDSHAKE_TIMEOUT - 1
            assert server.poll() is None, "The HTTPS server exited unexpectedly"
        except Exception:
            log.seek(0)
            print(log.read(), file=sys.stderr, end="")
            raise
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=TIMEOUT)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
                    raise
    assert server.returncode == 0, f"The HTTPS server exited with {server.returncode} after SIGTERM"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("--check-handshake-timeout", action="store_true", help="Wait for the 10-second TLS deadline.")
    options = parser.parse_args()
    executable = options.executable.resolve()
    with TemporaryDirectory() as plain_directory, TemporaryDirectory() as tls_directory:
        https_smoke(executable, Path(tls_directory), options.check_handshake_timeout)
        http_smoke(executable, Path(plain_directory))
    print("Standalone HTTPS and HTTP CLI smoke passed")
