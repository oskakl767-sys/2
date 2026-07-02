package com.mdm.agent.util

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceUtils {

    fun getDeviceId(context: Context): String {
        // Use ANDROID_ID - unique per device, survives app reinstall
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        return "MDM-${Build.MANUFACTURER}-${Build.MODEL}-$androidId"
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

    fun getServerUrl(context: Context): String {
        val saved = context.getSharedPreferences("mdm", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        return saved.ifEmpty { "https://one-1rre.onrender.com" }
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