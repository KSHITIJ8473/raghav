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

            val rawText = app.get("$mainUrl/stream/getSources?id=$id", headers = headers).text
            val m3u8Url = extractM3u8FromResponse(rawText) ?: return@runCatching

            generateM3u8(name, m3u8Url, mainUrl, headers = headers).forEach(callback)

            Regex(""""file"\s*:\s*"([^"]+\.vtt[^"]*)"""").findAll(rawText).forEach { m ->
                subtitleCallback(newSubtitleFile("English", m.groupValues[1]) {
                    this.headers = mapOf("Referer" to "$mainUrl/")
                })
            }
        }.onFailure { error ->
            Log.e(name, "extraction failed: ${error.message}")
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

    private fun extractM3u8FromResponse(raw: String): String? {
        val parsed = try {
            com.lagradost.cloudstream3.utils.AppUtils.parseJson<Response>(raw)
        } catch (_: Exception) { null }

        if (parsed?.sources?.file != null && parsed.sources.file!!.startsWith("http")) {
            return parsed.sources.file
        }

        Regex(""""(links\.hls\d+)"""").findAll(raw).toList().reversed().forEach { m ->
            val key = m.groupValues[1]
            val parts = key.split(".")
            if (parts.size == 2) {
                val obj = parts[0]
                val prop = parts[1]
                val pattern = """"$obj"\s*:\s*\{[^}]*"$prop"\s*:\s*"([^"]+)""""
                Regex(pattern).find(raw)?.let { return it.groupValues[1] }
            }
        }

        Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""").find(raw)?.let { return it.groupValues[1] }
        Regex("""(https?://[^\s"\\<>]+\.m3u8[^\s"\\<>]*)""").find(raw)?.let { return it.groupValues[1] }

        return null
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
                resolved.contains(".mp4", ignoreCase = true) && !resolved.contains(".txt") -> {
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
            Log.e(name, "extraction failed: ${error.message}")
        }
    }
}
