package com.music.bitchord.download

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pulls a resolved stream onto disk.
 *
 * Two things here are not obvious and both are load-bearing:
 *
 *  - **Bounded ranges, not one long GET.** googlevideo paces a continuous
 *    response down to roughly playback speed — about 15kB/s, against 5.7MB/s
 *    for the same bytes asked for as ranges. That is the difference between a
 *    four-minute track saving in a second and saving in four minutes, and it is
 *    the same finding [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource]
 *    exists for.
 *  - **The client's own headers.** googlevideo bakes the identity that minted a
 *    URL into it as `c=`/`cver=` and compares that against the headers of the
 *    request that comes back for the bytes. Fetching with anything else is a
 *    403 — so the fetch is dressed as whatever the URL says made it, which
 *    [PlayerClient.forStreamUrl] can recover from the URL alone.
 *
 * Sequential rather than parallel. Ranges are served at line rate, and a
 * typical AAC track is four megabytes: splitting that across connections buys
 * nothing a user could perceive and costs the temp files and reassembly that a
 * cancelled download would then have to clean up.
 *
 * Configured sources use [ResumableTransfer] with ordinary GETs and validated
 * byte ranges on retry; they do not need YouTube's bounded-range pacing.
 */
object Downloader {

    private const val TAG = "BitChord"

    /** Matches the range size read-ahead settled on; large enough to amortise, small enough to cancel promptly. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024


    /** Progressive YouTube media keeps its rendition-specific checkpoint across retries. */
    internal suspend fun fetchResumable(
        videoId: String,
        initial: StreamResolver.Stream,
        maxKbps: Int,
        requireM4a: Boolean,
        file: java.io.File,
        onProgress: (Long, Long) -> Unit,
    ): java.io.File {
        var stream = initial
        var refreshed = false
        while (true) {
            try {
                val identity = youtubeIdentity(videoId, stream.url)
                return ResumableTransfer.download(
                    Http.client, stream.url, StreamResolver.mediaHeadersFor(stream.url), file, onProgress,
                    immutableIdentity = identity,
                    chunkBytes = identity?.let { minOf(CHUNK_BYTES, PlayerClient.rangeBytesFor(stream.url)) },
                )
            } catch (e: ResumableTransfer.HttpFailure) {
                if (e.status !in REFUSAL_CODES || refreshed) throw e
                StreamResolver.onPlaybackRefused(stream.url, e.status)
                refreshed = true
                stream = StreamResolver.resolveForDownload(videoId, maxKbps, requireM4a)
                check(stream.downloadExtension == initial.downloadExtension) { "The download format changed; try again" }
                // A changed rendition causes ResumableTransfer to discard its old
                // checkpoint. Matching byte counts alone never authorize a splice.
            }
        }
    }

    internal fun youtubeIdentity(videoId: String, url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.host.endsWith(".googlevideo.com")) return null
        val values = listOf("id", "itag", "lmt", "clen").map { parsed.queryParameter(it)?.takeIf(String::isNotEmpty) ?: return null }
        return ResumableTransfer.hash("youtube:$videoId:${values.joinToString(":")}")
    }
    /**
     * A container a source's file can be kept in, named for what its opening
     * bytes say rather than for what the source called it.
     *
     * The bytes are the only witness worth believing here. Sources label
     * loosely — `aac` for both raw ADTS and AAC-in-MP4, `flac` on an HLS
     * playlist, nothing at all on an extensionless URL — and a download has to
     * name the file before it is written. Filed under the wrong extension, a
     * file either won't open or opens as the wrong thing.
     *
     * AIFF is deliberately absent: the player has no extractor for it, so a
     * saved AIFF would be a download that can never be played back here.
     *
     * @param codec the codec this container settles on its own, or null when
     *   it can carry several and only the source's own word can say which.
     */
    internal enum class Container(val extension: String, val mimeType: String, val codec: String?) {
        FLAC("flac", "audio/flac", "flac"),
        WAV("wav", "audio/x-wav", "wav"),
        MP4("m4a", "audio/mp4", null),
        MP3("mp3", "audio/mpeg", "mp3"),
        ADTS("aac", "audio/aac", "aac"),
        OGG("ogg", "audio/ogg", null),
        WEBM("webm", "audio/webm", null),
    }

    /** What [probe] found at the end of a source's URL. */
    internal sealed interface Probe {
        /** Audio, in [container], [totalBytes] long when the server said. */
        data class File(val container: Container, val totalBytes: Long?) : Probe

        /** An index of the audio rather than the audio — HLS, or DASH when [dash]. */
        data class Manifest(val dash: Boolean) : Probe
    }

    /**
     * Read the opening of a source URL and say what is there, or null when it
     * is nothing a download can keep.
     *
     * Called on every candidate before it is allowed to win, not only on the
     * winner. Some backends label an HLS playlist as FLAC (the reference addon
     * serves one from an extensionless `/dash/{id}`), and fetched as a file
     * that is a tiny `.flac` no player can open, reported as a success. A
     * candidate that cannot be kept also must not win only to be thrown away,
     * because that sends the download to YouTube past a copy that could have
     * been kept.
     *
     * @param statedCodec what the source called it, which only breaks the tie
     *   for an ID3-prefixed file. The bytes decide everything else.
     */
    internal suspend fun probe(
        url: String,
        headers: Map<String, String>,
        statedCodec: String? = null,
    ): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            // Awaited rather than executed: this runs inside the download
            // lookup's race, which has a deadline to keep, and a blocking call
            // would hold that race open past it on a server that stalled.
            Http.client.newCall(request).awaitResponse().use { response ->
                if (response.code !in 200..299) {
                    Log.w(TAG, "probe refused (HTTP ${response.code})")
                    return@use null
                }
                // A 206 states the whole length after the slash; a 200 is a
                // server that ignored the range and is sending all of it.
                val total = if (response.code == 206) {
                    response.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                } else {
                    response.header("Content-Length")?.toLongOrNull()
                }?.takeIf { it > 0 }
                val source = response.body?.source() ?: return@use null
                source.request(PROBE_BYTES.toLong())
                val head = source.buffer.readByteArray(minOf(source.buffer.size, PROBE_BYTES.toLong()))
                sniff(head, response.header("Content-Type").orEmpty(), statedCodec, total)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "could not probe source file: ${it.message}")
        }.getOrNull()
    }

    /** [Call.execute], but given up on — and the call cancelled — when the coroutine is. */
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    /** [probe]'s judgement, apart from the request so it can be tested on bytes alone. */
    internal fun sniff(head: ByteArray, contentType: String, statedCodec: String?, totalBytes: Long?): Probe? {
        val type = contentType.lowercase()
        // Read byte for byte, so a UTF-8 byte-order mark arrives as its three
        // Latin-1 characters rather than as one.
        val text = String(head, Charsets.ISO_8859_1).removePrefix("ï»¿").trimStart()
        if ("mpegurl" in type || text.startsWith("#EXTM3U")) return Probe.Manifest(dash = false)
        if ("dash+xml" in type || "<MPD" in text) return Probe.Manifest(dash = true)
        return containerOf(head, statedCodec)?.let { Probe.File(it, totalBytes) }
    }

    private fun containerOf(head: ByteArray, statedCodec: String?): Container? {
        fun at(offset: Int, ascii: String) = head.size >= offset + ascii.length &&
            ascii.indices.all { head[offset + it] == ascii[it].code.toByte() }
        return when {
            at(0, "fLaC") -> Container.FLAC
            at(0, "RIFF") && at(8, "WAVE") -> Container.WAV
            at(4, "ftyp") -> Container.MP4
            at(0, "OggS") -> Container.OGG
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> Container.WEBM
            // An ID3 tag can run to megabytes of artwork, so what follows it is
            // usually out of reach. MP3 is what nearly always sits behind one;
            // a source that said AAC gets the benefit of the doubt.
            at(0, "ID3") -> if (statedCodec.equals("aac", ignoreCase = true)) Container.ADTS else Container.MP3
            head.size >= 2 && head[0] == 0xFF.toByte() -> {
                val second = head[1].toInt() and 0xFF
                when {
                    // ADTS: twelve sync bits, then layer bits that are always zero.
                    second and 0xF6 == 0xF0 -> Container.ADTS
                    // MPEG audio: eleven sync bits and a layer that isn't the reserved zero.
                    second and 0xE0 == 0xE0 && second and 0x06 != 0 -> Container.MP3
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Enough to see past an XML prolog to an `<MPD`, and far more than any magic number needs. */
    private const val PROBE_BYTES = 512

    private val REFUSAL_CODES = setOf(403, 404, 410)
}
