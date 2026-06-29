// SPDX-License-Identifier: GPL-3.0-or-later
package guru.freberg.dlm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import guru.freberg.dlm.R
import guru.freberg.dlm.repo.AppContainer
import guru.freberg.dlm.repo.QueueRepository
import guru.freberg.dlm.scheduler.QState
import guru.freberg.dlm.scheduler.QueueSnapshot
import guru.freberg.dlm.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foreground service that keeps the process alive and drives the scheduler tick
 * while there is work, replacing the standalone dlmd daemon. Declared as a
 * `dataSync` foreground service (Android 14 requirement). Holds a CPU wakelock +
 * WifiLock during active transfers and self-stops when the queue goes idle.
 */
class DownloadService : LifecycleService() {

    private lateinit var container: AppContainer
    private val repo: QueueRepository get() = container.repository

    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val exported = HashSet<Long>()
    private var idleTicks = 0

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)
        container.ensureLoaded()
        createChannel()
        startForegroundNow(QueueSnapshot())
        startTickLoop()
        observeForExport()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Re-assert foreground in case we were restarted by the system.
        startForegroundNow(repo.snapshot.value)
        // Revive ticking: the loop self-terminates when the queue goes idle, so a
        // start command to a surviving idle instance must restart it (idempotent).
        startTickLoop()
        return START_STICKY
    }

    private fun startTickLoop() {
        if (tickJob?.isActive == true) return
        tickJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    container.scheduler.tick()
                    val snap = repo.snapshot.value
                    updateForeground(snap)
                    updateLocks(snap)
                    if (!hasWork(snap)) {
                        if (++idleTicks >= IDLE_TICKS_BEFORE_STOP) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            break
                        }
                    } else {
                        idleTicks = 0
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // One bad tick (e.g. a transient native/store error) must not
                    // kill the loop: that would crash the process and leave the
                    // wake/wifi locks held with no path to stopSelf(). Log and
                    // keep ticking.
                    Log.w(TAG, "tick failed", t)
                }
                delay(TICK_MS)
            }
        }
    }

    /** Copy finished files into the user's SAF tree when auto-export is on. */
    private fun observeForExport() {
        lifecycleScope.launch {
            // repo.snapshot is a StateFlow, which already conflates: a busy collector
            // (the blocking SAF copy below) only ever resumes on the latest snapshot,
            // so bursts of ~5 Hz progress emissions never queue redundant export passes.
            repo.snapshot.collect { snap ->
                if (!repo.autoExport || repo.downloadTreeUri() == null) return@collect
                val pending = snap.downloads.filter { it.state == QState.DONE && it.id !in exported }
                if (pending.isNotEmpty()) {
                    // Filesystem checks + SAF copy are blocking; keep them off the
                    // main thread. Single collector → no concurrent `exported` access.
                    withContext(Dispatchers.IO) {
                        for (link in pending) {
                            if (!File(link.outPath).exists()) {
                                // Source already gone; mark handled so we don't rescan it forever.
                                exported += link.id
                            } else if (runCatching { repo.exportToTree(link.outPath) }.getOrDefault(false)) {
                                // Only record success, so a transient SAF failure is retried.
                                exported += link.id
                            }
                        }
                    }
                }
                // Bound the set: drop ids no longer present in the queue. Skip the
                // allocation entirely when nothing has been exported yet.
                if (exported.isNotEmpty()) {
                    exported.retainAll(snap.downloads.mapTo(HashSet(snap.downloads.size)) { it.id })
                }
            }
        }
    }

    private fun hasWork(s: QueueSnapshot): Boolean {
        if (s.activeCount > 0) return true
        val forced = s.downloads.any { it.state == QState.QUEUED && it.force && it.enabled }
        val autoEligible = s.globalAutostart && s.downloads.any {
            it.state == QState.QUEUED && it.enabled && it.autostart
        }
        return forced || autoEligible
    }

    // ---- locks ------------------------------------------------------------

    private fun updateLocks(s: QueueSnapshot) {
        if (s.activeCount > 0) acquireLocks() else releaseLocks()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dlm:downloads")
                .also { it.setReferenceCounted(false) }
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "dlm:downloads")
                .also { it.setReferenceCounted(false) }
        }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    // ---- notification -----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.download_channel_desc) }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun startForegroundNow(s: QueueSnapshot) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(s), type)
    }

    private var lastNotifAt = 0L
    private fun updateForeground(s: QueueSnapshot) {
        // System rate-limits notification updates; refresh ~1s.
        val now = System.nanoTime() / 1_000_000
        if (now - lastNotifAt < 1000) return
        lastNotifAt = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: QueueSnapshot): Notification {
        val active = s.activeCount
        val speed = repo.formatRate(s.totalSpeedBps.toLong())
        val title = when {
            active == 1 -> "Downloading 1 file"
            active > 1 -> "Downloading $active files"
            else -> "dlm"
        }
        val waiting = s.downloads.count { it.state == guru.freberg.dlm.scheduler.QState.QUEUED }
        val text = when {
            active > 0 -> speed
            waiting > 0 -> "$waiting waiting"
            else -> "Up to date"
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(active > 0)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DownloadService"
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 1
        const val TICK_MS = 200L
        const val IDLE_TICKS_BEFORE_STOP = 30 // ~6s of no work
        const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L // safety cap
    }
}
