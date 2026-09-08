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
                Log.e("RaghavAnime", "showKitsuDownPopup: ${e.message}")
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
                Log.e("RaghavAnime", "[Recommendations] FAILED: ${e.message}")
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

        val tvType = when (subtype) {
            "movie" -> TvType.AnimeMovie
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

        runAllAsync(
            {
                runSource("Miruro", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val miruro = Miruro()
                    val loadResult = miruro.load("${miruro.mainUrl}/info/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                        return@runSource
                    }
                    val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                    val matchedEp = epList?.find { it.episode == episode }
                    if (matchedEp == null) {
                        return@runSource
                    }
                    miruro.loadLinks(matchedEp.data, false, cSub, cLink)
                }
            },
            {
                runSource("AniSuge", subtitleCallback, callback) { cSub, cLink ->
                    val aniSuge = AniSugeProvider()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniSuge.search(it) },
                        doLoad = { aniSuge.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniSuge"
                    )
                    if (epData != null) {
                        aniSuge.loadLinks(epData, false, cSub, cLink)
                    }
                }
            },
            {
                runSource("AniWaves", subtitleCallback, callback) { cSub, cLink ->
                    val aniWaves = AniWaves()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val aniWavesTargets = listOfNotNull(title, jpTitle).map { cleanTitle(it) }
                    var matchedData: String? = null
                    for (t in searchTitles) {
                        val searchResults = try { aniWaves.search(t) } catch (e: Throwable) { continue }
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
                                val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                                val ep = epList?.find { it.episode == episode } ?: continue
                                matchedData = ep.data
                                break
                            } catch (e: Throwable) { continue }
                        }
                        if (matchedData != null) break
                    }
                    if (matchedData != null) {
                        aniWaves.loadLinks(matchedData, false, cSub, cLink)
                    } else {
                    }
                }
            },
            {
                runSource("Anikai", subtitleCallback, callback) { cSub, cLink ->
                    val anikai = Anikai()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikai.search(it) },
                        doLoad = { anikai.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anikai"
                    )
                    if (epData != null) {
                        anikai.loadLinks(epData, false, cSub, cLink)
                    }
                }
            },
            {
                runSource("AniDb", subtitleCallback, callback) { cSub, cLink ->
                    val aniDb = AniDb()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { q -> aniDb.search(q, 1).items },
                        doLoad = { aniDb.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDb"
                    )
                    if (epData != null) {
                        aniDb.loadLinks(epData, false, cSub, cLink)
                    }
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
                    val anineko = Anineko()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anineko.search(it) },
                        doLoad = { anineko.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Anineko"
                    )
                    if (epData != null) {
                        anineko.loadLinks(epData, false, cSub, cLink)
                    }
                }
            },
            {
                runSource("2DHive", subtitleCallback, callback) { cSub, cLink ->
                    val twoDHive = RaghavTwoDHive()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { twoDHive.search(it) },
                        doLoad = { twoDHive.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "2DHive"
                    )
                    if (epData != null) {
                        twoDHive.loadLinks(epData, false, cSub, cLink)
                    }
                }
            },
            {
                runSource("AniKoto", subtitleCallback, callback) { cSub, cLink ->
                    val anikoto = RaghavAnikoto()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { anikoto.search(it) },
                        doLoad = { anikoto.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniKoto"
                    )
                    if (epData != null) {
                        anikoto.loadLinks(epData, false, cSub, cLink)
                    }
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
                    val animo = RaghavAnimo()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { animo.search(it) },
                        doLoad = { animo.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Animo"
                    )
                    if (epData != null) {
                        animo.loadLinks(epData, false, cSub, cLink)
                    }
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
                runSource("Senshi", subtitleCallback, callback) { cSub, cLink ->
                    val senshi = RaghavSenshi()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { senshi.search(it) },
                        doLoad = { senshi.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "Senshi"
                    )
                    if (epData != null) {
                        senshi.loadLinks(epData, false, cSub, cLink)
                    }
                }
            },
            {
                runSource("AniNami", subtitleCallback, callback) { cSub, cLink ->
                    if (aniId <= 0) return@runSource
                    val aniNami = RaghavAniNami()
                    val loadResult = aniNami.load("${aniNami.mainUrl}/anime/$aniId") as? com.lagradost.cloudstream3.AnimeLoadResponse
                    if (loadResult == null) {
                        return@runSource
                    }
                    val epList = if (isDub) loadResult.episodes?.get(DubStatus.Dubbed) else loadResult.episodes?.get(DubStatus.Subbed)
                    val matchedEp = epList?.find { it.episode == episode }
                    if (matchedEp == null) {
                        return@runSource
                    }
                    aniNami.loadLinks(matchedEp.data, false, cSub, cLink)
                }
            },
            {
                runSource("AniDao", subtitleCallback, callback) { cSub, cLink ->
                    val aniDao = RaghavAniDao()
                    val searchTitles = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
                    val epData = findEpisodeData(searchTitles, listOfNotNull(title, jpTitle), episode, isDub, year = linkData.year,
                        doSearch = { aniDao.search(it) },
                        doLoad = { aniDao.load(it) as? com.lagradost.cloudstream3.AnimeLoadResponse },
                        sourceTag = "AniDao"
                    )
                    if (epData != null) {
                        aniDao.loadLinks(epData, false, cSub, cLink)
                    }
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
        )

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
            Log.e("RaghavAnime", "$tag failed: ${t.javaClass.simpleName}: ${t.message}")
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
        for (t in searchTitles) {
            val searchResults = try { doSearch(t) } catch (e: Throwable) {
                Log.e("RaghavAnime", "[$sourceTag] search failed for '$t': ${e.message}")
                continue
            }
            for (r in searchResults) {
                val c = cleanTitle(r.name)
                if (c.isBlank()) continue
                val titleScore = when {
                    cleanedTargets.contains(c) -> 2
                    // short titles match too easily, require a real prefix overlap
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

        if (allCandidates.isEmpty()) {
            return null
        }

        allCandidates.sortByDescending { it.combinedScore }

        for (cand in allCandidates) {
            if (cand.titleScore < 2) break
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) {
                    return ep.data
                }
            } catch (e: Throwable) {
                Log.e("RaghavAnime", "[$sourceTag] load failed for '${cand.result.name}': ${e.message}")
            }
        }

        // fuzzy fallback, restricted to season/year consistent candidates so
        // a sequel entry can never satisfy a different season's lookup
        for (cand in allCandidates) {
            if (cand.titleScore == 2) continue
            val candSeasonNum = extractSeasonNumber(cand.result.name)
            val candYear = extractYear(cand.result.name)
            if (targetSeasonNum != null && candSeasonNum != null && candSeasonNum != targetSeasonNum) continue
            if (targetSeasonNum == null && candSeasonNum != null && candSeasonNum > 1) continue
            if (year != null && candYear != null && Math.abs(candYear - year) > 1) continue
            try {
                val loadResult = doLoad(cand.result.url) ?: continue
                val ep = loadResult.episodes?.get(epKey)?.find { it.episode == episode }
                if (ep != null) {
                    return ep.data
                }
            } catch (e: Throwable) {
                Log.e("RaghavAnime", "[$sourceTag] load failed for fuzzy '${cand.result.name}': ${e.message}")
            }
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
