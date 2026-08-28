package com.laddu100.raghavanime

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MiruroVidWish(sourceName: String = "VidWish") : MiruroMegaPlay(sourceName) {
    override val mainUrl = "https://vidwish.live"
}

open class MiruroMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[Miruro][${name}] getUrl: url=${url.take(120)} referer=$referer")
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )

        runCatching {
            val document = app.get(url, headers = headers).document
            Log.d("RaghavAnime", "[Miruro][${name}] embed page fetched (htmlLen=${document.html().length}), extracting stream id")
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-realid=["'](\d+)""").find(document.html())?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return@runCatching
            Log.d("RaghavAnime", "[Miruro][${name}] streamId=$id")
            val response = app.get("$mainUrl/stream/getSources?id=$id", headers = headers).parsedSafe<Response>()
                ?: return@runCatching
            Log.d("RaghavAnime", "[Miruro][${name}] getSources parsed: hasSources=${response.sources != null} tracks=${response.tracks.size}")
            val m3u8 = response.sources?.file ?: return@runCatching
            Log.d("RaghavAnime", "[Miruro][${name}] m3u8=${m3u8.take(120)}")

            Log.d("RaghavAnime", "[Miruro][${name}] generating M3u8 links from master playlist")
            generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                Log.d("RaghavAnime", "[Miruro][${name}] track: kind=${track.kind} label=${track.label} url=${file.take(120)}")
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(newSubtitleFile(track.label ?: "Subtitle", file) {
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    })
                }
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Miruro][${name}] primary extraction failed: ${error.message}, trying WebViewResolver fallback")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""\.m3u8"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                script = """document.querySelector('.jw-icon-display')?.click();""",
                useOkhttp = false,
                timeout = 30_000L
            )
            val m3u8 = app.get(url, referer = mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Miruro][${name}] WebViewResolver resolved: ${m3u8.take(120)}")
            if (m3u8.contains(".m3u8")) {
                Log.d("RaghavAnime", "[Miruro][${name}] emitting M3u8 links from WebView-resolved url")
                generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
            }
        }
    }

    data class Response(
        @JsonProperty("sources") val sources: Sources? = null,
        @JsonProperty("tracks") val tracks: List<Track> = emptyList()
    )

    data class Sources(@JsonProperty("file") val file: String? = null)

    data class Track(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
}

class MiruroWebView(private val sourceName: String, private val baseUrl: String) : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = baseUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[Miruro][WebView] getUrl: source=$sourceName url=${url.take(120)} referer=${referer?.take(120)}")
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 30_000L
            )
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[Miruro][WebView] resolution result: ${resolved.take(120)}")
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Miruro][WebView] m3u8 resolved, generating M3u8 links")
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[Miruro][WebView] mp4 resolved, emitting direct link")
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
                    Log.d("RaghavAnime", "[Miruro][WebView] emit link: $name url=${resolved.take(120)}")
                }
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[Miruro][WebView] getUrl failed: ${error.message}")
        }
    }
}
