// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import guru.freberg.dlm.scheduler.LinkSnap
import guru.freberg.dlm.scheduler.ListKind
import guru.freberg.dlm.scheduler.PkgSnap
import guru.freberg.dlm.scheduler.QState
import guru.freberg.dlm.ui.GroupMode
import guru.freberg.dlm.ui.QueueViewModel
import guru.freberg.dlm.ui.util.hostOf
import guru.freberg.dlm.ui.util.siteLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(vm: QueueViewModel, modifier: Modifier = Modifier, onAddClick: () -> Unit, onOpenStatus: () -> Unit = {}) {
    val snap by vm.snapshot.collectAsState()
    val statusItems by guru.freberg.dlm.repo.StatusCenter.items.collectAsState()
    val running = statusItems.any { it.state == guru.freberg.dlm.repo.StatusState.RUNNING }
    var sheetLink by remember { mutableStateOf<LinkSnap?>(null) }
    var sheetPkg by remember { mutableStateOf<PkgSnap?>(null) }
    var confirmClearFinished by remember { mutableStateOf(false) }
    var confirmClearFailed by remember { mutableStateOf(false) }

    val downloads = snap.downloads
    val pkgs = snap.packages.filter { it.list == ListKind.DOWNLOAD }
    val pkgIds = pkgs.mapTo(HashSet()) { it.id }
    val hasFinished = downloads.any { it.state == QState.DONE }
    val hasFailed = downloads.any { it.state == QState.ERROR }

    // Same grouping model as the Review tab: by site, by package, or both. A link's
    // site is its package's source-URL host when packaged (extractor task URLs can be
    // opaque), else the link's own host. Buckets are remembered so the O(N×M)
    // host/package bucketing doesn't re-run on every ~5 Hz progress emission.
    val groupMode by vm.groupMode.collectAsState()
    val collapsedSites by vm.collapsedSites.collectAsState()
    val byPackage = downloads.groupBy { it.packageId }
    val pkgSource = remember(pkgs) { pkgs.associate { it.id to it.comment } }
    fun hostForLink(l: LinkSnap): String =
        hostOf((if (l.packageId > 0) pkgSource[l.packageId] else null)?.takeIf { it.isNotBlank() } ?: l.url)
    val byHost = remember(downloads, pkgSource) { downloads.groupBy { hostForLink(it) } }
    val pkgsByHost = remember(pkgs, downloads) { pkgs.groupBy { pkgHostOf(it, downloads) } }
    val hosts = byHost.keys
    // Loose rows: ungrouped links, plus any whose owning package isn't a download
    // package yet (e.g. one link confirmed individually) — without this they'd render
    // under no header and vanish; they regroup once the package is confirmed.
    fun isLoose(l: LinkSnap) = l.packageId == 0L || l.packageId !in pkgIds

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
                    if (hasFailed) IconButton(onClick = { confirmClearFailed = true }) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = "Clear failed",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (hasFinished) IconButton(onClick = { confirmClearFinished = true }) {
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
            GroupModeSelector(groupMode, onSelect = { vm.setGroupMode(it) })
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                if (snap.activeCount > 0) {
                    item(key = "summary") { ActiveSummary(vm, snap.activeCount, snap.totalSpeedBps) }
                }
                // A package header for downloads, host-aware so its favicon resolves.
                fun pkgHeaderItem(scope: androidx.compose.foundation.lazy.LazyListScope, pkg: PkgSnap, indent: androidx.compose.ui.unit.Dp) {
                    val links = byPackage[pkg.id].orEmpty()
                    scope.item(key = "pkg-${pkg.id}") {
                        PackageHeader(
                            pkg = pkg,
                            expanded = !pkg.collapsed,
                            activeInPkg = links.count { it.state == QState.ACTIVE },
                            onToggle = { vm.setPackageCollapsed(pkg.id, !pkg.collapsed) },
                            onMenu = { sheetPkg = it },
                            host = pkg.comment?.let { hostOf(it) }?.takeIf { it.isNotEmpty() }
                                ?: hostOf(links.firstOrNull()?.url),
                            indent = indent,
                        )
                    }
                    if (!pkg.collapsed) scope.items(links, key = { it.id }) { link ->
                        DownloadRow(vm, link, onMenu = { sheetLink = it }, indent = indent)
                    }
                }

                when (groupMode) {
                    GroupMode.PKG -> {
                        items(downloads.filter { isLoose(it) }, key = { it.id }) { link ->
                            DownloadRow(vm, link, onMenu = { sheetLink = it })
                        }
                        pkgs.forEach { pkg -> pkgHeaderItem(this, pkg, 0.dp) }
                    }

                    GroupMode.SITE, GroupMode.SITE_PKG -> {
                        hosts.forEach { host ->
                            val hostLinks = byHost[host].orEmpty()
                            val collapsed = host in collapsedSites
                            item(key = "site-$host") {
                                DownloadsSiteHeader(host, hostLinks.size, collapsed) { vm.toggleSiteCollapsed(host) }
                            }
                            if (collapsed) return@forEach
                            if (groupMode == GroupMode.SITE) {
                                items(hostLinks, key = { it.id }) { link -> DownloadRow(vm, link, onMenu = { sheetLink = it }) }
                            } else {
                                pkgsByHost[host].orEmpty().forEach { pkg -> pkgHeaderItem(this, pkg, SitePkgIndent) }
                                items(hostLinks.filter { isLoose(it) }, key = { it.id }) { link ->
                                    DownloadRow(vm, link, onMenu = { sheetLink = it })
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    sheetLink?.let { LinkActionsSheet(vm, it, onDismiss = { sheetLink = null }) }
    sheetPkg?.let { PackageActionsSheet(vm, it, isLinkgrabber = false, onDismiss = { sheetPkg = null }) }

    if (confirmClearFailed) {
        val n = downloads.count { it.state == QState.ERROR }
        ConfirmDialog(
            title = "Clear failed downloads?",
            message = "$n failed ${if (n == 1) "download" else "downloads"} and any partial files will be removed.",
            confirmLabel = "Clear",
            onConfirm = { vm.clearFailed(); confirmClearFailed = false },
            onDismiss = { confirmClearFailed = false },
        )
    }
    if (confirmClearFinished) {
        val n = downloads.count { it.state == QState.DONE }
        ConfirmDialog(
            title = "Clear finished downloads?",
            message = "$n finished ${if (n == 1) "download" else "downloads"} will be removed from the list. The downloaded files are kept.",
            confirmLabel = "Clear",
            onConfirm = { vm.clearFinished(); confirmClearFinished = false },
            onDismiss = { confirmClearFinished = false },
        )
    }
}

/** How far a package group is inset under its site header in the SITE_PKG view. */
private val SitePkgIndent = 24.dp

/** Host of a package = its source-URL host, else the host of its first link.
 * Mirrors the Review tab's pkg_host. */
private fun pkgHostOf(pkg: PkgSnap, links: List<LinkSnap>): String =
    pkg.comment?.takeIf { it.isNotBlank() }?.let { hostOf(it) }?.takeIf { it.isNotEmpty() }
        ?: links.firstOrNull { it.packageId == pkg.id }?.let { hostOf(it.url) } ?: ""

/** Site group header for the Downloads tab: favicon, friendly name, host + count,
 * and collapse. Mirrors the Review tab's site header (minus "confirm all"). */
@Composable
private fun DownloadsSiteHeader(host: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (collapsed) "Expand" else "Collapse",
        )
        Spacer(Modifier.width(8.dp))
        SiteIcon(host, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(siteLabel(host), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${host.ifEmpty { "—" }} · $count file${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Site + packages / Site / Packages" selector, identical to the Review tab's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupModeSelector(selected: GroupMode, onSelect: (GroupMode) -> Unit) {
    val modes = GroupMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        modes.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) {
                Text(
                    when (mode) {
                        GroupMode.SITE_PKG -> "Site + packages"
                        GroupMode.SITE -> "Site"
                        GroupMode.PKG -> "Packages"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
