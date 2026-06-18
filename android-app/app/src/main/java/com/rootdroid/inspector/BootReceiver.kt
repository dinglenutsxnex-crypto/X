package com.rootdroid.inspector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.rootdroid.inspector.service.InspectorOverlayService
import com.rootdroid.inspector.virtual.FakeSuProvider

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        FakeSuProvider.install(context)
        if (Settings.canDrawOverlays(context)) {
            context.startForegroundService(Intent(context, InspectorOverlayService::class.java))
        }
    }
}
