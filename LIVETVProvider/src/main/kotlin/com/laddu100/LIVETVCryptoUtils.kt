package com.laddu100

import android.util.Base64
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LIVETVCryptoUtils {
    const val DEFAULT_LORA = "zH7hY9@lO=8uXk#f%mI/VvJd2G10Z5eU+L6Pi&aEbwA4scBCStQ3KyWqRjDgnoMRP"
    const val DEFAULT_SIG = "Mc1pOdG+rjLRsO8tlGCxcBFqGIU"

    private val FALLBACK_KEY = "ouAzyvGQqd5yAi5G".toByteArray(Charsets.ISO_8859_1)
    private val FALLBACK_IV = "QC4a1NX)XXEHq1bf".toByteArray(Charsets.ISO_8859_1)

    private const val FNV32_PRIME = 16777619L
    private const val MASK32 = 0xFFFFFFFFL

    private fun fnv1a32(data: ByteArray, seed: Long): Long {
        var h = seed and MASK32
        for (b in data) {
            h = ((b.toLong() and 0xFF) xor h) * FNV32_PRIME and MASK32
        }
        return h
    }

    private fun s32(v: Long): Long {
        val u = v and MASK32
        return if (u >= 0x80000000L) u - 0x100000000L else u
    }

    private fun u32(v: Long): Long = v and MASK32

    // the key and iv are picked character by character out of the lora
    // alphabet, the walk is steered by a hash of the signature so both sides
    // only need the two strings to rebuild them
    private fun deriveKey(lora: String, sig: String): ByteArray {
        var h = fnv1a32(sig.toByteArray(Charsets.ISO_8859_1), 2166136261L)
        val key = ByteArray(16)
        var offset = 0L
        for (i in 0 until 16) {
            val charVal = s32(sig[i % sig.length].code.toLong())
            val product = s32(h) * 31
            h = u32(s32(charVal + offset) xor s32(product))
            key[i] = lora[(h % lora.length).toInt()].code.toByte()
            offset = u32(offset + 13)
        }
        return key
    }

    private fun deriveIv(lora: String, sig: String): ByteArray {
        var mixed = fnv1a32(sig.toByteArray(Charsets.ISO_8859_1), 2166129450L)
        val iv = ByteArray(16)
        var ivIdx = 0
        var offset = 0L
        var loop = 0
        while (loop != 48) {
            val charVal = s32(sig[loop % sig.length].code.toLong())
            val product = s32(mixed) * 29
            mixed = u32(s32(charVal + offset) xor s32(product))
            iv[ivIdx] = lora[(mixed % lora.length).toInt()].code.toByte()
            loop += 3
            offset = u32(offset + 7)
            ivIdx++
        }
        return iv
    }

    private fun decodeBase64(encoded: String): ByteArray {
        var s = encoded.trim()
        val mod = s.length % 4
        if (mod != 0) {
            s += "=".repeat(4 - mod)
        }
        return Base64.decode(s, Base64.DEFAULT)
    }

    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            return data
        }
        val pad = data.last().toInt() and 0xFF
        if (pad in 1..16) {
            val tail = data.takeLast(pad)
            if (tail.all { it.toInt() and 0xFF == pad }) {
                return data.copyOf(data.size - pad)
            }
        }
        return data
    }

    private fun aesCbcDecrypt(ct: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            pkcs7Unpad(cipher.doFinal(ct))
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeText(data: ByteArray): Boolean {
        return !String(data, Charsets.UTF_8).contains('\uFFFD')
    }

    fun decryptPlayFy(
        encoded: String,
        lora: String = DEFAULT_LORA,
        sig: String = DEFAULT_SIG,
    ): String? {
        return try {
            val ct = decodeBase64(encoded)
            val derived = aesCbcDecrypt(ct, deriveKey(lora, sig), deriveIv(lora, sig))
            if (derived != null && looksLikeText(derived)) {
                return String(derived, Charsets.UTF_8)
            }
            val fallback = aesCbcDecrypt(ct, FALLBACK_KEY, FALLBACK_IV)
            if (fallback != null && looksLikeText(fallback)) {
                String(fallback, Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // payloads arrive either as raw text or wrapped in {"data": "..."}
    fun extractDataField(responseText: String): String {
        return try {
            val parsed = parseJson<Map<String, Any>>(responseText)
            (parsed["data"] as? String) ?: responseText.trim()
        } catch (_: Exception) {
            responseText.trim()
        }
    }
}
