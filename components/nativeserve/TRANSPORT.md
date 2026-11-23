# NativeServe stream transport

The routing API configures handlers in the HTTP core. The core owns request framing, response encoding, per-connection HTTP state and server policy. It drives the internal `StreamTransport` interface. Platform backends implement that interface and own the listener, sockets, readiness registrations, kernel submission queues and native addressing. HTTP code receives connection IDs, operation IDs, byte counts and portable outcomes. Backends can log the underlying platform error with operation context.

The existing Linux server remains the reference until the epoll extraction in [#49](https://github.com/christian-draeger/kotlin-native-perf-lab/issues/49). This contract lives in shared Kotlin so the HTTP core and tests can run without native headers.

## Operations and progress

| Operation | Completion |
| --- | --- |
| `accept` | Returns a connection ID. The loop owns the accepted connection from this point. |
| `read` | Returns bytes written into the submitted range. A count of zero signals peer EOF. |
| `write` | Returns bytes accepted from the submitted range. A successful write makes positive progress and may leave a suffix for the loop to submit. |
| Any submitted operation | Can fail with cancellation, connection loss or an I/O failure. An I/O failure on a connection ends use of that stream. |

Calls run on one event-loop thread. A submission returns `ACCEPTED`, `AT_CAPACITY` or `CLOSED`. A rejected submission leaves the caller free to reuse its buffer. Every accepted operation has a unique ID for the life of the instance and delivers one terminal outcome via `poll`, including when it completed during submission. The ID is retired after the callback. Connection IDs also stay unique for the life of the instance, including across OS descriptor reuse. Each connection supports at most one pending read and one pending write. The HTTP core can apply stricter backpressure, as it does when it stops reading while a response is queued. Submission for an occupied slot returns `AT_CAPACITY`.

`poll` waits for at most the supplied nonnegative millisecond timeout when no outcomes are ready. A backend may yield early after a bounded batch. Callbacks run serially and can submit or cancel work. The loop retains control of deadlines by bounding the poll timeout against its next monotonic deadline, checking expiry on return and requesting cancellation as needed. Recursive polling is outside the contract.

Readiness backends attempt nonblocking I/O and retain pending operations while the socket would block. Completion backends queue work and translate platform completions to the same outcomes. Both approaches can batch work and call the completion sink without allocating an event object per operation. The backend must account for pending operations, undelivered outcomes and accepted connections in its configured limits. If the queue is full, cancellation and close must still make progress.

## Cancellation and ownership

`cancel` is idempotent for the operation ID. Its terminal result may still be the original success or failure if the work finished before cancellation took effect. Backend events from retired operations or connections must be discarded. If a successful read or write completed after an application deadline, the core closes the stream when it can no longer use the resulting byte counts safely.

After `closeListener`, new accepts return `CLOSED` and outstanding accepts are cancelled. An accept that already won the race can still yield a connection, which the draining loop closes. `closeConnection` stops new work on that connection and cancels its outstanding operations. Close requests are idempotent. Final `close` stops delivery, cancels outstanding work, closes every owned handle and reclaims the buffers only after the kernel releases them. It can drain platform completions internally to do this. No callback remains after final `close` returns.

For an accepted read, the transport exclusively borrows the requested byte range. For an accepted write, the range remains immutable. No overlapping operations may own ranges when either operation can write to them. Reuse starts at the corresponding terminal callback, or when final `close` returns. Synchronous system calls can pin briefly. An asynchronous backend must keep kernel-visible storage stable for the full lifetime of the kernel access, using a durable pin or backend-owned storage. A cancel acknowledgement cannot release the original operation's memory. An adapter using a multi-event kernel operation must track its final event and any separate buffer-release notification before releasing storage. The shared interface exposes one outcome per submitted operation.

## Backend selection and verification

When the interface is wired into startup, automatic selection uses the supported platform default. Linux starts with epoll. A later backend can become the default once its platform and capability checks pass. Keep normal `serve { … }` startup automatic. Provide an explicit experiment override that fails on an unsupported backend and expose the actual backend and enabled capabilities in startup diagnostics and benchmark metadata. The current contract requires TCP stream I/O and leaves optional platform acceleration internal until a portable caller needs a capability.

The shared fake exercises immediate and delayed completion, partial transfers, EOF, capacity, cancellation races, stale events and final shutdown without timing dependencies. Backend integration adds real TCP smoke tests and exploratory before/after measurements of the epoll boundary, including allocation, throughput, latency and CPU use.

[Linux epoll](https://man7.org/linux/man-pages/man7/epoll.7.html) · [Apple kqueue](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/kqueue.2.html) · [Linux io_uring cancellation](https://man7.org/linux/man-pages/man7/io_uring_cancelation.7.html) · [Windows overlapped I/O cancellation](https://learn.microsoft.com/en-us/windows/win32/fileio/canceling-pending-i-o-operations)
