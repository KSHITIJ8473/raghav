package com.laddu100.kuroanime

import android.util.Base64
import com.lagradost.cloudstream3.app
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object FlixResolver {

    private const val TAG = "KuroAnime"

    data class FlixSubtitle(
        val url: String,
        val language: String?,
        val format: String?
    )

    data class Result(
        val m3u8: String,
        val subtitles: List<FlixSubtitle>,
        val releaseTitle: String?
    )

    fun embedUrlFrom(playerHtml: String): String? {
        val m = Regex("""<iframe[^>]+src="([^"]+)"""").find(playerHtml) ?: return null
        val src = m.groupValues[1]
        return src.takeIf { it.contains("flixcloud.cc/e/") }
    }

    suspend fun resolve(embedUrl: String, referer: String): Result? {
        return try {
            val pageHeaders = mapOf(
                "User-Agent" to KuroAnimeApi.BROWSER_HEADERS["User-Agent"]!!,
                "Referer" to referer
            )
            val page = app.get(embedUrl, headers = pageHeaders).text
            val region = page.substringAfter("node_ids", "")
            if (region.isBlank()) return null

            val seed = Regex("""obfuscation_seed:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val wasmB64 = Regex("""w_payload:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            val e = shaChain(seed)
            val s2 = shaChain(e)
            val keyField = "kf_${e.substring(8, 16)}"
            val ivField = "ivf_${e.substring(16, 24)}"
            val tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}"
            val keyFrag2Field = "${s2.substring(0, 16)}_${s2.substring(16, 24)}"

            val obfStr = extractJsObject(region, "obfuscated_crypto_data:") ?: return null
            val kf = Regex(""""?$keyField"?\s*:\s*"([^"]+)"""").find(obfStr)?.groupValues?.get(1) ?: return null
            val ivf = Regex(""""?$ivField"?\s*:\s*"([^"]+)"""").find(obfStr)?.groupValues?.get(1) ?: return null
            val token = Regex(""""$tokenField"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val keyFrag2 = Regex(""""$keyFrag2Field"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            val tokenResp = app.get(
                "${KuroAnimeApi.FLIX_EMBED_BASE}/api/m3u8/$token",
                headers = pageHeaders
            ).text
            val vidField = shaHex(token + "vid").substring(0, 10)
            val keyField2 = shaHex(token + "key").substring(0, 10)
            val encVideo = Regex(""""$vidField"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1) ?: return null
            val encKey = Regex(""""$keyField2"\s*:\s*"([^"]+)"""").find(tokenResp)?.groupValues?.get(1) ?: return null

            val wasm = MiniWasm(Base64.decode(wasmB64, Base64.DEFAULT))
            val frag1 = b64d(kf)
            val frag2 = b64d(keyFrag2)
            val encKeyBytes = b64d(encKey)
            val k = frag1.size
            if (frag2.size != k || encKeyBytes.size != k) return null
            val base = 1000
            wasm.writeMemory(base, frag1)
            wasm.writeMemory(base + k, frag2)
            wasm.writeMemory(base + 2 * k, encKeyBytes)
            val seedInt = seed.take(8).toLongOrNull(16)?.toInt() ?: return null
            wasm.call("_s", seedInt)
            wasm.call("_r", base, base + k, base + 2 * k, base + 3 * k, k)
            val keySeed = wasm.readMemory(base + 3 * k, k)
            if (keySeed.all { it == 0.toByte() }) return null

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(
                keySeed.map { it.toInt().toChar() }.toCharArray(),
                seed.toByteArray(Charsets.UTF_8),
                1000, 256
            )
            val pbkdf2 = factory.generateSecret(spec).encoded
            val xored = ByteArray(32)
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            for (i in 0 until 32) xored[i] = (pbkdf2[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
            val aesKey = MessageDigest.getInstance("SHA-256").digest(xored)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(b64d(ivf)))
            val urlBytes = cipher.doFinal(b64d(encVideo))
            val url = String(urlBytes, Charsets.UTF_8).trim()
            if (!url.startsWith("http")) return null

            val subs = extractSubtitles(region)
            val title = Regex("""<title>([^<]*)</title>""").find(page)?.groupValues?.get(1)
            Result(url, subs, title)
        } catch (e: Exception) {
            android.util.Log.d(TAG, "flix resolve failed: ${e.message}")
            null
        }
    }

    private fun shaChain(seed: String): String {
        var e = seed
        for (i in 0 until 3) e = shaHex(e + i)
        return e
    }

    private fun shaHex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun b64d(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    private fun extractJsObject(s: String, marker: String): String? {
        val i = s.indexOf(marker)
        if (i < 0) return null
        val start = s.indexOf('{', i)
        if (start < 0) return null
        var depth = 0
        var j = start
        var inStr = false
        var esc = false
        while (j < s.length) {
            val c = s[j]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, j + 1)
                    }
                }
            }
            j++
        }
        return null
    }

    private fun extractSubtitles(region: String): List<FlixSubtitle> {
        val out = mutableListOf<FlixSubtitle>()
        val m = Regex("""subtitles:\[([^\]]*)\]""").find(region) ?: return out
        val body = m.groupValues[1]
        if (body.isBlank()) return out
        val objRe = Regex("""\{([^{}]*)\}""")
        for (om in objRe.findAll(body)) {
            val o = om.groupValues[1]
            val url = Regex("""url:"([^"]+)"""").find(o)?.groupValues?.get(1) ?: continue
            val lang = Regex("""language:"([^"]+)"""").find(o)?.groupValues?.get(1)
            val fmt = Regex("""format:"([^"]+)"""").find(o)?.groupValues?.get(1)
            out.add(FlixSubtitle(url = url, language = lang, format = fmt))
        }
        return out
    }
}
