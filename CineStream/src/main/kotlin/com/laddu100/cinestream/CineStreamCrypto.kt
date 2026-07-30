package com.laddu100.cinestream

import android.util.Base64
import com.lagradost.api.Log
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CineStreamCrypto {
    private const val TAG = "CineStream_Crypto"
    private const val PASSPHRASE = "Penguin"

    fun decrypt(encryptedBase64Url: String): String? {
        return try {
            val b64 = encryptedBase64Url
                .replace("-", "+")
                .replace("_", "/")
            val padded = when (b64.length % 4) {
                2 -> "$b64=="
                3 -> "$b64="
                else -> b64
            }
            val fullCipher = Base64.decode(padded, Base64.DEFAULT)

            if (fullCipher.size < 16 || !String(fullCipher, 0, 8, Charsets.UTF_8).startsWith("Salted__")) {
                return null
            }

            val salt = fullCipher.copyOfRange(8, 16)
            val cipherText = fullCipher.copyOfRange(16, fullCipher.size)

            val passphraseBytes = PASSPHRASE.toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("MD5")
            var keyMaterial = md.digest(passphraseBytes + salt)
            var combined = keyMaterial
            while (combined.size < 48) {
                keyMaterial = md.digest(keyMaterial + passphraseBytes + salt)
                combined += keyMaterial
            }
            val keyBytes = combined.copyOfRange(0, 32)
            val ivBytes = combined.copyOfRange(32, 48)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt: ${e.message}")
            null
        }
    }
}
