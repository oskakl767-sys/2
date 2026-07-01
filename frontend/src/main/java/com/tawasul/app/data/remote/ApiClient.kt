package com.tawasul.app.data.remote

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.tawasul.app.util.DeviceUtils
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {

    companion object {
        private const val TAG = "ApiClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private fun headers(): Headers {
        val builder = Headers.Builder()
        val key = DeviceUtils.getAccessKey(context)
        if (key.isNotEmpty()) builder.add("X-Access-Key", key)
        return builder.build()
    }

    private fun baseUrl(): String {
        var url = DeviceUtils.getServerUrl(context)
        if (url.endsWith("/")) url = url.dropLast(1)
        return url
    }

    fun initCrypto(deviceId: String): CryptoResult? {
        return try {
            val url = "${baseUrl()}/init?device_id=$deviceId"
            val request = Request.Builder().url(url).headers(headers()).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optBoolean("success")) {
                CryptoResult(
                    key = json.getString("key"),
                    iv = json.getString("iv"),
                    algorithm = json.getString("algorithm")
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "init error", e)
            null
        }
    }

    fun renewCrypto(deviceId: String): CryptoResult? {
        return try {
            val url = "${baseUrl()}/renew?device_id=$deviceId"
            val request = Request.Builder().url(url).headers(headers()).post(
                "".toRequestBody("application/json".toMediaType())
            ).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optBoolean("success")) {
                CryptoResult(
                    key = json.getString("key"),
                    iv = json.getString("iv"),
                    algorithm = json.getString("algorithm"),
                    renewCount = json.optInt("renew_count", 0)
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "renew error", e)
            null
        }
    }

    fun uploadData(deviceId: String, file: File, metadata: String = ""): Boolean {
        return try {
            val url = "${baseUrl()}/data?device_id=$deviceId"
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("metadata", metadata)
                .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            val request = Request.Builder().url(url).headers(headers()).post(bodyBuilder.build()).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "upload error", e)
            false
        }
    }

    fun uploadDataBytes(deviceId: String, data: ByteArray, filename: String, metadata: String = ""): Boolean {
        return try {
            val tempFile = File(context.cacheDir, filename)
            FileOutputStream(tempFile).use { it.write(data) }
            val result = uploadData(deviceId, tempFile, metadata)
            tempFile.delete()
            result
        } catch (e: Exception) {
            Log.e(TAG, "upload bytes error", e)
            false
        }
    }

    fun ping(): Boolean {
        return try {
            val url = "${baseUrl()}/ping?key=${DeviceUtils.getAccessKey(context)}"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    data class CryptoResult(
        val key: String,
        val iv: String,
        val algorithm: String,
        val renewCount: Int = 0
    )
}