package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadArtworkTest {
    private val file = Song("file", "ミズキリ", "優里", null, durationText = "3:28", albumName = "PureBits")
    private val recording = Song("catalogue", "ミズキリ - Mizukiri", "Yuuri", "https://example.org/original.jpg",
        durationText = "3:28", albumName = "First album")

    @Test fun missingPictureUsesTitleAndArtistToQueryTheCatalogue() = runBlocking {
        val queries = mutableListOf<String>()
        val result = DownloadArtwork.lookupCover(file) { query ->
            queries += query
            listOf(recording.copy(artist = "Unrelated", thumbnailUrl = "https://example.org/wrong.jpg"), recording)
        }
        assertEquals(listOf("ミズキリ 優里"), queries)
        assertEquals(recording.thumbnailUrl, result)
    }

    @Test fun matchesBilingualRecordingAndRejectsDifferentArtistOrTake() {
        assertEquals(recording.thumbnailUrl, DownloadArtwork.matchingCover(file, listOf(recording)))
        assertNull(DownloadArtwork.matchingCover(file, listOf(recording.copy(artist = "Cover Band"))))
        assertNull(DownloadArtwork.matchingCover(file, listOf(recording.copy(title = "ミズキリ (Live)"))))
    }

    @Test fun selectsKnownReleaseAndLeavesAmbiguousEditionsAlone() {
        val compilation = recording.copy(albumName = "Compilation", thumbnailUrl = "https://example.org/compilation.jpg")
        assertNull(DownloadArtwork.matchingCover(file, listOf(compilation, recording)))
        assertEquals(recording.thumbnailUrl, DownloadArtwork.matchingCover(
            file.copy(albumName = recording.albumName), listOf(compilation, recording)))
    }
}
