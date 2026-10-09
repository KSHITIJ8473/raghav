package com.justplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object MmNet {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val cookieJar = ConcurrentHashMap<String, MutableMap<String, String>>()

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
    } catch (_: Exception) {
        ""
    }

    fun setCookie(host: String, name: String, value: String) {
        cookieJar.getOrPut(host) { ConcurrentHashMap() }[name] = value
    }

    private fun cookieHeader(host: String): String? =
        cookieJar[host]?.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    private fun base(h: Map<String, String>, referer: String?, host: String?): Map<String, String> {
        val out = h.toMutableMap()
        if (!out.containsKey("User-Agent")) out["User-Agent"] = UA
        if (referer != null) out["Referer"] = referer
        if (host != null) cookieHeader(host)?.let { out["Cookie"] = it }
        return out
    }

    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        useCookies: Boolean = false,
    ): String? = try {
        val resp = app.get(
            url,
            headers = base(headers, referer, if (useCookies) hostOf(url) else null),
            timeout = 30_000L,
        )
        if (resp.isSuccessful) resp.text else null
    } catch (_: Exception) {
        null
    }

    suspend fun getWithCookies(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String>? = try {
        val resp = app.get(
            url,
            headers = base(extraHeaders, referer, hostOf(url)),
            timeout = 30_000L,
        )
        storeSetCookies(hostOf(url), resp.headers.values("set-cookie"))
        resp.code to resp.text
    } catch (_: Exception) {
        null
    }

    suspend fun postJson(
        url: String,
        body: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        useCookies: Boolean = false,
    ): Pair<Int, String>? = try {
        val resp = app.post(
            url,
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = base(extraHeaders, referer, if (useCookies) hostOf(url) else null),
            timeout = 30_000L,
        )
        if (useCookies) storeSetCookies(hostOf(url), resp.headers.values("set-cookie"))
        resp.code to resp.text
    } catch (_: Exception) {
        null
    }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null,
    ): String? = try {
        val resp = app.post(
            url,
            data = form,
            headers = base(emptyMap(), referer, null),
            timeout = 30_000L,
        )
        if (resp.isSuccessful) resp.text else null
    } catch (_: Exception) {
        null
    }

    private fun storeSetCookies(host: String, values: List<String>) {
        for (raw in values) {
            val pair = raw.substringBefore(";")
            val idx = pair.indexOf('=')
            if (idx > 0) setCookie(host, pair.substring(0, idx).trim(), pair.substring(idx + 1).trim())
        }
    }

    suspend fun <T> compute(block: () -> T): T = withContext(Dispatchers.Default) { block() }
}

internal object MmCrypto {

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
    } catch (_: Exception) {
        null
    }

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.doFinal(payload)
    } catch (_: Exception) {
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

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if ((der[1].toInt() and 0xff) > 0x7f) i = 3
        fun readInt(): ByteArray {
            if (der[i] != 0x02.toByte()) throw IllegalArgumentException("bad der")
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

    fun grHash(bytes: ByteArray): IntArray {
        val st = IntArray(4)
        st[0] = 1779033703; st[1] = 3144134277.toInt(); st[2] = 1013904242; st[3] = 2773480762.toInt()
        fun mix() {
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
            mix()
        }
        repeat(8) { mix() }
        val r = IntArray(512)
        for (i in 0 until 512) {
            mix()
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
                mix()
            }
        }
        val out = IntArray(8)
        for (i in 0 until 8) {
            mix()
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

internal object MmJsPacker {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun baseN(num: Int, base: Int): String {
        if (num == 0) return CHARS[0].toString()
        var temp = num
        val sb = StringBuilder()
        while (temp > 0) {
            sb.append(CHARS[temp % base])
            temp /= base
        }
        return sb.reverse().toString()
    }

    private fun unpack(p: String, a: Int, c: Int, k: List<String>): String {
        var payload = p
        for (i in c - 1 downTo 0) {
            if (i < k.size && k[i].isNotEmpty()) {
                val pattern = Regex("\\b${Regex.escape(baseN(i, a))}\\b")

                payload = pattern.replace(payload) { _ -> k[i] }
            }
        }
        return payload
    }

    fun parseAndUnpack(html: String): String? {
        val startIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
        val actualStart = if (startIdx != -1) startIdx else html.indexOf("function(p,a,c,k,e,d)")
        if (actualStart == -1) return null

        val openBraceIdx = html.indexOf("{", actualStart)
        if (openBraceIdx == -1) return null

        var braceCount = 1
        var j = openBraceIdx + 1
        while (j < html.length && braceCount > 0) {
            if (html[j] == '{') braceCount++
            else if (html[j] == '}') braceCount--
            j++
        }

        val argsStartIdx = html.indexOf("(", j - 1)
        if (argsStartIdx == -1) return null

        var argsParenCount = 1
        var kIdx = argsStartIdx + 1
        while (kIdx < html.length && argsParenCount > 0) {
            if (html[kIdx] == '(') argsParenCount++
            else if (html[kIdx] == ')') argsParenCount--
            kIdx++
        }

        val argsStr = html.substring(argsStartIdx + 1, kIdx - 1).trim()
        if (argsStr.isEmpty()) return null

        val startChar = argsStr.first()
        val payload = StringBuilder()
        var i = 1
        while (i < argsStr.length) {
            if (argsStr[i] == startChar) {
                var backslashCount = 0
                var m = i - 1
                while (m >= 0 && argsStr[m] == '\\') {
                    backslashCount++
                    m--
                }
                if (backslashCount % 2 == 0) break
            }
            payload.append(argsStr[i])
            i++
        }

        val unescapedPayload = payload.toString()
            .replace("\\$startChar", startChar.toString())
            .replace("\\\\", "\\")

        val rest = argsStr.substring(i + 1)
        val restQuoteMatch = Regex("[\"']").find(rest) ?: return null
        val quotePos = restQuoteMatch.range.first
        val restQuoteChar = restQuoteMatch.value

        val ints = Regex("\\b\\d+\\b").findAll(rest.substring(0, quotePos)).map { it.value.toInt() }.toList()
        if (ints.size < 2) return null
        val a = ints[0]
        val c = ints[1]

        val keysStr = StringBuilder()
        var jj = quotePos + 1
        while (jj < rest.length) {
            if (rest[jj].toString() == restQuoteChar) {
                var backslashCount = 0
                var m = jj - 1
                while (m >= 0 && rest[m] == '\\') {
                    backslashCount++
                    m--
                }
                if (backslashCount % 2 == 0) break
            }
            keysStr.append(rest[jj])
            jj++
        }

        val keys = keysStr.toString()
            .replace("\\$restQuoteChar", restQuoteChar)
            .replace("\\\\", "\\")
            .split("|")

        return unpack(unescapedPayload, a, c, keys)
    }
}

internal object MmCineverse {

    private const val HUB = "https://rozgarlelo.modiplay.xyz"

    private val apiV1Hosts = mapOf(
        "multimovies.rpmhub.site" to "RPM Share",
        "multimovies.embedseek.xyz" to "Seek Streaming",
        "multimovies.p2pplay.pro" to "Stream P2P",
        "server1.uns.bio" to "Upn Share",
    )

    private val APIV1_KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
    private val APIV1_IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)

    private data class SubServer(val platform: String, val name: String, val code: String)

    private fun decryptApiV1(hex: String): JSONObject? {
        val data = try {
            MmCrypto.hexToBytes(hex)
        } catch (_: Exception) {
            return null
        }
        if (data.isEmpty() || data.size % 16 != 0) return null
        val plain = MmCrypto.aesCbcDecrypt(data, APIV1_KEY, APIV1_IV) ?: return null
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    suspend fun resolveApiV1(
        host: String,
        code: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val info = MmNet.get(
            "https://$host/api/v1/info?id=$code",
            referer = "https://$host/",
        )?.let { decryptApiV1(it) }
        val title = info?.optString("title").orEmpty()

        val videoHex = MmNet.get(
            "https://$host/api/v1/video?id=$code&w=1920&h=1080&r=$HUB",
            referer = "https://$host/",
        ) ?: return false
        val video = decryptApiV1(videoHex) ?: return false

        var added = false
        val cfNative = video.optString("cfNative")
        if (cfNative.isNotBlank() && PlayNet.m3u8Alive(cfNative)) {
            val quality = Regex("(\\d{3,4})p").find(title)?.groupValues?.get(1)
            callback(
                newExtractorLink(label, label + (quality?.let { " ${it}p" } ?: ""), cfNative, type = ExtractorLinkType.M3U8)
            )
            added = true
        }
        val tiktok = video.optString("hlsVideoTiktok")
        if (tiktok.isNotBlank()) {
            val ttDomain = try {
                JSONObject(video.optString("streamingConfig"))
                    .getJSONObject("adjust")
                    .getJSONObject("Tiktok")
                    .optString("domain")
            } catch (_: Exception) {
                ""
            }
            val ttVersion = try {
                JSONObject(video.optString("streamingConfig"))
                    .getJSONObject("adjust")
                    .getJSONObject("Tiktok")
                    .getJSONObject("params")
                    .optString("v")
            } catch (_: Exception) {
                ""
            }
            val url = if (ttDomain.isNotBlank()) {
                "https://$host/hlsmod/$ttDomain$tiktok?v=$ttVersion"
            } else {
                "https://$host$tiktok"
            }
            if (PlayNet.m3u8Alive(url)) {
                callback(newExtractorLink(label, "$label Relay", url, type = ExtractorLinkType.M3U8))
                added = true
            }
        }
        return added
    }

    private suspend fun resolveProxyPage(
        platform: String,
        code: String,
        displayName: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val proxyUrl = "$HUB/proxy.php?p=${MmNet.urlEncode(platform)}&c=${MmNet.urlEncode(code)}" +
            "&title=&site_ref=https%3A%2F%2Fmultimovies.garden%2F&noredirect=1"

        val resp = try {
            app.get(
                proxyUrl,
                headers = mapOf(
                    "User-Agent" to MmNet.UA,
                    "Referer" to "$HUB/",
                ),
                timeout = 30_000L,
                allowRedirects = false,
            )
        } catch (_: Exception) {
            return false
        }

        if (resp.code in 300..399) {
            val location = resp.headers["location"] ?: return false
            val target = MmNet.abs(HUB, location)
            val host = MmNet.hostOf(target)
            val hash = target.substringAfter("#", "")
            val name = apiV1Hosts[host]
            if (name != null && hash.isNotBlank()) {
                return resolveApiV1(host, hash, "$labelPrefix $name", callback)
            }
            if (host == "bysetayico.com") {
                val fileCode = target.substringAfterLast("/").substringBefore("#")
                return MmByseBridge.resolveByse("$labelPrefix Filemoon", fileCode, callback)
            }
            if (host == "vidara.to") {
                return MmVidara.resolve(target, "$labelPrefix $displayName", callback)
            }
            if (host == MmNet.hostOf(HUB)) {
                val relay = MmNet.get(target, referer = "$HUB/")?.let { page ->
                    Regex("var\\s+src=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
                }
                if (!relay.isNullOrBlank()) {
                    return emitRelay(MmNet.abs(HUB, MmNet.deEsc(relay)), "$labelPrefix $displayName", callback)
                }
                return false
            }
            if (MmXvid.isXvidStyle(host)) {
                return MmXvid.resolve(target, "$labelPrefix $displayName", "$HUB/", callback)
            }
            return MmXvid.resolveGeneric(target, "$labelPrefix $displayName", callback)
        }

        val html = resp.text
        val src = Regex("var\\s+src=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        if (src.isNullOrBlank()) return false
        return emitRelay(MmNet.abs(HUB, MmNet.deEsc(src)), "$labelPrefix $displayName", callback)
    }

    private suspend fun emitRelay(
        stream: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!stream.startsWith("http")) return false
        if (!PlayNet.m3u8Alive(stream, mapOf("Referer" to "$HUB/", "User-Agent" to MmNet.UA))) return false
        callback(
            newExtractorLink(
                label,
                label,
                stream,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = mapOf("Referer" to "$HUB/")
            }
        )
        return true
    }

    suspend fun resolve(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MmNet.get(embedUrl, referer = "https://multimovies.garden/") ?: return false
        val doc = org.jsoup.Jsoup.parse(html)

        val servers = doc.select(".srv-item").mapNotNull { el ->
            val onclick = el.attr("onclick")
            val m = Regex("switchServer\\('([^']*)','([^']*)','([^']*)','([^']*)'").find(onclick)
                ?: return@mapNotNull null
            SubServer(m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }.distinctBy { it.platform }

        val defaultFrame = doc.selectFirst("#playerFrame")?.attr("src")
        val ordered = if (defaultFrame != null) {
            val defaultPlatform = Regex("[?&]p=([^&]+)").find(defaultFrame)?.groupValues?.get(1)
            servers.sortedByDescending { it.platform == defaultPlatform }
        } else servers

        var any = false
        for (server in ordered) {
            try {
                any = resolveProxyPage(
                    server.platform,
                    server.code,
                    server.name,
                    labelPrefix,
                    callback,
                ) || any
            } catch (_: Exception) {

            }
        }
        return any
    }
}

internal object MmVidara {

    suspend fun resolve(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val code = embedUrl.substringAfterLast("/", "").substringBefore("?")
        if (code.isBlank()) return false
        val body = MmNet.postJson(
            "https://vidara.to/api/stream",
            JSONObject()
                .put("filecode", code)
                .put("device", "desktop")
                .put("codecs", org.json.JSONArray().put("h264").put("h265").put("av1"))
                .toString(),
            referer = embedUrl,
        )?.let { (status, text) ->
            if (status == 200) text else null
        } ?: return false
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        } ?: return false
        val url = root.optString("streaming_url")
        if (!url.startsWith("http") || !PlayNet.m3u8Alive(url)) return false
        callback(newExtractorLink(label, label, url, type = PlayNet.linkType(url)))
        return true
    }
}

internal object MmByse {

    private data class Fingerprint(
        val token: String,
        val viewerId: String,
        val deviceId: String,
        val confidence: Double,
    )

    private data class PlaybackSource(val url: String, val label: String, val height: Int)

    private val sessionCache = HashMap<String, Fingerprint>()
    private val sessionMutex = Mutex()

    private fun clientBody(): JSONObject {
        val random = { MmCrypto.randomB64urlHash() }
        return JSONObject()
            .put("user_agent", MmNet.UA)
            .put("architecture", "x86")
            .put("bitness", "64")
            .put("platform", "Windows")
            .put("platform_version", "19.0.0")
            .put("model", "")
            .put("ua_full_version", "131.0.0.0")
            .put("brand_full_versions", org.json.JSONArray()
                .put(JSONObject().put("brand", "Chromium").put("version", "131.0.0.0"))
                .put(JSONObject().put("brand", "Not A(Brand").put("version", "99.0.0.0")))
            .put("pixel_ratio", 1)
            .put("screen_width", 1920)
            .put("screen_height", 1080)
            .put("color_depth", 24)
            .put("languages", org.json.JSONArray().put("en-US"))
            .put("timezone", "Asia/Kolkata")
            .put("hardware_concurrency", 8)
            .put("device_memory", 8)
            .put("touch_points", 0)
            .put("webgl_vendor", "Google Inc. (AMD)")
            .put("webgl_renderer", "ANGLE (AMD, Radeon RX 560 Series (0x000067FF) Direct3D11 vs_5_0 ps_5_0, D3D11)")
            .put("canvas_hash", random())
            .put("audio_hash", random())
            .put("webgl_params_hash", random())
            .put("fonts_hash", random())
            .put("codecs_hash", random())
            .put("media_devices", "ai1ao1vi1")
            .put("pointer_type", "fine,hover")
            .put("extra", JSONObject()
                .put("vendor", "Google Inc.")
                .put("appVersion", "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"))
    }

    private fun fingerprintBody(fp: Fingerprint): JSONObject = JSONObject()
        .put("token", fp.token)
        .put("viewer_id", fp.viewerId)
        .put("device_id", fp.deviceId)
        .put("confidence", fp.confidence)

    private suspend fun obtainFingerprint(host: String, referer: String): Fingerprint? =
        sessionMutex.withLock {
            sessionCache[host]?.let { return it }

            val challenge = MmNet.postJson(
                "https://$host/api/videos/access/challenge",
                "{}",
                referer = referer,
                useCookies = true,
            )?.let { (code, text) ->
                if (code == 200) JSONObject(text) else null
            } ?: return null
            val challengeId = challenge.optString("challenge_id")
            val nonce = challenge.optString("nonce")
            if (challengeId.isBlank() || nonce.isBlank()) return null

            val key = MmCrypto.newP256Key()
            val attestBody = JSONObject()
                .put("viewer_id", "")
                .put("device_id", "")
                .put("challenge_id", challengeId)
                .put("nonce", nonce)
                .put("signature", key.signer(nonce))
                .put("public_key", JSONObject(key.jwk))
                .put("client", clientBody())
                .put("storage", JSONObject())
                .put("attributes", JSONObject().put("entropy", "high"))

            val attest = MmNet.postJson(
                "https://$host/api/videos/access/attest",
                attestBody.toString(),
                referer = referer,
                useCookies = true,
            )?.let { (code, text) ->
                if (code == 200) JSONObject(text) else null
            } ?: return null
            val token = attest.optString("token")
            if (token.isBlank()) return null

            val fp = Fingerprint(
                token = token,
                viewerId = attest.optString("viewer_id"),
                deviceId = attest.optString("device_id"),
                confidence = attest.optDouble("confidence", 0.5),
            )
            MmNet.setCookie(host, "byse_viewer_id", fp.viewerId)
            MmNet.setCookie(host, "byse_device_id", fp.deviceId)
            sessionCache[host] = fp
            fp
        }

    private fun assembleKey(version: String, parts: List<String>): ByteArray? {
        val v = version.trim().toIntOrNull() ?: return null
        if (v < 1 || v > 20) return null
        val first = v
        val second = 31 - v
        if (second < 1 || second > parts.size || first > parts.size) return null
        val picked = listOf(parts[first - 1], parts[second - 1])
            .filter { it.isNotBlank() }
            .map { MmCrypto.b64urlDecode(it) }
        if (picked.size != 2) return null
        return picked[0] + picked[1]
    }

    private fun parsePlayback(encrypted: JSONObject): List<PlaybackSource> {
        val iv = MmCrypto.b64urlDecode(encrypted.optString("iv"))
        val payload = MmCrypto.b64urlDecode(encrypted.optString("payload"))
        val version = encrypted.optString("version")
        val partsRaw = encrypted.optJSONArray("key_parts") ?: return emptyList()
        val parts = (0 until partsRaw.length()).map { partsRaw.optString(it) }
        val key = assembleKey(version, parts) ?: return emptyList()
        val plain = MmCrypto.aesGcmDecrypt(key, iv, payload) ?: return emptyList()
        val json = JSONObject(String(plain, Charsets.UTF_8))
        val out = mutableListOf<PlaybackSource>()
        val sources = json.optJSONArray("sources") ?: return emptyList()
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (url.isBlank()) continue
            val label = s.optString("label").ifBlank { "Filemoon" }
            out.add(PlaybackSource(url, label, s.optInt("height", 0)))
        }
        return out
    }

    suspend fun resolve(
        embedFrameUrl: String,
        code: String,
        labelPrefix: String,
        embedOrigin: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val playerHost = MmNet.hostOf(embedFrameUrl)
        if (playerHost.isBlank()) return false
        val referer = embedFrameUrl
        val fp = obtainFingerprint(playerHost, referer) ?: return false

        val captcha = MmNet.postJson(
            "https://$playerHost/api/videos/$code/embed/captcha",
            JSONObject().put("fingerprint", fingerprintBody(fp)).toString(),
            referer = referer,
            useCookies = true,
        )?.let { (code2, text) ->
            if (code2 == 200) JSONObject(text) else null
        } ?: return false
        val powToken = captcha.optString("pow_token")
        val powNonce = captcha.optString("pow_nonce")
        val difficulty = captcha.optInt("pow_difficulty", 0)
        if (powToken.isBlank() || powNonce.isBlank()) return false

        val solution = MmNet.compute { MmCrypto.solvePow(powNonce, difficulty) } ?: return false

        val verify = MmNet.postJson(
            "https://$playerHost/api/videos/$code/embed/captcha/verify",
            JSONObject()
                .put("pow_token", powToken)
                .put("solution", solution)
                .put("fingerprint", fingerprintBody(fp))
                .toString(),
            referer = referer,
            useCookies = true,
        )?.let { (code2, text) ->
            if (code2 == 200) JSONObject(text) else null
        } ?: return false
        val captchaToken = verify.optString("token")
        if (captchaToken.isBlank()) return false

        val playback = MmNet.postJson(
            "https://$playerHost/api/videos/$code/embed/playback",
            JSONObject().put("fingerprint", fingerprintBody(fp)).toString(),
            referer = referer,
            extraHeaders = mapOf(
                "X-Captcha-Token" to captchaToken,
                "X-Embed-Origin" to embedOrigin,
                "X-Embed-Referer" to "https://$embedOrigin/e/$code",
                "X-Embed-Parent" to "https://$embedOrigin/e/$code",
            ),
            useCookies = true,
        )?.let { (code2, text) ->
            if (code2 == 200) JSONObject(text) else null
        } ?: return false
        val encrypted = playback.optJSONObject("playback") ?: return false
        val sources = parsePlayback(encrypted)
        var emitted = 0
        for (src in sources) {
            if (!PlayNet.m3u8Alive(src.url)) continue
            callback(
                newExtractorLink(
                    "$labelPrefix Filemoon",
                    "$labelPrefix Filemoon ${src.label}",
                    src.url,
                    type = PlayNet.linkType(src.url),
                )
            )
            emitted++
        }
        return emitted > 0
    }
}

internal object MmByseBridge {
    suspend fun resolveByse(
        label: String,
        code: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val details = MmNet.get(
            "https://bysetayico.com/api/videos/$code/embed/details",
            referer = "https://bysetayico.com/e/$code",
        ) ?: return false
        val frameUrl = try {
            JSONObject(details).optString("embed_frame_url")
        } catch (_: Exception) {
            ""
        }
        if (frameUrl.isBlank()) return false
        return MmByse.resolve(frameUrl, code, label, "bysetayico.com", callback)
    }
}

internal object MmGdMirror {

    private const val API = "https://streams.iqsmartgames.com"
    private const val PLAYER = "https://pro.iqsmartgames.com"

    private data class FileEntry(val slug: String, val name: String, val size: String)

    private data class MirrorLink(
        val siteUrl: String,
        val friendlyName: String,
        val code: String,
    )

    private fun shortLabel(filename: String): String {
        val cleaned = filename
            .replace(Regex("(?i)\\b(multi|ddp[\\d.]*|atmos|h[ .]?265|hevc|x265|x264|avc|esub|hindi|web[ .-]?dl|webrip|hdrip|hdtc|hc)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > 26) cleaned.take(26).trim() + "..." else cleaned
    }

    private suspend fun fetchFiles(embedUrl: String): List<FileEntry> {
        val html = MmNet.get(embedUrl, referer = "https://multimovies.garden/") ?: return emptyList()

        val varValue = { name: String ->
            Regex("let\\s+$name\\s*=\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1).orEmpty()
        }
        val finalId = varValue("FinalID")
        val idType = varValue("idType")
        val key = varValue("myKey")
        if (finalId.isBlank() || key.isBlank()) return emptyList()

        val season = varValue("season")
        val episode = varValue("epname")
        val apiPath = if (season.isNotBlank() && episode.isNotBlank()) {
            "$API/myseriesapi?$idType=${MmNet.urlEncode(finalId)}" +
                "&season=$season&epname=${MmNet.urlEncode(episode)}&key=$key"
        } else {
            "$API/mymovieapi?$idType=${MmNet.urlEncode(finalId)}&key=$key"
        }

        val body = MmNet.get(apiPath, referer = "$API/") ?: return emptyList()
        val data = try {
            JSONObject(body).optJSONArray("data")
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val out = mutableListOf<FileEntry>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val slug = item.optString("fileslug")
            if (slug.isBlank()) continue
            out.add(FileEntry(slug, item.optString("filename"), item.optString("fsize")))
        }
        return out
    }

    private suspend fun fetchMirrors(slug: String): List<MirrorLink> {
        val body = MmNet.postForm(
            "$PLAYER/embedhelper2.php",
            mapOf(
                "sid" to slug,
                "UserFavSite" to "",
                "currentDomain" to """["streams.iqsmartgames.com","pro.iqsmartgames.com"]""",
            ),
            referer = "$PLAYER/",
        ) ?: return emptyList()

        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyList()
        }
        val sources = root.optJSONObject("sources") ?: return emptyList()
        val codes = try {
            JSONObject(String(MmCrypto.b64urlDecode(root.optString("mresult")), Charsets.UTF_8))
        } catch (_: Exception) {
            return emptyList()
        }

        val out = mutableListOf<MirrorLink>()
        for (key in codes.keys()) {
            val src = sources.optJSONObject(key) ?: continue
            val siteUrl = src.optString("siteUrl")
            val code = codes.optString(key)
            if (siteUrl.isBlank() || code.isBlank()) continue
            out.add(MirrorLink(siteUrl, src.optString("friendlyName"), code))
        }
        return out
    }

    private suspend fun dispatchMirror(
        mirror: MirrorLink,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = MmNet.abs(mirror.siteUrl, mirror.code)
        val host = MmNet.hostOf(pageUrl)
        return when {
            host in setOf(
                "multimovies.rpmhub.site",
                "multimovies.embedseek.xyz",
                "multimovies.p2pplay.pro",
                "server1.uns.bio",
            ) -> MmCineverse.resolveApiV1(host, mirror.code, label, callback)

            host == "bysetayico.com" -> MmByseBridge.resolveByse(label, mirror.code, callback)

            MmXvid.isXvidStyle(host) -> MmXvid.resolve(pageUrl, label, "https://pro.iqsmartgames.com/", callback)

            else -> MmXvid.resolveGeneric(pageUrl, label, callback)
        }
    }

    suspend fun resolve(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val files = fetchFiles(embedUrl)
        if (files.isEmpty()) return false
        var any = false
        for ((index, file) in files.withIndex()) {
            val mirrors = try {
                fetchMirrors(file.slug)
            } catch (_: Exception) {
                emptyList()
            }
            val fileLabel = shortLabel(file.name).ifBlank { "File ${index + 1}" }
            for (mirror in mirrors) {
                try {
                    any = dispatchMirror(mirror, "$labelPrefix $fileLabel", callback) || any
                } catch (_: Exception) {

                }
            }
        }
        return any
    }

    suspend fun resolveFilesforever(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val code = embedUrl.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return false

        val page = MmNet.get(embedUrl, referer = "https://multimovies.garden/")
        val sid = if (page != null) {
            Regex("id=\"gdmrfid\"\\s+value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
                ?: Regex("let\\s+FinalID\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)
                ?: code
        } else code

        val mirrors = try {
            fetchMirrors(sid)
        } catch (_: Exception) {
            emptyList()
        }
        var any = false
        for (mirror in mirrors) {
            try {
                any = dispatchMirror(mirror, "$labelPrefix $sid", callback) || any
            } catch (_: Exception) {

            }
        }
        return any
    }
}

internal object MmXvid {

    private val knownHosts = setOf(
        "hanerix.com",
        "morencius.com",
        "vibuxer.com",
        "n1mwq.org",
    )

    fun isXvidStyle(host: String): Boolean = host in knownHosts

    private fun extractLinks(unpacked: String, base: String): List<String> {
        val absolute = LinkedHashSet<String>()
        val relative = LinkedHashSet<String>()
        for (m in Regex("\"(?:hls\\d|file|mp4)\"\\s*:\\s*\"([^\"]+)\"").findAll(unpacked)) {
            val url = MmNet.deEsc(m.groupValues[1])
            if (url.isBlank()) continue
            if (url.startsWith("http")) absolute.add(url) else relative.add(MmNet.abs(base, url))
        }
        for (m in Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*").findAll(unpacked)) {
            absolute.add(MmNet.deEsc(m.groupValues.first()))
        }

        return (absolute + relative).filter { it.startsWith("http") }
    }

    suspend fun resolve(
        pageUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val base = try {
            val uri = URI(pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            return false
        }
        val html = MmNet.get(pageUrl, referer = referer) ?: return false
        val unpacked = MmJsPacker.parseAndUnpack(html) ?: return false
        val links = extractLinks(unpacked, base)
        var emitted = 0
        for (url in links) {
            val type = PlayNet.linkType(url)
            val alive = if (type == ExtractorLinkType.M3U8) {
                PlayNet.m3u8Alive(url, mapOf("Referer" to "$base/"))
            } else {
                PlayNet.fileAlive(url, "$base/")
            }
            if (!alive) continue
            callback(
                newExtractorLink(label, label, url, type = type) {
                    this.headers = mapOf("Referer" to "$base/")
                }
            )
            emitted++
        }
        return emitted > 0
    }

    suspend fun resolveGeneric(
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MmNet.get(pageUrl) ?: return false
        val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
            .findAll(html)
            .map { MmNet.deEsc(it.groupValues.first()) }
            .toSet()
        var emitted = 0
        for (url in urls) {
            if (!PlayNet.m3u8Alive(url)) continue
            callback(newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8))
            emitted++
        }
        return emitted > 0
    }
}

internal object MmVidout {

    private const val RAW = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main"

    private suspend fun addLink(url: String, label: String, callback: (ExtractorLink) -> Unit) {
        callback(
            newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8) {
                this.headers = mapOf("Referer" to "https://vidout.pages.dev/")
            }
        )
    }

    suspend fun resolve(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var added = false
        if (!isTv) {
            val url = MmNet.get("$RAW/hls/movie/$tmdbId", referer = "https://vidout.pages.dev/")
                ?.trim()
            if (!url.isNullOrBlank() && url.startsWith("http")) {
                addLink(url, "$labelPrefix Vidout", callback)
                added = true
            }
            val subsBody = MmNet.get("$RAW/sub/movie/$tmdbId/subtitles.json", referer = "https://vidout.pages.dev/")
            if (!subsBody.isNullOrBlank()) {
                try {
                    val subs = JSONObject(subsBody).optJSONObject("subtitles")
                    if (subs != null) {
                        for (lang in subs.keys()) {
                            val subUrl = subs.optString(lang)
                            if (subUrl.isNotBlank()) {
                                subtitleCallback(SubtitleFile(lang, subUrl))
                            }
                        }
                    }
                } catch (_: Exception) {

                }
            }
        } else if (season != null) {
            val body = MmNet.get("$RAW/hls/tv/$tmdbId/S$season.json", referer = "https://vidout.pages.dev/")
            if (!body.isNullOrBlank()) {
                val map = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    null
                }
                if (map != null) {
                    val key = episode?.toString()
                    val url = key?.let { map.optString(it) }
                    if (!url.isNullOrBlank() && url.startsWith("http")) {
                        addLink(url, "$labelPrefix Vidout", callback)
                        added = true
                    }
                }
            }
        }
        return added
    }
}

internal object MmVidsync {
    suspend fun resolve(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MmNet.get(url, referer = "https://multimovies.garden/") ?: return false
        val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
            .findAll(html)
            .map { MmNet.deEsc(it.groupValues.first()) }
            .toSet()
        for (u in urls) {
            callback(newExtractorLink(label, label, u, type = ExtractorLinkType.M3U8))
        }
        return urls.isNotEmpty()
    }
}

internal object MmBingr {

    private data class Server(val id: String, val name: String)

    private val servers = listOf(
        Server("s40", "Aphelion"),
        Server("s70", "Polaris"),
        Server("s62", "Bastion"),
        Server("s63", "Hallyu"),
        Server("s30", "Nova"),
        Server("s60", "Vertex"),
        Server("s61", "Corvus"),
        Server("s31", "Orion"),
        Server("s3", "Edmunds"),
    )

    private const val API = "https://api.bingr.one"

    private suspend fun call(
        server: Server,
        isTv: Boolean,
        id: String,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
    ): JSONObject? {
        if (server.id == "s40" && isTv && season != null && episode != null) {
            val body = MmNet.get(
                "$API/stream/aphelion-tv/$id/$season/$episode",
                referer = "https://bingr.one/",
            ) ?: return null
            return try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            }
        }
        val query = JSONObject().put("title", title)
        if (!year.isNullOrBlank()) query.put("year", year)
        if (isTv && season != null) query.put("season", season)
        if (isTv && episode != null) query.put("episode", episode)
        val body = JSONObject()
            .put("srv", server.id)
            .put("t", if (isTv) "tv" else "movie")
            .put("id", id)
            .put("query", query)
        val resp = MmNet.postJson(
            "$API/stream",
            body.toString(),
            referer = "https://bingr.one/",
            extraHeaders = mapOf("Origin" to "https://bingr.one"),
        ) ?: return null
        if (resp.first != 200) return null
        return try {
            JSONObject(resp.second)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun addSubtitles(
        root: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val subs = root.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val url = sub.optString("url")
            if (url.isBlank()) continue
            val lang = sub.optString("lang").ifBlank { sub.optString("label").ifBlank { "en" } }
            val label = sub.optString("label").ifBlank { lang }
            subtitleCallback(SubtitleFile(lang, url))
        }
    }

    suspend fun resolve(
        isTv: Boolean,
        id: String,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        for (server in servers) {
            val root = try {
                call(server, isTv, id, title, year, season, episode)
            } catch (_: Exception) {
                null
            } ?: continue
            val sources = root.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val url = src.optString("url")
                if (url.isBlank()) continue
                val quality = src.optString("quality").ifBlank { "HD" }
                callback(
                    newExtractorLink(
                        "$labelPrefix ${server.name}",
                        "$labelPrefix ${server.name} $quality",
                        url,
                        type = PlayNet.linkType(url),
                    )
                )
                any = true
            }
            addSubtitles(root, subtitleCallback)
        }
        return any
    }
}

internal object MmFilmu {

    private const val HOST = "https://embed.filmu.in"

    private suspend fun addSource(
        url: String,
        quality: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback(
            newExtractorLink(
                label,
                "$label $quality",
                url,
                type = PlayNet.linkType(url),
            )
        )
    }

    private suspend fun addSubtitles(
        root: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val subs = root.optJSONArray("subtitles") ?: return
        for (i in 0 until subs.length()) {
            val sub = subs.optJSONObject(i) ?: continue
            val url = sub.optString("url")
            if (url.isBlank()) continue
            val lang = sub.optString("lang").ifBlank { "en" }
            subtitleCallback(SubtitleFile(lang, url))
        }
    }

    suspend fun resolve(
        isTv: Boolean,
        tmdbId: String,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val path = if (isTv) {
            if (season == null || episode == null) return false
            "$HOST/api/singularity-tv?tmdb=$tmdbId&s=$season&e=$episode"
        } else {
            "$HOST/api/singularity-movie?id=$tmdbId"
        }
        val referer = if (isTv) "$HOST/tv/$tmdbId/$season/$episode" else "$HOST/movie/$tmdbId"
        val body = MmNet.get(path, referer = referer) ?: return false
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return false
        }

        var any = false
        val sources = root.optJSONArray("sources")
        if (sources != null) {
            val base = root.optString("_base")
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                var url = src.optString("url")
                if (url.isBlank()) continue
                if (!url.startsWith("http") && base.isNotBlank()) url = MmNet.abs(base, url)
                if (!url.startsWith("http")) continue
                addSource(url, src.optString("quality").ifBlank { "1080p" }, "$labelPrefix Filmu", callback)
                any = true
            }
            addSubtitles(root, subtitleCallback)
        }
        if (!any) {
            val single = root.optString("url").ifBlank {
                if (root.optBoolean("multilingual")) root.optString("multilingual_url") else ""
            }
            if (single.startsWith("http")) {
                addSource(single, root.optString("quality").ifBlank { "1080p" }, "$labelPrefix Filmu", callback)
                addSubtitles(root, subtitleCallback)
                any = true
            } else {
                val m3u8Path = root.optString("m3u8_path")
                val base = root.optString("_base")
                if (m3u8Path.isNotBlank() && base.isNotBlank()) {
                    addSource(
                        MmNet.abs(base, m3u8Path),
                        root.optString("quality").ifBlank { "1080p" },
                        "$labelPrefix Filmu",
                        callback,
                    )
                    any = true
                }
            }
        }
        return any
    }
}

internal object MmVidbolt {

    private const val MOVY_KEY = "0f461eaa465bb2a7acd037425217f2f209ef540a3171e1ac"
    private const val CURX_KEY = "streamrip_secret_2026"
    private const val SCRAPER = "https://scraper.vidbolt.xyz"

    private suspend fun fetchJson(url: String): JSONObject? {
        val body = MmNet.get(url, referer = "https://vidbolt.xyz/") ?: return null
        return try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun addSources(
        root: JSONObject,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val sources = root.optJSONArray("sources") ?: return false
        var any = false
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val url = src.optString("url")
            if (url.isBlank() || !url.startsWith("http")) continue
            val quality = src.optString("quality").ifBlank { "HD" }
            val language = src.optString("language").ifBlank { "" }
            val langTag = if (language.isNotBlank() && language.lowercase() != "original") " $language" else ""
            callback(
                newExtractorLink(
                    label,
                    "$label $quality$langTag",
                    url,
                    type = PlayNet.linkType(url),
                )
            )
            any = true
        }
        return any
    }

    suspend fun resolve(
        isTv: Boolean,
        tmdbId: String,
        imdbId: String?,
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false

        if (!isTv) {
            val movy = fetchJson(
                "https://api.movy.lol/api/source/$tmdbId?api_key=$MOVY_KEY&apikey=$MOVY_KEY",
            )
            if (movy != null && addSources(movy, "$labelPrefix Orion", callback)) any = true
        } else if (season != null && episode != null) {
            val curx = fetchJson(
                "https://img.animecurx.tech/api/tv/$tmdbId/$season/$episode?api_key=$CURX_KEY&apikey=$CURX_KEY",
            )
            if (curx != null && addSources(curx, "$labelPrefix Orion", callback)) any = true
        }

        val idForScraper = imdbId ?: "tmdb$tmdbId"
        val type = if (isTv) "tv" else "movie"

        val quasarParams = buildString {
            append("tmdbId=$tmdbId")
            if (isTv && season != null) append("&season=$season")
            if (isTv && episode != null) append("&episode=$episode")
        }
        val quasar = fetchJson("$SCRAPER/scrape/Quasar/$type/$idForScraper?$quasarParams")
        if (quasar != null && addSources(quasar, "$labelPrefix Quasar", callback)) any = true

        val saffronParams = buildString {
            append("tmdbId=$tmdbId")
            if (imdbId != null) append("&imdbId=$imdbId")
            append("&title=${MmNet.urlEncode(title)}")
            if (!year.isNullOrBlank()) append("&year=$year")
            if (isTv && season != null) append("&season=$season")
            if (isTv && episode != null) append("&episode=$episode")
        }
        val saffron = fetchJson("$SCRAPER/scrape/Saffron/$type/$idForScraper?$saffronParams")
        if (saffron != null && addSources(saffron, "$labelPrefix Saffron", callback)) any = true

        val callistoParams = buildString {
            append("title=${MmNet.urlEncode(title)}")
            append("&tmdbId=$tmdbId")
            if (!year.isNullOrBlank()) append("&year=$year")
            if (isTv && season != null) append("&season=$season")
            if (isTv && episode != null) append("&episode=$episode")
        }
        val callisto = fetchJson("$SCRAPER/scrape/Callisto/$type/$idForScraper?$callistoParams")
        if (callisto != null && addSources(callisto, "$labelPrefix Callisto", callback)) any = true

        return any
    }
}
