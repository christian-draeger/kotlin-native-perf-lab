# ktServe 0.3.0 qualification evidence

The release profile is a private, single-worker HTTP/1.1 origin using epoll on Ubuntu 24.04 x86_64 or ARM64 with Caddy terminating TLS. The [deployment contract](https://github.com/christian-draeger/kotlin-native-perf-lab/blob/4d95fde361339c4ca4ed0fcd4fa4fe74d2c4a1bc/components/ktserve/deployment/PRODUCTION.md) defines the configuration and support boundaries.

The reference is `35d8e938e2590a7c6221af2e7c7c0a8f432978bb`. The candidate fixtures were built at `cdec4ca0269a0769ca682567bb9042ea39feb2fe`. Their source, dependencies, example consumers, benchmark harness and ingress configuration match release commit `4d95fde361339c4ca4ed0fcd4fa4fe74d2c4a1bc`. The release commit also includes a correction to the manual verification of older Maven releases.

## Local ARM64 diagnostics

Five counterbalanced pairs measured the native /json endpoint at 16 HTTP/1.1 connections in an Ubuntu ARM64 VM. Median throughput rose from 245,169 to 270,205 requests/s. The median paired change was +7.74%, with four faster trials and a range from −2.78% to +36.42%. CPU-seconds per million successful requests fell from 4.170 to 3.747, with a reduction in each pair. Median p99 was 171 to 164 µs and peak RSS was 18,704 to 18,876 KiB. The load generator shared the VM. [Repeatable inputs and all trials](https://github.com/christian-draeger/kotlin-native-perf-lab/tree/35bd4072b8ba5412e8804bf29cf8360eb936f4dd/results/production-runtime-20261125/combined-core)

Three counterbalanced pairs at 800 offered requests/s also compared the candidate's disabled observer against an in-memory callback that counts events. All nine reference, candidate and observed trials delivered their calibrated load and met every operation target with zero failed responses and zero unsent arrivals. The overall p99 across a mix that includes a deliberate delay was 55.724 ms disabled and 56.034 ms enabled. CPU-seconds per million were 180.948 and 183.143 by group median. Paired CPU overhead was +0.04%, +5.66% and +69.30%. Short runs vary substantially, so the measurements give no precise telemetry overhead bound. The benchmark includes the synchronous callback and excludes the cost of an application-specific logging backend or exporter. [Raw local trials](observer-local/)

## Supporting checks

- [Identical-server control](control/) from [GitHub Actions](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45567260774). This compares the same server source on each architecture and provides a comparison for host and run-to-run variation.
- [TLS ingress artifacts](ingress/) from [GitHub Actions](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45570247028). These exercise the exact candidate release binaries on both architectures.
- [Resolved dependency inventory and OSV response](dependencies/) from the [0.3.0 signing rehearsal](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45570252010).
- [Supplemental Axum endurance data](endurance-reference-axum/) from [an independent GitHub-hosted run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45568569846). The standalone Axum result uses a different VM and has no controlled comparative weight.
