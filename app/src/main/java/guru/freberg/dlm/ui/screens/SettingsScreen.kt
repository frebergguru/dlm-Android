package guru.freberg.dlm.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import guru.freberg.dlm.ytdlp.YtdlpState
import guru.freberg.dlm.ui.QueueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: QueueViewModel, modifier: Modifier = Modifier, onOpenAuth: () -> Unit, onOpenStatus: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = vm.repository()
    val snap by vm.snapshot.collectAsState()
    val ytState by vm.ytdlpState.collectAsState()
    val clipboardMonitor by vm.clipboardMonitor.collectAsState()

    var limitText by remember { mutableStateOf(if (snap.maxSpeed > 0) vm.formatRate(snap.maxSpeed).removeSuffix("/s") else "") }
    var autoExport by remember { mutableStateOf(repo.autoExport) }
    var hasFolder by remember { mutableStateOf(repo.downloadTreeUri() != null) }
    var concurrent by remember { mutableFloatStateOf(snap.maxActive.toFloat()) }

    // Resolving the auth status decrypts stored credentials; load it once off the
    // main thread instead of decrypting on every recomposition.
    var authStatus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { authStatus = withContext(Dispatchers.IO) { repo.authStatus() } }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            repo.setDownloadTreeUri(uri.toString())
            hasFolder = true
        }
    }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingCard("Downloads") {
                Text("Download ${concurrent.toInt()} at the same time", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = concurrent,
                    onValueChange = { concurrent = it },
                    onValueChangeFinished = { vm.setMaxActive(concurrent.toInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { limitText = it },
                        label = { Text("Speed limit") },
                        placeholder = { Text("No limit") },
                        supportingText = { Text("e.g. 2M or 500k") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { vm.setMaxSpeed(if (limitText.isBlank()) 0 else vm.parseRate(limitText)) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("Apply") }
                }
                ToggleRow(
                    "Download automatically",
                    "When off, downloads wait until you start them.",
                    snap.globalAutostart,
                ) { vm.setGlobalAutostart(it) }
            }

            SettingCard("Adding links") {
                ToggleRow(
                    "Watch clipboard for links",
                    "Copy a link and it’s added to Review automatically. Works while " +
                        "the app is open — Android blocks clipboard access in the background.",
                    clipboardMonitor,
                ) { vm.setClipboardMonitor(it) }
            }

            SettingCard("Where to save") {
                Text(
                    "Files download to the app first, then copy to your chosen folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (hasFolder) "Folder selected" else "No folder chosen", Modifier.weight(1f))
                    OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Choose folder") }
                }
                ToggleRow("Save finished files there automatically", null, autoExport) {
                    autoExport = it; repo.autoExport = it
                }
            }

            SettingCard("Video & audio sites") {
                Text(
                    "Lets you download from YouTube and many other sites.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ytState == YtdlpState.READY) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        when (ytState) {
                            YtdlpState.NOT_READY -> "Sets up automatically the first time you use it."
                            YtdlpState.PREPARING -> "Setting up…"
                            YtdlpState.READY -> "Ready"
                            YtdlpState.FAILED -> "Setup didn’t finish — tap to retry."
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = { vm.prepareYtdlp() },
                    enabled = ytState != YtdlpState.PREPARING && ytState != YtdlpState.READY,
                ) { Text(if (ytState == YtdlpState.READY) "Up to date" else "Set up now") }
            }

            SettingCard("Activity") {
                Text(
                    "See setup and update progress (video support, yt-dlp updates).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenStatus) { Text("View activity") }
            }

            SettingCard("Internet Archive") {
                Text(
                    "Optional. Sign in to download restricted items from archive.org.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenAuth) { Text(friendlyAuth(authStatus)) }
            }

            Text(
                "dlm · version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

private fun friendlyAuth(status: String): String =
    if (status.contains("signed in", ignoreCase = true) || status.contains("S3") || status.contains("cookie"))
        "Signed in — manage" else "Sign in (optional)"

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
