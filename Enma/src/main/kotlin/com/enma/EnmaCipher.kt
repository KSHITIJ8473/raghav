package com.enma

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EnmaCipher {
    private const val TAG = "Enma"

    // pinned fallbacks, megaplay rotates these through lib/newclient.min.js
    private const val KEY_SEED = "i?LMTAx0Q6,:}50U"
    private const val IV_SEED = "W0;27ToaUpl_P%'c"

    @Volatile
    private var cachedSeeds: Pair<String, String>? = null

    private val keyRegex = Regex("""trustAesKey[^)]*?"([^"]{16})"\)""")
    private val ivRegex = Regex("""trustAesIv[^)]*?"([^"]{16})"\)""")
    private val fileRegex = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")

    private suspend fun seeds(): List<Pair<String, String>> {
        cachedSeeds?.let { return listOf(it, Pair(KEY_SEED, IV_SEED)) }
        val dynamic = try {
            val js = app.get("https://megaplay.buzz/lib/newclient.min.js", timeout = 10_000L).text
            val key = keyRegex.find(js)?.groupValues?.get(1)
            val iv = ivRegex.find(js)?.groupValues?.get(1)
            if (key != null && iv != null) Pair(key, iv).also { cachedSeeds = it } else null
        } catch (e: Exception) {
            Log.d(TAG, "seed fetch failed: ${e.message}")
            null
        }
        return listOfNotNull(dynamic, Pair(KEY_SEED, IV_SEED))
    }

    private fun decrypt(enc: String, keySeed: String, ivSeed: String): String? {
        return try {
            var b64 = enc.replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val cipherBytes = Base64.decode(b64, Base64.DEFAULT)

            val keyBytes = ByteArray(32)
            val seedKey = keySeed.toByteArray(Charsets.UTF_8)
            System.arraycopy(seedKey, 0, keyBytes, 0, minOf(32, seedKey.size))

            val ivBytes = ByteArray(16)
            val seedIv = ivSeed.toByteArray(Charsets.UTF_8)
            System.arraycopy(seedIv, 0, ivBytes, 0, minOf(16, seedIv.size))

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.d(TAG, "decrypt failed: ${e.message}")
            null
        }
    }

    suspend fun decryptFile(enc: String): String? {
        for ((keySeed, ivSeed) in seeds()) {
            val plain = decrypt(enc, keySeed, ivSeed) ?: continue
            fileRegex.find(plain)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    // the cdn rejects master.m3u8 without a token but only checks for expiry
    private const val TOKEN_KEY = "MpCdnT0k3n!9f2K#xQ7vL5mR8wN1pY4s"
    private val hexIdsRegex = Regex("""/([a-f0-9]{32})/([a-f0-9]{32})/""", RegexOption.IGNORE_CASE)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun signUrl(url: String): String {
        val m = hexIdsRegex.find(url) ?: return url
        val expires = System.currentTimeMillis() / 1000L + 7L * 24L * 60L * 60L
        val payload = "$expires|${m.groupValues[1].lowercase()}/${m.groupValues[2].lowercase()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TOKEN_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        val token = "${b64url(payload.toByteArray(Charsets.UTF_8))}.${b64url(signature)}"
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}token=$token"
    }
}
