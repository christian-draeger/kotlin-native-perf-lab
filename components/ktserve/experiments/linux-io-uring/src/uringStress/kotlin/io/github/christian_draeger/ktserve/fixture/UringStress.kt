@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.christian_draeger.ktserve.fixture

import io.github.christian_draeger.ktserve.transport.openUringTransport
import kotlinx.cinterop.MemScope

fun uringStressMain(args: Array<String>) = runFixture(args) { port -> probeTransport(port, MemScope::openUringTransport) }
