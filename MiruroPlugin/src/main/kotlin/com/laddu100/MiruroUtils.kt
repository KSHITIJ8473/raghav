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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

// ─── Pipe Encoding/Decoding ─────────────────────────────────────────────────

fun encodePipeRequest(payload: Map<String, Any?>): String {
    val json = payload.toJson()
    val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return encoded
}

fun decodePipeResponse(responseBody: String): String {
    val trimmed = responseBody.trim()
    // Pad base64 if needed
    val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
    val compressed = Base64.decode(padded, Base64.URL_SAFE)
    // Gzip decompress
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
// Root cause of the "coming soon" issue:
//   Miruro's /api/secure/pipe endpoint on all streaming domains (.ru, .tv,
//   .to, .bz) is now behind Cloudflare WAF. OkHttp (app.get) cannot pass the
//   JS challenge, so every pipe request returns 403 + HTML challenge page.
//   The old code caught the exception silently → zero episodes → Cloudstream
//   showed "coming soon".
//
// Fix:
//   1. Try OkHttp directly (fast path — works when CF isn't challenging).
//   2. On CF block, warm up cf_clearance via WebView (which CAN solve the JS
//      challenge), cache the cookie, and retry OkHttp with it.
//   3. If the working domain stays blocked, rotate through all mirror domains.
//   4. As a last resort, fetch the pipe response body directly through WebView.

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

    /** Detect Cloudflare challenge / WAF block in a response. */
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
        // Also catch 200 responses that are actually CF interstitials
        val lower = text.lowercase()
        if (lower.contains("just a moment") && lower.contains("challenge")) return true
        return false
    }

    /**
     * Warm up Cloudflare cookies by loading the main page in a WebView.
     * The WebView executes the CF JS challenge and obtains cf_clearance.
     * Returns the full cookie string for the domain (e.g. "cf_clearance=...; __cf_bm=...").
     */
    suspend fun warmUp(context: Context?, baseUrl: String): String {
        if (context == null) return ""

        // Return cached cookies if we already have them
        cookieCache[baseUrl]?.let { return it }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                var webView: WebView? = null
                var pageLoadCount = 0

                fun finish(cookieValue: String) {
                    if (done.compareAndSet(false, true)) {
                        try { webView?.destroy() } catch (_: Exception) {}
                        if (cookieValue.isNotEmpty()) {
                            cookieCache[baseUrl] = cookieValue
                        }
                        cont.resume(cookieValue)
                    }
                }

                try {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.userAgentString = CF_USER_AGENT

                        // Accept cookies so CookieManager stores cf_clearance
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                pageLoadCount++
                                // Cloudflare challenge causes one or more page loads before
                                // the real page appears. Wait for things to settle, then grab cookies.
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (done.get()) return@postDelayed
                                    val cookies =
                                        CookieManager.getInstance().getCookie(baseUrl) ?: ""
                                    // If we have cf_clearance, we're done.
                                    // Also accept after 2 page loads (some CF flows don't set
                                    // cf_clearance but still let subsequent XHR through).
                                    if (cookies.contains("cf_clearance") || pageLoadCount >= 2) {
                                        finish(cookies)
                                    }
                                }, 2500)
                            }
                        }
                    }

                    webView?.loadUrl(baseUrl)

                    // Overall timeout
                    Handler(Looper.getMainLooper()).postDelayed({
                        val cookies = CookieManager.getInstance().getCookie(baseUrl) ?: ""
                        finish(cookies)
                    }, 20000)
                } catch (e: Exception) {
                    finish("")
                }
            }
        }
    }

    /**
     * Last-resort: fetch the pipe URL directly in a WebView and read the
     * response body (the gzip+base64 text) via document.body.innerText.
     * This handles the case where even with cf_clearance, OkHttp is blocked
     * but the WebView (full browser engine) is not.
     */
    suspend fun fetchViaWebView(
        context: Context?,
        url: String,
        referer: String
    ): String? {
        if (context == null) return null

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                var webView: WebView? = null
                var attempts = 0

                fun finish(result: String?) {
                    if (done.compareAndSet(false, true)) {
                        try { webView?.destroy() } catch (_: Exception) {}
                        cont.resume(result)
                    }
                }

                try {
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = CF_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                attempts++
                                // Wait for CF challenge to resolve and the actual
                                // pipe response (plain text) to appear.
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (done.get()) return@postDelayed
                                    view?.evaluateJavascript(
                                        """(function(){
                                            var t = (document.body && document.body.innerText) ||
                                                   (document.documentElement && document.documentElement.textContent) || '';
                                            return t;
                                        })()"""
                                    ) { result ->
                                        if (done.get()) return@evaluateJavascript
                                        val text = result
                                            ?.trim()
                                            ?.removeSurrounding("\"")
                                            ?.replace("\\n", "\n")
                                            ?.replace("\\\"", "\"") ?: ""
                                        // The pipe response is a long base64 string (no HTML tags).
                                        // The CF challenge page is HTML whose innerText is short
                                        // English text ("Just a moment...", etc).
                                        if (text.length > 100 &&
                                            !text.lowercase().contains("cloudflare") &&
                                            !text.lowercase().contains("moment") &&
                                            !text.lowercase().contains("blocked") &&
                                            !text.lowercase().contains("enable cookies")) {
                                            // Try to verify it's valid by attempting a decode
                                            try {
                                                val padded = text + "=".repeat((4 - text.length % 4) % 4)
                                                val compressed = Base64.decode(padded, Base64.URL_SAFE)
                                                // If gzip header check passes, it's likely our response
                                                if (compressed.size > 2 &&
                                                    (compressed[0] == 0x1f.toByte() && compressed[1] == 0x8b.toByte())) {
                                                    finish(text)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }, if (attempts == 1) 4000 else 2000)
                            }
                        }
                    }

                    val headers = HashMap<String, String>()
                    headers["Referer"] = referer
                    webView?.loadUrl(url, headers)

                    Handler(Looper.getMainLooper()).postDelayed({
                        finish(null)
                    }, 30000)
                } catch (e: Exception) {
                    finish(null)
                }
            }
        }
    }
}

// ─── Pipe Request (Cloudflare-aware, multi-domain) ──────────────────────────

/**
 * Makes a Miruro pipe API request with automatic Cloudflare bypass and
 * domain rotation. Tries the working domain first, then all others.
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

    // Build domain try-order: working domain first, then the rest
    val working = MiruroCloudflare.getWorkingDomain()
    val domainOrder = listOf(working) + MIRURO_DOMAINS.filter { it != working }

    var lastError: Exception? = null
    for (domain in domainOrder) {
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
    val baseHeaders = mutableMapOf(
        "User-Agent" to CF_USER_AGENT,
        "Referer" to "$domain/",
        "Origin" to domain,
        "Accept" to "*/*"
    )

    // ── Attempt 1: OkHttp with cached cookies (if any) ──
    MiruroCloudflare.getCookies(domain)?.let { baseHeaders["Cookie"] = it }

    val response1 = try {
        app.get(pipeUrl, headers = baseHeaders, timeout = 30)
    } catch (e: Exception) {
        null
    }

    if (response1 != null && response1.code == 200) {
        val body = response1.text
        if (!MiruroCloudflare.isCloudflareBlock(body, 200)) {
            // Verify it's actually decodable (not HTML junk)
            try {
                return decodePipeResponse(body)
            } catch (e: Exception) {
                // Fall through to CF handling
            }
        }
    }

    // ── Attempt 2: Warm up CF cookies via WebView, then retry OkHttp ──
    val cookies = MiruroCloudflare.warmUp(Miruro.context, domain)
    if (cookies.isNotEmpty()) {
        baseHeaders["Cookie"] = cookies
        val response2 = try {
            app.get(pipeUrl, headers = baseHeaders, timeout = 30)
        } catch (e: Exception) {
            null
        }
        if (response2 != null && response2.code == 200) {
            val body = response2.text
            if (!MiruroCloudflare.isCloudflareBlock(body, 200)) {
                try {
                    return decodePipeResponse(body)
                } catch (e: Exception) {}
            }
        }
    }

    // ── Attempt 3: Fetch pipe response directly via WebView ──
    val webBody = MiruroCloudflare.fetchViaWebView(Miruro.context, pipeUrl, "$domain/")
    if (webBody != null && webBody.length > 100) {
        try {
            return decodePipeResponse(webBody)
        } catch (e: Exception) {}
    }

    throw Exception("Cloudflare block on $domain for /$path")
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

// AniList response models
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

// Quality helper
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
