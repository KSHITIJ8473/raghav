package com.play4u

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

object Play4uApi {

    private const val TAG = "Play4u"

    @Volatile
    var MAIN_URL = "https://play4u.org"
        private set

    // every sealed url in a page decodes with the key shipped right next to it
    private const val SEAL_PREFIX = "pa!~"
    private const val DEFAULT_SEAL_KEY = "pa4u-stream-seal-v1"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36"

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("play4u")
            ?.takeIf { it.contains("play4u") }
            ?.let { MAIN_URL = it.trimEnd('/') }
    }

    val browserHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$MAIN_URL/"
        )

    // the stream hosts answer with an html decoy unless the site origin rides along
    val streamHeaders: Map<String, String>
        get() = mapOf(
            "Referer" to "$MAIN_URL/",
            "Origin" to MAIN_URL
        )

    // the site helper swaps the url safe alphabet for the standard one before
    // atob, so the decode has to run on the standard table too
    fun unseal(value: String?, key: String = DEFAULT_SEAL_KEY): String {
        if (value.isNullOrBlank()) return ""
        if (!value.startsWith(SEAL_PREFIX)) return value
        var b64 = value.substring(SEAL_PREFIX.length)
            .replace("-", "+")
            .replace("_", "/")
        when (b64.length % 4) {
            1 -> return ""
            2 -> b64 += "=="
            3 -> b64 += "="
        }
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val kb = key.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor (kb[i % kb.size].toInt() and 0xFF)).toChar())
            }
            val text = out.toString()
            // a decoded url is plain ascii, anything else means the seal moved
            if (text.isEmpty() || text.any { it < ' ' || it > '~' }) "" else text
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "unseal: ${e.message}")
            ""
        }
    }

    // the seal key rides in a window var, reading it per page survives rotations
    private val SEAL_KEY_PATTERN = Regex("""window\.__PA_SEAL_KEY__\s*=\s*"([^"]*)"""")

    fun sealKeyOf(html: String): String =
        SEAL_KEY_PATTERN.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SEAL_KEY

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun page(path: String): String? {
        val target = if (path.startsWith("http")) path else "$MAIN_URL$path"
        return try {
            val res = app.get(target, headers = browserHeaders, timeout = 25L)
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            Log.d(TAG, "page $target: ${e.message}")
            null
        }
    }

    // the same call the site header search box makes, browse filters included
    suspend fun search(
        query: String?,
        page: Int,
        perPage: Int = 24,
        type: String? = null,
        genre: String? = null,
        industry: String? = null,
        lang: String? = null
    ): SearchPage? {
        val url = buildString {
            append("$MAIN_URL/api/search.php?q=")
            append(urlEncode(query ?: ""))
            append("&page=")
            append(page.coerceAtLeast(1))
            append("&per_page=")
            append(perPage)
            type?.let { append("&type=").append(it) }
            genre?.let { append("&genre=").append(it) }
            industry?.let { append("&industry=").append(it) }
            lang?.let { append("&lang=").append(it) }
        }
        return try {
            val res = app.get(url, headers = browserHeaders, timeout = 20L)
            if (!res.isSuccessful) return null
            parseJson<SearchPage>(res.text)
        } catch (e: Exception) {
            Log.d(TAG, "search: ${e.message}")
            null
        }
    }

    // slices a window.__PA_*__ object literal out of a page, the slice is
    // plain json so it parses as is
    fun extractVar(html: String, name: String): String? {
        val marker = "window.$name = "
        val at = html.indexOf(marker)
        if (at < 0) return null
        var start = html.indexOf('=', at) + 1
        while (start < html.length && html[start].isWhitespace()) start++
        if (start >= html.length || html[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{', '[' -> if (!inString) depth++
                '}', ']' -> if (!inString) {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun playVar(html: String): PlayPayload? =
        extractVar(html, "__PA_PLAY__")?.let {
            try {
                parseJson<PlayPayload>(it)
            } catch (e: Exception) {
                Log.d(TAG, "play var: ${e.message}")
                null
            }
        }

    fun seriesVar(html: String): SeriesPayload? =
        extractVar(html, "__PA_SERIES__")?.let {
            try {
                parseJson<SeriesPayload>(it)
            } catch (e: Exception) {
                Log.d(TAG, "series var: ${e.message}")
                null
            }
        }
}
