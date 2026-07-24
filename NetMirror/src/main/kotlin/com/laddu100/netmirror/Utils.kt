package com.laddu100.netmirror

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.api.Log
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.UUID
import kotlin.reflect.KClass

private const val TAG = "NetMirror"

val JSONParser = object : ResponseParser {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
    ).configure(
        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
    )

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return mapper.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            mapper.readValue(text, kClass.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }
}

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    )
}

inline fun <reified T : Any> parseJson(text: String): T {
    return JSONParser.parse(text, T::class)
}

inline fun <reified T : Any> tryParseJson(text: String): T? {
    return try {
        JSONParser.parseSafe(text, T::class)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0
    val parts = runtime.split(" ")
    for (part in parts) {
        when {
            part.endsWith("h") -> {
                val hours = part.removeSuffix("h").trim().toIntOrNull() ?: 0
                totalMinutes += hours * 60
            }
            part.endsWith("m") -> {
                val minutes = part.removeSuffix("m").trim().toIntOrNull() ?: 0
                totalMinutes += minutes
            }
        }
    }
    return totalMinutes
}

/**
 * Bypass the netmirror verification gate.
 *
 * Faithfully reproduces the upstream mobile plugin's bypass:
 *   1. If a saved t_hash_t cookie exists and is younger than 15 hours, reuse it.
 *   2. Otherwise, POST to https://net52.cc/verify.php with:
 *        - Origin: https://net22.cc
 *        - Referer: https://net22.cc/verify2
 *        - Body: g-recaptcha-response=<random UUID>
 *        - followRedirects=false (we want the Set-Cookie from the 302/200 response)
 *   3. Extract t_hash_t from the first matching Set-Cookie header.
 *   4. Save it for next time and return it.
 *
 * The key insight (which the previous broken version was missing): we POST
 * directly to verify.php on net52.cc — we do NOT GET verify2.php first
 * (verify2.php returns Cloudflare-compressed binary that we cannot parse).
 * The Origin/Referer must claim net22.cc even though the POST goes to net52.cc.
 */
suspend fun bypass(mainUrl: String): String {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()
    if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < 54_000_000L) {
        Log.d(TAG, "bypass: using cached cookie (age=${(System.currentTimeMillis() - savedTimestamp) / 1000}s)")
        return savedCookie
    }

    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Encoding" to "gzip, deflate, br, zstd",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Content-Type" to "application/x-www-form-urlencoded",
        "Origin" to "https://net22.cc",
        "Referer" to "https://net22.cc/verify2",
        "sec-ch-ua" to "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    )

    val formBody = FormBody.Builder()
        .add("g-recaptcha-response", UUID.randomUUID().toString())
        .build()

    val client: OkHttpClient = app.baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    return try {
        val requestBuilder = Request.Builder()
            .url("https://net52.cc/verify.php")
            .post(formBody)
        headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            Log.d(TAG, "bypass: POST https://net52.cc/verify.php HTTP ${response.code}")

            val setCookies = response.headers("Set-Cookie")
            Log.d(TAG, "bypass: Set-Cookie count=${setCookies.size}")
            setCookies.forEachIndexed { i, sc ->
                Log.d(TAG, "bypass: Set-Cookie[$i] prefix=${sc.take(80)}")
            }

            val tHashCookie = setCookies.firstOrNull { it.startsWith("t_hash_t=") }
            val newCookie = tHashCookie
                ?.substringAfter("t_hash_t=")
                ?.substringBefore(";")
                ?: ""

            // Also persist the raw Set-Cookie value — it's used as the `in=` parameter
            // for the m3u8 URL (the upstream mobile plugin stores it as nf_cookie_full).
            if (tHashCookie != null) {
                NetflixMirrorStorage.saveFullCookie(tHashCookie.substringAfter("t_hash_t=").substringBefore(";"))
            }

            if (newCookie.isNotEmpty()) {
                NetflixMirrorStorage.saveCookie(newCookie)
                Log.d(TAG, "bypass: SUCCESS — t_hash_t cookie acquired (len=${newCookie.length})")
            } else {
                Log.e(TAG, "bypass: FAILED — no t_hash_t cookie in response")
                NetflixMirrorStorage.clearCookie()
            }
            newCookie
        }
    } catch (e: Exception) {
        Log.e(TAG, "bypass: exception: ${e.message}")
        NetflixMirrorStorage.clearCookie()
        ""
    }
}

/**
 * The m3u8 stream URL format used by the upstream mobile plugin:
 *   https://net52.cc/mobile/<ott>/hls/<id>.m3u8?in=<cookie_value>
 *
 * The cookie value is the t_hash_t cookie content with %3A%3A decoded to "::".
 * This function builds that URL and returns it along with the headers/cookies
 * needed to fetch it.
 *
 * If the t_hash_t cookie contains URL-encoded "::" sequences ("%3A%3A"),
 * we decode them — the upstream player does the same before putting the cookie
 * into the `in=` query parameter.
 */
fun buildM3u8Url(mainUrl: String, ott: String, id: String, cookie: String): String {
    val base = mainUrl.trimEnd('/')
    val decodedCookie = cookie.replace("%3A%3A", "::")
    return "$base/mobile/$ott/hls/$id.m3u8?in=$decodedCookie"
}

/** Headers shared by every provider for browse/search/load requests. */
val baseBrowseHeaders = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
    "Cache-Control" to "max-age=0",
    "Connection" to "keep-alive",
    "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Android\"",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "same-origin",
    "Sec-Fetch-User" to "?1",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
    "X-Requested-With" to "XMLHttpRequest"
)
