package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class DownloadedFlacTest {
    private val recent = Song("recent", "ミズキリ - Mizukiri", "Yuuri", null, durationText = "3:28")
    private val file = SavedSongMetadata("addon", "ミズキリ", "優里", uri = "local", durationText = "3:28", verifiedFlac = true)

    @Test fun nativeTitleAndArtistAliasPlayLocalFlacWithoutCoverArt() {
        assertEquals(file, DownloadedFlac.Index(listOf(file)).match(recent))
    }

    @Test fun similarRecordingOrAmbiguousCopyCannotOverrideRecents() {
        for (other in listOf(file.copy(artist = "Other Artist"), file.copy(title = "ミズキリ Remix"),
            file.copy(durationText = "3:55"), file.copy(verifiedFlac = false),
            file.copy(albumName = "English Version"))) {
            assertNull(other.toString(), DownloadedFlac.Index(listOf(other)).match(recent))
        }
        assertNull(DownloadedFlac.Index(listOf(file, file.copy(uri = "other"))).match(recent))
        assertEquals(file, DownloadedFlac.Index(listOf(file, file.copy(videoId = "another-id"))).match(recent))
    }

    @Test fun requiresNativeFlacMarkerAndStreamInfoNotAnM4aOrMisleadingFilename() {
        val header = ByteArray(42)
        "fLaC".toByteArray().copyInto(header)
        header[7] = 34
        header[8] = 16
        header[10] = 16
        header[18] = 10
        assertTrue(DownloadedFlac.hasHeader(header.inputStream()))
        assertFalse(DownloadedFlac.hasHeader(header.copyOf(12).inputStream()))
        "ftyp".toByteArray().copyInto(header, 4)
        assertFalse(DownloadedFlac.hasHeader(header.inputStream()))
    }
}
