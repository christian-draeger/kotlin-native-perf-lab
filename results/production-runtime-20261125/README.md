# Runtime release diagnostics

This diagnostic used a local ARM64 Linux VM on Apple Silicon. It ran the unchanged application-mix capacity metric functions with release binaries and a single default ktServe worker. The server used CPUs 8–9 and the generator used CPUs 10–11. The trial order was counterbalanced. Other tasks used disjoint CPU masks on the same host, so these are exploratory results.

The initial run used a five-second cold window, five-second warm-up, and ten-second measurement window with two observations per cell. The 800 requests/s load qualified. Calibration did not qualify 3,200 requests/s. All raw data, including the unqualified tier, is retained under initial. Environment details, binaries and harness hashes are recorded beside the logs.

## Qualified initial results at 800 requests/s

All six trials passed response targets and recovery with zero failures or dropped arrivals.

| Release code | Successful req/s median | Overall p99 median | CPU seconds per million, both samples | Peak RSS median, KiB |
| --- | ---: | ---: | ---: | ---: |
| Baseline d567e14 | 800.70 | 55.08 ms | 72.437, 57.450 | 37,672 |
| Response deadlines and optional blocking routes ed0b0b5 | 800.65 | 54.22 ms | 63.694, 61.204 | 37,918 |
| Initial REST routing and policy 7d61cf3 | 800.55 | 55.46 ms | 176.118, 57.464 | 37,360 |

The #182 change showed no adverse signal in this limited comparison. One #188 CPU sample was substantially higher and also recorded roughly twice the generator CPU of the neighboring samples. The other sample closely matched baseline CPU. A follow-up with the final REST release is needed to distinguish a regression from run-to-run variation.
