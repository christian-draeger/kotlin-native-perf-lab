@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.nativeserve.fixture

import dev.perflab.nativeserve.transport.openUringTransport
import kotlinx.cinterop.MemScope

fun uringStressMain(args: Array<String>) = runFixture(args) { port -> probeTransport(port, MemScope::openUringTransport) }
