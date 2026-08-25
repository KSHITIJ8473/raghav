package com.laddu100

import android.util.Base64
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LIVETVCryptoUtils {

    private const val TAG = "LIVETVCrypto"

    private const val LIVE_AES_KEY = "bTVLbDVuazR4SzFrTjdwTg=="
    private const val LIVE_AES_IV = "azVLNG5NOG1LbE5MN2wxNQ=="

    private const val LIVE_PRIMARY_AES_KEY = "Yi8xam1sNW5rNHg1azdwTg=="
    private const val LIVE_PRIMARY_AES_IV = "MTRuTWs4bU41S2w1S0w3bA=="

    private const val SUB_FROM = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"
    private const val SUB_TO = "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"

    private val SUB_REVERSE = HashMap<Char, Char>()

    init {
        for (i in SUB_TO.indices) {
            SUB_REVERSE[SUB_TO[i]] = SUB_FROM[i]
        }
    }

    private data class KeyInfo(
        val key: ByteArray,
        val iv: ByteArray
    )

    private fun decodeKey(base64: String): ByteArray {
        return Base64.decode(base64, Base64.DEFAULT)
    }

    private val PRIMARY_KEY by lazy {
        KeyInfo(
            decodeKey(LIVE_PRIMARY_AES_KEY),
            decodeKey(LIVE_PRIMARY_AES_IV)
        )
    }

    private val FALLBACK_KEY by lazy {
        KeyInfo(
            decodeKey(LIVE_AES_KEY),
            decodeKey(LIVE_AES_IV)
        )
    }

    private fun decodeSubstitutionPayload(value: String): String {
        val restored = buildString {
            for (char in value) {
                append(SUB_REVERSE[char] ?: char)
            }
        }
        return String(
            Base64.decode(normalizeBase64(restored), Base64.DEFAULT),
            Charsets.UTF_8
        )
    }

    private fun normalizeBase64(value: String): String {
        var normalized = value
            .replace("-", "+")
            .replace("_", "/")
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace("\t", "")

        while (normalized.length % 4 != 0) {
            normalized += "="
        }

        return normalized
    }

    private fun decryptAes(dataB64: String, keyInfo: KeyInfo): String? {
        return try {
            val cipherBytes = Base64.decode(normalizeBase64(dataB64), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyInfo.key, "AES"),
                IvParameterSpec(keyInfo.iv)
            )
            val decrypted = cipher.doFinal(cipherBytes)
            String(decrypted, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(TAG, "AES failed: ${e.message}")
            null
        }
    }

    fun decryptLIVETV(body: String?): String? {
        return try {
            val raw = body?.trim().orEmpty()

            if (raw.isEmpty()) {
                return null
            }

            if (raw.startsWith("{") || raw.startsWith("[") || raw.startsWith("<")) {
                return raw
            }

            val cleaned = raw.replace("\\s".toRegex(), "")

            val primary = runCatching {
                decryptAes(decodeSubstitutionPayload(cleaned), PRIMARY_KEY)
            }.getOrNull()
            if (!primary.isNullOrBlank()) {
                return primary
            }

            val fallback = runCatching {
                decryptAes(cleaned, FALLBACK_KEY)
            }.getOrNull()
            if (!fallback.isNullOrBlank()) {
                return fallback
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt error: ${e.message}")
            null
        }
    }
}
