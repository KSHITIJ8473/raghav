package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class AniWavesEchoVideo : ExtractorApi() {
    override val name = "EchoVideo"
    override val mainUrl = "https://play.echovideo.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer ?: "https://aniwaves.ru/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
        )

        val html = response.text

        // EchoVideo embeds typically contain HLS sources in the page
        // Look for m3u8 URLs in the page source
        val m3u8Regex = Regex("""(?:file|src|source|url)\s*[:=]\s*['"](https?://[^'"]*\.m3u8[^'"]*)['""]""")
        val m3u8Matches = m3u8Regex.findAll(html)

        for (match in m3u8Matches) {
            val m3u8Url = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "Referer" to (referer ?: mainUrl),
                        "Origin" to mainUrl
                    )
                }
            )
        }

        // Also look for MP4 direct links
        val mp4Regex = Regex("""(?:file|src|source|url)\s*[:=]\s*['"](https?://[^'"]*\.mp4[^'"]*)['""]""")
        val mp4Matches = mp4Regex.findAll(html)

        for (match in mp4Matches) {
            val mp4Url = match.groupValues[1]
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "Referer" to (referer ?: mainUrl)
                    )
                }
            )
        }

        // Look for JSON-embedded sources (common in modern players)
        val jsonSourceRegex = Regex(""""sources"\s*:\s*\[([^\]]+)\]""")
        val jsonMatch = jsonSourceRegex.find(html)
        if (jsonMatch != null) {
            val sourcesJson = jsonMatch.groupValues[1]
            val urlRegex = Regex(""""(?:file|url|src)"\s*:\s*"([^"]+)"""")
            for (urlMatch in urlRegex.findAll(sourcesJson)) {
                val sourceUrl = urlMatch.groupValues[1].replace("\\/", "/")
                val type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = sourceUrl,
                        type = type
                    ) {
                        this.headers = mapOf(
                            "Referer" to (referer ?: mainUrl)
                        )
                    }
                )
            }
        }
    }
}

class AniWavesFilemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Filemoon-type extractors pack the video URL in a packed/eval JS
        val response = app.get(url, referer = referer ?: "https://aniwaves.ru/")
        val html = response.text

        // Look for eval(function(p,a,c,k,e,d) pattern
        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packed = packedRegex.find(html)?.value

        if (packed != null) {
            // Try to find the m3u8 URL inside the packed content
            // The packed script usually contains file:"https://...m3u8"
            val unpackedUrls = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").findAll(packed)
            for (match in unpackedUrls) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = match.value,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to url)
                    }
                )
            }
        }

        // Also look for direct m3u8 in the HTML itself
        val directM3u8 = Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""").findAll(html)
        for (match in directM3u8) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to url)
                }
            )
        }
    }
}

class AniWavesMyVidPlay : ExtractorApi() {
    override val name = "MyVidPlay"
    override val mainUrl = "https://myvidplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "https://aniwaves.ru/")
        val html = response.text

        // Similar to Filemoon, look for packed JS or direct sources
        val m3u8Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""")
        for (match in m3u8Regex.findAll(html)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to url)
                }
            )
        }

        // Look for JSON sources
        val jsonSourceRegex = Regex(""""sources"\s*:\s*\[([^\]]+)\]""")
        val jsonMatch = jsonSourceRegex.find(html)
        if (jsonMatch != null) {
            val urlRegex = Regex(""""(?:file|url)"\s*:\s*"([^"]+)"""")
            for (urlMatch in urlRegex.findAll(jsonMatch.groupValues[1])) {
                val sourceUrl = urlMatch.groupValues[1].replace("\\/", "/")
                val type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = sourceUrl,
                        type = type
                    ) {
                        this.headers = mapOf("Referer" to url)
                    }
                )
            }
        }
    }
}
