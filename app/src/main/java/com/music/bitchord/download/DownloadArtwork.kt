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
        val canFetch = !AudioCache.isOffline() && SystemClock.elapsedRealtime() >= (misses.get(audio) ?: 0L)
        val supplied = song.thumbnailUrl?.takeIf(::webImage)
        MediaTagger.cacheArtwork(context, supplied, canFetch)?.let { return@withContext it }
        if (song.artist.isBlank() || song.artist.equals("Unknown Artist", true) || song.title.isBlank()) {
            return@withContext null
        }
        val known = matchingCover(song, knownSongs)
        if (known != supplied) MediaTagger.cacheArtwork(context, known, canFetch)?.let { return@withContext it }
        if (!canFetch) return@withContext null
        val cover = lookupCover(song, ::searchCatalogue)
        val result = cover?.takeUnless { it == known || it == supplied }?.let { MediaTagger.cacheArtwork(context, it) }
        if (result == null) misses.put(audio, SystemClock.elapsedRealtime() + RETRY_MS)
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

    internal suspend fun lookupCover(song: Song, search: suspend (String) -> List<Song>): String? {
        val query = TrackMatcher.queries(TrackMatcher.targetOf(song)).firstOrNull() ?: return null
        return matchingCover(song, search(query))
    }

    // MediaStore uses the containing directory as the album for untagged audio.
    private fun unknownAlbum(album: String?) =
        album.isNullOrBlank() || album.equals(DownloadStore.FOLDER, true) || album.equals("<unknown>", true)

    internal fun matchingCover(song: Song, candidates: List<Song>): String? {
        val target = TrackMatcher.targetOf(song).copy(album = song.albumName?.takeUnless(::unknownAlbum))
        val eligible = candidates.filter {
            it.thumbnailUrl?.let(::webImage) == true && TrackMatcher.sharesArtist(song.artist, it.artist) &&
                (target.album == null || target.album.equals(it.albumName, ignoreCase = true))
        }
        val ranked = TrackMatcher.ranked(eligible, target)
        if (TrackMatcher.hasConflictingAlbums(ranked, target)) return null
        return ranked.firstOrNull()?.thumbnailUrl
    }

    private fun webImage(url: String) = url.startsWith("https://") || url.startsWith("http://")
}
