package codes.draeger.ktserve.benchmark

import codes.draeger.ktserve.MethodRoutes
import codes.draeger.ktserve.Request
import codes.draeger.ktserve.json
import codes.draeger.ktserve.serve
import codes.draeger.ktserve.text
import kotlin.time.measureTimedValue
import kotlinx.serialization.Serializable

@Serializable
private data class Item(val resource: Int, val id: Int)

fun main(args: Array<String>) = routingMain(args)

fun routingMain(args: Array<String>) {
    require(args.size == 3) { "Usage: routing-probe http RESOURCES PORT | match RESOURCES ITERATIONS" }
    val resources = args[1].toInt()
    require(resources > 0)
    when (args[0]) {
        "http" -> serve(port = args[2].toInt()) {
            get("/health") { text("ok") }
            repeat(resources) { resource ->
                val prefix = "/api/v1/r$resource/items"
                get("$prefix/current") { json(Item(resource, 0)) }
                get("$prefix/{id}") { request -> json(Item(resource, requireNotNull(request.pathInt("id")))) }
            }
        }
        "match" -> measureMatching(resources, args[2].toInt())
        else -> error("Expected http or match")
    }
}

private fun measureMatching(resources: Int, iterations: Int) {
    require(iterations > 0)
    val (routes, registration) = measureTimedValue {
        MethodRoutes<Int>().apply {
            repeat(resources) { resource ->
                val prefix = "/api/v1/r$resource/items"
                add("$prefix/current", resource)
                add("$prefix/{id}", resource)
            }
        }
    }
    val requestCount = 1024
    val requests = Array(requestCount) { index ->
        val resource = (index * 137 + 29) % resources
        val path = when (index % 8) {
            0 -> "/api/v1/missing/items/$index"
            1 -> "/api/v1/r$resource/items/current"
            else -> "/api/v1/r$resource/items/$index"
        }
        Request("GET", path, null)
    }
    val expected = LongArray(requestCount) { index ->
        val resource = (index * 137 + 29) % resources
        when (index % 8) {
            0 -> -1
            1 -> resource.toLong()
            else -> (resource + index).toLong()
        }
    }
    fun run(count: Int): Long {
        var checksum = 0L
        repeat(count) { index ->
            val request = requests[index % requestCount]
            checksum += routes.resolve(request) { resource, matched ->
                resource + (matched.pathInt("id") ?: 0)
            } ?: -1
        }
        return checksum
    }
    val expectedChecksum = iterations / requestCount * expected.sum() + expected.take(iterations % requestCount).sum()
    run(iterations)
    val (checksum, elapsed) = measureTimedValue { run(iterations) }
    check(checksum == expectedChecksum) { "Dispatch checksum $checksum differs from $expectedChecksum" }
    println("routes=${resources * 2} iterations=$iterations checksum=$checksum nanos=${elapsed.inWholeNanoseconds} " +
        "registration_nanos=${registration.inWholeNanoseconds}")
}
