package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "MM_Net"

object MMNet {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // hosts that keep per-session cookies (the byse player binds its captcha to them)
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
    } catch (e: Exception) {
        ""
    }

    fun setCookie(host: String, name: String, value: String) {
        cookieJar.getOrPut(host) { ConcurrentHashMap() }[name] = value
    }

    fun cookieHeader(host: String): String? =
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
    ): String? = try {
        val resp = app.get(
            url,
            headers = base(headers, referer, null),
            timeout = 30_000L,
        )
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        Log.d(TAG, "get ${hostOf(url)}: ${e.message}")
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
    } catch (e: Exception) {
        Log.d(TAG, "post ${hostOf(url)}: ${e.message}")
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
    } catch (e: Exception) {
        Log.d(TAG, "post ${hostOf(url)}: ${e.message}")
        null
    }

    private fun storeSetCookies(host: String, values: List<String>) {
        for (raw in values) {
            val pair = raw.substringBefore(";")
            val idx = pair.indexOf('=')
            if (idx > 0) setCookie(host, pair.substring(0, idx).trim(), pair.substring(idx + 1).trim())
        }
    }

    // the PoW search is pure integer work, keep it off the IO threads
    suspend fun <T> compute(block: () -> T): T = withContext(Dispatchers.Default) { block() }
}
