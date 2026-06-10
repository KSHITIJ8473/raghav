package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class Vidwish : MegaPlay() {
    override var mainUrl = "https://vidwish.live"
    override val name = "Vidwish"
}

open class MegaPlay : ExtractorApi() {
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
            "Accept-Language" to "en-US,en;q=0.5",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        val ajaxHeaders = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
        )

        val embedHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "$mainUrl/"),
        )

        try {
            val id = Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
                ?: app.get(url, headers = embedHeaders, referer = referer)
                    .document
                    .selectFirst("#megaplay-player")
                    ?.attr("data-realid")
                    ?.takeIf { it.isNotBlank() }
                ?: app.get(url, headers = embedHeaders, referer = referer)
                    .document
                    .selectFirst("#megaplay-player")
                    ?.attr("data-id")
                    ?.takeIf { it.isNotBlank() }
                ?: return

            val response = app.get(
                "$mainUrl/stream/getSources?id=$id",
                headers = ajaxHeaders,
                referer = referer ?: "$mainUrl/"
            ).parsedSafe<MegaPlayResponse>() ?: return

            val m3u8 = response.sources?.file ?: throw Exception("No m3u8 in response")

            M3u8Helper.generateM3u8(
                name,
                m3u8,
                mainUrl,
                headers = playbackHeaders
            ).forEach(callback)

            response.tracks?.forEach { track ->
                if (track.kind != "captions" && track.kind != "subtitles") return@forEach
                val file = track.file ?: return@forEach
                subtitleCallback(
                    newSubtitleFile(track.label ?: "Unknown", file) {
                        this.headers = playbackHeaders
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("MegaPlay", "Primary extraction failed: ${e.message}")
            fallbackWebView(url, referer, playbackHeaders, subtitleCallback, callback)
        }
    }

    private suspend fun fallbackWebView(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val clickPlay = """
            (() => {
                const btn = document.querySelector('.jw-icon-display.jw-button-color.jw-reset');
                if (btn) { btn.click(); return "clicked"; }
                return "button not found";
            })();
        """.trimIndent()

        try {
            val m3u8Resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = clickPlay,
                useOkhttp = false,
                timeout = 15_000L
            )

            val m3u8Url = app.get(
                url = url,
                referer = referer ?: "$mainUrl/",
                interceptor = m3u8Resolver
            ).url

            if (m3u8Url.isNotBlank()) {
                M3u8Helper.generateM3u8(name, m3u8Url, mainUrl, headers = headers).forEach(callback)
            }
        } catch (ex: Exception) {
            Log.e("MegaPlay", "WebView fallback failed: ${ex.message}")
        }
    }

    data class MegaPlayResponse(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track>? = null,
    )

    data class Sources(
        @JsonProperty("file") val file: String? = null,
    )

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )
}

