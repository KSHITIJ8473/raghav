package com.laddu100.raghavanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class RaghavAnime : MainAPI() {
    override var mainUrl = "https://graphql.anilist.co"
    override var name = "RaghavAnime"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Popular This Season",
        "RECENT" to "Recently Updated",
        "ACTION" to "Action & Adventure",
        "FANTASY" to "Fantasy & Magic",
        "SCIFI" to "Sci-Fi & Mecha",
        "ROMANCE" to "Romance & Drama",
        "COMEDY" to "Comedy & Slice of Life",
        "MOVIES" to "Top Rated Movies",
        "TOP_RATED" to "Top Rated Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = HOMEPAGE_QUERY
        val variables = mutableMapOf<String, Any?>("page" to page, "perPage" to 20)

        when (request.data) {
            "TRENDING" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
            "POPULAR" -> {
                variables["sort"] = listOf("POPULARITY_DESC")
            }
            "RECENT" -> {
                variables["sort"] = listOf("START_DATE_DESC")
                variables["status"] = "RELEASING"
            }
            "ACTION" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Action", "Adventure")
            }
            "FANTASY" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Fantasy")
            }
            "SCIFI" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Sci-Fi")
            }
            "ROMANCE" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Romance", "Drama")
            }
            "COMEDY" -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
                variables["genreIn"] = listOf("Comedy")
            }
            "MOVIES" -> {
                variables["sort"] = listOf("SCORE_DESC")
                variables["format"] = "MOVIE"
            }
            "TOP_RATED" -> {
                variables["sort"] = listOf("SCORE_DESC")
                variables["format"] = "TV"
            }
            else -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
        }

        val responseText = anilistQuery(query, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
        val responseText = anilistQuery(SEARCH_QUERY, variables)
        val response = parseJson<AniListResponse>(responseText)
        val mediaList = response.data?.Page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
        val infoResponse = parseJson<AniListResponse>(infoText)
        val media = infoResponse.data?.Media ?: return null

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val jpTitle = media.title?.romaji
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

        val tvType = when (media.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        // We fetch mapping metadata from ani.zip to resolve mappings and get exact episode lists
        val syncMetaData = try {
            app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text
        } catch (_: Exception) {
            null
        }
        val animeMetaData = syncMetaData?.let { parseAnimeData(it) }

        // Determine total episodes
        val totalEps = media.episodes ?: animeMetaData?.episodes?.size ?: 0
        
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (i in 1..totalEps) {
            val epData = animeMetaData?.episodes?.get(i.toString())
            val epTitle = (epData?.title?.get("en") ?: epData?.title?.get("ja") ?: epData?.title?.get("x-jat") ?: "Episode $i").toString()
            val epDesc = (epData?.overview ?: "No summary available").toString()
            val epPoster = (epData?.image ?: posterUrl)?.toString()

            val subLinkData = LinkData(
                animeId = anilistId,
                title = title,
                jpTitle = jpTitle,
                episode = i,
                isDub = false,
                year = year
            ).toJson()

            val dubLinkData = LinkData(
                animeId = anilistId,
                title = title,
                jpTitle = jpTitle,
                episode = i,
                isDub = true,
                year = year
            ).toJson()

            subEpisodes.add(newEpisode(subLinkData) {
                this.episode = i
                this.name = epTitle
                this.description = epDesc
                this.posterUrl = epPoster
            })

            dubEpisodes.add(newEpisode(dubLinkData) {
                this.episode = i
                this.name = epTitle
                this.description = epDesc
                this.posterUrl = epPoster
            })
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            if (animeScore != null) this.score = Score.from10((animeScore / 10).toString())
            this.showStatus = showStatus
            addAniListId(anilistId)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val aniId = linkData.animeId
        val title = linkData.title
        val jpTitle = linkData.jpTitle
        val episode = linkData.episode
        val isDub = linkData.isDub

        runAllAsync(
            {
                // 1. Miruro
                try {
                    val miruro = Miruro()
                    val loadResult = miruro.load("${miruro.mainUrl}/info/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                    val matchedEp = epList?.find { it.episode == episode }
                    if (matchedEp != null) {
                        miruro.loadLinks(matchedEp.data, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 2. AniSuge
                try {
                    val aniSuge = AniSugeProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = aniSuge.search(t)
                        val firstResult = matchSearchResult(searchResults, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = aniSuge.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            matchedData = matchedEp.data
                            break
                        }
                    }
                    if (matchedData != null) {
                        aniSuge.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 3. AniWaves
                try {
                    val aniWaves = AniWaves()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = aniWaves.search(t)
                        val firstResult = matchSearchResult(searchResults, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = aniWaves.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val matchedEp = loadResult?.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode }
                        if (matchedEp != null) {
                            val parts = matchedEp.data.split("|").toMutableList()
                            parts[0] = if (isDub) "dub" else "sub"
                            matchedData = parts.joinToString("|")
                            break
                        }
                    }
                    if (matchedData != null) {
                        aniWaves.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 4. Anikai
                try {
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = anikai.search(t)
                        val firstResult = matchSearchResult(searchResults, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = anikai.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            matchedData = matchedEp.data
                            break
                        }
                    }
                    if (matchedData != null) {
                        anikai.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 5. AniDb
                try {
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = aniDb.search(t, 1)
                        val firstResult = matchSearchResult(searchResults.items, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = aniDb.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            matchedData = matchedEp.data
                            break
                        }
                    }
                    if (matchedData != null) {
                        aniDb.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 6. Anikage
                try {
                    val anikage = AnikageProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = anikage.search(t)
                        val firstResult = matchSearchResult(searchResults, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = anikage.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            matchedData = matchedEp.data
                            break
                        }
                    }
                    if (matchedData != null) {
                        anikage.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 7. Anineko
                try {
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = anineko.search(t)
                        val firstResult = matchSearchResult(searchResults, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = anineko.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            matchedData = matchedEp.data
                            break
                        }
                    }
                    if (matchedData != null) {
                        anineko.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            },
            {
                // 8. Animetsu
                try {
                    val animetsu = AnimetsuProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = animetsu.search(t)
                        val firstResult = matchSearchResult(searchResults, listOfNotNull(title, jpTitle)) ?: continue
                        val loadResult = animetsu.load(firstResult.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                        val epList = if (isDub) loadResult?.episodes?.get(DubStatus.Dubbed) else loadResult?.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            matchedData = matchedEp.data
                            break
                        }
                    }
                    if (matchedData != null) {
                        animetsu.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (_: Throwable) {}
            }
        )

        return true
    }

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun matchSearchResult(searchResults: List<SearchResponse>, targetTitles: List<String>): SearchResponse? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        for (res in searchResults) {
            val cleanedRes = cleanTitle(res.name)
            if (cleanedTargets.contains(cleanedRes)) {
                return res
            }
        }
        for (res in searchResults) {
            val cleanedRes = cleanTitle(res.name)
            if (cleanedTargets.any { target -> target.contains(cleanedRes) || cleanedRes.contains(target) }) {
                return res
            }
        }
        return searchResults.firstOrNull()
    }

    data class LinkData(
        val animeId: Int,
        val title: String,
        val jpTitle: String?,
        val episode: Int,
        val isDub: Boolean,
        val year: Int?
    )
}

val HOMEPAGE_QUERY = """
    query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}genreIn: [String], ${'$'}format: MediaFormat, ${'$'}status: MediaStatus) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: ${'$'}sort, genre_in: ${'$'}genreIn, format: ${'$'}format, status: ${'$'}status) {
                id
                title { romaji english native }
                coverImage { large extraLarge }
                format
                episodes
                status
                seasonYear
                averageScore
                genres
            }
        }
    }
""".trimIndent()
