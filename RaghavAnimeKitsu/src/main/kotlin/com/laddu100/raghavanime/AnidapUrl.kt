package com.laddu100.raghavanime

import com.lagradost.cloudstream3.app
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal object AnidapUrl {

    private const val UWU_XOR_KEY = "10b06cdc1ca48c9fb0b94af97cc040cf"
    private const val SHIRO_HOST = "https://hls.dramavideo.se"
    private val UWU_HOSTS = listOf("https://cdnx.aniwatchtv.site")

    // providers the web client wraps into the cdnx uwu proxy with a fixed key
    private val UWU_PROXY_KEYS = mapOf(
        "sora" to "https://krussdomi.com",
        "yuki" to "https://megaplay.buzz",
        "uwu" to "https://kwik.cx/",
        "kiwi" to "https://anidb.app/",
        "miku" to "https://allanime.uns.bio",
    )

    // these two serve their urls directly, the referer fallback never applies
    private val FALLBACK_EXCLUDE = setOf("vee", "neko")

    private val stripDomainRegex = Regex("^https?://[^/]+")

    private fun stripDomain(url: String): String = url.replace(stripDomainRegex, "")

    fun uwuToken(url: String, key: String): String {
        val keyBytes = UWU_XOR_KEY.toByteArray(Charsets.US_ASCII)
        val data = url.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + key.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size) { i -> (data[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
        return android.util.Base64.encodeToString(out, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            .trimEnd('=')
    }

    fun uwuProxy(url: String, key: String, hostIndex: Int = 0): String {
        val host = UWU_HOSTS[hostIndex % UWU_HOSTS.size]
        return "$host/uwu/${uwuToken(url, key)}"
    }

    fun shiroProxy(url: String): String {
        val sb = StringBuilder()
        for (b in url.toByteArray(Charsets.UTF_8)) {
            sb.append(String.format("%02x", (b.toInt() xor 137) and 0xFF))
        }
        return "$SHIRO_HOST/media/$sb&origin=https://kem.clvd.xyz/"
    }

    private fun beepRewrite(url: String): String {
        return when {
            url.startsWith("https://bd.24stream.xyz/media") ||
                url.startsWith("https://bd.aniwatchtv.site/media") -> url
            url.startsWith("/") ->
                "https://bd.aniwatchtv.site/media" + url.replace("/r2", "")
            else ->
                "https://bd.aniwatchtv.site/media" + stripDomain(url).replace("/r2", "")
        }
    }

    // mirrors the site's transformSourceUrl so plugin and web client land on the same url
    fun transform(url: String, providerId: String, referer: String?): String {
        val p = providerId.lowercase()
        var r = url

        if (r.contains("https://vivibebe.site/public/stream/")) {
            r = r.replace("https://vivibebe.site/public/stream/", "https://hawk.aniwatchtv.site/media/")
        }
        if (r.startsWith("https://playeng.animeapps.top/r2/")) {
            r = "https://bd.aniwatchtv.site/media" + stripDomain(r).replace("/r2", "")
        }

        when (p) {
            "shiro" -> r = shiroProxy(r)
            "sora", "yuki", "uwu", "kiwi", "miku" ->
                r = uwuProxy(r, UWU_PROXY_KEYS[p] ?: return r)
            "mochi" -> r = r.replace("https://tools.fast4speed.rsvp", "https://mp4.24stream.xyz/storage")
            "beep" -> r = beepRewrite(r)
        }

        // the site falls back to the proxy keyed by the response referer for
        // anything its handlers did not rewrite - that path is what makes
        // zuna, loli and adp dub urls playable
        if (r == url && !referer.isNullOrBlank() && p !in FALLBACK_EXCLUDE) {
            return uwuProxy(url, referer)
        }

        if (r.startsWith("http://")) {
            r = "https://" + r.removePrefix("http://")
        }
        return r
    }

    private val probeClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    fun fetchText(url: String, headers: Map<String, String>): String? {
        return try {
            val req = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..399) null else resp.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun fetchBytes(url: String, headers: Map<String, String>, rangeEnd: Int = 2047): ByteArray? {
        return try {
            val h = LinkedHashMap(headers)
            h["Range"] = "bytes=0-$rangeEnd"
            val req = Request.Builder().url(url)
                .apply { h.forEach { (k, v) -> header(k, v) } }
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in 200..399) null else resp.body?.bytes()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // megaplay cdns hide TS data behind a fake 1x1 PNG header; players resync
    // to the 0x47 sync byte past it, so accept that shape here too
    fun isVideoBytes(d: ByteArray?): Boolean {
        if (d == null || d.isEmpty()) return false
        if (d[0] == 0x47.toByte()) return true
        if (d.size > 8 && d[0] == 0x89.toByte() && d[1] == 0x50.toByte()) {
            val iend = indexOf(d, "IEND".toByteArray())
            if (iend >= 0) {
                for (i in iend + 4 until minOf(d.size, iend + 4 + 64)) {
                    if (d[i] == 0x47.toByte()) return true
                }
            }
            return false
        }
        if (indexOf(d, "ftyp".toByteArray()) in 0..64) return true
        return false
    }

    class Variant(val quality: Int?, val url: String)

    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http")) return ref
        val schemeIdx = base.indexOf("://")
        if (schemeIdx < 0) return ref
        val host = base.substring(schemeIdx + 3).substringBefore("/")
        val origin = base.substring(0, schemeIdx + 3) + host
        return if (ref.startsWith("/")) origin + ref
        else base.substringBeforeLast("/") + "/" + ref
    }

    fun parseMaster(body: String, masterUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        var pendingHeight: Int? = null
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                pendingHeight = res?.groupValues?.get(2)?.toIntOrNull()
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                out.add(Variant(pendingHeight, resolveUrl(masterUrl, line)))
                pendingHeight = null
            }
        }
        return out
    }

    fun firstSegment(body: String, playlistUrl: String): String? {
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                return resolveUrl(playlistUrl, line)
            }
        }
        return null
    }

    // the site names some hls playlists ".txt", treat them the same
    fun looksLikeHls(url: String, declaredType: String?): Boolean {
        val t = (declaredType ?: "").lowercase()
        val path = url.substringBefore("?")
        return t.contains("mpegurl") || t.contains("m3u8") ||
            path.endsWith(".m3u8") || path.endsWith(".txt") ||
            url.contains(".m3u8") || url.contains("index.txt")
    }

    fun looksLikeDash(url: String, declaredType: String?): Boolean {
        val t = (declaredType ?: "").lowercase()
        return t.contains("dash") || url.substringBefore("?").endsWith(".mpd") || url.contains(".mpd")
    }

    fun looksLikeMp4(url: String, declaredType: String?): Boolean {
        val t = (declaredType ?: "").lowercase()
        val path = url.substringBefore("?")
        return t.contains("mp4") || t.contains("webm") || t.contains("matroska") ||
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv")
    }
}
