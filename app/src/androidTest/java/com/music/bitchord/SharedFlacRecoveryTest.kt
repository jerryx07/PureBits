package com.music.bitchord

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadArtwork
import com.music.bitchord.download.Downloads
import com.music.bitchord.download.FlacTagger
import com.music.bitchord.download.MediaTagger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedFlacRecoveryTest {
    @Test fun existingPictureSurvivesTaggingAndProvidesAnOfflineThumbnail() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val jpeg = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            it.toByteArray()
        }
        bitmap.recycle()
        val source = android.util.Base64.decode("ZkxhQ4AAACIBAAEAAAAAAAAACsRA8AAAAQAAAAAAAAAAAAAAAAAAAAAA//iJCAATAAAAHYE=", 0)
        val original = FlacTagger.tag(source, "Fixture", "Artist", null, null, jpeg, "image/jpeg")
        val file = File.createTempFile("download-art-test", ".flac", context.cacheDir)
        try {
            file.writeBytes(FlacTagger.tag(original, "Fixture", "Artist", null, "lyrics", null, "image/jpeg"))
            val song = Song("test-file", "Fixture", "Unknown Artist", "content://media/external/audio/albumart/0",
                localUri = Uri.fromFile(file).toString())
            val art = requireNotNull(DownloadArtwork.recover(context, song, emptyList()))
            val cached = requireNotNull(Uri.parse(art).path)
            val picture = requireNotNull(BitmapFactory.decodeFile(cached))
            assertEquals(16, picture.width)
            assertTrue(Color.green(picture.getPixel(0, 0)) > 240 && Color.red(picture.getPixel(0, 0)) < 10)
            picture.recycle()
            assertEquals(art, DownloadArtwork.recover(context, song.copy(thumbnailUrl = art), emptyList()))
        } finally {
            file.delete()
        }
    }

    @Test fun discoversPublishedFlacWithoutAnAppRecordAndCanPlayIt() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val ui = instrumentation.uiAutomation
        ui.grantRuntimePermission(context.packageName, if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        val title = "Recovery " + UUID.randomUUID()
        val name = "Fixture - $title.flac"
        val path = "/sdcard/Music/PureBits/$name"
        fun shell(command: String) {
            val pipes = ui.executeShellCommandRw("sh")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter().use { it.appendLine(command) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        }
        var scannedUri: android.net.Uri? = null
        try {
            // The shell owns this fixture, as a previous app installation would.
            shell("mkdir -p /sdcard/Music/PureBits")
            val audio = "ZkxhQ4AAACIBAAEAAAAAAAAACsRA8AAAAQAAAAAAAAAAAAAAAAAAAAAA//iJCAATAAAAHYE="
            shell("echo $audio | base64 -d > '$path'")
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("audio/flac")) { _, found ->
                scannedUri = found
                latch.countDown()
            }
            assertTrue(latch.await(20, TimeUnit.SECONDS))
            val scanned = requireNotNull(scannedUri)
            val id = scanned.lastPathSegment
            val canonical = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id).build()
            val columns = arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.IS_PENDING)
            val indexed = resolver.query(canonical, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) columns.indices.map(cursor::getString) else emptyList()
            }
            assertEquals(listOf("Music/PureBits/", name, "0"), indexed)
            assertTrue("Recovered URI should open as FLAC", resolver.openInputStream(canonical)?.use {
                com.music.bitchord.download.DownloadedFlac.hasHeader(it)
            } == true)
            val rows = Downloads.getDownloadedSongs(context).filter { it.localUri == canonical.toString() }
            val song = rows.single()
            assertEquals(title, song.title)
            assertEquals("FLAC", song.downloadFormat)
            assertEquals(id, android.net.Uri.parse(song.localUri).lastPathSegment)
            assertEquals(song.localUri, Downloads.playbackUri(song.copy(videoId = "catalogue", localUri = null)))
            assertEquals(1, Downloads.getDownloadedSongs(context).count { it.localUri == song.localUri })
            val previouslySaved = song.copy(videoId = "existing-download", localUri = scanned.toString())
            val listed = LocalMediaRepository.scanDownloadFolder(context, listOf(previouslySaved))
            assertEquals(listOf(previouslySaved.videoId), listed.filter { it.title == title }.map { it.videoId })

            // An image already fetched for a catalogue row should also serve a coverless local recording.
            val coverUrl = "https://example.org/$title.jpg".replace(" ", "-")
            val pixel = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
            val jpeg = ByteArrayOutputStream().use { pixel.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            pixel.recycle()
            val cached = requireNotNull(MediaTagger.persistArtwork(context, coverUrl, MediaTagger.Artwork(jpeg, "image/jpeg")))
            val before = resolver.openInputStream(canonical)!!.use { it.readBytes() }
            val catalogueRow = song.copy(videoId = "other-catalogue-id", localUri = null, thumbnailUrl = coverUrl)
            assertEquals(cached, Downloads.resolveArtwork(context, song, listOf(catalogueRow)))
            assertEquals(cached, Downloads.resolveArtwork(context, song, emptyList()))
            assertEquals(cached, Downloads.getDownloadedSongs(context).single { it.localUri == song.localUri }.thumbnailUrl)
            assertArrayEquals(before, resolver.openInputStream(canonical)!!.use { it.readBytes() })
        } finally {
            shell("rm -f \"$path\"")
            scannedUri?.let { runCatching { resolver.delete(it, null, null) } }
            scannedUri?.lastPathSegment?.let { id ->
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id).build()
                Downloads.forgetMissingUri(uri.toString())
            }
        }
    }
}
