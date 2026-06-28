package guru.freberg.dlm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import guru.freberg.dlm.scheduler.LinkSnap
import guru.freberg.dlm.scheduler.ListKind
import guru.freberg.dlm.scheduler.PkgSnap
import guru.freberg.dlm.ui.GroupMode
import guru.freberg.dlm.ui.QueueViewModel
import guru.freberg.dlm.ui.util.faviconUrl
import guru.freberg.dlm.ui.util.fileTypeIcon
import guru.freberg.dlm.ui.util.formatBytes
import guru.freberg.dlm.ui.util.hostOf
import guru.freberg.dlm.ui.util.siteLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkgrabberScreen(vm: QueueViewModel, modifier: Modifier = Modifier, onAddClick: () -> Unit) {
    val snap by vm.snapshot.collectAsState()
    val staged = snap.linkgrabber
    val pkgs = snap.packages.filter { it.list == ListKind.LINKGRABBER }
    val ungrouped = staged.filter { it.packageId == 0L }

    val groupMode by vm.groupMode.collectAsState()
    val collapsedSites by vm.collapsedSites.collectAsState()
    val failedFavicons by vm.failedFavicons.collectAsState()
    val clipboardMonitor by vm.clipboardMonitor.collectAsState()

    // Unique hosts in first-appearance order (mirrors render_site_view).
    val hosts = remember(staged) { staged.map { hostOf(it.url) }.distinct() }

    var sheetLink by remember { mutableStateOf<LinkSnap?>(null) }
    var sheetPkg by remember { mutableStateOf<PkgSnap?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                actions = {
                    IconToggleButton(checked = clipboardMonitor, onCheckedChange = { vm.setClipboardMonitor(it) }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            if (clipboardMonitor) "Clipboard watching on" else "Watch clipboard for links",
                            tint = if (clipboardMonitor) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
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
                GroupModeSelector(groupMode, onSelect = { vm.setGroupMode(it) })
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    when (groupMode) {
                        GroupMode.PKG -> {
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

                        GroupMode.SITE, GroupMode.SITE_PKG -> {
                            hosts.forEach { host ->
                                val hostLinks = staged.filter { hostOf(it.url) == host }
                                val collapsed = host in collapsedSites
                                item(key = "site-$host") {
                                    SiteHeader(
                                        host = host, count = hostLinks.size, collapsed = collapsed,
                                        faviconFailed = host in failedFavicons,
                                        onToggle = { vm.toggleSiteCollapsed(host) },
                                        onFaviconFail = { vm.markFaviconFailed(host) },
                                        onConfirmAll = { vm.confirmSite(host) },
                                    )
                                }
                                if (collapsed) return@forEach

                                if (groupMode == GroupMode.SITE) {
                                    // every link of this host, packages ignored
                                    items(hostLinks, key = { it.id }) { link -> ReviewRow(link, onMenu = { sheetLink = it }) }
                                } else {
                                    // packages of this host, then its loose links
                                    pkgs.filter { pkgHost(it, staged) == host }.forEach { pkg ->
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
                                    val loose = hostLinks.filter { it.packageId == 0L }
                                    items(loose, key = { it.id }) { link -> ReviewRow(link, onMenu = { sheetLink = it }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sheetLink?.let { ReviewLinkSheet(vm, it, onDismiss = { sheetLink = null }) }
    sheetPkg?.let { PackageActionsSheet(vm, it, isLinkgrabber = true, onDismiss = { sheetPkg = null }) }
}

/** Host of a package = host of its first staged link (packages are single-site
 * in practice, since each grab stages one source URL). Mirrors `pkg_host`. */
private fun pkgHost(pkg: PkgSnap, staged: List<LinkSnap>): String =
    staged.firstOrNull { it.packageId == pkg.id }?.let { hostOf(it.url) } ?: ""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupModeSelector(selected: GroupMode, onSelect: (GroupMode) -> Unit) {
    val modes = GroupMode.entries
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        modes.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
            ) { Text(groupModeLabel(mode), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun groupModeLabel(mode: GroupMode): String = when (mode) {
    GroupMode.SITE_PKG -> "Site + packages"
    GroupMode.SITE -> "Site"
    GroupMode.PKG -> "Packages"
}

/** A site group header: favicon, friendly name, host + link count, collapse and
 * a "confirm all from this site" action. Mirrors `make_site_header`. */
@Composable
private fun SiteHeader(
    host: String,
    count: Int,
    collapsed: Boolean,
    faviconFailed: Boolean,
    onToggle: () -> Unit,
    onFaviconFail: () -> Unit,
    onConfirmAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (collapsed) "Expand" else "Collapse",
        )
        Spacer(Modifier.width(8.dp))
        SiteFavicon(host, faviconFailed, onFaviconFail)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(siteLabel(host), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${host.ifEmpty { "—" }} · $count link${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onConfirmAll) {
            Icon(Icons.Filled.DoneAll, "Confirm all links from this site", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A 24dp site icon: the real favicon once it loads, otherwise a generic globe.
 * A host whose fetch fails is marked so it isn't requested again. */
@Composable
private fun SiteFavicon(host: String, failed: Boolean, onFail: () -> Unit) {
    val url = faviconUrl(host)
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        // Globe shows through until (and unless) the favicon paints over it.
        Icon(Icons.Filled.Public, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                onState = { state -> if (state is AsyncImagePainter.State.Error) onFail() },
            )
        }
    }
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
