package com.justplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveInfo(
    val teamA: String? = null,
    val teamB: String? = null,
    val teamAFlag: String? = null,
    val teamBFlag: String? = null,
    val eventCat: String? = null,
    val eventName: String? = null,
    val eventLogo: String? = null,
    val isHot: String? = null,
    val eventType: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveFormat(
    val title: String? = null,
    val webLink: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveEvent(
    val id: Int? = null,
    val title: String? = null,
    val image: String? = null,
    val slug: String? = null,
    val cat: String? = null,
    val eventInfo: LiveInfo? = null,
    val publish: Int? = null,
    val formats: List<LiveFormat>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveLoadData(
    val eventId: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val slug: String? = null,
    val formats: List<LiveFormat> = emptyList(),
    val info: LiveInfo? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveStreamUrl(
    val api: String? = null,
    val id: Int? = null,
    val link: String? = null,
    val title: String? = null,
    val type: String? = null,
    val webLink: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveStreamResponse(
    val streamUrls: List<LiveStreamUrl>? = null,
)

private object LiveCrypto {
    private fun hexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    private val keys: List<Pair<SecretKeySpec, IvParameterSpec>> by lazy {
        listOf(
            "3368487a78594167534749382f68616d:557143766b766a656345497a38343256",
            "4d7165594743543441594b6f484b7254:6f484b725451755078387a6c386f4a2b",
        ).map { secret ->
            val parts = secret.split(":")
            SecretKeySpec(hexBytes(parts[0]), "AES") to IvParameterSpec(hexBytes(parts[1]))
        }
    }

    // the event and channel lists arrive AES encrypted, text that does not
    // come out as json means the wrong key was tried
    fun decrypt(payload: String): String? {
        return try {
            val ciphertext = base64DecodeArray(
                payload.trim()
                    .replace("\n", "")
                    .replace("\r", "")
                    .replace(" ", "")
                    .replace("\t", "")
            )
            for ((key, iv) in keys) {
                val text = tryDecrypt(ciphertext, key, iv) ?: continue
                return text
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryDecrypt(ciphertext: ByteArray, key: SecretKeySpec, iv: IvParameterSpec): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val text = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            if (text.startsWith("{") || text.startsWith("[") || text.contains("http", ignoreCase = true)) {
                text
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

// the backend host rotates whenever the current one gets blocked, so the live
// one is read from remote config and cached here instead of hitting firebase
// on every home page load
private class RemoteDomain(
    private val ttlMs: Long = 1_800_000L,
    private val fallbackTtlMs: Long = 120_000L,
    private val minRetryMs: Long = 60_000L,
) {
    private val mutex = Mutex()

    @Volatile
    var value: String? = null
        private set

    @Volatile
    private var expiresAt = 0L

    @Volatile
    private var lastAttempt = 0L

    val peek: String?
        get() = value

    fun invalidate() {
        expiresAt = 0L
    }

    suspend fun get(fetch: suspend () -> String?, fallback: () -> String): String {
        peek?.let { cached ->
            if (System.currentTimeMillis() < expiresAt) return cached
        }
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val current = value
            if (current != null && now < expiresAt && now - lastAttempt < minRetryMs) {
                return@withLock current
            }
            lastAttempt = now
            val fetched = fetch()
            if (!fetched.isNullOrBlank()) {
                value = fetched
                expiresAt = now + ttlMs
                return@withLock fetched
            }
            // keep serving the stale host for a short while rather than
            // hammering firebase with a broken network
            if (current == null) {
                val result = fallback()
                value = result
                expiresAt = now + fallbackTtlMs
                return@withLock result
            }
            current
        }
    }
}

// a dead mirror answers 4xx or drops the connection, invalidating the cached
// host here makes the next call re-read remote config
private class StaleDomainInterceptor(private val domain: RemoteDomain) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val host = chain.request().url.host
        val ours = domain.peek?.let {
            okhttp3.HttpUrl.Companion.run { it.toHttpUrlOrNull() }?.host?.equals(host, ignoreCase = true)
        } == true
        return try {
            val response = chain.proceed(chain.request())
            if (ours && response.code >= 400) domain.invalidate()
            response
        } catch (e: java.io.IOException) {
            if (ours) domain.invalidate()
            throw e
        }
    }
}

object LiveSports {
    private const val DEFAULT_BASE = "https://cfymarketscanjiostar80.top"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64. x64) AppleWebKit/537.36"

    var context: Context? = null

    private val domain = RemoteDomain()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(StaleDomainInterceptor(domain))
        .build()

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RemoteConfig(val entries: Map<String, String>? = null)

    // cric_api2 is the working endpoint most of the time, cric_api1 is the spare
    private suspend fun baseUrlFromFirebase(): String? {
        return try {
            withContext(Dispatchers.IO) {
                val body = """
                    {"appInstanceId":"${UUID.randomUUID().toString().replace("-", "")}","appInstanceIdToken":"","appId":"1:963020218535:android:47ec53252c64fb3c9c7b82","countryCode":"US","languageCode":"en-US","platformVersion":"30","timeZone":"UTC","appVersion":"5.0","appBuild":"50","packageName":"com.cricfy.tv","sdkVersion":"22.1.0","analyticsUserProperties":{}}
                """.trimIndent()
                val request = Request.Builder()
                    .url("https://firebaseremoteconfig.googleapis.com/v1/projects/963020218535/namespaces/firebase:fetch")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("X-Android-Package", "com.cricfy.tv")
                    .header("X-Goog-Api-Key", "AIzaSyAh9jkEU0E_UYxH0m_BKAt-uUSTiTPqhb8")
                    .header("X-Google-GFE-Can-Retry", "yes")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val entries = parseJson<RemoteConfig>(response.body.string()).entries
                    (entries?.get("cric_api2") ?: entries?.get("cric_api1"))?.trimEnd('/')
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun baseUrl(): String =
        domain.get(fetch = { baseUrlFromFirebase() }, fallback = { DEFAULT_BASE })

    suspend fun fetchEvents(): List<LiveEvent> {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("${baseUrl()}/categories/live-events.txt")
                    .header("User-Agent", WEB_UA)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val text = LiveCrypto.decrypt(response.body.string().trim())
                        ?: return@withContext emptyList()
                    parseJson<List<LiveEvent>>(text).filter { it.publish == 1 && !it.slug.isNullOrBlank() }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchStreams(slug: String): LiveStreamResponse? {
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("${baseUrl()}/channels/${slug.lowercase()}.txt")
                    .header("User-Agent", WEB_UA)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val text = LiveCrypto.decrypt(response.body.string().trim())
                        ?: return@withContext null
                    parseJson<LiveStreamResponse>(text)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startTimeOf(event: LiveEvent): Long? = try {
        event.eventInfo?.startTime?.let {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(it)?.time
        }
    } catch (_: Exception) {
        null
    }

    private fun endTimeOf(event: LiveEvent): Long? = try {
        event.eventInfo?.endTime?.let {
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(it)?.time
        }
    } catch (_: Exception) {
        null
    }

    private fun isLive(event: LiveEvent): Boolean {
        val start = startTimeOf(event) ?: return false
        val end = endTimeOf(event)
        val now = System.currentTimeMillis()
        return now >= start && (end == null || now < end)
    }

    private fun isEnded(event: LiveEvent): Boolean =
        endTimeOf(event)?.let { System.currentTimeMillis() >= it } == true

    private fun statusTag(event: LiveEvent): String = when {
        isEnded(event) -> "ENDED"
        isLive(event) -> "LIVE"
        startTimeOf(event) != null -> "UPCOMING"
        else -> ""
    }

    private fun displayTitle(event: LiveEvent): String {
        val info = event.eventInfo
        val a = info?.teamA?.takeIf { it.isNotBlank() } ?: return event.title ?: ""
        val b = info?.teamB?.takeIf { it.isNotBlank() } ?: return event.title ?: ""
        return if (a == b) a else "$a vs $b"
    }

    // wide match card with both crests, start time and state, same card
    // service the live tv plugin renders its rows with
    private fun matchCardUrl(event: LiveEvent): String {
        val info = event.eventInfo
        val title = java.net.URLEncoder.encode(info?.eventName ?: event.title ?: "", "UTF-8")
        val teamA = java.net.URLEncoder.encode(info?.teamA ?: "Team A", "UTF-8")
        val teamB = java.net.URLEncoder.encode(info?.teamB ?: "Team B", "UTF-8")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val time = try {
            info?.startTime?.let { start ->
                val parsed = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(start)
                val display = SimpleDateFormat("MMM dd, yyyy hh:mm a 'UTC'", Locale.US)
                display.timeZone = TimeZone.getTimeZone("UTC")
                parsed?.let { java.net.URLEncoder.encode(display.format(it), "UTF-8") } ?: ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }
        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$title")
            append("&teamA=$teamA")
            append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=${isLive(event)}")
            append("&isEnded=${isEnded(event)}")
        }
    }

    private fun searchResponse(api: MainAPI, event: LiveEvent): SearchResponse {
        val name = displayTitle(event)
        val status = statusTag(event)
        val poster = matchCardUrl(event)
        val data = LiveLoadData(
            eventId = event.id,
            title = name,
            poster = poster,
            slug = event.slug,
            formats = event.formats ?: emptyList(),
            info = event.eventInfo,
        )
        return api.newLiveSearchResponse(
            if (status.isNotBlank()) "$status $name" else name,
            data.toJson(),
            TvType.Live
        ) { this.posterUrl = poster }
    }

    suspend fun homePage(api: MainAPI): HomePageResponse {
        val events = fetchEvents()
        if (events.isEmpty()) {
            return newHomePageResponse("Live Sports", emptyList<SearchResponse>(), hasNext = false)
        }
        val lists = events
            .groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }
            .map { (category, categoryEvents) ->
                val items = categoryEvents
                    .sortedWith(
                        compareBy<LiveEvent> { event ->
                            when {
                                isEnded(event) -> 2
                                isLive(event) -> 0
                                startTimeOf(event) != null -> 1
                                else -> 3
                            }
                        }.thenBy { startTimeOf(it) ?: Long.MAX_VALUE }
                    )
                    .map { event -> searchResponse(api, event) }
                HomePageList(category, items, isHorizontalImages = true)
            }
            .sortedBy { list ->
                when {
                    list.name.contains("Cricket", ignoreCase = true) -> 0
                    list.name.contains("Football", ignoreCase = true) -> 1
                    list.name.contains("Basketball", ignoreCase = true) -> 2
                    else -> 10
                }
            }
        return newHomePageResponse(lists, hasNext = false)
    }

    suspend fun buildLoadResponse(api: MainAPI, data: LiveLoadData, url: String): LoadResponse {
        val plot = buildString {
            data.info?.let { info ->
                info.eventType?.let { append("Event: $it\n") }
                info.eventName?.let { append("Tournament: $it\n") }
                info.startTime?.let { start ->
                    try {
                        val parsed = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US).parse(start)
                        val display = SimpleDateFormat("MMM dd, yyyy HH:mm 'UTC'", Locale.US)
                        display.timeZone = TimeZone.getTimeZone("UTC")
                        if (parsed != null) append("Starts: ${display.format(parsed)}\n") else append("Starts: $start\n")
                    } catch (_: Exception) {
                        append("Starts: $start\n")
                    }
                }
            }
            append("\nAvailable servers: ${data.formats.size}")
        }
        return api.newLiveStreamLoadResponse(data.title ?: "", url, url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
    }

    // links can carry per stream headers after a pipe, url|Referer=..&Origin=..
    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = mutableMapOf<String, String>()
        if (!link.contains("|")) {
            return link to headers
        }
        val parts = link.split("|", limit = 2)
        if (parts.size < 2) {
            return parts[0] to headers
        }
        parts[1].split("&").forEach { pair ->
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                val name = when (kv[0].trim().lowercase()) {
                    "user-agent" -> "User-Agent"
                    "referer" -> "Referer"
                    "origin" -> "Origin"
                    "cookie" -> "Cookie"
                    else -> kv[0].trim()
                }
                headers[name] = kv[1].trim()
            }
        }
        return parts[0] to headers
    }

    private fun hexToBase64Url(hex: String): String {
        val bytes = hex.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun isDirectStream(url: String): Boolean =
        url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") ||
            url.contains(".ts") || url.contains(".mkv") || url.contains(".webm")

    suspend fun resolveLinks(
        data: LiveLoadData,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = data.slug ?: return false
        val streams = fetchStreams(slug)?.streamUrls ?: return false
        if (streams.isEmpty()) return false

        for (stream in streams) {
            val serverName = stream.title ?: "Server"
            val rawLink = stream.link ?: continue
            val (url, headers) = parseStreamLink(rawLink)
            if (url.isBlank()) continue
            val resolved = resolveEmbed(url) ?: continue
            try {
                if (stream.type == "7") {
                    emitDrmLink(serverName, resolved, stream.api, headers, callback)
                } else {
                    emitPlainLink(serverName, resolved, headers, callback)
                }
            } catch (_: Exception) {
            }
        }
        return true
    }

    // type 7 servers hand out clearkey protected dash, the kid and key ride
    // along in the api field as hex kid:hex key
    private suspend fun emitDrmLink(
        serverName: String,
        url: String,
        api: String?,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val drm = api?.split(":")
        if (drm == null || drm.size != 2) {
            callback.invoke(
                newExtractorLink("JustPlay", serverName, url, ExtractorLinkType.DASH) {
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) this.headers = headers
                }
            )
            return
        }
        val kid = hexToBase64Url(drm[0])
        val key = hexToBase64Url(drm[1])
        callback.invoke(
            newDrmExtractorLink("JustPlay", serverName, url, INFER_TYPE, CLEARKEY_UUID) {
                this.quality = Qualities.Unknown.value
                this.key = key
                this.kid = kid
                if (headers.isNotEmpty()) this.headers = headers
            }
        )
    }

    private suspend fun emitPlainLink(
        serverName: String,
        url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val linkType = when {
            url.contains(".mpd") -> ExtractorLinkType.DASH
            url.contains(".m3u") -> ExtractorLinkType.M3U8
            else -> INFER_TYPE
        }
        val finalHeaders = headers.toMutableMap()
        if (linkType == ExtractorLinkType.M3U8 && !finalHeaders.containsKey("User-Agent")) {
            finalHeaders["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        }
        callback.invoke(
            newExtractorLink("JustPlay", serverName, url, linkType) {
                this.quality = Qualities.Unknown.value
                if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
            }
        )
    }

    private suspend fun resolveEmbed(url: String): String? {
        if (isDirectStream(url)) return url
        return loadInWebView(url)
    }

    // some servers only expose the stream from inside their player page, run
    // it in a throwaway webview and grab the first media request it fires
    private suspend fun loadInWebView(embedUrl: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                val appContext = context
                if (appContext == null) {
                    continuation.resume(null)
                    return@suspendCoroutine
                }
                var captured = false
                var resumed = false

                // the link load can be cancelled while the page is still
                // loading, a late resume is dropped instead of thrown
                fun finishOnce(result: String?) {
                    if (resumed) return
                    resumed = true
                    try {
                        continuation.resume(result)
                    } catch (_: Exception) {
                    }
                }

                val webView = try {
                    WebView(appContext)
                } catch (_: Exception) {
                    finishOnce(null)
                    return@suspendCoroutine
                }
                webView.settings.javaScriptEnabled = true
                webView.settings.loadsImagesAutomatically = true
                webView.settings.domStorageEnabled = true
                webView.settings.allowContentAccess = true
                webView.settings.allowFileAccess = true
                webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webView.settings.mediaPlaybackRequiresUserGesture = false

                val bridge = object {
                    @JavascriptInterface
                    fun onStreamUrlFound(url: String) {
                        Handler(Looper.getMainLooper()).post {
                            if (!captured && url.isNotBlank()) {
                                captured = true
                                try {
                                    webView.destroy()
                                } catch (_: Exception) {
                                }
                                finishOnce(url)
                            }
                        }
                    }
                }
                webView.addJavascriptInterface(bridge, "StreamBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val requestUrl = request.url.toString()
                        if (isDirectStream(requestUrl) && !captured) {
                            captured = true
                            Handler(Looper.getMainLooper()).post {
                                try {
                                    webView.destroy()
                                } catch (_: Exception) {
                                }
                                finishOnce(requestUrl)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        super.onPageFinished(view, pageUrl)
                        if (captured) return
                        // player pages park the stream in a playbackURL var
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (captured) return@postDelayed
                            try {
                                view.evaluateJavascript(
                                    "(function(){if(typeof playbackURL!=='undefined'&&playbackURL){" +
                                        "window.StreamBridge.onStreamUrlFound(playbackURL);}})();",
                                    null
                                )
                            } catch (_: Exception) {
                            }
                        }, 500)
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!captured) {
                                try {
                                    webView.destroy()
                                } catch (_: Exception) {
                                }
                                finishOnce(null)
                            }
                        }, 3000)
                    }
                }

                webView.webChromeClient = WebChromeClient()
                webView.loadUrl(embedUrl)

                // hard stop so a hanging page cannot eat the whole load
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!captured) {
                        try {
                            webView.destroy()
                        } catch (_: Exception) {
                        }
                        finishOnce(null)
                    }
                }, 30_000)
            }
        }
    }
}
