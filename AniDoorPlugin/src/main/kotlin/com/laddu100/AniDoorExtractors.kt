package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.JsonParser
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName

// ── CUSTOM MEGAPLAY EXTRACTOR (FIXED) ────────────────────────────────
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
        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://anidoor.me/")
        )

        // Parse URL to extract IDs as fallback
        val urlRegex = Regex("""/stream/(ani|mal|s-\d+)/([^/]+)(?:/(\d+))?/(sub|dub)""")
        val urlMatch = urlRegex.find(url)
        val urlProvider = urlMatch?.groupValues?.get(1)
        val urlAnimeId = urlMatch?.groupValues?.get(2)
        val urlEpNum = urlMatch?.groupValues?.get(3)
        val urlType = urlMatch?.groupValues?.get(4)

        val type = urlType ?: if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

        // Fetch the page
        val pageRes = app.get(url, headers = pageHeaders)
        val doc = pageRes.document
        val html = pageRes.text

        // Collect any cookies from the page response
        val cookies = pageRes.okhttpResponse.headers("Set-Cookie")
        val cookieHeader = cookies.joinToString("; ") {
            it.substringBefore(";")
        }.takeIf { it.isNotBlank() }

        // Try multiple methods to find stream ID
        var streamId: String? = null

        // Method 1: #megaplay-player data-id / data-realid
        val playerEl = doc.selectFirst("#megaplay-player")
        streamId = playerEl?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: playerEl?.attr("data-realid")?.takeIf { it.isNotBlank() }

        // Method 2: Any element with data-id
        if (streamId.isNullOrBlank()) {
            streamId = doc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
        }

        // Method 3: Search script tags for stream ID patterns
        if (streamId.isNullOrBlank()) {
            val scriptPatterns = listOf(
                Regex("""(?:streamId|sourceId|dataId|source_id)\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""(?:var|let|const)\s+(?:streamId|sourceId)\s*=\s*["']([^"']+)["']"""),
            )
            for (script in doc.select("script")) {
                val content = script.html()
                for (pattern in scriptPatterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        streamId = match.groupValues[1]
                        break
                    }
                }
                if (!streamId.isNullOrBlank()) break
            }
        }

        // Method 4: Look for data-realid on any element
        if (streamId.isNullOrBlank()) {
            streamId = doc.selectFirst("[data-realid]")?.attr("data-realid")?.takeIf { it.isNotBlank() }
        }

        // Method 5: Use anime ID from URL as fallback
        if (streamId.isNullOrBlank()) {
            streamId = urlAnimeId
            Log.d("MegaPlay", "Using URL-extracted anime ID as streamId: $streamId")
        }

        if (streamId.isNullOrBlank()) {
            Log.e("MegaPlay", "Could not find stream ID for URL: $url")
            // Last resort: scan entire HTML for m3u8
            extractM3u8FromHtml(html, playbackHeaders, callback)
            return
        }

        val ajaxHeaders = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to url,
        )
        if (!cookieHeader.isNullOrBlank()) {
            ajaxHeaders["Cookie"] = cookieHeader
        }

        // Try multiple API endpoints/formats
        val apiAttempts = buildList {
            add("$mainUrl/stream/getSources?id=$streamId&type=$type")
            if (urlEpNum != null) {
                add("$mainUrl/stream/getSources?id=$streamId&episode=$urlEpNum&type=$type")
            }
            add("$mainUrl/api/source/$streamId")
            if (urlProvider == "mal") {
                add("$mainUrl/stream/getSources?id=$streamId&type=$type&provider=mal")
            }
            if (urlProvider == "ani") {
                add("$mainUrl/stream/getSources?id=$streamId&type=$type&provider=ani")
            }
            add("$mainUrl/stream/getSources?id=$streamId&type=$type&episode=${urlEpNum ?: "1"}")
        }

        for (apiUrl in apiAttempts) {
            try {
                val jsonText = app.get(apiUrl, headers = ajaxHeaders, referer = url).text
                Log.d("MegaPlay", "API response from $apiUrl: ${jsonText.take(200)}")

                val root = JsonParser.parseString(jsonText).asJsonObject
                val m3u8 = extractM3u8FromJson(root)

                if (!m3u8.isNullOrBlank()) {
                    Log.d("MegaPlay", "Found m3u8: $m3u8")

                    val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders)
                    if (generated.isNotEmpty()) {
                        generated.forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                                this.referer = "$mainUrl/"
                                this.headers = playbackHeaders
                            }
                        )
                    }

                    extractSubtitlesFromJson(root, playbackHeaders, subtitleCallback)
                    return // Success
                }
            } catch (e: Exception) {
                Log.d("MegaPlay", "API attempt failed for $apiUrl: ${e.message}")
            }
        }

        // Last resort: scan entire page HTML for m3u8 URLs
        Log.d("MegaPlay", "All API attempts failed, scanning HTML for m3u8")
        extractM3u8FromHtml(html, playbackHeaders, callback)
    }

    private fun extractM3u8FromJson(root: com.google.gson.JsonObject): String? {
        // Format 1: { "sources": { "file": "..." } }
        try {
            root.getAsJsonObject("sources")?.get("file")?.asString?.let { return it }
        } catch (_: Exception) {}

        // Format 2: { "sources": [{ "file": "..." }] }
        try {
            root.getAsJsonArray("sources")?.firstOrNull()?.asJsonObject?.get("file")?.asString?.let { return it }
        } catch (_: Exception) {}

        // Format 3: { "source": "..." }
        try {
            root.get("source")?.asString?.let { return it }
        } catch (_: Exception) {}

        // Format 4: { "file": "..." }
        try {
            root.get("file")?.asString?.let { return it }
        } catch (_: Exception) {}

        // Format 5: { "data": { ... } }
        try {
            val data = root.getAsJsonObject("data")
            data.get("file")?.asString?.let { return it }
            data.getAsJsonObject("sources")?.get("file")?.asString?.let { return it }
            data.getAsJsonArray("sources")?.firstOrNull()?.asJsonObject?.get("file")?.asString?.let { return it }
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
                subtitleCallback(
                    newSubtitleFile(label, file) { this.headers = headers }
                )
            }
        } catch (_: Exception) {}
    }

    private fun extractM3u8FromHtml(
        html: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
        m3u8Regex.findAll(html).forEach { match ->
            val m3u8 = match.groupValues[1]
            val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = headers)
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = headers
                    }
                )
            }
        }
    }
}

// ── CUSTOM TRYEMBED EXTRACTOR (FIXED) ────────────────────────────────
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

        // Collect cookies
        val cookies = pageRes.okhttpResponse.headers("Set-Cookie")
        val tryembedAuth = cookies.firstOrNull { it.contains("tryembed_auth=") }
            ?.substringBefore(";")?.substringAfter("tryembed_auth=")

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        // Method 1: RAW_PAYLOAD in HTML
        val payloadRegex = Regex("""RAW_PAYLOAD\s*=\s*["']([^"']+)["']""")
        val payloadMatch = payloadRegex.find(html)
        var responseData = if (payloadMatch != null) {
            try {
                val base64Payload = payloadMatch.groupValues[1]
                val decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT)
                val decodedJson = String(decodedBytes, Charsets.UTF_8)
                parseJson<TryEmbedResponse>(decodedJson)
            } catch (e: Exception) {
                null
            }
        } else null

        // Method 2: JSON embedded directly in script tag
        if (responseData?.providers == null && responseData?.sources == null) {
            val jsonRegex = Regex("""(?:window\.__DATA__|streamData)\s*=\s*(\{.+?\})\s*;""")
            val jsonMatch = jsonRegex.find(html)
            if (jsonMatch != null) {
                try {
                    responseData = parseJson<TryEmbedResponse>(jsonMatch.groupValues[1])
                } catch (_: Exception) {}
            }
        }

        // Method 3: AJAX API request
        if (responseData?.providers == null && responseData?.sources == null) {
            val pathParts = url.substringAfter("$mainUrl/embed/anime/").split("/")
            if (pathParts.size >= 3) {
                val alId = pathParts[0]
                val episode = pathParts[1]
                val audio = pathParts[2]

                val apiHeaders = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin",
                    "Sec-Fetch-Dest" to "empty",
                    "Accept" to "*/*"
                )
                if (tryembedAuth != null) {
                    apiHeaders["Cookie"] = "tryembed_auth=$tryembedAuth"
                }

                val apiUrls = listOf(
                    "$mainUrl/api/stream_data?id=$alId&episode=$episode&audio=$audio",
                    "$mainUrl/api/source?id=$alId&episode=$episode&audio=$audio",
                    "$mainUrl/api/stream?id=$alId&episode=$episode&audio=$audio",
                )

                for (apiUrl in apiUrls) {
                    try {
                        val apiRes = app.get(apiUrl, headers = apiHeaders)
                        val parsed = parseJson<TryEmbedResponse>(apiRes.text)
                        if (parsed.providers != null || parsed.sources != null) {
                            responseData = parsed
                            break
                        }
                    } catch (e: Exception) {
                        Log.d("TryEmbed", "API attempt failed for $apiUrl: ${e.message}")
                    }
                }
            }
        }

        if (responseData == null) {
            // Last resort: scan HTML for m3u8
            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8 = match.groupValues[1]
                callback(
                    newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                    }
                )
            }
            return
        }

        // Process providers (existing format)
        responseData.providers?.forEach { provider ->
            if (provider.status != "ready") return@forEach
            val qualities = provider.qualities ?: return@forEach
            val serverName = provider.name ?: name
            for (quality in qualities) {
                val token = quality.token ?: quality.fallbackToken ?: continue
                val qualityLabel = quality.name ?: "Auto"
                val m3u8Url = "$mainUrl/s/$token.m3u8"

                callback(
                    newExtractorLink(
                        serverName,
                        "$serverName - $qualityLabel",
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                    }
                )
            }
        }

        // Process sources (alternative format)
        responseData.sources?.forEach { source ->
            val fileUrl = source.file ?: return@forEach
            val label = source.label ?: source.type ?: "Auto"
            if (fileUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, fileUrl, "$mainUrl/", headers = playbackHeaders).forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, "$name - $label", fileUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                    }
                )
            }
        }
    }
}

// ── CUSTOM VIDNEST EXTRACTOR (FIXED) ─────────────────────────────────
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
        val uri = java.net.URI(url)
        val pathParts = uri.path.split("/").filter { it.isNotBlank() }
        if (pathParts.size < 4) {
            Log.e("VidNest", "URL path too short: $url")
            return
        }

        val backend = if (pathParts[0] == "animepahe") "animepahe" else "hianime"
        val alId = pathParts[1]
        val episode = pathParts[2]
        val audio = pathParts[3]

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json"
        )

        // Try multiple API endpoints
        val apiBases = listOf(
            "https://new.vidnest.fun",
            "https://api.vidnest.fun",
            "https://vidnest.fun"
        )

        var apiResponseText: String? = null
        for (base in apiBases) {
            val apiUrls = listOf(
                "$base/$backend/anime/$alId/$episode/$audio",
                "$base/api/$backend/anime/$alId/$episode/$audio",
                "$base/anime/$backend/$alId/$episode/$audio",
            )
            for (apiUrl in apiUrls) {
                try {
                    val res = app.get(apiUrl, headers = apiHeaders)
                    if (res.code == 200 && res.text.isNotBlank()) {
                        apiResponseText = res.text
                        Log.d("VidNest", "Success from $apiUrl")
                        break
                    }
                } catch (e: Exception) {
                    Log.d("VidNest", "Failed $apiUrl: ${e.message}")
                }
            }
            if (apiResponseText != null) break
        }

        if (apiResponseText == null) {
            Log.e("VidNest", "All API endpoints failed")
            return
        }

        val json = try {
            parseJson<VidNestApiResponse>(apiResponseText)
        } catch (e: Exception) {
            Log.e("VidNest", "JSON parsing failed: ${e.message}")
            return
        }

        val dataField = json.data
        if (dataField.isNullOrBlank()) {
            Log.e("VidNest", "No data field in response")
            return
        }

        val decryptedJsonText = if (json.encrypted) {
            try {
                decrypt(dataField)
            } catch (e: Exception) {
                Log.e("VidNest", "Decryption failed: ${e.message}")
                return
            }
        } else {
            dataField
        }

        val decryptedResponse = try {
            parseJson<VidNestDecryptedResponse>(decryptedJsonText)
        } catch (e: Exception) {
            Log.e("VidNest", "Failed to parse decrypted JSON: ${e.message}")
            // Try parsing as raw JSON if decryption produced garbage
            if (json.encrypted) {
                try {
                    parseJson<VidNestDecryptedResponse>(dataField)
                } catch (_: Exception) {
                    return
                }
            } else return
        }

        if (!decryptedResponse.success) {
            Log.e("VidNest", "Response success=false")
            return
        }

        val metaSubOrDub = decryptedResponse.metadata?.subOrDub
        if (!metaSubOrDub.isNullOrBlank() && !metaSubOrDub.equals(audio, ignoreCase = true)) {
            Log.e("VidNest", "Audio mismatch: requested $audio but got $metaSubOrDub")
            return
        }

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to "https://megaplay.buzz",
            "Referer" to "https://megaplay.buzz/",
        )

        decryptedResponse.sources?.forEach { source ->
            val fileUrl = source.file ?: return@forEach
            if (fileUrl.isNotBlank()) {
                val generated = M3u8Helper.generateM3u8(name, fileUrl, "https://megaplay.buzz/", headers = playbackHeaders)
                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                } else {
                    callback(
                        newExtractorLink(name, name, fileUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://megaplay.buzz/"
                            this.headers = playbackHeaders
                        }
                    )
                }
            }
        }

        decryptedResponse.tracks?.forEach { track ->
            val fileUrl = track.file ?: return@forEach
            val label = track.label ?: "Unknown"
            if (track.kind == "captions" || track.kind == "subtitles") {
                subtitleCallback(
                    newSubtitleFile(label, fileUrl) {
                        this.headers = playbackHeaders
                    }
                )
            }
        }
    }

    private fun decrypt(encryptedData: String): String {
        val key = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
        val charMap = key.withIndex().associate { it.value to it.index }
        val out = java.io.ByteArrayOutputStream()

        var i = 0
        while (i < encryptedData.length) {
            val endIdx = if (i + 4 < encryptedData.length) i + 4 else encryptedData.length
            val chunk = encryptedData.substring(i, endIdx).padEnd(4, '=')
            val d = chunk.map { charMap[it] ?: 64 }

            val b1 = ((d[0] shl 2) or (d[1] ushr 4))
            out.write(b1)

            if (d[2] != 64) {
                val b2 = (((d[1] and 15) shl 4) or (d[2] ushr 2))
                out.write(b2)
            }

            if (d[3] != 64) {
                val b3 = (((d[2] and 3) shl 6) or d[3])
                out.write(b3)
            }

            i += 4
        }

        return out.toString("UTF-8")
    }

    data class VidNestApiResponse(
        @JsonProperty("data") val data: String? = null,
        @JsonProperty("encrypted") val encrypted: Boolean = false
    )

    data class VidNestDecryptedResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("sources") val sources: List<VidNestSource>? = null,
        @JsonProperty("tracks") val tracks: List<VidNestTrack>? = null,
        @JsonProperty("metadata") val metadata: VidNestMetadata? = null
    )

    data class VidNestMetadata(
        @JsonProperty("subOrDub") val subOrDub: String? = null
    )

    data class VidNestSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class VidNestTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("default") val default: Boolean = false
    )
}

// ── CUSTOM DROPFILE EXTRACTOR (FIXED) ────────────────────────────────
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
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://anidoor.me/")
        )
        val pageRes = app.get(url, headers = pageHeaders)
        val html = pageRes.text

        // Multiple regex patterns for finding video URLs
        val regexes = listOf(
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
            Regex("""["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']"""),
            Regex("""src\s*=\s*["'](https?://[^"']+)["']"""),
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

// ── CUSTOM HD EXTRACTOR (FIXED) ──────────────────────────────────────
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
        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://anidoor.me/")
        )
        val pageRes = app.get(url, headers = pageHeaders)
        val html = pageRes.text

        val regexes = listOf(
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)"""),
            Regex("""["'](https?://[^"']+(?:\.m3u8|\.mp4)[^"']*)["']"""),
            Regex("""src\s*=\s*["'](https?://[^"']+)["']"""),
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