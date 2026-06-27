package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class OneTube : MainAPI() {
    override var mainUrl = "https://www.1tube.org"
    override var name = "OneTube"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * Standard browser headers — 1tube.org is behind Cloudflare and rejects
     * bare Java/OkHttp User-Agents with 403.
     */
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to mainUrl
    )

    private val imgBase = "https://image.tmdb.org/t/p/w500"

    // ============================================================
    //  Homepage — uses the /api/shawts endpoint
    // ============================================================
    override val mainPage = mainPageOf(
        "movie" to "Latest Movies",
        "tv" to "Latest Series",
        "all" to "Trending Now"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = request.data
        val url = "$mainUrl/api/shawts?type=$type&page=$page"
        val response = app.get(url, headers = baseHeaders).parsedSafe<ShawtsResponse>() ?: return newHomePageResponse(request.name, emptyList())
        val items = response.results.map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = response.has_more)
    }

    // ============================================================
    //  Search — uses the /api/search endpoint
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        // The /api/search endpoint returns up to 20 results per page. We fetch
        // the first 3 pages (up to 60 results) so users get a richer result set
        // for common queries like "spider" or "avatar".
        val allResults = mutableListOf<SearchResult>()
        for (p in 1..3) {
            val url = "$mainUrl/api/search?query=${java.net.URLEncoder.encode(query, "utf-8")}&page=$p"
            val response = app.get(url, headers = baseHeaders).parsedSafe<SearchResponse>() ?: break
            allResults += response.results
            // Stop early if we've reached the last page
            val totalPages = response.total_pages ?: 1
            if (p >= totalPages) break
        }
        return allResults.map { it.toSearchResponse() }
    }

    // ============================================================
    //  Load — fetches details + (for TV) all seasons' episodes
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        // URL format: <mainUrl>/watch/<tmdbId>?type=<movie|tv>
        val tmdbId = url.substringAfter("/watch/").substringBefore("?").toIntOrNull() ?: return null
        val mediaType = if (url.contains("type=tv")) "tv" else "movie"

        val detailsUrl = "$mainUrl/api/details/$tmdbId?type=$mediaType"
        val details = app.get(detailsUrl, headers = baseHeaders).parsedSafe<DetailsResponse>() ?: return null

        val title = details.title ?: details.name ?: return null
        val poster = details.poster_path?.let { imgBase + it }
        val bg = details.backdrop_path?.let { imgBase + it }
        val plot = details.overview
        val year = (details.release_date ?: details.first_air_date)?.substring(0, 4)?.toIntOrNull()
        val tags = details.genres?.map { it.name } ?: emptyList()
        val rating = details.vote_average?.let { (it * 10).toInt() }
        val runtime = details.runtime?.takeIf { it > 0 } ?: details.episode_run_time?.firstOrNull()

        val showStatus = when (details.status?.lowercase()) {
            "released", "ended", "canceled" -> ShowStatus.Completed
            "in production", "post production", "returning series", "airing" -> ShowStatus.Ongoing
            else -> null
        }

        // Recommendations
        val recUrl = "$mainUrl/api/recommendations/$tmdbId?type=$mediaType"
        val recs = app.get(recUrl, headers = baseHeaders).parsedSafe<RecommendationsResponse>()
            ?.results?.map { it.toSearchResponse() } ?: emptyList()

        if (mediaType == "movie") {
            // Movie — data string: movie|<tmdbId>|0|0
            return newMovieLoadResponse(title, url, TvType.Movie, "movie|$tmdbId|0|0") {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = year
                this.plot = plot
                this.tags = tags
                this.rating = rating
                this.runtime = runtime
                this.recommendations = recs
            }
        }

        // TV series — fetch every season's episodes
        val seasons = details.seasons?.filter { it.season_number != null } ?: emptyList()
        val episodeMap = mutableMapOf<Int, List<com.lagradost.cloudstream3.Episode>>()

        for (season in seasons) {
            val seasonNum = season.season_number!!
            // Skip season 0 (specials) unless it's the only season — keeps the UI clean.
            if (seasonNum == 0 && seasons.size > 1) continue

            val seasonUrl = "$mainUrl/api/tv/$tmdbId/season/$seasonNum"
            val seasonData = app.get(seasonUrl, headers = baseHeaders).parsedSafe<SeasonResponse>()
            if (seasonData?.episodes == null) continue

            val episodes = seasonData.episodes.map { ep ->
                // TV episode data string: tv|<tmdbId>|<season>|<episode>
                newEpisode("tv|$tmdbId|$seasonNum|${ep.episode_number ?: 1}") {
                    this.name = ep.name ?: "Episode ${ep.episode_number}"
                    this.episode = ep.episode_number ?: 1
                    this.season = seasonNum
                    this.posterUrl = ep.still_path?.let { imgBase + it }
                    this.description = ep.overview
                    this.rating = ep.vote_average?.let { (it * 10).toInt() }
                    this.runtime = ep.runtime
                }
            }
            if (episodes.isNotEmpty()) {
                episodeMap[seasonNum] = episodes
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeMap) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.year = year
            this.plot = plot
            this.tags = tags
            this.rating = rating
            this.showStatus = showStatus
            this.recommendations = recs
        }
    }

    // ============================================================
    //  Load Links — builds all 8 server embed URLs and resolves them
    // ============================================================
    //
    //  Data string format:  "<mediaType>|<tmdbId>|<season>|<episode>"
    //    movie: "movie|19995|0|0"
    //    tv:    "tv|1399|1|1"
    //
    //  Server mapping (extracted from the site's watch-page JS chunk):
    //    MAIN_1          → player.videasy.to
    //    MAIN_2          → vidsrc.wtf/api/1
    //    MAIN_3          → vidup.to
    //    MAIN_4          → vidlink.pro
    //    MAIN_5          → vidrock.ru
    //    MAIN_6          → player.vidzee.wtf
    //    MULTILANGUAGE   → vidsrc.wtf/api/2  (multi-audio-track embeds)
    //    PREMIUM_EMBEDS  → vidsrc.wtf/api/4 (premium-quality embeds)
    //
    //  All 8 servers are exposed as separate ExtractorLink entries so the
    //  user can pick whichever works best in their region. CloudStream's
    //  built-in loadExtractor() handles the actual m3u8/mp4 resolution for
    //  each provider (vidsrc, vidlink, vidup, etc. are all registered).
    //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 4) return false

        val mediaType = parts[0]        // "movie" or "tv"
        val tmdbId = parts[1]
        val season = parts[2]
        val episode = parts[3]

        val isMovie = mediaType == "movie"

        // Build all 8 server embed URLs
        val servers = listOf(
            ServerInfo("Main 1", "MAIN_1",
                if (isMovie) "https://player.videasy.to/movie/$tmdbId?color=8c52ff&overlay=true"
                else "https://player.videasy.to/tv/$tmdbId/$season/$episode?color=8c52ff&episodeSelector=false&nextEpisode=false&autoplayNextEpisode=false&overlay=true"
            ),
            ServerInfo("Main 2", "MAIN_2",
                if (isMovie) "https://www.vidsrc.wtf/api/1/movie/?color=8c52ff&id=$tmdbId"
                else "https://www.vidsrc.wtf/api/1/tv/?color=8c52ff&id=$tmdbId&s=$season&e=$episode"
            ),
            ServerInfo("Main 3", "MAIN_3",
                if (isMovie) "https://vidup.to/movie/$tmdbId?autoPlay=true&title=true&poster=true&theme=8c52ff"
                else "https://vidup.to/tv/$tmdbId/$season/$episode?autoPlay=true&title=true&poster=true&theme=8c52ff&nextButton=false&autoNext=false"
            ),
            ServerInfo("Main 4", "MAIN_4",
                if (isMovie) "https://vidlink.pro/movie/$tmdbId?primaryColor=8c52ff&secondaryColor=a2a2a2&iconColor=eefdec&icons=default&player=default&title=true&poster=true&autoplay=true&nextbutton=false"
                else "https://vidlink.pro/tv/$tmdbId/$season/$episode?primaryColor=8c52ff&secondaryColor=a2a2a2&iconColor=eefdec&icons=default&player=default&title=true&poster=true&autoplay=true&nextbutton=false"
            ),
            ServerInfo("Main 5", "MAIN_5",
                if (isMovie) "https://vidrock.ru/movie/$tmdbId?theme=8c52ff&autoplay=true&autonext=false&download=false&nextbutton=false&episodeselector=false"
                else "https://vidrock.ru/tv/$tmdbId/$season/$episode?theme=8c52ff&autoplay=true&autonext=false&download=false&nextbutton=false&episodeselector=false"
            ),
            ServerInfo("Main 6", "MAIN_6",
                if (isMovie) "https://player.vidzee.wtf/embed/movie/$tmdbId?color=8c52ff"
                else "https://player.vidzee.wtf/embed/tv/$tmdbId/$season/$episode?color=8c52ff"
            ),
            ServerInfo("Multi-language", "MULTILANGUAGE",
                if (isMovie) "https://www.vidsrc.wtf/api/2/movie/?color=8c52ff&id=$tmdbId"
                else "https://www.vidsrc.wtf/api/2/tv/?color=8c52ff&id=$tmdbId&s=$season&e=$episode"
            ),
            ServerInfo("Premium", "PREMIUM_EMBEDS",
                if (isMovie) "https://www.vidsrc.wtf/api/4/movie/?id=$tmdbId&color=8c52ff"
                else "https://www.vidsrc.wtf/api/4/tv/?id=$tmdbId&s=$season&e=$episode&color=8c52ff"
            )
        )

        var foundAny = false
        for (server in servers) {
            try {
                val loaded = loadExtractor(server.url, "$mainUrl/", subtitleCallback, callback)
                if (loaded) foundAny = true
            } catch (_: Exception) {
                // One failing server shouldn't kill the rest
            }
        }
        return foundAny
    }

    // ============================================================
    //  Helper: convert a search/shawt result to a SearchResponse
    // ============================================================
    private fun SearchResult.toSearchResponse(): SearchResponse {
        val id = this.id ?: return newMovieSearchResponse("", "", TvType.Movie) {}
        val mediaType = this.media_type ?: if (first_air_date != null) "tv" else "movie"
        val title = this.title ?: this.name ?: ""
        val poster = poster_path?.let { imgBase + it }
        val year = (release_date ?: first_air_date)?.substring(0, 4)?.toIntOrNull()
        // Use the watch URL with type query param so load() knows what to fetch
        val url = "$mainUrl/watch/$id?type=$mediaType"

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun RecResult.toSearchResponse(): SearchResponse {
        val id = this.id ?: return newMovieSearchResponse("", "", TvType.Movie) {}
        val mediaType = this.media_type ?: if (first_air_date != null) "tv" else "movie"
        val title = this.title ?: this.name ?: ""
        val poster = poster_path?.let { imgBase + it }
        val year = (release_date ?: first_air_date)?.substring(0, 4)?.toIntOrNull()
        val url = "$mainUrl/watch/$id?type=$mediaType"

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ============================================================
    //  Data classes for JSON parsing
    // ============================================================
    private data class ServerInfo(val label: String, val id: String, val url: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShawtsResponse(
        @JsonProperty("results") val results: List<SearchResult>,
        @JsonProperty("page") val page: Int,
        @JsonProperty("has_more") val has_more: Boolean
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponse(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("results") val results: List<SearchResult>,
        @JsonProperty("total_pages") val total_pages: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("media_type") val media_type: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("vote_average") val vote_average: Double?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecommendationsResponse(
        @JsonProperty("results") val results: List<RecResult>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("media_type") val media_type: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailsResponse(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val original_title: String?,
        @JsonProperty("original_name") val original_name: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("episode_run_time") val episode_run_time: List<Int>?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("genres") val genres: List<Genre>?,
        @JsonProperty("seasons") val seasons: List<Season>?,
        @JsonProperty("number_of_seasons") val number_of_seasons: Int?,
        @JsonProperty("number_of_episodes") val number_of_episodes: Int?,
        @JsonProperty("imdb_id") val imdb_id: String?,
        @JsonProperty("homepage") val homepage: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("episode_count") val episode_count: Int?,
        @JsonProperty("air_date") val air_date: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("overview") val overview: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonResponse(
        @JsonProperty("id") val id: String?,
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("episodes") val episodes: List<Episode>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Episode(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("episode_number") val episode_number: Int?,
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("air_date") val air_date: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("vote_average") val vote_average: Double?
    )
}
