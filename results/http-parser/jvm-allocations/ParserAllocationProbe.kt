package codes.draeger.ktserve

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ParserAllocationProbe {
    @Test
    fun measureAllocatedBytesOnOneJvmThread() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val input = ("GET /health HTTP/1.1\r\nHost: localhost:18080\r\nUser-Agent: wrk\r\n" +
            "Accept: */*\r\nX-Note: sample value\r\n\r\n").encodeToByteArray()
        val buffer = RequestBuffer(1024)
        val retained = arrayOfNulls<Request>(1024)
        val seen = arrayOfNulls<String>(1024)

        fun parse(count: Int, readHeader: Boolean) {
            repeat(count) { index ->
                input.copyInto(buffer.bytes)
                buffer.append(input.size)
                val parsed = buffer.parse() as ParsedHead.Complete
                val slot = index % retained.size
                retained[slot] = parsed.request
                if (readHeader) seen[slot] = parsed.request.header("X-Note")
                buffer.consume(parsed.consumed)
            }
        }

        val rounds = 200_000
        for (readHeader in listOf(false, true)) {
            parse(rounds * 2, readHeader)
            repeat(5) {
                val start = bean.getThreadAllocatedBytes(thread)
                parse(rounds, readHeader)
                val allocated = bean.getThreadAllocatedBytes(thread) - start
                assertEquals("/health", retained.last()?.path)
                if (readHeader) assertEquals("sample value", seen.last())
                println("allocation read_header=$readHeader round=${it + 1} iterations=$rounds total_bytes=$allocated bytes_per_request=${allocated.toDouble() / rounds}")
            }
        }
    }
}
