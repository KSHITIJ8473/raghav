package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName

// ── MEGAPLAY EXTRACTOR (API-FIRST WITH WEBVIEW FALLBACK) ──
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )

        // FIXED: Use try/catch instead of runCatching so fallback always triggers
        try {
            val document = app.get(url, headers = headers).document
            val html = document.html()

            // Try multiple patterns to extract the player ID
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id\s*=\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-realid\s*=\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: Regex(""""id"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)

            if (id == null) {
                Log.d(name, "No player ID found in HTML, falling back to WebView")
                throw Exception("No player ID found")
            }

            Log.d(name, "Found player ID: $id, calling API")

            val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"
            val response = app.get(
                "$mainUrl/stream/getSources?id=$id&type=$type",
                headers = headers,
                referer = url
            ).parsedSafe<MegaPlayResponse>()

            if (response == null || response.sources?.file == null) {
                Log.d(name, "API returned no sources, falling back to WebView")
                throw Exception("API returned no sources")
            }

            val m3u8 = response.sources!!.file!!
            val playbackHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
            val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders)
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, "$name - ${type.uppercase()}", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                    }
                )
            }

            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(
                        newSubtitleFile(track.label ?: "Subtitle", file) {
                            this.headers = playbackHeaders
                        }
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(name, "API extraction failed, trying WebView: ${error.message}")
            // WebView fallback
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.m3u8"""),
                    additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                    script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button,.plyr__control--overlaid')?.click();""",
                    useOkhttp = false,
                    timeout = 25_000L
                )
                val resolved = app.get(url, referer = referer ?: "https://anidoor.me/", interceptor = resolver).url
                if (resolved.contains(".m3u8")) {
                    val playbackHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                    M3u8Helper.generateM3u8(name, resolved, mainUrl, headers = playbackHeaders).forEach(callback)
                }
            } catch (e2: Exception) {
                Log.e(name, "WebView fallback also failed: ${e2.message}")
            }
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: MegaPlaySources? = null,
        @JsonProperty("tracks") val tracks: List<MegaPlayTrack> = emptyList()
    )
    data class MegaPlaySources(@JsonProperty("file") val file: String? = null)
    data class MegaPlayTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}

// ── TRYEMBED EXTRACTOR ──
open class AniDoorTryEmbed : ExtractorApi() {
    override val name = "TryEmbed"
    override val mainUrl = "https://tryembed.us.cc"
    override val requiresReferer = true

    data class TryEmbedResponse(
        @JsonProperty("providers") val providers: List<TryEmbedProvider>? = null,
        @JsonProperty("sources") val sources: List<TryEmbedSourceAlt>? = null,
        @JsonProperty("selectedProvider") val selectedProvider: TryEmbedProvider? = null,
        @JsonProperty("captions") val captions: List<TryEmbedCaption>? = null
    )
    data class TryEmbedProvider(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("qualities") val qualities: List<TryEmbedQuality>? = null
    )
    data class TryEmbedQuality(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("token") val token: String? = null,
        @JsonProperty("fallbackToken") val fallbackToken: String? = null,
        @JsonProperty("directUrl") val directUrl: String? = null,
        @JsonProperty("isM3U8") val isM3U8: Boolean? = null
    )
    data class TryEmbedSourceAlt(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
    )
    data class TryEmbedCaption(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedReferer = referer ?: "https://anidoor.me/"
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedReferer
        )

        // Step 1: Visit embed page to get cookies and extract payload
        val pageRes = app.get(url, headers = pageHeaders)
        val html = pageRes.text

        // Extract cookies for API auth
        val cookies = pageRes.okhttpResponse.headers("Set-Cookie")
        val tryembedAuth = cookies.firstOrNull { it.contains("tryembed_auth=") }
            ?.substringBefore(";")?.substringAfter("tryembed_auth=")

        Log.d(name, "Got cookies: tryembed_auth=${tryembedAuth?.take(10)}...")

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to url,
        )

        // Step 2: Extract payload from embed page
        val payloadRegex = Regex("""RAW_PAYLOAD\s*=\s*["']([^"']+)["']""")
        val payloadMatch = payloadRegex.find(html)
        val payloadMeta = if (payloadMatch != null) {
            try {
                val decodedBytes = Base64.decode(payloadMatch.groupValues[1], Base64.DEFAULT)
                val decodedJson = String(decodedBytes, Charsets.UTF_8)
                parseJson<TryEmbedPayloadMeta>(decodedJson)
            } catch (e: Exception) { null }
        } else null

        // Step 3: Build API URL from payload or URL path
        var responseData: TryEmbedResponse? = null
        val pathParts = url.substringAfter("$mainUrl/embed/anime/").split("/")
        val alId = payloadMeta?.meta?.anilistId ?: pathParts.getOrNull(0) ?: ""
        val episode = payloadMeta?.meta?.episode?.toString() ?: pathParts.getOrNull(1) ?: "1"
        val audio = payloadMeta?.meta?.audio ?: pathParts.getOrNull(2) ?: "sub"

        // Step 4: Call API with cookies
        if (alId.isNotBlank()) {
            val apiHeaders = mutableMapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to mainUrl,
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Dest" to "empty",
                "Accept" to "application/json, */*"
            )
            // CRITICAL: Cookie must be sent with API request
            if (tryembedAuth != null) {
                apiHeaders["Cookie"] = "tryembed_auth=$tryembedAuth"
            }

            val apiUrl = "$mainUrl/api/stream_data?id=$alId&episode=$episode&audio=$audio"
            try {
                val apiRes = app.get(apiUrl, headers = apiHeaders)
                Log.d(name, "API response: HTTP ${apiRes.code}, len=${apiRes.text.length}")
                responseData = parseJson<TryEmbedResponse>(apiRes.text)
            } catch (e: Exception) {
                Log.e(name, "API call failed: ${e.message}")
            }
        }

        if (responseData == null) {
            Log.e(name, "No response data from API for $url")
            return
        }

        // Step 5: Collect all providers (including selectedProvider)
        val allProviders = mutableListOf<TryEmbedProvider>()
        responseData.providers?.let { allProviders.addAll(it) }
        responseData.selectedProvider?.let { allProviders.add(it) }

        // Step 6: Process providers
        allProviders.forEach { provider ->
            if (provider.status != "ready") {
                Log.d(name, "Provider ${provider.id}/${provider.name} status=${provider.status}, skipping")
                return@forEach
            }
            val qualities = provider.qualities ?: return@forEach
            val serverName = provider.name ?: provider.id ?: name

            for (quality in qualities) {
                val qualityLabel = quality.name ?: "Auto"

                // FIXED: Handle directUrl format (used by dub/Timi server)
                val directUrl = quality.directUrl
                if (directUrl != null) {
                    val isM3u8 = quality.isM3U8 ?: directUrl.contains(".m3u8")
                    if (isM3u8) {
                        M3u8Helper.generateM3u8(
                            serverName, directUrl, "$mainUrl/", headers = playbackHeaders
                        ).forEach(callback)
                    } else {
                        callback(newExtractorLink(
                            serverName, "$serverName - $qualityLabel", directUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = playbackHeaders
                        })
                    }
                    continue
                }

                // Handle token-based format (used by sub/Alpha server)
                val token = quality.token ?: quality.fallbackToken
                if (token != null) {
                    val m3u8Url = "$mainUrl/s/$token.m3u8"
                    callback(newExtractorLink(
                        serverName, "$serverName - $qualityLabel", m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                    })
                }
            }
        }

        // Step 7: Process direct sources (fallback)
        responseData.sources?.forEach { source ->
            val fileUrl = source.file ?: return@forEach
            val label = source.label ?: source.type ?: "Auto"
            if (fileUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, fileUrl, "$mainUrl/", headers = playbackHeaders).forEach(callback)
            } else {
                callback(newExtractorLink(name, "$name - $label", fileUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"; this.headers = playbackHeaders
                })
            }
        }

        // Step 8: Process captions/subtitles
        responseData.captions?.forEach { caption ->
            val file = caption.file ?: return@forEach
            subtitleCallback(
                newSubtitleFile(caption.label ?: "Subtitle", file) {
                    this.headers = mapOf("Referer" to "$mainUrl/")
                }
            )
        }
    }

    data class TryEmbedPayloadMeta(
        @JsonProperty("meta") val meta: TryEmbedMeta? = null
    )
    data class TryEmbedMeta(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("anilist_id") val anilistId: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("audio") val audio: String? = null
    )
}

// ── VIDNEST EXTRACTOR (API-FIRST WITH WEBVIEW FALLBACK) ──
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )

        // FIXED: Use try/catch so WebView fallback always triggers on failure
        try {
            val document = app.get(url, headers = headers).document
            val html = document.html()

            // Try to find player ID - VidNest is a Next.js app, data-id may be in RSC payloads
            var id: String? = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id\s*=\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""data-realid\s*=\s*["'](\d+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)

            // Also search RSC (React Server Component) payloads
            if (id == null) {
                val rscChunks = Regex("""self\.__next_f\.push\((\[.*?\])\s*\)""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(html).map { it.groupValues[1] }.toList()
                val rscText = rscChunks.joinToString(" ")
                id = Regex("""data-id["']?\s*:\s*["']?(\d+)""").find(rscText)?.groupValues?.get(1)
                    ?: Regex(""""id"\s*:\s*(\d{2,6})""").find(rscText)?.groupValues?.get(1)
            }

            if (id == null) {
                Log.d(name, "No player ID found in VidNest HTML/RSC, falling back to WebView")
                throw Exception("No player ID found")
            }

            Log.d(name, "Found VidNest player ID: $id")

            // Query MegaPlay API directly since VidNest proxies their player
            val apiBase = "https://megaplay.buzz"
            val response = app.get(
                "$apiBase/stream/getSources?id=$id",
                headers = headers + mapOf("Referer" to "$apiBase/"),
            ).parsedSafe<VidNestMegaPlayResponse>()

            if (response == null || response.sources?.file == null) {
                Log.d(name, "MegaPlay API returned no sources for VidNest ID $id, falling back to WebView")
                throw Exception("API returned no sources")
            }

            val m3u8 = response.sources!!.file!!
            val playbackHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*",
                "Referer" to "$apiBase/",
            )

            M3u8Helper.generateM3u8(name, m3u8, apiBase, headers = playbackHeaders).forEach(callback)

            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(
                        newSubtitleFile(track.label ?: "Subtitle", file) {
                            this.headers = mapOf("Referer" to "$apiBase/")
                        }
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(name, "API extraction failed, trying WebView: ${error.message}")
            // WebView fallback
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.m3u8"""),
                    additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                    script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button,.plyr__control--overlaid')?.click();""",
                    useOkhttp = false,
                    timeout = 25_000L
                )
                val resolved = app.get(url, referer = referer ?: "https://anidoor.me/", interceptor = resolver).url
                if (resolved.contains(".m3u8")) {
                    val resolvedReferer = if (resolved.contains("megaplay.buzz")) "https://megaplay.buzz/"
                        else if (resolved.contains("vidnest.fun")) "https://vidnest.fun/"
                        else mainUrl
                    val playbackHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                        "Referer" to resolvedReferer
                    )
                    M3u8Helper.generateM3u8(name, resolved, resolvedReferer, headers = playbackHeaders).forEach(callback)
                }
            } catch (e2: Exception) {
                Log.e(name, "WebView fallback also failed: ${e2.message}")
            }
        }
    }

    // VidNest reuses MegaPlay API response types (renamed to avoid conflict)
    data class VidNestMegaPlayResponse(
        @JsonProperty("sources") val sources: VidNestMegaPlaySources? = null,
        @JsonProperty("tracks") val tracks: List<VidNestMegaPlayTrack> = emptyList()
    )
    data class VidNestMegaPlaySources(@JsonProperty("file") val file: String? = null)
    data class VidNestMegaPlayTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
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
        val pageHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://anidoor.me/"))
        val html = app.get(url, headers = pageHeaders).text

        val regexes = listOf(
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
        )

        val seenUrls = mutableSetOf<String>()
        for (regex in regexes) {
            for (match in regex.findAll(html)) {
                val link = match.groupValues[1].trim()
                if (!link.startsWith("http")) continue
                if (!seenUrls.add(link)) continue

                val refererForLink = when {
                    link.contains("streamzone1.site") || link.contains("cinewave2.site") -> "https://megaplay.buzz/"
                    else -> mainUrl
                }
                val headersForLink = mapOf("User-Agent" to USER_AGENT, "Referer" to refererForLink)

                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, refererForLink, headers = headersForLink).forEach(callback)
                } else if (link.contains(".mp4")) {
                    callback(
                        newExtractorLink(source = name, name = name, url = link, type = ExtractorLinkType.VIDEO) {
                            this.referer = refererForLink
                            this.headers = headersForLink
                            this.quality = getQualityFromName(name)
                        }
                    )
                }
            }
        }
    }
}

// ── HD (NIGHTSLAYER) EXTRACTOR ──
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
        val pageHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://anidoor.me/"))
        val html = app.get(url, headers = pageHeaders).text

        val regexes = listOf(
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
        )

        val seenUrls = mutableSetOf<String>()
        for (regex in regexes) {
            for (match in regex.findAll(html)) {
                val link = match.groupValues[1].trim()
                if (!link.startsWith("http")) continue
                if (!seenUrls.add(link)) continue

                val refererForLink = when {
                    link.contains("streamzone1.site") || link.contains("cinewave2.site") -> "https://megaplay.buzz/"
                    else -> mainUrl
                }
                val headersForLink = mapOf("User-Agent" to USER_AGENT, "Referer" to refererForLink)

                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, refererForLink, headers = headersForLink).forEach(callback)
                } else if (link.contains(".mp4")) {
                    callback(
                        newExtractorLink(source = name, name = name, url = link, type = ExtractorLinkType.VIDEO) {
                            this.referer = refererForLink
                            this.headers = headersForLink
                            this.quality = getQualityFromName(name)
                        }
                    )
                }
            }
        }
    }
}
