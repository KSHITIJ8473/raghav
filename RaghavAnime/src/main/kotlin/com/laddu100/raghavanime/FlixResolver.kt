package com.laddu100.raghavanime

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object FlixResolver {

    private const val TAG = "RaghavAnime"
    private const val FLIX_EMBED_BASE = "https://flixcloud.cc"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    data class FlixSubtitle(
        val url: String,
        val language: String?,
        val format: String?
    )

    data class Result(
        val m3u8: String,
        val pkKey: ByteArray,
        val masterContent: String,
        val subtitles: List<FlixSubtitle>
    )

    suspend fun resolve(embedUrl: String, referer: String): Result? {
        return try {
            val pageHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to referer
            )
            val page = app.get(embedUrl, headers = pageHeaders).text
            val region = page.substringAfter("node_ids", "")
            if (region.isBlank()) return null

            val seed = Regex("""obfuscation_seed:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val wasmB64 = Regex("""w_payload:"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            val e = shaChain(seed)
            val s2 = shaChain(e)
            val kf = Regex(""""?kf_${e.substring(8, 16)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val ivf = Regex(""""?ivf_${e.substring(16, 24)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val token = Regex(""""?${e.substring(48, 64)}_${e.substring(56, 64)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null
            val keyFrag2 = Regex(""""?${s2.substring(0, 16)}_${s2.substring(16, 24)}"?\s*:\s*"([^"]+)"""").find(region)?.groupValues?.get(1) ?: return null

            val tokenResp = app.get(
                "$FLIX_EMBED_BASE/api/m3u8/$token",
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

            val pbkdf2 = pbkdf2Sha256(keySeed, seed.toByteArray(Charsets.UTF_8), 1000, 32)
            val xored = ByteArray(32)
            val seedBytes = seed.toByteArray(Charsets.UTF_8)
            for (i in 0 until 32) xored[i] = (pbkdf2[i].toInt() xor seedBytes[i % seedBytes.size].toInt()).toByte()
            val aesKey = MessageDigest.getInstance("SHA-256").digest(xored)

            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(b64d(ivf)))
            val padded = cipher.doFinal(b64d(encVideo))
            val pad = padded.last().toInt() and 0xFF
            val cut = if (pad in 1..16 && padded.size > pad) padded.size - pad else padded.size
            val url = String(padded.copyOfRange(0, cut), Charsets.UTF_8).trim()
            if (!url.startsWith("http")) return null

            val pk = derivePk(wasm.dataBytes) ?: return null

            val masterRaw = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$FLIX_EMBED_BASE/"
                )
            ).text.trim()
            val masterBody = decryptPlaylist(masterRaw, pk) ?: return null

            Result(url, pk, masterBody, extractSubtitles(region))
        } catch (e: Exception) {
            Log.d(TAG, "[ReAnime] flix resolve failed: ${e.message}")
            null
        }
    }

    fun decryptPlaylist(body: String, pk: ByteArray): String? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("#EXTM3U")) return trimmed
            val padded = buildString {
                append(trimmed)
                append("=".repeat((4 - trimmed.length % 4) % 4))
            }
            val raw = Base64.decode(padded, Base64.DEFAULT)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor pk[i % pk.size].toInt()).toByte()
            val text = String(out, Charsets.UTF_8)
            if (text.startsWith("#EXTM3U")) text else null
        } catch (e: Exception) {
            null
        }
    }

    private fun derivePk(data: ByteArray): ByteArray? {
        if (data.size < 64) return null
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = (data[i].toInt() xor data[i + 32].toInt()).toByte()
        return out
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val blocks = (keyLen + 31) / 32
        val out = ByteArray(blocks * 32)
        val index = ByteArray(4)
        for (i in 1..blocks) {
            index[0] = (i ushr 24).toByte()
            index[1] = ((i ushr 16) and 0xFF).toByte()
            index[2] = ((i ushr 8) and 0xFF).toByte()
            index[3] = (i and 0xFF).toByte()
            mac.reset()
            var u = mac.doFinal(salt + index)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * 32, 32)
        }
        return out.copyOf(keyLen)
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

    private fun extractSubtitles(region: String): List<FlixSubtitle> {
        val out = mutableListOf<FlixSubtitle>()
        val m = Regex("""subtitles:\[([^\]]*)\]""").find(region) ?: return out
        val body = m.groupValues[1]
        if (body.isBlank()) return out
        val objRe = Regex("""\{([^{}]*)\}""")
        for (om in objRe.findAll(body)) {
            val o = om.groupValues[1]
            val url = Regex(""""?url"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1) ?: continue
            if (!url.startsWith("http")) continue
            val lang = Regex(""""?language"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1)
            val fmt = Regex(""""?format"?\s*:\s*"([^"]+)"""").find(o)?.groupValues?.get(1)
            out.add(FlixSubtitle(url = url, language = lang, format = fmt))
        }
        return out
    }
}
