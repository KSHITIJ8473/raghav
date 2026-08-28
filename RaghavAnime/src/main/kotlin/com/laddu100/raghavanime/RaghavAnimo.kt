package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import java.net.URLEncoder

class RaghavAnimo : MainAPI() {
    override var mainUrl = "https://4animo.xyz"
    override var name = "Animo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://api.kryzox.xyz"
    private val cdnUrl = "https://cdn.4animo.xyz"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
    private val apiHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "recently-updated" to "Recently Updated",
        "recently-added" to "Recently Added",
        "top" to "Top Rated",
        "movie" to "Movies",
        "tv" to "TV Series",
        "ova" to "OVA",
        "ona" to "ONA",
        "special" to "Specials",
        "completed" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        Log.d("RaghavAnime", "[Animo] getMainPage page=$page name=${request.name}")
        return try {
            val url = "$apiUrl/anime/${request.data}?page=$page&limit=20"
            val items = parseAnimeList(app.get(url, headers = apiHeaders).text)
            Log.d("RaghavAnime", "[Animo] getMainPage ${request.name}: parsed ${items.size} items")
            val home = items.mapNotNull { it.toSearchResponse() }
            Log.d("RaghavAnime", "[Animo] getMainPage ${request.name}: ${home.size} results")
            newHomePageResponse(request.name, home, hasNext = home.size == 20)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Animo] getMainPage ${request.name} failed: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseAnimeList(text: String): List<AnimeSearchItem> = try {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) parseJson(text)
        else parseJson<SearchResponseData>(text).data ?: emptyList()
    } catch (e: Exception) {
        Log.e("RaghavAnime", "[Animo] parseAnimeList failed (len=${text.length}): ${e.message}")
        emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        if (query.isBlank()) return emptyList()
        Log.d("RaghavAnime", "[Animo] search: query='$query'")
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/anime/search?keyword=$encoded&page=1&limit=20"
            val resp = parseJson<SearchResponseData>(app.get(url, headers = apiHeaders).text)
            Log.d("RaghavAnime", "[Animo] search: ${resp.data?.size ?: 0} results")
            resp.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Animo] search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("animo") ?: mainUrl
        val animeId = url.substringAfterLast("/").toIntOrNull() ?: return null
        Log.d("RaghavAnime", "[Animo] load: animeId=$animeId")

        val anime = try {
            parseJson<AnimeDetails>(app.get("$apiUrl/anime/$animeId", headers = apiHeaders).text)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Animo] load: anime details fetch failed for $animeId: ${e.message}")
            return null
        }
        val title = anime.titles?.english ?: anime.titles?.romaji ?: return null

        val episodes = try {
            parseJson<EpisodesResponse>(
                app.get("$apiUrl/anime/$animeId/episodes", headers = apiHeaders).text
            ).data ?: emptyList()
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Animo] load: episodes fetch failed for $animeId: ${e.message}")
            emptyList()
        }
        Log.d("RaghavAnime", "[Animo] load: '$title' episodes=${episodes.size}")

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()

        episodes.forEach { ep ->
            val num = ep.number ?: return@forEach
            val epId = ep.id ?: return@forEach
            val epName = ep.titles?.en ?: ep.titles?.romaji ?: "Episode $num"
            val ani = ep.ani ?: ""
            if (ep.sub == true) {
                subEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, ani, "sub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
            if (ep.dub == true) {
                dubEps.add(newEpisode(EpisodeData(animeId, epId, ep.embed_id, num, ani, "dub").toJson()) {
                    this.episode = num
                    this.name = epName
                    this.posterUrl = ep.thumbnail
                })
            }
        }

        val tvType = when (anime.type?.uppercase()) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
        val year = anime.air?.start?.substringBefore("-")?.toIntOrNull()
        val finalType = if (tvType == TvType.AnimeMovie && dubEps.isNotEmpty()) TvType.Anime else tvType

        Log.d("RaghavAnime", "[Animo] load done: '$title' sub=${subEps.size} dub=${dubEps.size}")
        return newAnimeLoadResponse(title, url, finalType) {
            this.posterUrl = anime.images?.poster
            this.plot = anime.synopsis
            this.year = year
            this.tags = anime.genres
            if (anime.score != null) this.score = Score.from10(anime.score.toString())
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<EpisodeData>(data)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[Animo] loadLinks: failed to parse episode data: ${e.message}")
            return false
        }

        val type = epData.streamType
        Log.d("RaghavAnime", "[Animo] loadLinks: animeId=${epData.animeId} ep=${epData.episodeId} type=$type ani='${epData.ani}'")
        val query = "?k=1&autoPlay=1&skipIntro=1&skipOutro=1"

        val embeds = mutableListOf(
            "a-1" to "$cdnUrl/embed/a-1/${epData.episodeId}/$type$query",
            "s-1" to "$cdnUrl/embed/s-1/${epData.embedId ?: epData.episodeId}/$type$query"
        )
        if (epData.ani.isNotEmpty()) {
            embeds.add("hd-1" to "$cdnUrl/embed/hd-1/ani/${epData.ani}/$type$query")
            embeds.add("hd-2" to "$cdnUrl/embed/hd-2/ani/${epData.ani}/$type$query")
        }
        Log.d("RaghavAnime", "[Animo] loadLinks: ${embeds.size} embeds [${embeds.joinToString { it.first }}]")

        var found = false
        var subsAdded = false

        for ((key, embedUrl) in embeds) {
            try {
                Log.d("RaghavAnime", "[Animo] trying embed $key: ${embedUrl.take(120)}")
                if (!resolveSource(embedUrl, key, type, subsAdded, subtitleCallback, callback)) {
                    Log.w("RaghavAnime", "[Animo] embed $key resolved no links")
                    continue
                }
                Log.d("RaghavAnime", "[Animo] embed $key OK")
                found = true
                subsAdded = true
            } catch (e: Exception) {
                Log.d("Animo", "source $key failed: ${e.message}")
            }
        }

        Log.d("RaghavAnime", "[Animo] loadLinks done: found=$found")
        return found
    }

    private suspend fun resolveSource(
        embedUrl: String,
        key: String,
        type: String,
        subsAlreadyAdded: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedResp = app.get(embedUrl, headers = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.7"
        ), timeout = 15_000L)
        Log.d("RaghavAnime", "[Animo] resolveSource $key: embed code=${embedResp.code} len=${embedResp.text.length}")
        if (embedResp.code != 200) return false

        val token = Regex("getSources\\?t=([A-Za-z0-9_.-]+)")
            .find(embedResp.text)?.groupValues?.get(1) ?: return false
        Log.d("RaghavAnime", "[Animo] resolveSource $key: token=${token.take(40)}")

        val reqHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to embedUrl,
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty"
        )

        val sourcesResp = app.get("$cdnUrl/stream/getSources?t=$token", headers = reqHeaders, timeout = 15_000L)
        Log.d("RaghavAnime", "[Animo] resolveSource $key: sources code=${sourcesResp.code}")
        if (sourcesResp.code != 200) return false

        val sourcesText = sourcesResp.text
        Log.d("RaghavAnime", "[Animo] resolveSource $key: sources len=${sourcesText.length}")
        if (sourcesText.contains("invalid token")) return false

        val sources = parseJson<GetSourcesResponse>(sourcesText)
        val masterFile = sources.sources?.firstOrNull()?.file ?: return false
        val masterUrl = if (masterFile.startsWith("http")) masterFile else "$cdnUrl/${masterFile.removePrefix("/")}"
        Log.d("RaghavAnime", "[Animo] resolveSource $key: master=${masterUrl.take(120)} tracks=${sources.tracks?.size ?: 0}")

        val masterResp = app.get(masterUrl, headers = reqHeaders, timeout = 15_000L)
        Log.d("RaghavAnime", "[Animo] resolveSource $key: master code=${masterResp.code} len=${masterResp.text.length}")
        if (masterResp.code != 200 || !masterResp.text.trim().startsWith("#EXTM3U")) return false

        val playHeaders = mapOf(
            "User-Agent" to ua,
            "Accept" to "*/*",
            "Referer" to embedUrl,
            "Origin" to cdnUrl
        )

        val label = "$name $key ($type)"
        Log.d("RaghavAnime", "[Animo] link: $label ${masterUrl.take(120)}")
        callback.invoke(
            newExtractorLink(label, label, masterUrl, type = ExtractorLinkType.M3U8) {
                this.referer = embedUrl
                this.headers = playHeaders
            }
        )

        if (!subsAlreadyAdded) {
            Log.d("RaghavAnime", "[Animo] resolveSource $key: adding ${sources.tracks?.size ?: 0} subtitle tracks")
            sources.tracks?.forEach { t ->
                val file = t.file ?: return@forEach
                val subUrl = if (file.startsWith("http")) file else "$cdnUrl/${file.removePrefix("/")}"
                Log.d("RaghavAnime", "[Animo] subtitle: ${t.label ?: "English"} ${subUrl.take(120)}")
                subtitleCallback.invoke(newSubtitleFile(t.label ?: "English", subUrl) {
                    this.headers = playHeaders
                })
            }
        }

        return true
    }

    private fun AnimeSearchItem.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = titles?.english ?: titles?.romaji ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
            this.posterUrl = images?.poster
            addDubStatus(dubExist = (dub_count ?: 0) > 0, subExist = (sub_count ?: 0) > 0)
        }
    }

    data class EpisodeData(
        val animeId: Int,
        val episodeId: Int,
        val embedId: String?,
        val episodeNum: Int,
        val ani: String,
        val streamType: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeSearchItem(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val episodes_count: Int? = null,
        val sub_count: Int? = null,
        val dub_count: Int? = null,
        val score: Double? = null,
        val season_year: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(
        val success: Boolean? = null,
        val data: List<AnimeSearchItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Images(val poster: String? = null, val banner: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Titles(val romaji: String? = null, val english: String? = null, val native: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetails(
        val id: Int? = null,
        val slug: String? = null,
        val titles: Titles? = null,
        val synopsis: String? = null,
        val images: Images? = null,
        val type: String? = null,
        val status: String? = null,
        val score: Double? = null,
        val rating: String? = null,
        val air: Air? = null,
        val genres: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Air(val start: String? = null, val end: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val anime_id: Int? = null,
        val total: Int? = null,
        val sub_count: String? = null,
        val dub_count: String? = null,
        val data: List<EpisodeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeItem(
        val id: Int? = null,
        val number: Int? = null,
        val titles: EpisodeTitles? = null,
        val filler: Boolean? = null,
        val rating: String? = null,
        val thumbnail: String? = null,
        val sub: Boolean? = null,
        val dub: Boolean? = null,
        @JsonProperty("embed_id") val embed_id: String? = null,
        val ani: String? = null,
        val mal: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeTitles(val en: String? = null, val ja: String? = null, val romaji: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GetSourcesResponse(
        val sources: List<MegaSource>? = null,
        val tracks: List<MegaTrack>? = null,
        val encrypted: Boolean? = null,
        val server: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaSource(val file: String? = null, val type: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
        val default: Boolean? = null
    )
}
