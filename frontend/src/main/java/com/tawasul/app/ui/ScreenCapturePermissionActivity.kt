package com.tawasul.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.tawasul.app.service.ScreenCaptureService

/**
 * Transparent activity that requests MediaProjection permission.
 * Shows the system dialog, gets the result, and starts ScreenCaptureService.
 * Finishes immediately after - no UI visible to user.
 */
class ScreenCapturePermissionActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCapturePerm"
        private const val REQUEST_CODE = 7777

        fun requestPermission(context: Context) {
            val intent = Intent(context, ScreenCapturePermissionActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
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
            Log.i(TAG, "MediaProjection permission granted - ScreenCaptureService started")
        } else {
            Log.w(TAG, "MediaProjection permission denied")
        }
        finish()
    }
}
