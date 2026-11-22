# Phase-0 baseline results

- [Preliminary comparison](20261122T221558Z/summary.md) with all nine variant and endpoint combinations, five repetitions each.
- [Trial data](20261122T221558Z/results.csv), paired warm-up and measured wrk logs, [smoke results](20261122T221558Z/smoke.txt), and [environment and binary checksums](20261122T221558Z/environment.txt).
- [Toolchains and build profiles](toolchains.md) with the measured source revision and release, GC, and warm-up settings.
- [Diagnostic profiles](profiles.md) from a separate native x86_64 GitHub Actions run.

The benchmark ran in translated x86_64 Ubuntu userspace on an Apple M3 Max. wrk shared the container, CPU pool, and kernel with the servers. The data record behavior in that environment. A controlled comparison still requires native hardware, a separate load generator, and validated JVM steady state.

Across the three endpoints, JVM median measured throughput was 2.85–3.64 times the median rate of the preceding warm-up windows, which included JVM startup work. The first Native `/health` trial recorded a 16.59 ms p99, compared with 3.50–4.30 ms in the other four trials. All five trials are included. The aggregate wrk logs leave the cause of the spike unresolved.

The raw output directory was produced using the commands below with the toolchains listed in the environment record.

```bash
./scripts/build-all.sh
./scripts/smoke-all.sh
PORT=18080 RUNS=5 WARMUP=10 DURATION=5 THREADS=2 CONNECTIONS=16 ./scripts/benchmark.sh
```
