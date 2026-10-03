package com.laddu100.senshi

import android.util.Base64
import com.lagradost.api.Log
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SenshiCrypt {

    private const val TAG = "Senshi"
    private const val MARKER = "EM3U8v1:"

    // served by s.vidcloud.se, hidden in the site player bundle as two arrays
    // that xor into this key
    private val KEY = intArrayOf(
        110, 226, 114, 19, 39, 237, 70, 155, 182, 217, 58, 185, 183, 168, 56, 4,
        81, 144, 181, 186, 133, 217, 206, 163, 177, 225, 120, 5, 247, 180, 174, 246
    ).map { it.toByte() }.toByteArray()

    fun isEncrypted(text: String?): Boolean = text != null && text.startsWith(MARKER)

    fun decrypt(text: String): String? {
        return try {
            val raw = Base64.decode(text.substring(MARKER.length).trim(), Base64.DEFAULT)
            if (raw.size < 29) {
                Log.e(TAG, "playlist payload truncated: ${raw.size} bytes")
                return null
            }
            val iv = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "playlist decrypt failed: ${e.message}")
            null
        }
    }
}
