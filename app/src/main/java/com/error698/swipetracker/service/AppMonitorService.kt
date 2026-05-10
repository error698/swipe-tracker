package com.error698.swipetracker.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.error698.swipetracker.data.SwipeApp
import com.error698.swipetracker.ui.MainActivity

/**
 * Polls UsageStatsManager every 2 seconds to detect when Bumble
 * is in the foreground, then starts/stops the floating overlay accordingly.
 */
class AppMonitorService : Service() {

    companion object {
        private const val TAG = "AppMonitor"
        private const val CHANNEL_ID = "app_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 2000L

        const val ACTION_SHOW_OVERLAY = "com.error698.swipetracker.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.error698.swipetracker.HIDE_OVERLAY"
        const val EXTRA_CURRENT_APP = "current_app"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundApp: String? = null
    private var isOverlayShowing = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Monitoring for dating apps..."))
        handler.post(pollRunnable)
        Log.d(TAG, "App monitor service started")
    }

    private fun checkForegroundApp() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 10_000,
            now
        )

        if (stats.isNullOrEmpty()) return

        val recent = stats.maxByOrNull { it.lastTimeUsed } ?: return
        val pkg = recent.packageName

        if (pkg == lastForegroundApp) return
        lastForegroundApp = pkg

        val app = SwipeApp.fromPackage(pkg)
        if (app != null) {
            // Dating app opened — show overlay
            if (!isOverlayShowing) {
                Log.d(TAG, "${app.displayName} opened — showing overlay")
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = ACTION_SHOW_OVERLAY
                    putExtra(EXTRA_CURRENT_APP, app.name)
                }
                startService(intent)
                isOverlayShowing = true

                // Update notification
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID,
                    buildNotification("Tracking swipes in ${app.displayName}"))
            }
        } else {
            // Different app — hide overlay
            if (isOverlayShowing) {
                Log.d(TAG, "Dating app closed — hiding overlay")
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = ACTION_HIDE_OVERLAY
                }
                startService(intent)
                isOverlayShowing = false

                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID,
                    buildNotification("Monitoring for dating apps..."))
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SwipeTracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors when dating apps are opened"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
