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
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume

// ─── Pipe Encoding/Decoding ─────────────────────────────────────────────────

fun encodePipeRequest(payload: Map<String, Any?>): String {
    val json = payload.toJson()
    return Base64.encodeToString(
        json.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

/**
 * Decode a base64url + gzip response (old format, no XOR).
 */
fun decodePipeResponse(responseBody: String): String {
    val trimmed = responseBody.trim()
    val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
    val compressed = Base64.decode(padded, Base64.URL_SAFE)
    return decompress(compressed)
}

/**
 * Decompress data that may be gzip, zlib, or raw deflate.
 * Mirrors the JS Kr() function: checks magic bytes to determine format.
 */
private fun decompress(data: ByteArray): String {
    // gzip: magic bytes 1f 8b 08
    if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
        val bais = ByteArrayInputStream(data)
        val gzis = GZIPInputStream(bais)
        return gzis.use { it.readBytes().toString(Charsets.UTF_8) }
    }
    // zlib or raw deflate: use Inflater with nowrap
    // zlib header: first byte & 0x0f == 0x08, first byte >> 4 <= 7, (byte0<<8|byte1) % 31 == 0
    val isZlib = data.size > 1 &&
        (data[0].toInt() and 0x0f) == 0x08 &&
        (data[0].toInt() shr 4) <= 7 &&
        (((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)) % 31 == 0

    val inflater = if (isZlib) Inflater() else Inflater(true) // true = raw deflate (no zlib header)
    val bais = ByteArrayInputStream(data)
    val iis = InflaterInputStream(bais, inflater)
    return iis.use { it.readBytes().toString(Charsets.UTF_8) }
}

private fun gunzip(data: ByteArray): String = decompress(data)

// ─── XOR obfuscation key (from VITE_PIPE_OBF_KEY in env2.js) ────────────────
// JS: Ga = new Uint8Array("71951034f8fbcf53d89db52ceb3dc22c".match(/.{2}/g).map(e => parseInt(e, 16)))
private val XOR_KEY = byteArrayOf(
    0x71, 0x95.toByte(), 0x10, 0x34, 0xF8.toByte(), 0xFB.toByte(), 0xCF.toByte(), 0x53,
    0xD8.toByte(), 0x9D.toByte(), 0xB5.toByte(), 0x2C, 0xEB.toByte(), 0x3D, 0xC2.toByte(), 0x2C
)

private fun xorDecrypt(data: ByteArray): ByteArray {
    val result = ByteArray(data.size)
    for (i in data.indices) {
        result[i] = (data[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
    }
    return result
}

/**
 * Decode pipe response based on x-obfuscated header value.
 *
 * - No header / null  → plain JSON text, return as-is
 * - Header present, value != "2" → base64url + gzip
 * - Header === "2" → base64url + XOR + gzip
 */
fun decodePipeResponseWithHeader(responseBody: String, obfuscatedHeader: String?): String {
    if (obfuscatedHeader == null) {
        // Plain JSON response
        return responseBody.trim()
    }

    val trimmed = responseBody.trim()
    val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
    var decoded = Base64.decode(padded, Base64.URL_SAFE)

    if (obfuscatedHeader == "2") {
        decoded = xorDecrypt(decoded)
    }

    return decompress(decoded)
}

/**
 * Auto-detect response format (for WebView fallback where we can't read headers).
 * Tries: plain JSON → base64url+decompress → base64url+XOR+decompress
 */
fun decodePipeResponseAuto(responseBody: String): String {
    val trimmed = responseBody.trim()

    // 1. Plain JSON?
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        return trimmed
    }

    val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
    val decoded = try {
        Base64.decode(padded, Base64.URL_SAFE)
    } catch (_: Exception) {
        throw Exception("Cannot base64-decode pipe response")
    }

    // 2. base64url + decompress (gzip/zlib/deflate)?
    try {
        return decompress(decoded)
    } catch (_: Exception) {}

    // 3. base64url + XOR + decompress?
    try {
        val xored = xorDecrypt(decoded)
        return decompress(xored)
    } catch (_: Exception) {}

    throw Exception("Cannot decode pipe response (tried JSON, decompress, XOR+decompress)")
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

val MIRURO_DOMAINS = listOf(
    "https://www.miruro.ru",
    "https://www.miruro.tv",
    "https://www.miruro.to",
    "https://www.miruro.bz"
)

const val CF_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * Cloudflare bypass via WebView + JS fetch() injection.
 */
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
     * Fetch the pipe API response via WebView.
     * Loads homepage → waits for CF solve → injects fetch() → returns response text.
     */
    suspend fun fetchPipeViaWebView(
        context: Context?,
        domain: String,
        pipeUrl: String
    ): String? {
        if (context == null) return null

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                var webView: WebView? = null
                val fetchInjected = AtomicBoolean(false)
                val loadCount = intArrayOf(0)

                fun finish(result: String?) {
                    if (done.compareAndSet(false, true)) {
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

                fun injectFetch(view: WebView?) {
                    if (done.get() || !fetchInjected.compareAndSet(false, true)) return
                    val relativeUrl = pipeUrl.substringAfter(domain)

                    val js = """
                        (function() {
                            window.__pipe_result = null;
                            window.__pipe_error = null;
                            try {
                                fetch("$relativeUrl", {
                                    method: "GET",
                                    credentials: "include",
                                    headers: { "Accept": "*/*" }
                                }).then(function(r) {
                                    return r.text();
                                }).then(function(text) {
                                    window.__pipe_result = text;
                                }).catch(function(e) {
                                    window.__pipe_error = e.message;
                                });
                            } catch(e) {
                                window.__pipe_error = e.message;
                            }
                        })();
                    """.trimIndent()

                    view?.evaluateJavascript(js) {
                        println("Miruro: fetch() injected, polling for result...")
                    }

                    // Poll for result every 500ms (up to 15s)
                    for (i in 1..30) {
                        val delay = (i * 500).toLong()
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (done.get()) return@postDelayed
                            view?.evaluateJavascript(
                                "(function(){ if(window.__pipe_result !== null) return window.__pipe_result; if(window.__pipe_error) return 'ERROR:' + window.__pipe_error; return null; })()"
                            ) { result ->
                                if (done.get()) return@evaluateJavascript
                                if (result != null && result != "null") {
                                    val text = result.trim().removeSurrounding("\"")
                                        .replace("\\n", "\n")
                                        .replace("\\\"", "\"")
                                        .replace("\\\\", "\\")
                                    if (text.startsWith("ERROR:")) {
                                        println("Miruro: fetch() error: ${text.substring(6)}")
                                        finish(null)
                                    } else if (text.isNotEmpty() && text.length > 10) {
                                        println("Miruro: fetch() got ${text.length} chars")
                                        finish(text)
                                    }
                                }
                            }
                        }, delay)
                    }
                }

                fun checkAndInject(view: WebView?) {
                    if (done.get() || fetchInjected.get()) return
                    view?.evaluateJavascript("document.title") { titleResult ->
                        if (done.get() || fetchInjected.get()) return@evaluateJavascript
                        val title = titleResult?.trim()?.removeSurrounding("\"") ?: ""
                        println("Miruro: checkAndInject title='$title' loadCount=${loadCount[0]}")

                        val isChallenge = title.lowercase().contains("just a moment") ||
                                          title.lowercase().contains("attention required") ||
                                          title.lowercase().contains("cloudflare") ||
                                          title.lowercase().contains("blocked") ||
                                          title.isBlank()

                        if (!isChallenge) {
                            println("Miruro: CF solved! Injecting fetch()...")
                            injectFetch(view)
                        }
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
                                loadCount[0]++
                                println("Miruro: onPageFinished #${loadCount[0]}: $pageUrl")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    checkAndInject(view)
                                }, 500)
                            }
                        }
                    }

                    println("Miruro: Loading homepage: $domain")
                    webView?.loadUrl(domain)

                    // Periodic challenge-solved check every 1s (CF can take 5-10s)
                    for (i in 1..12) {
                        val delay = (i * 1000).toLong()
                        Handler(Looper.getMainLooper()).postDelayed({
                            checkAndInject(webView)
                        }, delay)
                    }

                    // Overall timeout: 30s
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish(null)
                    }, 30000)
                } catch (e: Exception) {
                    println("Miruro: WebView error: ${e.message}")
                    finish(null)
                }
            }
        }
    }
}

// ─── Pipe Request ───────────────────────────────────────────────────────────

suspend fun miruroPipeRequest(path: String, query: Map<String, Any>): String {
    val enrichedQuery = query.toMutableMap()
    enrichedQuery["live"] = "true"
    enrichedQuery["_t"] = (System.currentTimeMillis() / (600 * 1000)) * (600 * 1000)

    val payload = mapOf(
        "path" to path,
        "method" to "GET",
        "query" to enrichedQuery,
        "body" to null
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

    // ── Step 1: OkHttp direct (fast path with cached cookies) ──
    try {
        val response = app.get(pipeUrl, headers = headers, timeout = 15)
        if (response.code == 200) {
            val body = response.text
            if (!MiruroCloudflare.isCloudflareBlock(body, 200)) {
                val obfHeader = response.headers["x-obfuscated"]
                try {
                    return decodePipeResponseWithHeader(body, obfHeader)
                } catch (_: Exception) {
                    try { return decodePipeResponseAuto(body) } catch (_: Exception) {}
                }
            }
        }
        println("Miruro: OkHttp direct failed (HTTP ${response.code}), falling back to WebView")
    } catch (e: Exception) {
        println("Miruro: OkHttp direct exception: ${e.message}")
    }

    // ── Step 2: WebView fetch() injection (reliable CF bypass) ──
    val webBody = MiruroCloudflare.fetchPipeViaWebView(
        Miruro.context, domain, pipeUrl
    )
    if (webBody != null && webBody.isNotEmpty()) {
        println("Miruro: WebView returned ${webBody.length} chars, decoding...")
        try {
            return decodePipeResponseAuto(webBody)
        } catch (e: Exception) {
            println("Miruro: Failed to decode WebView response - ${e.message}")
            println("Miruro: First 100 chars: ${webBody.take(100)}")
        }
    } else {
        println("Miruro: WebView returned null/empty")
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
