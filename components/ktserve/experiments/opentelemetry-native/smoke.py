#!/usr/bin/env python3
"""Send one traceparent request and verify SDK flush and shutdown."""

import argparse
import http.client
import socket
import subprocess
import time


def smoke(binary, endpoint, expect_export_failure):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]

    started = time.monotonic()
    with subprocess.Popen([binary, str(port), endpoint], stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True) as server:
        client = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
        try:
            for attempt in range(80):
                try:
                    client.connect()
                    break
                except ConnectionRefusedError:
                    if server.poll() is not None or attempt == 79:
                        raise
                    time.sleep(0.1)
            client.request("GET", "/widgets/42", headers={
                "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            })
            response = client.getresponse()
            assert (response.status, response.read()) == (200, b"native trace")
            output, _ = server.communicate(timeout=15)
            print(output, end="")
            assert server.returncode == 0
            success = "false" if expect_export_failure else "true"
            assert f"flush={success}" in output and f"shutdown={success}" in output
            print(f"HTTP response and SDK shutdown in {time.monotonic() - started:.2f} seconds")
        finally:
            client.close()
            if server.poll() is None:
                server.kill()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary")
    parser.add_argument("endpoint")
    parser.add_argument("--expect-export-failure", action="store_true")
    args = parser.parse_args()
    smoke(args.binary, args.endpoint, args.expect_export_failure)
