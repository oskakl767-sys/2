package com.mdm.agent.data.remote

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager {

    companion object {
        private const val TAG = "CryptoManager"
        private const val ALGO = "AES/CBC/PKCS5Padding"
    }

    private var keyBytes: ByteArray? = null
    private var ivBytes: ByteArray? = null
    private var renewCount: Int = 0

    val isInitialized: Boolean get() = keyBytes != null && ivBytes != null

    fun initFromServer(result: ApiClient.CryptoResult) {
        keyBytes = hexToBytes(result.key)
        ivBytes = hexToBytes(result.iv)
        renewCount = result.renewCount
        Log.d(TAG, "Crypto initialized: algo=${result.algorithm} renew=$renewCount")
    }

    fun renewFromServer(result: ApiClient.CryptoResult) {
        keyBytes = hexToBytes(result.key)
        ivBytes = hexToBytes(result.iv)
        renewCount = result.renewCount
        Log.d(TAG, "Crypto renewed: count=$renewCount")
    }

    fun encrypt(plainBytes: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(ALGO)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(plainBytes)
        } catch (e: Exception) {
            Log.e(TAG, "encrypt error", e)
            null
        }
    }

    fun decrypt(encBytes: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(ALGO)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(encBytes)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt error", e)
            null
        }
    }

    fun encryptString(text: String): String? {
        val encrypted = encrypt(text.toByteArray(Charsets.UTF_8)) ?: return null
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptString(encoded: String): String? {
        val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
        val decrypted = decrypt(encrypted) ?: return null
        return String(decrypted, Charsets.UTF_8)
    }

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