package com.laddu100

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

        // 1. Get the page to receive tryembed_auth cookie
        val pageRes = app.get(url, headers = pageHeaders)
        val cookies = pageRes.okhttpResponse.headers("Set-Cookie")
        val authCookieHeader = cookies.firstOrNull { it.contains("tryembed_auth=") } ?: return
        val tryembedAuth = authCookieHeader.substringBefore(";").substringAfter("tryembed_auth=")

        // 2. Parse AniList ID and episode from URL path
        // URL Format: https://tryembed.us.cc/embed/anime/{al}/{e}/{sub/dub}
        val pathParts = url.substringAfter("https://tryembed.us.cc/embed/anime/").split("/")
        if (pathParts.size < 3) return
        val alId = pathParts[0]
        val episode = pathParts[1]
        val audio = pathParts[2] // sub or dub

        // 3. Make AJAX API request to get stream data
        val apiHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to url,
            "Origin" to mainUrl,
            "Cookie" to "tryembed_auth=$tryembedAuth",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Dest" to "empty",
            "Accept" to "*/*"
        )

        val apiUrl = "$mainUrl/api/stream_data?id=$alId&episode=$episode&audio=$audio"
        val apiRes = try {
            app.get(apiUrl, headers = apiHeaders)
        } catch (e: Exception) {
            Log.e("TryEmbed", "API fetch failed: ${e.message}")
            return
        }

        val responseData = parseJson<TryEmbedResponse>(apiRes.text)
        val providers = responseData.providers ?: return

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
                        "$mainUrl/",
                        ExtractorLinkType.M3U8,
                        headers = playbackHeaders
                    )
                )
            }
        }
    }
}
