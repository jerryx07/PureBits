package com.music.bitchord.playback

import androidx.media3.common.MimeTypes
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.music.bitchord.data.NerdStats
import com.music.bitchord.download.ManifestFile
import com.music.bitchord.download.OfflineDash
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/** A reusable entry is decoded lossless audio and every byte needed to play it. */
internal class LosslessReplayCache(
    private val cache: Cache,
    private val readBytes: (String, Int) -> ByteArray?,
) {
    data class Copy(val key: String, val mime: String?, val depth: Long, val rate: Long)
    private val replaySegments = ConcurrentHashMap.newKeySet<String>()
    private val segmentOwners = ConcurrentHashMap<String, String>()

    fun resolved(key: String, url: String, declaredLossless: Boolean? = null) {
        val mutations = ContentMetadataMutations().set(URL, url)
        if (declaredLossless == false) mutations.set(DECLARED_LOSSY, "1") else mutations.remove(DECLARED_LOSSY)
        if (!complete(key)) mutations.remove(REJECTED)
        if (!cache.isCached(key, 0, 1)) {
            mutations.remove(CODEC).remove(DEPTH).remove(RATE).remove(PARTS)
        }
        StreamContainer.manifestMimeOf(url)?.let { mutations.set(MANIFEST, it) }
            ?: mutations.remove(MANIFEST)
        cache.applyContentMetadataMutations(key, mutations)
    }

    fun decoded(key: String, mime: String?, depth: Int?, rate: Int?) {
        if (mime == null) return
        cache.applyContentMetadataMutations(key, ContentMetadataMutations()
            .set(CODEC, mime).set(DEPTH, depth?.toLong() ?: 0L).set(RATE, rate?.toLong() ?: 0L))
    }

    /** Called after a writer closes, never fetches a missing byte from the network. */
    fun inspect(key: String) {
        if (!complete(key)) return
        val meta = cache.getContentMetadata(key)
        if (meta.string(MEDIA_ID) == null) return
        if (meta.string(CODEC) == null) {
            val head = readBytes(key, 4)
            if (head?.contentEquals("fLaC".toByteArray()) == true) decoded(key, "audio/flac", null, null)
        }
        if (meta.string(PARTS) != null) return
        val mime = meta.string(MANIFEST) ?: return
        val url = meta.string(URL) ?: return
        if (ContentMetadata.getContentLength(meta) > MAX_MANIFEST_BYTES) return
        val text = readBytes(key, MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8) ?: return
        val parts = losslessManifestParts(url, mime, text) ?: return
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(PARTS, parts.joinToString("\n")))
        parts.forEach { segmentOwners[it] = key }
    }

    /** Unknown headers may buffer briefly; known lossy streams never fill the song cache. */
    fun mayWrite(key: String): Boolean {
        val owner = segmentOwners[key] ?: key
        val meta = cache.getContentMetadata(owner)
        return meta.string(CODEC)?.let(NerdStats::isLosslessMime) ?: (meta.string(DECLARED_LOSSY) != "1")
    }

    fun resetActivations() { replaySegments.clear() }

    fun copy(key: String): Copy? {
        val meta = cache.getContentMetadata(key)
        val mime = meta.string(MANIFEST)
        val parts = meta.string(PARTS)?.split('\n')?.filter(String::isNotBlank)
        if (!reusableLossless(
                decodedMime = meta.string(CODEC), complete = complete(key),
                rejected = meta.string(REJECTED) == "1", manifest = mime != null,
                partsComplete = !parts.isNullOrEmpty() && parts.all(::complete),
            )) return null
        return Copy(key, mime, meta.get(DEPTH, 0L), meta.get(RATE, 0L))
    }

    fun best(mediaId: String, exactKey: String? = null, stereo: Boolean? = null): Copy? = cache.keys.asSequence()
        .filter { key ->
            val meta = cache.getContentMetadata(key)
            meta.string(MEDIA_ID) == mediaId && !key.endsWith("#watch") &&
                (exactKey == null || key == exactKey) &&
                (stereo == null || key.contains("#stereo") == stereo) &&
                meta.string(PLAYED) != null
        }
        .mapNotNull(::copy)
        .maxWithOrNull(compareBy<Copy> { it.depth }.thenBy { it.rate })

    fun activate(copy: Copy) {
        parts(copy.key).forEach(replaySegments::add)
    }

    fun isReplaySegment(key: String) = key in replaySegments

    fun reject(mediaId: String) {
        cache.keys.filter { cache.getContentMetadata(it).string(MEDIA_ID) == mediaId }.forEach {
            replaySegments.removeAll(parts(it).toSet())
            cache.applyContentMetadataMutations(it, ContentMetadataMutations().set(REJECTED, "1"))
        }
    }

    fun protectedKeys(keep: Set<String>): Set<String> = buildSet {
        cache.keys.forEach { key ->
            val mediaId = cache.getContentMetadata(key).string(MEDIA_ID) ?: return@forEach
            inspect(key)
            if (mediaId in keep || copy(key) != null) {
                add(key)
                addAll(parts(key))
            }
        }
    }

    private fun parts(key: String) = cache.getContentMetadata(key).string(PARTS)
        ?.split('\n')?.filter(String::isNotBlank).orEmpty()

    private fun complete(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    private fun ContentMetadata.string(name: String): String? = get(name, null as String?)

    companion object {
        private const val CODEC = "pb_decoded_codec"
        private const val DEPTH = "pb_decoded_depth"
        private const val RATE = "pb_decoded_rate"
        private const val URL = "pb_cached_origin_url"
        private const val MANIFEST = "pb_cached_manifest"
        private const val PARTS = "pb_cached_segments"
        private const val REJECTED = "pb_rejected_recording"
        private const val DECLARED_LOSSY = "pb_declared_lossy"
        private const val MEDIA_ID = "bc_media_id"
        private const val PLAYED = "bc_played"
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
    }
}

internal fun reusableLossless(
    decodedMime: String?, complete: Boolean, rejected: Boolean, manifest: Boolean, partsComplete: Boolean,
): Boolean = NerdStats.isLosslessMime(decodedMime) && complete && !rejected && (!manifest || partsComplete)

/** Static, unencrypted audio playlists only. Unsupported plans are never marked complete. */
internal fun losslessManifestParts(url: String, mime: String, text: String): List<String>? = runCatching {
    val base = url.toHttpUrlOrNull() ?: return null
    val refs = when (mime) {
        MimeTypes.APPLICATION_MPD -> {
            if (Regex("type\\s*=\\s*[\"']dynamic[\"']", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                text.contains("<BaseURL", true) || text.contains("<Location", true) ||
                text.contains("<UTCTiming", true) || text.contains("xlink:href", true)) return null
            OfflineDash.parse(text).let { listOf(it.initialization) + it.media }
        }
        MimeTypes.APPLICATION_M3U8 -> {
            if (!text.contains("#EXT-X-ENDLIST") || text.contains("BYTERANGE") ||
                text.contains("#EXT-X-KEY") || text.contains("#EXT-X-STREAM-INF")) return null
            ManifestFile.hlsSegments(text).let { listOf(it.first) + it.second }
        }
        else -> return null
    }
    refs.map { base.resolve(it)?.toString() ?: return null }.distinct().takeIf { it.isNotEmpty() }
}.getOrNull()
