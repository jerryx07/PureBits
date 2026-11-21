package com.music.bitchord.playback

import androidx.media3.common.MimeTypes
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LosslessReplayCacheTest {
    /** A disk index with independently controllable holes; no network or Android runtime. */
    private class Disk {
        val bytes = linkedMapOf<String, ByteArray>()
        val metadata = mutableMapOf<String, DefaultContentMetadata>()
        val holes = mutableSetOf<String>()
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { _, method, args ->
            val key = args?.firstOrNull() as? String
            when (method.name) {
                "getKeys" -> bytes.keys.toSet()
                "getContentMetadata" -> metadata[key] ?: DefaultContentMetadata.EMPTY
                "applyContentMetadataMutations" -> {
                    metadata[key!!] = (metadata[key] ?: DefaultContentMetadata.EMPTY)
                        .copyWithMutationsApplied(args!![1] as ContentMetadataMutations)
                    null
                }
                "isCached" -> key !in holes && (bytes[key]?.size ?: 0) >= (args!![1] as Long) + (args[2] as Long)
                else -> error("Unexpected cache call: ${method.name}")
            }
        } as Cache
        fun index() = LosslessReplayCache(cache) { key, limit -> bytes[key]?.take(limit)?.toByteArray() }
        fun put(key: String, mediaId: String? = null, content: String = "audio-bytes") {
            bytes[key] = content.toByteArray()
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, bytes.getValue(key).size.toLong())
            mediaId?.let { mutations.set("bc_media_id", it).set("bc_played", "1") }
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    @Test fun `completed upgraded and manually selected lossless copies survive a new session`() {
        for (key in listOf("song#hifi", "song#addon-random-marker")) {
            val disk = Disk()
            disk.put(key, "song")
            disk.index().decoded(key, "audio/flac", 24, 96000)
            val restarted = disk.index()
            assertEquals(key, restarted.best("song")?.key)
            assertTrue(key in restarted.protectedKeys(emptySet()))
        }
    }

    @Test fun `a high sample rate does not make lossy audio reusable`() {
        val disk = Disk()
        disk.put("song", "song")
        val index = disk.index()
        for (mime in listOf("audio/mpeg", "audio/mp4a-latm", "audio/opus", "audio/eac3-joc")) {
            index.decoded("song", mime, 24, 192000)
            assertNull(index.best("song"))
            assertFalse("song" in index.protectedKeys(emptySet()))
        }
    }

    @Test fun `temporary lossy buffer is protected only while its track is active`() {
        val disk = Disk()
        disk.put("song", "song")
        val index = disk.index()
        index.decoded("song", "audio/opus", null, 48000)
        assertTrue("song" in index.protectedKeys(setOf("song")))
        assertFalse("song" in index.protectedKeys(emptySet()))
    }

    @Test fun `known lossy streams do not write audio to disk`() {
        val disk = Disk()
        val index = disk.index()
        index.resolved("youtube", "https://cdn.example/song.webm", declaredLossless = false)
        assertFalse(index.mayWrite("youtube"))
        index.resolved("unknown", "https://cdn.example/song", declaredLossless = null)
        assertTrue(index.mayWrite("unknown"))
        index.decoded("unknown", "audio/mp4a-latm", 24, 96000)
        assertFalse(index.mayWrite("unknown"))
    }

    @Test fun `actual decoder evidence overrides an addon claiming lossless`() {
        val disk = Disk()
        val index = disk.index()
        index.resolved("song", "https://cdn.example/song.flac", declaredLossless = true)
        assertTrue(index.mayWrite("song"))
        index.decoded("song", "audio/opus", null, 48000)
        assertFalse(index.mayWrite("song"))
    }

    @Test fun `a cleared cache key cannot inherit codec proof from its previous recording`() {
        val disk = Disk()
        disk.put("song", "song")
        val index = disk.index()
        index.decoded("song", "audio/flac", 24, 96000)
        disk.bytes.remove("song")
        index.resolved("song", "https://cdn.example/new.mp3", declaredLossless = false)
        assertFalse(index.mayWrite("song"))
        assertNull(index.best("song"))
    }

    @Test fun `unknown audio and a file with holes are not complete lossless copies`() {
        val disk = Disk()
        disk.put("song", "song")
        val index = disk.index()
        assertNull(index.best("song"))
        index.decoded("song", "audio/alac", 16, 44100)
        disk.holes += "song"
        assertNull(index.best("song"))
        disk.holes.clear()
        assertEquals("song", index.best("song")?.key)
    }

    @Test fun `existing native flac can be recognized from its bytes without addon lookup`() {
        val disk = Disk()
        disk.put("song#alt", "song", "fLaC-native-content")
        val index = disk.index()
        index.inspect("song#alt")
        assertEquals("song#alt", index.best("song")?.key)
    }

    @Test fun `decoded lossy format outranks even a misleading flac prefix`() {
        val disk = Disk()
        disk.put("song", "song", "fLaC")
        val index = disk.index()
        index.decoded("song", "audio/mp4a-latm", 24, 96000)
        index.inspect("song")
        assertNull(index.best("song"))
    }

    @Test fun `select by measured quality and exact track identity instead of file size`() {
        val disk = Disk()
        val index = disk.index()
        disk.put("song#alt", "song", "large-file".repeat(100))
        index.decoded("song#alt", "audio/flac", 16, 44100)
        disk.put("song#hifi", "song", "small")
        index.decoded("song#hifi", "audio/flac", 24, 96000)
        disk.put("another#hifi", "another")
        index.decoded("another#hifi", "audio/flac", 32, 192000)
        assertEquals("song#hifi", index.best("song")?.key)
    }

    @Test fun `explicit rendition cannot be replaced by another cached recording`() {
        val disk = Disk()
        disk.put("song#hifi", "song")
        val index = disk.index()
        index.decoded("song#hifi", "audio/flac", 24, 96000)
        assertNull(index.best("song", exactKey = "song#addon-new-choice"))
        assertEquals("song#hifi", index.best("song", exactKey = "song#hifi")?.key)
    }

    @Test fun `search next source permanently excludes the old recording from replay selection`() {
        val disk = Disk()
        disk.put("song#old", "song")
        val index = disk.index()
        index.decoded("song#old", "audio/flac", 24, 192000)
        index.reject("song")
        assertNull(disk.index().best("song"))
        disk.put("song#new", "song")
        index.decoded("song#new", "audio/flac", 16, 44100)
        assertEquals("song#new", disk.index().best("song")?.key)
    }

    @Test fun `stereo request and watch copy cannot replace the selected mix`() {
        val disk = Disk()
        val index = disk.index()
        for (key in listOf("source|song", "source|song#stereo", "source|song#watch")) {
            disk.put(key, "source-song")
            index.decoded(key, "audio/flac", 24, 96000)
        }
        assertEquals("source|song#stereo", index.best("source-song", stereo = true)?.key)
        assertEquals("source|song", index.best("source-song", stereo = false)?.key)
    }

    private val hls = """
        #EXTM3U
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:5,
        part1.m4s
        #EXTINF:5,
        part2.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test fun `segmented song needs the entire playlist initialization and all audio segments`() {
        val disk = Disk()
        disk.put("song#hifi", "song", hls)
        val index = disk.index()
        index.resolved("song#hifi", "https://cdn.example/album/song.m3u8")
        index.decoded("song#hifi", "audio/flac", 24, 96000)
        index.inspect("song#hifi")
        assertNull(index.best("song"))
        for (part in listOf("init.mp4", "part1.m4s")) disk.put("https://cdn.example/album/$part")
        assertNull(index.best("song"))
        val last = "https://cdn.example/album/part2.m4s"
        disk.put(last)
        val copy = index.best("song")!!
        assertEquals(MimeTypes.APPLICATION_M3U8, copy.mime)
        index.activate(copy)
        assertTrue(index.isReplaySegment(last))
        assertTrue(last in index.protectedKeys(emptySet()))
        disk.holes += last
        assertNull(index.best("song"))
        assertFalse(last in index.protectedKeys(emptySet()))
    }

    @Test fun `live encrypted master and byte range playlists are not offered for offline replay`() {
        val url = "https://cdn.example/song.m3u8"
        for (unsupported in listOf(
            hls.replace("#EXT-X-ENDLIST", ""),
            "$hls\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"",
            "$hls\n#EXT-X-BYTERANGE:1024@0",
            "$hls\n#EXT-X-STREAM-INF:BANDWIDTH=1000",
        )) assertNull(losslessManifestParts(url, MimeTypes.APPLICATION_M3U8, unsupported))
    }

    @Test fun `static dash retains its complete chosen representation`() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT10S"><Period><AdaptationSet contentType="audio">
            <Representation id="flac" codecs="flac" bandwidth="1000000">
            <SegmentTemplate initialization="init.mp4" media="part${'$'}Number${'$'}.m4s" timescale="1">
            <SegmentTimeline><S d="5" r="1"/></SegmentTimeline></SegmentTemplate>
            </Representation></AdaptationSet></Period></MPD>"""
        assertEquals(listOf("https://cdn.example/init.mp4", "https://cdn.example/part1.m4s", "https://cdn.example/part2.m4s"),
            losslessManifestParts("https://cdn.example/song.mpd", MimeTypes.APPLICATION_MPD, mpd))
        assertNull(losslessManifestParts("https://cdn.example/song.mpd", MimeTypes.APPLICATION_MPD, mpd.replace("static", "dynamic")))
    }
}
