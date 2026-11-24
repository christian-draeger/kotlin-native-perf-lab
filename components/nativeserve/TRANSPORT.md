# NativeServe stream transport

The routing API configures handlers in the HTTP core. The core owns request framing, response encoding, per-connection HTTP state and server policy. It drives the internal `StreamTransport` interface. Platform backends implement that interface and own the listener, sockets, readiness registrations, kernel submission queues and native addressing. HTTP code receives connection IDs, operation IDs, byte counts and portable outcomes. Backends can log the underlying platform error with operation context.

The HTTP loop and contract live in shared Kotlin so the HTTP core and tests can run without native headers. Linux uses level-triggered epoll. Apple Silicon macOS uses kqueue.

## Operations and progress

| Operation | Completion |
| --- | --- |
| `accept` | Returns a connection ID. The loop owns the accepted connection from this point. |
| `read` | Returns bytes written into the submitted range. A count of zero signals peer EOF. |
| `write` | Returns bytes accepted from the submitted range. A successful write makes positive progress and may leave a suffix for the loop to submit. |
| Any submitted operation | Can fail with cancellation, connection loss or an I/O failure. An I/O failure on a connection ends use of that stream. |

Calls run on one event-loop thread. A submission returns `ACCEPTED`, `AT_CAPACITY` or `CLOSED`. A rejected submission leaves the caller free to reuse its buffer. Every accepted operation has a unique ID for the life of the instance and delivers one terminal outcome via `poll`, including when it completed during submission. The ID is retired after the callback. Connection IDs also stay unique for the life of the instance, including across OS descriptor reuse. Each connection supports at most one pending read and one pending write. The HTTP core can apply stricter backpressure, as it does when it stops reading while a response is queued. Submission for an occupied slot returns `AT_CAPACITY`.

Transfer callbacks report both operation and connection IDs so the HTTP loop can look up the connection directly. A listener failure has no connection ID.

`poll` waits for at most the supplied nonnegative millisecond timeout when no outcomes are ready. A backend may yield early after a bounded batch. Callbacks run serially and can submit or cancel work. The loop retains control of deadlines by bounding the poll timeout against its next monotonic deadline, checking expiry on return and requesting cancellation as needed. Recursive polling is outside the contract.

Readiness backends attempt nonblocking I/O and retain pending operations while the socket would block. Completion backends queue work and translate platform completions to the same outcomes. Both approaches can batch work and call the completion sink without allocating an event object per operation. The backend must account for pending operations, undelivered outcomes and accepted connections in its configured limits. If the queue is full, cancellation and close must still make progress.

## Cancellation and ownership

`cancel` is idempotent for the operation ID. Its terminal result may still be the original success or failure if the work finished before cancellation took effect. Backend events from retired operations or connections must be discarded. If a successful read or write completed after an application deadline, the core closes the stream when it can no longer use the resulting byte counts safely.

After `closeListener`, new accepts return `CLOSED` and outstanding accepts are cancelled. An accept that already won the race can still yield a connection, which the draining loop closes. `closeConnection` stops new work on that connection and cancels its outstanding operations. Close requests are idempotent. Final `close` stops delivery, cancels outstanding work, closes every owned handle and reclaims the buffers only after the kernel releases them. It can drain platform completions internally to do this. No callback remains after final `close` returns.

For an accepted read, the transport exclusively borrows the requested byte range. For an accepted write, the range remains immutable. No overlapping operations may own ranges when either operation can write to them. Reuse starts at the corresponding terminal callback, or when final `close` returns. Synchronous system calls can pin briefly. An asynchronous backend must keep kernel-visible storage stable for the full lifetime of the kernel access, using a durable pin or backend-owned storage. A cancel acknowledgement cannot release the original operation's memory. An adapter using a multi-event kernel operation must track its final event and any separate buffer-release notification before releasing storage. The shared interface exposes one outcome per submitted operation.

## Backend selection and verification

Linux selects epoll automatically. Use `NATIVESERVE_BACKEND=epoll` to request it explicitly. Startup reports the selected backend and IPv4 TCP capability in the server log, which the benchmark archives with each trial.

The JVM selects NIO. One owning thread uses a JDK `Selector` and nonblocking channels to wait for readiness and pass completion back to the shared HTTP loop. A transfer wraps the submitted heap array range in a `ByteBuffer` until completion or cancellation. JDK implementations can copy heap-buffer I/O through a temporary direct buffer during the system call. The adapter owns the keys, channels and pending transfers, with one read and one write per connection. The range becomes available to the shared code after the corresponding terminal callback. [Java NIO Selector contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Selector.html)

epoll owns the listener, poll descriptor and each accepted socket. A registration carries the unique connection ID. After closing a socket, that ID can only deliver cancellations for pending requests. A new socket receives a new ID even when Linux reuses the file descriptor. Each connection owns two reusable transfer slots, and the backend bounds queued work by its connection limit. A write can finish while processing a read event. Interest changes are applied after those callbacks, so a complete response followed by another read can keep its existing read registration. Buffers are pinned only during the synchronous system call.

The opt-in worker experiment opens one listening socket. Each worker owns a duplicate listener descriptor, a poll descriptor, and the sockets it accepts. Listener registrations use `EPOLLEXCLUSIVE`. The coordinator partitions the process connection limit among workers, and the handlers in the sample can run concurrently. Coroutines launch and join the fixed workers. Event arrays stay alive in the coordinator's native scope until the workers have joined.

macOS selects kqueue automatically. `NATIVESERVE_BACKEND=kqueue` requests it explicitly. The backend registers one one-shot read or write filter per pending socket operation. It stores the operation ID in `udata` and checks that ID against live work before every callback, including when a descriptor was reused during the same event batch. Reads consume buffered bytes before reporting EOF. Darwin sockets use `SO_NOSIGPIPE` and the backend pins Kotlin buffers only during `recv` and `send`. The `serve` scope owns the event arrays and the backend closes the descriptors before that scope returns. The macOS preview uses one event loop and numeric IPv4 bind addresses.

The shared fake exercises immediate and delayed completion, partial transfers, EOF, capacity, cancellation races, stale events and final shutdown without timing dependencies. The native fixture runs the same transport probe and TCP stress cases on Linux and macOS. Exploratory epoll boundary measurements record allocation, throughput, latency and CPU use.

[Linux epoll](https://man7.org/linux/man-pages/man7/epoll.7.html) · [Apple kqueue](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man2/kqueue.2.html) · [Linux io_uring cancellation](https://man7.org/linux/man-pages/man7/io_uring_cancelation.7.html) · [Windows overlapped I/O cancellation](https://learn.microsoft.com/en-us/windows/win32/fileio/canceling-pending-i-o-operations)
