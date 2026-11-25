# Paired Linux ARM64 shared epoll admission diagnostic

Two shared epoll workers handled a mixed HTTP/1.1 workload on an Apple M3 Max in an OrbStack ARM64 Linux container with external networking disabled. Server processes had logical CPUs 0 and 1. k6 used CPUs 2 and 3. Both builds used identical arguments, payload checks, the same Go calibration fixture and GNU time. Each load tier had two scored repetitions per build with alternated ordering, 96 virtual users per operation, a 10-second cold phase, a 10-second warmup and a 10-second scored phase.

| Offered req/s | Old failed responses by trial | Candidate failed responses by trial | Candidate trials meeting all targets |
| ---: | --- | --- | --- |
| 200 | 84, 100 | 0, 0 | 2/2 |
| 800 | 157, 193 | 0, 0 | 2/2 |

The load generator qualified at both rates and reported zero dropped starts in all eight scored trials. Every candidate trial also passed recovery. All four old trials failed validation. Performance and resource numbers for successful responses appear in the raw rows for diagnosis. The old runs' failed requests prevent a fair efficiency comparison. This experiment establishes the local effect on admission for the tested app and rates. It ran on one shared machine.

The separate socket regression opened persistent HTTP connections sequentially. The previous ARM64 release with one worker passed the full 1,024-connection check, overflow, recovery and shutdown. With two shared workers it reset clients 544, 519 and 519 across three attempts. The candidate held all 1,024, rejected the overflow, admitted a replacement after an active socket closed and shut down cleanly. Its shared workers recorded 996 and 29 admissions including the replacement.

- [Old raw runs](old)
- [Candidate raw runs](candidate)
- [Generator calibration](calibration)
- [Scored readout](scored-trials.txt)
- [Environment](environment.json)
- [Input SHA-256 hashes](input.sha256)
- [Diagnostic orchestration](pair-admission.py)
- [Original hosted sweep supplying the old Linux ARM64 binary](https://github.com/christian-draeger/kotlin-native-perf-lab/actions/runs/45504842708)
- [Admission issue](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/178)

The candidate binary is the local Linux ARM64 release build of the listed candidate commit. The two binaries share Kotlin 2.4.21. The local execution used the ARM64 image from `ghcr.io/developmentseed/titiler@sha256:5b739117ff60d3cef9b0e0b8d9f4d628032c00a4965117e3f745cadc79fa7287` with image ID `sha256:89edf5b1294fe47093562deac91f4edfd1f48737c4b4cdb9019a547e6ff8070c`. The pinned k6 release archive SHA-256 was `7bb415f59d81ad649a86f6da6b3e1bd2e4e64e84909deef13e9bca70608abccb`. GNU time was extracted from the Ubuntu `time_1.9-0.2build1_arm64.deb` package with SHA-256 `9791f7c86477cc36d0723315915e806903f6676da23385e1011ca1b688897c98`.
