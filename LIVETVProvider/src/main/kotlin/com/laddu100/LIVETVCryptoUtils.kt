package com.laddu100

import com.lagradost.cloudstream3.base64DecodeArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LIVETVCryptoUtils {
    private val CRICIFY_PROVIDER_SECRET1 by lazy { "3368487a78594167534749382f68616d:557143766b766a656345497a38343256" }
    private val CRICIFY_PROVIDER_SECRET2 by lazy { "4d7165594743543441594b6f484b7254:6f484b725451755078387a6c386f4a2b" }

    private val KEYS by lazy {
        mapOf(
            "key1" to parseKeyInfo(CRICIFY_PROVIDER_SECRET1),
            "key2" to parseKeyInfo(CRICIFY_PROVIDER_SECRET2),
        )
    }

    private fun parseKeyInfo(secret: String): KeyInfo {
        val parts = secret.split(":")
        return KeyInfo(
            key = hexStringToByteArray(parts[0]),
            iv = hexStringToByteArray(parts[1]),
        )
    }

    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    fun decryptData(encryptedBase64: String): String? {
        return try {
            val cleanBase64 = encryptedBase64.trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")
                .replace("\t", "")

            val ciphertext = base64DecodeArray(cleanBase64)

            for ((_, keyInfo) in KEYS) {
                val result = tryDecrypt(ciphertext, keyInfo)
                if (result != null) {
                    return result
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryDecrypt(ciphertext: ByteArray, keyInfo: KeyInfo): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKeySpec = SecretKeySpec(keyInfo.key, "AES")
            val ivParameterSpec = IvParameterSpec(keyInfo.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec)

            val decrypted = cipher.doFinal(ciphertext)
            val text = String(decrypted, Charsets.UTF_8)

            if (text.startsWith("{") || text.startsWith("[") || text.contains("http", ignoreCase = true)) {
                text
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
