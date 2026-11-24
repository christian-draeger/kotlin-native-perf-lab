@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.fixture

import dev.perflab.nativeserve.transport.ipv4SocketAddress
import dev.perflab.nativeserve.transport.openPlatformTransport
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket

internal fun probeTransport(port: Int): Unit = memScoped {
    val address = ipv4SocketAddress("127.0.0.1", port)
    val transport = openPlatformTransport("127.0.0.1", port, connectionLimit = 2)
    probeTransport(transport) {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        if (connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
            close(fd)
            error("Could not connect transport probe")
        }
        object : ProbeSocket {
            override fun sendByte(value: Byte): Unit = memScoped {
                val data = alloc<ByteVar>()
                data.value = value
                check(send(fd, data.ptr, 1.convert(), 0) == 1L)
            }

            override fun close() {
                close(fd)
            }
        }
    }
}
