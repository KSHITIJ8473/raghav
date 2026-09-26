package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CommonActivity.activity
import android.content.Context
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    @Volatile
    private var anilistDownPopupShown = false

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var prefetchJob: Job? = null

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var linksJob: Job? = null

    private val downCheckLock = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var lastDownCheckTime: Long = 0L
    private val DOWN_CHECK_INTERVAL = 60_000L

    private suspend fun isAniListDown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDownCheckTime < DOWN_CHECK_INTERVAL) {
            return anilistDownPopupShown
        }
        if (!downCheckLock.tryLock()) return anilistDownPopupShown
        try {
            lastDownCheckTime = now
            val testQuery = "query { Page(page:1, perPage:1) { media(type: ANIME) { id } } }"
            val responseText = anilistQuery(testQuery, emptyMap())

            if (responseText.contains("temporarily disabled")) {
                showAniListDownPopup()
                return true
            }

            if (responseText.contains("\"data\"") && !responseText.contains("\"data\":null")) {
                anilistDownPopupShown = false
                return false
            }

            if (responseText.contains("Too Many Requests") || responseText.contains("429")) {
                return false
            }

            return false
        } catch (e: Exception) {
            return false
        } finally {
            downCheckLock.unlock()
        }
    }

    private fun showAniListDownPopup() {
        if (anilistDownPopupShown) return
        anilistDownPopupShown = true
        val ctx = activity ?: return
        ctx.runOnUiThread {
            try {
                val cBg = Color.parseColor("#0A0A0A")
                val cCard = Color.parseColor("#1A1A1A")
                val cAccent = Color.parseColor("#FF1744")
                val cText = Color.parseColor("#FFFFFF")
                val cTextSub = Color.parseColor("#9E9E9E")
                val d = ctx.resources.displayMetrics.density
                fun Int.dp() = (this * d).toInt()

                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp(), 28.dp(), 24.dp(), 24.dp())
                    setBackgroundColor(cBg)
                }

                container.addView(TextView(ctx).apply {
                    text = "AniList API is Down"
                    textSize = 20f
                    setTextColor(cAccent)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 12.dp())
                })

                container.addView(TextView(ctx).apply {
                    text = "RaghavAnime depends on the AniList API for anime metadata, search, and homepage content.\n\nThis may be because the AniList API is disabled from their end, or something is wrong from our end. Whichever the case, it will soon be fixed.\n\nIf AniList is disabled from their end, everything will work again once AniList restores services.\n\nUse other providers in the meantime - there are many others available."
                    textSize = 13f
                    setTextColor(cTextSub)
                    setLineSpacing(1.4f, 1.0f)
                    setPadding(0, 0, 0, 20.dp())
                })

                val scroll = ScrollView(ctx).apply { addView(container) }
                val dialog = AlertDialog.Builder(ctx).setView(scroll).create()

                container.addView(Button(ctx).apply {
                    text = "Got it"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(cAccent)
                    }
                    setPadding(0, 14.dp(), 0, 14.dp())
                    setOnClickListener { dialog.dismiss() }
                })

                dialog.show()
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Popular This Season",
        "RECENT" to "Recently Updated",
        "TOP_RATED" to "Top Rated Series",
        "RECOMMEND" to "Recommended For You"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "RECOMMEND") {
            if (!RaghavAnimeFeatures.isEnabled("recommendations")) {
                return newHomePageResponse(request.name, emptyList())
            }
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList())
            }
            return try {
                val list = RaghavAnimeFeatures.getRecommendationsList()
                val home = list.map { item ->
                    newAnimeSearchResponse(item.title, item.url, TvType.Anime) {
                        this.posterUrl = item.posterUrl
                    }
                }
                newHomePageResponse(request.name, home)
            } catch (e: Exception) {
                newHomePageResponse(request.name, emptyList())
            }
        }

        isAniListDown()

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
        isAniListDown()
        val results = try {
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
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        isAniListDown()

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

        prefetchSources(anilistId, title, jpTitle, year)

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

        if (RaghavAnimeFeatures.isEnabled("watch_time")) {
            try { RaghavAnimeFeatures.recordWatchTime(aniId, title, null, 24 * 60 * 1000L) } catch (_: Exception) {}
        }

        val animeKey = aniId.toString()
        val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        val targetTitles = listOfNotNull(title, jpTitle)

        val sources = listOf<Pair<String, suspend () -> Unit>>(
            "Miruro" to {
                val epData = SourceCache.episodeData("Miruro", animeKey, isDub, episode) {
                    resolveMiruro(aniId, episode, isDub)
                }
                if (epData != null) {
                    Miruro().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "AniSuge" to {
                val epData = SourceCache.episodeData("AniSuge", animeKey, isDub, episode) {
                    resolveAniSuge(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    AniSugeProvider().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "AniWaves" to {
                val epData = SourceCache.episodeData("AniWaves", animeKey, isDub, episode) {
                    resolveAniWaves(searchTitles, targetTitles, episode, isDub)
                }
                if (epData != null) {
                    AniWaves().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "AniKage" to {
                val anikage = RaghavAniKage()
                anikage.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
            },
            "2DHive" to {
                val epData = SourceCache.episodeData("2DHive", animeKey, isDub, episode) {
                    resolveTwoDHive(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavTwoDHive().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "AniKoto" to {
                val epData = SourceCache.episodeData("AniKoto", animeKey, isDub, episode) {
                    resolveAniKoto(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavAnikoto().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "Enma" to {
                val enma = RaghavEnma()
                enma.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
            },
            "Animo" to {
                val epData = SourceCache.episodeData("Animo", animeKey, isDub, episode) {
                    resolveAnimo(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavAnimo().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "Anidap" to {
                val anidap = RaghavAnidap()
                anidap.loadLinksByAnilistId(aniId, episode, isDub, subtitleCallback, callback)
            },
            "AniPM" to {
                val anipm = RaghavAniPM()
                anipm.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subtitleCallback, callback)
            },
            "Senshi" to {
                val epData = SourceCache.episodeData("Senshi", animeKey, isDub, episode) {
                    resolveSenshi(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavSenshi().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "AniNami" to {
                val epData = SourceCache.episodeData("AniNami", animeKey, isDub, episode) {
                    resolveAniNami(aniId, episode, isDub)
                }
                if (epData != null) {
                    RaghavAniNami().loadLinks(epData, false, subtitleCallback, callback)
                }
            },
            "AniChan" to {
                val anichan = RaghavAniChan()
                anichan.loadLinksByAnilistId(aniId, episode, isDub, subtitleCallback, callback)
            },
            "Kyren" to {
                val kyren = RaghavKyren()
                kyren.loadLinksByAnilistId(aniId, title, episode, isDub, subtitleCallback, callback)
            },
            "ReAnime" to {
                val reanime = RaghavReAnime()
                reanime.loadLinksByAnilistId(aniId, episode, isDub, subtitleCallback, callback)
            },
        )

        // known fast sources first so links show up early, nothing gets skipped
        val ordered = sources
            .mapIndexed { idx, src -> Triple(RaghavSourceStats.priority(src.first), idx, src) }
            .sortedByDescending { it.first }
            .map { it.third }

        val concurrency = RaghavPerf.sourceConcurrency()

        // slow sources keep resolving past the wait cap instead of being cut off
        linksJob?.cancel()
        linksJob = loadScope.launch {
            RaghavPerf.runLimitedAsync(concurrency, ordered.map { (name, task) ->
                { runBoundedSource(name, task) }
            })
        }
        withTimeoutOrNull(MAX_SOURCE_WAIT_MS) { linksJob?.join() }

        return true
    }

    private suspend fun runBoundedSource(tag: String, task: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        var ok = false
        try {
            try {
                task()
                ok = true
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                delay(2000)
                task()
                ok = true
            }
            RaghavSourceStats.record(tag, ok, System.currentTimeMillis() - start)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            RaghavSourceStats.record(tag, ok, System.currentTimeMillis() - start)
        }
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

    private suspend fun warmSource(provider: String, animeKey: String, isDub: Boolean, resolve: suspend () -> Map<Int, String>?) {
        try {
            SourceCache.warm(provider, animeKey, isDub, resolve)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // a failed warm only means that show resolves on click
        }
    }

    private fun prefetchSources(anilistId: Int, title: String, jpTitle: String?, year: Int?) {
        if (anilistId <= 0 || title.isBlank()) return
        val animeKey = anilistId.toString()
        val titles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        val targets = listOfNotNull(title, jpTitle)

        prefetchJob?.cancel()
        prefetchJob = prefetchScope.launch {
            // let the show page render before any background work starts
            delay(750)
            for (isDub in listOf(false, true)) {
                if (!isActive) return@launch
                RaghavPerf.runLimitedAsync(RaghavPerf.prefetchConcurrency(), listOf(
                    { warmSource("AniWaves", animeKey, isDub) { resolveAniWaves(titles, targets, null, isDub)?.episodes } },
                    { warmSource("2DHive", animeKey, isDub) { resolveTwoDHive(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("AniKoto", animeKey, isDub) { resolveAniKoto(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("Animo", animeKey, isDub) { resolveAnimo(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("AniNami", animeKey, isDub) { resolveAniNami(anilistId, null, isDub)?.episodes } }
                ))
            }
        }
    }

    private suspend fun resolveMiruro(anilistId: Int, episode: Int?, isDub: Boolean): SourceCache.Match? {
        val miruro = Miruro()
        val loadResult = miruro.load("${miruro.mainUrl}/info/$anilistId") as? com.lagradost.cloudstream3.AnimeLoadResponse ?: return null
        return matchFrom(loadResult, episode, isDub)
    }

    private suspend fun resolveAniNami(anilistId: Int, episode: Int?, isDub: Boolean): SourceCache.Match? {
        val aniNami = RaghavAniNami()
        val loadResult = aniNami.load("${aniNami.mainUrl}/anime/$anilistId") as? com.lagradost.cloudstream3.AnimeLoadResponse ?: return null
        return matchFrom(loadResult, episode, isDub)
    }

    private fun matchFrom(loadResult: com.lagradost.cloudstream3.AnimeLoadResponse, episode: Int?, isDub: Boolean): SourceCache.Match? {
        val epList = loadResult.episodes?.get(if (isDub) DubStatus.Dubbed else DubStatus.Subbed)
        val map = episodeMap(epList) ?: return null
        return SourceCache.Match(map, episode != null && map.containsKey(episode))
    }

    private fun episodeMap(epList: List<Episode>?): Map<Int, String>? {
        if (epList.isNullOrEmpty()) return null
        val map = epList.mapNotNull { e -> e.episode?.let { it to e.data } }.toMap()
        return map.ifEmpty { null }
    }

    private suspend fun resolveAniSuge(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val aniSuge = AniSugeProvider()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { aniSuge.search(it) },
            doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "AniSuge")
    }

    private suspend fun resolveTwoDHive(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val twoDHive = RaghavTwoDHive()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { twoDHive.search(it) },
            doLoad = { twoDHive.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "2DHive")
    }

    private suspend fun resolveAniKoto(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val anikoto = RaghavAnikoto()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { anikoto.search(it) },
            doLoad = { anikoto.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "AniKoto")
    }

    private suspend fun resolveAnimo(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val animo = RaghavAnimo()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { animo.search(it) },
            doLoad = { animo.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "Animo")
    }

    private suspend fun resolveSenshi(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val senshi = RaghavSenshi()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { senshi.search(it) },
            doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "Senshi")
    }

    private suspend fun resolveAniWaves(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean): SourceCache.Match? {
        val aniWaves = AniWaves()
        val cleanedTargets = targets.map { cleanTitle(it) }
        var failedSearches = 0
        var fallback: Map<Int, String>? = null
        for (t in titles) {
            val searchResults = try { aniWaves.search(t) } catch (e: Throwable) {
                failedSearches++
                continue
            }
            val candidates = searchResults.filter { r -> cleanedTargets.contains(cleanTitle(r.name)) }
            for (result in candidates) {
                try {
                    val loadResult = aniWaves.load(result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                    val epList = if (isDub) {
                        loadResult.episodes?.get(DubStatus.Dubbed)?.takeIf { it.isNotEmpty() }
                            ?: loadResult.episodes?.get(DubStatus.Subbed)
                    } else {
                        loadResult.episodes?.get(DubStatus.Subbed)
                    }
                    val map = episodeMap(epList) ?: continue
                    if (fallback == null) fallback = map
                    if (episode == null || map.containsKey(episode)) {
                        return SourceCache.Match(map, episode != null)
                    }
                } catch (e: Throwable) {
                    continue
                }
            }
        }
        if (fallback != null) return SourceCache.Match(fallback, false)
        if (titles.isNotEmpty() && failedSearches == titles.size) {
            throw IllegalStateException("AniWaves is unreachable right now")
        }
        return null
    }

    private suspend fun findEpisodeMap(
        searchTitles: List<String>,
        targetTitles: List<String>,
        episode: Int?,
        isDub: Boolean,
        year: Int?,
        doSearch: suspend (String) -> List<SearchResponse>,
        doLoad: suspend (String) -> com.lagradost.cloudstream3.AnimeLoadResponse?,
        sourceTag: String = "Source"
    ): SourceCache.Match? {
        val cleanedTargets = targetTitles.map { cleanTitle(it) }
        val epKey = if (isDub) DubStatus.Dubbed else DubStatus.Subbed

        val targetSeasonNum = targetTitles.firstNotNullOfOrNull { extractSeasonNumber(it) }

        data class Candidate(val combinedScore: Int, val titleScore: Int, val result: SearchResponse)

        val allCandidates = mutableListOf<Candidate>()
        var failedSearches = 0
        for (t in searchTitles) {
            val searchResults = try { doSearch(t) } catch (e: Throwable) {
                failedSearches++
                continue
            }
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

        allCandidates.sortByDescending { it.combinedScore }

        var fallback: Map<Int, String>? = null
        for (cand in allCandidates) {
            if (cand.titleScore < 2) {
                break
            }
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val map = episodeMap(loadResult.episodes?.get(epKey)) ?: continue
                if (fallback == null) fallback = map
                if (episode == null || map.containsKey(episode)) {
                    return SourceCache.Match(map, episode != null)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
            }
        }

        if (fallback != null) return SourceCache.Match(fallback, false)
        if (searchTitles.isNotEmpty() && failedSearches == searchTitles.size) {
            throw IllegalStateException("$sourceTag is unreachable right now")
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
        private const val MAX_SOURCE_WAIT_MS = 35_000L
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
