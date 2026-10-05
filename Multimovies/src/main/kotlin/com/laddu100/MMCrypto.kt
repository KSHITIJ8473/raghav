package com.laddu100

import com.lagradost.api.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "MMCrypto"

object MMCrypto {

    fun b64url(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)
            .replace("=", "")
            .replace('+', '-')
            .replace('/', '_')

    fun b64urlDecode(s: String): ByteArray {
        var t = s.replace("-", "+").replace("_", "/")
        while (t.length % 4 != 0) t += "="
        return Base64.getDecoder().decode(t)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(data)
    } catch (e: Exception) {
        Log.e(TAG, "cbc decrypt: ${e.message}")
        null
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.doFinal(payload)
    } catch (e: Exception) {
        Log.e(TAG, "gcm decrypt: ${e.message}")
        null
    }

    fun randomB64urlHash(): String =
        b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })

    data class EcKeyPair(val jwk: Map<String, String>, val signer: (String) -> String)

    fun newP256Key(): EcKeyPair {
        val pair: KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .genKeyPair()
        val pub = pair.public as ECPublicKey
        val point = pub.w
        val coordBytes = { v: java.math.BigInteger ->
            val raw = v.toByteArray()
            val fixed = ByteArray(32)
            val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
            System.arraycopy(src, 0, fixed, fixed.size - src.size, src.size)
            fixed
        }
        val jwk = mapOf(
            "kty" to "EC",
            "crv" to "P-256",
            "x" to b64url(coordBytes(point.affineX)),
            "y" to b64url(coordBytes(point.affineY)),
        )
        return EcKeyPair(jwk) { message ->
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(pair.private)
            sig.update(message.toByteArray(Charsets.UTF_8))
            b64url(derToRaw(sig.sign()))
        }
    }

    // java emits ASN.1 DER, the byse endpoint expects the webcrypto raw r||s layout
    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if ((der[1].toInt() and 0xff) > 0x7f) i = 3
        fun readInt(): ByteArray {
            if (der[i] != 0x02.toByte()) throw IllegalArgumentException("bad DER")
            i++
            val len = der[i].toInt() and 0xff
            i++
            val v = der.copyOfRange(i, i + len)
            i += len
            var start = 0
            while (start < v.size - 1 && v[start] == 0.toByte()) start++
            val trimmed = v.copyOfRange(start, v.size)
            val out = ByteArray(32)
            System.arraycopy(trimmed, 0, out, out.size - trimmed.size, trimmed.size)
            return out
        }
        val r = readInt()
        val s = readInt()
        return r + s
    }

    // the byse proof of work uses a custom 256 bit digest over raw bytes
    fun grHash(bytes: ByteArray): IntArray {
        val st = IntArray(4)
        st[0] = 1779033703; st[1] = 3144134277.toInt(); st[2] = 1013904242; st[3] = 2773480762.toInt()
        fun ye() {
            st[0] = st[0] + st[1]
            st[3] = rotl(st[3] xor st[0], 16)
            st[2] = st[2] + st[3]
            st[1] = rotl(st[1] xor st[2], 12)
            st[0] = st[0] + st[1]
            st[3] = rotl(st[3] xor st[0], 8)
            st[2] = st[2] + st[3]
            st[1] = rotl(st[1] xor st[2], 7)
        }
        for (b in bytes) {
            st[0] = st[0] + (b.toInt() and 0xff)
            st[0] = rotl(st[0], 7)
            ye()
        }
        repeat(8) { ye() }
        val r = IntArray(512)
        for (i in 0 until 512) {
            ye()
            r[i] = st[0] xor st[2]
        }
        repeat(2) {
            for (s in 0 until 512) {
                val a = r[s] and 511
                var c = r[s] + r[a]
                c = rotl(c, 13)
                c = c xor imul(r[(s + 1) and 511], 2654435761.toInt())
                r[s] = c
                st[0] = st[0] xor c
                ye()
            }
        }
        val out = IntArray(8)
        for (i in 0 until 8) {
            ye()
            var s = st[0]
            val a = i * 64
            for (c in 0 until 64) {
                val d = r[a + c]
                s = s + d
                s = rotl(s, 5)
                s = s xor imul(d, 2246822519.toInt())
            }
            out[i] = s xor st[2]
        }
        return out
    }

    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))
    private fun imul(x: Int, y: Int): Int {
        val p = x.toLong() * y.toLong()
        return (p and 0xffffffffL).toInt()
    }

    fun leadingZeroBits(words: IntArray): Int {
        var total = 0
        for (w in words) {
            if (w == 0) {
                total += 32
                continue
            }
            return total + Integer.numberOfLeadingZeros(w)
        }
        return total
    }

    // finds s where grHash(nonce + ":" + s) has at least difficulty leading zero bits
    fun solvePow(nonce: String, difficulty: Int, maxMillis: Long = 15_000): String? {
        if (difficulty <= 0) return "0"
        val prefix = nonce + ":"
        val start = System.currentTimeMillis()
        var s = 0
        while (true) {
            val candidate = (prefix + s).toByteArray(Charsets.ISO_8859_1)
            if (leadingZeroBits(grHash(candidate)) >= difficulty) return s.toString()
            s++
            if (s and 8191 == 0 && System.currentTimeMillis() - start > maxMillis) return null
        }
    }
}
