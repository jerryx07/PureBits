package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object DownloadArtwork {
    private val misses by lazy { LruCache<String, Long>(512) }
    private const val RETRY_MS = 15 * 60_000L
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")

    suspend fun recover(context: Context, song: Song, knownSongs: List<Song>): String? = withContext(Dispatchers.IO) {
        val audio = song.localUri ?: return@withContext null
        val current = song.thumbnailUrl?.let(Uri::parse)
        if (current?.scheme == "file" && current.path?.let { File(it).length() > 0 } == true) {
            return@withContext song.thumbnailUrl
        }
        MediaTagger.embeddedArtwork(context, Uri.parse(audio))?.let { return@withContext it }
        if (current?.scheme == "content" && !(current.pathSegments.contains("albumart") && unknownAlbum(song.albumName))) {
            MediaTagger.cacheLocalArtwork(context, current)?.let { return@withContext it }
        }
        val offline = AudioCache.isOffline()
        if (offline) misses.remove(audio)
        val canFetch = !offline && SystemClock.elapsedRealtime() >= (misses.get(audio) ?: 0L)
        val result = lookupCover(song, knownSongs, canFetch,
            load = { MediaTagger.cacheArtwork(context, it, canFetch) },
            byId = { YtMusicRepository.trackLinks(it).getOrNull() },
            search = ::searchCatalogue)
        if (result == null && canFetch && !AudioCache.isOffline()) {
            misses.put(audio, SystemClock.elapsedRealtime() + RETRY_MS)
        }
        result
    }

    private suspend fun searchCatalogue(query: String): List<Song> =
        YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty().mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }

    internal suspend fun lookupCover(
        song: Song,
        knownSongs: List<Song>,
        allowNetwork: Boolean,
        load: (String) -> String?,
        byId: suspend (String) -> Song?,
        search: suspend (String) -> List<Song>,
    ): String? {
        val tried = mutableSetOf<String>()
        fun tryImage(url: String?): String? =
            url?.takeIf { webImage(it) && tried.add(it) }?.let(load)

        tryImage(song.thumbnailUrl)?.let { return it }
        val searchable = song.title.isNotBlank() && song.artist.isNotBlank() &&
            !song.artist.equals("Unknown Artist", true) && song.artist != "<unknown>"
        if (searchable) matchingCovers(song, knownSongs).firstNotNullOfOrNull(::tryImage)?.let { return it }
        if (!allowNetwork) return null
        if (youtubeId.matches(song.videoId)) {
            val exact = byId(song.videoId)?.takeIf { it.videoId == song.videoId }
            tryImage(exact?.thumbnailUrl)?.let { return it }
        }
        if (!searchable) return null
        val candidates = mutableListOf<Song>()
        for (query in TrackMatcher.queries(TrackMatcher.targetOf(song))) {
            candidates += search(query)
            matchingCovers(song, candidates).firstNotNullOfOrNull(::tryImage)?.let { return it }
        }
        return null
    }

    // MediaStore uses the containing directory as the album for untagged audio.
    private fun unknownAlbum(album: String?) =
        album.isNullOrBlank() || album.equals(DownloadStore.FOLDER, true) || album.equals("<unknown>", true)

    internal fun matchingCovers(song: Song, candidates: List<Song>): List<String> {
        val target = TrackMatcher.targetOf(song).copy(album = song.albumName?.takeUnless(::unknownAlbum))
        val eligible = candidates.filter {
            it.thumbnailUrl?.let(::webImage) == true && TrackMatcher.sharesArtist(song.artist, it.artist) &&
                (target.album == null || target.album.equals(it.albumName, ignoreCase = true))
        }
        val ranked = TrackMatcher.ranked(eligible, target)
        if (TrackMatcher.hasConflictingAlbums(ranked, target)) return emptyList()
        return ranked.mapNotNull { it.thumbnailUrl }.distinct()
    }

    private fun webImage(url: String) = url.startsWith("https://") || url.startsWith("http://")
}
