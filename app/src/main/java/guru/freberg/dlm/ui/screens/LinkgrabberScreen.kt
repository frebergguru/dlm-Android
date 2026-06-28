package guru.freberg.dlm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import guru.freberg.dlm.scheduler.LinkSnap
import guru.freberg.dlm.scheduler.ListKind
import guru.freberg.dlm.scheduler.PkgSnap
import guru.freberg.dlm.ui.QueueViewModel
import guru.freberg.dlm.ui.util.fileTypeIcon
import guru.freberg.dlm.ui.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkgrabberScreen(vm: QueueViewModel, modifier: Modifier = Modifier, onAddClick: () -> Unit) {
    val snap by vm.snapshot.collectAsState()
    val staged = snap.linkgrabber
    val pkgs = snap.packages.filter { it.list == ListKind.LINKGRABBER }
    val ungrouped = staged.filter { it.packageId == 0L }

    var sheetLink by remember { mutableStateOf<LinkSnap?>(null) }
    var sheetPkg by remember { mutableStateOf<PkgSnap?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Review") }) },
        floatingActionButton = {
            if (staged.isEmpty())
                FloatingActionButton(onClick = onAddClick) { Icon(Icons.Filled.Add, "Add link") }
        },
        bottomBar = {
            if (staged.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { vm.confirmAll(true) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start all downloads")
                    }
                    TextButton(onClick = { vm.lgClear() }) { Text("Clear") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ResolvingBanner(vm)
            if (staged.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Filled.Inbox, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)) },
                    title = "Nothing to review",
                    subtitle = "Links you add appear here first, so you can check them before downloading.",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(ungrouped, key = { it.id }) { link -> ReviewRow(link, onMenu = { sheetLink = it }) }
                    pkgs.forEach { pkg ->
                        val links = staged.filter { it.packageId == pkg.id }
                        item(key = "pkg-${pkg.id}") {
                            PackageHeader(
                                pkg = pkg, expanded = !pkg.collapsed, activeInPkg = 0,
                                onToggle = { vm.setPackageCollapsed(pkg.id, !pkg.collapsed) },
                                onMenu = { sheetPkg = it },
                            )
                        }
                        if (!pkg.collapsed) items(links, key = { it.id }) { link -> ReviewRow(link, onMenu = { sheetLink = it }) }
                    }
                }
            }
        }
    }

    sheetLink?.let { ReviewLinkSheet(vm, it, onDismiss = { sheetLink = null }) }
    sheetPkg?.let { PackageActionsSheet(vm, it, isLinkgrabber = true, onDismiss = { sheetPkg = null }) }
}

@Composable
private fun ReviewRow(link: LinkSnap, onMenu: (LinkSnap) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onMenu(link) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(fileTypeIcon(link.name), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(link.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(if (link.total >= 0) formatBytes(link.total) else "Size unknown")
                    if (link.delegate) append(" · video/stream")
                    if (!link.enabled) append(" · skipped")
                    if (link.availability == "offline") append(" · unavailable")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewLinkSheet(vm: QueueViewModel, link: LinkSnap, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(link.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            HorizontalDivider()
            SheetRow(Icons.Filled.PlayArrow, "Start download") { vm.confirm(link.id, false, true); onDismiss() }
            SheetRow(Icons.Filled.Done, "Add to downloads (don’t start)") { vm.confirm(link.id, false, false); onDismiss() }
            SheetRow(Icons.Filled.VisibilityOff, if (link.enabled) "Skip this link" else "Include this link") { vm.setEnabled(link.id, false, !link.enabled); onDismiss() }
            HorizontalDivider()
            SheetRow(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) { vm.lgRemove(link.id, false); onDismiss() }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
