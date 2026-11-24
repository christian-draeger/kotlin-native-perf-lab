@file:OptIn(
    ExperimentalStdlibApi::class,
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package codes.draeger.ktserve.example

import kotlin.concurrent.Volatile
import kotlin.native.runtime.GC
import kotlin.time.measureTime
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import platform.posix.CLOCK_PROCESS_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.timespec

// Publish each object or buffer so the compiler cannot discard the measured work.
@Volatile
private var sink: Any? = null

fun runJsonProbe(args: Array<String>) {
    if (args.firstOrNull() == "http") {
        runJsonHttpProbe(args)
        return
    }
    require(args.size == 2 || args.size == 4) {
        "Use small|catalog fixture or small|catalog construct|string|utf8|complete timing|heap iterations"
    }
    when (args[0]) {
        "small" -> probe(args, User.serializer()) { benchmarkUser(42) }
        "catalog" -> probe(args, Catalog.serializer()) { benchmarkCatalog(123) }
        else -> error("Unsupported JSON payload")
    }
}

private fun <T : Any> probe(args: Array<String>, serializer: KSerializer<T>, construct: () -> T) {
    val fixture = construct()
    val encoded = Json.encodeToString(serializer, fixture)
    if (args[1] == "fixture") {
        require(args.size == 2)
        print(encoded)
        return
    }
    require(args.size == 4)
    val operation: () -> Any = when (args[1]) {
        "construct" -> construct
        "string" -> { { Json.encodeToString(serializer, fixture) } }
        "utf8" -> { { encoded.encodeToByteArray() } }
        "complete" -> { { Json.encodeToString(serializer, construct()).encodeToByteArray() } }
        else -> error("Unsupported JSON phase")
    }
    val iterations = args[3].toInt()
    require(iterations in 1..100_000)
    when (args[2]) {
        "timing" -> {
            repeat(5_000) { sink = operation() }
            GC.collect()
            val epoch = checkNotNull(GC.lastGCInfo).epoch
            val before = processCpuNanos()
            val elapsed = measureTime { repeat(iterations) { sink = operation() } }
            val cpu = processCpuNanos() - before
            val completedCollections = checkNotNull(GC.lastGCInfo).epoch - epoch
            println("wall_ns=${elapsed.inWholeNanoseconds},cpu_ns=$cpu,completed_gc_cycles=$completedCollections")
        }
        "heap" -> {
            require(iterations <= 1_024)
            repeat(1_024) { sink = operation() }
            GC.collect()
            val start = checkNotNull(GC.lastGCInfo)
            repeat(iterations) { sink = operation() }
            GC.collect()
            val end = checkNotNull(GC.lastGCInfo)
            check(end.epoch == start.epoch + 1) {
                "Unexpected collection inside the heap sample (${start.epoch} to ${end.epoch})"
            }
            val baseline = checkNotNull(start.memoryUsageAfter["heap"]).totalObjectsSizeBytes
            val peak = checkNotNull(end.memoryUsageBefore["heap"]).totalObjectsSizeBytes
            check(peak >= baseline) { "Invalid managed-heap sample" }
            println("managed_heap_growth_bytes=${peak - baseline}")
        }
        else -> error("Unsupported measurement kind")
    }
}

private fun processCpuNanos(): Long = memScoped {
    val timestamp = alloc<timespec>()
    check(clock_gettime(CLOCK_PROCESS_CPUTIME_ID, timestamp.ptr) == 0)
    timestamp.tv_sec * 1_000_000_000L + timestamp.tv_nsec
}
