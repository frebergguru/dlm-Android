// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import guru.freberg.dlm.scheduler.LinkSnap
import guru.freberg.dlm.scheduler.ListKind
import guru.freberg.dlm.scheduler.PkgSnap
import guru.freberg.dlm.scheduler.QState
import guru.freberg.dlm.ui.QueueViewModel
import guru.freberg.dlm.ui.util.hostOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(vm: QueueViewModel, modifier: Modifier = Modifier, onAddClick: () -> Unit, onOpenStatus: () -> Unit = {}) {
    val snap by vm.snapshot.collectAsState()
    val statusItems by guru.freberg.dlm.repo.StatusCenter.items.collectAsState()
    val running = statusItems.any { it.state == guru.freberg.dlm.repo.StatusState.RUNNING }
    var sheetLink by remember { mutableStateOf<LinkSnap?>(null) }
    var sheetPkg by remember { mutableStateOf<PkgSnap?>(null) }

    val downloads = snap.downloads
    val pkgs = snap.packages.filter { it.list == ListKind.DOWNLOAD }
    // One pass to bucket links by package instead of re-filtering the full list per
    // package (O(links) rather than O(packages × links)) in the LazyColumn builder.
    val byPackage = downloads.groupBy { it.packageId }
    val ungrouped = byPackage[0L].orEmpty()
    val hasFinished = downloads.any { it.state == QState.DONE }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    IconButton(onClick = onOpenStatus) {
                        if (running) androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                        ) else Icon(Icons.Filled.Sync, "Activity")
                    }
                    IconButton(onClick = { vm.setGlobalAutostart(!snap.globalAutostart) }) {
                        Icon(
                            if (snap.globalAutostart) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (snap.globalAutostart) "Pause all" else "Resume all",
                        )
                    }
                    if (hasFinished) IconButton(onClick = { vm.clearFinished() }) {
                        Icon(Icons.Filled.DeleteSweep, "Clear finished")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ResolvingBanner(vm)
            if (downloads.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Filled.CloudDownload, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) },
                    title = "No downloads yet",
                    subtitle = "Tap Add and paste a link to get started.",
                    modifier = Modifier.weight(1f),
                )
            } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                if (snap.activeCount > 0) {
                    item(key = "summary") { ActiveSummary(vm, snap.activeCount, snap.totalSpeedBps) }
                }
                items(ungrouped, key = { it.id }) { link ->
                    DownloadRow(vm, link, onMenu = { sheetLink = it })
                }
                pkgs.forEach { pkg ->
                    val links = byPackage[pkg.id].orEmpty()
                    item(key = "pkg-${pkg.id}") {
                        PackageHeader(
                            pkg = pkg,
                            expanded = !pkg.collapsed,
                            activeInPkg = links.count { it.state == QState.ACTIVE },
                            onToggle = { vm.setPackageCollapsed(pkg.id, !pkg.collapsed) },
                            onMenu = { sheetPkg = it },
                            host = hostOf(links.firstOrNull()?.url),
                        )
                    }
                    if (!pkg.collapsed) {
                        items(links, key = { it.id }) { link ->
                            DownloadRow(vm, link, onMenu = { sheetLink = it })
                        }
                    }
                }
            }
            }
        }
    }

    sheetLink?.let { LinkActionsSheet(vm, it, onDismiss = { sheetLink = null }) }
    sheetPkg?.let { PackageActionsSheet(vm, it, isLinkgrabber = false, onDismiss = { sheetPkg = null }) }
}

@Composable
private fun ActiveSummary(vm: QueueViewModel, active: Int, totalSpeed: Double) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudDownload, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    if (active == 1) "Downloading 1 file" else "Downloading $active files",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    vm.formatRate(totalSpeed.toLong()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
fun EmptyState(icon: @Composable () -> Unit, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
