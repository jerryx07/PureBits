package com.music.bitchord.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.personal.PrivateRecord
import com.music.bitchord.data.personal.PrivateSync
import com.music.bitchord.data.personal.text
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.rememberMediaController
import com.music.bitchord.playback.playSongs
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

class PrivateSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { PrivateLibrary(onBack = ::finish) } } }
    }
}

@Composable
private fun PrivateLibrary(onBack: () -> Unit) {
    val connected by PrivateSync.connected.collectAsState()
    val status by PrivateSync.status.collectAsState()
    val records by PrivateSync.records.collectAsState()
    val configuredURL by PrivateSync.serverURL.collectAsState()
    val scope = rememberCoroutineScope()
    val controller = rememberMediaController()
    var url by remember(configuredURL) { mutableStateOf(configuredURL) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("like") }
    var playlist by remember { mutableStateOf<String?>(null) }
    var editPlaylist by remember { mutableStateOf<PrivateRecord?>(null) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<Song?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<PrivateRecord?>(null) }
    fun perform(block: suspend () -> Unit) {
        scope.launch { busy = true; error = ""; try { block() } catch (_: Exception) { error = "Could not finish. Check your connection and sign-in details, then retry." } finally { busy = false } }
    }
    val playlists = records.filter { it.kind == "playlist" }
    val songs = when {
        playlist != null -> records.filter { it.kind == "entry" && it.data.text("playlistId") == playlist }.sortedBy { it.data["position"]?.jsonPrimitive?.longOrNull ?: 0 }
        tab == "history" -> records.filter { it.kind == "history" }.sortedByDescending { it.data["playedAt"]?.jsonPrimitive?.longOrNull ?: 0 }
        else -> records.filter { it.kind == "like" }.sortedByDescending { it.seq }
    }.mapNotNull { it.data["song"]?.jsonObject?.let(PrivateSync::songFromJSON) }.distinctBy { it.videoId }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Private web sync", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            }
            Text(status, color = MaterialTheme.colorScheme.primary)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        item {
            OutlinedTextField(url, { url = it }, label = { Text("Your HTTPS server address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !connected)
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Authenticator code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && password.isNotEmpty() && code.length == 6, onClick = { perform { try { PrivateSync.connect(url, password, code) } finally { password = ""; code = "" } } }) { Text(if (connected) "Sign in again" else "Connect") }
                if (connected) TextButton(enabled = !busy, onClick = { confirmDisconnect = true }) { Text("Disconnect") }
            }
        }
        if (connected) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { perform { PrivateSync.synchronize() } }) { Text("Sync now") }
                    OutlinedButton(enabled = !busy, onClick = { perform { PrivateSync.importLibrary() } }) { Text("Import library") }
                }
                Text("Import brings in YouTube liked songs, available history and playlists. Playlists become private copies; edits to those copies sync here and on the web. Audio files and account cookies stay on this phone.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("like" to "Likes", "history" to "Recents", "playlist" to "Playlists", "addon" to "Addons").forEach { (key, label) ->
                        TextButton(onClick = { tab = key; playlist = null }) { Text(label, color = if (tab == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
            if (tab == "addon") {
                item { Text("Manage sources in the app's Sources settings or in the web player. Browser support depends on the addon's protocol and authentication.") }
                items(records.filter { it.kind == "addon" }, key = { it.id }) { a ->
                    Text(a.data.text("label").ifBlank { a.data.text("kind") }, style = MaterialTheme.typography.titleMedium)
                    Text(if (a.data.text("kind") == "ADDON") "HTTP addon" else "Android source", style = MaterialTheme.typography.bodySmall)
                }
            } else if (tab == "playlist" && playlist == null) {
                item { Button(onClick = { creating = true; title = "" }) { Text("Create playlist") } }
                items(playlists, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(p.data.text("title"), Modifier.weight(1f).clickable { playlist = p.id }.padding(vertical = 12.dp))
                        TextButton(onClick = { editPlaylist = p; title = p.data.text("title") }) { Text("Rename") }
                        TextButton(onClick = { confirmDelete = p }) { Text("Delete") }
                    }
                }
            } else {
                item {
                    if (playlist != null) TextButton(onClick = { playlist = null }) { Text("All playlists") }
                    if (songs.isEmpty()) Text("No songs yet. Import your library or add music from the web player.")
                }
                items(songs, key = { it.videoId }) { song ->
                    val liked = records.any { it.kind == "like" && it.id == song.videoId }
                    Column(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = controller != null) { perform { controller?.playSongs(songs, songs.indexOf(song)) } }) {
                            Text(song.title, style = MaterialTheme.typography.titleMedium)
                            Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row {
                            TextButton(onClick = { perform { PrivateSync.edit("like", song.videoId, buildJsonObject { put("song", PrivateSync.songJSON(song)) }, liked) } }) { Text(if (liked) "Unlike" else "Like") }
                            TextButton(onClick = { adding = song }) { Text("Add to playlist") }
                            if (playlist != null) TextButton(onClick = { perform { PrivateSync.edit("entry", "$playlist:${song.videoId}", buildJsonObject { put("playlistId", playlist); put("song", PrivateSync.songJSON(song)) }, true) } }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (creating || editPlaylist != null) AlertDialog(onDismissRequest = { creating = false; editPlaylist = null }, title = { Text("Playlist name") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = {
        TextButton(onClick = { val id = editPlaylist?.id ?: UUID.randomUUID().toString(); perform { PrivateSync.edit("playlist", id, buildJsonObject { put("title", title.trim()) }) }; creating = false; editPlaylist = null }, enabled = title.isNotBlank()) { Text("Save") }
    }, dismissButton = { TextButton(onClick = { creating = false; editPlaylist = null }) { Text("Cancel") } })
    adding?.let { song -> AlertDialog(onDismissRequest = { adding = null }, title = { Text("Add to playlist") }, text = {
        LazyColumn { if (playlists.isEmpty()) item { Text("Create a playlist first.") }; items(playlists, key = { it.id }) { p -> TextButton(onClick = { perform { PrivateSync.edit("entry", "${p.id}:${song.videoId}", buildJsonObject { put("playlistId", p.id); put("song", PrivateSync.songJSON(song)); put("position", System.currentTimeMillis()) }) }; adding = null }) { Text(p.data.text("title")) } } }
    }, confirmButton = { TextButton(onClick = { adding = null }) { Text("Close") } }) }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text("Disconnect this phone?") }, text = { Text("Pending private changes on this phone will be discarded. The server library and Android playlists remain. If the server cannot be reached, revoke this session from its terminal.") }, confirmButton = { TextButton(onClick = { confirmDisconnect = false; perform { PrivateSync.disconnect() } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } })
    confirmDelete?.let { p -> AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text("Delete playlist?") }, text = { Text("This removes the private playlist from both devices.") }, confirmButton = { TextButton(onClick = { confirmDelete = null; perform { PrivateSync.edit("playlist", p.id, p.data, true) } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }) }
}
