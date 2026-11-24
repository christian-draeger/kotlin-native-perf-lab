@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.perflab.ktserve.transport

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.linux.EFD_CLOEXEC
import platform.linux.EFD_NONBLOCK
import platform.linux.eventfd
import platform.linux.eventfd_read
import platform.posix.EAGAIN
import platform.posix.close
import platform.posix.errno

class EpollApplicationSignalTest {
    @Test
    fun closingDuringWritesLeavesTheReusedDescriptorUntouched(): Unit = runBlocking {
        repeat(32) {
            val signal = EpollApplicationSignal()
            val began = CompletableDeferred<Unit>()
            val writing = AtomicBoolean(true)
            val writer = launch(Dispatchers.Default) {
                signal.signal()
                began.complete(Unit)
                while (writing.load()) signal.signal()
                repeat(16) { signal.signal() }
            }
            try {
                began.await()
                signal.close()
                val replacement = reopen(signal.descriptor)
                try {
                    signal.close()
                    repeat(128) { signal.signal() }
                    writing.store(false)
                    writer.join()
                    memScoped {
                        val counter = alloc<ULongVar>()
                        val result = eventfd_read(replacement, counter.ptr)
                        val code = errno
                        assertEquals(-1, result)
                        assertEquals(EAGAIN, code, "A stale callback wrote to or closed the replacement descriptor")
                    }
                } finally {
                    close(replacement)
                }
            } finally {
                writing.store(false)
                signal.close()
                writer.join()
            }
        }
    }

    private fun reopen(descriptor: Int): Int {
        // Linux picks the lowest free descriptor. Keep any lower holes occupied until it reuses our fd.
        val fillers = mutableListOf<Int>()
        try {
            while (true) {
                val next = eventfd(0.convert(), EFD_NONBLOCK or EFD_CLOEXEC)
                assertTrue(next >= 0, "eventfd failed (errno $errno)")
                if (next == descriptor) return next
                fillers += next
                assertTrue(next < descriptor, "Expected the closed descriptor number to be reusable")
            }
        } finally {
            fillers.forEach { close(it) }
        }
    }
}
