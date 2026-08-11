package com.csksy.anichan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class AniChanProvider : MainAPI() {
    override var mainUrl = "https://anichan.net"
    override var name = "AniChan"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "season" to "Airing This Season",
        "popular" to "All-Time Popular"
    )

    private val anilistUrl = "https://graphql.anilist.co"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val sort = when (request.data) {
                "trending" -> "TRENDING_DESC"
                "season" -> "POPULARITY_DESC"
                "popular" -> "POPULARITY_DESC"
                else -> "TRENDING_DESC"
            }

            val (season, seasonYear) = if (request.data == "season") {
                val cal = java.util.Calendar.getInstance()
                val s = when (cal.get(java.util.Calendar.MONTH)) {
                    in 0..2 -> "WINTER"
                    in 3..5 -> "SPRING"
                    in 6..8 -> "SUMMER"
                    in 9..11 -> "FALL"
                    else -> "WINTER"
                }
                s to cal.get(java.util.Calendar.YEAR)
            } else null to null

            val query = if (season != null) {
                "{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: [$sort], season: $season, seasonYear: $seasonYear) { id title { english romaji } coverImage { large extraLarge } format episodes seasonYear } } }"
            } else {
                "{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: [$sort]) { id title { english romaji } coverImage { large extraLarge } format episodes seasonYear } } }"
            }

            val response = app.post(anilistUrl, json = mapOf("query" to query), timeout = 15_000L).text
            val data = parseJson<AniListPage>(response)
            val media = data.data?.page?.media ?: emptyList()

            val items = media.mapNotNull { m ->
                val title = m.title?.english ?: m.title?.romaji ?: return@mapNotNull null
                val id = m.id ?: return@mapNotNull null
                val poster = m.coverImage?.extraLarge ?: m.coverImage?.large ?: ""
                newAnimeSearchResponse(title, "/anime/$id", TvType.Anime) {
                    this.posterUrl = poster
                    this.year = m.seasonYear
                }
            }

            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.e("AniChan", "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val response = app.get(
                "$mainUrl/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
                headers = mapOf("User-Agent" to browserUA),
                timeout = 15_000L
            ).text
            val data = parseJson<AniChanSearchResponse>(response)
            data.results.mapNotNull { item ->
                val title = item.title ?: item.titleRomaji ?: return@mapNotNull null
                val id = item.id ?: return@mapNotNull null
                val tvType = if (item.format == "MOVIE") TvType.AnimeMovie else TvType.Anime
                newAnimeSearchResponse(title, "/anime/$id", tvType) {
                    this.posterUrl = item.poster
                    this.year = item.startDate?.year
                }
            }
        } catch (e: Exception) {
            Log.e("AniChan", "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = url.substringAfterLast("/").toIntOrNull() ?: return null

        val metaQuery = "{ Media(id: $anilistId) { id title { english romaji } coverImage { large extraLarge } bannerImage description genres episodes format seasonYear status nextAiringEpisode { episode } } }"
        val meta: AniListMediaDetail? = try {
            val resp = app.post(anilistUrl, json = mapOf("query" to metaQuery), timeout = 15_000L).text
            parseJson<AniListDetailResponse>(resp).data?.media
        } catch (e: Exception) {
            Log.e("AniChan", "load: AniList fetch failed: ${e.message}")
            null
        }

        val title = meta?.title?.english ?: meta?.title?.romaji ?: return null
        val poster = meta?.coverImage?.extraLarge ?: meta?.coverImage?.large ?: ""
        val banner = meta?.bannerImage ?: ""
        val plot = meta?.description?.replace(Regex("<[^>]+>"), "")?.replace("\\n", "\n")
        val genres = meta?.genres ?: emptyList()
        val year = meta?.seasonYear

        val epData = try {
            val resp = app.get(
                "$mainUrl/api/watch/episodes?anilistId=$anilistId",
                headers = mapOf("User-Agent" to browserUA, "Referer" to "$mainUrl/anime/$anilistId"),
                timeout = 15_000L
            ).text
            parseJson<EpisodesResponse>(resp)
        } catch (e: Exception) {
            Log.e("AniChan", "load: episodes fetch failed: ${e.message}")
            return null
        }

        var epCount = epData.episodes ?: 0
        if (epCount == 0 && meta?.statusStr == "RELEASING") {
            epCount = (meta?.nextAiringEpisode?.episode ?: 1) - 1
            if (epCount < 1) epCount = 1
        }
        if (epCount == 0) epCount = 1

        val dubAvailable = epData.dubAvailable == true
        val isMovie = meta?.formatStr == "MOVIE" || epCount <= 1

        if (isMovie) {
            if (dubAvailable) {
                val subEp = newEpisode(EpisodeData(anilistId, 1, false).toJson()) { this.name = title }
                val dubEp = newEpisode(EpisodeData(anilistId, 1, true).toJson()) { this.name = title }
                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    addEpisodes(DubStatus.Subbed, listOf(subEp))
                    addEpisodes(DubStatus.Dubbed, listOf(dubEp))
                }
            } else {
                return newMovieLoadResponse(title, url, TvType.AnimeMovie, EpisodeData(anilistId, 1, false).toJson()) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                }
            }
        }

        val subEpisodes = (1..epCount).map { epNum ->
            newEpisode(EpisodeData(anilistId, epNum, false).toJson()) {
                this.episode = epNum
                this.name = "Episode $epNum"
            }
        }

        val dubEpisodes = if (dubAvailable) {
            (1..epCount).map { epNum ->
                newEpisode(EpisodeData(anilistId, epNum, true).toJson()) {
                    this.episode = epNum
                    this.name = "Episode $epNum"
                }
            }
        } else emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = genres
            this.year = year
            addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val epData = parseJson<EpisodeData>(data)
            val anilistId = epData.anilistId
            val episode = epData.episode
            val category = if (epData.isDub) "dub" else "sub"

            var foundLinks = false

            try {
                val resp = app.get(
                    "$mainUrl/api/watch/servers?anilistId=$anilistId&ep=$episode&category=$category",
                    headers = mapOf("User-Agent" to browserUA, "Referer" to "$mainUrl/anime/$anilistId"),
                    timeout = 15_000L
                ).text
                val servers = parseJson<ServersResponse>(resp).servers ?: emptyList()

                for (server in servers) {
                    val stream = server.stream ?: continue
                    val fullStream = if (stream.startsWith("/")) "$mainUrl$stream" else stream
                    val rawLabel = server.label ?: server.name ?: "AniChan"
                    val label = rawLabel.replace("★ ", "").trim()
                    val subType = server.subType ?: "soft"
                    val isHardsub = subType == "hard"

                    val displayLabel = when {
                        isHardsub -> "$label (Hardsub)"
                        epData.isDub -> "$label (Dub)"
                        else -> label
                    }

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
                    foundLinks = true

                    server.subtitles?.forEach { sub ->
                        val subUrl = sub.url ?: return@forEach
                        val fullSubUrl = if (subUrl.startsWith("/")) "$mainUrl$subUrl" else subUrl
                        val lang = sub.lang ?: "English"
                        subtitleCallback.invoke(SubtitleFile(lang, fullSubUrl))
                    }
                }
            } catch (e: Exception) {
                Log.e("AniChan", "loadLinks $category: ${e.message}")
            }

            foundLinks
        } catch (e: Exception) {
            Log.e("AniChan", "loadLinks: ${e.message}")
            false
        }
    }

    data class EpisodeData(val anilistId: Int, val episode: Int, val isDub: Boolean)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniChanSearchResponse(
    @JsonProperty("results") val results: List<SearchResult> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("startDate") val startDate: StartDate? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartDate(
    @JsonProperty("year") val year: Int? = null
)

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
    @JsonProperty("host") val host: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("stream") val stream: String? = null,
    @JsonProperty("subType") val subType: String? = null,
    @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null,
    @JsonProperty("audios") val audios: List<Audio>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Audio(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("lang") val lang: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListDetailResponse(
    @JsonProperty("data") val data: AniListDetailData? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListDetailData(
    @JsonProperty("Media") val media: AniListMediaDetail? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMediaDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AniListCover? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("format") val formatStr: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("status") val statusStr: String? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniListAiring? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListAiring(
    @JsonProperty("episode") val episode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPage(
    @JsonProperty("data") val data: AniListPageData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPageData(
    @JsonProperty("Page") val page: AniListPageMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListPageMedia(
    @JsonProperty("media") val media: List<AniListMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AniListCover? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListTitle(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("romaji") val romaji: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListCover(
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("extraLarge") val extraLarge: String? = null
)
