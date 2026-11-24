# Experimental ktServe io_uring transport

This standalone Linux x86_64 and ARM64 build uses the shared ktServe HTTP/1.x application and Kotlin request/response API. It can run either io_uring or epoll in the same executable. Linux release builds outside this experiment continue to select epoll.

The CI build uses Ubuntu 24.04's signed `liburing-dev` 2.5 package and links the architecture-specific static archive. Startup checks `IORING_FEAT_NODROP`, `IORING_FEAT_SUBMIT_STABLE`, `IORING_FEAT_EXT_ARG`, and support for single-shot accept, send, receive, and async cancel. The kernel and the execution environment must allow ring creation. `--capabilities` prints the feature mask or exits with status 77 and the actual Linux error number. Backend selection does not silently fall back. [liburing manual](https://github.com/axboe/liburing/tree/master/man)

After installing `liburing-dev` on Ubuntu x86_64, run these commands from the repository root.

```sh
project=components/ktserve/experiments/linux-io-uring
cc -D_GNU_SOURCE -Wall -Wextra -Werror -O3 -fPIC \
  -c "$project/src/nativeInterop/cinterop/uring_transport.c" -o /tmp/ktserve-uring.o
./components/ktserve/gradlew -p "$project" -Pbenchmark.targetArch=x86_64 \
  -Pliburing.shim=/tmp/ktserve-uring.o -Pliburing.archive=/usr/lib/x86_64-linux-gnu/liburing.a \
  linkReleaseExecutableNative linkStressReleaseExecutableNative

server="$project/build/bin/native/releaseExecutable/ktserve-io-uring.kexe"
fixture="$project/build/bin/native/stressReleaseExecutable/ktserve-io-uring-stress.kexe"
"$server" --capabilities
bash "$project/smoke.sh" "$server" "$fixture"
KTSERVE_BACKEND=io_uring "$server" 8080
```

Open `http://127.0.0.1:8080/health` to verify the process. Set `KTSERVE_BACKEND=epoll` to select the readiness backend explicitly. Startup logs the selected backend and the io_uring feature mask when applicable.

## Ownership

The transport uses a single owner thread and limits connections to 1,024. Each connection can hold one send and one receive. A live operation has room for one additional cancellation entry. The transport copies sends to native storage and copies successful receives into the leased Kotlin buffer range. Kotlin arrays are pinned only during these copies. Each native buffer stays live through the operation's terminal completion. The cancellation completion acknowledges the cancellation request and never releases a target buffer.

Closed sockets remain owned by the transport until their target operations complete. The transport drains late completions and closes any accepted socket returned during listener shutdown. There are no multishot operations, fixed files, registered buffers, SQ polling, or zero-copy sends. The shared transport fixture and HTTP suite exercise real sockets including cancellation races, blocked output, limits, coroutine cancellation, and drain.

The smoke run checks correctness. Matching offered load, resource measurements and the epoll comparison are tracked in [#54](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/54).

Run the **ktServe Linux io_uring** workflow with `measure=true` for an exploratory comparison. Each Linux worker runs both backends from the same release executable on separate server and load CPU masks within the VM. The workflow alternates backend order and archives raw wrk data, CPU, RSS and summaries for `/json` and `/users/123` at 16 and 64 connections.
