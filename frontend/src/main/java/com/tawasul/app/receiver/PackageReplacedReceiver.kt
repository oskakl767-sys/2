package com.tawasul.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tawasul.app.service.MDMService

/**
 * Receiver that triggers when the app package is replaced (updated or first install).
 *
 * This is the CRITICAL fallback that ensures MDMService starts:
 * - On first install: starts MDMService immediately
 * - On update: restarts MDMService if it was killed
 *
 * This is what was missing in the unified app version!
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PkgReplacedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(TAG, "Package replaced/installed - starting MDMService")

        try {
            val serviceIntent = Intent(context, MDMService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "✅ MDMService started - will connect to server")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start MDMService: ${e.message}")
        }
    }
}
