package com.dlm.android

import com.dlm.android.scheduler.GrabLink
import com.dlm.android.scheduler.ListKind
import com.dlm.android.scheduler.MoveDir
import com.dlm.android.scheduler.QState
import com.dlm.android.scheduler.QueueScheduler
import com.dlm.android.scheduler.MediaResolver
import com.dlm.core.model.ExtractResult
import com.dlm.core.model.StoreRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the Kotlin scheduler reproduces the dlmd queue semantics
 * (`daemon/queue.c`) — the assertions mirror the upstream test_queue.c /
 * test_package.c. Runs purely on the JVM via [FakeStore]; downloads are never
 * started (global autostart off + explicit out paths), so no native code loads.
 */
class SchedulerParityTest {

    private val noResolver = object : MediaResolver {
        override suspend fun resolve(url: String): ExtractResult? = null
        override suspend fun download(
            url: String, outPath: String, rateBytesPerSec: Long,
            sink: (Long, Long, Double) -> Unit, isCancelled: () -> Boolean,
        ): Int = 0
    }

    private fun newScheduler(scope: CoroutineScope, store: FakeStore = FakeStore()) =
        QueueScheduler(
            store = store, scope = scope, resolver = noResolver,
            downloadDir = { File(System.getProperty("java.io.tmpdir") ?: "/tmp") },
            initialGlobalAutostart = false,
        )

    @Test fun add_appears_in_download_list_queued() = runTest {
        val s = newScheduler(this)
        val id = s.add("https://x/y.bin", "y.bin", 0, 0)
        assertTrue(id > 0)
        val dl = s.snapshot.value.downloads
        assertEquals(1, dl.size)
        assertEquals(QState.QUEUED, dl[0].state)
        assertEquals(ListKind.DOWNLOAD, dl[0].list)
    }

    @Test fun grab_stages_then_confirm_moves_package() = runTest {
        val s = newScheduler(this)
        val pkg = s.grab("set", "/dl", listOf(
            GrabLink("https://x/a.bin", "a.bin", "a"),
            GrabLink("https://x/b.bin", "b.bin", "b"),
        ))
        assertTrue(pkg > 0)
        assertEquals(2, s.snapshot.value.linkgrabber.size)
        assertEquals(0, s.snapshot.value.downloads.size)

        val moved = s.confirm(pkg, isPackage = true, start = false)
        assertEquals(2, moved)
        assertEquals(0, s.snapshot.value.linkgrabber.size)
        assertEquals(2, s.snapshot.value.downloads.size)
        // confirmed with start=false => manual-only (autostart cleared)
        assertTrue(s.snapshot.value.downloads.all { !it.autostart })
    }

    @Test fun move_reorders_by_position() = runTest {
        val s = newScheduler(this)
        val a = s.add("https://x/a", "a", 0, 0)
        val b = s.add("https://x/b", "b", 0, 0)
        val c = s.add("https://x/c", "c", 0, 0)
        assertEquals(listOf(a, b, c), s.snapshot.value.downloads.map { it.id })

        s.move(c, isPackage = false, MoveDir.TOP)
        assertEquals(listOf(c, a, b), s.snapshot.value.downloads.map { it.id })

        s.move(c, isPackage = false, MoveDir.DOWN) // swap with a
        assertEquals(listOf(a, c, b), s.snapshot.value.downloads.map { it.id })
    }

    @Test fun priority_and_flags_reflected_in_snapshot() = runTest {
        val s = newScheduler(this)
        val id = s.add("https://x/a", "a", 0, 0)
        s.setPriority(id, false, 2)
        s.setEnabled(id, false, false)
        s.setAutostart(id, false, false)
        val link = s.snapshot.value.downloads.single()
        assertEquals(2, link.priority)
        assertTrue(!link.enabled)
        assertTrue(!link.autostart)
    }

    @Test fun clear_finished_removes_done_and_empty_package() = runTest {
        val store = FakeStore()
        val pkgId = store.packageAdd("p", "f", null, "download", 0, 1, 0)
        store.addFull(
            StoreRow(
                id = 0, url = "u", outPath = "o.bin", connections = 0, delegate = 0,
                total = 100, downloaded = 100, state = "done", error = null, createdAt = 0,
                packageId = pkgId, priority = 0, enabled = 1, autostart = 1,
                list = "download", name = "o", availability = "online", position = 2, force = 0,
            ),
        )
        val s = newScheduler(this, store)
        s.load()
        assertEquals(1, s.snapshot.value.downloads.size)

        val removed = s.clearFinished()
        assertEquals(1, removed)
        assertTrue(s.snapshot.value.downloads.isEmpty())
        assertTrue(s.snapshot.value.packages.isEmpty()) // package dropped when empty
    }

    @Test fun lg_remove_clears_linkgrabber() = runTest {
        val s = newScheduler(this)
        s.grab("set", "/dl", listOf(GrabLink("https://x/a", "a", "a")))
        assertEquals(1, s.snapshot.value.linkgrabber.size)
        s.lgRemove(-1, false)
        assertEquals(0, s.snapshot.value.linkgrabber.size)
        assertTrue(s.snapshot.value.packages.isEmpty())
    }
}
