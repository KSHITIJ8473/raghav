package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class RaghavAniChan : MainAPI() {
    override var mainUrl = "https://anichan.net"
    override var name = "AniChan"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val browserUA = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("dubAvailable") val dubAvailable: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServersResponse(
        @JsonProperty("servers") val servers: List<Server>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Server(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("subType") val subType: String? = null,
        @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Subtitle(
        @JsonProperty("lang") val lang: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class EpisodeData(val anilistId: Int, val episode: Int, val isDub: Boolean)

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val category = if (isDub) "dub" else "sub"
        Log.d("RaghavAnime", "[AniChan] loadLinksByAnilistId: anilistId=$anilistId ep=$episode category=$category")
        return try {
            val resp = app.get(
                "$mainUrl/api/watch/servers?anilistId=$anilistId&ep=$episode&category=$category",
                headers = mapOf("User-Agent" to browserUA, "Referer" to "$mainUrl/anime/$anilistId"),
                timeout = 15_000L
            ).text
            Log.d("RaghavAnime", "[AniChan] servers response len=${resp.length}")
            val servers = parseJson<ServersResponse>(resp).servers ?: emptyList()
            Log.d("RaghavAnime", "[AniChan] parsed ${servers.size} servers")
            var found = false

            for (server in servers) {
                Log.d("RaghavAnime", "[AniChan] server: name=${server.name} label=${server.label} subType=${server.subType}")
                val stream = server.stream ?: continue
                val fullStream = if (stream.startsWith("/")) "$mainUrl$stream" else stream
                val rawLabel = server.label ?: server.name ?: "AniChan"
                val label = rawLabel.replace("★ ", "").trim()
                val subType = server.subType ?: "soft"
                val isHardsub = subType == "hard"

                val displayLabel = when {
                    isHardsub -> "$label (Hardsub)"
                    isDub -> "$label (Dub)"
                    else -> label
                }

                Log.d("RaghavAnime", "[AniChan] link: $displayLabel ${fullStream.take(120)}")
                callback.invoke(newExtractorLink(
                    "AniChan",
                    displayLabel,
                    fullStream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to browserUA,
                        "Referer" to "$mainUrl/anime/$anilistId"
                    )
                })
                found = true

                server.subtitles?.forEach { sub ->
                    val subUrl = sub.url ?: return@forEach
                    val fullSubUrl = if (subUrl.startsWith("/")) "$mainUrl$subUrl" else subUrl
                    Log.d("RaghavAnime", "[AniChan] subtitle: ${sub.lang ?: "English"} ${fullSubUrl.take(120)}")
                    subtitleCallback.invoke(SubtitleFile(sub.lang ?: "English", fullSubUrl))
                }
            }
            Log.d("RaghavAnime", "[AniChan] loadLinksByAnilistId done: found=$found")
            found
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniChan] FAILED: ${e.message}")
            false
        }
    }
}
