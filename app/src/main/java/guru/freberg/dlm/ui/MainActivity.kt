// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import guru.freberg.dlm.ui.util.detectUrl
import guru.freberg.dlm.ui.screens.ArchiveOrgLoginScreen
import guru.freberg.dlm.ui.screens.DownloadsScreen
import guru.freberg.dlm.ui.screens.LinkgrabberScreen
import guru.freberg.dlm.ui.screens.SettingsScreen
import guru.freberg.dlm.ui.screens.StatusScreen
import guru.freberg.dlm.ui.theme.DlmTheme
import kotlinx.coroutines.launch

enum class Screen { DOWNLOADS, LINKGRABBER, SETTINGS, AUTH, STATUS }

class MainActivity : ComponentActivity() {

    private val vm: QueueViewModel by viewModels()

    // Observable so a re-shared link delivered via onNewIntent recomposes the UI.
    private val sharedUrl = mutableStateOf<String?>(null)

    // A link detected on the clipboard when the app comes to the foreground.
    private val clipboardUrl = mutableStateOf<String?>(null)
    // The last clipboard link we offered, so the same copy isn't proposed twice.
    private var lastClipHandled: String? = null

    // Watches the clipboard while the app is foregrounded (Android 10+ blocks
    // background reads); active only when the clipboard-monitor setting is on.
    private val clipManager by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!vm.clipboardMonitor.value) return@OnPrimaryClipChangedListener
        detectClipboardUrl()?.let { vm.autoGrab(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        maybeRequestNotifications()
        // Only extract on a fresh start; on recreation (e.g. rotation) the intent
        // is unchanged and re-reading it would re-pop the Add dialog.
        if (savedInstanceState == null) sharedUrl.value = extractSharedUrl(intent)

        setContent {
            DlmTheme {
                AppRoot(
                    vm, sharedUrl.value, clipboardUrl.value,
                    onSharedConsumed = { sharedUrl.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedUrl.value = extractSharedUrl(intent)
    }

    override fun onStart() {
        super.onStart()
        clipManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onStop() {
        clipManager?.removePrimaryClipChangedListener(clipListener)
        super.onStop()
    }

    // Clipboard access requires the window to have focus, so detect here rather
    // than in onResume. A shared/opened link always takes precedence.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || sharedUrl.value != null) return
        val clip = detectClipboardUrl() ?: return
        // With the monitor on, silently stage the link from both paths instead of
        // popping the Add dialog (covers links copied while the app was away).
        if (vm.clipboardMonitor.value) { vm.autoGrab(clip); return }
        if (clip == lastClipHandled) return
        lastClipHandled = clip
        clipboardUrl.value = clip
    }

    private fun detectClipboardUrl(): String? {
        val cm = clipManager ?: return null
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 } ?: return null
        return detectUrl(clip.getItemAt(0)?.coerceToText(this)?.toString())
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? = httpUrlOrNull(
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
    )

    private fun httpUrlOrNull(raw: String?): String? = raw?.trim()?.takeIf {
        it.isNotEmpty() && android.net.Uri.parse(it).scheme?.lowercase() in setOf("http", "https")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    vm: QueueViewModel,
    sharedUrl: String?,
    clipboardUrl: String?,
    onSharedConsumed: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.DOWNLOADS) }
    var prefillUrl by remember { mutableStateOf(sharedUrl) }
    var showAdd by remember { mutableStateOf(sharedUrl != null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snap by vm.snapshot.collectAsState()
    val reviewCount = snap.linkgrabber.size

    // Open the add dialog whenever a new shared link arrives (incl. via onNewIntent).
    androidx.compose.runtime.LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            prefillUrl = sharedUrl
            showAdd = true
            // Clear the source so onWindowFocusChanged's shared-link guard doesn't
            // stay latched and disable clipboard detection for the rest of the session.
            onSharedConsumed()
        }
    }

    // Offer a link detected on the clipboard when the app is foregrounded.
    androidx.compose.runtime.LaunchedEffect(clipboardUrl) {
        if (clipboardUrl != null && !showAdd) {
            prefillUrl = clipboardUrl
            showAdd = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.messages.collect { scope.launch { snackbar.showSnackbar(it) } }
    }

    // System Back should navigate within the app (sub-screens have no bottom-bar
    // entry, only a TopAppBar arrow) instead of finishing the Activity.
    BackHandler(enabled = screen != Screen.DOWNLOADS) {
        screen = if (screen == Screen.AUTH) Screen.SETTINGS else Screen.DOWNLOADS
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (screen != Screen.AUTH && screen != Screen.STATUS) NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.DOWNLOADS,
                    onClick = { screen = Screen.DOWNLOADS },
                    icon = { Icon(Icons.Filled.Download, null) },
                    label = { Text("Downloads") },
                )
                NavigationBarItem(
                    selected = screen == Screen.LINKGRABBER,
                    onClick = { screen = Screen.LINKGRABBER },
                    icon = {
                        BadgedBox(badge = { if (reviewCount > 0) Badge { Text("$reviewCount") } }) {
                            Icon(Icons.Filled.Inbox, null)
                        }
                    },
                    label = { Text("Review") },
                )
                NavigationBarItem(
                    selected = screen == Screen.SETTINGS,
                    onClick = { screen = Screen.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (screen) {
            Screen.DOWNLOADS -> DownloadsScreen(vm, mod, onAddClick = { showAdd = true }, onOpenStatus = { screen = Screen.STATUS })
            Screen.LINKGRABBER -> LinkgrabberScreen(vm, mod, onAddClick = { showAdd = true })
            Screen.SETTINGS -> SettingsScreen(vm, mod, onOpenAuth = { screen = Screen.AUTH }, onOpenStatus = { screen = Screen.STATUS })
            Screen.AUTH -> ArchiveOrgLoginScreen(vm, mod, onBack = { screen = Screen.SETTINGS })
            Screen.STATUS -> StatusScreen(mod, onBack = { screen = Screen.DOWNLOADS })
        }
    }

    if (showAdd) {
        AddUrlDialog(
            initialUrl = prefillUrl ?: "",
            onDismiss = { showAdd = false; prefillUrl = null },
            onCrawl = { vm.crawl(it); showAdd = false; prefillUrl = null },
            onAddDirect = { vm.addDirect(it); showAdd = false; prefillUrl = null },
        )
    }
}
