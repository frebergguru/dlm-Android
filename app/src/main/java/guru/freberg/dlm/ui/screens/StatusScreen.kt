// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import guru.freberg.dlm.repo.StatusCenter
import guru.freberg.dlm.repo.StatusItem
import guru.freberg.dlm.repo.StatusState
import guru.freberg.dlm.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val items by StatusCenter.items.collectAsState()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (items.any { it.state != StatusState.RUNNING })
                        TextButton(onClick = { StatusCenter.clearFinished() }) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) },
                title = "Nothing running",
                subtitle = "Setup and update activity shows up here.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.key }) { item ->
                    StatusRow(item)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun StatusRow(item: StatusItem) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (item.state) {
                StatusState.RUNNING -> {
                    val p = item.progress
                    if (p != null)
                        CircularProgressIndicator(progress = { p }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
                StatusState.DONE -> Icon(Icons.Filled.CheckCircle, null, tint = SuccessGreen)
                StatusState.FAILED -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.detail.isNotBlank()) Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == StatusState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
