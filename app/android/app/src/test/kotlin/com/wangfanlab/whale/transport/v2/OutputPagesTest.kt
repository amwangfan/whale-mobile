package com.wangfanlab.whale.transport.v2

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64

class OutputPagesTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun oldQueryPreservesOriginalOutputShape() {
        val original = mapOf("text" to "old format", "content_type" to "text")
        val result = OutputPages.resolve("ref", mapOf("offset" to 0),
            { _, _, _ -> throw AssertionError("paging was not requested") }) { original }
        assertEquals(original, result)
    }

    @Test fun explicitPageFormatUsesPagedSource() {
        val result = OutputPages.resolve("ref", mapOf("page_format" to "bytes.v1", "offset" to 3.0),
            { ref, offset, limit -> mapOf("ref" to ref, "offset" to offset, "limit" to limit) }) {
            throw AssertionError("paged source is available")
        }
        assertEquals(mapOf("ref" to "ref", "offset" to 3L, "limit" to OutputPages.MAX_PAGE_BYTES), result)
    }

    @Test fun requestedPagesCanAdaptExistingOutputProvider() {
        val page = OutputPages.resolve("ref", mapOf("page_format" to "bytes.v1", "max_bytes" to 2), null) {
            mapOf("text" to "abcdef")
        }
        assertArrayEquals("ab".toByteArray(), Base64.getDecoder().decode(page["bytes_b64"] as String))
        assertEquals(true, page["truncated"])
    }

    class Source {
        fun readOutput(ref: String) = mapOf("text" to ref)
        fun readOutput(ref: String, offset: Long, maxBytes: Int) =
            mapOf("ref" to ref, "offset" to offset, "limit" to maxBytes)
    }

    @Test fun reflectiveHookSelectsPagedOverload() {
        val reader = OutputPages.reader(Source())!!
        assertEquals(mapOf("ref" to "ref", "offset" to 9L, "limit" to 12), reader("ref", 9, 12))
    }

    @Test fun missingPagedSourceKeepsLegacyFallbackAvailable() {
        assertNull(OutputPages.reader(Any()))
    }

    @Test fun largeUtf8AndBinaryRoundtrip() {
        val contents = ("鲸鱼🐋".repeat(20000)).toByteArray(Charsets.UTF_8) + byteArrayOf(0, -1, 13)
        val file = files.newFile().apply { writeBytes(contents) }
        val combined = ByteArrayOutputStream()
        var offset = 0L
        do {
            val page = OutputPages.read(file, OutputPages.Request(offset, 997))
            val bytes = Base64.getDecoder().decode(page["bytes_b64"] as String)
            assertTrue(bytes.size <= 997)
            assertEquals(offset, page["offset"])
            combined.write(bytes)
            offset = page["next_offset"] as Long
        } while (page["truncated"] == true)
        assertArrayEquals(contents, combined.toByteArray())
    }

    @Test fun eofAndBeyondEofAreEmpty() {
        val file = files.newFile()
        for (offset in listOf(0L, 100L)) {
            val page = OutputPages.read(file, OutputPages.Request(offset, 10))
            assertEquals("", page["bytes_b64"])
            assertEquals(false, page["truncated"])
        }
    }

    @Test fun requestAcceptsJsonNumbersAndClampsPageBudget() {
        assertEquals(OutputPages.Request(2, OutputPages.MAX_PAGE_BYTES),
            OutputPages.request(mapOf("offset" to 2.0, "max_bytes" to 1000000.0)))
        assertEquals(0L, OutputPages.request(emptyMap()).offset)
    }

    @Test fun invalidRequestsFail() {
        for (filters in listOf(mapOf("offset" to -1), mapOf("offset" to 0.5),
                mapOf("offset" to Double.NaN), mapOf("max_bytes" to true),
                mapOf("max_bytes" to 0), mapOf("offset" to "5"))) {
            try { OutputPages.request(filters); fail("accepted $filters") }
            catch (expected: IllegalArgumentException) { }
        }
    }

    @Test fun legacyOutputsArePagedWithoutLosingSourceTruncation() {
        val page = OutputPages.legacy(mapOf("text" to "abcdef", "truncated" to true),
            OutputPages.Request(2, 2))
        assertArrayEquals("cd".toByteArray(), Base64.getDecoder().decode(page["bytes_b64"] as String))
        assertEquals(4L, page["next_offset"])
        assertEquals(true, page["source_truncated"])
        assertEquals(mapOf("error" to "bad_ref"), OutputPages.legacy(
            mapOf("error" to "bad_ref"), OutputPages.Request(0, 1)))
    }
}
