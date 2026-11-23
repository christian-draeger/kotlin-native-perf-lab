#!/usr/bin/env python3
"""Check the reference application's signal and listener lifecycle."""
import os
from pathlib import Path
import signal
import subprocess
import sys

from reactor_http_smoke import connect, response


def main(port, pid):
    runner = Path(__file__).resolve().parents[1] / "run-server.sh"
    conflict = subprocess.run([runner, "kotlin-reactor"], capture_output=True, text=True, timeout=5)
    assert conflict.returncode != 0 and f"bind 127.0.0.1:{port} failed" in conflict.stderr, conflict.stderr
    with connect(port) as conn, conn.makefile("rb") as stream:
        conn.sendall(b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n")
        status, headers, body = response(stream)
        assert status == 200 and headers["connection"] == "keep-alive" and body == b"ok"
        os.kill(pid, signal.SIGTERM)
        assert stream.read(1) == b"", "The idle connection remained open during shutdown"
    print("Reactor shutdown smoke passed")


if __name__ == "__main__":
    main(int(sys.argv[1]), int(sys.argv[2]))
