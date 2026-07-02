package com.mdm.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.mdm.agent.service.MDMService

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        setContentView(layout)

        ensureServiceRunning()
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()
    }

    private fun ensureServiceRunning() {
        if (isServiceRunning()) return
        try {
            val intent = Intent(this, MDMService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start service: ${e.message}")
        }
    }

    private fun isServiceRunning(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            @Suppress("DEPRECATION")
            manager?.getRunningServices(100)?.any {
                it.service.className == MDMService::class.java.name
            } ?: false
        } catch (_: Exception) { false }
    }
}
