package com.laddu100

import android.util.Base64
import android.util.Log
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LIVETVCryptoUtils {

    private const val TAG = "LIVETVCrypto"

    private const val LIVE_AES_KEY = "bTVLbDVuazR4SzFrTjdwTg=="
    private const val LIVE_AES_IV = "azVLNG5NOG1LbE5MN2wxNQ=="

    private const val LIVE_PRIMARY_AES_KEY = "Yi8xam1sNW5rNHg1azdwTg=="
    private const val LIVE_PRIMARY_AES_IV = "MTRuTWs4bU41S2w1S0w3bA=="

    private val NATIVE_KEY = byteArrayOf(
        99, 122, 49, 52, 82, 83, 116, 107,
        78, 48, 49, 80, 86, 69, 53, 119
    )

    private val NATIVE_IV = byteArrayOf(
        87, 84, 108, 69, 118, 99, 107, 100,
        50, 85, 82, 52, 49, 115, 100, 107
    )

    private const val SUB_FROM = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"
    private const val SUB_TO = "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"

    private val SUB_REVERSE = HashMap<Char, Char>()

    init {
        for (i in SUB_TO.indices) {
            SUB_REVERSE[SUB_TO[i]] = SUB_FROM[i]
        }
    }

    private data class KeyInfo(val key: ByteArray, val iv: ByteArray)

    private fun decodeKey(base64: String): ByteArray =
        Base64.decode(base64, Base64.DEFAULT)

    private val PRIMARY_KEY by lazy {
        KeyInfo(decodeKey(LIVE_PRIMARY_AES_KEY), decodeKey(LIVE_PRIMARY_AES_IV))
    }

    private val FALLBACK_KEY by lazy {
        KeyInfo(decodeKey(LIVE_AES_KEY), decodeKey(LIVE_AES_IV))
    }

    private fun swapAdjacentPairs(b: ByteArray): ByteArray {
        val out = Arrays.copyOf(b, b.size)
        var i = 0
        while (i + 1 < out.size) {
            val tmp = out[i]
            out[i] = out[i + 1]
            out[i + 1] = tmp
            i += 2
        }
        return out
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

    private fun aesCbcDecryptBytes(
        cipherBytes: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray? {
        if (key.size != 16 || iv.size != 16 || cipherBytes.size % 16 != 0) {
            return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            cipher.doFinal(cipherBytes)
        } catch (e: Exception) {
            Log.e(TAG, "AES failed: ${e.message}")
            null
        }
    }

    private fun decryptAes(dataB64: String, keyInfo: KeyInfo): String? {
        return try {
            val cipherBytes = Base64.decode(normalizeBase64(dataB64), Base64.DEFAULT)
            aesCbcDecryptBytes(cipherBytes, keyInfo.key, keyInfo.iv)
                ?.let { String(it, Charsets.UTF_8).trim() }
        } catch (e: Exception) {
            Log.e(TAG, "AES failed: ${e.message}")
            null
        }
    }

    // Newest payload format: double-base64 with byte reversal + pair swap, then AES.
    private fun decryptNativeLib(raw: String): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val b1 = Base64.decode(stripped, Base64.DEFAULT)
            val b1Reversed = b1.reversedArray()
            val b2 = swapAdjacentPairs(b1Reversed)
            val b2Str = String(b2, Charsets.US_ASCII).replace("\\s".toRegex(), "")
            val b3 = Base64.decode(b2Str, Base64.DEFAULT)
            val pt = aesCbcDecryptBytes(b3, NATIVE_KEY, NATIVE_IV) ?: return null
            val text = String(pt, Charsets.UTF_8).trim()
            if (!text.startsWith("{") && !text.startsWith("[")) return null
            text
        } catch (e: Exception) {
            Log.e(TAG, "Native decrypt failed: ${e.message}")
            null
        }
    }

    private fun decryptPrimary(raw: String): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val restored = buildString {
                for (char in stripped) {
                    append(SUB_REVERSE[char] ?: char)
                }
            }
            val b1 = Base64.decode(normalizeBase64(restored), Base64.DEFAULT)
            val b1Str = String(b1, Charsets.US_ASCII)
            val ct = Base64.decode(normalizeBase64(b1Str), Base64.DEFAULT)
            val pt = aesCbcDecryptBytes(ct, PRIMARY_KEY.key, PRIMARY_KEY.iv) ?: return null
            val text = String(pt, Charsets.UTF_8).trim()
            if (!text.startsWith("{") && !text.startsWith("[")) return null
            text
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptFallback(raw: String): String? {
        return try {
            val stripped = raw.replace("\\s".toRegex(), "")
            val ct = Base64.decode(normalizeBase64(stripped), Base64.DEFAULT)
            val pt = aesCbcDecryptBytes(ct, FALLBACK_KEY.key, FALLBACK_KEY.iv) ?: return null
            val text = String(pt, Charsets.UTF_8).trim()
            if (!text.startsWith("{") && !text.startsWith("[")) return null
            text
        } catch (e: Exception) {
            null
        }
    }

    fun decryptLIVETV(body: String?): String? {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.startsWith("{") || raw.startsWith("[") || raw.startsWith("<")) return raw

        decryptNativeLib(raw)?.let { return it }
        decryptPrimary(raw)?.let { return it }
        decryptFallback(raw)?.let { return it }

        Log.e(TAG, "All decryption strategies failed")
        return null
    }
}
