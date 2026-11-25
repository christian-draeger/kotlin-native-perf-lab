@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.transport

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLL_CLOEXEC
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_DEL
import platform.linux.EPOLL_CTL_MOD
import platform.linux.EPOLLET
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.ECONNABORTED
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_CLOEXEC
import platform.posix.SOCK_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.TCP_NODELAY
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getenv
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket

private const val BATCH_SIZE = 64
private const val LISTENER_TOKEN = 0UL
private const val APPLICATION_TOKEN = ULong.MAX_VALUE
// EPOLLEXCLUSIVE from Linux's eventpoll UAPI is absent from the linuxX64 sysroot bindings.
private const val EPOLL_EXCLUSIVE = 0x1000_0000U

internal fun MemScope.openPlatformTransport(
    host: String,
    port: Int,
    connectionLimit: Int,
): StreamTransport = openEpollTransport(
    connectionLimit, EPOLLIN,
    tcpNoDelay = configuredTcpNoDelay(),
    edgeTriggered = configuredEdgeTrigger(),
    wakeOnApplicationProgress = configuredApplicationWakeup(),
) { openListener(host, port) }

internal fun MemScope.openEpollTransport(
    host: String,
    port: Int,
    connectionLimit: Int,
    tcpNoDelay: Boolean,
): StreamTransport = openEpollTransport(connectionLimit, EPOLLIN, tcpNoDelay) { openListener(host, port) }

internal fun MemScope.openWorkerEpollTransports(
    host: String,
    port: Int,
    workers: Int,
    connectionLimit: Int,
    reusePort: Boolean,
): List<StreamTransport> {
    val tcpNoDelay = configuredTcpNoDelay()
    val edgeTriggered = configuredEdgeTrigger()
    val wakeOnApplicationProgress = configuredApplicationWakeup()
    val transports = ArrayList<StreamTransport>(workers)
    val sharedBudget = if (reusePort) null else SharedConnectionBudget(connectionLimit)
    val sharedListener = if (reusePort) -1 else openListener(host, port)
    try {
        repeat(workers) { index ->
            val events = if (reusePort) EPOLLIN else EPOLLIN or EPOLL_EXCLUSIVE
            val limit = if (reusePort) {
                connectionLimit / workers + if (index < connectionLimit % workers) 1 else 0
            } else connectionLimit
            transports += openEpollTransport(
                limit, events, tcpNoDelay = tcpNoDelay, edgeTriggered = edgeTriggered,
                wakeOnApplicationProgress = wakeOnApplicationProgress, sharedBudget = sharedBudget,
            ) {
                if (reusePort) openListener(host, port, reusePort = true)
                else checked(fcntl(sharedListener, F_DUPFD_CLOEXEC, 0), "duplicate listener")
            }
        }
        return transports
    } catch (cause: Throwable) {
        transports.forEach(StreamTransport::close)
        throw cause
    } finally {
        if (sharedListener >= 0) close(sharedListener)
    }
}

private fun configuredTcpNoDelay(): Boolean {
    val mode = getenv("KTSERVE_EPOLL_TCP_NODELAY")?.toKString() ?: return false
    require(mode == "default" || mode == "on") { "KTSERVE_EPOLL_TCP_NODELAY must be default or on" }
    println("ktServe epoll tcp_nodelay=$mode")
    return mode == "on"
}

private fun configuredEdgeTrigger(): Boolean {
    val mode = getenv("KTSERVE_EPOLL_TRIGGER")?.toKString() ?: return false
    require(mode == "level" || mode == "edge") { "KTSERVE_EPOLL_TRIGGER must be level or edge" }
    println("ktServe epoll peer_trigger=$mode")
    return mode == "edge"
}

private fun configuredApplicationWakeup(): Boolean {
    val mode = getenv("KTSERVE_EPOLL_APPLICATION_WAKEUP")?.toKString() ?: return false
    require(mode == "poll" || mode == "eventfd") { "KTSERVE_EPOLL_APPLICATION_WAKEUP must be poll or eventfd" }
    println("ktServe epoll application_wakeup=$mode")
    return mode == "eventfd"
}

private fun MemScope.openEpollTransport(
    connectionLimit: Int,
    listenerEvents: UInt,
    tcpNoDelay: Boolean = false,
    edgeTriggered: Boolean = false,
    wakeOnApplicationProgress: Boolean = false,
    sharedBudget: SharedConnectionBudget? = null,
    open: () -> Int,
): StreamTransport {
    val events = allocArray<epoll_event>(BATCH_SIZE)
    val change = alloc<epoll_event>()
    // The event loop owns this option value and the accepted sockets for the enclosing scope.
    val noDelayValue = if (tcpNoDelay) alloc<IntVar> { value = 1 }.ptr else null
    memset(change.ptr, 0, sizeOf<epoll_event>().convert())
    val applicationCount = if (wakeOnApplicationProgress) alloc<ULongVar>().ptr else null
    val listener = open()
    val poller = epoll_create1(EPOLL_CLOEXEC)
    if (poller < 0) {
        val cause = errno
        close(listener)
        error("epoll_create1 failed (errno $cause)")
    }
    var applicationSignal: EpollApplicationSignal? = null
    try {
        if (applicationCount != null) {
            applicationSignal = EpollApplicationSignal()
            change.data.u64 = APPLICATION_TOKEN
            change.events = EPOLLIN
            checked(epoll_ctl(poller, EPOLL_CTL_ADD, applicationSignal.descriptor, change.ptr), "register eventfd")
        }
        return EpollTransport(
            listener, poller, events, change.ptr, connectionLimit, listenerEvents, noDelayValue, edgeTriggered,
            applicationSignal, applicationCount, sharedBudget,
        )
    } catch (cause: Throwable) {
        applicationSignal?.close()
        close(poller)
        close(listener)
        throw cause
    }
}

internal fun MemScope.ipv4SocketAddress(host: String, port: Int): sockaddr_in {
    val address = alloc<sockaddr_in>()
    memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
    address.sin_family = AF_INET.convert()
    address.sin_port = htons(port.toUShort())
    val octets = host.split('.')
    require(octets.size == 4) { "Expected a numeric IPv4 bind address" }
    val ipv4 = octets.fold(0U) { value, octet ->
        val number = octet.toUByteOrNull()
        require(number != null && octet.all { it in '0'..'9' }) { "Expected a numeric IPv4 bind address" }
        (value shl 8) or number.toUInt()
    }
    address.sin_addr.s_addr = htonl(ipv4)
    return address
}

internal fun MemScope.openListener(
    host: String,
    port: Int,
    nonBlocking: Boolean = true,
    reusePort: Boolean = false,
): Int {
    val address = ipv4SocketAddress(host, port)
    val mode = if (nonBlocking) SOCK_NONBLOCK else 0
    val listener = checked(socket(AF_INET, SOCK_STREAM or mode or SOCK_CLOEXEC, 0), "socket")
    try {
        val reuse = alloc<IntVar>()
        reuse.value = 1
        checked(setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert()), "setsockopt")
        if (reusePort) {
            checked(setsockopt(listener, SOL_SOCKET, SO_REUSEPORT, reuse.ptr, sizeOf<IntVar>().convert()), "SO_REUSEPORT")
        }
        checked(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()), "bind $host:$port")
        checked(listen(listener, 256), "listen")
    } catch (cause: Throwable) {
        close(listener)
        throw cause
    }
    return listener
}

private fun checked(result: Int, operation: String): Int {
    if (result < 0) error("$operation failed (errno $errno)")
    return result
}

private class SharedConnectionBudget(limit: Int) {
    private val available = AtomicInt(limit)

    fun tryAcquire(): Boolean {
        while (true) {
            val slots = available.load()
            if (slots == 0) return false
            if (available.compareAndSet(slots, slots - 1)) return true
        }
    }

    fun release(count: Int = 1) {
        available.fetchAndAdd(count)
    }
}

private class EpollTransport(
    private var listener: Int,
    private var poller: Int,
    private val events: CPointer<epoll_event>,
    private val change: CPointer<epoll_event>,
    private val connectionLimit: Int,
    private val listenerEvents: UInt,
    private val noDelayValue: CPointer<IntVar>?,
    private val edgeTriggered: Boolean,
    private val applicationSignal: EpollApplicationSignal?,
    private val applicationCount: CPointer<ULongVar>?,
    private val sharedBudget: SharedConnectionBudget?,
) : StreamTransport {
    override val signalApplicationProgress: (() -> Unit)? = applicationSignal?.let { it::signal }

    private val peers = mutableMapOf<ConnectionId, Peer>()
    private val work = ArrayDeque<Peer>()
    private var processing: Peer? = null
    private var nextConnection = 1L
    private var listenerWatched = false
    private var acceptOperation: OperationId? = null
    private var acceptCancelled = false

    override fun accept(operation: OperationId): Submission {
        if (listener < 0) return Submission.CLOSED
        if (acceptOperation != null) return Submission.AT_CAPACITY
        acceptOperation = operation
        return Submission.ACCEPTED
    }

    override fun read(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        return submit(peer, peer.read, operation, bytes, offset, length)
    }

    override fun write(connection: ConnectionId, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission {
        val peer = peers[connection] ?: return Submission.CLOSED
        return submit(peer, peer.write, operation, bytes, offset, length)
    }

    private fun submit(peer: Peer, slot: Transfer, operation: OperationId, bytes: ByteArray, offset: Int, length: Int): Submission {
        if (peer.fd < 0) return Submission.CLOSED
        if (slot.buffer != null) return Submission.AT_CAPACITY
        require(offset >= 0 && length > 0 && offset <= bytes.size - length)
        slot.operation = operation
        slot.buffer = bytes
        slot.offset = offset
        slot.length = length
        slot.firstAttempt = slot.writing
        queue(peer)
        return Submission.ACCEPTED
    }

    override fun cancel(operation: OperationId) {
        if (acceptOperation == operation) {
            acceptCancelled = true
            return
        }
        for (peer in peers.values) {
            val slot = when {
                peer.read.matches(operation) -> peer.read
                peer.write.matches(operation) -> peer.write
                else -> continue
            }
            if (slot.failure == null) slot.failure = TransportFailure.CANCELLED
            queue(peer)
            return
        }
    }

    override fun closeListener() {
        if (listener >= 0) {
            if (listenerWatched && listenerEvents != EPOLLIN) {
                // Other workers hold references to the same listening socket.
                watch(EPOLL_CTL_DEL, listener, LISTENER_TOKEN, 0U)
            }
            close(listener)
            listener = -1
            listenerWatched = false
            acceptCancelled = acceptOperation != null
        }
    }

    override fun closeConnection(connection: ConnectionId) {
        val peer = peers[connection] ?: return
        stop(peer, TransportFailure.CANCELLED)
        if (peer.pending) queue(peer)
        else if (!peer.queued && peer !== processing) retire(connection)
    }

    override fun poll(timeoutMillis: Int, completions: TransportCompletions) {
        if (poller < 0) return
        val hadAcceptCancellation = acceptCancelled
        if (hadAcceptCancellation) {
            val operation = checkNotNull(acceptOperation)
            acceptCancelled = false
            acceptOperation = null
            completions.failed(null, operation, TransportFailure.CANCELLED)
        }

        val queued = minOf(BATCH_SIZE, work.size)
        repeat(queued) {
            if (poller < 0) return
            val peer = work.removeFirst()
            peer.queued = false
            process(peer, 0U, completions)
        }

        if (poller < 0) return
        watchListener()
        val timeout = if (queued > 0 || hadAcceptCancellation || acceptCancelled) 0 else timeoutMillis
        val count = epoll_wait(poller, events, BATCH_SIZE, timeout)
        if (count < 0 && errno == EINTR) return
        checked(count, "epoll_wait")
        for (index in 0 until count) {
            if (poller < 0) return
            val event = events[index]
            val token = event.data.u64
            if (token == LISTENER_TOKEN) {
                acceptReady(completions)
            } else if (token == APPLICATION_TOKEN) {
                checkNotNull(applicationSignal).consume(checkNotNull(applicationCount))
            } else {
                val peer = peers[ConnectionId(token.toLong())] ?: continue
                if (edgeTriggered) peer.edgeReadiness = peer.edgeReadiness or event.events
                if (!peer.queued) process(peer, event.events, completions)
            }
        }
    }

    private fun acceptReady(completions: TransportCompletions) {
        repeat(BATCH_SIZE) {
            val operation = acceptOperation ?: return
            if (listener < 0 || acceptCancelled) return
            val fd = accept(listener, null, null)
            if (fd < 0) {
                when (errno) {
                    EINTR, ECONNABORTED -> return@repeat
                    EAGAIN -> return
                    else -> {
                        acceptOperation = null
                        completions.failed(null, operation, TransportFailure.IO_FAILURE)
                        return
                    }
                }
            }
            val id = adopt(fd, completions) ?: return@repeat
            acceptOperation = null
            completions.accepted(operation, id)
        }
    }

    private fun adopt(fd: Int, completions: TransportCompletions): ConnectionId? {
        if (!(sharedBudget?.tryAcquire() ?: (peers.size < connectionLimit))) {
            close(fd)
            completions.admissionRejected()
            return null
        }
        var adopted = false
        try {
            if (fcntl(fd, F_SETFL, O_NONBLOCK) < 0 || fcntl(fd, F_SETFD, FD_CLOEXEC) < 0) return null
            if (noDelayValue != null &&
                setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, noDelayValue, sizeOf<IntVar>().convert()) < 0
            ) {
                error("Accepted socket TCP_NODELAY failed (errno $errno)")
            }
            val id = ConnectionId(nextConnection++)
            peers[id] = Peer(id, fd)
            adopted = true
            return id
        } finally {
            if (!adopted) {
                close(fd)
                sharedBudget?.release()
            }
        }
    }

    private fun process(peer: Peer, ready: UInt, completions: TransportCompletions) {
        processing = peer
        try {
            val available = if (edgeTriggered) peer.edgeReadiness else ready
            if (available and (EPOLLERR or EPOLLHUP) != 0U) stop(peer, TransportFailure.CONNECTION_LOST)
            if (peer.read.failure != null || available and EPOLLIN != 0U) complete(peer, peer.read, completions)

            var writable = available and EPOLLOUT != 0U
            for (attempt in 0 until BATCH_SIZE) {
                val slot = peer.write
                if (slot.buffer == null || !(slot.failure != null || slot.firstAttempt || writable)) break
                writable = false
                if (!complete(peer, slot, completions)) break
            }
        } finally {
            processing = null
        }

        if (peer.fd >= 0) updateInterest(peer)
        if (peer.fd < 0 && !peer.pending) {
            retire(peer.id)
        } else if (peer.read.failure != null || peer.write.failure != null || peer.write.firstAttempt ||
            edgeTriggered && peer.interest and peer.edgeReadiness != 0U
        ) {
            // Preserve observed readiness across bounded batches without spinning on empty transfer slots.
            queue(peer)
        }
    }

    private fun complete(peer: Peer, slot: Transfer, completions: TransportCompletions): Boolean {
        val bytes = slot.buffer ?: return false
        slot.firstAttempt = false
        var count = 0
        if (slot.failure == null) {
            val transferred = bytes.usePinned { pinned ->
                if (slot.writing) {
                    send(peer.fd, pinned.addressOf(slot.offset), slot.length.convert(), MSG_NOSIGNAL)
                } else {
                    recv(peer.fd, pinned.addressOf(slot.offset), slot.length.convert(), 0)
                }
            }
            if (transferred < 0) {
                val cause = errno
                if (cause == EAGAIN || cause == EINTR) {
                    if (edgeTriggered) {
                        val direction = if (slot.writing) EPOLLOUT else EPOLLIN
                        peer.edgeReadiness = if (cause == EAGAIN) peer.edgeReadiness and direction.inv()
                        else peer.edgeReadiness or direction
                    }
                    return false
                }
            }
            if (transferred < 0 || slot.writing && transferred == 0L) {
                stop(peer, TransportFailure.CONNECTION_LOST)
            } else {
                count = transferred.toInt()
            }
        }

        val operation = slot.operation
        val failure = slot.failure
        slot.buffer = null
        slot.failure = null
        when {
            failure != null -> completions.failed(peer.id, operation, failure)
            slot.writing -> completions.wrote(peer.id, operation, count)
            else -> completions.read(peer.id, operation, count)
        }
        return true
    }

    private fun updateInterest(peer: Peer) {
        var interest = 0U
        if (peer.read.buffer != null && peer.read.failure == null) interest = interest or EPOLLIN
        if (peer.write.buffer != null && peer.write.failure == null) interest = interest or EPOLLOUT
        if (interest == peer.interest) return
        val action = when {
            interest == 0U -> EPOLL_CTL_DEL
            peer.interest == 0U -> EPOLL_CTL_ADD
            else -> EPOLL_CTL_MOD
        }
        val events = if (edgeTriggered && interest != 0U) interest or EPOLLET else interest
        if (watch(action, peer.fd, peer.id.value.toULong(), events) == 0) {
            peer.interest = interest
        } else {
            stop(peer, TransportFailure.IO_FAILURE)
        }
    }

    private fun watchListener() {
        if (listener < 0) return
        val needListener = acceptOperation != null && !acceptCancelled
        if (needListener == listenerWatched) return
        val action = if (needListener) EPOLL_CTL_ADD else EPOLL_CTL_DEL
        checked(watch(action, listener, LISTENER_TOKEN, listenerEvents), "register listener")
        listenerWatched = needListener
    }

    private fun watch(action: Int, fd: Int, token: ULong, interest: UInt): Int {
        change.pointed.data.u64 = token
        change.pointed.events = interest
        return epoll_ctl(poller, action, fd, change)
    }

    private fun queue(peer: Peer) {
        if (!peer.queued && peer !== processing) {
            peer.queued = true
            work.addLast(peer)
        }
    }

    private fun stop(peer: Peer, failure: TransportFailure) {
        if (peer.fd < 0) return
        close(peer.fd)
        peer.fd = -1
        if (peer.read.buffer != null && peer.read.failure == null) peer.read.failure = failure
        if (peer.write.buffer != null && peer.write.failure == null) peer.write.failure = failure
    }

    private fun retire(connection: ConnectionId) {
        if (peers.remove(connection) != null) sharedBudget?.release()
    }

    override fun close() {
        if (poller < 0) return
        closeListener()
        for (peer in peers.values) {
            if (peer.fd >= 0) close(peer.fd)
            peer.fd = -1
            peer.read.buffer = null
            peer.write.buffer = null
        }
        val retired = peers.size
        peers.clear()
        if (retired != 0) sharedBudget?.release(retired)
        work.clear()
        acceptOperation = null
        acceptCancelled = false
        applicationSignal?.close()
        close(poller)
        poller = -1
    }

    private class Peer(val id: ConnectionId, var fd: Int) {
        val read = Transfer(writing = false)
        val write = Transfer(writing = true)
        var interest = 0U
        var edgeReadiness = 0U
        var queued = false

        val pending: Boolean
            get() = read.buffer != null || write.buffer != null
    }

    private class Transfer(val writing: Boolean) {
        var operation = OperationId(0)
        var buffer: ByteArray? = null
        var offset = 0
        var length = 0
        var firstAttempt = false
        var failure: TransportFailure? = null

        fun matches(id: OperationId): Boolean = buffer != null && operation == id
    }
}
