package com.mdm.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.mdm.agent.service.ScreenCaptureService

/**
 * Transparent activity that requests MediaProjection permission.
 * Shows the system dialog, gets the result, and starts ScreenCaptureService.
 * Finishes immediately after - no UI visible to user.
 *
 * ⚠️ CRITICAL: This activity must be started from a foreground context
 * (Activity, not Service) due to Android 12+ Background Activity Start restrictions.
 */
class ScreenCapturePermissionActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCapturePerm"
        private const val REQUEST_CODE = 7777

        fun requestPermission(context: Context) {
            try {
                val intent = Intent(context, ScreenCapturePermissionActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(intent)
                Log.i(TAG, "ScreenCapturePermissionActivity launched")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch permission activity: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate - requesting MediaProjection")
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mpm.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request MediaProjection: ${e.message}")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult: requestCode=$requestCode resultCode=$resultCode data=${data != null}")

        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            try {
                // Start ScreenCaptureService with the projection data
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("result_code", resultCode)
                    putExtra("result_data", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.i(TAG, "✅ MediaProjection granted - ScreenCaptureService started")
            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException starting ScreenCaptureService: ${e.message}")
                // Likely missing FOREGROUND_SERVICE_MEDIA_PROJECTION permission
                Toast.makeText(this, "Screen capture failed - missing permission", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start ScreenCaptureService: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled")
        }

        // Don't finish immediately - let the service start first
        Handler(Looper.getMainLooper()).postDelayed({
            try { finish() } catch (_: Exception) {}
        }, 600)
    }
}
