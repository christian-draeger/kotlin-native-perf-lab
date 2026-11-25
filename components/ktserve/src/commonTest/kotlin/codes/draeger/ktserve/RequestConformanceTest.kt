package codes.draeger.ktserve

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class RequestConformanceTest {
    @Test
    fun segmentedHeadsMatchWholeInputAndKeepOwnedFields() {
        val random = Random(187)
        repeat(128) { index ->
            val tag = random.nextInt().toUInt().toString(16)
            val firstBytes = "GET /items/$index?tag=$tag HTTP/1.1\r\nHost: example.test\r\nX-Tag: $tag\r\n\r\n".encodeToByteArray()
            val secondBytes = "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray()
            val combined = firstBytes + secondBytes
            val whole = RequestBuffer(combined.size)
            whole.add(combined)
            val expected = assertIs<ParsedHead.Complete>(whole.parse())

            val split = RequestBuffer(combined.size)
            var offset = 0
            while (offset < firstBytes.size) {
                val last = minOf(firstBytes.size - 1, offset + random.nextInt(1, 13))
                if (last == offset) break
                split.add(combined, offset, last)
                offset = last
                assertEquals(ParsedHead.Incomplete, split.parse())
            }
            split.add(combined, offset, combined.size)
            val actual = assertIs<ParsedHead.Complete>(split.parse())
            assertEquals(firstBytes.size, actual.consumed)
            assertEquals(expected.keepAlive, actual.keepAlive)
            assertEquals(expected.request.path, actual.request.path)
            assertEquals(expected.request.query, actual.request.query)
            split.consume(actual.consumed)
            assertEquals("/health", assertIs<ParsedHead.Complete>(split.parse()).request.path)
            assertEquals(expected.request.headers(), actual.request.headers())
            assertEquals(tag, actual.request.header("x-tag"))
        }
    }

    @Test
    fun incompleteInputRespectsTheConfiguredByteLimit() {
        val random = Random(181)
        repeat(64) {
            val capacity = random.nextInt(32, 128)
            val buffer = RequestBuffer(capacity)
            repeat(capacity - 1) {
                buffer.add(byteArrayOf('A'.code.toByte()))
                assertEquals(ParsedHead.Incomplete, buffer.parse())
            }
            buffer.add(byteArrayOf('A'.code.toByte()))
            assertEquals(431, assertIs<ParsedHead.Rejected>(buffer.parse()).status)
        }
    }

    @Test
    fun generatedFieldValuesEnforceTheOctetGrammar() {
        val random = Random(20)
        val controls = (0..31).filter { it != 9 } + 127
        val prefix = "GET /health HTTP/1.1\r\nHost: localhost\r\nX-Note: ".encodeToByteArray()
        val delimiter = "\r\n\r\n".encodeToByteArray()
        repeat(128) {
            val value = ByteArray(random.nextInt(1, 32)) { random.nextInt(32, 127).toByte() }
            val text = RequestBuffer(128).apply { add(prefix + value + delimiter) }
            assertIs<ParsedHead.Complete>(text.parse())
            value[random.nextInt(value.size)] = controls[random.nextInt(controls.size)].toByte()
            val invalid = RequestBuffer(128).apply { add(prefix + value + delimiter) }
            // A generated control octet cannot complete a differently sized head.
            assertEquals(400, assertIs<ParsedHead.Rejected>(invalid.parse()).status)
        }
    }

    @Test
    fun chunkMetadataKeepsItsWireGrammarAndBodyBudget() {
        val correct = "3;name=token; Note = \"sample value\"\r\nabc\r\n0\r\nX-Note: done\r\n\r\n".encodeToByteArray()
        val buffer = RequestBuffer(128)
        buffer.add(correct)
        val spoken = mutableListOf<Byte>()
        val decoder = ChunkedBody(bodyBudget = 3, metadataBudget = correct.size)
        val progress = decoder.consume(buffer) { bytes, size ->
            spoken.addAll(bytes.take(size))
            true
        }
        assertEquals(BodyProgress.COMPLETE, progress)
        assertContentEquals("abc".encodeToByteArray(), spoken.toByteArray())
        assertEquals(0, buffer.size)

        for ((wire, result) in listOf(
            "3;\r\nabc\r\n0\r\n\r\n" to BodyProgress.INVALID,
            "3;x=\"unfinished\r\nabc\r\n0\r\n\r\n" to BodyProgress.INVALID,
            "4\r\nabcd\r\n0\r\n\r\n" to BodyProgress.OVERSIZE,
            "1;too-much-metadata=sample\r\na\r\n0\r\n\r\n" to BodyProgress.METADATA_LIMIT,
        )) {
            val input = RequestBuffer(128).apply { add(wire.encodeToByteArray()) }
            val budget = if (result == BodyProgress.METADATA_LIMIT) 15 else 64
            val invalid = ChunkedBody(bodyBudget = 3, metadataBudget = budget)
            var accepted = false
            val progress = invalid.consume(input) { _, _ ->
                accepted = true
                true
            }
            assertEquals(result, progress)
            assertFalse(accepted)
        }
    }

    private fun RequestBuffer.add(bytes: ByteArray, start: Int = 0, end: Int = bytes.size) {
        bytes.copyInto(this.bytes, destinationOffset = size, startIndex = start, endIndex = end)
        append(end - start)
    }
}
