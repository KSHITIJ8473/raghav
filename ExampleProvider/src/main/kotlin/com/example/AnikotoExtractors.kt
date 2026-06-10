package com.example

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.parsedSafe

class Vidwish : MegaPlay() {
    override val name = "Vidwish"
    override val mainUrl = "https://vidwish.live"
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
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        val ajaxHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        // URL from anikoto looks like: https://megaplay.buzz/stream/s-2/2143/sub
        val streamId = Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
            ?: app.get(url, referer = referer ?: mainUrl).document
                .selectFirst("#megaplay-player")
                ?.attr("data-realid")
                ?.takeIf { it.isNotBlank() }
            ?: return

        val response = runCatching {
            app.get(
                "$mainUrl/stream/getSources?id=$streamId",
                headers = ajaxHeaders,
                referer = referer ?: mainUrl
            ).parsedSafe<MegaPlayResponse>()
        }.getOrNull() ?: return

        val m3u8 = response.sources?.file
        if (m3u8.isNullOrBlank()) {
            Log.e("MegaPlay", "No m3u8 for id=$streamId")
            return
        }

        M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders).forEach(callback)

        response.tracks?.forEach { track ->
            if (track.kind != "captions" && track.kind != "subtitles") return@forEach
            val file = track.file ?: return@forEach
            subtitleCallback(
                newSubtitleFile(track.label ?: "Unknown", file) {
                    this.headers = playbackHeaders
                }
            )
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
