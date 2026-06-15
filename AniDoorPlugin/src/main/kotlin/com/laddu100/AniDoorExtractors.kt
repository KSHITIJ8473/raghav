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

// ── CUSTOM MEGAPLAY EXTRACTOR ────────────────────────────────────────
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

        val doc = app.get(url, headers = pageHeaders).document
        val playerEl = doc.selectFirst("#megaplay-player")
        val streamId = playerEl?.attr("data-id")
            ?: playerEl?.attr("data-realid")
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
            ?: return

        val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

        val ajaxHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to url,
        )

        val jsonText = try {
            app.get(
                "$mainUrl/stream/getSources?id=$streamId&type=$type",
                headers = ajaxHeaders,
                referer = url
            ).text
        } catch (e: Exception) {
            Log.e("MegaPlay", "getSources failed: ${e.message}")
            return
        }

        val root = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (e: Exception) {
            null
        } ?: return
        val m3u8 = root.getAsJsonObject("sources")?.get("file")?.asString
        if (m3u8.isNullOrBlank()) {
            Log.e("MegaPlay", "No m3u8 in response for id=$streamId")
            return
        }

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

        try {
            val tracks = root.getAsJsonArray("tracks")
            if (tracks != null) {
                for (element in tracks) {
                    val track = element.asJsonObject
                    val kind = track.get("kind")?.asString ?: continue
                    if (kind != "captions" && kind != "subtitles") continue
                    val file = track.get("file")?.asString ?: continue
                    val label = track.get("label")?.asString ?: "Unknown"
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = playbackHeaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

// ── CUSTOM TRYEMBED EXTRACTOR ────────────────────────────────────────
open class AniDoorTryEmbed : ExtractorApi() {
    override val name = "TryEmbed"
    override val mainUrl = "https://tryembed.us.cc"
    override val requiresReferer = true

    data class TryEmbedResponse(
        @JsonProperty("providers") val providers: List<TryEmbedProvider>? = null
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

        // 1. Get the page
        val pageRes = app.get(url, headers = pageHeaders)
        val html = pageRes.text

        // Check if RAW_PAYLOAD is embedded in the page HTML
        val payloadRegex = Regex("""RAW_PAYLOAD\s*=\s*["']([^"']+)["']""")
        val payloadMatch = payloadRegex.find(html)
        val responseData = if (payloadMatch != null) {
            try {
                val base64Payload = payloadMatch.groupValues[1]
                val decodedBytes = Base64.decode(base64Payload, Base64.DEFAULT)
                val decodedJson = String(decodedBytes, Charsets.UTF_8)
                parseJson<TryEmbedResponse>(decodedJson)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val finalResponseData = responseData ?: run {
            // Fallback: Make AJAX API request to get stream data if RAW_PAYLOAD is not present
            val cookies = pageRes.okhttpResponse.headers("Set-Cookie")
            val authCookieHeader = cookies.firstOrNull { it.contains("tryembed_auth=") }
            val tryembedAuth = authCookieHeader?.substringBefore(";")?.substringAfter("tryembed_auth=")

            // Parse AniList ID and episode from URL path
            val pathParts = url.substringAfter("https://tryembed.us.cc/embed/anime/").split("/")
            if (pathParts.size < 3) return
            val alId = pathParts[0]
            val episode = pathParts[1]
            val audio = pathParts[2] // sub or dub

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

            val apiUrl = "$mainUrl/api/stream_data?id=$alId&episode=$episode&audio=$audio"
            val apiRes = try {
                app.get(apiUrl, headers = apiHeaders)
            } catch (e: Exception) {
                Log.e("TryEmbed", "API fetch failed: ${e.message}")
                return
            }

            try {
                parseJson<TryEmbedResponse>(apiRes.text)
            } catch (e: Exception) {
                null
            }
        } ?: return

        val providers = finalResponseData.providers ?: return

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        for (provider in providers) {
            if (provider.status != "ready") continue
            val qualities = provider.qualities ?: continue
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
    }
}

// ── CUSTOM VIDNEST EXTRACTOR ─────────────────────────────────────────
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
        if (pathParts.size < 4) return
        
        val alId = pathParts[1]
        val episode = pathParts[2]
        val audio = pathParts[3] // sub or dub

        val backend = if (pathParts[0] == "animepahe") "animepahe" else "hianime"

        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Accept" to "application/json"
        )

        val apiUrl = "https://new.vidnest.fun/$backend/anime/$alId/$episode/$audio"
        val responseText = try {
            app.get(apiUrl, headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("VidNest", "API request failed: ${e.message}")
            return
        }

        val json = try {
            parseJson<VidNestApiResponse>(responseText)
        } catch (e: Exception) {
            Log.e("VidNest", "JSON parsing failed: ${e.message}")
            return
        }

        if (json.data.isNullOrBlank()) return

        val decryptedJsonText = decrypt(json.data)
        val decryptedResponse = try {
            parseJson<VidNestDecryptedResponse>(decryptedJsonText)
        } catch (e: Exception) {
            Log.e("VidNest", "Failed to parse decrypted JSON: ${e.message}")
            return
        }

        if (!decryptedResponse.success) return

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        decryptedResponse.sources?.forEach { source ->
            val fileUrl = source.file ?: return@forEach
            val generated = M3u8Helper.generateM3u8(name, fileUrl, mainUrl, headers = playbackHeaders)
            if (generated.isNotEmpty()) {
                generated.forEach(callback)
            } else {
                callback(
                    newExtractorLink(name, name, fileUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.headers = playbackHeaders
                    }
                )
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
        @JsonProperty("tracks") val tracks: List<VidNestTrack>? = null
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
        val pageRes = app.get(url, headers = mapOf("Referer" to (referer ?: "https://anidoor.me/")))
        val html = pageRes.text

        val regex = Regex("""(https?://[^\s"']+(?:\.m3u8|\.mp4)[^\s"']*)""")
        regex.findAll(html).forEach { match ->
            val link = match.groupValues[1]
            if (link.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, link, mainUrl).forEach(callback)
            } else {
                callback(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        referer ?: "",
                        getQualityFromName(name),
                        link.contains(".m3u8")
                    )
                )
            }
        }
    }
}

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
        val pageRes = app.get(url, headers = mapOf("Referer" to (referer ?: "https://anidoor.me/")))
        val html = pageRes.text

        val regex = Regex("""(https?://[^\s"']+(?:\.m3u8|\.mp4)[^\s"']*)""")
        regex.findAll(html).forEach { match ->
            val link = match.groupValues[1]
            if (link.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, link, mainUrl).forEach(callback)
            } else {
                callback(
                    ExtractorLink(
                        name,
                        name,
                        link,
                        referer ?: "",
                        getQualityFromName(name),
                        link.contains(".m3u8")
                    )
                )
            }
        }
    }
}
