package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

// vidtube.site embeds: data-id on the player element -> /stream/getSourcesNew
// with the audio type from the embed path, playback needs the vidtube referer
class VidTubeExtractor(private val sourceName: String = "VidTube") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://vidtube.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val stream = MegaPlayHelper.resolveStream(url, referer ?: "$mainUrl/", "VidTube")
        if (stream == null) {
            Log.d("RaghavAnime", "[VidTube] no stream for ${url.take(120)}")
            return
        }
        MegaPlayHelper.emitLinks(
            name, name, stream.m3u8, "$mainUrl/",
            stream.subtitles, subtitleCallback, callback
        )
    }
}
