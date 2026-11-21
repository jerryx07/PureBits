package com.music.bitchord.download

import android.content.Context
import com.music.bitchord.data.Http
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Checkpoints never appear as playable tracks or enter Android cloud backups. */
internal object DownloadParts {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withTrack(id: String, block: suspend () -> T): T =
        locks.getOrPut(ResumableTransfer.hash(id)) { Mutex() }.withLock { block() }

    fun root(context: Context, id: String) =
        File(File(context.noBackupFilesDir, "download-parts"), ResumableTransfer.hash(id)).also {
            if (it.exists()) it.setLastModified(System.currentTimeMillis())
        }

    fun clear(context: Context, id: String) {
        root(context, id).deleteRecursively()
    }

    suspend fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        File(context.noBackupFilesDir, "download-parts").listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            locks.getOrPut(folder.name) { Mutex() }.withLock {
                if (folder.lastModified() < cutoff) folder.deleteRecursively()
            }
        }
    }

    fun fraction(context: Context, id: String): Float {
        val root = root(context, id)
        val progressive = ResumableTransfer.fraction(File(root, "audio.part"))
        val segmented = SegmentParts.fraction(File(root, "segments"))
        return maxOf(progressive, segmented)
    }
}

/** Reuse finished segments only for exactly the same manifest, headers and URLs. */
internal class SegmentParts(
    private val root: File,
    identity: String,
    private val urls: List<String>,
    private val headers: Map<String, String>,
) {
    private val finished: MutableSet<Int>
    init {
        val signature = ResumableTransfer.hash(identity + "\n" + urls.joinToString("\n") { ResumableTransfer.identity(it, headers) })
        val marker = File(root, "plan")
        if (runCatching { marker.readText() }.getOrNull() != signature) {
            root.deleteRecursively()
            check(root.mkdirs()) { "Could not create segment checkpoint" }
            marker.writeText(signature)
        }
        File(root, "count").writeText(urls.size.toString())
        finished = urls.indices.filterTo(mutableSetOf()) { ResumableTransfer.isComplete(file(it)) }
    }
    private fun file(index: Int) = File(root, "segment-$index.part")
    suspend fun fetch(index: Int): File = ResumableTransfer.download(
        Http.client, urls[index], headers, file(index), { _, _ -> }
    ).also { finished += index }
    fun completed(): Int = finished.size
    companion object {
        fun fraction(root: File): Float {
            val total = runCatching { File(root, "count").readText().toInt() }.getOrNull()?.takeIf { it > 0 } ?: return 0f
            val completed = root.listFiles()?.count {
                it.name.endsWith(".part") && ResumableTransfer.isComplete(it)
            } ?: 0
            return (completed.toFloat() / total).coerceIn(0f, 1f)
        }
    }
}
