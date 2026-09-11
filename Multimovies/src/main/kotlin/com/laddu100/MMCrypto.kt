package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// CryptoJS.AES "passphrase" mode - OpenSSL EVP_BytesToKey (MD5, 8 byte salt) + AES-256-CBC, base64url on the wire
object MMCrypto {

    fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int = 32, ivLen: Int = 16): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = ArrayList<Byte>(keyLen + ivLen)
        var prev = ByteArray(0)
        while (out.size < keyLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(password)
            md.update(salt)
            prev = md.digest()
            out.addAll(prev.toList())
        }
        val key = out.subList(0, keyLen).toByteArray()
        val iv = out.subList(keyLen, keyLen + ivLen).toByteArray()
        return key to iv
    }

    private fun pkcs7Pad(data: ByteArray, block: Int = 16): ByteArray {
        val padLen = block - (data.size % block)
        return data + ByteArray(padLen) { padLen.toByte() }
    }

    fun aesEncrypt(plain: String, passphrase: String): String? = try {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(pkcs7Pad(plain.toByteArray(Charsets.UTF_8)))
        val out = "Salted__".toByteArray(Charsets.UTF_8) + salt + ct
        Base64.getEncoder().encodeToString(out)
    } catch (e: Exception) {
        Log.e("MMCrypto", "encrypt: ${e.message}")
        null
    }

    fun aesDecrypt(data: String, passphrase: String): String? = try {
        val b64 = data.trim()
            .replace("-", "+")
            .replace("_", "/")
            .let { if (it.length % 4 != 0) it + "=".repeat(4 - it.length % 4) else it }
        val raw = Base64.getDecoder().decode(b64)
        if (raw.size < 17 || String(raw, 0, 8, Charsets.UTF_8) != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}

object MMNet {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val baseHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun abs(base: String, url: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        val b = base.trimEnd('/')
        return if (u.startsWith("/")) "$b$u" else "$b/$u"
    }

    fun deEsc(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("&amp;", "&")

    fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (e: Exception) {
        ""
    }

    fun originOf(url: String): String = try {
        val u = URI(url)
        "${u.scheme}://${u.host}"
    } catch (e: Exception) {
        ""
    }

    suspend fun getText(
        url: String,
        headers: Map<String, String> = baseHeaders,
        referer: String? = null,
    ): String? = try {
        val h = headers.toMutableMap()
        if (referer != null) h["Referer"] = referer
        val resp = app.get(url, headers = h, timeout = 30_000L)
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        null
    }
}
