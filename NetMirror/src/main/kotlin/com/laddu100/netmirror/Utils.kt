package com.laddu100.netmirror

import com.laddu100.netmirror.entities.Source
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass
import okhttp3.FormBody
import com.lagradost.api.Log
import java.util.UUID
import okhttp3.Request
import java.util.Base64

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
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T {
    return JSONParser.parse(text, T::class)
}

inline fun <reified T : Any> tryParseJson(text: String): T? {
    return try {
        return JSONParser.parseSafe(text, T::class)
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

@Volatile
var netMirrorWorkingDomain: String = "https://net52.cc"

private val candidateDomains = listOf(
    "https://net52.cc",
    "https://net77.cc",
    "https://net22.cc",
    "https://net99.cc",
    "https://net50.cc"
)

private fun bypassHeaders(base: String): Map<String, String> = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Encoding" to "gzip, deflate, br, zstd",
    "Accept-Language" to "en-US,en;q=0.9",
    "Cache-Control" to "max-age=0",
    "Connection" to "keep-alive",
    "Origin" to base,
    "Referer" to "$base/verify2",
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

private suspend fun tryBypassDomain(domain: String): String {
    val base = domain.trimEnd('/')
    val client = app.baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    try {
        val getReq = Request.Builder()
            .url("$base/verify2")
            .get()
            .apply { bypassHeaders(base).forEach { (k, v) -> addHeader(k, v) } }
            .build()
        client.newCall(getReq).execute().use { resp ->
            resp.headers("Set-Cookie").forEach { sc ->
                val name = sc.substringBefore("=", "").trim()
                val value = sc.substringAfter("=", "").substringBefore(";").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    // collected but not strictly needed
                }
            }
        }
    } catch (_: Exception) { }

    val formBody = FormBody.Builder()
        .add("g-recaptcha-response", UUID.randomUUID().toString())
        .build()
    val postReq = Request.Builder()
        .url("$base/verify.php")
        .post(formBody)
        .apply { bypassHeaders(base).forEach { (k, v) -> addHeader(k, v) } }
        .build()

    return try {
        client.newCall(postReq).execute().use { response ->
            Log.d(TAG, "bypass: $base/verify.php HTTP ${response.code}")
            response.headers("Set-Cookie")
                .firstOrNull { it.startsWith("t_hash_t=") }
                ?.substringAfter("t_hash_t=")
                ?.substringBefore(";")
                .orEmpty()
        }
    } catch (e: Exception) {
        Log.d(TAG, "bypass: $base exception: ${e.message}")
        ""
    }
}

suspend fun bypass(mainUrl: String): String {
    val (savedCookie, savedTimestamp) = NetflixMirrorStorage.getCookie()

    if (!savedCookie.isNullOrEmpty() && System.currentTimeMillis() - savedTimestamp < 300_000) {
        return savedCookie
    }

    for (domain in candidateDomains) {
        val cookie = tryBypassDomain(domain)
        if (cookie.isNotEmpty()) {
            netMirrorWorkingDomain = domain
            NetflixMirrorStorage.saveCookie(cookie)
            return cookie
        }
    }

    NetflixMirrorStorage.clearCookie()
    return ""
}

val newTvBaseHeaders = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept" to "application/json, text/plain, */*"
)

val checkDomains = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFwcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmxj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
)

fun decodeBase64(value: String): String {
    return String(Base64.getDecoder().decode(value))
}

private var resolvedStapeUrl: String = ""
private var resolvedStapeUrlTime: Long = 0L
private const val STAPE_URL_TTL_MS = 10 * 60 * 1000L

suspend fun resolveStapeUrl(force: Boolean = false): String {
    val now = System.currentTimeMillis()
    if (!force && resolvedStapeUrl.isNotBlank() && now - resolvedStapeUrlTime < STAPE_URL_TTL_MS) {
        return resolvedStapeUrl
    }
    for (encoded in checkDomains) {
        val base = try {
            decodeBase64(encoded).trimEnd('/')
        } catch (_: Exception) {
            continue
        }
        try {
            val response = app.get("$base/check.php", headers = newTvBaseHeaders)
            val resp = tryParseJson<CheckResponse>(response.text) ?: continue
            val stapeEncoded = resp.stape ?: continue
            val stape = try {
                decodeBase64(stapeEncoded).trimEnd('/')
            } catch (_: Exception) {
                continue
            }
            if (stape.startsWith("http")) {
                resolvedStapeUrl = stape
                resolvedStapeUrlTime = now
                return resolvedStapeUrl
            }
        } catch (_: Exception) {
            // Try next domain.
        }
    }
    throw Exception("Failed to resolve streamtape URL")
}

suspend fun loadNewTvLinks(
    id: String,
    ott: String,
    providerName: String,
    callback: (ExtractorLink) -> Unit,
    subtitleCallback: (SubtitleFile) -> Unit
): Boolean {
    Log.d(TAG, "$providerName: loadLinks id=$id ott=$ott")
    val apiBase = try {
        resolveApiUrl()
    } catch (e: Exception) {
        Log.e(TAG, "$providerName: resolveApiUrl failed: ${e.message}")
        return false
    }
    Log.d(TAG, "$providerName: apiBase=$apiBase")

    val token = getNewTvUserToken(apiBase, ott)
    Log.d(TAG, "$providerName: token=${if (token.isNotEmpty()) "YES" else "NO"}")
    if (token.isEmpty()) return false

    val headers = buildNewTvHeaders(ott, mapOf("Usertoken" to token))
    val resp = try {
        val r = app.get("$apiBase/newtv/player.php?id=$id", headers = headers)
        Log.d(TAG, "$providerName: player.php HTTP ${r.code} body=${r.text.take(200)}")
        r.parsedSafe<NewTvPlayerResponse>()
    } catch (e: Exception) {
        Log.e(TAG, "$providerName: player.php exception: ${e.message}")
        null
    } ?: return false

    val videoLink = resp.video_link
    if (videoLink.isNullOrBlank()) {
        Log.e(TAG, "$providerName: no video_link status=${resp.status}")
        return false
    }

    Log.d(TAG, "$providerName: got video_link=${videoLink.take(80)}")
    callback.invoke(
        newExtractorLink(providerName, providerName, videoLink, type = ExtractorLinkType.M3U8) {
            this.referer = resp.referer ?: apiBase
        }
    )
    return true
}

suspend fun getNewTvUserToken(apiBase: String, ott: String): String {
    val (savedToken, savedTs) = NetflixMirrorStorage.getUserToken(ott)
    if (!savedToken.isNullOrEmpty() && System.currentTimeMillis() - savedTs < 86_400_000) {
        Log.d(TAG, "getNewTvUserToken: using cached token for ott=$ott")
        return savedToken
    }

    val otpHeaders = mapOf(
        "accept" to "application/json, text/plain, */*",
        "cache-control" to "no-cache, no-store, must-revalidate",
        "Connection" to "Keep-Alive",
        "expires" to "0",
        "otp" to "111111",
        "pragma" to "no-cache",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.Gatu v1.0"
    )

    val resp = try {
        val r = app.get("$apiBase/newtv/otp.php", headers = otpHeaders)
        Log.d(TAG, "getNewTvUserToken: otp.php HTTP ${r.code} body=${r.text.take(200)}")
        r.parsedSafe<NewTvOtpResponse>()
    } catch (e: Exception) {
        Log.e(TAG, "getNewTvUserToken: otp.php exception: ${e.message}")
        null
    } ?: return ""

    val newToken = resp.usertoken.orEmpty()
    if (newToken.isNotEmpty()) {
        NetflixMirrorStorage.saveUserToken(ott, newToken)
        Log.d(TAG, "getNewTvUserToken: got token (${newToken.length} chars)")
    } else {
        Log.e(TAG, "getNewTvUserToken: no usertoken in response, status=${resp.status}")
    }
    return newToken
}

@Volatile
private var resolvedApiUrl: String = ""
@Volatile
private var resolvedApiUrlTime: Long = 0L
private const val API_URL_TTL_MS = 10 * 60 * 1000L

suspend fun resolveApiUrl(): String {
    val now = System.currentTimeMillis()
    if (resolvedApiUrl.isNotBlank() && now - resolvedApiUrlTime < API_URL_TTL_MS) {
        return resolvedApiUrl
    }

    val (savedBase, savedTs) = NetflixMirrorStorage.getApiBase()
    if (!savedBase.isNullOrEmpty() && now - savedTs < 86_400_000) {
        resolvedApiUrl = savedBase
        resolvedApiUrlTime = now
        Log.d(TAG, "resolveApiUrl: using cached=$savedBase")
        return resolvedApiUrl
    }

    for (encoded in checkDomains) {
        val base = try {
            decodeBase64(encoded).trimEnd('/')
        } catch (_: Exception) {
            continue
        }
        try {
            val r = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
            Log.d(TAG, "resolveApiUrl: $base/checknewtv.php HTTP ${r.code}")
            val resp = r.parsedSafe<NewTvTokenResponse>() ?: continue
            val tokenHash = resp.token_hash ?: continue
            val decoded = try {
                decodeBase64(tokenHash).trimEnd('/')
            } catch (_: Exception) {
                continue
            }
            if (decoded.startsWith("http")) {
                resolvedApiUrl = decoded
                resolvedApiUrlTime = System.currentTimeMillis()
                NetflixMirrorStorage.saveApiBase(decoded)
                Log.d(TAG, "resolveApiUrl: resolved=$decoded")
                return resolvedApiUrl
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveApiUrl: $base failed: ${e.message}")
        }
    }
    throw Exception("Failed to resolve NewTV API base URL")
}

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val result = newTvBaseHeaders.toMutableMap()
    result["Ott"] = ott
    extra.forEach { (key, value) -> result[key] = value }
    return result
}

data class CheckResponse(
    val token_hash: String? = null,
    val doms: String? = null,
    val mwin: String? = null,
    val popwin: String? = null,
    val `var`: String? = null,
    val stape: String? = null,
    val u: List<String>? = null
)

data class StreamtapeResponse(
    val status: String? = null,
    val video_link: String? = null,
    val link: String? = null,
    val url: String? = null,
    val file: String? = null,
    val source: String? = null,
    val referer: String? = null,
    val sources: List<Source>? = null
)

data class NewTvTokenResponse(
    val token_hash: String? = null,
    val doms: String? = null,
    val mwin: String? = null,
    val popwin: String? = null,
    val `var`: String? = null
)

data class NewTvPlayerResponse(
    val status: String? = null,
    val ott: String? = null,
    val usertoken: String? = null,
    val video_link: String? = null,
    val referer: String? = null,
    val error: String? = null,
    val title: String? = null,
    val ep: String? = null,
    val ep_title: String? = null
)

data class NewTvOtpResponse(
    val otp: String? = null,
    val status: String? = null,
    val usertoken: String? = null,
    val pub_msg: String? = null,
    val pub_msg_f_size: Int? = null,
    val pub_msg_color: String? = null
)
