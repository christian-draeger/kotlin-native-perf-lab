@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.fixture

import codes.draeger.ktserve.probe.ns_close_probe_socket
import codes.draeger.ktserve.probe.ns_connect_probe_socket
import codes.draeger.ktserve.probe.ns_send_probe_byte
import codes.draeger.ktserve.transport.openIocpTransport

internal fun probeTransport(port: Int) {
    val transport = openIocpTransport("127.0.0.1", port, connectionLimit = 2)
    probeTransport(transport) {
        val socket = ns_connect_probe_socket(port)
        check(socket != ULong.MAX_VALUE) { "Could not connect Windows transport probe" }
        object : ProbeSocket {
            override fun sendByte(value: Byte) {
                check(ns_send_probe_byte(socket, value) == 1)
            }

            override fun close() {
                ns_close_probe_socket(socket)
            }
        }
    }
}
