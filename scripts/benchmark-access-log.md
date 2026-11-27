# Fixed-rate ktServe access-log benchmark

Build the native static CLI in release mode. Run the harness on the Linux server with Python 3.11 or later. The load machine needs Vegeta, Python, GNU time, and taskset. Set `LOAD_SSH` to a preconfigured SSH destination for a separate Linux machine. Use an IPv4 address assigned to the server and reachable from the load machine. Choose CPU masks for both machines and a disk-backed directory with space for the logs. Pass a file recording the binary's build revision and toolchain versions.

```sh
LOAD_SSH=bench-load python3 scripts/benchmark-access-log.py \
  components/ktserve/experiments/cli/static/build/bin/native/releaseExecutable/ktserve.kexe \
  results/access-log-fixed-rate \
  --toolchain ./build-toolchain.txt --bind 192.0.2.10 \
  --server-cpus 2,3 --load-cpus 2,3 --log-dir /var/tmp \
  --rates 4000 12000 24000 --repeats 3 --warmup 5 --duration 15
```

For a same-host diagnostic, omit `LOAD_SSH`, use `--local`, and supply disjoint CPU masks. The default bind address is `127.0.0.1`. Minimal Linux containers can pass `KTSERVE_SOURCE_REVISION` and `KTSERVE_SOURCE_STATUS` from the checkout they mounted when Git is absent.

## Compare the original logger and the extension

Pass the original release binary as the first argument and the new one via `--compare-binary`. Both run with `--access-log` enabled. Use `--compare-toolchain` for separate build provenance, or put both binaries' revisions and toolchain settings in the file passed to `--toolchain`.

```sh
LOAD_SSH=bench-load python3 scripts/benchmark-access-log.py \
  ./ktserve-legacy results/access-log-implementation-comparison \
  --compare-binary ./ktserve-extension \
  --toolchain ./legacy-toolchain.txt --compare-toolchain ./extension-toolchain.txt \
  --bind 192.0.2.10 --server-cpus 2,3 --load-cpus 2,3 --log-dir /var/tmp \
  --rates 4000 12000 24000 --repeats 3 --warmup 5 --duration 15
```

The trial data identifies the binaries as `legacy-on` and `extension-on` and records both SHA-256 hashes and build provenance. With no comparison binary, the harness runs the original logging-on/off experiment using a single binary.

Each pair uses warmed release binaries, one epoll worker, the same persistent HTTP/1.1 connection budget and the same 256-byte response. The order alternates per repetition. Vegeta writes raw results on the load host during its run. Their transfer happens after server CPU and RSS sampling. The offered-rate check requires the attempted count within 2% of target, all responses successful, and load-generator CPU at or below 85% of its budget. [Vegeta options and report fields](https://github.com/tsenart/vegeta#usage)

`runs.csv` records the variant, whether logging was active, completed RPS, client p50/p95/p99, CPU-seconds and CPU-seconds per million successful requests for the server process and its reactor thread, peak RSS, offered and completed counts, and written access log counts. Server CPU sampling brackets the timed load and a short log drain. `medians.json` summarizes valid load samples. `environment.json` records host and load metadata, build provenance, binary SHA-256 and sink filesystem. Each trial retains its raw Vegeta data, report, load timing and server output.

The expected access-log totals include the warm-up, measurement and one successful readiness request. Rows written during shutdown count toward total delivery. `log_reported_drops` records the logger summary on clean exit and is empty if the binary terminates on SIGTERM before the logger can publish it. The sink is a regular filesystem file with ordinary buffering. The workload does not call `fsync`. This measurement describes the configured file sink rather than syslog or a log collector.
