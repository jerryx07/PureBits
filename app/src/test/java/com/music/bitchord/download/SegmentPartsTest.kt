package com.music.bitchord.download

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentPartsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `retry retains finished segments but changed manifest invalidates them`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val root = File(temp.root, "segments")
            val urls = listOf(server.url("/init").toString(), server.url("/part").toString())
            server.enqueue(MockResponse().setBody("init-bytes"))
            server.enqueue(MockResponse().setResponseCode(401))
            val first = SegmentParts(root, "manifest-one", urls, emptyMap())
            first.fetch(0)
            try { first.fetch(1); fail("Unauthorized segment must fail") } catch (_: ResumableTransfer.HttpFailure) { }
            assertEquals(0.5f, SegmentParts.fraction(root), 0.00001f)

            val retry = SegmentParts(root, "manifest-one", urls, emptyMap())
            assertEquals(1, retry.completed())
            assertEquals("init-bytes", retry.fetch(0).readText())
            assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setBody("media-bytes"))
            assertEquals("media-bytes", retry.fetch(1).readText())
            assertEquals(2, retry.completed())
            assertEquals(3, server.requestCount)

            val changed = SegmentParts(root, "manifest-two", urls, emptyMap())
            assertEquals(0, changed.completed())
            server.enqueue(MockResponse().setBody("different-init"))
            assertEquals("different-init", changed.fetch(0).readText())
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }
}
