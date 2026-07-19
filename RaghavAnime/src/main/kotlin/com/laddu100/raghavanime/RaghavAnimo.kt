package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class RaghavAnimo : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "Animo"
    override var lang = "en"
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.kryzox.xyz"
    private val cdnUrl get() = mainUrl.replace("://", "://cdn.")
    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val text = app.get("$apiUrl/anime/search?keyword=$encoded", headers = apiHeaders).text
            val resp = parseJson<SearchResp>(text)
            resp.data?.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val title = item.titles?.english ?: item.titles?.romaji ?: return@mapNotNull null
                newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = item.images?.poster
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: return null
        return try {
            val text = app.get("$apiUrl/anime/$animeId", headers = apiHeaders).text
            val anime = parseJson<AnimeDetail>(text)
            val title = anime.titles?.english ?: anime.titles?.romaji ?: return null

            val epText = app.get("$apiUrl/anime/$animeId/episodes", headers = apiHeaders).text
            val epResp = parseJson<EpisodesResp>(epText)
            val episodes = epResp.data ?: emptyList()

            val subEps = mutableListOf<Episode>()
            val dubEps = mutableListOf<Episode>()

            episodes.forEach { ep ->
                val num = ep.number ?: return@forEach
                val epName = ep.titles?.en ?: ep.titles?.romaji ?: "Episode $num"
                if (ep.sub == true) {
                    val epData = AnimoEpData(animeId, num, false).toJson()
                    subEps.add(newEpisode(epData) { this.episode = num; this.name = epName })
                }
                if (ep.dub == true) {
                    val epData = AnimoEpData(animeId, num, true).toJson()
                    dubEps.add(newEpisode(epData) { this.episode = num; this.name = epName })
                }
            }

            val tvType = when (anime.type?.uppercase()) {
                "MOVIE" -> if (dubEps.isNotEmpty()) TvType.Anime else TvType.AnimeMovie
                "OVA", "ONA", "SPECIAL" -> TvType.OVA
                else -> TvType.Anime
            }

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = anime.images?.poster
                this.plot = anime.synopsis
                this.year = anime.air?.year
                if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
                if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try { parseJson<AnimoEpData>(data) } catch (e: Exception) { return false }
        val playHeaders = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to ua)
        var found = false

        val type = if (epData.isDub) "dub" else "sub"
        for (hd in 1..4) {
            try {
                val sourcesUrl = "$cdnUrl/stream/getSources?hd=$hd&id=${epData.animeId}&episode=${epData.episodeNum}&type=$type"
                val response = app.get(sourcesUrl, headers = mapOf(
                    "User-Agent" to ua,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "$cdnUrl/embed/hd-$hd/${epData.animeId}/${epData.episodeNum}/$type",
                    "Origin" to cdnUrl
                ), timeout = 30_000L)
                val text = response.text
                if (response.code != 200 || text.contains("Just a moment")) continue
                val sources = parseJson<GetSourcesResp>(text)
                sources.sources?.forEach { s ->
                    val file = s.file ?: return@forEach
                    val streamUrl = if (file.startsWith("http")) file else "$cdnUrl$file"
                    val label = "$name HD$hd ($type)"
                    if (s.type == "hls" || streamUrl.contains(".m3u8")) {
                        try {
                            M3u8Helper.generateM3u8(label, streamUrl, "$cdnUrl/", headers = playHeaders).forEach(callback)
                        } catch (_: Exception) {
                            callback.invoke(newExtractorLink(label, label, streamUrl, type = ExtractorLinkType.M3U8) {
                                this.referer = "$cdnUrl/"; this.headers = playHeaders
                            })
                        }
                        found = true
                    }
                }
                if (hd == 1) {
                    sources.tracks?.forEach { t ->
                        val file = t.file ?: return@forEach
                        val subUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                        subtitleCallback.invoke(newSubtitleFile(t.label ?: "English", subUrl) {
                            this.headers = playHeaders
                        })
                    }
                }
            } catch (e: Exception) { }
            if (found) break
        }
        return found
    }

    data class AnimoEpData(val animeId: Int, val episodeNum: Int, val isDub: Boolean)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResp(val success: Boolean? = null, val data: List<SearchItem>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchItem(val id: Int? = null, val titles: Title? = null, val images: Image? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Title(val romaji: String? = null, val english: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Image(val poster: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetail(val titles: Title? = null, val synopsis: String? = null, val type: String? = null, val air: Air? = null, val images: Image? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Air(val year: Int? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResp(val data: List<EpItem>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpItem(val number: Int? = null, val titles: EpTitle? = null, val sub: Boolean? = null, val dub: Boolean? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpTitle(val en: String? = null, val romaji: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GetSourcesResp(val sources: List<Source>? = null, val tracks: List<Track>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(val file: String? = null, val type: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(val file: String? = null, val label: String? = null)
}
