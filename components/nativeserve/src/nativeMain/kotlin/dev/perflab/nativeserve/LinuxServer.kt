@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve

import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLL_CLOEXEC
import platform.linux.EPOLL_CTL_ADD
import platform.linux.EPOLL_CTL_MOD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.ECONNABORTED
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_CLOEXEC
import platform.posix.SOCK_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.htonl
import platform.posix.htons
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.time.TimeSource

fun serve(
    port: Int = 8080,
    host: String = "127.0.0.1",
    limits: ServerLimits = ServerLimits(),
    stopRequested: () -> Boolean = { false },
    configure: Routes.() -> Unit,
): Unit = memScoped {
    require(port in 1..65535) { "Port must be between 1 and 65535" }
    val routes = Routes().apply(configure)
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

    val listener = checked(socket(AF_INET, SOCK_STREAM or SOCK_NONBLOCK or SOCK_CLOEXEC, 0), "socket")
    try {
        val reuse = alloc<IntVar>()
        reuse.value = 1
        checked(setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert()), "setsockopt")
        checked(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()), "bind $host:$port")
        checked(listen(listener, 256), "listen")
    } catch (cause: Throwable) {
        close(listener)
        throw cause
    }
    runReactor(listener, routes, limits, stopRequested)
}

private fun checked(result: Int, operation: String): Int {
    if (result < 0) error("$operation failed (errno $errno)")
    return result
}

private fun runReactor(
    listener: Int,
    routes: Routes,
    limits: ServerLimits,
    stopRequested: () -> Boolean,
): Unit = memScoped {
    var accepting = true
    var poller = -1
    val clients = mutableMapOf<ULong, Client>()
    val clock = TimeSource.Monotonic.markNow()
    var nextId = 1UL
    var nextSweep = 0L
    var drainDeadline: Long? = null

    try {
        poller = checked(epoll_create1(EPOLL_CLOEXEC), "epoll_create1")
        val events = allocArray<epoll_event>(64)
        val change = alloc<epoll_event>()
        memset(change.ptr, 0, sizeOf<epoll_event>().convert())

        fun watch(operation: Int, fd: Int, token: ULong, events: UInt): Int {
            change.data.u64 = token
            change.events = events
            return epoll_ctl(poller, operation, fd, change.ptr)
        }

        checked(watch(EPOLL_CTL_ADD, listener, 0UL, EPOLLIN), "register listener")
        while (true) {
            val beforeWait = clock.elapsedNow().inWholeMilliseconds
            if (accepting && stopRequested()) {
                close(listener)
                accepting = false
                drainDeadline = beforeWait + limits.drainMillis
                val iterator = clients.values.iterator()
                while (iterator.hasNext()) {
                    val client = iterator.next()
                    if (!client.beginDrain()) {
                        close(client.fd)
                        iterator.remove()
                    }
                }
            }
            val deadline = drainDeadline
            if (deadline != null && (clients.isEmpty() || beforeWait >= deadline)) break
            val timeout = if (deadline == null) 250 else minOf(250L, deadline - beforeWait).toInt()
            val ready = epoll_wait(poller, events, 64, timeout)
            if (ready < 0 && errno == EINTR) continue
            checked(ready, "epoll_wait")
            val now = clock.elapsedNow().inWholeMilliseconds
            for (index in 0 until ready) {
                val event = events[index]
                val token = event.data.u64
                if (token == 0UL) {
                    if (!accepting) continue
                    for (attempt in 0 until 64) {
                        val fd = accept(listener, null, null)
                        if (fd < 0 && (errno == EINTR || errno == ECONNABORTED)) continue
                        if (fd < 0 && errno == EAGAIN) break
                        checked(fd, "accept")
                        if (clients.size >= limits.connections || fcntl(fd, F_SETFL, O_NONBLOCK) < 0 ||
                            fcntl(fd, F_SETFD, FD_CLOEXEC) < 0
                        ) {
                            close(fd)
                            continue
                        }
                        val id = nextId++
                        if (watch(EPOLL_CTL_ADD, fd, id, EPOLLIN) == 0) {
                            clients[id] = Client(fd, now, limits)
                        } else {
                            close(fd)
                        }
                    }
                } else {
                    val client = clients[token] ?: continue
                    val previousInterest = client.interest
                    val keep = event.events and (EPOLLERR or EPOLLHUP) == 0U && client.progress(routes, now)
                    if (!keep || previousInterest != client.interest &&
                        watch(EPOLL_CTL_MOD, client.fd, token, client.interest) != 0
                    ) {
                        clients.remove(token)
                        close(client.fd)
                    }
                }
            }
            if (now >= nextSweep) {
                val iterator = clients.values.iterator()
                while (iterator.hasNext()) {
                    val client = iterator.next()
                    if (client.expired(now)) {
                        close(client.fd)
                        iterator.remove()
                    }
                }
                nextSweep = now + 250
            }
        }
    } finally {
        clients.values.forEach { close(it.fd) }
        if (poller >= 0) close(poller)
        if (accepting) close(listener)
    }
}

private class Client(
    val fd: Int,
    connectedAt: Long,
    limits: ServerLimits,
) {
    private val http = HttpConnection(connectedAt, limits)

    val interest: UInt
        get() = if (http.output == null) EPOLLIN else EPOLLOUT

    fun expired(now: Long): Boolean = http.expired(now)

    fun beginDrain(): Boolean = http.beginDrain()

    fun progress(routes: Routes, now: Long): Boolean {
        if (!flush(now) || !consumeHeads(routes, now)) return false
        if (http.output != null) return true

        val input = http.input
        val count = input.bytes.usePinned { pinned ->
            recv(fd, pinned.addressOf(input.size), (input.bytes.size - input.size).convert(), 0)
        }
        if (count < 0) return errno == EAGAIN || errno == EINTR
        if (count == 0L) return false

        http.received(count.toInt(), now)
        return consumeHeads(routes, now)
    }

    private fun consumeHeads(routes: Routes, now: Long): Boolean {
        while (http.output == null && http.prepareResponse(routes, now)) {
            if (!flush(now)) return false
        }
        return true
    }

    private fun flush(now: Long): Boolean {
        val bytes = http.output ?: return true
        val count = bytes.usePinned { pinned ->
            send(fd, pinned.addressOf(http.outputOffset), (bytes.size - http.outputOffset).convert(), MSG_NOSIGNAL)
        }
        if (count < 0) return errno == EAGAIN || errno == EINTR
        if (count == 0L) return false
        return http.wrote(count.toInt(), now)
    }
}
