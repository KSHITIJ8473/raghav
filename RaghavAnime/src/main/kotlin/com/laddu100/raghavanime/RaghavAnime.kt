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
        val activity = activity
        if (activity != null) {
            val sharedPref = activity.getSharedPreferences("RaghavAnimePrefs", Context.MODE_PRIVATE)
            val neverShowAgain = sharedPref.getBoolean("never_show_prompt", false)

            if (!neverShowAgain && !hasShownThisSession) {
                hasShownThisSession = true
                activity.runOnUiThread {
                    try {
                        val builder = AlertDialog.Builder(activity)
                        val container = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            val padding = (16 * activity.resources.displayMetrics.density).toInt()
                            setPadding(padding, padding, padding, padding)
                        }

                        val messageView = TextView(activity).apply {
                            text = "Please allow all sources to load. If one source does not work for you, just select a different one."
                            textSize = 16f
                        }

                        val checkBox = CheckBox(activity).apply {
                            text = "Don't show again"
                            val topPadding = (8 * activity.resources.displayMetrics.density).toInt()
                            setPadding(0, topPadding, 0, 0)
                        }

                        container.addView(messageView)
                        container.addView(checkBox)

                        builder.setView(container)
                        builder.setCancelable(false)
                        builder.setPositiveButton("OK") { dialog: android.content.DialogInterface, _: Int ->
                            if (checkBox.isChecked) {
                                sharedPref.edit().putBoolean("never_show_prompt", true).apply()
                            }
                            dialog.dismiss()
                        }

                        builder.create().show()
                    } catch (_: Throwable) {}
                }
            }
        }

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
            "MOVIE" -> TvType.Anime
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
        var totalEps = media.episodes ?: animeMetaData?.episodes?.size ?: 0
        if (media.format == "MOVIE" && totalEps == 0) {
            totalEps = 1
        }
        
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
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { aniSuge.search(it) },
                        doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData != null) aniSuge.loadLinks(epData, false, subtitleCallback, callback)
                } catch (_: Throwable) {}
            },
            {
                // 3. AniWaves
                try {
                    val aniWaves = AniWaves()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val aniWavesTargets = listOfNotNull(title, jpTitle).map { cleanTitle(it) }
                    // AniWaves encodes sub/dub in the data string — always load from Subbed list and flip the prefix
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = try { aniWaves.search(t) } catch (_: Throwable) { continue }
                        // Only consider results with at least a partial title match (score >= 1)
                        val candidates = searchResults.mapNotNull { r ->
                            val c = cleanTitle(r.name)
                            val score = when {
                                aniWavesTargets.contains(c) -> 2
                                aniWavesTargets.any { tgt -> tgt.contains(c) || c.contains(tgt) } -> 1
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
                    if (matchedData != null) aniWaves.loadLinks(matchedData, false, subtitleCallback, callback)
                } catch (_: Throwable) {}
            },
            {
                // 4. Anikai
                try {
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anikai.search(it) },
                        doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData != null) anikai.loadLinks(epData, false, subtitleCallback, callback)
                } catch (_: Throwable) {}
            },
            {
                // 5. AniDb
                try {
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { q -> aniDb.search(q, 1).items },
                        doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData != null) aniDb.loadLinks(epData, false, subtitleCallback, callback)
                } catch (_: Throwable) {}
            },
            {
                // 6. Anikage
                try {
                    val anikage = AnikageProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anikage.search(it) },
                        doLoad = { anikage.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData != null) anikage.loadLinks(epData, false, subtitleCallback, callback)
                } catch (_: Throwable) {}
            },
            {
                // 7. Anineko
                try {
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { anineko.search(it) },
                        doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData != null) anineko.loadLinks(epData, false, subtitleCallback, callback)
                } catch (_: Throwable) {}
            },
            {
                // 8. Animetsu
                try {
                    val animetsu = AnimetsuProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub,
                        doSearch = { animetsu.search(it) },
                        doLoad = { animetsu.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse }
                    )
                    if (epData != null) animetsu.loadLinks(epData, false, subtitleCallback, callback)
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
        // Pass 1: exact match
        for (res in searchResults) {
            val cleanedRes = cleanTitle(res.name)
            if (cleanedTargets.contains(cleanedRes)) {
                return res
            }
        }
        // Pass 2: substring match
        for (res in searchResults) {
            val cleanedRes = cleanTitle(res.name)
            if (cleanedTargets.any { target -> target.contains(cleanedRes) || cleanedRes.contains(target) }) {
                return res
            }
        }
        // No match — return null so the caller truly skips this result
        return null
    }

    /**
     * Exhaustively search all results from all titles for an episode match.
     * Only considers results that have at least a partial title match (score >= 1)
     * to prevent wrong anime from being loaded.
     * Returns the episode data string, or null if truly not found.
     */
    private suspend fun findEpisodeData(
        searchTitles: List<String>,
        targetTitles: List<String>,
        episode: Int,
        isDub: Boolean,
        doSearch: suspend (String) -> List<SearchResponse>,
        doLoad: suspend (String) -> com.lagradost.cloudstream3.AnimeLoadResponse?,
        dubKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Dubbed,
        subKey: com.lagradost.cloudstream3.DubStatus = com.lagradost.cloudstream3.DubStatus.Subbed
    ): String? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        for (t in searchTitles) {
            val searchResults = try { doSearch(t) } catch (_: Throwable) { continue }
            // Score each result: 2 = exact match, 1 = partial match, 0 = unrelated
            val candidates = searchResults.mapNotNull { r ->
                val c = cleanTitle(r.name)
                val score = when {
                    cleanedTargets.contains(c) -> 2
                    cleanedTargets.any { tgt -> tgt.contains(c) || c.contains(tgt) } -> 1
                    else -> 0
                }
                // Only keep results with at least a partial title match
                if (score > 0) Pair(score, r) else null
            }.sortedByDescending { it.first }

            for ((_, result) in candidates) {
                try {
                    val loadResult = doLoad(result.url) ?: continue
                    val epKey = if (isDub) dubKey else subKey
                    val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                    if (ep != null) return ep.data
                } catch (_: Throwable) { continue }
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
            }
        }
    }
""".trimIndent()
