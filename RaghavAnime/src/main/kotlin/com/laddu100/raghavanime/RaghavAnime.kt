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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import com.raghav.donation.DonationManager
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue

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

    // one loading pass per episode, shared by every player open of that
    // episode so the sources are not cancelled and restarted on re-entry
    private class SourcePass(val key: String, val start: Long) {
        val links = ConcurrentLinkedQueue<ExtractorLink>()
        val subs = ConcurrentLinkedQueue<SubtitleFile>()
        var job: Job? = null
    }

    private val passLock = Any()
    private var currentPass: SourcePass? = null

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
                val cAccent = Color.parseColor("#0A84FF")
                val cText = Color.parseColor("#FFFFFF")
                val cTextSub = Color.parseColor("#98989F")
                val d = ctx.resources.displayMetrics.density
                fun Int.dp() = (this * d).toInt()

                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp(), 28.dp(), 24.dp(), 24.dp())
                    background = GradientDrawable().apply {
                        cornerRadius = 24 * d
                        setColor(Color.parseColor("#17171B"))
                        setStroke(1.dp(), Color.parseColor("#30FFFFFF"))
                    }
                }

                container.addView(TextView(ctx).apply {
                    text = "AniList API is Down"
                    textSize = 20f
                    setTextColor(cText)
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
                        cornerRadius = 16 * d
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
        DonationManager.checkAndShow()
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
                    val anilistId = Regex("""/info/(\d+)""").find(item.url)?.groupValues?.get(1)?.toIntOrNull()
                    val poster = CustomPosters.poster(anilistId, null, false, item.posterUrl)
                    newAnimeSearchResponse(CustomPosters.cardTitle(item.title), item.url, TvType.Anime) {
                        this.posterUrl = poster
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

            buildRowItems(mediaList)
        } catch (e: Exception) {
            val cached = homePageCache[request.data]
            if (cached != null) {
                buildRowItems(cached)
            } else {
                kitsuRow(request.data, page)
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // every custom poster costs one ani.zip lookup, resolve the row in
    // parallel and keep the anilist cover when the feature is off or fails
    private suspend fun buildRowItems(mediaList: List<AniListMedia>): List<SearchResponse> =
        coroutineScope {
            mediaList.map { media ->
                async {
                    val id = media.id ?: return@async null
                    val title = media.title?.english ?: media.title?.romaji ?: return@async null
                    val fallback = media.coverImage?.extraLarge ?: media.coverImage?.large
                    val poster = CustomPosters.poster(id, null, media.format == "MOVIE", fallback)
                    newAnimeSearchResponse(CustomPosters.cardTitle(title), "$mainUrl/info/$id", TvType.Anime) {
                        this.posterUrl = poster
                        addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                    }
                }
            }.awaitAll().filterNotNull()
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
                val fallback = media.coverImage?.extraLarge ?: media.coverImage?.large
                val poster = CustomPosters.poster(id, null, media.format == "MOVIE", fallback)
                newAnimeSearchResponse(CustomPosters.cardTitle(title), "$mainUrl/info/$id", TvType.Anime) {
                    this.posterUrl = poster
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
                }
            }
        } catch (e: Exception) {
            kitsuSearch(query)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val kitsuId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        if (kitsuId != null) return kitsuLoad(kitsuId, url, null)

        val anilistId = Regex("""/info/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        isAniListDown()

        val media = try {
            val infoText = anilistQuery(INFO_QUERY, mapOf("id" to anilistId))
            val infoResponse = parseJson<AniListResponse>(infoText)
            infoResponse.data?.Media
        } catch (e: Exception) {
            null
        } ?: return kitsuLoadByAnilistId(anilistId, url)

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

        prefetchSources(anilistId, anilistId, title, jpTitle, year)

        val clearLogo = TmdbArtwork.clearLogo(
            animeMetaData?.mappings?.themoviedb_id,
            media.format == "MOVIE"
        )

        val poster = CustomPosters.poster(anilistId, null, media.format == "MOVIE", posterUrl, animeMetaData?.mappings)

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bannerUrl
            this.logoUrl = clearLogo
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

    private suspend fun kitsuRow(request: String, page: Int): List<SearchResponse> {
        val offset = (page - 1) * 20
        val url = when (request) {
            "TRENDING" -> "$KITSU_API/trending/anime?page[limit]=20&page[offset]=$offset"
            "POPULAR" -> "$KITSU_API/anime?sort=popularityRank&page[limit]=20&page[offset]=$offset"
            "TOP_RATED" -> "$KITSU_API/anime?sort=ratingRank&page[limit]=20&page[offset]=$offset"
            "RECENT" -> "$KITSU_API/anime?filter[status]=current&sort=-startDate&page[limit]=20&page[offset]=$offset"
            else -> "$KITSU_API/trending/anime?page[limit]=20&page[offset]=$offset"
        }

        val mediaList = try {
            val responseText = app.get(url, headers = KITSU_HEADERS, timeout = 15L).text
            parseJson<KitsuResponse>(responseText).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return coroutineScope {
            mediaList.map { item ->
                async {
                    val id = item.id?.toIntOrNull() ?: return@async null
                    val attrs = item.attributes ?: return@async null
                    val title = attrs.canonicalTitle ?: return@async null
                    val fallback = attrs.posterImage?.large ?: attrs.posterImage?.original
                    val poster = CustomPosters.poster(null, id, attrs.subtype == "movie", fallback)
                    newAnimeSearchResponse(CustomPosters.cardTitle(title), "https://kitsu.io/anime/$id", TvType.Anime) {
                        this.posterUrl = poster
                        addDubStatus(dubExist = true, subExist = true, dubEpisodes = attrs.episodeCount, subEpisodes = attrs.episodeCount)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun kitsuSearch(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val mediaList = try {
            val responseText = app.get("$KITSU_API/anime?filter[text]=$encoded&page[limit]=20", headers = KITSU_HEADERS, timeout = 15L).text
            parseJson<KitsuResponse>(responseText).data ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return mediaList.mapNotNull { item ->
            val id = item.id?.toIntOrNull() ?: return@mapNotNull null
            val attrs = item.attributes ?: return@mapNotNull null
            val title = attrs.canonicalTitle ?: return@mapNotNull null
            val fallback = attrs.posterImage?.large ?: attrs.posterImage?.original
            val poster = CustomPosters.poster(null, id, attrs.subtype == "movie", fallback)
            newAnimeSearchResponse(CustomPosters.cardTitle(title), "https://kitsu.io/anime/$id", TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(dubExist = true, subExist = true, dubEpisodes = attrs.episodeCount, subEpisodes = attrs.episodeCount)
            }
        }
    }

    private suspend fun kitsuLoadByAnilistId(anilistId: Int, url: String): LoadResponse? {
        val meta = try {
            parseAnimeData(app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text)
        } catch (_: Exception) { null } ?: return null
        val kitsuId = meta.mappings?.kitsu_id ?: return null
        return kitsuLoad(kitsuId, url, anilistId, meta)
    }

    private suspend fun kitsuLoad(kitsuId: Int, url: String, anilistId: Int?, aniZip: MetaAnimeData? = null): LoadResponse? {
        val media = try {
            val infoText = app.get("$KITSU_API/anime/$kitsuId?include=mappings", headers = KITSU_HEADERS, timeout = 15L).text
            parseJson<KitsuSingleResponse>(infoText)
        } catch (e: Exception) {
            return null
        }

        val data = media.data ?: return null
        val attrs = data.attributes ?: return null
        val title = attrs.canonicalTitle ?: attrs.titles?.en ?: attrs.titles?.en_jp ?: "Unknown"
        val jpTitle = attrs.titles?.ja_jp ?: attrs.titles?.en_jp
        val posterUrl = attrs.posterImage?.large ?: attrs.posterImage?.original
        val bannerUrl = attrs.coverImage?.large ?: attrs.coverImage?.original
        val plot = attrs.synopsis
        val year = attrs.startDate?.substring(0, 4)?.toIntOrNull()
        val animeScore = attrs.averageRating?.toFloatOrNull()?.let { (it / 10).toInt() }
        val epCount = attrs.episodeCount ?: 0
        val subtype = attrs.subtype
        val status = attrs.status

        val tags = try { fetchCategories(kitsuId) } catch (_: Exception) { emptyList() }

        val tvType = when (subtype) {
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (status) {
            "current" -> ShowStatus.Ongoing
            "finished" -> ShowStatus.Completed
            else -> null
        }

        val malId = media.included?.mapNotNull { inc ->
            val incAttrs = inc.attributes ?: return@mapNotNull null
            if (incAttrs.externalSite == "myanimelist/anime") {
                incAttrs.externalId?.toIntOrNull()
            } else null
        }?.firstOrNull()

        val kitsuAnilistId = media.included?.mapNotNull { inc ->
            val incAttrs = inc.attributes ?: return@mapNotNull null
            if (incAttrs.externalSite == "anilist/anime") {
                incAttrs.externalId?.toIntOrNull()
            } else null
        }?.firstOrNull()

        val resolvedAnilistId = anilistId ?: kitsuAnilistId

        val animeMetaData = aniZip ?: try {
            val idParam = if (resolvedAnilistId != null) "anilist_id=$resolvedAnilistId" else "kitsu_id=$kitsuId"
            parseAnimeData(app.get("https://api.ani.zip/mappings?$idParam").text)
        } catch (_: Exception) { null }

        val anizipNumericCount = animeMetaData?.episodes?.keys
            ?.filterNotNull()
            ?.filter { it.toIntOrNull() != null }
            ?.size ?: 0

        var totalEps = epCount.takeIf { it > 0 } ?: anizipNumericCount ?: 0
        if (totalEps == 0) totalEps = 1

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (i in 1..totalEps) {
            val epData = animeMetaData?.episodes?.get(i.toString())
            val epTitle = epData?.title?.get("en") ?: epData?.title?.get("ja") ?: epData?.title?.get("x-jat") ?: "Episode $i"
            val epDesc = epData?.overview ?: "No summary available"
            val epPoster = epData?.image ?: posterUrl

            val subLinkData = LinkData(animeId = resolvedAnilistId ?: 0, kitsuId = kitsuId, malId = malId, title = title, jpTitle = jpTitle, episode = i, isDub = false, year = year).toJson()
            val dubLinkData = LinkData(animeId = resolvedAnilistId ?: 0, kitsuId = kitsuId, malId = malId, title = title, jpTitle = jpTitle, episode = i, isDub = true, year = year).toJson()

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

        prefetchSources(kitsuId, resolvedAnilistId ?: 0, title, jpTitle, year)

        val clearLogo = TmdbArtwork.clearLogo(
            animeMetaData?.mappings?.themoviedb_id,
            subtype == "movie"
        )

        val poster = CustomPosters.poster(resolvedAnilistId, kitsuId, subtype == "movie", posterUrl, animeMetaData?.mappings)

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bannerUrl
            this.logoUrl = clearLogo
            this.year = year
            this.plot = plot
            this.tags = tags
            if (animeScore != null) this.score = Score.from10(animeScore.toString())
            this.showStatus = showStatus
            if (resolvedAnilistId != null) addAniListId(resolvedAnilistId)
            if (malId != null) addMalId(malId)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private suspend fun fetchCategories(kitsuId: Int): List<String> {
        return try {
            val url = "$KITSU_API/anime/$kitsuId/categories"
            val responseText = app.get(url, headers = KITSU_HEADERS, timeout = 10L).text
            val response = parseJson<KitsuResponse>(responseText)
            response.data?.mapNotNull { it.attributes?.title }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        rawCallback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val aniId = linkData.animeId
        val title = linkData.title
        val jpTitle = linkData.jpTitle
        val episode = linkData.episode
        val isDub = linkData.isDub

        if (aniId > 0 && RaghavAnimeFeatures.isEnabled("watch_time")) {
            try { RaghavAnimeFeatures.recordWatchTime(aniId, title, null, 24 * 60 * 1000L) } catch (_: Exception) {}
        }

        val animeKey = (linkData.kitsuId.takeIf { it > 0 } ?: aniId).toString()
        val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        val targetTitles = listOfNotNull(title, jpTitle)

        val episodeKey = "$animeKey|$episode|${if (isDub) "dub" else "sub"}"
        var reuse = false
        val pass: SourcePass
        synchronized(passLock) {
            val running = currentPass
            val runningJob = running?.job
            if (running != null && runningJob != null && running.key == episodeKey &&
                (runningJob.isActive ||
                    System.currentTimeMillis() - running.start < PASS_TTL_MS)) {
                reuse = true
                pass = running
            } else {
                runningJob?.cancel()
                pass = SourcePass(episodeKey, System.currentTimeMillis())
                currentPass = pass
            }
        }

        // everything the pass emits is kept so a later open of the episode
        // gets the whole set replayed instead of re-resolving every source
        val callback: (ExtractorLink) -> Unit = { link ->
            pass.links.add(link)
            rawCallback(link)
        }
        val subs: (SubtitleFile) -> Unit = { file ->
            pass.subs.add(file)
            subtitleCallback(file)
        }

        val sources = listOf<Pair<String, suspend () -> Unit>>(
            "Miruro" to {
                if (aniId > 0) {
                    val epData = SourceCache.episodeData("Miruro", animeKey, isDub, episode) {
                        resolveMiruro(aniId, episode, isDub)
                    }
                    if (epData != null) {
                        Miruro().loadLinks(epData, false, subs, callback)
                    }
                }
            },
            "AniSuge" to {
                val epData = SourceCache.episodeData("AniSuge", animeKey, isDub, episode) {
                    resolveAniSuge(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    AniSugeProvider().loadLinks(epData, false, subs, callback)
                }
            },
            "AniWaves" to {
                val epData = SourceCache.episodeData("AniWaves", animeKey, isDub, episode) {
                    resolveAniWaves(searchTitles, targetTitles, episode, isDub)
                }
                if (epData != null) {
                    AniWaves().loadLinks(epData, false, subs, callback)
                }
            },
            "AniKage" to {
                if (aniId > 0) {
                    val anikage = RaghavAniKage()
                    anikage.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subs, callback)
                }
            },
            "2DHive" to {
                val epData = SourceCache.episodeData("2DHive", animeKey, isDub, episode) {
                    resolveTwoDHive(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavTwoDHive().loadLinks(epData, false, subs, callback)
                }
            },
            "AniKoto" to {
                val epData = SourceCache.episodeData("AniKoto", animeKey, isDub, episode) {
                    resolveAniKoto(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavAnikoto().loadLinks(epData, false, subs, callback)
                }
            },
            "GoTaku" to {
                val epData = SourceCache.episodeData("GoTaku", animeKey, isDub, episode) {
                    resolveGoTaku(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavGoTaku().loadLinks(epData, false, subs, callback)
                }
            },
            "Anidap" to {
                if (aniId > 0) {
                    val anidap = RaghavAnidap()
                    anidap.loadLinksByAnilistId(aniId, episode, isDub, subs, callback)
                }
            },
            "AniPM" to {
                val anipm = RaghavAniPM()
                anipm.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, subs, callback)
            },
            "Senshi" to {
                val epData = SourceCache.episodeData("Senshi", animeKey, isDub, episode) {
                    resolveSenshi(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavSenshi().loadLinks(epData, false, subs, callback)
                }
            },
            "AniNami" to {
                if (aniId > 0) {
                    val epData = SourceCache.episodeData("AniNami", animeKey, isDub, episode) {
                        resolveAniNami(aniId, episode, isDub)
                    }
                    if (epData != null) {
                        RaghavAniNami().loadLinks(epData, false, subs, callback)
                    }
                }
            },
            "AniChan" to {
                if (aniId > 0) {
                    val anichan = RaghavAniChan()
                    anichan.loadLinksByAnilistId(aniId, episode, isDub, subs, callback)
                }
            },
            "Xanime" to {
                val epData = SourceCache.episodeData("Xanime", animeKey, isDub, episode) {
                    resolveXanime(searchTitles, targetTitles, episode, isDub, linkData.year)
                }
                if (epData != null) {
                    RaghavXanime().loadLinks(epData, false, subs, callback)
                }
            },
            "Kyren" to {
                if (aniId > 0) {
                    val kyren = RaghavKyren()
                    kyren.loadLinksByAnilistId(aniId, title, episode, isDub, subs, callback)
                }
            },
            "ReAnime" to {
                if (aniId > 0) {
                    val reanime = RaghavReAnime()
                    reanime.loadLinksByAnilistId(aniId, episode, isDub, subs, callback)
                }
            },
            "Anv" to {
                if (aniId > 0) {
                    val anv = RaghavAnv()
                    anv.loadLinksByAnilistId(aniId, episode, isDub, subs, callback)
                }
            },
        )

        val ordered = sources
            .mapIndexed { idx, src -> Triple(RaghavSourceStats.priority(src.first), idx, src) }
            .sortedByDescending { it.first }
            .map { it.third }

        val concurrency = RaghavPerf.sourceConcurrency()

        if (!reuse) {
            pass.job = loadScope.launch {
                RaghavPerf.runLimitedAsync(concurrency, ordered.map { (name, task) ->
                    { runBoundedSource(name, task) }
                })
            }
        } else {
            pass.links.forEach(rawCallback)
            pass.subs.forEach(subtitleCallback)
        }

        // the player only collects links while this call is open, so it stays
        // up for the full pass and every source gets its turn
        withTimeoutOrNull(MAX_SOURCE_WAIT_MS) { pass.job?.join() }

        pass.links.forEach(rawCallback)
        pass.subs.forEach(subtitleCallback)

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
                delay(750)
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
        } catch (_: Exception) {}
    }

    private fun prefetchSources(animeKey: Int, aniId: Int, title: String, jpTitle: String?, year: Int?) {
        if (animeKey <= 0 || title.isBlank()) return
        val key = animeKey.toString()
        val titles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        val targets = listOfNotNull(title, jpTitle)

        prefetchJob?.cancel()
        prefetchJob = prefetchScope.launch {
            delay(750)
            launch {
                try { RaghavSenshiVhost.warmup() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            for (isDub in listOf(false, true)) {
                if (!isActive) return@launch
                RaghavPerf.runLimitedAsync(RaghavPerf.prefetchConcurrency(), listOf(
                    { warmSource("AniWaves", key, isDub) { resolveAniWaves(titles, targets, null, isDub)?.episodes } },
                    { warmSource("2DHive", key, isDub) { resolveTwoDHive(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("AniKoto", key, isDub) { resolveAniKoto(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("GoTaku", key, isDub) { resolveGoTaku(titles, targets, null, isDub, year)?.episodes } },
                    { if (aniId > 0) warmSource("AniNami", key, isDub) { resolveAniNami(aniId, null, isDub)?.episodes } },
                    { warmSource("Xanime", key, isDub) { resolveXanime(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("Senshi", key, isDub) { resolveSenshi(titles, targets, null, isDub, year)?.episodes } },
                    { warmSource("AniSuge", key, isDub) { resolveAniSuge(titles, targets, null, isDub, year)?.episodes } },
                    { if (aniId > 0) RaghavAniChan().warm() }
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

    private suspend fun resolveGoTaku(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val gotaku = RaghavGoTaku()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { gotaku.search(it) },
            doLoad = { gotaku.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "GoTaku")
    }

    private suspend fun resolveSenshi(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val senshi = RaghavSenshi()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { senshi.search(it) },
            doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "Senshi")
    }

    private suspend fun resolveXanime(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val xanime = RaghavXanime()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { xanime.search(it) },
            doLoad = { xanime.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "Xanime")
    }

    private suspend fun resolveAniWaves(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean): SourceCache.Match? {
        val aniWaves = AniWaves()
        val cleanedTargets = targets.map { cleanTitle(it) }

        // both aliases are searched together so one slow call cannot stall the source
        val searches = if (titles.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                titles.map { t ->
                    async {
                        searchTitle(t) { q -> aniWaves.search(q) }
                    }
                }.awaitAll()
            }
        }

        data class Wave(val score: Int, val result: SearchResponse)
        val candidates = mutableListOf<Wave>()
        for (searchResults in searches) {
            for (r in searchResults.orEmpty()) {
                val score = titleMatchScore(cleanTitle(r.name), cleanedTargets)
                if (score > 0) candidates.add(Wave(score, r))
            }
        }
        val ordered = candidates
            .distinctBy { it.result.url }
            .sortedByDescending { it.score }

        var fallback: Map<Int, String>? = null
        var loadTried = 0
        var openedAny = false
        // exact names first, close names follow when no exact one carried the episode
        for (tier in 2 downTo 1) {
            for (cand in ordered) {
                if (cand.score != tier) continue
                loadTried++
                val loadResult = try {
                    aniWaves.load(cand.result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                } ?: continue
                openedAny = true
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
            }
        }
        if (fallback != null) return SourceCache.Match(fallback, false)
        if (loadTried > 0 && !openedAny) {
            throw IllegalStateException("AniWaves is unreachable right now")
        }
        if (titles.isNotEmpty() && searches.all { it == null }) {
            throw IllegalStateException("AniWaves is unreachable right now")
        }
        return null
    }

    private fun titleMatchScore(cleaned: String, cleanedTargets: List<String>): Int {
        if (cleaned.isBlank()) return 0
        if (cleanedTargets.contains(cleaned)) return 2
        if (cleaned.length >= 4 && cleanedTargets.any { tgt -> tgt.contains(cleaned) || cleaned.contains(tgt) }) return 1
        return 0
    }

    // some sites cannot find "Title: Subtitle" or a long romaji, a shorter ask saves them
    private fun searchFallbacks(title: String): List<String> {
        val out = mutableListOf<String>()
        val base = title.substringBefore(':').trim()
        if (base.length >= 3 && !base.equals(title, ignoreCase = true)) out.add(base)
        val words = title.split(' ').filter { it.isNotBlank() }
        if (words.size > 4) out.add(words.take(4).joinToString(" "))
        val first = words.firstOrNull()?.trim()?.trim { !it.isLetterOrDigit() } ?: ""
        if (first.length >= 4 && !first.equals(title, ignoreCase = true) &&
            out.none { it.equals(first, ignoreCase = true) }) out.add(first)
        return out
    }

    private suspend fun searchTitle(
        query: String,
        doSearch: suspend (String) -> List<SearchResponse>
    ): List<SearchResponse>? {
        return try {
            val direct = doSearch(query)
            if (direct.isNotEmpty()) {
                direct
            } else {
                var found = emptyList<SearchResponse>()
                for (alt in searchFallbacks(query)) {
                    try {
                        val altResults = doSearch(alt)
                        if (altResults.isNotEmpty()) {
                            found = altResults
                            break
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                    }
                }
                found
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }
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

        // every alias is searched at once so a slow site does not stall the rest
        val searches = if (searchTitles.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                searchTitles.map { t ->
                    async {
                        searchTitle(t, doSearch)
                    }
                }.awaitAll()
            }
        }

        val allCandidates = mutableListOf<Candidate>()
        for (searchResults in searches) {
            for (r in searchResults.orEmpty()) {
                val c = cleanTitle(r.name)
                val titleScore = titleMatchScore(c, cleanedTargets)
                if (titleScore == 0) continue

                val candSeasonNum = extractSeasonNumber(r.name)
                val candYear = extractYear(r.name)
                val yearScore = if (year != null && (candYear == year || r.name.contains(year.toString()))) 1 else 0
                val seasonScore = if (targetSeasonNum != null && candSeasonNum == targetSeasonNum) 1 else 0

                val combinedScore = titleScore * 10 + yearScore * 5 + seasonScore * 3
                allCandidates.add(Candidate(combinedScore, titleScore, r))
            }
        }

        val ordered = allCandidates
            .distinctBy { it.result.url }
            .sortedByDescending { it.combinedScore }

        var fallback: Map<Int, String>? = null
        var loadTried = 0
        var openedAny = false
        // exact names carry the episode, close names only step in when no exact
        // one did, so a site that spells the title differently still loads
        for (tier in 2 downTo 1) {
            for (cand in ordered) {
                if (cand.titleScore != tier) continue
                if (tier == 1) {
                    val candSeasonNum = extractSeasonNumber(cand.result.name)
                    val candYear = extractYear(cand.result.name)
                    if (targetSeasonNum != null && candSeasonNum != null && candSeasonNum != targetSeasonNum) continue
                    if (targetSeasonNum == null && candSeasonNum != null && candSeasonNum > 1) continue
                    if (year != null && candYear != null && Math.abs(candYear - year) > 1) continue
                }
                loadTried++
                val loadResult = try {
                    doLoad(cand.result.url)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    null
                } ?: continue
                openedAny = true
                val map = episodeMap(loadResult.episodes?.get(epKey)) ?: continue
                if (fallback == null) fallback = map
                if (episode == null || map.containsKey(episode)) {
                    return SourceCache.Match(map, episode != null)
                }
            }
        }

        if (fallback != null) return SourceCache.Match(fallback, false)
        // nothing opened although matches existed: the site hiccuped, so let the
        // caller retry instead of remembering the title as absent
        if (loadTried > 0 && !openedAny) {
            throw IllegalStateException("$sourceTag is unreachable right now")
        }
        if (searchTitles.isNotEmpty() && searches.all { it == null }) {
            throw IllegalStateException("$sourceTag is unreachable right now")
        }
        return null
    }

    data class LinkData(
        val animeId: Int = 0,
        val kitsuId: Int = 0,
        val malId: Int? = null,
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
        private const val PASS_TTL_MS = 10 * 60 * 1000L
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

const val KITSU_API = "https://kitsu.io/api/edge"
val KITSU_HEADERS = mapOf(
    "Accept" to "application/vnd.api+json",
    "Content-Type" to "application/vnd.api+json"
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuResponse(
    @JsonProperty("data") val data: List<KitsuMedia>? = null,
    @JsonProperty("included") val included: List<KitsuMedia>? = null,
    @JsonProperty("meta") val meta: KitsuMeta? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuSingleResponse(
    @JsonProperty("data") val data: KitsuMedia? = null,
    @JsonProperty("included") val included: List<KitsuMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuMeta(
    @JsonProperty("count") val count: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuMedia(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("attributes") val attributes: KitsuAttributes? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuAttributes(
    @JsonProperty("canonicalTitle") val canonicalTitle: String? = null,
    @JsonProperty("titles") val titles: KitsuTitles? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("subtype") val subtype: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int? = null,
    @JsonProperty("episodeLength") val episodeLength: Int? = null,
    @JsonProperty("startDate") val startDate: String? = null,
    @JsonProperty("endDate") val endDate: String? = null,
    @JsonProperty("averageRating") val averageRating: String? = null,
    @JsonProperty("popularityRank") val popularityRank: Int? = null,
    @JsonProperty("ratingRank") val ratingRank: Int? = null,
    @JsonProperty("posterImage") val posterImage: KitsuImage? = null,
    @JsonProperty("coverImage") val coverImage: KitsuImage? = null,
    @JsonProperty("externalSite") val externalSite: String? = null,
    @JsonProperty("externalId") val externalId: String? = null,
    @JsonProperty("title") val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuTitles(
    @JsonProperty("en") val en: String? = null,
    @JsonProperty("en_jp") val en_jp: String? = null,
    @JsonProperty("ja_jp") val ja_jp: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuImage(
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("original") val original: String? = null,
    @JsonProperty("medium") val medium: String? = null,
    @JsonProperty("small") val small: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuCategory(
    @JsonProperty("attributes") val attributes: KitsuCategoryAttrs? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KitsuCategoryAttrs(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null
)
