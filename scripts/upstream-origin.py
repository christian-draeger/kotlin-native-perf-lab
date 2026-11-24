#!/usr/bin/env python3
"""Loopback HTTP origin with fixed fast, slow and fault-injection paths."""

import argparse
import asyncio
import json

DELAYS = {"fast": 0.005, "slow": 0.040, "deadline": 0.450, "disconnect": None}


async def run(port):
    stats = {case: {"started": 0, "replied": 0, "peer_closed": 0} for case in DELAYS}

    async def handle(reader, writer):
        try:
            headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
            request = headers.split(b"\r\n", 1)[0].split()
            if len(request) != 3 or request[0] != b"GET":
                return
            case = request[1].decode("ascii").removeprefix("/")
            if case == "stats":
                body = json.dumps(stats).encode("ascii")
            elif case in DELAYS:
                stats[case]["started"] += 1
                delay = DELAYS[case]
                if delay is None:
                    return
                try:
                    await asyncio.wait_for(reader.read(1), delay)
                    stats[case]["peer_closed"] += 1
                    return
                except ConnectionResetError:
                    stats[case]["peer_closed"] += 1
                    return
                except TimeoutError:
                    body = b"ready"
            else:
                return
            writer.write(
                f"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: {len(body)}\r\n\r\n".encode()
                + body
            )
            await writer.drain()
            if case in DELAYS:
                stats[case]["replied"] += 1
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
    asyncio.run(run(parser.parse_args().port))
