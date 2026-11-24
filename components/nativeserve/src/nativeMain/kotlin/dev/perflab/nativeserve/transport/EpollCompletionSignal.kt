@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.transport

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.linux.EFD_CLOEXEC
import platform.linux.EFD_NONBLOCK
import platform.linux.eventfd
import platform.linux.eventfd_read
import platform.linux.eventfd_write
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno

/** A nonblocking notification counter owned by one epoll loop. */
internal class EpollCompletionSignal {
    val descriptor = eventfd(0.convert(), EFD_NONBLOCK or EFD_CLOEXEC)
    private val lifecycle = Mutex()
    private var closed = false

    init {
        check(descriptor >= 0) { "eventfd failed (errno $errno)" }
    }

    suspend fun signal() {
        lifecycle.withLock {
            if (closed) return
            updateCounter { eventfd_write(descriptor, 1UL) }
        }
    }

    fun consume() = memScoped {
        val pending = alloc<ULongVar>()
        updateCounter { eventfd_read(descriptor, pending.ptr) }
    }

    // Only the owner consumes and closes the descriptor. Writers can race with
    // teardown, so closure shares their lock before the numeric fd can be reused.
    fun close() {
        runBlocking {
            lifecycle.withLock {
                closed = true
                close(descriptor)
            }
        }
    }

    private inline fun updateCounter(operation: () -> Int) {
        while (operation() != 0) {
            when (val code = errno) {
                EINTR -> continue
                EAGAIN -> return
                else -> error("eventfd counter operation failed (errno $code)")
            }
        }
    }
}
