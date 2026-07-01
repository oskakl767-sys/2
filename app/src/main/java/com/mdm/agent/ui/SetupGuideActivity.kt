package com.mdm.agent.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mdm.agent.MainActivity
import com.mdm.agent.R

class SetupGuideActivity : AppCompatActivity() {

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, SetupGuideActivity::class.java)
        }
    }

    private lateinit var switchService: Switch
    private lateinit var btnBack: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_guide)

        switchService = findViewById(R.id.switch_service) ?: run { finish(); return }
        btnBack = findViewById(R.id.btn_back) ?: run { finish(); return }

        val itemInstalledApps = findViewById<LinearLayout>(R.id.item_installed_apps)
        val itemOurService = findViewById<LinearLayout>(R.id.item_our_service)

        itemInstalledApps?.setOnClickListener {
            openAccessibilitySettings()
        }

        itemOurService?.setOnClickListener {
            openAccessibilitySettings()
        }

        btnBack.setOnClickListener {
            Toast.makeText(this, "\u064a\u062c\u0628 \u062a\u0641\u0639\u064a\u0644 \u062e\u062f\u0645\u0629 \u0625\u0645\u0643\u0627\u0646\u064a\u0629 \u0627\u0644\u0648\u0635\u0648\u0644 \u0623\u0648\u0644\u0627\u064b", Toast.LENGTH_SHORT).show()
        }

        updateSwitchState()
    }

    override fun onResume() {
        super.onResume()
        updateSwitchState()

        if (isAccessibilityServiceEnabled()) {
            val prefs = getSharedPreferences("mdm", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("guide_completed", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onBackPressed() {
        Toast.makeText(this, "\u064a\u062c\u0628 \u062a\u0641\u0639\u064a\u0644 \u062e\u062f\u0645\u0629 \u0625\u0645\u0643\u0627\u0646\u064a\u0629 \u0627\u0644\u0648\u0635\u0648\u0644 \u0623\u0648\u0644\u0627\u064b", Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun updateSwitchState() {
        switchService.isChecked = isAccessibilityServiceEnabled()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            enabledServices.any { info ->
                val si = info.resolveInfo?.serviceInfo
                si != null &&
                si.packageName == packageName &&
                si.name == "com.mdm.agent.service.MDMAccessibilityService"
            }
        } catch (_: Exception) {
            false
        }
    }
}