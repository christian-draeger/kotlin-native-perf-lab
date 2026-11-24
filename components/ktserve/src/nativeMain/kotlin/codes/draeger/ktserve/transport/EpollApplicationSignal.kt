@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package codes.draeger.ktserve.transport

import kotlin.concurrent.atomics.AtomicInt
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.convert
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import platform.linux.EFD_CLOEXEC
import platform.linux.EFD_NONBLOCK
import platform.linux.eventfd
import platform.linux.eventfd_read
import platform.linux.eventfd_write
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno

/** One nonblocking counter. Callbacks retain this heap object even after the loop's scope ends. */
internal class EpollApplicationSignal {
    private val leases = AtomicInt(0)
    private val drained = CompletableDeferred<Unit>()
    val descriptor = eventfd(0.convert(), EFD_NONBLOCK or EFD_CLOEXEC)

    init {
        check(descriptor >= 0) { "eventfd failed (errno $errno)" }
    }

    fun signal() {
        while (true) {
            val active = leases.load()
            if (active < 0) return
            if (leases.compareAndSet(active, active + 1)) break
        }
        try {
            updateCounter { eventfd_write(descriptor, 1UL) }
        } finally {
            if (leases.fetchAndAdd(-1) == CLOSING + 1) drained.complete(Unit)
        }
    }

    // Only the poll owner accesses the counter buffer from its native memory scope.
    fun consume(counter: CPointer<ULongVar>) = updateCounter { eventfd_read(descriptor, counter) }

    fun close() {
        // The sign bit stops callbacks from taking new leases. Finish writes before
        // closing the descriptor so a callback cannot hit a reused descriptor number.
        while (true) {
            val active = leases.load()
            if (active < 0) return
            if (!leases.compareAndSet(active, CLOSING + active)) continue
            if (active != 0) runBlocking { drained.await() }
            close(descriptor)
            return
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

    private companion object {
        const val CLOSING = Int.MIN_VALUE
    }
}
