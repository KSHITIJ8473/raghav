package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

// ─── MegaPlay Extractor ─────────────────────────────────────────────────────

class AnivexaVidWish(sourceName: String = "VidWish") : AnivexaMegaPlay(sourceName) {
    override val mainUrl = "https://vidwish.live"
}

class AnivexaVidTube(sourceName: String = "VidTube") : AnivexaMegaPlay(sourceName) {
    override val mainUrl = "https://vidtube.site"
}

open class AnivexaMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
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

            generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)

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
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = """document.querySelector('.jw-icon-display')?.click();""",
                useOkhttp = false,
                timeout = 15_000L
            )
            val m3u8 = app.get(url, referer = mainUrl, interceptor = resolver).url
            if (m3u8.contains(".m3u8")) {
                generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
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

// ─── WebView Fallback Extractor ──────────────────────────────────────────────

class AnivexaWebView(
    private val sourceName: String,
    private val baseUrl: String
) : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = baseUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(
                url,
                referer = referer ?: mainUrl,
                interceptor = resolver
            ).url
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = resolved,
                            type = INFER_TYPE
                        ) {
                            quality = getQualityFromName(resolved)
                            this.headers = headers
                        }
                    )
                }
            }
        }.onFailure { error ->
            Log.e(name, "WebView extraction failed: ${error.message}")
        }
    }
}