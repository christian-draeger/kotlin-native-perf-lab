#!/usr/bin/env python3
"""Serve the pinned CPU embedding reference on loopback for local integration checks."""

import argparse
from contextlib import suppress
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
from pathlib import Path

from verify_cpu import MODEL, embed, load_reference


MAX_INPUT_BYTES = 1024


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model_cache", type=Path)
    parser.add_argument("--port", type=int, default=8081)
    args = parser.parse_args()
    session, tokenizer = load_reference(args.model_cache)

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(3)
            super().setup()

        def do_POST(self):
            if self.path != "/embed":
                self.send_error(404)
                return
            length = self.headers.get("Content-Length", "")
            if not length.isdecimal() or len(length) > 4 or not 0 < int(length) <= MAX_INPUT_BYTES:
                self.send_error(400)
                return
            try:
                data = self.rfile.read(int(length))
                sentence = data.decode("utf-8")
            except (TimeoutError, UnicodeError):
                self.send_error(400)
                return
            if len(data) != int(length) or not sentence.strip():
                self.send_error(400)
                return

            vector = embed(session, tokenizer, [sentence])[0].tolist()
            body = json.dumps({"model": MODEL, "embedding": vector}, separators=(",", ":"), allow_nan=False).encode()
            with suppress(BrokenPipeError, ConnectionResetError):
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

    with HTTPServer(("127.0.0.1", args.port), Handler) as server:
        print(f"CPU embedding service listening on {server.server_port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
