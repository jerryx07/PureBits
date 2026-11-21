package com.music.bitchord.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/** Private, untagged bytes. The public destination is only written after completion. */
internal object ResumableTransfer {
    internal class HttpFailure(val status: Int) : IOException("Download failed (HTTP $status)")
    private class InvalidResponse(message: String) : IOException(message)
    private class DiskFailure(cause: IOException) : IOException("Could not save download bytes", cause)
    private data class State(
        val identity: String,
        val validator: String? = null,
        val total: Long = -1,
        val complete: Boolean = false,
    )
    private data class Range(val start: Long, val end: Long, val total: Long)
    private val rangePattern = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    // Persist the hash, never signed URLs or authorization headers.
    fun identity(url: String, headers: Map<String, String>): String = hash(
        url + "\n" + headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).entries
            .joinToString("\n") { "${it.key.lowercase()}:${it.value}" }
    )

    private fun metadata(file: File) = File(file.path + ".resume")
    private fun read(file: File): State? = runCatching {
        val p = Properties().apply { metadata(file).inputStream().use(::load) }
        State(
            p.getProperty("identity") ?: return null,
            p.getProperty("validator"),
            p.getProperty("total").toLong(),
            p.getProperty("complete").toBoolean(),
        )
    }.getOrNull()
    private fun save(file: File, state: State) {
        val target = metadata(file)
        val temp = File(target.path + ".tmp")
        val p = Properties().apply {
            setProperty("identity", state.identity)
            state.validator?.let { setProperty("validator", it) }
            setProperty("total", state.total.toString())
            setProperty("complete", state.complete.toString())
        }
        try {
            temp.outputStream().use { p.store(it, null); it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw DiskFailure(e)
        }
    }
    fun fraction(file: File): Float = read(file)?.takeIf { it.total > 0 }?.let { (file.length().toDouble() / it.total).toFloat().coerceIn(0f, 1f) } ?: 0f
    fun isComplete(file: File): Boolean = read(file)?.let { it.complete && file.isFile && file.length() == it.total && it.total > 0 } == true

    /** A weak ETag cannot validate byte ranges. Dates qualify only with the RFC's 60-second margin. */
    private fun validator(response: Response): String? {
        val tag = response.header("ETag")
        if (tag != null) return tag.takeIf { it.startsWith('"') && it.endsWith('"') }
        val modified = response.header("Last-Modified") ?: return null
        val date = response.header("Date") ?: return null
        return runCatching {
            val last = ZonedDateTime.parse(modified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val at = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            modified.takeIf { at.epochSecond - last.epochSecond >= 60 }
        }.getOrNull()
    }
    private fun range(response: Response): Range? = rangePattern.matchEntire(response.header("Content-Range").orEmpty())?.destructured?.let { (start, end, total) ->
        val a = start.toLongOrNull() ?: return null; val b = end.toLongOrNull() ?: return null; val n = total.toLongOrNull() ?: return null
        Range(a, b, n).takeIf { a <= b && b < n }
    }

    suspend fun download(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        file: File,
        onProgress: (Long, Long) -> Unit,
        // Only the YouTube wrapper supplies an immutable, rendition-specific identity.
        immutableIdentity: String? = null,
        chunkBytes: Long? = null,
        retries: Int = 3,
        retryDelayMs: Long = 1_000,
    ): File = withContext(Dispatchers.IO) {
        require(chunkBytes == null || chunkBytes > 0)
        require(chunkBytes == null || immutableIdentity != null) { "Bounded requests need an immutable rendition" }
        val identity = immutableIdentity ?: identity(url, headers)
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Could not create download checkpoint" } }
        var state = read(file)?.takeIf { it.identity == identity } ?: State(identity)
        var failures = 0
        var restarts = 0
        RandomAccessFile(file, "rw").use { out ->
            if (read(file)?.identity != identity || (state.total >= 0 && out.length() > state.total)) { out.setLength(0); state = State(identity) }
            if (state.complete && out.length() == state.total && state.total > 0) { onProgress(state.total, state.total); return@withContext file }
            state = state.copy(complete = false)
            while (true) {
                currentCoroutineContext().ensureActive()
                // Without a strong validator or an immutable media identity, appending
                // two responses could silently combine different recordings.
                if (out.length() > 0 && state.validator == null && immutableIdentity == null) { out.setLength(0); state = State(identity) }
                var position = out.length()
                if (state.total > 0) onProgress(position, state.total)
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    removeHeader("Range"); removeHeader("If-Range")
                    header("Accept-Encoding", "identity")
                    if (position > 0 || chunkBytes != null) {
                        val end = chunkBytes?.let { position + it - 1 }
                        header("Range", "bytes=$position-${end ?: ""}")
                        if (position > 0) state.validator?.let { header("If-Range", it) }
                    }
                }.build()
                try {
                    var restart = false
                    coroutineScope {
                        val call = client.newCall(request)
                        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { call.cancel() }
                        }
                        try { call.execute().use { response ->
                            if (response.code == 416 && position > 0 && restarts++ < 1) { restart = true; return@use }
                            if (response.code != 200 && response.code != 206) throw HttpFailure(response.code)
                            if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) throw InvalidResponse("Compressed byte ranges cannot be resumed safely")
                            val body = response.body
                            val part = if (response.code == 206) range(response) ?: throw InvalidResponse("Invalid download Content-Range") else null
                            if (part != null && part.start != position) throw InvalidResponse("Server returned the wrong download offset")
                            val responseValidator = validator(response)
                            val total = part?.total ?: body.contentLength()
                            if (response.code == 200 && position > 0) {
                                // Range was ignored, or If-Range discovered a changed file.
                                out.setLength(0); position = 0; state = State(identity)
                            } else if (position > 0 && ((state.total > 0 && total != state.total) || (responseValidator != null && state.validator != null && responseValidator != state.validator))) {
                                if (restarts++ >= 1) throw InvalidResponse("The download keeps changing")
                                restart = true; return@use
                            }
                            val expected = part?.let { it.end - it.start + 1 } ?: body.contentLength()
                            if (part != null && body.contentLength() >= 0 && body.contentLength() != expected) throw InvalidResponse("Download range length is inconsistent")
                            state = State(identity, responseValidator ?: state.validator.takeIf { part != null }, total)
                            save(file, state)
                            out.seek(position)
                            if (total > 0) onProgress(position, total)
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            body.byteStream().use { source ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val size = source.read(buffer)
                                    if (size < 0) break
                                    if (expected >= 0 && received + size > expected) throw InvalidResponse("Server sent more than the requested range")
                                    try { out.write(buffer, 0, size) } catch (e: IOException) { throw DiskFailure(e) }
                                    received += size; position += size
                                    if (total > 0) onProgress(position, total)
                                }
                            }
                            if (expected >= 0 && received != expected) throw IOException("Download connection ended early")
                            if (received == 0L) throw IOException("Download connection stalled")
                            if (total < 0 || position == total) {
                                out.fd.sync(); state = state.copy(total = position, complete = true); save(file, state)
                            } else if (state.validator == null && immutableIdentity == null) {
                                // An unsolicited partial response must not make us
                                // repeatedly download the same prefix forever.
                                throw InvalidResponse("The source cannot safely resume this partial response")
                            }
                        } } finally { cancellation.cancelAndJoin() }
                    }
                    if (restart) { out.setLength(0); state = State(identity); save(file, state); continue }
                    if (state.complete) return@withContext file
                    failures = 0 // A complete range arrived; the next range has its own retry budget.
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    val retryable = e !is InvalidResponse && e !is DiskFailure && (e !is HttpFailure || e.status == 408 || e.status == 429 || e.status in 500..599)
                    if (!retryable || failures++ >= retries) throw e
                    out.fd.sync()
                    delay((retryDelayMs * (1L shl (failures - 1).coerceAtMost(4))).coerceAtMost(10_000))
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") file
    }
}
