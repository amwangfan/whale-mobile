package com.wangfanlab.whale.transport.v2

import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentOutputFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store(limit: Int = 5) = AgentOutputFiles(folder.root, folder.root, folder.root, limit)

    @Test fun largeMultibyteTextRetainsLegacyTailAndCanBeFullyPaged() {
        val text = "鲸鱼🐋".repeat(10000)
        File(folder.root, "result").writeText(text)
        val legacy = store().read("agent:out:result")
        assertEquals(text.takeLast(5), legacy["text"])
        assertEquals(true, legacy["truncated"])
        val all = java.io.ByteArrayOutputStream()
        var offset = 0L
        do {
            val page = store().read("agent:out:result", OutputPages.Request(offset, 997))
            all.write(Base64.getDecoder().decode(page["bytes_b64"] as String))
            offset = page["next_offset"] as Long
        } while (page["truncated"] == true)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), all.toByteArray())
    }

    @Test fun badAndMissingReferencesHaveSameErrorsForBothReadModes() {
        for (page in listOf(null, OutputPages.Request(0, 1))) {
            assertEquals("bad_ref", store().read("agent:out:../secret", page)["error"])
            assertEquals("bad_ref", store().read("agent:ui:invalid", page)["error"])
            assertEquals("not_found", store().read("agent:out:missing", page)["error"])
        }
    }

    @Test fun exactTailBoundaryAndImagesKeepTheirOldShapes() {
        File(folder.root, "result").writeText("12345")
        assertEquals(false, store().read("agent:out:result")["truncated"])
        File(folder.root, "shot-1.jpg").writeBytes(byteArrayOf(0, 1, -1))
        val image = store().read("agent:shot:shot-1.jpg")
        assertEquals("image_jpeg", image["content_type"])
        assertEquals(3L, image["size_bytes"])
        assertArrayEquals(byteArrayOf(0, 1, -1), Base64.getDecoder().decode(image["data_base64"] as String))
    }
}
