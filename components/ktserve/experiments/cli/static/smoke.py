import http.client
import os
import socket
import subprocess
import sys
import time
from contextlib import closing
from pathlib import Path
from tempfile import TemporaryDirectory


def request(port, path, method="GET", headers=None):
    with closing(http.client.HTTPConnection("127.0.0.1", port, timeout=3)) as client:
        client.request(method, path, headers=headers or {})
        result = client.getresponse()
        return result.status, dict(result.getheaders()), result.read()


def check_file_responses(port, data):
    status, headers, body = request(port, "/data.bin", "HEAD", {"Range": "bytes=2-4"})
    assert (status, headers["Content-Length"], body) == (200, str(len(data)), b"")
    etag = headers["ETag"]
    assert etag.startswith('"') and etag.endswith('"')
    status, headers, body = request(port, "/data.bin")
    assert (status, headers["ETag"], headers["Accept-Ranges"], body) == (200, etag, "bytes", data)
    status, headers, body = request(port, "/data.bin", headers={"Connection": "close"})
    assert (status, headers["Connection"], headers["ETag"], body) == (200, "close", etag, data)
    for condition in (etag, f"W/{etag}", f'"unrelated,etag", W/{etag}', "*"):
        status, headers, body = request(port, "/data.bin", headers={"If-None-Match": condition})
        assert (status, headers["ETag"], headers["Cache-Control"], body) == (304, etag, "no-cache", b"")
    status, headers, body = request(port, "/data.bin", "HEAD", {"If-None-Match": etag})
    assert (status, headers["ETag"], body) == (304, etag, b"")
    assert request(port, "/data.bin", headers={"If-None-Match": '"other"', "if-none-match": etag})[0] == 304
    assert request(port, "/data.bin", headers={"If-None-Match": etag, "Range": "bytes=2-4"})[0] == 304
    for interval, start, end in (("2-4", 2, 4), ("-4", len(data) - 4, len(data) - 1),
                                 (f"{len(data) - 4}-", len(data) - 4, len(data) - 1)):
        status, headers, body = request(port, "/data.bin", headers={"Range": f"bytes={interval}"})
        assert (status, headers["Content-Range"], headers["Cache-Control"], body) == (
            206, f"bytes {start}-{end}/{len(data)}", "no-cache", data[start:end + 1]
        )
    status, headers, body = request(port, "/data.bin", headers={"Range": f"bytes={len(data)}-"})
    assert (status, headers["Content-Range"], body) == (416, f"bytes */{len(data)}", b"")
    assert request(port, "/data.bin", headers={"Range": "bytes=0-1,3-4"})[2] == data
    assert request(port, "/data.bin", headers={"Range": "bytes=broken"})[2] == data
    assert request(port, "/data.bin", headers={"Range": "bytes=0-1", "If-Range": etag})[2] == data[:2]
    assert request(port, "/data.bin", headers={"Range": "bytes=0-1", "If-Range": f"W/{etag}"})[2] == data
    status, _, body = request(port, "/data.bin", headers={"Range": "bytes=0-1", "If-Match": etag})
    assert (status, body) == (206, data[:2])
    assert request(port, "/data.bin", headers={"If-Match": f"W/{etag}"})[0] == 412


def check_access_log(path):
    for _ in range(100):
        records = [line for line in path.read_text().splitlines() if line.startswith("event=exchange ")]
        if any("method=GET status=200 duration_ns=" in line for line in records):
            assert all("/" not in line for line in records)
            return
        time.sleep(0.02)
    raise AssertionError("The CLI did not log a completed GET")


def smoke(binary, fixture):
    site = fixture / "public"
    site.mkdir()
    (site / "api").mkdir()
    (site / "index.html").write_text("<h1>ktServe</h1>", encoding="utf-8")
    (site / "api" / "index.html").write_text("<h1>API</h1>", encoding="utf-8")
    (site / "api" / "status.json").write_text('{"ok":true}', encoding="utf-8")
    (site / "café file.txt").write_text("UTF-8 path", encoding="utf-8")
    (site / ".hidden").write_text("hidden", encoding="utf-8")
    (site / "empty.txt").touch()
    data = bytes(range(256)) * 400
    (site / "data.bin").write_bytes(data)
    extra = fixture / "extra"
    extra.mkdir()
    (extra / "index.html").write_text("mounted index", encoding="utf-8")
    (extra / "asset.txt").write_text("mounted asset", encoding="utf-8")
    (fixture / "private.txt").write_text("outside root", encoding="utf-8")
    (site / "external-file").symlink_to(fixture / "private.txt")
    (site / "external-dir").symlink_to(fixture, target_is_directory=True)

    oversized = site / "oversized.bin"
    with oversized.open("wb") as file:
        file.truncate(1024 * 1024 + 1)
    rejected = subprocess.run([binary, "serve", site], capture_output=True, timeout=5)
    assert rejected.returncode == 1 and b"exceeds 1048576 bytes" in rejected.stderr, rejected
    oversized.unlink()

    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    with (fixture / "server.log").open("w+") as log, subprocess.Popen(
        [binary, "serve", site, "--cache-control", "no-cache",
         "--mount", "/extra", extra, "--cache-control", "public, max-age=600", "--access-log"],
        env=os.environ | {"PORT": str(port)}, stdout=log, stderr=log,
    ) as server:
        try:
            for _ in range(100):
                if server.poll() is not None:
                    break
                try:
                    status, headers, body = request(port, "/")
                    break
                except ConnectionRefusedError:
                    time.sleep(0.05)
            else:
                raise AssertionError("The static server did not start")
            assert server.poll() is None, "The static server exited during startup"
            assert (status, headers["Content-Type"], body) == (200, "text/html; charset=UTF-8", b"<h1>ktServe</h1>")
            assert headers["Cache-Control"] == "no-cache"
            assert request(port, "/api/")[2] == b"<h1>API</h1>"
            status, headers, body = request(port, "/api/status.json?v=1")
            assert (status, headers["Content-Type"], body) == (200, "application/json", b'{"ok":true}')
            assert request(port, "/caf%C3%A9%20file.txt")[2] == b"UTF-8 path"
            check_file_responses(port, data)
            assert request(port, "/empty.txt")[2] == b""
            status, headers, body = request(port, "/extra/")
            assert (status, headers["Cache-Control"], body) == (200, "public, max-age=600", b"mounted index")
            status, headers, body = request(port, "/extra?view=a%2Bb&x=?")
            assert (status, headers["Location"], body) == (308, "/extra/?view=a%2Bb&x=?", b"")
            status, headers, body = request(port, "/api", "HEAD")
            assert (status, headers["Location"], body) == (308, "/api/", b"")
            assert request(port, "/extra/asset.txt")[2] == b"mounted asset"
            assert request(port, "/", "POST")[0] == 405
            for path in ("/absent", "/asset.txt", "/.hidden", "/external-file", "/external-dir/private.txt", "/../private.txt"):
                assert request(port, path)[0] == 404, path
            (site / "index.html").write_text("edited", encoding="utf-8")
            assert request(port, "/")[2] == b"<h1>ktServe</h1>"
            check_access_log(fixture / "server.log")
        except Exception:
            log.seek(0)
            print(log.read(), file=sys.stderr, end="")
            raise
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    server.kill()


if __name__ == "__main__":
    with TemporaryDirectory() as directory:
        smoke(Path(sys.argv[1]).resolve(), Path(directory))
    print("Static CLI smoke passed")
