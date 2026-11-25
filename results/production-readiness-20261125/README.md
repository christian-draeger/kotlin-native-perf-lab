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

## Endurance on Linux x86_64

The exact candidate served a 31-minute mixed load run on an Intel Xeon 6975P-C. The schedule used 800 offered requests/s interleaved with two-minute bursts at 1,600 and 3,200. k6 counted 1,871,994 successful responses including warm-up, zero failed responses and one unsent JSON arrival during the second burst. Median window p99 over the first steady phase, the 3,200 burst and the last recovery phase was 51.530, 51.698 and 51.724 ms, respectively. Resident memory at the ends of recovery windows stayed between 26.0 and 26.4 MiB in the last phase. Warm-up, each minute of traffic, process measurements and the post-run parity check are retained. [Endurance artifacts](endurance/) · [GitHub Actions](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45570239053)

## Paired HTTP/1.1 release qualification

Both GitHub-hosted Ubuntu architectures passed the service gate at 800 offered requests/s. Eighteen trials across both architectures, the reference, the candidate and the observed candidate reached the required rate and met every per-operation target with zero failed responses and zero unsent arrivals. The overall p99 combines endpoints with different targets, including a deliberate application delay.

| Architecture | Overall p99, reference → candidate | CPU s per million successful requests | Peak RSS |
| --- | ---: | ---: | ---: |
| ARM64 | 51.855 → 52.081 ms | 107.460 → 107.446 | 35.652 → 34.516 MiB |
| x86_64 | 52.325 → 52.372 ms | 102.423 → 101.161 | 37.211 → 36.191 MiB |

All table entries are medians across three trials per variant. Median within-pair p99 change was +0.44% on ARM64 and −0.19% on x86_64. In-memory observation changed paired CPU cost by median +2.31% on ARM64 and +0.03% on x86_64. The identical-server 800/s control had median CPU s per million of 107.406 → 103.698 on ARM64 and 121.159 → 121.189 on x86_64.

At 3,200 offered requests/s, x86_64 did not qualify the load generator. ARM64 met the targets in two out of three reference trials, zero candidate trials and one observed candidate trial. Two default-candidate trials breached several 20 ms endpoint targets under delivered load. The third recorded 16 unsent arrivals and did not qualify. The ARM64 3,200 median CPU cost was similar between variants, despite differences in the request tail. Each trial recovered afterward. These outcomes limit the supported mix to the tested 800/s profile and motivate separate capacity profiling. The CPU model, load settings, failed and unqualified trials and separate TLS upgrade and rollback logs are retained. [Complete traces](pair/) · [GitHub Actions](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45570243060)

## Decision

Qualified for release within the stated HTTP/1.1 profile. The [same-commit Maven staging run](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45572662741) passed the independent JVM, macOS and both Linux application consumers. The [signing rehearsal](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45570252010) passed with a temporary key. The OSV query found no advisories in the 46 resolved coordinates checked. TLS ingress, upgrade, graceful shutdown and rollback checks passed. The performance scope is the tested fixture at 800 requests/s. The load generator shares the same VM and uses separate logical CPUs, so cross-framework production claims require measurements on independent hosts.

## Repeat of the 3,200 requests/s ARM64 case

A separate Ubuntu ARM64 VM on an Apple M3 Max ran five counterbalanced pairs per mode using the same reference and candidate binaries as the GitHub ARM64 job. The repeat used 12 measured seconds per trial. All 15 trials met the operation targets with zero failed responses and zero unsent arrivals. Overall median p99 was 55.167 → 55.224 ms and group median CPU-seconds per million was 88.012 → 87.224. The median within-pair CPU change was +0.30%, with results from −5.50% to +14.43%. This repeat did not reproduce the hosted high-rate failures. The hosts differ, so the two sets of evidence are reported together and the qualification remains at 800 offered requests/s. [Raw repeat](arm-high-rate/)
