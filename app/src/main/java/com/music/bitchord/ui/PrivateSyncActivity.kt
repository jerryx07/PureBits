package com.music.bitchord.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.personal.PrivateSync
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.screens.SettingsGroup
import com.music.bitchord.ui.theme.BitChordTheme
import com.music.bitchord.ui.theme.SystemBarIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class PrivateSyncScreenModel : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow("")
    fun publish(address: String, password: String, code: String) {
        if (busy.value) return
        busy.value = true
        error.value = ""
        viewModelScope.launch {
            try { PrivateSync.syncNow(address, password, code) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = "Sync did not finish. Check the server address, password and current authenticator code, then retry." }
            finally { busy.value = false }
        }
    }
}

class PrivateSyncActivity : ComponentActivity() {
    private val model: PrivateSyncScreenModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by AppSettings.themeMode.collectAsState()
            val font by AppSettings.appFont.collectAsState()
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            BitChordTheme(darkTheme = dark, font = font) {
                SystemBarIcons(dark = !dark)
                Surface { PrivateLibrary(model, ::finish) }
            }
        }
    }
}

@Composable
private fun PrivateLibrary(model: PrivateSyncScreenModel, onBack: () -> Unit) {
    val address by PrivateSync.serverURL.collectAsState()
    val status by PrivateSync.status.collectAsState()
    val counts by PrivateSync.counts.collectAsState()
    val last by PrivateSync.lastSync.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf(false) }
    val url = draft ?: address
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) { Text("Back") }
        Text("Private web sync", Modifier.padding(horizontal = 22.dp, vertical = 10.dp), style = MaterialTheme.typography.headlineLarge)
        SettingsGroup(header = "Web player") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editing || address.isEmpty()) {
                    OutlinedTextField(url, { draft = it; addressError = false }, label = { Text("HTTPS server address") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true,
                        enabled = !busy, isError = addressError, modifier = Modifier.fillMaxWidth())
                    if (addressError) Text("Enter a valid HTTPS server address.", color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = !busy && url.isNotBlank(), onClick = { scope.launch {
                        try { PrivateSync.rememberAddress(url); draft = null; editing = false }
                        catch (_: IllegalArgumentException) { addressError = true }
                        catch (_: Exception) { model.error.value = "Could not save the address on this phone." }
                    } }) { Text("Save address") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(address, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(enabled = !busy, onClick = { editing = true }) { Text("Change") }
                    }
                }
            }
        }
        SettingsGroup(header = "Send from this phone", footer = "Your web likes and listening history stay on the web player. The phone signs out after each sync.") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Liked songs, Recents and addons", style = MaterialTheme.typography.titleMedium)
                if (last > 0) {
                    Text("Last sent " + DateFormat.getDateTimeInstance().format(Date(last)), style = MaterialTheme.typography.bodyMedium)
                }
                if (counts.isNotEmpty()) {
                    Text("${counts["like"] ?: 0} likes · ${counts["history"] ?: 0} history entries · ${counts["addon"] ?: 0} addons", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Authenticator code") }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.publish(url, password, code); password = ""; code = "" },
                    enabled = !busy && url.isNotBlank() && password.isNotEmpty() && code.length == 6, modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Sync now")
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                else if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
