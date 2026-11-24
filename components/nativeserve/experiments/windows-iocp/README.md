# Experimental NativeServe IOCP transport

This standalone Kotlin/Native executable checks whether the pinned toolchain can call Winsock and I/O completion ports on Windows. It binds a TCP listener to an ephemeral IPv4 loopback port, associates the accepted socket with a completion port, receives one byte through `WSARecv` and then cancels a second receive. It checks the connection key, operation pointer, byte count and cancellation status returned by the kernel.

Kotlin 2.4.21 targets `mingwX64` with the Native distribution's MinGW sysroot. The Gradle wrapper pins Gradle 9.7.0. CI uses Temurin 21 on Windows Server 2022 x64 and records the OS version and executable SHA-256. Kotlin/Native also supports cross-linking this target on macOS and Linux x86_64. Run the executable on Windows for runtime evidence. JetBrains lists `mingwX64` as Tier 3, which carries weaker testing and compatibility guarantees. The probe covers Windows x64. [Kotlin/Native targets and hosts](https://kotlinlang.org/docs/native-target-support.html)

Run from the repository root with PowerShell on Windows.

```powershell
./components/nativeserve/gradlew.bat -p components/nativeserve/experiments/windows-iocp runReleaseExecutableMingwX64
```

Run the experimental HTTP/1.x server with the same parity routes as the other backends. The optional positional arguments select port and IPv4 address. The executable defaults to IOCP and accepts the standard `NATIVESERVE_BACKEND=iocp` selector. It reports `NativeServe backend=iocp tcp=ipv4` after Winsock, the listener and IOCP are ready. Startup fails with a Windows error if those capabilities are unavailable.

```powershell
$env:NATIVESERVE_BACKEND = 'iocp'
./components/nativeserve/gradlew.bat -p components/nativeserve/experiments/windows-iocp runServerReleaseExecutableMingwX64
```

Check the server from a second terminal with `curl.exe -fsS http://127.0.0.1:8080/health`. Embedded callers retain the same `serve` and `stopRequested` API.

## Ownership and limits

The Windows backend uses Winsock 2.2, IOCP, `AcceptEx` with no initial data, `WSARecv`, `WSASend` and `CancelIoEx`. The shared HTTP state controls connection, request and response limits. The transport owns one pending accept and at most one pending receive and one pending send per connection. Closed connections count against capacity until their terminal operations arrive.

Every submitted operation owns its native `OVERLAPPED` and payload copy until IOCP returns a terminal packet. Sends copy from the borrowed Kotlin range before submission. Reads copy back only after a successful completion. Kotlin arrays are pinned during those short copies. A cancellation request can race with successful I/O. Closing a connection retires its pending HTTP operations and leaves the native storage live until the kernel finishes. Shutdown closes sockets, drains completions, then closes the port and releases Winsock. [AcceptEx](https://learn.microsoft.com/en-us/windows/win32/api/mswsock/nf-mswsock-acceptex) · [Winsock overlapped receives](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-wsarecv) · [I/O completion ports](https://learn.microsoft.com/en-us/windows/win32/fileio/i-o-completion-ports) · [Windows cancellation](https://learn.microsoft.com/en-us/windows/win32/fileio/canceling-pending-i-o-operations)

The Windows workflow exercises the common transport fixture and the HTTP suite with real Winsock sockets. It checks partial reads, cancellation and late outcomes, connection and body bounds, response streaming and graceful drain. The retained-heap checks also run on Windows. Descriptor-count checks use the Unix platform APIs. Keep the Windows comparison separate under [#57](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/57).
