# Windows IOCP feasibility probe

This standalone Kotlin/Native executable checks whether the pinned toolchain can call Winsock and I/O completion ports on Windows. It binds a TCP listener to an ephemeral IPv4 loopback port, associates the accepted socket with a completion port, receives one byte through `WSARecv` and then cancels a second receive. It checks the connection key, operation pointer, byte count and cancellation status returned by the kernel.

Kotlin 2.4.21 targets `mingwX64` with the Native distribution's MinGW sysroot. The Gradle wrapper pins Gradle 9.7.0. CI uses Temurin 21 on Windows Server 2022 x64 and records the OS version and executable SHA-256. Kotlin/Native also supports cross-linking this target on macOS and Linux x86_64. Run the executable on Windows for runtime evidence. JetBrains lists `mingwX64` as Tier 3, which carries weaker testing and compatibility guarantees. The probe covers Windows x64. [Kotlin/Native targets and hosts](https://kotlinlang.org/docs/native-target-support.html)

Run from the repository root with PowerShell on Windows.

```powershell
./components/nativeserve/gradlew.bat -p components/nativeserve/experiments/windows-iocp runReleaseExecutableMingwX64
```

The C interop fixture owns Winsock initialization, sockets and the completion port. It keeps the `OVERLAPPED`, flags and receive storage alive until the matching kernel completion. `CancelIoEx` requests cancellation. The completion packet confirms its outcome before storage is reused. Cleanup closes the socket and drains any outstanding completion before returning. The production backend in [#56](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/56) will additionally need asynchronous accept, partial transfer handling, deadlines and bounded draining across multiple connections. [Winsock overlapped receives](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-wsarecv) · [I/O completion ports](https://learn.microsoft.com/en-us/windows/win32/fileio/i-o-completion-ports) · [Windows cancellation](https://learn.microsoft.com/en-us/windows/win32/fileio/canceling-pending-i-o-operations)
