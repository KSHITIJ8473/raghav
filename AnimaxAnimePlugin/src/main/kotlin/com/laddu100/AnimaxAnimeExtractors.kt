package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class AnimaxAnimeEmbedExtractor : ExtractorApi() {
    override val name = "AnimaxAnimeEmbed"
    override val mainUrl = "https://animaxanime.vercel.app"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: mainUrl
        extractSubtitleFromUrl(url)?.let { subtitleUrl ->
            subtitleCallback(SubtitleFile("English", subtitleUrl))
        }

        if (url.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(
                name,
                url,
                realReferer,
                headers = mapOf("Referer" to realReferer)
            ).forEach(callback)
            return
        }

        val response = app.get(url, referer = realReferer)
        val body = response.text

        val hlsMatches = Regex("""https?://[^\s'\"]+\.m3u8[^\s'\"]*""").findAll(body).map { it.value }.distinct()
        for (match in hlsMatches) {
            M3u8Helper.generateM3u8(
                name,
                match,
                realReferer,
                headers = mapOf("Referer" to url)
            ).forEach(callback)
        }

        val mp4Matches = Regex("""https?://[^\s'\"]+\.mp4[^\s'\"]*""").findAll(body).map { it.value }.distinct()
        for (match in mp4Matches) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = match,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = url
                    this.headers = mapOf("Referer" to url)
                    quality = getQualityFromName(match)
                }
            )
        }
    }

    private fun extractSubtitleFromUrl(url: String): String? {
        val match = Regex("""(?:sub|caption)_1=([^&]+)""").find(url) ?: return null
        return runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
    }
}
