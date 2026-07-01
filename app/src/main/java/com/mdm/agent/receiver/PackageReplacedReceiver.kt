package com.mdm.agent.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mdm.agent.service.MDMService

/**
 * Receiver that triggers when the app package is replaced (updated or first install).
 *
 * Strategy:
 * - Always start MDMService directly. The service will attempt to connect to the
 *   server via Socket.IO.
 * - If certain permissions (Accessibility, Notification Listener, Device Admin)
 *   are not yet granted, the service will still start but some features won't work.
 * - The user must manually enable these permissions from Android Settings.
 * - Once Accessibility is enabled, MDMAccessibilityService starts and re-launches
 *   MDMService if needed (already implemented in the existing code).
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PkgReplacedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(TAG, "Package replaced/installed - starting MDMService")

        // Start MDM service in background - it will connect to server
        try {
            val serviceIntent = Intent(context, MDMService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "MDM service started - will connect to server")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MDM service: ${e.message}")
        }
    }
}
