package com.laddu100.raghavanime

import com.lagradost.cloudstream3.CommonActivity.activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.ScrollView

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import java.net.URLEncoder

class RaghavAnime : MainAPI() {
    override var mainUrl = "https://kitsu.io"
    override var name = "RaghavAnime(Kitsu)"
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
    private var kitsuDownPopupShown = false

    private val downCheckLock = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var lastDownCheckTime: Long = 0L
    private val DOWN_CHECK_INTERVAL = 60_000L

    private suspend fun isKitsuDown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDownCheckTime < DOWN_CHECK_INTERVAL) {
            return kitsuDownPopupShown
        }
        if (!downCheckLock.tryLock()) return kitsuDownPopupShown
        try {
            lastDownCheckTime = now
            val response = try {
                app.get("$KITSU_API/anime?page[limit]=1", headers = KITSU_HEADERS, timeout = 10_000L).text
            } catch (e: Exception) {
                return false
            }
            if (response.contains("\"data\"")) {
                kitsuDownPopupShown = false
                return false
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            downCheckLock.unlock()
        }
    }

    private fun showKitsuDownPopup() {
        if (kitsuDownPopupShown) return
        kitsuDownPopupShown = true
        val ctx = activity ?: return
        ctx.runOnUiThread {
            try {
                val cBg = Color.parseColor("#0A0A0A")
                val cAccent = Color.parseColor("#FF1744")
                val cTextSub = Color.parseColor("#9E9E9E")
                val d = ctx.resources.displayMetrics.density
                fun Int.dp() = (this * d).toInt()

                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp(), 28.dp(), 24.dp(), 24.dp())
                    setBackgroundColor(cBg)
                }

                container.addView(TextView(ctx).apply {
                    text = "Kitsu API is Down"
                    textSize = 20f
                    setTextColor(cAccent)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 12.dp())
                })

                container.addView(TextView(ctx).apply {
                    text = "RaghavAnime(Kitsu) depends on the Kitsu API for anime metadata, search, and homepage content.\n\nThis may be because the Kitsu API is disabled from their end, or something is wrong from our end. Whichever the case, it will soon be fixed.\n\nIf Kitsu is disabled from their end, everything will work again once Kitsu restores services.\n\nUse other providers in the meantime — there are many others in the raghav repo."
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
                Log.e("RaghavAnimeKitsu", "showKitsuDownPopup: ${e.message}")
            }
        }
    }

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending Now",
        "POPULAR" to "Most Popular",
        "TOP_RATED" to "Top Rated",
        "RECENT" to "Currently Airing",
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
                Log.e("RaghavAnimeKitsu", "[Recommendations] failed: ${e.message}")
                newHomePageResponse(request.name, emptyList())
            }
        }

        isKitsuDown()

        val offset = (page - 1) * 20
        val url = when (request.data) {
            "TRENDING" -> "$KITSU_API/trending/anime?page[limit]=20&page[offset]=$offset"
            "POPULAR" -> "$KITSU_API/anime?sort=popularityRank&page[limit]=20&page[offset]=$offset"
            "TOP_RATED" -> "$KITSU_API/anime?sort=ratingRank&page[limit]=20&page[offset]=$offset"
            "RECENT" -> "$KITSU_API/anime?filter[status]=current&sort=-startDate&page[limit]=20&page[offset]=$offset"
            else -> "$KITSU_API/trending/anime?page[limit]=20&page[offset]=$offset"
        }

        val home = try {
            val responseText = app.get(url, headers = KITSU_HEADERS, timeout = 15_000L).text
            val response = parseJson<KitsuResponse>(responseText)
            val mediaList = response.data ?: emptyList()

            if (mediaList.isNotEmpty()) {
                homePageCache[request.data] = mediaList
            }

            mediaList.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val attrs = item.attributes ?: return@mapNotNull null
                val title = attrs.canonicalTitle ?: return@mapNotNull null
                val posterUrl = attrs.posterImage?.large ?: attrs.posterImage?.original
                val epCount = attrs.episodeCount
                newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = epCount, subEpisodes = epCount)
                }
            }
        } catch (e: Exception) {
            val cached = homePageCache[request.data]
            if (cached != null) {
                cached.mapNotNull { item ->
                    val id = item.id ?: return@mapNotNull null
                    val attrs = item.attributes ?: return@mapNotNull null
                    val title = attrs.canonicalTitle ?: return@mapNotNull null
                    val posterUrl = attrs.posterImage?.large ?: attrs.posterImage?.original
                    val epCount = attrs.episodeCount
                    newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                        this.posterUrl = posterUrl
                        addDubStatus(dubExist = true, subExist = true, dubEpisodes = epCount, subEpisodes = epCount)
                    }
                }
            } else {
                emptyList()
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        isKitsuDown()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = try {
            val url = "$KITSU_API/anime?filter[text]=$encoded&page[limit]=20"
            val responseText = app.get(url, headers = KITSU_HEADERS, timeout = 15_000L).text
            val response = parseJson<KitsuResponse>(responseText)
            val mediaList = response.data ?: emptyList()

            mediaList.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val attrs = item.attributes ?: return@mapNotNull null
                val title = attrs.canonicalTitle ?: return@mapNotNull null
                val posterUrl = attrs.posterImage?.large ?: attrs.posterImage?.original
                val epCount = attrs.episodeCount
                newAnimeSearchResponse(title, "$mainUrl/anime/$id", TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = true, subExist = true, dubEpisodes = epCount, subEpisodes = epCount)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val kitsuId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        isKitsuDown()

        val media = try {
            val infoUrl = "$KITSU_API/anime/$kitsuId?include=mappings"
            val infoText = app.get(infoUrl, headers = KITSU_HEADERS, timeout = 15_000L).text
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

        // movies carry both sub and dub episode lists here, and the app hides
        // the sub/dub switcher on movie types, so they stay regular anime
        val tvType = when (subtype) {
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }
        val showStatus = when (status) {
            "current" -> ShowStatus.Ongoing
            "finished" -> ShowStatus.Completed
            else -> null
        }

        val anilistId = media.included?.mapNotNull { inc ->
            val incAttrs = inc.attributes ?: return@mapNotNull null
            if (incAttrs.externalSite == "anilist/anime") {
                incAttrs.externalId?.toIntOrNull()
            } else null
        }?.firstOrNull()

        val malId = media.included?.mapNotNull { inc ->
            val incAttrs = inc.attributes ?: return@mapNotNull null
            if (incAttrs.externalSite == "myanimelist/anime") {
                incAttrs.externalId?.toIntOrNull()
            } else null
        }?.firstOrNull()

        val syncMetaData = try {
            app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text
        } catch (_: Exception) { null }
        val animeMetaData = syncMetaData?.let { parseAnimeData(it) }

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

            val subLinkData = LinkData(animeId = anilistId ?: 0, kitsuId = kitsuId, malId = malId, title = title, jpTitle = jpTitle, episode = i, isDub = false, year = year).toJson()
            val dubLinkData = LinkData(animeId = anilistId ?: 0, kitsuId = kitsuId, malId = malId, title = title, jpTitle = jpTitle, episode = i, isDub = true, year = year).toJson()

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
            if (animeScore != null) this.score = Score.from10(animeScore.toString())
            this.showStatus = showStatus
            if (anilistId != null) addAniListId(anilistId)
            if (malId != null) addMalId(malId)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private suspend fun fetchCategories(kitsuId: Int): List<String> {
        return try {
            val url = "$KITSU_API/anime/$kitsuId/categories"
            val responseText = app.get(url, headers = KITSU_HEADERS, timeout = 10_000L).text
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val aniId = linkData.animeId
        val title = linkData.title
        val jpTitle = linkData.jpTitle
        val episode = linkData.episode
        val isDub = linkData.isDub

        if (RaghavAnimeFeatures.isEnabled("watch_time")) {
            try { RaghavAnimeFeatures.recordWatchTime(linkData.kitsuId, title, null, 24 * 60 * 1000L) } catch (_: Exception) {}
        }

        val animeKey = linkData.kitsuId.toString()
        val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        val targetTitles = listOfNotNull(title, jpTitle)

        val sources = listOf<suspend () -> Unit>(
            {
                runSource("Miruro", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val epData = SourceCache.episodeData("Miruro", animeKey, isDub, episode) {
                        resolveMiruro(aniId, episode, isDub)
                    }
                    if (epData == null) return@runSource
                    Miruro().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniSuge", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("AniSuge", animeKey, isDub, episode) {
                        resolveAniSuge(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    AniSugeProvider().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniWaves", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("AniWaves", animeKey, isDub, episode) {
                        resolveAniWaves(searchTitles, targetTitles, episode, isDub)
                    }
                    if (epData == null) return@runSource
                    AniWaves().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("Anikai", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("Anikai", animeKey, isDub, episode) {
                        resolveAnikai(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    Anikai().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniDb", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("AniDb", animeKey, isDub, episode) {
                        resolveAniDb(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    AniDb().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniKage", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val anikage = RaghavAniKage()
                    anikage.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, cSub, cLink)
                }
            },
            {
                runSource("Anineko", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("Anineko", animeKey, isDub, episode) {
                        resolveAnineko(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    Anineko().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("2DHive", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("2DHive", animeKey, isDub, episode) {
                        resolveTwoDHive(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    RaghavTwoDHive().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniKoto", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("AniKoto", animeKey, isDub, episode) {
                        resolveAniKoto(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    RaghavAnikoto().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("Enma", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val enma = RaghavEnma()
                    enma.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, cSub, cLink)
                }
            },
            {
                runSource("Animo", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("Animo", animeKey, isDub, episode) {
                        resolveAnimo(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    RaghavAnimo().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("Anidap", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val anidap = RaghavAnidap()
                    anidap.loadLinksByAnilistId(aniId, episode, isDub, cSub, cLink)
                }
            },
            {
                runSource("AniPM", subtitleCallback, callback) { cSub, cLink ->
                    val anipm = RaghavAniPM()
                    anipm.loadLinksByAnilistId(aniId, title, jpTitle, episode, isDub, cSub, cLink)
                }
            },
            {
                runSource("Senshi", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("Senshi", animeKey, isDub, episode) {
                        resolveSenshi(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    RaghavSenshi().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniNami", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val epData = SourceCache.episodeData("AniNami", animeKey, isDub, episode) {
                        resolveAniNami(aniId, episode, isDub)
                    }
                    if (epData == null) return@runSource
                    RaghavAniNami().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniDao", subtitleCallback, callback) { cSub, cLink ->
                    val epData = SourceCache.episodeData("AniDao", animeKey, isDub, episode) {
                        resolveAniDao(searchTitles, targetTitles, episode, isDub, linkData.year)
                    }
                    if (epData == null) return@runSource
                    RaghavAniDao().loadLinks(epData, false, cSub, cLink)
                }
            },
            {
                runSource("AniChan", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val anichan = RaghavAniChan()
                    anichan.loadLinksByAnilistId(aniId, episode, isDub, cSub, cLink)
                }
            },
            {
                runSource("Kyren", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val kyren = RaghavKyren()
                    kyren.loadLinksByAnilistId(aniId, title, episode, isDub, cSub, cLink)
                }
            },
            {
                runSource("ReAnime", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val reanime = RaghavReAnime()
                    reanime.loadLinksByAnilistId(aniId, episode, isDub, cSub, cLink)
                }
            },
        )

        runAllAsync(*sources.toTypedArray())

        return true
    }

    private suspend fun runSource(
        tag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        block: suspend ((SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit
    ) {
        try {
            block(subtitleCallback, callback)
        } catch (t: Throwable) {
            Log.e("RaghavAnimeKitsu", "[$tag] failed: ${t.message}")
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

    private suspend fun resolveAnikai(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val anikai = Anikai()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { anikai.search(it) },
            doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "Anikai")
    }

    private suspend fun resolveAniDb(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val aniDb = AniDb()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { q -> aniDb.search(q, 1).items },
            doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "AniDb")
    }

    private suspend fun resolveAnineko(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val anineko = Anineko()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { anineko.search(it) },
            doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "Anineko")
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

    private suspend fun resolveAniDao(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean, year: Int?): SourceCache.Match? {
        val aniDao = RaghavAniDao()
        return findEpisodeMap(titles, targets, episode, isDub, year,
            doSearch = { aniDao.search(it) },
            doLoad = { aniDao.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
            sourceTag = "AniDao")
    }

    private suspend fun resolveAniWaves(titles: List<String>, targets: List<String>, episode: Int?, isDub: Boolean): SourceCache.Match? {
        val aniWaves = AniWaves()
        val cleanedTargets = targets.map { cleanTitle(it) }
        var failedSearches = 0
        var fallback: Map<Int, String>? = null
        for (t in titles) {
            val searchResults = try { aniWaves.search(t) } catch (e: Throwable) {
                failedSearches++
                Log.e("RaghavAnimeKitsu", "[AniWaves] search failed for '$t': ${e.message}")
                continue
            }
            val candidates = searchResults.filter { r -> cleanedTargets.contains(cleanTitle(r.name)) }
            for (result in candidates) {
                try {
                    val loadResult = aniWaves.load(result.url) as? com.lagradost.cloudstream3.AnimeLoadResponse ?: continue
                    val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                    val map = episodeMap(epList) ?: continue
                    if (fallback == null) fallback = map
                    if (episode == null || map.containsKey(episode)) {
                        return SourceCache.Match(map, episode != null)
                    }
                } catch (e: Throwable) {
                    Log.e("RaghavAnimeKitsu", "[AniWaves] load failed for '${result.name}': ${e.message}")
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
                Log.e("RaghavAnimeKitsu", "[$sourceTag] search failed for '$t': ${e.message}")
                continue
            }
            for (r in searchResults) {
                val c = cleanTitle(r.name)
                if (c.isBlank()) continue
                val titleScore = when {
                    // exact match after normalization
                    cleanedTargets.contains(c) -> 2
                    // prefix match against a real name (guards against
                    // trivial overlaps like "boku no")
                    c.length >= 6 && cleanedTargets.any { tgt ->
                        (c.startsWith(tgt) || tgt.startsWith(c))
                    } -> 1
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

        // exact title matches first, best season/year score wins
        for (cand in allCandidates) {
            if (cand.titleScore < 2) break
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val map = episodeMap(loadResult.episodes?.get(epKey)) ?: continue
                if (fallback == null) fallback = map
                if (episode == null || map.containsKey(episode)) {
                    return SourceCache.Match(map, episode != null)
                }
            } catch (e: Throwable) {
                Log.e("RaghavAnimeKitsu", "[$sourceTag] load failed for '${cand.result.name}': ${e.message}")
            }
        }

        // fuzzy fallback: only season- and year-consistent candidates,
        // so a sequel entry can never satisfy another season's lookup
        for (cand in allCandidates) {
            if (cand.titleScore == 2) continue
            val candSeasonNum = extractSeasonNumber(cand.result.name)
            val candYear = extractYear(cand.result.name)
            if (targetSeasonNum != null && candSeasonNum != null && candSeasonNum != targetSeasonNum) continue
            if (targetSeasonNum == null && candSeasonNum != null && candSeasonNum > 1) continue
            if (year != null && candYear != null && Math.abs(candYear - year) > 1) continue
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val map = episodeMap(loadResult.episodes?.get(epKey)) ?: continue
                if (fallback == null) fallback = map
                if (episode == null || map.containsKey(episode)) {
                    return SourceCache.Match(map, episode != null)
                }
            } catch (e: Throwable) {
                Log.e("RaghavAnimeKitsu", "[$sourceTag] load failed for fuzzy '${cand.result.name}': ${e.message}")
            }
        }

        if (fallback != null) return SourceCache.Match(fallback, false)
        if (searchTitles.isNotEmpty() && failedSearches == searchTitles.size) {
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
        private val homePageCache = mutableMapOf<String, List<KitsuMedia>>()
    }
}

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
