package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink

class VidTubeExtractor(private val sourceName: String = "VidTube") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://vidtube.site"
    override val requiresReferer = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VTResponse(
        @JsonProperty("sources") val sources: VTSources? = null,
        @JsonProperty("tracks") val tracks: List<VTTrack>? = null
    )

    data class VTSources(@JsonProperty("file") val file: String? = null)

    data class VTTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, headers = mapOf("Referer" to "$mainUrl/")).document
            val id = doc.selectFirst("[data-id]")?.attr("data-id")?.takeIf { it.isNotBlank() }
                ?: Regex("""data-id=["']([^"']+)["']""").find(doc.html())?.groupValues?.get(1)
            if (id.isNullOrBlank()) {
                return
            }

            // the embed url itself is the required referer for the ajax call
            val ajaxHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Accept" to "*/*"
            )

            var payload: VTResponse? = null
            for (endpoint in listOf("getSourcesNew", "getSources")) {
                try {
                    val text = app.get("$mainUrl/stream/$endpoint?id=$id", headers = ajaxHeaders).text
                    payload = parseJson(text)
                    if (payload?.sources?.file != null) {
                        break
                    }
                } catch (e: Exception) {
                }
            }

            val m3u8 = payload?.sources?.file
            if (m3u8.isNullOrBlank()) {
                return
            }

            val playHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
            generateM3u8(name, m3u8, mainUrl, headers = playHeaders).forEach(callback)

            payload.tracks?.forEach { track ->
                val file = track.file ?: return@forEach
                if (track.kind == "captions" || track.kind == "subtitles") {
                    subtitleCallback(
                        newSubtitleFile(track.label ?: "English", file) {
                            this.headers = playHeaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[VidTube] extraction failed for ${url.take(120)}: ${e.message}")
        }
    }
}
