package com.tawasul.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tawasul.app.service.MDMService

/**
 * Exported receiver that allows OTHER apps (specifically Tawasul cover app)
 * to start MDMService without violating Android's exported=false restriction.
 *
 * Tawasul sends a broadcast with action "com.mdm.agent.action.START_SERVICE"
 * after installing MDM, and this receiver starts MDMService within the MDM
 * app's own process (allowed since it's the same app).
 *
 * This is the ONLY reliable way to start MDMService after first install,
 * since:
 *  - PackageReplacedReceiver only fires on updates, not first install
 *  - MDMService is exported=false, so Tawasul cannot start it directly
 *  - Background activity start restrictions on Android 10+
 */
class StartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "StartReceiver"
        const val ACTION_START = "com.mdm.agent.action.START_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received broadcast: ${intent.action}")

        if (intent.action != ACTION_START) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }

        try {
            val serviceIntent = Intent(context, MDMService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "✅ MDMService started successfully via broadcast")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start MDMService: ${e.message}", e)
        }
    }
}
