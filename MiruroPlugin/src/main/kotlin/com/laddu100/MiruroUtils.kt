package com.laddu100

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

// ─── Pipe Encoding/Decoding ─────────────────────────────────────────────────

fun encodePipeRequest(payload: Map<String, Any?>): String {
    val json = payload.toJson()
    return Base64.encodeToString(
        json.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

fun decodePipeResponse(responseBody: String): String {
    val trimmed = responseBody.trim()
    val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
    val compressed = Base64.decode(padded, Base64.URL_SAFE)
    val bais = ByteArrayInputStream(compressed)
    val gzis = GZIPInputStream(bais)
    val baos = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var len: Int
    while (gzis.read(buffer).also { len = it } != -1) {
        baos.write(buffer, 0, len)
    }
    gzis.close()
    return baos.toString("UTF-8")
}

/**
 * Try to decode a pipe response. Falls back to plain JSON if the response
 * is not gzip+base64 (API may have changed format).
 */
fun decodePipeResponseSafe(responseBody: String): String {
    val trimmed = responseBody.trim()
    // Try gzip+base64 first
    try {
        return decodePipeResponse(trimmed)
    } catch (_: Exception) {}
    // Fallback: maybe it's plain JSON
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        return trimmed
    }
    throw Exception("Cannot decode pipe response (not gzip+base64, not JSON)")
}

fun translateEpisodeId(encodedId: String): String {
    return try {
        val padded = encodedId + "=".repeat((4 - encodedId.length % 4) % 4)
        val decoded = Base64.decode(padded, Base64.URL_SAFE).toString(Charsets.UTF_8)
        if (decoded.contains(":")) decoded else encodedId
    } catch (e: Exception) {
        encodedId
    }
}

// ─── Cloudflare Bypass + Domain Management ──────────────────────────────────
//
// Design (v2 — fast & reliable):
//   1. OkHttp direct with cached cookies (fast path — ~1s when cookies work)
//   2. WebView fetch (solves CF challenge + reads response body in one step)
//   Only 2 domains tried (not 4). Cookies cached after first WebView success
//   so subsequent requests take the fast OkHttp path.

val MIRURO_DOMAINS = listOf(
    "https://www.miruro.ru",
    "https://www.miruro.tv",
    "https://www.miruro.to",
    "https://www.miruro.bz"
)

const val CF_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

object MiruroCloudflare {
    private val cookieCache = ConcurrentHashMap<String, String>()
    private val workingDomain = AtomicReference<String?>(MIRURO_DOMAINS[0])

    fun getWorkingDomain(): String = workingDomain.get() ?: MIRURO_DOMAINS[0]
    fun setWorkingDomain(d: String) { workingDomain.set(d) }

    fun getCookies(baseUrl: String): String? = cookieCache[baseUrl]
    fun setCookies(baseUrl: String, cookies: String) { cookieCache[baseUrl] = cookies }

    fun isCloudflareBlock(text: String, code: Int): Boolean {
        if (code == 403 || code == 503) {
            val lower = text.lowercase()
            return lower.contains("cloudflare") ||
                   lower.contains("just a moment") ||
                   lower.contains("sorry, you have been blocked") ||
                   lower.contains("cf-ray") ||
                   lower.contains("challenge-platform") ||
                   lower.contains("attention required") ||
                   lower.contains("enable cookies")
        }
        val lower = text.lowercase()
        if (lower.contains("just a moment") && lower.contains("challenge")) return true
        return false
    }

    /**
     * Fetch a pipe URL via WebView. Solves Cloudflare challenge automatically
     * (WebView executes CF JS), then reads the response body via innerText.
     *
     * Polls content at 3s, 6s, 9s, 12s, 15s, 18s to catch the response after
     * CF challenge resolves (can take 5-10s).
     *
     * On success, saves cookies for future fast OkHttp requests.
     */
    suspend fun fetchViaWebView(
        context: Context?,
        domain: String,
        url: String,
        referer: String
    ): String? {
        if (context == null) return null

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                var webView: WebView? = null

                fun finish(result: String?) {
                    if (done.compareAndSet(false, true)) {
                        // Save cookies for future OkHttp calls
                        try {
                            val cookies = CookieManager.getInstance().getCookie(domain) ?: ""
                            if (cookies.isNotEmpty()) {
                                setCookies(domain, cookies)
                            }
                        } catch (_: Exception) {}
                        try { webView?.destroy() } catch (_: Exception) {}
                        cont.resume(result)
                    }
                }

                fun checkContent(view: WebView?) {
                    if (done.get()) return
                    view?.evaluateJavascript(
                        """(function(){
                            var t = '';
                            try { t = document.body.innerText || ''; } catch(e) {}
                            if (!t) try { t = document.documentElement.textContent || ''; } catch(e) {}
                            return t;
                        })()"""
                    ) { result ->
                        if (done.get()) return@evaluateJavascript
                        val text = result
                            ?.trim()
                            ?.removeSurrounding("\"")
                            ?.replace("\\n", "\n")
                            ?.replace("\\\"", "\"")
                            ?.replace("\\\\", "\\") ?: ""
                        if (isValidPipeResponse(text)) {
                            finish(text)
                        }
                        // If not valid, keep waiting (CF still resolving or page not ready)
                    }
                }

                try {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = CF_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                // Wait 3s for CF challenge JS to run, then check content
                                Handler(Looper.getMainLooper()).postDelayed({
                                    checkContent(view)
                                }, 3000)
                            }
                        }
                    }

                    val headers = HashMap<String, String>()
                    headers["Referer"] = referer
                    webView?.loadUrl(url, headers)

                    // Poll content at 6s, 9s, 12s, 15s, 18s
                    // (3s is handled by onPageFinished)
                    val pollDelays = longArrayOf(6000, 9000, 12000, 15000, 18000)
                    for (delay in pollDelays) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            checkContent(webView)
                        }, delay)
                    }

                    // Overall timeout: 22s
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish(null)
                    }, 22000)
                } catch (e: Exception) {
                    finish(null)
                }
            }
        }
    }
}

/**
 * Check if text looks like a valid pipe API response (not a CF challenge page).
 * Valid responses are long base64 strings that decode to gzip data.
 */
private fun isValidPipeResponse(text: String): Boolean {
    if (text.length < 50) return false
    val lower = text.lowercase()
    if (lower.contains("cloudflare") || lower.contains("just a moment") ||
        lower.contains("blocked") || lower.contains("enable cookies") ||
        lower.contains("<html") || lower.contains("<!doctype") ||
        lower.contains("attention required") || lower.contains("cf-ray")) {
        return false
    }
    // Check if it's valid base64 that decompresses as gzip
    return try {
        val padded = text + "=".repeat((4 - text.length % 4) % 4)
        val compressed = Base64.decode(padded, Base64.URL_SAFE)
        compressed.size > 2 &&
            compressed[0] == 0x1f.toByte() &&
            compressed[1] == 0x8b.toByte()
    } catch (_: Exception) {
        false
    }
}

// ─── Pipe Request (Cloudflare-aware, 2 domains max) ─────────────────────────

/**
 * Makes a Miruro pipe API request. Tries the working domain first,
 * then ONE alternate. Each domain gets 2 attempts: OkHttp → WebView fetch.
 */
suspend fun miruroPipeRequest(path: String, query: Map<String, Any>): String {
    val payload = mapOf(
        "path" to path,
        "method" to "GET",
        "query" to query,
        "body" to null,
        "version" to "0.1.0"
    )
    val encoded = encodePipeRequest(payload)

    val working = MiruroCloudflare.getWorkingDomain()
    val domainsToTry = mutableListOf(working)
    for (d in MIRURO_DOMAINS) {
        if (d != working && domainsToTry.size < 2) {
            domainsToTry.add(d)
        }
    }

    var lastError: Exception? = null
    for (domain in domainsToTry) {
        try {
            val result = miruroPipeRequestForDomain(domain, encoded, path)
            MiruroCloudflare.setWorkingDomain(domain)
            return result
        } catch (e: Exception) {
            println("Miruro: pipe '$path' failed on $domain - ${e.message}")
            lastError = e
        }
    }
    throw lastError ?: Exception("All Miruro domains failed for /$path")
}

private suspend fun miruroPipeRequestForDomain(
    domain: String,
    encoded: String,
    path: String
): String {
    val pipeUrl = "$domain/api/secure/pipe?e=$encoded"
    val headers = mutableMapOf(
        "User-Agent" to CF_USER_AGENT,
        "Referer" to "$domain/",
        "Origin" to domain,
        "Accept" to "*/*"
    )
    MiruroCloudflare.getCookies(domain)?.let { headers["Cookie"] = it }

    // ── Step 1: OkHttp direct (fast path — ~1s when cookies work) ──
    try {
        val response = app.get(pipeUrl, headers = headers, timeout = 15)
        if (response.code == 200) {
            val body = response.text
            if (!MiruroCloudflare.isCloudflareBlock(body, 200)) {
                try {
                    return decodePipeResponseSafe(body)
                } catch (_: Exception) {
                    // Body wasn't decodable — fall through to WebView
                }
            }
        }
    } catch (_: Exception) {
        // Network error — fall through to WebView
    }

    // ── Step 2: WebView fetch (solves CF + reads response body) ──
    val webBody = MiruroCloudflare.fetchViaWebView(
        Miruro.context, domain, pipeUrl, "$domain/"
    )
    if (webBody != null) {
        try {
            return decodePipeResponseSafe(webBody)
        } catch (_: Exception) {}
    }

    throw Exception("Failed on $domain for /$path")
}

// ─── AniList GraphQL ────────────────────────────────────────────────────────

const val ANILIST_URL = "https://graphql.anilist.co"

val SEARCH_QUERY = """
    query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                id
                title { romaji english native }
                coverImage { large extraLarge }
                bannerImage
                format
                episodes
                status
                seasonYear
                averageScore
                genres
                description(asHtml: false)
                duration
                studios(isMain: true) { nodes { name } }
                startDate { year month day }
            }
        }
    }
""".trimIndent()

val TRENDING_QUERY = """
    query (${'$'}page: Int, ${'$'}perPage: Int) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: TRENDING_DESC) {
                id
                title { romaji english native }
                coverImage { large extraLarge }
                format
                episodes
                status
                seasonYear
                averageScore
                genres
            }
        }
    }
""".trimIndent()

val POPULAR_QUERY = """
    query (${'$'}page: Int, ${'$'}perPage: Int) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: POPULARITY_DESC) {
                id
                title { romaji english native }
                coverImage { large extraLarge }
                format
                episodes
                status
                seasonYear
                averageScore
                genres
            }
        }
    }
""".trimIndent()

val RECENT_QUERY = """
    query (${'$'}page: Int, ${'$'}perPage: Int) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: START_DATE_DESC, status: RELEASING) {
                id
                title { romaji english native }
                coverImage { large extraLarge }
                format
                episodes
                status
                seasonYear
                averageScore
                genres
            }
        }
    }
""".trimIndent()

val INFO_QUERY = """
    query (${'$'}id: Int) {
        Media(id: ${'$'}id, type: ANIME) {
            id
            title { romaji english native }
            description(asHtml: false)
            coverImage { large extraLarge color }
            bannerImage
            format
            season
            seasonYear
            episodes
            duration
            status
            averageScore
            meanScore
            popularity
            favourites
            genres
            tags { name rank }
            source
            studios { nodes { id name isAnimationStudio } }
            nextAiringEpisode { episode airingAt timeUntilAiring }
            startDate { year month day }
            endDate { year month day }
            relations {
                edges {
                    relationType(version: 2)
                    node {
                        id
                        title { romaji english }
                        coverImage { large }
                        format
                        type
                        status
                        episodes
                    }
                }
            }
            recommendations(sort: RATING_DESC, perPage: 10) {
                nodes {
                    mediaRecommendation {
                        id
                        title { romaji english }
                        coverImage { large }
                        format
                        episodes
                        status
                        averageScore
                    }
                }
            }
        }
    }
""".trimIndent()

suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
    val requestData = mapOf(
        "query" to query,
        "variables" to variables
    ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

    val headers = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json"
    )

    val response = app.post(
        ANILIST_URL,
        headers = headers,
        requestBody = requestData
    )
    return response.text
}

// ─── Data Classes ───────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListResponse(@JsonProperty("data") val data: AniListData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListData(
    @JsonProperty("Page") val Page: AniListPage? = null,
    @JsonProperty("Media") val Media: AniListMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPage(@JsonProperty("media") val media: List<AniListMedia>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("studios") val studios: AniListStudios? = null,
    @JsonProperty("recommendations") val recommendations: AniListRecommendations? = null,
    @JsonProperty("relations") val relations: AniListRelations? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("native") val native: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCoverImage(
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("color") val color: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListStudios(@JsonProperty("nodes") val nodes: List<AniListStudio>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListStudio(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("isAnimationStudio") val isAnimationStudio: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListRecommendations(@JsonProperty("nodes") val nodes: List<AniListRecNode>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListRecNode(@JsonProperty("mediaRecommendation") val mediaRecommendation: AniListMedia? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListRelations(@JsonProperty("edges") val edges: List<AniListRelationEdge>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListRelationEdge(
    @JsonProperty("relationType") val relationType: String? = null,
    @JsonProperty("node") val node: AniListMedia? = null
)

// Miruro Pipe response models
@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodesResponse(
    @JsonProperty("providers") val providers: Map<String, MiruroProvider>? = null,
    @JsonProperty("mappings") val mappings: MiruroMappings? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroProvider(
    @JsonProperty("episodes") val episodes: MiruroEpisodeCategories? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodeCategories(
    @JsonProperty("sub") val sub: List<MiruroEpisode>? = null,
    @JsonProperty("dub") val dub: List<MiruroEpisode>? = null,
    @JsonProperty("ssub") val ssub: List<MiruroEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("isFiller") val isFiller: Boolean? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("description") val description: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroMappings(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("malId") val malId: Int? = null,
    @JsonProperty("aniId") val aniId: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroSourcesResponse(
    @JsonProperty("streams") val streams: List<MiruroStream>? = null,
    @JsonProperty("subtitles") val subtitles: List<MiruroSubtitle>? = null,
    @JsonProperty("intro") val intro: MiruroSkipTime? = null,
    @JsonProperty("outro") val outro: MiruroSkipTime? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("resolution") val resolution: MiruroResolution? = null,
    @JsonProperty("codec") val codec: String? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("fansub") val fansub: String? = null,
    @JsonProperty("isActive") val isActive: Boolean? = null,
    @JsonProperty("referer") val referer: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroResolution(
    @JsonProperty("width") val width: Int? = null,
    @JsonProperty("height") val height: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroSkipTime(
    @JsonProperty("start") val start: Double? = null,
    @JsonProperty("end") val end: Double? = null
)

fun qualityFromString(quality: String?): Int {
    return when {
        quality == null -> -1
        quality.contains("2160") || quality.contains("4K", true) -> 2160
        quality.contains("1080") -> 1080
        quality.contains("720") -> 720
        quality.contains("480") -> 480
        quality.contains("360") -> 360
        else -> -1
    }
}
