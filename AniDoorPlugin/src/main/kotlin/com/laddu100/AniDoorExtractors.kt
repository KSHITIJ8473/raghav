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

        runCatching {
            // Try API-first approach (like Anivexa does)
            val document = app.get(url, headers = headers).document
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-realid=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return@runCatching

            val response = app.get(
                "$mainUrl/stream/getSources?id=$id",
                headers = headers
            ).parsedSafe<MegaPlayResponse>() ?: return@runCatching

            val m3u8 = response.sources?.file ?: return@runCatching

            M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)

            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(
                        newSubtitleFile(track.label ?: "Subtitle", file) {
                            this.headers = mapOf("Referer" to "$mainUrl/")
                        }
                    )
                }
            }
        }.onFailure { error ->
            Log.e(name, "API extraction failed, trying WebView: ${error.message}")
            // WebView fallback
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.m3u8"""),
                    additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                    script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                    useOkhttp = false,
                    timeout = 20_000L
                )
                val resolved = app.get(url, referer = referer ?: "https://anidoor.me/", interceptor = resolver).url
                if (resolved.contains(".m3u8")) {
                    val playbackHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                        "Accept" to "*/*",
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
        @JsonProperty("sources") val sources: List<TryEmbedSourceAlt>? = null
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
        @JsonProperty("fallbackToken") val fallbackToken: String? = null
    )
    data class TryEmbedSourceAlt(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("label") val label: String? = null
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

        val pageRes = app.get(url, headers = pageHeaders)
        val html = pageRes.text

        val cookies = pageRes.okhttpResponse.headers("Set-Cookie")
        val tryembedAuth = cookies.firstOrNull { it.contains("tryembed_auth=") }
            ?.substringBefore(";")?.substringAfter("tryembed_auth=")

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        // Try to find payload in page
        val payloadRegex = Regex("""RAW_PAYLOAD\s*=\s*["']([^"']+)["']""")
        val payloadMatch = payloadRegex.find(html)
        var responseData = if (payloadMatch != null) {
            try {
                val base64Payload = payloadMatch.groupValues[1]
                val decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT)
                val decodedJson = String(decodedBytes, Charsets.UTF_8)
                parseJson<TryEmbedResponse>(decodedJson)
            } catch (e: Exception) { null }
        } else null

        if (responseData?.providers == null && responseData?.sources == null) {
            val jsonRegex = Regex("""(?:window\.__DATA__|streamData)\s*=\s*(\{.+?\})\s*;""")
            val jsonMatch = jsonRegex.find(html)
            if (jsonMatch != null) {
                try { responseData = parseJson<TryEmbedResponse>(jsonMatch.groupValues[1]) } catch (_: Exception) {}
            }
        }

        // Try API endpoints if page parsing failed
        if (responseData?.providers == null && responseData?.sources == null) {
            val pathParts = url.substringAfter("$mainUrl/embed/anime/").split("/")
            if (pathParts.size >= 3) {
                val alId = pathParts[0]
                val episode = pathParts[1]
                val audio = pathParts[2]

                val apiHeaders = mutableMapOf(
                    "User-Agent" to USER_AGENT, "Referer" to url, "Origin" to mainUrl,
                    "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-Dest" to "empty", "Accept" to "*/*"
                )
                if (tryembedAuth != null) { apiHeaders["Cookie"] = "tryembed_auth=$tryembedAuth" }

                val apiUrls = listOf(
                    "$mainUrl/api/stream_data?id=$alId&episode=$episode&audio=$audio",
                    "$mainUrl/api/source?id=$alId&episode=$episode&audio=$audio",
                    "$mainUrl/api/stream?id=$alId&episode=$episode&audio=$audio",
                )

                for (apiUrl in apiUrls) {
                    try {
                        val apiRes = app.get(apiUrl, headers = apiHeaders)
                        val parsed = parseJson<TryEmbedResponse>(apiRes.text)
                        if (parsed.providers != null || parsed.sources != null) { responseData = parsed; break }
                    } catch (e: Exception) { Log.d("TryEmbed", "API attempt failed: ${e.message}") }
                }
            }
        }

        if (responseData == null) {
            Log.d("TryEmbed", "No response data found for $url")
            return
        }

        // Process providers
        responseData.providers?.forEach { provider ->
            if (provider.status != "ready") return@forEach
            val qualities = provider.qualities ?: return@forEach
            val serverName = provider.name ?: name
            for (quality in qualities) {
                val token = quality.token ?: quality.fallbackToken ?: continue
                val qualityLabel = quality.name ?: "Auto"
                val m3u8Url = "$mainUrl/s/$token.m3u8"
                callback(newExtractorLink(serverName, "$serverName - $qualityLabel", m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"; this.headers = playbackHeaders
                })
            }
        }

        // Process direct sources
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
    }
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
        // Vidnest uses the same megaplay API structure
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )

        runCatching {
            val document = app.get(url, headers = headers).document
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-realid=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return@runCatching

            // VidNest currently renders MegaPlay player ids, but its own getSources route 404s.
            // Query MegaPlay directly so both sub and dub VidNest embeds resolve instead of falling back to WebView only.
            val apiBase = "https://megaplay.buzz"
            val response = app.get(
                "$apiBase/stream/getSources?id=$id",
                headers = headers + mapOf("Referer" to "$apiBase/"),
            ).parsedSafe<MegaPlayResponse>() ?: return@runCatching

            val m3u8 = response.sources?.file ?: return@runCatching
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
        }.onFailure { error ->
            Log.e(name, "API extraction failed, trying WebView: ${error.message}")
            // WebView fallback
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.m3u8"""),
                    additionalUrls = listOf(Regex("""(?i)\.m3u8""")),
                    script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                    useOkhttp = false,
                    timeout = 15_000L
                )
                val resolved = app.get(url, referer = referer ?: "https://anidoor.me/", interceptor = resolver).url
                if (resolved.contains(".m3u8")) {
                    // FIXED: Use the correct referer for vidnest content
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

    // Reuse MegaPlay API response types
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
