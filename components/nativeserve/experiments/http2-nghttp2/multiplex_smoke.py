"""Exercise two real HTTP/2 streams with a separate protocol implementation."""

import argparse
import os
import signal
import socket
import ssl
import time
from collections import defaultdict

from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.errors import ErrorCodes
from h2.events import ConnectionTerminated, DataReceived, StreamEnded
from h2.settings import SettingCodes

WINDOW = 4096


def exercise(sock, shutdown_pid):
    session = H2Connection(config=H2Configuration(client_side=True, header_encoding="ascii"))
    session.initiate_connection()
    session.update_settings({SettingCodes.INITIAL_WINDOW_SIZE: WINDOW})
    bodies = defaultdict(bytearray)
    ended = set()
    goaway = []

    def send_pending():
        pending = session.data_to_send()
        if pending:
            sock.sendall(pending)

    def get(path):
        stream = session.get_next_available_stream_id()
        session.send_headers(stream, [
            (":method", "GET"), (":scheme", "https" if isinstance(sock, ssl.SSLSocket) else "http"),
            (":authority", "localhost"), (":path", path),
        ], end_stream=True)
        return stream

    def receive_until(condition, blocked):
        deadline = time.monotonic() + 5
        while not condition():
            assert time.monotonic() < deadline, "Timed out waiting for HTTP/2 progress"
            payload = sock.recv(64 * 1024)
            assert payload, "HTTP/2 connection closed early"
            for event in session.receive_data(payload):
                if isinstance(event, DataReceived):
                    bodies[event.stream_id].extend(event.data)
                    if event.stream_id != blocked:
                        session.acknowledge_received_data(event.flow_controlled_length, event.stream_id)
                elif isinstance(event, StreamEnded):
                    ended.add(event.stream_id)
                elif isinstance(event, ConnectionTerminated):
                    goaway.append(event)
            if not goaway:
                send_pending()

    slow = get("/stream")
    small = get("/health")
    send_pending()
    receive_until(lambda: small in ended and len(bodies[slow]) >= WINDOW, slow)
    assert bodies[small] == b"ok"
    assert bodies[slow] == b"x" * WINDOW and slow not in ended
    if shutdown_pid:
        assert session.remote_settings.max_frame_size == 16384
        assert session.remote_settings.max_concurrent_streams == 8
    session.reset_stream(slow, error_code=ErrorCodes.CANCEL)
    following = get("/health")
    send_pending()
    receive_until(lambda: following in ended, slow)
    assert bodies[following] == b"ok"

    if shutdown_pid:
        draining = get("/stream")
        send_pending()
        receive_until(lambda: len(bodies[draining]) >= WINDOW, draining)
        os.kill(shutdown_pid, signal.SIGTERM)
        receive_until(lambda: goaway, draining)
        assert goaway[0].error_code == ErrorCodes.NO_ERROR
        assert goaway[0].last_stream_id >= draining
    print("HTTP/2 multiplex setup, flow-control cap and live cancellation passed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("port", type=int)
    parser.add_argument("--ca")
    parser.add_argument("--shutdown-pid", type=int)
    args = parser.parse_args()
    with socket.create_connection(("127.0.0.1", args.port), timeout=5) as sock:
        if args.ca:
            context = ssl.create_default_context(cafile=args.ca)
            context.set_alpn_protocols(["h2"])
            with context.wrap_socket(sock, server_hostname="localhost") as secured:
                assert secured.selected_alpn_protocol() == "h2"
                exercise(secured, args.shutdown_pid)
        else:
            exercise(sock, args.shutdown_pid)


if __name__ == "__main__":
    main()
