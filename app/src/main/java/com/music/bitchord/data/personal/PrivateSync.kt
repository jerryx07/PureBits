package com.music.bitchord.data.personal

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit

object PrivateSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val syncLock = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private lateinit var prefs: SharedPreferences
    private var document = PrivateDocument()
    val serverURL = MutableStateFlow("")
    val status = MutableStateFlow("")
    val lastSync = MutableStateFlow(0L)
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun init(app: Application) {
        WorkManager.getInstance(app).cancelUniqueWork("purebits-private-sync")
        scope.launch {
            try {
                val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                prefs = EncryptedSharedPreferences.create(app, "purebits_personal", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
                val old = prefs.getString("document", null)?.let { json.decodeFromString<PrivateDocument>(it) } ?: PrivateDocument()
                val kinds = setOf("like", "history", "addon")
                document = old.copy(records = old.records.filterValues { it.kind in kinds }, pending = old.pending.filter { it.kind in kinds })
                serverURL.value = prefs.getString("url", "").orEmpty()
                lastSync.value = prefs.getLong("lastSync", 0L)
                if (!prefs.contains("publishedTo") && serverURL.value.isNotEmpty()) {
                    prefs.edit().putString("publishedTo", serverURL.value).apply()
                }
                prefs.edit().remove("token").remove("ytPending").remove("observedPlaylists").remove("observedSources").apply()
                save()
                ready.complete(Unit)
            } catch (e: Exception) {
                status.value = "Private storage could not be opened"
                ready.completeExceptionally(e)
            }
        }
    }

    private fun save() {
        check(prefs.edit().putString("document", json.encodeToString(document)).commit())
        counts.value = document.records.values.filterNot { it.deleted }.groupingBy { it.kind }.eachCount()
    }

    suspend fun rememberAddress(value: String) = withContext(Dispatchers.IO) {
        ready.await()
        val url = value.trim().toHttpUrlOrNull()
        require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) { "Enter an HTTPS address without a path" }
        val origin = url.toString().trimEnd('/')
        check(prefs.edit().putString("url", origin).commit())
        serverURL.value = origin
    }

    fun recordPlay(song: Song) {
        if (serverURL.value.isEmpty() || song.localUri != null) return
        val time = System.currentTimeMillis()
        scope.launch { lock.withLock {
            document = document.change("history", UUID.randomUUID().toString(),
                buildJsonObject { put("song", songJSON(song)); put("playedAt", time) })
            save()
        } }
    }

    fun recordLike(videoId: String, liked: Boolean) {
        if (serverURL.value.isEmpty()) return
        scope.launch {
            val previous = lock.withLock { document.records["like\u0000$videoId"] }
            val data = previous?.data?.takeIf { it["song"] is JsonObject } ?: if (liked) {
                val song = YtMusicRepository.trackLinks(videoId).getOrNull() ?: return@launch
                buildJsonObject { put("song", songJSON(song)) }
            } else buildJsonObject {}
            lock.withLock { document = document.change("like", videoId, data, !liked); save() }
        }
    }

    private suspend fun collectLibrary(): List<String> {
        val unavailable = mutableListOf<String>()
        val before = lock.withLock { document.records }
        if (Innertube.cookie != null) {
            try {
                var page = YtMusicRepository.browseSongs(YtMusicRepository.LIKED_MUSIC).getOrThrow()
                val likes = linkedMapOf<String, Song>()
                val tokens = mutableSetOf<String>()
                while (true) {
                    page.songs.forEach { likes[it.videoId] = it }
                    val token = page.continuation ?: break
                    check(tokens.add(token))
                    page = YtMusicRepository.moreSongs(token).getOrThrow()
                }
                lock.withLock {
                    for ((id, song) in likes) {
                        val key = "like\u0000$id"
                        if (document.records[key] == before[key] && document.pending.none { it.kind == "like" && it.id == id && it.deleted }) {
                            document = document.change("like", id, buildJsonObject { put("song", songJSON(song)) })
                        }
                    }
                    val previous = prefs.getStringSet("accountLikes", emptySet()).orEmpty()
                    for (id in previous - likes.keys) {
                        val key = "like\u0000$id"
                        val old = before[key] ?: continue
                        if (document.records[key] == old && document.pending.none { it.kind == "like" && it.id == id && !it.deleted }) {
                            document = document.change("like", id, old.data, true)
                        }
                    }
                    save()
                    prefs.edit().putStringSet("accountLikes", likes.keys.toSet()).apply()
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { unavailable += "account likes" }
            YtMusicRepository.history().onSuccess { songs ->
                lock.withLock {
                    songs.forEachIndexed { i, song ->
                        val id = "import-${song.videoId}"
                        val data = buildJsonObject { put("song", songJSON(song)); put("playedAt", -i.toLong()); put("imported", true) }
                        document = document.change("history", id, data)
                    }
                    save()
                }
            }.onFailure { unavailable += "account history" }
        }
        lock.withLock {
            val sources = SourceRegistry.configs.value.associate { it.id to sourceJSON(it) }
            val removed = document.records.values.filter { it.kind == "addon" && !it.deleted && it.id !in sources }
            for (old in removed) document = document.change("addon", old.id, old.data, true)
            for ((id, data) in sources) document = document.change("addon", id, data)
            save()
        }
        return unavailable
    }

    suspend fun syncNow(address: String, password: String, code: String) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            rememberAddress(address)
            val origin = serverURL.value
            status.value = "Signing in…"
            val response = post(origin, "login", buildJsonObject { put("password", password); put("code", code) }.toString(), null)
            val token = response.getValue("token").jsonPrimitive.content
            try {
                // Exchange the short-lived authenticator code before account pagination.
                status.value = "Preparing library…"
                val unavailable = collectLibrary()
                status.value = "Sending to web player…"
                val pending = lock.withLock {
                    if (prefs.getString("publishedTo", "") != origin) {
                        val old = document
                        document = PrivateDocument()
                        old.records.values.filterNot { it.deleted }.forEach { document = document.change(it.kind, it.id, it.data) }
                        save()
                        check(prefs.edit().putString("publishedTo", origin).commit())
                    }
                    document.pending
                }
                for (batch in pending.chunked(100).ifEmpty { listOf(emptyList()) }) {
                    val request = PrivateSyncRequest(0, batch)
                    val result = json.decodeFromJsonElement<PrivateSyncResponse>(post(origin, "sync/manual", json.encodeToString(request), token))
                    check(result.ack.toSet() == batch.mapTo(mutableSetOf()) { it.mutation })
                    lock.withLock {
                        document = document.copy(cursor = result.cursor, pending = document.pending.filterNot { it.mutation in result.ack })
                        save()
                    }
                }
                val time = System.currentTimeMillis()
                check(prefs.edit().putLong("lastSync", time).commit())
                lastSync.value = time
                status.value = if (unavailable.isEmpty()) "Sync complete" else "Saved available changes. Could not refresh " + unavailable.joinToString()
            } finally {
                withContext(NonCancellable) {
                    try { post(origin, "logout", "{}", token) } catch (_: Exception) {
                        status.value = "Could not confirm sign-out. The temporary session expires within ten minutes."
                    }
                }
            }
        }
    }
    private fun post(origin: String, path: String, body: String, token: String?): JsonObject {
        val request = Request.Builder().url("$origin/api/$path").header("X-PureBits-Client", "android").header("Connection", "close")
            .post(body.toRequestBody("application/json".toMediaType())).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return client.newCall(request).execute().use { response ->
            // Do not log URL, body, cookies, addon credentials or raw upstream errors.
            check(response.isSuccessful) { if (response.code == 401) "Sign-in failed or session expired" else "Private server returned ${response.code}" }
            val responseBody = requireNotNull(response.body)
            val source = responseBody.source(); require(!source.request(16L * 1024 * 1024 + 1)) { "Sync response too large" }
            json.parseToJsonElement(source.readUtf8()).jsonObject
        }
    }

    fun songJSON(song: Song) = buildJsonObject {
        put("videoId", song.videoId); put("title", song.title); put("artist", song.artist)
        song.thumbnailUrl?.takeIf { it.startsWith("https://") }?.let { put("thumbnailUrl", it) }
        song.durationText?.let { put("durationText", it) }; song.albumName?.let { put("albumName", it) }
        song.artistId?.let { put("artistId", it) }; song.albumId?.let { put("albumId", it) }
    }
    private fun sourceJSON(config: SourceConfig) = buildJsonObject {
        put("kind", config.kind.name); put("label", config.label); put("baseUrl", config.baseUrl)
        put("enabled", config.enabled); put("pickedQuality", config.pickedQuality?.let(::JsonPrimitive) ?: JsonNull)
    }
}

fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

class PrivateSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // A job already starting during an app upgrade must also finish without uploading.
    override suspend fun doWork(): Result = Result.success()
}
