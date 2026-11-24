#!/usr/bin/env python3
"""Exercise more simultaneous ready peers than one epoll batch."""

from contextlib import ExitStack
import os
import signal
import sys

from ktserve_http_smoke import connect, response


def main(port, pid):
    health = b"GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n"
    json = b"GET /json HTTP/1.1\r\nHost: localhost\r\n\r\n"
    health_body = b"ok"
    json_body = b'{"id":42,"name":"benchmark-user","active":true}'

    with ExitStack() as stack:
        clients = []
        for _ in range(96):
            peer = stack.enter_context(connect(port))
            stream = stack.enter_context(peer.makefile("rb"))
            peer.sendall(health[:-1])
            clients.append((peer, stream))

        for peer, _ in clients:
            peer.sendall(health[-1:] + json + health)
        for _, stream in clients:
            for expected in (health_body, json_body, health_body):
                status, _, body = response(stream)
                assert (status, body) == (200, expected)

        # After the earlier pipeline has drained, fresh readiness must still be delivered.
        for peer, _ in clients:
            peer.sendall(json)
        for _, stream in clients:
            status, _, body = response(stream)
            assert (status, body) == (200, json_body)

        os.kill(pid, signal.SIGTERM)
        for _, stream in clients:
            assert stream.read(1) == b"", "Idle connection stayed open during shutdown"

    print("96 fragmented, pipelined and reactivated peers passed graceful shutdown")


if __name__ == "__main__":
    main(int(sys.argv[1]), int(sys.argv[2]))
