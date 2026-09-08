package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class RaghavKyren : MainAPI() {
    override var mainUrl = "https://kyren.moe"
    override var name = "Kyren"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val apiHeaders = mapOf(
        "User-Agent" to browserUA,
        "Accept" to "application/json",
        "Referer" to "$mainUrl/"
    )

    private val servers = listOf("megaplay", "megaplay-direct", "tryembed")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamResponse(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("sources") val sources: List<StreamSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<SubtitleInfo>? = null,
        @JsonProperty("error") val error: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamSource(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("isDub") val isDub: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleInfo(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("label") val label: String? = null
    )

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        title: String?,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lang = if (isDub) "dub" else "sub"
        val encodedTitle = URLEncoder.encode(title ?: "", "UTF-8")
        var found = false

        for (server in servers) {
            try {
                val streamUrl = "$mainUrl/api/stream/$anilistId/$episode?lang=$lang&title=$encodedTitle&server=$server"

                val res = app.get(streamUrl, headers = apiHeaders)
                val parsed = parseJson<StreamResponse>(res.text)

                if (parsed.ok != true) {
                    continue
                }

                val sources = parsed.sources ?: emptyList()
                if (sources.isEmpty()) {
                    continue
                }

                for (source in sources) {
                    val sourceUrl = source.url ?: continue
                    if (sourceUrl.isBlank()) continue

                    val providerName = source.provider ?: server
                    val langLabel = if (source.isDub == true || source.language == "dub") "Dub" else "Sub"
                    val quality = when (source.quality?.lowercase()) {
                        "1080p" -> Qualities.P1080.value
                        "720p" -> Qualities.P720.value
                        "480p" -> Qualities.P480.value
                        "360p" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    when (source.type) {
                        "hls" -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Kyren",
                                    name = "$providerName $langLabel",
                                    url = sourceUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf(
                                        "User-Agent" to browserUA,
                                        "Referer" to "$mainUrl/"
                                    )
                                }
                            )
                            found = true
                        }
                        else -> {
                            val loaded = loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
                            if (loaded) {
                                found = true
                            } else {
                            }
                        }
                    }
                }

                parsed.subtitles?.forEach { sub ->
                    val subUrl = sub.url ?: return@forEach
                    if (subUrl.isBlank()) return@forEach
                    val subLabel = sub.label ?: sub.lang ?: "English"
                    subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
                }
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[Kyren] server '$server' failed for anilist $anilistId ep$episode ($lang): ${e.message}")
            }
        }

        if (!found) {
        }
        return found
    }
}
