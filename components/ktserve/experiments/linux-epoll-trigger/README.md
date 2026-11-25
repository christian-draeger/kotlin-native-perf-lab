# Linux epoll peer trigger experiment

ktServe uses level-triggered epoll by default. Set `KTSERVE_EPOLL_TRIGGER=edge` to request edge-triggered notifications for established HTTP/1.1 connections. Use `KTSERVE_EPOLL_TRIGGER=level` for an explicit control. The listener retains level-triggered behavior. Two workers can use the same `EPOLLEXCLUSIVE` shared listener or separate `SO_REUSEPORT` listeners in both peer modes.

Each connection has a single I/O owner. In edge mode an observed readiness bit survives a partial transfer and a bounded service turn. A pending transfer that can still progress goes back into the existing work queue. A direction waits for a new notification after `EAGAIN`; an interrupted syscall remains eligible to run. Each turn serves at most one read and a bounded write batch before polling again. Bytes awaiting application demand remain in the socket or the existing bounded HTTP buffers. [Linux epoll manual](https://man7.org/linux/man-pages/man7/epoll.7.html)

Run **ktServe epoll trigger comparison** in GitHub Actions for Linux x86_64 and ARM64. It runs the real transport and streaming stress fixtures in both modes and checks fragmentation, pipelining, reactivation and graceful shutdown across 96 peers in each configuration. The measured trial pairs share one release binary, two assigned server CPUs and two separate generator CPUs. There are 64 keep-alive connections, two `wrk` threads and three repetitions per configuration and endpoint. It compares `/json` and the larger `/catalog/123` with one worker, two shared-listener workers and two reuse-port workers. PR runs have shorter trial settings for validation.

Raw load and smoke logs, CPU topology, binary hashes, throughput, tail latency, CPU usage and RSS are retained as workflow artifacts. The hosted comparison is exploratory and uses one VM per architecture. A default change requires further checks with connection churn and independently generated load.

For a local rehearsal with GNU time, `wrk` and four available Linux CPU IDs:

```sh
./components/ktserve/gradlew -p components/ktserve \
  linkReleaseExecutableNative linkStressReleaseExecutableNative
bash scripts/benchmark-epoll-trigger.sh "$PWD/results/epoll-trigger" \
  components/ktserve/build/bin/native/stressReleaseExecutable/ktserve-stress-fixture.kexe
```

## Application progress wake-ups

`KTSERVE_EPOLL_APPLICATION_WAKEUP=eventfd` enables an independent opt-in readiness notification for suspending handlers and streaming bodies. The default `poll` mode retains timed checks. Either setting can run with level or edge triggered peer sockets. The Linux race test checks concurrent writes during close, repeated close and descriptor reuse. The transport stress checks exercise eventfd with both peer modes.

Run **ktServe integration benchmarks** with the `native-wakeup` application selection to compare `poll` and `eventfd` in the same Ktor Native release beside CIO. It uses level-triggered peers, 16 HTTP/1.1 connections and the exact 24 KiB upload. Pages labels the series as an upload wake-up pilot. [Integration workflow](../../../../.github/workflows/integration-benchmarks.yml) · [Upload tracking issue](https://github.com/christian-draeger/ktserve/issues/130)
