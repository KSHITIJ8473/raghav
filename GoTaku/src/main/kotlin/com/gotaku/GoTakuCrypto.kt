package com.gotaku

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.InflaterInputStream
import java.io.ByteArrayInputStream

object GoTakuCrypto {

    private val apiSeed = intArrayOf(
        203, 10, 122, 188, 182, 30, 245, 41, 26, 106, 139, 92, 56, 203, 90, 52,
        167, 208, 189, 122, 150, 242, 60, 1, 70, 177, 253, 150, 158, 124, 253, 47
    ).map { it.toByte() }.toByteArray()

    // rotated by the site, current value lives in the embed player bundle
    private val manifestSeed = byteHex(
        "458e5403c40a7333" + "2706df406193dd6b" + "040a2ea97a6bb59b" + "52b294a61006e6ef"
    )

    private val apiKey = hkdf(apiSeed, ByteArray(0), "api-seal|v1".toByteArray())
    private val manifestKey = hkdf(manifestSeed, ByteArray(0), "manifest-resp|v1".toByteArray())

    fun byteHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        val all = out.toByteArray()
        return all.copyOf(length)
    }

    private fun b64Decode(s: String): ByteArray {
        val normalized = s.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    private fun b64EncodeUrl(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE)
    }

    // sealed body: one version byte, then the gcm iv, then the payload
    private fun isSealed(body: ByteArray): Boolean {
        return body.size > 29 && body[0].toInt() == 1
    }

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun unseal(body: ByteArray): ByteArray? {
        if (!isSealed(body)) return null
        return try {
            val inflated = ByteArrayOutputStream()
            InflaterInputStream(ByteArrayInputStream(gcmDecrypt(apiKey, body.copyOfRange(1, 13), body.copyOfRange(13, body.size)))).use { input ->
                input.copyTo(inflated)
            }
            inflated.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    // the nonce is sealed into the request and keys the response
    fun newNonce(): ByteArray {
        return ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    }

    fun buildManifestPath(nonce: ByteArray, stamp: String): String {
        val payload = """{"nonce":"${b64EncodeUrl(nonce)}","stamp":"$stamp"}"""
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(manifestSeed, "AES"), GCMParameterSpec(128, iv))
        val sealed = iv + cipher.doFinal(payload.toByteArray())
        return b64EncodeUrl(sealed)
    }

    fun openManifestResponse(nonce: ByteArray, body: String): ByteArray? {
        return try {
            val key = hkdf(manifestSeed, nonce, "manifest-resp|v1".toByteArray())
            val blob = b64Decode(body.trim())
            gcmDecrypt(key, blob.copyOfRange(0, 12), blob.copyOfRange(12, blob.size))
        } catch (e: Exception) {
            null
        }
    }

    fun playlistKey(keySeed: ByteArray, token: String): ByteArray {
        return hkdf(keySeed, token.toByteArray(), "pl|v1".toByteArray())
    }

    fun decryptPlaylist(key: ByteArray, blob: ByteArray): String? {
        return try {
            val plain = gcmDecrypt(key, blob.copyOfRange(0, 12), blob.copyOfRange(12, blob.size))
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // the first segment bytes are masked with a counter from the url path digest
    fun decryptSegment(data: ByteArray, urlPath: String, keySeed: ByteArray, segmentBytes: Int, rangeStart: Int = 0): ByteArray {
        if (rangeStart >= segmentBytes || segmentBytes <= 0) return data
        val urlId = urlPath.split("/").filter { it.isNotEmpty() }
            .let { parts ->
                val id = parts.drop(2).joinToString("/")
                id.substringBeforeLast('.')
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(urlId.toByteArray())
        val counter = digest.copyOf(8) + ByteArray(8)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keySeed, "AES"), IvParameterSpec(counter))
        val keystream = cipher.doFinal(ByteArray(data.size + rangeStart))
        val out = data.copyOf()
        val limit = minOf(out.size.toLong(), (segmentBytes - rangeStart).toLong()).toInt()
        for (i in 0 until limit) {
            val j = rangeStart + i
            if (j >= segmentBytes) break
            out[i] = (out[i].toInt() xor keystream[j].toInt()).toByte()
        }
        return out
    }
}
