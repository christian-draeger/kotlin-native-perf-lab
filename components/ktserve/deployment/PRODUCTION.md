# Production qualification

The supported production profile is a standalone ktServe HTTP/1.1 origin on Linux x86_64 or ARM64, using one epoll worker and a loopback connection from the [TLS ingress recipe](http1/README.md). Select this topology explicitly with `serve(workers = 1)` in a Kotlin application or `KTSERVE_WORKERS=1` in the bundled executable. The qualification environment is Ubuntu 24.04. The release notes link the exact build and deployment evidence.

Use an explicit release dependency or a verified native archive. The JVM and macOS builds serve development and portability use cases. Framework adapters, additional Linux listeners, and the HTTP/2 transport have their own compatibility scopes.

## Configuration

Keep TLS at the ingress, restrict access to loopback, and use the documented Host and forwarding policy. The profile uses the defaults for connections, header and body size, idle, request and response deadlines, and drain unless the deployment evidence records a different choice. Choose size and time limits that fit the application's contract. Make larger limits or optional execution modes a separate qualification.

Keep synchronous route callbacks short. Use bounded blocking-handler admission or cancellable suspension for slow work. Enable the observer with a bounded destination. Alert on request errors, deadlines, resource pressure, and dropped telemetry. Use an external supervisor to restart a process when the transport reports a permanent listener failure.

## Promote a release

1. Check the eight implementation prerequisites in [the production release issue](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/189). Confirm release smoke, the JVM consumer, parser verification, bounded overload and recovery, and ingress checks for the intended revision.
2. Run **ktServe paired release qualification** against the previous qualified revision. Record the successful delivery and per-operation response targets at 800 offered requests per second in the mixed workload. Review paired p99, CPU cost, and peak RSS alongside cold-phase failures and dropped arrivals. Preserve measurements at higher offered rates with their calibration status. Review a sustained run and the upgrade and rollback logs. See the [benchmark protocol](../../../BENCHMARK_PROTOCOL.md#pair-production-releases).
3. Stage the intended version, verify the independent consumers, and run the [signing rehearsal](../RELEASING.md#rehearse-a-security-patch). Check the dependency evidence and the private reporting route.
4. Record the release decision and the source commit, checksums, supported setup, and evidence. Publish the matching tag and verify the artifacts through a fresh consumer. Roll back to the prior verified release and configuration if the deployment fails its health or request checks.

Patch releases preserve the documented API and configuration within a supported minor version. Check the release notes for changes when moving between minor versions. Report suspected security problems through the [private reporting process](../../../SECURITY.md).
