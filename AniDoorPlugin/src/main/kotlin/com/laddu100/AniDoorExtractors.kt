package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser
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

// ── CUSTOM MEGAPLAY EXTRACTOR (USING WEBVIEW TO BYPASS CLOUDFLARE) ──
open class AniDoorMegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
        } catch (e: Exception) {
            Log.e("MegaPlay", "WebView extraction failed: ${e.message}")
        }
    }
}

// ── CUSTOM TRYEMBED EXTRACTOR ────────────────────────────────────────
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

        if (responseData == null) { return }

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

// ── CUSTOM VIDNEST EXTRACTOR (USING WEBVIEW TO BYPASS CLOUDFLARE) ──
open class AniDoorVidnest : ExtractorApi() {
    override val name = "VidNest"
    override val mainUrl = "https://vidnest.fun"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
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
                val playbackHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Referer" to "https://megaplay.buzz/" 
                )
                M3u8Helper.generateM3u8(name, resolved, "https://megaplay.buzz/", headers = playbackHeaders).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("VidNest", "WebView extraction failed: ${e.message}")
        }
    }
}

// ── CUSTOM DROPFILE EXTRACTOR (FIXED SUSPEND LOOP) ──────────────────
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

        for (regex in regexes) {
            for (match in regex.findAll(html)) {
                val link = match.groupValues[1]
                if (!link.startsWith("http")) continue

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

// ── CUSTOM HD EXTRACTOR (FIXED SUSPEND LOOP) ────────────────────────
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

        for (regex in regexes) {
            for (match in regex.findAll(html)) {
                val link = match.groupValues[1]
                if (!link.startsWith("http")) continue

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
