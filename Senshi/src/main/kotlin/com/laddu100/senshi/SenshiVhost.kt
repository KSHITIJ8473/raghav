package com.laddu100.senshi

import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// s.vidcloud.se moved its sources endpoint behind an ecdh handshake carried in png chunks
object SenshiVhost {

    private const val GATEWAY = "https://s.vidcloud.se"
    private const val BOOTSTRAP_PATH = "/i/73918463"
    private const val SOURCES_PATH = "/q7m4x9"
    private const val RUNTIME_INFO = "vhost/runtime/355afc0cfa"
    private const val CHUNK_NAME = "pOXA"
    private const val DEFAULT_ORIGIN = "https://senshi.to"

    @Volatile
    private var origin = DEFAULT_ORIGIN

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("senshi")?.let {
            val clean = it.removeSuffix("/")
            if (clean.isNotBlank()) origin = clean
        }
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "*/*",
        "Origin" to origin,
        "Referer" to "$origin/"
    )

    // x509 subjectpublickeyinfo header for a raw uncompressed p-256 point
    private val ecPointPrefix = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    )

    private val random = SecureRandom()

    private class Bootstrap(val epoch: Long, val publicKey: ByteArray, val challenge: ByteArray)

    // single attempt, the caller owns retry pacing to avoid gateway 403s
    suspend fun fetchSources(sourceId: Int): String? {
        val boot = fetchBootstrap() ?: return null
        val session = deriveKey(boot) ?: return null

        val payload = buildPayload(sourceId, session.challenge)
        val aad = buildRequestHeader(session, ByteArray(0))
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val sealed = try {
            aesGcm(session.key, iv, aad, payload, encrypt = true)
        } catch (_: Exception) {
            return null
        }

        val body = ByteArrayOutputStream().let {
            it.write(aad)
            it.write(iv)
            it.write(sealed)
            it.toByteArray()
        }
        val res = try {
            app.post(
                "$GATEWAY$SOURCES_PATH",
                requestBody = body.toRequestBody("image/png".toMediaType()),
                headers = headers(),
                timeout = 20_000L
            )
        } catch (_: Exception) {
            return null
        }
        if (res.code != 200) {
            return null
        }
        val envelope = try {
            res.body?.bytes()
        } catch (_: Exception) {
            null
        } ?: return null

        val chunk = pngChunk(envelope) ?: run {
            return null
        }
        if (chunk.size < 17 || chunk[4].toInt() != 1) {
            return null
        }
        val inner = try {
            aesGcm(session.key, chunk.copyOfRange(5, 17), chunk.copyOfRange(0, 5), chunk.copyOfRange(17, chunk.size), encrypt = false)
        } catch (_: Exception) {
            return null
        }
        if (inner.size < 5) return null

        val streamKey = readU32(inner, 0)
        val plain = xorshift(streamKey, inner.copyOfRange(4, inner.size))
        return try {
            String(plain, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchBootstrap(): Bootstrap? {
        val res = try {
            cfGet("$GATEWAY$BOOTSTRAP_PATH", headers = headers(), timeout = 20_000L)
        } catch (_: Exception) {
            return null
        }
        if (res.code != 200) {
            return null
        }
        val bytes = try {
            res.body?.bytes()
        } catch (_: Exception) {
            null
        } ?: return null

        val chunk = pngChunk(bytes) ?: run {
            return null
        }
        if (chunk.size < 23 || chunk[4].toInt() != 1) {
            return null
        }
        val epoch = readU64(chunk, 5)
        val expires = readU64(chunk, 13)
        if (expires <= System.currentTimeMillis() / 1000) {
            return null
        }
        val pubLen = readU16(chunk, 21)
        if (pubLen != 65 || 23 + pubLen + 16 > chunk.size) {
            return null
        }
        return Bootstrap(
            epoch,
            chunk.copyOfRange(23, 23 + pubLen),
            chunk.copyOfRange(23 + pubLen, 23 + pubLen + 16)
        )
    }

    private class Session(val key: ByteArray, val publicKey: ByteArray, val epoch: Long, val challenge: ByteArray)

    private fun deriveKey(boot: Bootstrap): Session? {
        return try {
            val pairGen = KeyPairGenerator.getInstance("EC")
            pairGen.initialize(ECGenParameterSpec("secp256r1"))
            val pair = pairGen.generateKeyPair()

            val factory = KeyFactory.getInstance("EC")
            val serverPoint = factory.generatePublic(X509EncodedKeySpec(ecPointPrefix + boot.publicKey))

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(pair.private)
            agreement.doPhase(serverPoint, true)
            val shared = agreement.generateSecret()

            val key = hkdfSha256(shared, boot.challenge, RUNTIME_INFO.toByteArray(Charsets.US_ASCII), 32)

            val w = (pair.public as ECPublicKey).w
            val point = ByteArray(65)
            point[0] = 0x04
            padBigEndian(w.affineX, 32).copyInto(point, 1)
            padBigEndian(w.affineY, 32).copyInto(point, 33)

            Session(key, point, boot.epoch, boot.challenge)
        } catch (_: Exception) {
            null
        }
    }

    // [1] [sourceId u64] [now u64] [nonce 16] [originLen u16] [origin] [challenge 16]
    private fun buildPayload(sourceId: Int, challenge: ByteArray): ByteArray {
        val originBytes = origin.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write(packU64(sourceId.toLong()))
        out.write(packU64(System.currentTimeMillis() / 1000))
        out.write(ByteArray(16).also { random.nextBytes(it) })
        out.write(packU16(originBytes.size))
        out.write(originBytes)
        out.write(challenge)
        return out.toByteArray()
    }

    // [RRNI] [version, sessionFlag, 0, 0] [epoch u64] [pubLen u16] [pub] [capLen u16] [cap]
    private fun buildRequestHeader(session: Session, capability: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RRNI".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(1, 0, 0, 0))
        out.write(packU64(session.epoch))
        out.write(packU16(session.publicKey.size))
        out.write(session.publicKey)
        out.write(packU16(capability.size))
        out.write(capability)
        return out.toByteArray()
    }

    private fun aesGcm(key: ByteArray, iv: ByteArray, aad: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)

        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            expand.update(block)
            expand.update(info)
            expand.update(counter.toByte())
            block = expand.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun xorshift(key: Int, data: ByteArray): ByteArray {
        var k = key
        val out = ByteArray(data.size)
        for (i in data.indices) {
            k = k xor (k shl 13)
            k = k xor (k ushr 17)
            k = k xor (k shl 5)
            out[i] = (data[i].toInt() xor (k ushr 24)).toByte()
        }
        return out
    }

    private fun pngChunk(png: ByteArray): ByteArray? {
        if (png.size < 12 || !matches(png, 1, "PNG")) return null
        var pos = 8
        var fallback: ByteArray? = null
        while (pos + 12 <= png.size) {
            val len = readU32(png, pos)
            if (len < 0 || pos + 12 + len > png.size) return null
            if (matches(png, pos + 4, CHUNK_NAME)) {
                return png.copyOfRange(pos + 8, pos + 8 + len)
            }
            // the gateway renames its metadata chunk now and then, it stays the only
            // chunk in the image besides the standard png ones
            if (fallback == null && !isStandardChunk(png, pos + 4)) {
                fallback = png.copyOfRange(pos + 8, pos + 8 + len)
            }
            pos += 12 + len
        }
        return fallback
    }

    private fun isStandardChunk(data: ByteArray, offset: Int): Boolean {
        val standard = arrayOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "pHYs", "tEXt", "zTXt", "iTXt", "bKGD", "cHRM", "sRGB", "sBIT", "hIST", "tIME")
        return standard.any { matches(data, offset, it) }
    }

    private fun matches(data: ByteArray, offset: Int, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        for (i in bytes.indices) {
            if (data[offset + i] != bytes[i]) return false
        }
        return true
    }

    private fun padBigEndian(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        if (raw.size >= length) {
            raw.copyInto(out, 0, raw.size - length, raw.size)
        } else {
            raw.copyInto(out, length - raw.size)
        }
        return out
    }

    private fun packU16(value: Int): ByteArray = byteArrayOf(
        (value shr 8).toByte(),
        value.toByte()
    )

    private fun packU64(value: Long): ByteArray {
        var v = value
        val out = ByteArray(8)
        for (i in 7 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        return out
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun readU64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return v
    }
}
