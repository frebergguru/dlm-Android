package com.dlm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp

/** Paste or auto-fill a link, then add it for review (default) or download now. */
@Composable
fun AddUrlDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onCrawl: (String) -> Unit,
    onAddDirect: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf(initialUrl) }

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
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { onCrawl(url) }) { Text("Add") } },
        dismissButton = { TextButton(enabled = url.isNotBlank(), onClick = { onAddDirect(url) }) { Text("Download now") } },
    )
}
