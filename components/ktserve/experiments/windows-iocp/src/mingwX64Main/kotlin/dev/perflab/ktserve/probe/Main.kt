@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.ktserve.probe

fun main() {
    check(perflab_probe_iocp() == 0) { "Winsock/IOCP probe failed" }
    println("Winsock loopback receive and cancellation completed through IOCP")
}
