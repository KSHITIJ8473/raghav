package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.api.Log

class ReanimeExtractor : ExtractorApi() {
    override val name = "Re:ANIME"
    override val mainUrl = "https://reanime.to"
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
                script = """document.querySelector('button,[role="button"],video,.jw-icon-display,.vds-play-button')?.click();""",
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
                else -> {
                    // try fetching embedded sources from the page
                    val html = app.get(url, referer = referer ?: mainUrl).text

                    val m3u8Regex = Regex("""(?:file|src|source|url)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""")
                    m3u8Regex.findAll(html).forEach { match ->
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = match.groupValues[1],
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = headers
                            }
                        )
                    }

                    val mp4Regex = Regex("""(?:file|src|source|url)\s*[:=]\s*["'](https?://[^"']*\.mp4[^"']*)["']""")
                    mp4Regex.findAll(html).forEach { match ->
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = match.groupValues[1],
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = headers
                            }
                        )
                    }
                }
            }
        }.onFailure { error ->
            Log.e(name, "Extraction failed: ${error.message}")
        }
    }
}
