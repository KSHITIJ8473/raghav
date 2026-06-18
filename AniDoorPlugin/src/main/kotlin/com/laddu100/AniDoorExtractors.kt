package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink

// ── MEGAPLAY EXTRACTOR (WebView-first for SPAs) ──
open class AniDoorMegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // WebView is needed for SPA pages
        tryWebView(url, callback)
    }

    private suspend fun tryWebView(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                script = """
                    // Auto-click play button and wait for player
                    const clickPlay = () => {
                        const btn = document.querySelector('button,[role="button"],.jw-icon-display,.vjs-big-play-button,.plyr__control--overlaid');
                        if (btn) btn.click();
                    };
                    clickPlay();
                    setTimeout(clickPlay, 2000);
                """.trimIndent(),
                useOkhttp = false,
                timeout = 35_000L
            )
            val resolved = app.get(url, referer = referer ?: "https://anidoor.me/", interceptor = resolver).url
            if (resolved.contains(".m3u8")) {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to (if (resolved.contains("megaplay.buzz")) "https://megaplay.buzz/" else url)
                )
                M3u8Helper.generateM3u8(name, resolved, headers["Referer"]!!, headers = headers).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e(name, "WebView extraction failed: ${e.message}")
        }
    }
}

// ── VIDNEST EXTRACTOR (WebView-first) ──
open class AniDoorVidnest : ExtractorApi() {
    override val name = "VidNest"
    override val mainUrl = "https://vidnest.fun"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        tryWebView(url, callback)
    }

    private suspend fun tryWebView(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                script = """
                    const clickPlay = () => {
                        const btn = document.querySelector('button,[role="button"],.jw-icon-display,.vjs-big-play-button,.plyr__control--overlaid');
                        if (btn) btn.click();
                    };
                    clickPlay();
                    setTimeout(clickPlay, 2000);
                """.trimIndent(),
                useOkhttp = false,
                timeout = 35_000L
            )
            val resolved = app.get(url, referer = referer ?: "https://anidoor.me/", interceptor = resolver).url
            if (resolved.contains(".m3u8")) {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to (if (resolved.contains("megaplay.buzz")) "https://megaplay.buzz/" else url)
                )
                M3u8Helper.generateM3u8(name, resolved, headers["Referer"]!!, headers = headers).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e(name, "WebView extraction failed: ${e.message}")
        }
    }
}

// ── TRYEMBED EXTRACTOR (API-based with WebView fallback) ──
open class AniDoorTryEmbed : ExtractorApi() {
    override val name = "TryEmbed"
    override val mainUrl = "https://tryembed.us.cc"
    override val requiresReferer = true

    data class TryEmbedResponse(
        @JsonProperty("providers") val providers: List<Provider>? = null,
        @JsonProperty("sources") val sources: List<Source>? = null,
        @JsonProperty("selectedProvider") val selectedProvider: Provider? = null,
        @JsonProperty("captions") val captions: List<Caption>? = null
    )
    data class Provider(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("qualities") val qualities: List<Quality>? = null
    )
    data class Quality(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("token") val token: String? = null,
        @JsonProperty("fallbackToken") val fallbackToken: String? = null,
        @JsonProperty("directUrl") val directUrl: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean? = null
    )
    data class Source(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )
    data class Caption(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
    data class PayloadMeta(
        @JsonProperty("meta") val meta: Meta? = null
    )
    data class Meta(
        @JsonProperty("anilist_id") val anilistId: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("audio") val audio: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://anidoor.me/"))
        val page = app.get(url, headers = headers)
        val html = page.text

        // Extract payload for API call
        var alId: String? = null
        var episode: String = "1"
        var audio: String = "sub"

        Regex("""RAW_PAYLOAD\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.let { payload ->
            try {
                val decoded = String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                parseJson<PayloadMeta>(decoded).meta?.let {
                    alId = it.anilistId
                    episode = it.episode?.toString() ?: "1"
                    audio = it.audio ?: "sub"
                }
            } catch (e: Exception) {
                Log.d(name, "Payload decode failed: ${e.message}")
            }
        }

        alId ?: url.substringAfter("/embed/anime/").split("/").getOrNull(0) ?: return

        // Get auth cookie
        val authCookie = page.okhttpResponse.headers("Set-Cookie")
            .firstOrNull { it.contains("tryembed_auth=") }
            ?.substringBefore(";")
            ?.substringAfter("tryembed_auth=")

        val apiHeaders = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to url,
            "Accept" to "application/json, */*"
        )
        authCookie?.let { apiHeaders["Cookie"] = "tryembed_auth=$it" }

        val response = try {
            app.get("$mainUrl/api/stream_data?id=$alId&episode=$episode&audio=$audio", headers = apiHeaders)
                .parsedSafe<TryEmbedResponse>()
        } catch (e: Exception) {
            Log.e(name, "API failed: ${e.message}")
            null
        }

        response?.let { addLinks(it, callback) }
    }

    private fun addLinks(response: TryEmbedResponse, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$mainUrl/")

        val providers = mutableListOf<Provider>()
        response.providers?.let { providers.addAll(it) }
        response.selectedProvider?.let { providers.add(it) }

        providers.forEach { provider ->
            if (provider.status != "ready") return@forEach
            provider.qualities?.forEach { q ->
                val label = q.name ?: "Auto"
                when {
                    q.directUrl != null -> {
                        if (q.isM3U8 ?: q.directUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(name, q.directUrl, "$mainUrl/", headers = headers).forEach(callback)
                        }
                    }
                    q.token != null -> callback(newExtractorLink(
                        name, "$name - $label", "$mainUrl/s/${q.token}.m3u8", ExtractorLinkType.M3U8
                    ) { this.headers = headers })
                }
            }
        }
    }
}

// ── DROPFILE EXTRACTOR ──
open class AniDoorDropfile : ExtractorApi() {
    override val name = "DropFile"
    override val mainUrl = "https://dropfile.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://anidoor.me/"))
        val html = app.get(url, headers = headers).text

        Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
            .findAll(html)
            .mapNotNull { it.groupValues[1].trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .forEach { link ->
                val ref = if (link.contains("streamzone1.site") || link.contains("cinewave2.site"))
                    "https://megaplay.buzz/" else mainUrl
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, ref, headers = headers).forEach(callback)
                }
            }
    }
}

// ── HD EXTRACTOR ──
open class AniDoorHD : ExtractorApi() {
    override val name = "HD"
    override val mainUrl = "https://stream.nightslayer.workers.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://anidoor.me/"))
        val html = app.get(url, headers = headers).text

        Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)[^\s"'<>]*)""")
            .findAll(html)
            .mapNotNull { it.groupValues[1].trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .forEach { link ->
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, mainUrl, headers = headers).forEach(callback)
                }
            }
    }
}