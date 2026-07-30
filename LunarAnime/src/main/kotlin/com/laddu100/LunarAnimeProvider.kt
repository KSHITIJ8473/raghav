package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class LunarAnimeProvider : MainAPI() {
    override var mainUrl = "https://lunaranime.ru"
    override var name = "LunarAnime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val apiUrl = "https://api.lunaranime.ru"
    private val TAG = "LunarAnime"

    // Hosters that CloudStream's built-in loadExtractor can resolve
    private val supportedHosters = setOf(
        "vidmoly", "voe", "filemoon", "videzz", "luluvdo", "speedfiles", "doodstream"
    )

    private val subbedLanguages = setOf("eng-sub", "ger-sub")
    private val dubbedLanguages = setOf("ger-dub")

    // Static mainPage — CloudStream reads this ONCE at init (per spec §15, dynamic getters don't work)
    override val mainPage = mainPageOf(
        "$apiUrl/api/anidb/feed?feeds=trending&limit=20" to "Trending",
        "$apiUrl/api/anidb/feed?feeds=popular&limit=20" to "Popular",
        "$apiUrl/api/anidb/feed?feeds=seasonal&limit=20" to "This Season",
        "$apiUrl/api/anidb/feed?feeds=topRated&limit=20" to "Top Rated",
        "$apiUrl/api/anidb/feed?feeds=topThisYear&limit=20" to "Top This Year",
        "$apiUrl/api/anidb/feed?feeds=nextSeason&limit=20" to "Upcoming",
        "$apiUrl/api/anidb/feed?feeds=todaySchedule&limit=20" to "Airing Today"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarSearchResponse(
        @JsonProperty("currentPage") val currentPage: Int? = null,
        @JsonProperty("totalCount") val totalCount: Int? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("items") val items: List<LunarAnimeItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarAnimeItem(
        @JsonProperty("anilistId") val anilistId: Int,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("titleEnglish") val titleEnglish: String? = null,
        @JsonProperty("titleRomaji") val titleRomaji: String? = null,
        @JsonProperty("title") val title: LunarTitle? = null,
        @JsonProperty("coverImage") val coverImage: LunarCoverImage? = null,
        @JsonProperty("episodeCount") val episodeCount: Int? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null
    ) {
        fun getTitle(): String {
            return titleEnglish
                ?: title?.english
                ?: titleRomaji
                ?: title?.romaji
                ?: "Unknown"
        }
        fun getPoster(): String? {
            return coverImage?.extraLarge
                ?: coverImage?.large
                ?: coverImage?.medium
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarTitle(
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("native") val native: String? = null,
        @JsonProperty("romaji") val romaji: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String? = null,
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("medium") val medium: String? = null,
        @JsonProperty("color") val color: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarFeedWrapper(
        @JsonProperty("feeds") val feeds: Map<String, LunarFeed>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarFeed(
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("items") val items: List<LunarAnimeItem>? = null,
        @JsonProperty("title") val title: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarInfoResponse(
        @JsonProperty("anilistId") val anilistId: Int,
        @JsonProperty("id") val id: String,
        @JsonProperty("titleEnglish") val titleEnglish: String? = null,
        @JsonProperty("titleRomaji") val titleRomaji: String? = null,
        @JsonProperty("englishTitle") val englishTitle: String? = null,
        @JsonProperty("romajiTitle") val romajiTitle: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("episodeCount") val episodeCount: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("season") val season: String? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("studios") val studios: List<String>? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("isAdult") val isAdult: Boolean? = null,
        @JsonProperty("coverImage") val coverImage: LunarCoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("trailerId") val trailerId: String? = null,
        @JsonProperty("seasons") val seasons: List<LunarSeason>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarSeason(
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("animeId") val animeId: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("titleEnglish") val titleEnglish: String? = null,
        @JsonProperty("relation") val relation: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarEpisodesResponse(
        @JsonProperty("data") val data: List<LunarEpisode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarEpisode(
        @JsonProperty("number") val number: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("airDate") val airDate: String? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("length") val length: Int? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("isFiller") val isFiller: Boolean? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("hasSub") val hasSub: Boolean? = null,
        @JsonProperty("hasDub") val hasDub: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarStreamResponse(
        @JsonProperty("episodes") val episodes: List<LunarStreamEpisode>? = null,
        @JsonProperty("message") val message: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarStreamEpisode(
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("hosters") val hosters: List<LunarHoster>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LunarHoster(
        @JsonProperty("hoster") val hoster: String,
        @JsonProperty("language") val language: String,
        @JsonProperty("owned") val owned: Boolean? = null,
        @JsonProperty("redirect_uri") val redirectUri: String
    )

    /**
     * Derive the title-slug from the full id (which has a 5-char suffix).
     * "one-piece-p8k27" -> "one-piece"
     * "demon-slayer-kimetsu-no-yaiba-j2hzd" -> "demon-slayer-kimetsu-no-yaiba"
     */
    private fun deriveSlug(fullId: String): String {
        val lastDashIndex = fullId.lastIndexOf('-')
        if (lastDashIndex >= 0 && lastDashIndex < fullId.length - 1) {
            val suffix = fullId.substring(lastDashIndex + 1)
            if (suffix.matches(Regex("^[a-z0-9]{5}$"))) {
                val slug = fullId.substring(0, lastDashIndex)
                Log.d(TAG, "deriveSlug: stripped suffix '$suffix' -> slug='$slug'")
                return slug
            }
        }
        Log.d(TAG, "deriveSlug: no valid suffix found, returning fullId as-is")
        return fullId
    }

    /**
     * Extract anilistId from the URL passed by CloudStream.
     * CloudStream prepends mainUrl to our search data, so:
     *   "21" -> "https://lunaranime.ru/21"
     * We need to extract "21" from this URL.
     */
    private fun extractAnilistId(url: String): Int? {
        // Try direct parse first (in case it's just the number)
        url.toIntOrNull()?.let {
            Log.d(TAG, "extractAnilistId: direct parse OK -> $it")
            return it
        }
        // Extract the last path segment and parse as int
        val lastSegment = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
        val id = lastSegment.toIntOrNull()
        if (id != null) {
            Log.d(TAG, "extractAnilistId: extracted from URL path -> $id")
        } else {
            Log.e(TAG, "extractAnilistId FAILED: could not parse int from '$lastSegment' (url='$url')")
        }
        return id
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList())
        }
        return try {
            val startTime = System.currentTimeMillis()
            val res = app.get(request.data, timeout = 30_000L)
            val elapsed = System.currentTimeMillis() - startTime

            val parsed = parseJson<LunarFeedWrapper>(res.text)
            val feedName = request.data.substringAfter("feeds=").substringBefore("&")

            val feed = parsed.feeds?.get(feedName)
            if (feed == null) {
                Log.e(TAG, "getMainPage: feed '$feedName' not found in response. Available feeds: ${parsed.feeds?.keys}")
                return newHomePageResponse(request.name, emptyList())
            }

            val items = feed.items ?: emptyList()
            Log.d(TAG, "getMainPage: feed '$feedName' has ${items.size} items")

            val home = items.mapNotNull { it.toSearchResult() }
            Log.d(TAG, "getMainPage: '${request.name}' -> ${home.size} search results (from ${items.size} items)")
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED for '${request.name}': ${e.message}")
            Log.e(TAG, "getMainPage error: ${e.stackTraceToString().take(500)}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun LunarAnimeItem.toSearchResult(): SearchResponse? {
        val title = getTitle()
        if (title == "Unknown") {
            Log.e(TAG, "toSearchResult: skipping item with no title (anilistId=$anilistId)")
            return null
        }
        val poster = getPoster()
        // data = anilistId (CloudStream will prepend mainUrl to make https://lunaranime.ru/<id>)
        return newAnimeSearchResponse(title, anilistId.toString(), TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = true, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) {
            return emptyList()
        }
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl/api/anidb/search?q=$encoded"
            val startTime = System.currentTimeMillis()
            val res = app.get(url, timeout = 30_000L)
            val elapsed = System.currentTimeMillis() - startTime

            val parsed = parseJson<LunarSearchResponse>(res.text)
            val items = parsed.items ?: emptyList()
            Log.d(TAG, "search: parsed ${items.size} items (currentPage=${parsed.currentPage}, totalPages=${parsed.totalPages})")

            val results = items.mapNotNull { it.toSearchResult() }
            Log.d(TAG, "search END: '$query' -> ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED for '$query': ${e.message}")
            Log.e(TAG, "search error: ${e.stackTraceToString().take(500)}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {

        // Extract anilistId from URL (CloudStream prepends mainUrl to our search data)
        val anilistId = extractAnilistId(url)
        if (anilistId == null) {
            Log.e(TAG, "load: could not extract anilistId from url='$url'")
            return null
        }

        return try {
            // 1. Fetch details
            val infoUrl = "$apiUrl/api/anidb/info?anilistId=$anilistId"
            val infoStart = System.currentTimeMillis()
            val infoRes = app.get(infoUrl, timeout = 30_000L)
            val infoElapsed = System.currentTimeMillis() - infoStart

            val info = parseJson<LunarInfoResponse>(infoRes.text)

            // 2. Fetch episodes
            val epsUrl = "$apiUrl/api/animes/v2/vermillion/episodes?id=$anilistId"
            val epsStart = System.currentTimeMillis()
            val epsRes = app.get(epsUrl, timeout = 30_000L)
            val epsElapsed = System.currentTimeMillis() - epsStart

            val epsData = parseJson<LunarEpisodesResponse>(epsRes.text)
            val episodes = epsData.data ?: emptyList()
            if (episodes.isEmpty()) {
                Log.e(TAG, "load: [2/4] NO episodes returned! Response body: ${epsRes.text.take(300)}")
            } else {
                val firstEp = episodes.first()
                val lastEp = episodes.last()
            }

            // 3. Derive slug from info.id
            val slug = deriveSlug(info.id)

            // 4. Determine sub/dub availability
            val hasSub = episodes.any { it.hasSub == true }
            val hasDub = episodes.any { it.hasDub == true }

            // 5. Determine TvType (per spec §10 — movie with dub needs Anime to show selector)
            val isMovie = info.format == "MOVIE"
            val tvType = when {
                isMovie && hasDub -> TvType.Anime
                isMovie -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            // 6. Build episode lists
            val title = info.titleEnglish
                ?: info.englishTitle
                ?: info.titleRomaji
                ?: info.romajiTitle
                ?: "Unknown"
            val poster = info.coverImage?.extraLarge ?: info.coverImage?.large

            // Data string format: "$mainUrl|$slug|$anilistId|$epNum|$dubStatus"
            // Prefix with mainUrl so CloudStream treats it as absolute URL (doesn't prepend mainUrl/)
            val subEpisodes = if (hasSub) episodes.map { ep ->
                newEpisode("$mainUrl|$slug|$anilistId|${ep.number}|sub") {
                    this.episode = ep.number
                    this.name = ep.title ?: "Episode ${ep.number}"
                }
            } else emptyList()

            val dubEpisodes = if (hasDub) episodes.map { ep ->
                newEpisode("$mainUrl|$slug|$anilistId|${ep.number}|dub") {
                    this.episode = ep.number
                    this.name = ep.title ?: "Episode ${ep.number}"
                }
            } else emptyList()

            Log.d(TAG, "load: episodes built: sub=${subEpisodes.size} dub=${dubEpisodes.size}")

            val response = newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.plot = info.description
                this.tags = info.genres
                this.year = info.seasonYear
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
            Log.d(TAG, "load END: success for anilistId=$anilistId title='$title'")
            response
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED for anilistId=$anilistId: ${e.message}")
            Log.e(TAG, "load error: ${e.stackTraceToString().take(600)}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parts = data.split("|")
        if (parts.size < 5) {
            Log.e(TAG, "loadLinks: invalid data format (expected 5 parts, got ${parts.size}): '$data'")
            return false
        }
        // Format: $mainUrl|$slug|$anilistId|$epNum|$dubStatus
        // parts[0] = mainUrl (e.g. "https://lunaranime.ru") — ignored, just prevents CloudStream from prepending
        val slug = parts[1]
        val anilistId = parts[2]
        val epNum = parts[3]
        val dubStatus = parts[4]

        val targetLanguages = if (dubStatus == "dub") dubbedLanguages else subbedLanguages

        return try {
            val streamUrl = "$apiUrl/api/stream?slug=$slug&season=1&episode=$epNum"
            val startTime = System.currentTimeMillis()
            val res = app.get(streamUrl, timeout = 30_000L)
            val elapsed = System.currentTimeMillis() - startTime

            val parsed = parseJson<LunarStreamResponse>(res.text)
            val streamEp = parsed.episodes?.firstOrNull()
            if (streamEp == null) {
                Log.e(TAG, "loadLinks: no episodes in stream response (slug=$slug ep=$epNum). message=${parsed.message}")
                Log.e(TAG, "loadLinks: response body: ${res.text.take(300)}")
                return false
            }

            val hosters = streamEp.hosters
            if (hosters.isNullOrEmpty()) {
                Log.e(TAG, "loadLinks: no hosters in stream response (slug=$slug ep=$epNum)")
                return false
            }

            hosters.forEach { h ->
            }

            var found = false
            for (hoster in hosters) {
                // Skip unsupported hosters (especially "unknown" which has random domains)
                if (hoster.hoster !in supportedHosters) {
                    continue
                }
                // Filter by target language for this tab
                if (hoster.language !in targetLanguages) {
                    continue
                }

                val embedUrl = hoster.redirectUri
                val languageLabel = hoster.language

                try {
                    val loaded = loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
                    if (loaded) {
                        found = true
                    } else {
                        Log.d(TAG, "loadLinks: loadExtractor returned false for '${hoster.hoster}' ($languageLabel)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks: FAILED loadExtractor '${hoster.hoster}' ($languageLabel): ${e.message}")
                }
            }

            if (!found) {
                Log.e(TAG, "loadLinks: NO links found for slug=$slug ep=$epNum dubStatus=$dubStatus (checked ${hosters.size} hosters)")
            } else {
                Log.d(TAG, "loadLinks END: success for slug=$slug ep=$epNum dubStatus=$dubStatus")
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FAILED: ${e.message}")
            Log.e(TAG, "loadLinks error: ${e.stackTraceToString().take(600)}")
            false
        }
    }
}
