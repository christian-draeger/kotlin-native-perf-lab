@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.perflab.ktserve.fixture

import dev.perflab.ktserve.transport.openUringTransport
import kotlinx.cinterop.MemScope

fun uringStressMain(args: Array<String>) = runFixture(args) { port -> probeTransport(port, MemScope::openUringTransport) }
