package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadArtworkTest {
    private val file = Song("file", "ミズキリ", "優里", null, durationText = "3:28", albumName = "PureBits")
    private val recording = Song("catalogue", "ミズキリ - Mizukiri", "Yuuri", "https://example.org/original.jpg",
        durationText = "3:28", albumName = "First album")

    @Test fun missingPictureUsesTitleAndArtistToQueryTheCatalogue() = runBlocking {
        val queries = mutableListOf<String>()
        val result = DownloadArtwork.lookupCover(file, emptyList(), true, { it }, { error("local file ID") }) { query ->
            queries += query
            listOf(recording.copy(artist = "Unrelated", thumbnailUrl = "https://example.org/wrong.jpg"), recording)
        }
        assertEquals(listOf("ミズキリ 優里"), queries)
        assertEquals(recording.thumbnailUrl, result)
    }

    @Test fun matchesBilingualRecordingAndRejectsDifferentArtistOrTake() {
        assertEquals(listOf(recording.thumbnailUrl), DownloadArtwork.matchingCovers(file, listOf(recording)))
        assertEquals(emptyList<String>(), DownloadArtwork.matchingCovers(file, listOf(recording.copy(artist = "Cover Band"))))
        assertEquals(emptyList<String>(), DownloadArtwork.matchingCovers(file, listOf(recording.copy(title = "ミズキリ (Live)"))))
    }

    @Test fun selectsKnownReleaseAndLeavesAmbiguousEditionsAlone() {
        val compilation = recording.copy(albumName = "Compilation", thumbnailUrl = "https://example.org/compilation.jpg")
        assertEquals(emptyList<String>(), DownloadArtwork.matchingCovers(file, listOf(compilation, recording)))
        assertEquals(listOf(recording.thumbnailUrl), DownloadArtwork.matchingCovers(
            file.copy(albumName = recording.albumName), listOf(compilation, recording)))
    }

    @Test fun retriesAlternateQueryAndImageAfterAnExpiredThumbnail() = runBlocking {
        val expired = "https://example.org/expired.jpg"
        val stale = recording.copy(thumbnailUrl = expired)
        val queries = mutableListOf<String>()
        val images = mutableListOf<String>()
        val result = DownloadArtwork.lookupCover(file.copy(thumbnailUrl = expired), listOf(stale), true,
            load = { url -> images += url; "file:///covers/found.jpg".takeIf { url == recording.thumbnailUrl } },
            byId = { error("local file ID") }) { query ->
            queries += query
            if (query == "ミズキリ") listOf(stale, recording) else listOf(stale)
        }
        assertEquals("file:///covers/found.jpg", result)
        assertEquals(listOf("ミズキリ 優里", "ミズキリ"), queries)
        assertEquals(listOf(expired, recording.thumbnailUrl), images)
    }

    @Test fun originalCatalogueIdSuppliesArtEvenWithoutArtistTags() = runBlocking {
        val id = "abcdefghijk"
        val result = DownloadArtwork.lookupCover(file.copy(videoId = id, artist = "Unknown Artist"),
            emptyList(), true, { it }, { requested -> recording.copy(videoId = requested) }) {
            error("An exact catalogue entry should finish the lookup")
        }
        assertEquals(recording.thumbnailUrl, result)
    }

    @Test fun offlineRecoveryUsesKnownCoverWithoutCallingCatalogue() = runBlocking {
        val result = DownloadArtwork.lookupCover(file.copy(videoId = "abcdefghijk"), listOf(recording), false,
            { "file:///covers/local.jpg" }, { error("Offline ID lookup") }) { error("Offline search") }
        assertEquals("file:///covers/local.jpg", result)
    }
}
