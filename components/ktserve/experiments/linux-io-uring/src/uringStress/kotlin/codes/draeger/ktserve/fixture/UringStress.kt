@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package codes.draeger.ktserve.fixture

import codes.draeger.ktserve.transport.openUringTransport
import kotlinx.cinterop.MemScope

fun uringStressMain(args: Array<String>) = runFixture(args) { port -> probeTransport(port, MemScope::openUringTransport) }
