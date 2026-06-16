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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class AnizenVidWish(sourceName: String = "VidWish") : AnizenMegaPlay(sourceName) {
    override val mainUrl = "https://vidwish.live"
}

class AnizenVidTube(sourceName: String = "VidPlay") : AnizenMegaPlay(sourceName) {
    override val mainUrl = "https://vidtube.site"
}

class AnizenWebView(private val sourceName: String, private val baseUrl: String) : ExtractorApi() {
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
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
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

open class AnizenMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Headers for fetching the HTML and API Data
        val ajaxHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/"
        )
        
        // FIX: Clean, standard headers for the video player. NO ORIGIN HEADER.
        // CDNs often block requests with an Origin header.
        val videoHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to "https://megaplay.buzz/"
        )

        runCatching {
            val document = app.get(url, headers = ajaxHeaders).document
            val html = document.html()
            
            val id = document.selectFirst("#megaplay-player")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["'](\d+)""").find(html)?.groupValues?.get(1)
                ?: document.selectFirst("#megaplay-player")?.attr("data-realid")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-realid=["'](\d+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""/stream/s-\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: return@runCatching

            // Scrape the security tokens from the settings object in the HTML
            val cid = Regex("""cid\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""
            val cidu = Regex("""cidu\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""
            val type = Regex("""type\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""

            // Build the API URL with the required tokens
            var apiUrl = "$mainUrl/stream/getSources?id=$id"
            if (cid.isNotEmpty()) apiUrl += "&cid=$cid"
            if (cidu.isNotEmpty()) apiUrl += "&cidu=$cidu"
            if (type.isNotEmpty()) apiUrl += "&type=$type"

            val response = app.get(apiUrl, headers = ajaxHeaders).parsedSafe<Response>()
                ?: return@runCatching
            val m3u8 = response.sources?.file ?: return@runCatching

            // Pass clean videoHeaders to generateM3u8 to fix Remote Error 2004
            generateM3u8(name, m3u8, "https://megaplay.buzz/", headers = videoHeaders).forEach(callback)
            
            response.tracks.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(newSubtitleFile(track.label ?: "Subtitle", file) {
                        this.headers = videoHeaders
                    })
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
                generateM3u8(name, m3u8, "https://megaplay.buzz/", headers = videoHeaders).forEach(callback)
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

open class AnizenAbyss : ExtractorApi() {
    override var name = "Abyss"
    override var mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to "https://playhydrax.com",
            "Referer" to "https://playhydrax.com/"
        )
        val document = app.get(url, headers = headers).document
        val scripts = document.select("script").joinToString("\n") { it.data() }
        val encrypted = Regex("""const\s+datas\s*=\s*"([^"]*)"""").find(scripts)?.groupValues?.get(1)
            ?: Regex("""datas\s*=\s*"([^"]*)"""").find(scripts)?.groupValues?.get(1)
            ?: return

        val decrypted = app.post(
            url = "https://enc-dec.app/api/dec-abyss",
            headers = headers,
            requestBody = """{"text":"$encrypted"}""".toRequestBody("application/json".toMediaType())
        ).parsedSafe<AbyssResponse>()?.result ?: return

        decrypted.sources.filter { it.status }.forEach { source ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name [${source.codec.uppercase()}]",
                    url = source.url,
                    type = INFER_TYPE
                ) {
                    quality = getQualityFromName(source.type)
                    this.headers = mapOf("Referer" to "https://playhydrax.com/")
                }
            )
        }
    }

    data class AbyssResponse(val status: Long, val result: Result)
    data class Result(val sources: List<AbyssSource>)
    data class AbyssSource(
        val url: String,
        val size: Long = 0,
        val type: String = "",
        val codec: String = "mp4",
        val status: Boolean = true
    )
}

class AnizenRyzex : AnizenAbyss() {
    override var name = "Ryzex"
    override var mainUrl = "https://ryzex.top"
}
