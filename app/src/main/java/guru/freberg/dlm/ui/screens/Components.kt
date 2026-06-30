// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import guru.freberg.dlm.scheduler.LinkSnap
import guru.freberg.dlm.scheduler.MoveDir
import guru.freberg.dlm.scheduler.PkgSnap
import guru.freberg.dlm.scheduler.Priority
import guru.freberg.dlm.scheduler.QState
import guru.freberg.dlm.ui.QueueViewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import guru.freberg.dlm.ui.theme.stateAccent
import guru.freberg.dlm.ui.util.faviconModel
import guru.freberg.dlm.ui.util.fileTypeIcon
import guru.freberg.dlm.ui.util.hostOf
import guru.freberg.dlm.ui.util.formatBytes
import guru.freberg.dlm.ui.util.formatEta

/* ----------------------------------------------------------------------- */
/* Download-list row                                                        */
/* ----------------------------------------------------------------------- */

@Composable
fun DownloadRow(vm: QueueViewModel, link: LinkSnap, onMenu: (LinkSnap) -> Unit, indent: Dp = 0.dp) {
    val accent = stateAccent(
        done = link.state == QState.DONE,
        error = link.state == QState.ERROR,
        paused = link.state == QState.PAUSED,
        active = link.state == QState.ACTIVE,
    )
    Column(Modifier.padding(start = indent).fillMaxWidth().clickable { onMenu(link) }.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeAvatar(link.name, accent, hostOf(link.url))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    link.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    statusLine(vm, link),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (link.state == QState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Flags(link)
            if (link.state == QState.ACTIVE || link.state == QState.PAUSED) {
                Text(
                    "${(link.progressFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            IconButton(onClick = { onMenu(link) }) { Icon(Icons.Filled.MoreVert, "More options") }
        }
        if (link.state == QState.ACTIVE || (link.downloaded > 0 && link.state == QState.PAUSED)) {
            val animated by animateFloatAsState(link.progressFraction, label = "progress")
            @Suppress("DEPRECATION")
            LinearProgressIndicator(
                progress = animated,
                color = accent,
                trackColor = accent.copy(alpha = 0.18f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp).height(5.dp),
            )
        }
    }
}

@Composable
private fun TypeAvatar(name: String, accent: Color, host: String? = null) {
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier.matchParentSize().clip(CircleShape).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fileTypeIcon(name), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        // Small site-favicon badge so every row shows where the file came from.
        if (!host.isNullOrEmpty()) {
            SiteIcon(
                host,
                Modifier.align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun Flags(link: LinkSnap) {
    Row {
        if (!link.enabled) FlagIcon(Icons.Filled.VisibilityOff, "Skipped")
        if (link.list == guru.freberg.dlm.scheduler.ListKind.DOWNLOAD && !link.autostart && link.state == QState.QUEUED)
            FlagIcon(Icons.Filled.Schedule, "Starts manually")
        if (link.force) FlagIcon(Icons.Filled.Bolt, "Starting now")
        if (link.availability == "offline") FlagIcon(Icons.Filled.CloudOff, "Unavailable")
    }
}

@Composable
private fun FlagIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String) {
    Icon(
        icon, desc,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp).padding(start = 2.dp),
    )
}

private fun statusLine(vm: QueueViewModel, l: LinkSnap): String {
    if (l.state == QState.ERROR) return friendlyError(l.error)
    // Streamed media reports a 0–100 percentage rather than byte totals.
    val isStreamPct = l.delegate && l.total in 1..100
    val size = when {
        isStreamPct -> "${l.downloaded}%"
        l.total > 0 -> "${formatBytes(l.downloaded)} / ${formatBytes(l.total)}"
        l.downloaded > 0 -> formatBytes(l.downloaded)
        else -> ""
    }
    return when (l.state) {
        QState.QUEUED -> listOf("Waiting", size).filter { it.isNotBlank() }.joinToString(" · ")
        QState.ACTIVE -> {
            val speed = if (l.speedBps > 0) vm.formatRate(l.speedBps.toLong()) else ""
            val eta = if (!isStreamPct && l.total > 0) formatEta(l.total - l.downloaded, l.speedBps) else ""
            listOf(size, speed, eta).filter { it.isNotBlank() }.joinToString(" · ")
        }
        QState.PAUSED -> listOf("Paused", size).filter { it.isNotBlank() }.joinToString(" · ")
        QState.DONE -> {
            // Show the real size when known; otherwise just "Done".
            val bytes = when {
                l.total > 0 -> l.total
                !l.delegate && l.downloaded > 0 -> l.downloaded
                else -> -1
            }
            listOfNotNull("Done", if (bytes > 0) formatBytes(bytes) else null).joinToString(" · ")
        }
        QState.ERROR -> friendlyError(l.error)
    }
}

/** Turn engine error codes into plain language. */
private fun friendlyError(err: String?): String = when (err) {
    null -> "Failed"
    "network error" -> "Couldn’t connect — tap to try again"
    "not found" -> "This link doesn’t exist (404)"
    "access denied" -> "Access denied — it may need a sign-in (403)"
    "HTTP error" -> "The server couldn’t serve this file"
    "filesystem error" -> "Couldn’t save the file"
    "out of memory" -> "Ran out of memory"
    "bad argument" -> "This link can’t be downloaded"
    else -> err.replaceFirstChar { it.uppercase() }
}

/* ----------------------------------------------------------------------- */
/* Package header                                                           */
/* ----------------------------------------------------------------------- */

/**
 * A small site icon: the real favicon once it loads, otherwise a question mark
 * (the site couldn't be identified). Self-contained (Coil disk/memory-cached,
 * ~48px) so it can be reused anywhere a site needs identifying — Review site
 * headers and Downloads package headers alike.
 */
@Composable
fun SiteIcon(host: String, modifier: Modifier = Modifier.size(24.dp)) {
    val model = faviconModel(host)
    val context = LocalPlatformContext.current
    var failed by remember(host) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.QuestionMark, null, modifier = Modifier.matchParentSize(), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model != null && !failed) {
            val request = remember(model) { ImageRequest.Builder(context).data(model).size(48).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true },
            )
        }
    }
}

@Composable
fun PackageHeader(
    pkg: PkgSnap,
    expanded: Boolean,
    activeInPkg: Int,
    onToggle: () -> Unit,
    onMenu: (PkgSnap) -> Unit,
    host: String? = null,
    indent: Dp = 0.dp,
) {
    Row(
        Modifier.padding(start = indent).fillMaxWidth().clickable { onToggle() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (expanded) "Collapse" else "Expand",
        )
        // Site favicon when the package's host is known, else a generic folder.
        if (!host.isNullOrEmpty()) SiteIcon(host, Modifier.padding(horizontal = 8.dp).size(24.dp))
        else Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(if (pkg.linkCount == 1) "1 file" else "${pkg.linkCount} files")
                    if (activeInPkg > 0) append(" · $activeInPkg downloading")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onMenu(pkg) }) { Icon(Icons.Filled.MoreVert, "Folder options") }
    }
}

/* ----------------------------------------------------------------------- */
/* Action bottom sheets                                                     */
/* ----------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionsSheet(vm: QueueViewModel, link: LinkSnap, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember { mutableStateOf(false) }
    var showPriority by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                link.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()

            when (link.state) {
                QState.ACTIVE, QState.QUEUED ->
                    SheetItem(Icons.Filled.Pause, "Pause") { vm.pause(link.id); onDismiss() }
                QState.PAUSED, QState.ERROR ->
                    SheetItem(Icons.Filled.PlayArrow, "Resume") { vm.resume(link.id); onDismiss() }
                else -> {}
            }
            // "Start now" expedites a not-yet-running link; hide it once the link is
            // already downloading (ACTIVE) or finished (DONE).
            if (link.state != QState.DONE && link.state != QState.ACTIVE)
                SheetItem(Icons.Filled.Bolt, "Start now") { vm.force(link.id, false); onDismiss() }
            if (link.state == QState.DONE)
                SheetItem(Icons.Filled.IosShare, "Save to folder…") { vm.export(link); onDismiss() }

            SheetItem(Icons.Filled.Schedule, if (link.autostart) "Start manually" else "Start automatically") {
                vm.setAutostart(link.id, false, !link.autostart); onDismiss()
            }
            SheetItem(Icons.Filled.VisibilityOff, if (link.enabled) "Skip this download" else "Include this download") {
                vm.setEnabled(link.id, false, !link.enabled); onDismiss()
            }
            SheetItem(Icons.Filled.DriveFileMove, "Importance: ${priorityLabel(link.priority)}") { showPriority = !showPriority }
            if (showPriority) PriorityChips(link.priority) { vm.setPriority(link.id, false, it); onDismiss() }

            ReorderRow { dir -> vm.move(link.id, false, dir); onDismiss() }
            HorizontalDivider()
            SheetItem(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error) { confirmRemove = true }
        }
    }

    if (confirmRemove) ConfirmDialog(
        title = "Remove download?",
        message = "“${link.name}” and its partial file will be deleted.",
        onConfirm = { vm.remove(link.id); confirmRemove = false; onDismiss() },
        onDismiss = { confirmRemove = false },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageActionsSheet(vm: QueueViewModel, pkg: PkgSnap, isLinkgrabber: Boolean, onDismiss: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    var showPriority by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(pkg.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            HorizontalDivider()
            if (isLinkgrabber) {
                SheetItem(Icons.Filled.PlayArrow, "Start all in this folder") { vm.confirm(pkg.id, true, true); onDismiss() }
                SheetItem(Icons.Filled.Done, "Add to downloads (don’t start)") { vm.confirm(pkg.id, true, false); onDismiss() }
            }
            SheetItem(Icons.Filled.DriveFileMove, "Importance: ${priorityLabel(pkg.priority)}") { showPriority = !showPriority }
            if (showPriority) PriorityChips(pkg.priority) { vm.setPriority(pkg.id, true, it); onDismiss() }
            ReorderRow { dir -> vm.move(pkg.id, true, dir); onDismiss() }
            HorizontalDivider()
            SheetItem(Icons.Filled.Delete, "Remove folder", tint = MaterialTheme.colorScheme.error) { confirmRemove = true }
        }
    }
    if (confirmRemove) ConfirmDialog(
        title = "Remove folder?",
        message = "All ${pkg.linkCount} item(s) in “${pkg.name}” will be removed.",
        onConfirm = {
            if (isLinkgrabber) vm.lgRemove(pkg.id, true) else vm.pkgRemove(pkg.id)
            confirmRemove = false; onDismiss()
        },
        onDismiss = { confirmRemove = false },
    )
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriorityChips(current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (p in Priority.HIGHEST downTo Priority.LOWEST) {
            FilterChip(
                selected = p == current,
                onClick = { onPick(p) },
                label = { Text(shortPriority(p)) },
            )
        }
    }
}

@Composable
private fun ReorderRow(onMove: (MoveDir) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Reorder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
        IconButton(onClick = { onMove(MoveDir.TOP) }) { Icon(Icons.Filled.VerticalAlignTop, "Move to top") }
        IconButton(onClick = { onMove(MoveDir.UP) }) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
        IconButton(onClick = { onMove(MoveDir.DOWN) }) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
        IconButton(onClick = { onMove(MoveDir.BOTTOM) }) { Icon(Icons.Filled.VerticalAlignBottom, "Move to bottom") }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun priorityLabel(p: Int): String = when (p) {
    3 -> "Highest"; 2 -> "Higher"; 1 -> "High"; 0 -> "Normal"; -1 -> "Low"; -2 -> "Lower"; else -> "Lowest"
}

fun shortPriority(p: Int): String = when (p) {
    3 -> "Highest"; 2 -> "Higher"; 1 -> "High"; 0 -> "Normal"; -1 -> "Low"; -2 -> "Lower"; else -> "Lowest"
}
