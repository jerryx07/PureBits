package com.music.bitchord.download

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private val audio = ByteArray(128 * 1024) { ((it * 31 + it / 257) % 256).toByte() }
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(2, TimeUnit.SECONDS).build()
    private val url get() = server.url("/song.flac").toString()

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        file = File(temp.root, "audio.part")
    }

    @After fun teardown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun full(bytes: ByteArray = audio) = MockResponse().setBody(Buffer().write(bytes))
        .setHeader("ETag", "\"recording-one\"")

    private fun remainder(start: Long, bytes: ByteArray = audio) = MockResponse().setResponseCode(206)
        .setBody(Buffer().write(bytes, start.toInt(), bytes.size - start.toInt()))
        .setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
        .setHeader("ETag", "\"recording-one\"")

    private suspend fun fetch(
        target: String = url,
        progress: (Long, Long) -> Unit = { _, _ -> },
        retries: Int = 0,
        immutable: String? = null,
        chunk: Long? = null,
    ) = ResumableTransfer.download(client, target, emptyMap(), file, progress,
        immutableIdentity = immutable, chunkBytes = chunk, retries = retries, retryDelayMs = 0)

    private suspend fun interrupted(response: MockResponse = full(), immutable: String? = null): Long {
        server.enqueue(response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            fetch(immutable = immutable)
            fail("An interrupted response must not be considered complete")
        } catch (_: IOException) { }
        server.takeRequest()
        assertTrue(file.length() in 1 until audio.size.toLong())
        assertFalse(ResumableTransfer.isComplete(file))
        return file.length()
    }

    @Test fun `manual retry resumes at the actual saved byte and preserves the recording`() = runBlocking {
        val saved = interrupted()
        assertEquals(saved.toFloat() / audio.size, ResumableTransfer.fraction(file), 0.00001f)
        server.enqueue(remainder(saved))
        val progress = mutableListOf<Long>()
        fetch(progress = { written, _ -> progress += written })
        val request = server.takeRequest()
        assertEquals("bytes=$saved-", request.getHeader("Range"))
        assertEquals("\"recording-one\"", request.getHeader("If-Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals(saved, progress.first())
        assertArrayEquals(audio, file.readBytes())
        assertTrue(ResumableTransfer.isComplete(file))
    }

    @Test fun `network interruption retries automatically without repeating the prefix`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val offset = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toLong()
                return if (offset == null) full().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                else remainder(offset)
            }
        }
        fetch(retries = 1)
        assertEquals(2, server.requestCount)
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `ignored range replaces the checkpoint instead of appending a second file`() = runBlocking {
        val saved = interrupted()
        val changed = audio.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
        server.enqueue(full(changed).setHeader("ETag", "\"replacement\""))
        fetch()
        assertEquals("bytes=$saved-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(changed, file.readBytes())
    }

    @Test fun `wrong range offset is rejected without modifying saved bytes`() = runBlocking {
        val saved = interrupted()
        val before = file.readBytes()
        server.enqueue(remainder(saved + 1))
        try { fetch(); fail("Wrong offset must be rejected") } catch (_: IOException) { }
        assertArrayEquals(before, file.readBytes())
        assertFalse(ResumableTransfer.isComplete(file))
    }

    @Test fun `changed validator in partial response forces a fresh full download`() = runBlocking {
        val saved = interrupted()
        val changed = audio.reversedArray()
        server.enqueue(remainder(saved, changed).setHeader("ETag", "\"replacement\""))
        server.enqueue(full(changed).setHeader("ETag", "\"replacement\""))
        fetch()
        assertEquals("bytes=$saved-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(changed, file.readBytes())
    }

    @Test fun `unsatisfiable range restarts and never labels a partial file complete`() = runBlocking {
        interrupted()
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(full())
        fetch()
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `weak validator requires a full restart`() = runBlocking {
        interrupted(full().setHeader("ETag", "W/\"weak\""))
        server.enqueue(full())
        fetch()
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `unvalidated partial response fails instead of repeatedly downloading its prefix`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 0-1023/${audio.size}")
            .setBody(Buffer().write(audio, 0, 1024)))
        try { fetch(retries = 3); fail("Cannot safely request the remaining range") } catch (_: IOException) { }
        assertEquals(1, server.requestCount)
        assertFalse(ResumableTransfer.isComplete(file))
    }

    @Test fun `server failures exhaust retry budget while retaining previously downloaded bytes`() = runBlocking {
        val saved = interrupted()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        try { fetch(retries = 2); fail("Server is unavailable") } catch (_: ResumableTransfer.HttpFailure) { }
        assertEquals(saved, file.length())
        assertEquals(4, server.requestCount)
        repeat(3) { assertEquals("bytes=$saved-", server.takeRequest().getHeader("Range")) }
        server.enqueue(remainder(saved))
        fetch()
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `strong last modified date can validate a resumed download`() = runBlocking {
        val modified = "Wed, 21 Oct 2015 07:28:00 GMT"
        val saved = interrupted(full().removeHeader("ETag")
            .setHeader("Last-Modified", modified).setHeader("Date", "Wed, 21 Oct 2015 07:30:00 GMT"))
        server.enqueue(remainder(saved).removeHeader("ETag"))
        fetch()
        assertEquals(modified, server.takeRequest().getHeader("If-Range"))
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `changed source URL cannot reuse even a same sized checkpoint`() = runBlocking {
        interrupted()
        val changed = audio.reversedArray()
        server.enqueue(full(changed))
        fetch(target = server.url("/another-source.flac").toString())
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(changed, file.readBytes())
    }

    @Test fun `immutable rendition can resume after a signed URL expires`() = runBlocking {
        val saved = interrupted(full().removeHeader("ETag"), immutable = "youtube-rendition-one")
        server.enqueue(remainder(saved).removeHeader("ETag"))
        fetch(target = server.url("/song.flac?new-signature").toString(), immutable = "youtube-rendition-one")
        assertEquals("bytes=$saved-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `bounded ranges finish with the exact original bytes`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bounds = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val start = bounds[0].toInt()
                val end = minOf(bounds[1].toInt(), audio.lastIndex)
                return MockResponse().setResponseCode(206).setBody(Buffer().write(audio, start, end - start + 1))
                    .setHeader("Content-Range", "bytes $start-$end/${audio.size}")
            }
        }
        fetch(immutable = "rendition", chunk = 40_000)
        assertEquals(4, server.requestCount)
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `completed checkpoint can be copied again without another network request`() = runBlocking {
        server.enqueue(full())
        fetch()
        fetch()
        assertEquals(1, server.requestCount)
        assertArrayEquals(audio, file.readBytes())
    }

    @Test fun `unknown length can complete but an empty response cannot`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(audio), 4096))
        fetch()
        assertTrue(ResumableTransfer.isComplete(file))
        assertArrayEquals(audio, file.readBytes())
        server.enqueue(MockResponse().setBody(""))
        try { fetch(target = server.url("/empty").toString()); fail("Empty audio must fail") } catch (_: IOException) { }
        assertFalse(ResumableTransfer.isComplete(file))
    }

    @Test fun `cancel interrupts a blocked response without waiting for the socket timeout`() = runBlocking {
        server.enqueue(full().throttleBody(1024, 30, TimeUnit.SECONDS))
        val job = launch { fetch(retries = 3) }
        withTimeout(5000) { while (file.length() == 0L) delay(10) }
        withTimeout(1000) { job.cancelAndJoin() }
        assertFalse(ResumableTransfer.isComplete(file))
        assertEquals(1, server.requestCount)
    }

    @Test fun `youtube identity follows rendition not the expiring URL signature`() {
        val original = "https://rr1.googlevideo.com/videoplayback?id=track&itag=140&lmt=123&clen=456&sig=old"
        val identity = Downloader.youtubeIdentity("song", original)
        assertNotNull(identity)
        assertEquals(identity, Downloader.youtubeIdentity("song", original.replace("sig=old", "sig=new")))
        assertNotEquals(identity, Downloader.youtubeIdentity("song", original.replace("itag=140", "itag=251")))
        assertNotEquals(identity, Downloader.youtubeIdentity("song", original.replace("lmt=123", "lmt=124")))
        assertNull(Downloader.youtubeIdentity("song", original.replace("rr1.googlevideo.com", "addon.example")))
        assertNull(Downloader.youtubeIdentity("song", original.replace("&lmt=123", "")))
    }
}
