# ktServe CLI experiment

Use `serve` to publish JSON or a directory over HTTP. Use `run` to compile and start a Kotlin route or application. Both commands use the ktServe Native server and bind to loopback in the examples below.

## Serve JSON from stdin

Download the standalone executable for your platform. This example uses Linux x86-64.

```sh
curl -fsSL https://github.com/christian-draeger/ktserve/releases/download/cli-preview-0.1.4/ktserve-cli-preview-0.1.4-linux-x86_64 -o ktserve && chmod +x ktserve
printf '%s\n' '{"status":"ok"}' | ./ktserve serve -
```

```sh
curl -fsS http://127.0.0.1:8080/
```

Supply valid UTF-8 JSON. The CLI reads up to 1 MiB from stdin to EOF at startup and serves those bytes verbatim at `/` with `Content-Type: application/json`.

## Serve a directory

Download the archive for your platform from the [CLI preview release](https://github.com/christian-draeger/ktserve/releases/tag/cli-preview-0.1.4). Extract it and run `./ktserve serve ./public` from the extracted directory. Open http://127.0.0.1:8080/ to try the bundled site.

### Build from source

Build the executable from the repository root. Use JDK 21 for Gradle.

```sh
# Linux x86_64
./components/ktserve/gradlew -p components/ktserve/experiments/cli/static linkReleaseExecutableNative

# Linux ARM64 target from a Linux x86_64 build host
./components/ktserve/gradlew -p components/ktserve/experiments/cli/static \
  -Pbenchmark.targetArch=aarch64 linkReleaseExecutableNative

# Apple Silicon macOS with full Xcode
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  ./components/ktserve/gradlew -p components/ktserve/experiments/cli/static \
  -Pbenchmark.targetOs=macos linkReleaseExecutableNative
```

```sh
cd components/ktserve/experiments/cli
PORT=8080 ./ktserve serve ./public
```

```sh
curl -fsS http://127.0.0.1:8080/
curl -fsS http://127.0.0.1:8080/api/status.json
```

Repeat `--mount <prefix> <directory>` to serve additional directories. A positional directory mounts at `/` and can be omitted when using `--mount`. For example, this command mounts `public` at `/demo/`.

```sh
./ktserve serve --mount /demo ./public
```

Set `--cache-control` after the directory or mount it applies to. Enable `--access-log` to write method, status, duration, and outcome to stderr. Each I/O loop records exchanges in its own preallocated ring buffer. A background writer formats batches into a reusable UTF-8 buffer and writes them to stderr. A full ring drops log entries while serving continues. When the server returns normally, the writer drains the rings and reports any drops.

```sh
./ktserve serve ./public --cache-control no-cache \
  --mount /demo ./public --cache-control 'public, max-age=600' --access-log
```

The launcher starts `static/build/bin/native/releaseExecutable/ktserve.kexe`. You can run that executable directly with `serve <directory>` on the matching operating system and architecture. It needs no JVM at runtime.

The command uses the same [static-file API](../../README.md#serve-static-files) as a Kotlin application. It snapshots each directory at startup and serves `index.html` at directory URLs ending in `/`. Restart the process to pick up file changes.

## Serve HTTPS directly on Linux

Download the `-https.tar.gz` archive for Linux x86-64 or ARM64 and extract it. These builds use the OpenSSL 3 runtime libraries, provided by `libssl3t64` on Ubuntu 24.04. Run the CLI from the extracted directory and supply both a PEM certificate chain, with the leaf certificate first, and its matching unencrypted PEM private key. Restart to reload the certificate.

```sh
PORT=8443 ./ktserve serve ./public \
  --tls-cert /path/to/fullchain.pem --tls-key /path/to/key.pem --access-log
```

Use the certificate's hostname. For a private CA, pass its root certificate to curl.

```sh
curl --cacert /path/to/ca.pem https://localhost:8443/
```

The TLS transport serves HTTP/1.1 over TLS 1.2 and newer on one Linux epoll worker. Mounts, range responses and access logs work over either listener. Pending handshakes count toward the connection limit and time out after ten seconds. The CLI defaults to IPv4 loopback. Set `BIND_HOST` to the numeric IPv4 address of a specific interface to accept remote requests. SIGINT and SIGTERM initiate shutdown and flush queued access logs.

### Build with HTTPS from source

Build against OpenSSL for the target Linux distribution and architecture so its glibc dependencies match the deployment. With `libssl-dev` installed on a Linux x86-64 build host, run:

```sh
./components/ktserve/gradlew -p components/ktserve/experiments/cli/static \
  -Pktserve.opensslRoot=/usr linkReleaseExecutableNative
```

The source launcher in this directory starts the resulting executable.

Verify the HTTPS executable with Python and the OpenSSL command-line tool:

```sh
python3 components/ktserve/experiments/cli/static/https_smoke.py \
  components/ktserve/experiments/cli/static/build/bin/native/releaseExecutable/ktserve.kexe
```

## Run a Kotlin route from stdin

Download the preview executable and use a Kotlin routing block as standard input:

```sh
./ktserve run - <<<'get("/health") { text("ok") }'
```

```sh
curl -fsS http://127.0.0.1:8080/health
```

The CLI wraps the input in a `serve { … }` block, compiles it with its bundled project inputs and starts the resulting Native executable. Use a Gradle-compatible JDK such as JDK 21 on Linux x86-64 or on Apple Silicon macOS with full Xcode. The first invocation may download Gradle, the Kotlin/Native compiler and build dependencies.

The compiled app defaults to IPv4 loopback at port 8080. Set `BIND_HOST` or `PORT` to change the listener.

## Run a Kotlin application

Run a single `.kt` file that declares `main` in the default package and uses the ktServe Kotlin API. The launcher uses JDK 21, Gradle, and Kotlin/Native to compile the file, then starts the native executable. On Apple Silicon, set `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` if the system has Command Line Tools selected.

```sh
cd components/ktserve/experiments/cli
./ktserve run ./run/App.kt 8080
```

```sh
curl -fsS http://127.0.0.1:8080/users/123
```

Gradle resolves ktServe from this checkout as an included build. [Gradle composite builds](https://docs.gradle.org/current/userguide/composite_builds.html) · [Kotlin/Native executables](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)

Each invocation builds in a private directory. Successful executables are kept in `run/build/apps/` and can be started directly again.

## Smoke checks

After building the static executable, run both checks from the repository root.

```sh
cli=components/ktserve/experiments/cli
python3 "$cli/static/smoke.py" "$cli/ktserve"
python3 "$cli/run/smoke.py"
```
