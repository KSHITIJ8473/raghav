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

// ── CUSTOM MEGAPLAY EXTRACTOR (FIXED FOR DUB & CID TOKENS) ───────────
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
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://anidoor.me/")
        )

        // Parse URL to extract IDs and Type
        val urlRegex = Regex("""/stream/(ani|mal|s-\d+)/([^/]+)(?:/(\d+))?/(sub|dub)""")
        val urlMatch = urlRegex.find(url)
        val urlAnimeId = urlMatch?.groupValues?.get(2)
        val urlEpNum = urlMatch?.groupValues?.get(3)
        val urlType = urlMatch?.groupValues?.get(4) ?: if (url.contains("/dub")) "dub" else "sub"

        val pageRes = app.get(url, headers = pageHeaders)
        val doc = pageRes.document
        val html = pageRes.text

        // Find Stream ID
        var streamId: String? = doc.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: urlAnimeId

        if (streamId.isNullOrBlank()) {
            Log.e("MegaPlay", "Could not find stream ID")
            return
        }

        // FIX: Scrape cid and cidu tokens to authorize Dub tracks and prevent 403s
        val cid = Regex("""cid\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""
        val cidu = Regex("""cidu\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""

        val ajaxHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to url,
        )

        // Build API URL with Tokens
        var apiUrl = "$mainUrl/stream/getSources?id=$streamId&type=$urlType"
        if (cid.isNotEmpty()) apiUrl += "&cid=$cid"
        if (cidu.isNotEmpty()) apiUrl += "&cidu=$cidu"
        if (!urlEpNum.isNullOrBlank()) apiUrl += "&episode=$urlEpNum"

        // Clean headers for video playback (No Origin to avoid 403 on CDN)
        val playbackHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to "$mainUrl/"
        )

        try {
            val jsonText = app.get(apiUrl, headers = ajaxHeaders, referer = url).text
            val root = JsonParser.parseString(jsonText).asJsonObject
            val m3u8 = extractM3u8FromJson(root)

            if (!m3u8.isNullOrBlank()) {
                M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders).forEach(callback)
                extractSubtitlesFromJson(root, playbackHeaders, subtitleCallback)
                return
            }
        } catch (e: Exception) {
            Log.d("MegaPlay", "API attempt failed: ${e.message}")
        }
    }

    private fun extractM3u8FromJson(root: com.google.gson.JsonObject): String? {
        try { root.getAsJsonObject("sources")?.get("file")?.asString?.let { return it } } catch (_: Exception) {}
        try { root.getAsJsonArray("sources")?.firstOrNull()?.asJsonObject?.get("file")?.asString?.let { return it } } catch (_: Exception) {}
        try { root.get("source")?.asString?.let { return it } } catch (_: Exception) {}
        try { root.get("file")?.asString?.let { return it } } catch (_: Exception) {}
        try {
            val data = root.getAsJsonObject("data")
            data.get("file")?.asString?.let { return it }
            data.getAsJsonObject("sources")?.get("file")?.asString?.let { return it }
        } catch (_: Exception) {}
        return null
    }

    private fun extractSubtitlesFromJson(
        root: com.google.gson.JsonObject,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val tracks = root.getAsJsonArray("tracks") ?: return
            for (element in tracks) {
                val track = element.asJsonObject
                val kind = track.get("kind")?.asString ?: continue
                if (kind != "captions" && kind != "subtitles") continue
                val file = track.get("file")?.asString ?: continue
                val label = track.get("label")?.asString ?: "Unknown"
                subtitleCallback(newSubtitleFile(label, file) { this.headers = headers })
            }
        } catch (_: Exception) {}
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
        val pageHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to (referer ?: "https://anidoor.me/"))
        val pageRes = app.get(url, headers = pageHeaders)
        val html = pageRes.text
        val playbackHeaders = mapOf("User-Agent" to USER_AGENT, "Accept" to "*/*", "Origin" to mainUrl, "Referer" to "$mainUrl/")

        // Method 1: RAW_PAYLOAD in HTML
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

        // Method 2: JSON embedded directly in script tag
        if (responseData?.providers == null && responseData?.sources == null) {
            val jsonRegex = Regex("""(?:window\.__DATA__|streamData)\s*=\s*(\{.+?\})\s*;""")
            val jsonMatch = jsonRegex.find(html)
            if (jsonMatch != null) {
                try { responseData = parseJson<TryEmbedResponse>(jsonMatch.groupValues[1]) } catch (_: Exception) {}
            }
        }

        if (responseData == null) return

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

// ── CUSTOM VIDNEST EXTRACTOR (FIXED WITH WEBVIEW FOR STABILITY) ──────
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
        // VidNest constantly changes decryption keys. The most stable way to extract 
        // from modern JW Players is to use WebViewResolver to intercept the m3u8 stream.
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
                    "Referer" to "https://megaplay.buzz/" // VidNest usually routes through MegaPlay CDN
                )
                M3u8Helper.generateM3u8(name, resolved, "https://megaplay.buzz/", headers = playbackHeaders).forEach(callback)
            }
        } catch (e: Exception) {
            Log.e("VidNest", "WebView extraction failed: ${e.message}")
        }
    }
}

// ── CUSTOM DROPFILE EXTRACTOR ────────────────────────────────────────
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
            regex.findAll(html).forEach { match ->
                val link = match.groupValues[1]
                if (!link.startsWith("http")) return@forEach

                val refererForLink = when {
                    link.contains("streamzone1.site") || link.contains("cinewave2.site") -> "https://megaplay.buzz/"
                    else -> mainUrl
                }
                val headersForLink = mapOf("User-Agent" to USER_AGENT, "Referer" to refererForLink)

                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, refererForLink, headers = headersForLink).forEach(callback)
                } else if (link.contains(".mp4")) {
                    callback(newExtractorLink(source = name, name = name, url = link, type = ExtractorLinkType.VIDEO) {
                        this.referer = refererForLink; this.headers = headersForLink; this.quality = getQualityFromName(name)
                    })
                }
            }
        }
    }
}

// ── CUSTOM HD EXTRACTOR ──────────────────────────────────────────────
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
            regex.findAll(html).forEach { match ->
                val link = match.groupValues[1]
                if (!link.startsWith("http")) return@forEach

                val refererForLink = when {
                    link.contains("streamzone1.site") || link.contains("cinewave2.site") -> "https://megaplay.buzz/"
                    else -> mainUrl
                }
                val headersForLink = mapOf("User-Agent" to USER_AGENT, "Referer" to refererForLink)

                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(name, link, refererForLink, headers = headersForLink).forEach(callback)
                } else if (link.contains(".mp4")) {
                    callback(newExtractorLink(source = name, name = name, url = link, type = ExtractorLinkType.VIDEO) {
                        this.referer = refererForLink; this.headers = headersForLink; this.quality = getQualityFromName(name)
                    })
                }
            }
        }
    }
}
