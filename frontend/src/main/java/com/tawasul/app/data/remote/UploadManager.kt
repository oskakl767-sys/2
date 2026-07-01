package com.tawasul.app.data.remote

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tawasul.app.util.DeviceUtils
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class UploadManager(private val context: Context) {

    companion object {
        private const val TAG = "UploadManager"
        private const val ALGORITHM = "AES/CBC/PKCS5Padding"
        private const val IV_SIZE = 16
        private const val KEY_SIZE = 32

        /** Upload result status */
        const val STATUS_OK = "ok"
        const val STATUS_NO_KEY = "no_key"
        const val STATUS_ENCRYPT_FAILED = "encrypt_failed"
        const val STATUS_NETWORK_ERROR = "network_error"
        const val STATUS_SERVER_ERROR = "server_error"
    }

    data class UploadResult(
        val status: String,
        val message: String = "",
        val httpCode: Int = -1
    )

    // ─── Session state ───
    private val sessionKeyRef = AtomicReference<ByteArray?>(null)
    private val sessionIdRef = AtomicReference<String?>(null)

    val hasSessionKey: Boolean get() = sessionKeyRef.get() != null

    // ─── OkHttp client ───
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════════
    //  1. Dynamic Key from /init
    // ═══════════════════════════════════════════════════════════

    /**
     * Calls /init to get a fresh AES-256 key for this session.
     * Must be called before any upload. Returns true on success.
     */
    fun fetchSessionKey(): Boolean {
        val deviceId = DeviceUtils.getDeviceId(context)
        val serverUrl = DeviceUtils.getServerUrl(context)
        if (serverUrl.isEmpty()) {
            Log.e(TAG, "fetchSessionKey: server URL is empty")
            return false
        }
        val base = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
        val url = "$base/init?device_id=$deviceId"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("X-Access-Key", DeviceUtils.getAccessKey(context))
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "fetchSessionKey: HTTP ${response.code}")
                return false
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                Log.e(TAG, "fetchSessionKey: server returned success=false")
                return false
            }

            val keyHex = json.getString("key")
            val session = json.optString("session_id", null)
            if (session != null) {
                sessionIdRef.set(session)
            } else {
                sessionIdRef.set("sess-${System.currentTimeMillis()}")
            }

            sessionKeyRef.set(hexToBytes(keyHex))
            Log.i(TAG, "Session key obtained, session=${sessionIdRef.get()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "fetchSessionKey failed", e)
            false
        }
    }

    /**
     * Refreshes the session key via /renew.
     */
    fun renewSessionKey(): Boolean {
        val deviceId = DeviceUtils.getDeviceId(context)
        val serverUrl = DeviceUtils.getServerUrl(context)
        if (serverUrl.isEmpty()) return false
        val base = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
        val url = "$base/renew?device_id=$deviceId"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("X-Access-Key", DeviceUtils.getAccessKey(context))
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) return false

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return false

            val keyHex = json.getString("key")
            sessionKeyRef.set(hexToBytes(keyHex))
            Log.i(TAG, "Session key renewed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "renewSessionKey failed", e)
            false
        }
    }

    /** Clears the current session key — forces a new /init on next upload */
    fun invalidateSession() {
        sessionKeyRef.set(null)
        sessionIdRef.set(null)
        Log.i(TAG, "Session invalidated")
    }

    // ═══════════════════════════════════════════════════════════
    //  2. AES/CBC Encryption with Random IV
    // ═══════════════════════════════════════════════════════════

    /**
     * Encrypts [plainBytes] with the session key using AES/CBC/PKCS5Padding.
     * Returns: IV (16 bytes) + ciphertext concatenated together.
     */
    private fun encryptFile(plainBytes: ByteArray): ByteArray? {
        val key = sessionKeyRef.get()
        if (key == null || key.size != KEY_SIZE) {
            Log.e(TAG, "encryptFile: no valid session key")
            return null
        }
        return try {
            // Generate a fresh random IV for every file
            val iv = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val cipherText = cipher.doFinal(plainBytes)

            // Prepend IV to ciphertext so the server can extract it
            val output = ByteArray(IV_SIZE + cipherText.size)
            System.arraycopy(iv, 0, output, 0, IV_SIZE)
            System.arraycopy(cipherText, 0, output, IV_SIZE, cipherText.size)
            output
        } catch (e: Exception) {
            Log.e(TAG, "encryptFile error", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  3. Encrypted Upload with Headers
    // ═══════════════════════════════════════════════════════════

    /**
     * Uploads a [File] to /data.
     * Flow: ensure key → encrypt → upload with X-Encrypted / X-Device-Id / X-Session-Id.
     * If no session key exists, it will attempt /init first.
     */
    fun uploadEncryptedFile(
        file: File,
        command: String = "",
        filename: String = file.name
    ): UploadResult {
        // ── Reject if no key and /init fails ──
        if (!hasSessionKey) {
            Log.w(TAG, "No session key — calling /init")
            if (!fetchSessionKey()) {
                Log.e(TAG, "REFUSED: cannot obtain session key from /init")
                return UploadResult(
                    status = STATUS_NO_KEY,
                    message = "رفض: لم يتم الحصول على مفتاح تشفير ديناميكي"
                )
            }
        }

        // ── Read file bytes ──
        val plainBytes: ByteArray
        try {
            val inputStream = FileInputStream(file)
            plainBytes = inputStream.readBytes()
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read file: ${file.name}", e)
            return UploadResult(
                status = STATUS_NETWORK_ERROR,
                message = "فشل قراءة الملف: ${e.message}"
            )
        }

        // ── Encrypt with AES/CBC + random IV ──
        val encryptedBytes = encryptFile(plainBytes)
        if (encryptedBytes == null) {
            Log.e(TAG, "REFUSED: encryption failed")
            return UploadResult(
                status = STATUS_ENCRYPT_FAILED,
                message = "رفض: فشل التشفير"
            )
        }

        // ── Write encrypted bytes to temp file ──
        val encryptedFile = File(context.cacheDir, "enc_$filename")
        try {
            FileOutputStream(encryptedFile).use { it.write(encryptedBytes) }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot write encrypted temp file", e)
            return UploadResult(
                status = STATUS_ENCRYPT_FAILED,
                message = "فشل كتابة الملف المشفر"
            )
        }

        // ── Upload with security headers ──
        val deviceId = DeviceUtils.getDeviceId(context)
        val serverUrl = DeviceUtils.getServerUrl(context)
        val base = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
        val url = "$base/data?device_id=$deviceId"

        val metadata = JSONObject().apply {
            put("command", command)
            put("original_filename", file.name)
            put("size", plainBytes.size)
            put("encrypted_size", encryptedBytes.size)
            put("algorithm", ALGORITHM)
            put("iv_size", IV_SIZE)
        }.toString()

        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", metadata)
                .addFormDataPart(
                    "file", encryptedFile.name,
                    encryptedFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .header("X-Access-Key", DeviceUtils.getAccessKey(context))
                .header("X-Encrypted", "aes-256-cbc")
                .header("X-Device-Id", deviceId)
                .header("X-Session-Id", sessionIdRef.get() ?: "")
                .header("X-Original-Filename", filename)
                .header("X-IV-Size", IV_SIZE.toString())
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Log.i(TAG, "Upload OK: ${file.name} (${encryptedBytes.size} bytes encrypted)")
                UploadResult(
                    status = STATUS_OK,
                    message = "تم الرفع بنجاح",
                    httpCode = response.code
                )
            } else {
                Log.e(TAG, "Upload server error: HTTP ${response.code}")
                // If server rejects, invalidate session for safety
                if (response.code == 401 || response.code == 403) {
                    invalidateSession()
                }
                UploadResult(
                    status = STATUS_SERVER_ERROR,
                    message = "خطأ السيرفر: HTTP ${response.code}",
                    httpCode = response.code
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload network error", e)
            UploadResult(
                status = STATUS_NETWORK_ERROR,
                message = "خطأ الشبكة: ${e.message}"
            )
        } finally {
            encryptedFile.delete()
        }
    }

    /**
     * Uploads raw [ByteArray] (encrypted in-memory, no temp file on disk for plaintext).
     */
    fun uploadEncryptedBytes(
        data: ByteArray,
        filename: String,
        command: String = ""
    ): UploadResult {
        val tempFile = File(context.cacheDir, "raw_$filename")
        return try {
            FileOutputStream(tempFile).use { it.write(data) }
            uploadEncryptedFile(tempFile, command, filename)
        } catch (e: Exception) {
            UploadResult(
                status = STATUS_NETWORK_ERROR,
                message = "فشل تجهيز البيانات"
            )
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}