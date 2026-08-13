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
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume

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
    return decompress(compressed)
}

private fun decompress(data: ByteArray): String {
    if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
        val bais = ByteArrayInputStream(data)
        val gzis = GZIPInputStream(bais)
        return gzis.use { it.readBytes().toString(Charsets.UTF_8) }
    }
    val isZlib = data.size > 1 &&
        (data[0].toInt() and 0x0f) == 0x08 &&
        (data[0].toInt() shr 4) <= 7 &&
        (((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)) % 31 == 0

    val inflater = if (isZlib) Inflater() else Inflater(true)
    val bais = ByteArrayInputStream(data)
    val iis = InflaterInputStream(bais, inflater)
    return iis.use { it.readBytes().toString(Charsets.UTF_8) }
}

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

fun decodePipeResponseWithHeader(responseBody: String, obfuscatedHeader: String?): String {
    if (obfuscatedHeader == null) {
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

fun decodePipeResponseAuto(responseBody: String): String {
    val trimmed = responseBody.trim()

    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        return trimmed
    }

    val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
    val decoded = try {
        Base64.decode(padded, Base64.URL_SAFE)
    } catch (_: Exception) {
        throw Exception("Cannot base64-decode pipe response")
    }

    try {
        return decompress(decoded)
    } catch (_: Exception) {}

    try {
        val xored = xorDecrypt(decoded)
        return decompress(xored)
    } catch (_: Exception) {}

    throw Exception("Cannot decode pipe response")
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

const val MIRURO_DEFAULT_DOMAIN = "https://www.miruro.to"

const val CF_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

object MiruroCloudflare {
    private const val TAG = "MiruroCF"
    private val workingDomain = AtomicReference<String?>(MIRURO_DEFAULT_DOMAIN)

    @Volatile private var sessionWebView: WebView? = null
    @Volatile private var sessionDomain: String? = null
    @Volatile private var sessionReady: Boolean = false
    @Volatile private var sessionReadyTime: Long = 0L
    private val SESSION_TTL = 5 * 60 * 1000L

    private val fetchMutex = Mutex()
    private val warmupMutex = Mutex()

    fun getWorkingDomain(): String = workingDomain.get() ?: MIRURO_DEFAULT_DOMAIN

    fun setWorkingDomain(d: String) {
        val old = workingDomain.get()
        workingDomain.set(d)
        if (old != null && old != d) {
            destroySession()
        }
    }

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
        return lower.contains("just a moment") && lower.contains("challenge")
    }

    private suspend fun ensureSession(context: Context, domain: String) {
        val now = System.currentTimeMillis()
        val current = sessionWebView
        val currentDomain = sessionDomain
        val isStale = current == null ||
                      currentDomain != domain ||
                      !sessionReady ||
                      (now - sessionReadyTime) > SESSION_TTL

        if (!isStale && current != null) {
            try {
                val alive = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<String?> { cont ->
                        current.evaluateJavascript("document.title") { res ->
                            cont.resume(res)
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (cont.isActive) cont.resume(null)
                        }, 2000)
                    }
                }
                if (alive != null && alive != "null" && alive.isNotBlank()) {
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "liveness check failed: ${e.message}")
            }
        }

        warmupMutex.withLock {
            if (sessionWebView != null && sessionDomain == domain && sessionReady &&
                (System.currentTimeMillis() - sessionReadyTime) <= SESSION_TTL) {
                return
            }
            destroySession()
            warmupSession(context, domain)
        }
    }

    private suspend fun warmupSession(context: Context, domain: String) {
        Log.d(TAG, "warming up: $domain")
        val start = System.currentTimeMillis()
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val done = AtomicBoolean(false)
                    val solved = AtomicBoolean(false)
                    var webView: WebView? = null

                    fun finish(success: Boolean) {
                        if (done.compareAndSet(false, true)) {
                            sessionReady = success
                            if (success) {
                                sessionReadyTime = System.currentTimeMillis()
                                Log.d(TAG, "warmup done in ${System.currentTimeMillis() - start}ms")
                            } else {
                                Log.d(TAG, "warmup failed after ${System.currentTimeMillis() - start}ms")
                                try { webView?.destroy() } catch (_: Exception) {}
                                sessionWebView = null
                            }
                            cont.resume(Unit)
                        }
                    }

                    fun checkSolved(view: WebView?) {
                        if (done.get() || solved.get()) return
                        view?.evaluateJavascript("document.title") { titleResult ->
                            if (done.get() || solved.get()) return@evaluateJavascript
                            val title = titleResult?.trim()?.removeSurrounding("\"") ?: ""
                            val isChallenge = title.lowercase().contains("just a moment") ||
                                              title.lowercase().contains("attention required") ||
                                              title.lowercase().contains("cloudflare") ||
                                              title.lowercase().contains("blocked") ||
                                              title.isBlank()
                            if (!isChallenge) {
                                if (solved.compareAndSet(false, true)) {
                                    sessionWebView = view
                                    sessionDomain = domain
                                    try {
                                        CookieManager.getInstance().getCookie(domain)
                                    } catch (_: Exception) {}
                                    finish(true)
                                }
                            }
                        }
                    }

                    try {
                        webView = WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = CF_USER_AGENT
                            CookieManager.getInstance().setAcceptCookie(true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    super.onPageFinished(view, pageUrl)
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        checkSolved(view)
                                    }, 500)
                                }
                            }
                        }
                        webView?.loadUrl(domain)

                        val cfHandler = Handler(Looper.getMainLooper())
                        val cfRunnable = object : Runnable {
                            var checkCount = 0
                            override fun run() {
                                if (done.get() || checkCount >= 25) return
                                checkCount++
                                checkSolved(webView)
                                if (!done.get()) cfHandler.postDelayed(this, 1000)
                            }
                        }
                        cfHandler.postDelayed(cfRunnable, 1000)

                        Handler(Looper.getMainLooper()).postDelayed({
                            finish(false)
                        }, 25000)
                    } catch (e: Exception) {
                        Log.d(TAG, "warmup exception: ${e.message}")
                        finish(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "warmup outer exception: ${e.message}")
            sessionReady = false
        }
    }

    private fun destroySession() {
        try {
            sessionWebView?.let { wv ->
                Handler(Looper.getMainLooper()).post {
                    try { wv.destroy() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        sessionWebView = null
        sessionDomain = null
        sessionReady = false
    }

    private suspend fun fetchViaSession(pipeUrl: String, domain: String): String? {
        val wv = sessionWebView ?: return null
        val relativeUrl = pipeUrl.substringAfter(domain)

        return fetchMutex.withLock {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<String?> { cont ->
                    val done = AtomicBoolean(false)

                    fun finish(result: String?) {
                        if (done.compareAndSet(false, true)) {
                            cont.resume(result)
                        }
                    }

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

                    try {
                        wv.evaluateJavascript(js) {}
                    } catch (e: Exception) {
                        Log.d(TAG, "inject failed: ${e.message}")
                        finish(null)
                        return@suspendCancellableCoroutine
                    }

                    val pollHandler = Handler(Looper.getMainLooper())
                    val pollRunnable = object : Runnable {
                        var pollCount = 0
                        override fun run() {
                            if (done.get() || pollCount >= 50) {
                                if (!done.get()) finish(null)
                                return
                            }
                            pollCount++
                            try {
                                wv.evaluateJavascript(
                                    "(function(){ if(window.__pipe_result !== null) return window.__pipe_result; if(window.__pipe_error) return 'ERROR:' + window.__pipe_error; return null; })()"
                                ) { result ->
                                    if (done.get()) return@evaluateJavascript
                                    if (result != null && result != "null") {
                                        val text = result.trim().removeSurrounding("\"")
                                            .replace("\\n", "\n")
                                            .replace("\\\"", "\"")
                                            .replace("\\\\", "\\")
                                        if (text.startsWith("ERROR:")) {
                                            finish(null)
                                        } else if (text.isNotEmpty() && text.length > 10) {
                                            finish(text)
                                        } else {
                                            pollHandler.postDelayed(this, 300)
                                        }
                                    } else {
                                        pollHandler.postDelayed(this, 300)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "poll failed: ${e.message}")
                                finish(null)
                            }
                        }
                    }
                    pollHandler.postDelayed(pollRunnable, 300)

                    Handler(Looper.getMainLooper()).postDelayed({
                        finish(null)
                    }, 15000)
                }
            }
        }
    }

    suspend fun fetchPipe(context: Context?, domain: String, pipeUrl: String): String? {
        if (context == null) return null
        try {
            ensureSession(context, domain)
        } catch (e: Exception) {
            Log.d(TAG, "ensureSession failed: ${e.message}")
            return null
        }
        if (!sessionReady) return null

        val result = fetchViaSession(pipeUrl, domain)
        if (result != null && result.isNotEmpty()) {
            return result
        }

        destroySession()
        try {
            ensureSession(context, domain)
            if (sessionReady) {
                return fetchViaSession(pipeUrl, domain)
            }
        } catch (e: Exception) {
            Log.d(TAG, "retry failed: ${e.message}")
        }
        return null
    }

    fun resetSession() {
        destroySession()
    }
}

suspend fun miruroPipeRequest(path: String, query: Map<String, Any>): String {
    val enrichedQuery = query.toMutableMap()
    enrichedQuery["live"] = "true"
    enrichedQuery["_t"] = System.currentTimeMillis()

    val payload = mapOf(
        "path" to path,
        "method" to "GET",
        "query" to enrichedQuery,
        "body" to null
    )
    val encoded = encodePipeRequest(payload)

    val domain = MiruroCloudflare.getWorkingDomain()
    val pipeUrl = "$domain/api/secure/pipe?e=$encoded"

    val webBody = MiruroCloudflare.fetchPipe(Miruro.context, domain, pipeUrl)
    if (webBody != null && webBody.isNotEmpty()) {
        return decodePipeResponseAuto(webBody)
    }

    throw Exception("Failed on $domain for /$path")
}

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
