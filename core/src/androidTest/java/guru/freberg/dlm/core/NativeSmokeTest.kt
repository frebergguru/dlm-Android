// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import guru.freberg.dlm.core.jni.NativeLib
import guru.freberg.dlm.core.jni.NativeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Confirms the cross-compiled native deps link and run on a real ABI, and that
 * the JNI ↔ Kotlin store round-trip works end to end. Run on an emulator/device:
 *   ./gradlew :core:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {

    @Test fun nativeLibLoadsAndParsesRates() {
        assertNotNull(NativeLib.version())
        assertEquals(2L * 1024 * 1024, NativeLib.parseRate("2M"))
        assertEquals(0L, NativeLib.parseRate(""))
        assertTrue(NativeLib.formatRate(1536).contains("/s"))
    }

    @Test fun storeRoundTrip() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = File(ctx.filesDir, "dlm-test").apply { mkdirs() }
        NativeLib.ensureInit(cfg.path, File(cfg, "cacert.pem").path)

        val dbFile = File(cfg, "smoke-${System.nanoTime()}.db")
        val store = NativeStore.open(dbFile.path)!!
        try {
            val id = store.add("https://example.com/a.bin", "${cfg.path}/a.bin", 4, 0, 1000)
            assertTrue(id > 0)
            store.setProgress(id, 100, 50)
            store.setPriority(id, 2)
            val rows = store.loadAll()
            val row = rows.single { it.id == id }
            assertEquals(100, row.total)
            assertEquals(50, row.downloaded)
            assertEquals(2, row.priority)
        } finally {
            store.close()
            dbFile.delete()
        }
    }
}
