package com.error698.swipetracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.error698.swipetracker.service.AppMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, AppMonitorService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
