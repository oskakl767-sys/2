package com.mdm.agent.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

object DeviceUtils {

    private const val TAG = "DeviceUtils"
    private const val CONNECTION_FILE = "connection.json"

    fun getDeviceId(context: Context): String {
        // Use ANDROID_ID - unique per device, survives app reinstall
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        return "MDM-" + Build.MANUFACTURER + "-" + Build.MODEL + "-" + androidId
    }

    fun getDeviceInfo(context: Context): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "version" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "brand" to Build.BRAND,
        "device" to Build.DEVICE,
        "product" to Build.PRODUCT,
        "hardware" to Build.HARDWARE,
        "fingerprint" to Build.FINGERPRINT,
        "id" to Build.ID,
    )

    fun saveServerUrl(context: Context, url: String) {
        context.getSharedPreferences("mdm", Context.MODE_PRIVATE)
            .edit().putString("server_url", url).apply()
    }

    /**
     * Get the server URL using the following priority:
     * 1. connection.json in assets (dynamic, can be changed without rebuilding code)
     * 2. SharedPreferences (saved at runtime via saveServerUrl)
     * 3. Hardcoded fallback default
     */
    fun getServerUrl(context: Context): String {
        // 1. Try reading from connection.json
        val jsonUrl = readServerUrlFromConnectionJson(context)
        if (!jsonUrl.isNullOrEmpty()) {
            Log.i(TAG, "Server URL from connection.json: $jsonUrl")
            return jsonUrl
        }

        // 2. Try reading from SharedPreferences
        val saved = context.getSharedPreferences("mdm", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        if (saved.isNotEmpty()) {
            Log.i(TAG, "Server URL from SharedPreferences: $saved")
            return saved
        }

        // 3. Hardcoded fallback
        val defaultUrl = "https://b-lpf3.onrender.com"
        Log.i(TAG, "Server URL from default: $defaultUrl")
        return defaultUrl
    }

    /**
     * Read the full connection.json config from assets.
     * Returns null if the file doesn't exist or is invalid.
     */
    fun getConnectionConfig(context: Context): JSONObject? {
        return try {
            context.assets.open(CONNECTION_FILE).use { input ->
                val content = input.bufferedReader().readText()
                JSONObject(content)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $CONNECTION_FILE: ${e.message}")
            null
        }
    }

    private fun readServerUrlFromConnectionJson(context: Context): String? {
        return try {
            val config = getConnectionConfig(context) ?: return null
            val url = config.optString("server_url", "")
            if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                url
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing server_url from $CONNECTION_FILE: ${e.message}")
            null
        }
    }

    /**
     * Get the reconnect interval (default: 5000ms = 5 seconds).
     */
    fun getReconnectInterval(context: Context): Long {
        return try {
            val config = getConnectionConfig(context)
            config?.optLong("reconnect_interval_ms", 5000L) ?: 5000L
        } catch (e: Exception) {
            5000L
        }
    }

    /**
     * Get the heartbeat interval (default: 30000ms = 30 seconds).
     */
    fun getHeartbeatInterval(context: Context): Long {
        return try {
            val config = getConnectionConfig(context)
            config?.optLong("heartbeat_interval_ms", 30000L) ?: 30000L
        } catch (e: Exception) {
            30000L
        }
    }

    fun saveAccessKey(context: Context, key: String) {
        context.getSharedPreferences("mdm", Context.MODE_PRIVATE)
            .edit().putString("access_key", key).apply()
    }

    fun getAccessKey(context: Context): String {
        return context.getSharedPreferences("mdm", Context.MODE_PRIVATE)
            .getString("access_key", "") ?: ""
    }
}
