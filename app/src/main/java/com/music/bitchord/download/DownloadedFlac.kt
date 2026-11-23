package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import java.io.DataInputStream
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object DownloadedFlac {
    /** Native FLAC marker and the mandatory 34-byte STREAMINFO block. */
    fun hasHeader(input: InputStream): Boolean {
        val header = ByteArray(42)
        try {
            DataInputStream(input).readFully(header)
        } catch (_: java.io.EOFException) {
            return false
        }
        fun byte(i: Int) = header[i].toInt() and 255
        val minBlock = (byte(8) shl 8) or byte(9)
        val maxBlock = (byte(10) shl 8) or byte(11)
        val sampleRate = (byte(18) shl 12) or (byte(19) shl 4) or (byte(20) ushr 4)
        return minBlock >= 16 && maxBlock >= minBlock && sampleRate > 0 &&
            String(header, 0, 4, Charsets.US_ASCII) == "fLaC" &&
            header[4].toInt() and 0x7f == 0 && header[5].toInt() == 0 &&
            header[6].toInt() == 0 && header[7].toInt() == 34
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private val separator = Regex("\\s+[-–—]\\s+")
    private val alternate = Regex("[\\p{IsLatin}\\p{M}\\d '’.,!?]+")
    private val version = Regex("\\b(live|cover|remix|edit|version|acoustic|instrumental|karaoke|remaster(?:ed)?|demo|sped|slowed|english)\\b")
    private val editionAlbum = Regex("(?i)^[\\p{L}]+ (version|edition|ver\\.?)$")

    private fun albumForMatch(title: String, album: String?): String? =
        if (album != null && editionAlbum.matches(album.trim())) "$title ($album)" else album

    private fun titles(raw: String): Set<String> {
        val full = normalize(raw)
        val parts = full.split(separator)
        if (parts.size != 2 || parts.any(String::isBlank) || parts.any(version::containsMatchIn)) return setOf(full)
        val native = parts.singleOrNull { part -> part.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN } }
        return if (native != null && parts.any(alternate::matches)) setOf(full, native) else setOf(full)
    }

    class Index(records: Collection<SavedSongMetadata>) {
        private val byTitle = buildMap<String, MutableList<SavedSongMetadata>> {
            records.filter { it.verifiedFlac }.forEach { record ->
                titles(record.title).forEach { getOrPut(it) { mutableListOf() }.add(record) }
            }
        }

        fun match(song: Song): SavedSongMetadata? {
            if (song.title.isBlank() || song.artist.isBlank() || song.artist.equals("Unknown Artist", true)) return null
            val target = TrackMatcher.targetOf(song).copy(album = albumForMatch(song.title, song.albumName))
            return titles(song.title).asSequence().flatMap { byTitle[it].orEmpty() }.filter { record ->
                TrackMatcher.sharesArtist(song.artist, record.artist) &&
                    TrackMatcher.secondsOf(record.durationText)?.let { seconds ->
                        target.durationSec?.let { abs(it - seconds) <= 3 }
                    } != false &&
                    TrackMatcher.score(Song(record.videoId, record.title, record.artist, null,
                        durationText = record.durationText, albumName = albumForMatch(record.title, record.albumName)), target) != null
            }.distinctBy { it.uri }.take(2).toList().singleOrNull()
        }
    }
}
