package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CommonActivity.activity
import android.content.Context
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox

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
    override val instantLinkLoading = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Popular This Season",
        "RECENT" to "Recently Updated",
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
            "TOP_RATED" -> {
                variables["sort"] = listOf("SCORE_DESC")
                variables["format"] = "TV"
            }
            else -> {
                variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            }
        }

        val home = try {
            val responseText = anilistQuery(query, variables)
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()

            if (mediaList.isNotEmpty()) {
                homePageCache[request.data] = mediaList
            }

            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                }
            }
        } catch (e: Exception) {
            val cached = homePageCache[request.data]
            if (cached != null) {
                cached.mapNotNull { media ->
                    val id = media.id ?: return@mapNotNull null
                    val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                    val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                    newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                        this.posterUrl = posterUrl
                        addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                    }
                }
            } else {
                emptyList()
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val variables = mapOf<String, Any?>("search" to query, "page" to 1, "perPage" to 20)
            val responseText = anilistQuery(SEARCH_QUERY, variables)
            val response = parseJson<AniListResponse>(responseText)
            val mediaList = response.data?.Page?.media ?: emptyList()

            mediaList.mapNotNull { media ->
                val id = media.id ?: return@mapNotNull null
                val title = media.title?.english ?: media.title?.romaji ?: return@mapNotNull null
                val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
                newAnimeSearchResponse(title, "$mainUrl/info/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val media = try {
            val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
            val infoResponse = parseJson<AniListResponse>(infoText)
            infoResponse.data?.Media
        } catch (e: Exception) {
            return null
        } ?: run {
            return null
        }

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val jpTitle = media.title?.romaji
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

        val tvType = when (media.format) {
            "MOVIE" -> TvType.Anime
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val syncMetaData = try {
            app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text
        } catch (_: Exception) { null }
        val animeMetaData = syncMetaData?.let { parseAnimeData(it) }

        val anizipNumericCount = animeMetaData?.episodes?.keys
            ?.filterNotNull()
            ?.filter { it.toIntOrNull() != null }
            ?.size ?: 0

        var totalEps = media.episodes
            ?: anizipNumericCount
            ?: 0

        media.nextAiringEpisode?.episode?.let { nextEp ->
            if (totalEps >= nextEp) {
                totalEps = nextEp - 1
            }
        }

        if (media.format == "MOVIE" && totalEps == 0) totalEps = 1
        if (totalEps == 0) totalEps = 1

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (i in 1..totalEps) {
            val epData = animeMetaData?.episodes?.get(i.toString())
            val epTitle = epData?.title?.get("en") ?: epData?.title?.get("ja") ?: epData?.title?.get("x-jat") ?: "Episode $i"
            val epDesc = epData?.overview ?: "No summary available"
            val epPoster = epData?.image ?: posterUrl

            val subLinkData = LinkData(animeId = anilistId, title = title, jpTitle = jpTitle, episode = i, isDub = false, year = year).toJson()
            val dubLinkData = LinkData(animeId = anilistId, title = title, jpTitle = jpTitle, episode = i, isDub = true, year = year).toJson()

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
                try {
                    val miruro = Miruro()
                    val loadResult = miruro.load("${miruro.mainUrl}/info/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                    } else {
                        val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            miruro.loadLinks(matchedEp.data, false, subtitleCallback, callback)
                        } else {
                        }
                    }
                } catch (e: Throwable) { }
            },
            {
                try {
                    val aniSuge = AniSugeProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniSuge.search(it) },
                        doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniSuge"
                    )
                    if (epData != null) aniSuge.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val aniWaves = AniWaves()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val aniWavesTargets = listOfNotNull(title, jpTitle).map { cleanTitle(it) }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = try { aniWaves.search(t) } catch (_: Throwable) { continue }
                        val candidates = searchResults.mapNotNull { r ->
                            val c = cleanTitle(r.name)
                            val score = when {
                                aniWavesTargets.contains(c) -> 2
                                else -> 0
                            }
                            if (score > 0) Pair(score, r) else null
                        }.sortedByDescending { it.first }
                        for ((_, result) in candidates) {
                            try {
                                val loadResult = aniWaves.load(result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                                val ep = loadResult.episodes?.get(DubStatus.Subbed)?.find { it.episode == episode } ?: continue
                                val parts = ep.data.split("|").toMutableList()
                                parts[0] = if (isDub) "dub" else "sub"
                                matchedData = parts.joinToString("|")
                                break
                            } catch (_: Throwable) { continue }
                        }
                        if (matchedData != null) break
                    }
                    if (matchedData != null) {
                        aniWaves.loadLinks(matchedData, false, subtitleCallback, callback)
                    }
                } catch (e: Throwable) { }
            },
            {
                try {
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikai.search(it) },
                        doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anikai"
                    )
                    if (epData != null) anikai.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { q -> aniDb.search(q, 1).items },
                        doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDb"
                    )
                    if (epData != null) aniDb.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val anikage = RaghavAniKage()
                    anikage.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anineko.search(it) },
                        doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anineko"
                    )
                    if (epData != null) anineko.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val animetsu = AnimetsuProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { animetsu.search(it) },
                        doLoad = { animetsu.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Animetsu"
                    )
                    if (epData != null) animetsu.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val twoDHive = RaghavTwoDHive()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { twoDHive.search(it) },
                        doLoad = { twoDHive.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "2DHive"
                    )
                    if (epData != null) twoDHive.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val anikoto = RaghavAnikoto()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikoto.search(it) },
                        doLoad = { anikoto.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniKoto"
                    )
                    if (epData != null) anikoto.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val enma = RaghavEnma()
                    enma.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val animo = RaghavAnimo()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { animo.search(it) },
                        doLoad = { animo.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Animo"
                    )
                    if (epData != null) {
                        animo.loadLinks(epData, false, subtitleCallback, callback)
                    }
                } catch (e: Throwable) { }
            },
            {
                try {
                    val anidap = RaghavAnidap()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anidap.search(it) },
                        doLoad = { anidap.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anidap"
                    )
                    if (epData != null) anidap.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val senshi = RaghavSenshi()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { senshi.search(it) },
                        doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Senshi"
                    )
                    if (epData != null) senshi.loadLinks(epData, false, subtitleCallback, callback)
                } catch (e: Throwable) { }
            },
            {
                try {
                    val aniNami = RaghavAniNami()
                    val loadResult = aniNami.load("${aniNami.mainUrl}/anime/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                    } else {
                        val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                        val matchedEp = epList?.find { it.episode == episode }
                        if (matchedEp != null) {
                            aniNami.loadLinks(matchedEp.data, false, subtitleCallback, callback)
                        } else {
                        }
                    }
                } catch (e: Throwable) { }
            },
        )

        return true
    }

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun romanToInt(s: String): Int? {
        val upper = s.uppercase()
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10)
        if (upper.any { it !in map }) return null
        var result = 0
        var prev = 0
        for (c in upper.reversed()) {
            val curr = map[c] ?: return null
            if (curr < prev) result -= curr else result += curr
            prev = curr
        }
        return if (result in 1..20) result else null
    }

    private fun extractSeasonNumber(title: String): Int? {
        val lower = title.lowercase()
        Regex("""(\d+)(?:st|nd|rd|th)\s*season""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""season\s*(\d+)""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""\bs(\d+)\b""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""part\s*(\d+)""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""cour\s*(\d+)""").find(lower)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("""\s+([ivx]+)\s*$""").find(lower)?.let {
            return romanToInt(it.groupValues[1])
        }
        return null
    }

    private fun extractYear(title: String): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun findEpisodeData(
        searchTitles: List<String>,
        targetTitles: List<String>,
        episode: Int,
        isDub: Boolean,
        year: Int?,
        doSearch: suspend (String) -> List<SearchResponse>,
        doLoad: suspend (String) -> com.lagradost.cloudstream3.AnimeLoadResponse?,
        dubKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Dubbed,
        subKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Subbed,
        sourceTag: String = "Source"
    ): String? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        val epKey = if (isDub) dubKey else subKey

        val targetSeasonNum = targetTitles.firstNotNullOfOrNull { extractSeasonNumber(it) }

        data class Candidate(val combinedScore: Int, val titleScore: Int, val result: SearchResponse)

        val allCandidates = mutableListOf<Candidate>()
        var totalSearchResults = 0
        for (t in searchTitles) {
            val searchResults = try { doSearch(t) } catch (e: Throwable) {
                continue
            }
            totalSearchResults += searchResults.size
            for (r in searchResults) {
                val c = cleanTitle(r.name)
                val titleScore = when {
                    cleanedTargets.contains(c) -> 2
                    cleanedTargets.any { tgt -> tgt.contains(c) || c.contains(tgt) } -> 1
                    else -> 0
                }
                if (titleScore == 0) continue

                val candSeasonNum = extractSeasonNumber(r.name)
                val candYear = extractYear(r.name)
                val yearScore = if (year != null && (candYear == year || r.name.contains(year.toString()))) 1 else 0
                val seasonScore = if (targetSeasonNum != null && candSeasonNum == targetSeasonNum) 1 else 0

                val combinedScore = titleScore * 10 + yearScore * 5 + seasonScore * 3
                allCandidates.add(Candidate(combinedScore, titleScore, r))
            }
        }

        if (allCandidates.isEmpty()) {
            return null
        }

        allCandidates.sortByDescending { it.combinedScore }

        for (cand in allCandidates) {
            if (cand.titleScore < 2) {
                break
            }
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) {
                    return ep.data
                }
            } catch (e: Throwable) {
            }
        }

        return null
    }

    data class LinkData(
        val animeId: Int,
        val title: String,
        val jpTitle: String?,
        val episode: Int,
        val isDub: Boolean,
        val year: Int?
    )

    companion object {
        var hasShownThisSession = false
        private val homePageCache = mutableMapOf<String, List<AniListMedia>>()
    }
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
                nextAiringEpisode { episode }
            }
        }
    }
""".trimIndent()
