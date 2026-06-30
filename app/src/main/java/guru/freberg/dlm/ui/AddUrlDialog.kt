// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import guru.freberg.dlm.ui.util.detectUrl
import guru.freberg.dlm.ui.util.isSafeDownloadInput

/** Paste or auto-fill a link, then add it for review (default) or download now. */
@Composable
fun AddUrlDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onCrawl: (String) -> Unit,
    onAddDirect: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // Saveable so a partially typed/pasted link survives configuration changes
    // (e.g. rotation) instead of being wiped back to the initial value.
    var url by rememberSaveable { mutableStateOf(initialUrl) }

    // Auto-paste: on open, if the field is still empty and the clipboard holds a
    // link, prefill it (mirrors the desktop on_add_clip_ready). Reading here can
    // surface the Android 12+ "pasted from clipboard" notification.
    LaunchedEffect(Unit) {
        if (url.isBlank()) detectUrl(clipboard.getText()?.text)?.let { url = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a link") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Paste a link") },
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                // Read the clipboard only on an explicit tap (avoids the Android 12+
                // background-paste access notification on every open).
                TextButton(onClick = {
                    val clip = clipboard.getText()?.text?.trim().orEmpty()
                    if (clip.isNotBlank()) url = clip
                }) { Text("Paste from clipboard") }
                Text(
                    "“Add” lets you review it first. “Download now” starts right away.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = isSafeDownloadInput(url), onClick = { onCrawl(url.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(enabled = isSafeDownloadInput(url), onClick = { onAddDirect(url.trim()) }) { Text("Download now") } },
    )
}
