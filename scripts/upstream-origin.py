#!/usr/bin/env python3
"""Loopback HTTP origin with fixed fast, slow and fault-injection paths."""

import argparse
import asyncio
import json

DELAYS = {"fast": 0.005, "slow": 0.040, "deadline": 0.450, "disconnect": None}


def closes_connection(headers):
    for line in headers.split(b"\r\n"):
        name, _, value = line.partition(b":")
        if name.lower() == b"connection" and b"close" in (token.strip().lower() for token in value.split(b",")):
            return True
    return False


async def run(port, keep_alive):
    stats = {case: {"started": 0, "replied": 0, "peer_closed": 0} for case in DELAYS}
    # Count only sockets that carry an application request. Stats probes stay out of the sample.
    connections = stats["_connections"] = {"accepted": 0, "requests": 0, "reused": 0}

    async def handle(reader, writer):
        seen_case = False
        try:
            while True:
                headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
                first_line, _, fields = headers.partition(b"\r\n")
                request = first_line.split()
                if len(request) != 3 or request[0] != b"GET":
                    return
                case = request[1].decode("ascii").removeprefix("/")
                persistent = False
                if case == "stats":
                    body = json.dumps(stats).encode("ascii")
                elif case in DELAYS:
                    connections["requests"] += 1
                    connections["reused" if seen_case else "accepted"] += 1
                    seen_case = True
                    stats[case]["started"] += 1
                    delay = DELAYS[case]
                    if delay is None:
                        return
                    try:
                        # Each socket carries one in-flight request. EOF here means cancellation
                        # while the origin was still preparing the response.
                        if not await asyncio.wait_for(reader.read(1), delay):
                            stats[case]["peer_closed"] += 1
                        return
                    except ConnectionResetError:
                        stats[case]["peer_closed"] += 1
                        return
                    except TimeoutError:
                        body = b"ready"
                    persistent = keep_alive and request[2] == b"HTTP/1.1" and not closes_connection(fields)
                else:
                    return
                connection = "keep-alive" if persistent else "close"
                writer.write(
                    f"HTTP/1.1 200 OK\r\nConnection: {connection}\r\nContent-Length: {len(body)}\r\n\r\n".encode()
                    + body
                )
                await writer.drain()
                if case in DELAYS:
                    stats[case]["replied"] += 1
                if not persistent:
                    return
        except (ConnectionError, TimeoutError, UnicodeError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(handle, "127.0.0.1", port, limit=8192)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("port", type=int)
    parser.add_argument("--keep-alive", action="store_true", help="Allow sequential requests on a reused HTTP/1.1 socket")
    args = parser.parse_args()
    asyncio.run(run(args.port, args.keep_alive))
