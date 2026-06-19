package com.laddu100

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic helpers for PlayZTV.
 *
 * Provides AES/CBC/PKCS5 decryption and a custom substitution
 * cipher used to protect the backend URL and category payloads.
 *
 * The cipher operates on a fixed mapping that is the inverse of
 * the encryption side — each character in the source range is
 * swapped with its counterpart in the target range.
 */
object PlayZTVCryptoUtils {

    // ── AES parameters ─────────────────────────────────────────────────────────

    private val AES_KEY_BYTES = android.util.Base64.decode("bTVLbDVuazR4SzFrTjdwTg==", android.util.Base64.DEFAULT)
    private val AES_IV_BYTES  = android.util.Base64.decode("azVLNG5NOG1LbE5MN2wxNQ==", android.util.Base64.DEFAULT)

    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    // ── Substitution tables ────────────────────────────────────────────────────

    private const val CHAR_SOURCE = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"
    private const val CHAR_TARGET = "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * AES-256-CBC decrypt → Base64-decode → substitution-decode.
     * This is the primary pipeline for backend payloads.
     */
    fun fullDecrypt(encoded: String): String {
        return try {
            val raw = decryptAes(encoded)
            val b64 = String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT))
            reverseSubstitute(b64)
        } catch (e: Exception) {
            encoded
        }
    }

    /**
     * AES-256-CBC decryption. Returns the decrypted bytes as a UTF-8 string.
     */
    fun decryptAes(encryptedBase64: String): String {
        val keySpec = SecretKeySpec(AES_KEY_BYTES, "AES")
        val ivSpec  = IvParameterSpec(AES_IV_BYTES)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val cipherBytes = android.util.Base64.decode(encryptedBase64, android.util.Base64.DEFAULT)
        val plainBytes  = cipher.doFinal(cipherBytes)

        return String(plainBytes, Charsets.UTF_8)
    }

    // ── Substitution cipher ────────────────────────────────────────────────────

    /**
     * Reverses the substitution applied during encryption.
     * Each character found in [CHAR_TARGET] is replaced by the
     * corresponding character from [CHAR_SOURCE].
     */
    private fun reverseSubstitute(input: String): String {
        val lookup = buildReverseLookup()
        return buildString(input.length) {
            for (ch in input) {
                append(lookup[ch] ?: ch)
            }
        }
    }

    /**
     * Builds a lazy character→character reverse-mapping from CHAR_TARGET→CHAR_SOURCE.
     */
    private fun buildReverseLookup(): Map<Char, Char> {
        val map = mutableMapOf<Char, Char>()
        for (i in CHAR_SOURCE.indices) {
            map[CHAR_TARGET[i]] = CHAR_SOURCE[i]
        }
        return map
    }

    // ── Random token generation ────────────────────────────────────────────────

    /** Generates a URL-safe random token (used for cache-busting). */
    fun randomToken(length: Int = 16): String {
        val rng = SecureRandom()
        val bytes = ByteArray(length)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}