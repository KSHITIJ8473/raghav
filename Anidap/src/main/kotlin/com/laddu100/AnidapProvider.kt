package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AnidapProvider : MainAPI() {
    override var mainUrl = "https://anidap.lol"
    override var name = "Anidap"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val chadHost = "https://chad.anidap.lol"
    private val chadUrl = "$chadHost/rest/api"
    private val TAG = "Anidap"
    private val baseHeaders = mapOf("Referer" to "$mainUrl/home")

    // 
    // ARCHITECTURE (v7 — rewritten from scratch):
    //
    // The #1 bug in v6 was that loadLinks() only passed `Referer` to ExtractorLink,
    // ignoring the FULL headers map from the API. Different CDNs need different headers:
    //   - beep/mimi/yuki/loli  → need Referer only
    //   - uwu/kiwi             → need Origin: https://animex.one (NO Referer!)
    //   - sora                 → need Referer: https://kaa.lt/ + Android User-Agent
    //
    // Fix: pass the EXACT headers map from the API response to each ExtractorLink.
    // This makes ALL sources work because each CDN gets its required headers.
    //
    // Other fixes:
    //   - Real episode titles from /rest/api/episodes endpoint (was "Episode N")
    //   - Quality in source label for multi-quality providers (uwu 800p + 360p)
    //   - Clear (Hardsub) marker in Subbed tab for hardsub providers
    //   - (Sub) / (Dub) prefix in labels to prevent sub/dub confusion
    //   - Subtitles (tracks) passed to subtitleCallback with correct labels
    // 

    // ==================== DATA MODELS ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResponseData(
        @JsonProperty("results") val results: List<AnimeItem>? = null,
        @JsonProperty("currentPage") val currentPage: Int? = null,
        @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: AnimeTitle? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("rating") val rating: Double? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeTitle(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetail(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: AnimeTitle? = null,
        @JsonProperty("titleEnglish") val titleEnglish: String? = null,
        @JsonProperty("coverImage") val coverImage: CoverImage? = null,
        @JsonProperty("bannerImage") val bannerImage: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("episodeCount") val episodeCount: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("seasonYear") val seasonYear: Int? = null,
        @JsonProperty("averageScore") val averageScore: Int? = null,
        @JsonProperty("duration") val duration: Int? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("trailerId") val trailerId: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        @JsonProperty("large") val large: String? = null,
        @JsonProperty("extraLarge") val extraLarge: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(
        @JsonProperty("name") val name: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServersResponse(
        @JsonProperty("subProviders") val subProviders: List<Provider>? = null,
        @JsonProperty("dubProviders") val dubProviders: List<Provider>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Provider(
        @JsonProperty("id") val id: String,
        @JsonProperty("default") val default: Boolean? = null,
        @JsonProperty("tip") val tip: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesResponse(
        @JsonProperty("sources") val sources: List<Source>? = null,
        @JsonProperty("tracks") val tracks: List<Track>? = null,
        @JsonProperty("headers") val headers: Map<String, String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Source(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Track(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("lang") val lang: String? = null
    )

    // Episode metadata from /rest/api/episodes
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeInfo(
        @JsonProperty("number") val number: Int,
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("img") val img: String? = null,
        @JsonProperty("isFiller") val isFiller: Boolean? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("length") val length: Int? = null
    )

    // ==================== HELPERS ====================

    private fun AnimeItem.getTitle(): String {
        return title?.userPreferred ?: title?.english ?: title?.romaji ?: "Unknown"
    }

    private fun AnimeItem.getPoster(): String? {
        return image ?: cover
    }

    /**
     * Normalize the API tip for display.
     * "Hard sub, Fast" -> "Hardsub, Fast"
     * "Soft sub, Fastest, High quality" -> "Soft Sub, Fastest, High quality"
     */
    private fun normalizeTip(tip: String?): String? {
        if (tip.isNullOrBlank()) return null
        return tip
            .replace("Hard sub", "Hardsub", ignoreCase = false)
            .replace("Soft sub", "Soft Sub", ignoreCase = false)
    }

    /**
     * Returns true if the provider's tip indicates it's a hardsub provider.
     */
    private fun isHardsubProvider(tip: String?): Boolean {
        if (tip.isNullOrBlank()) return false
        return tip.contains("Hard", ignoreCase = true)
    }

    /**
     * Parse a quality string like "800p", "360p", "1080p", "auto" into a quality Int.
     * Maps to CloudStream's Qualities enum values.
     */
    private fun parseQuality(qualityStr: String?): Int {
        if (qualityStr.isNullOrBlank() || qualityStr.equals("auto", ignoreCase = true)) {
            return Qualities.Unknown.value
        }
        // Extract the numeric part (e.g. "800p" -> 800)
        val match = Regex("(\\d{3,4})").find(qualityStr)
        val height = match?.groupValues?.get(1)?.toIntOrNull() ?: return Qualities.Unknown.value
        // Map to closest standard quality
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 600 -> Qualities.P720.value  // 800p, 720p range
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    /**
     * Build a minimal source label — just the provider name (+ quality for multi-quality).
     *
     *   "Anidap - beep"
     *   "Anidap - uwu 800p"
     *   "Anidap - uwu 360p"
     *   "Anidap - mimi"
     *
     * The user already picked Sub or Dub tab, so no type/hardsub marker is needed.
     */
    private fun buildSourceLabel(
        providerId: String,
        tip: String?,
        quality: String?,
        type: String
    ): String {
        val qualitySuffix = if (!quality.isNullOrBlank() && !quality.equals("auto", ignoreCase = true)) {
            " $quality"
        } else ""

        return "$name - $providerId$qualitySuffix"
    }

    // ==================== getMainPage ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage START: section='${request.name}' page=$page")
        val lists = mutableListOf<HomePageList>()

        try {
            when (request.name) {
                "All" -> {
                    coroutineScope {
                        val trendingDeferred = async { fetchTrendingFromAPI() }
                        val popularDeferred = async { fetchSearch("naruto") }
                        val recentDeferred = async { fetchSearch("one piece") }

                        val trending = trendingDeferred.await()
                        if (trending.isNotEmpty()) {
                            lists.add(HomePageList("Trending", trending, isHorizontalImages = true))
                        }

                        val popular = popularDeferred.await()
                        if (popular.isNotEmpty()) {
                            lists.add(HomePageList("Popular", popular, isHorizontalImages = true))
                        }

                        val recent = recentDeferred.await()
                        if (recent.isNotEmpty()) {
                            lists.add(HomePageList("🆕 Recently Added", recent, isHorizontalImages = true))
                        }

                        kotlinx.coroutines.delay(500)
                        val actionDeferred = async { fetchSearch("demon slayer") }
                        val comedyDeferred = async { fetchSearch("my hero academia") }
                        val fantasyDeferred = async { fetchSearch("jujutsu kaisen") }

                        val action = actionDeferred.await()
                        if (action.isNotEmpty()) {
                            lists.add(HomePageList("💥 Action", action, isHorizontalImages = true))
                        }

                        val comedy = comedyDeferred.await()
                        if (comedy.isNotEmpty()) {
                            lists.add(HomePageList("😂 Comedy", comedy, isHorizontalImages = true))
                        }

                        val fantasy = fantasyDeferred.await()
                        if (fantasy.isNotEmpty()) {
                            lists.add(HomePageList("🧙 Fantasy", fantasy, isHorizontalImages = true))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED: ${e.message}")
        }

        Log.d(TAG, "getMainPage END: ${lists.size} sections")
        return newHomePageResponse(lists, hasNext = false)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/anime/search" to "All"
    )

    private suspend fun fetchSearch(query: String): List<SearchResponse> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api/anime/search?q=$encoded"
            Log.d(TAG, "fetchSearch: $url")
            val res = app.get(url, headers = baseHeaders, timeout = 30_000L)
            val parsed = parseJson<SearchResponseData>(res.text)
            val results = parsed.results ?: emptyList()
            results.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSearch FAILED: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchTrendingFromAPI(): List<SearchResponse> {
        return try {
            val url = "$mainUrl/api/anime/trending"
            val res = app.get(url, headers = baseHeaders, timeout = 30_000L)
            val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(res.text)
            val resultsNode = root.path("data").path("data").path("results")
            if (resultsNode.isArray) {
                val results = mutableListOf<SearchResponse>()
                for (node in resultsNode) {
                    val id = node.path("id").asText()
                    val title = node.path("title").path("userPreferred").asText()
                    val image = node.path("image").asText()
                    if (id.isNotBlank() && title.isNotBlank()) {
                        results.add(newAnimeSearchResponse(title, "$mainUrl|$id", TvType.Anime) {
                            this.posterUrl = image.ifBlank { null }
                            addDubStatus(dubExist = true, subExist = true)
                        })
                    }
                }
                results
            } else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "fetchTrendingFromAPI FAILED: ${e.message}")
            emptyList()
        }
    }

    private fun AnimeItem.toSearchResponse(): SearchResponse? {
        val title = getTitle()
        if (title == "Unknown") return null
        val data = "$mainUrl|$id"
        return newAnimeSearchResponse(title, data, TvType.Anime) {
            this.posterUrl = getPoster()
            addDubStatus(dubExist = true, subExist = true)
        }
    }

    // ==================== search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search START: query='$query'")
        if (query.length < 2) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$mainUrl/api/anime/search?q=$encoded"
            val res = app.get(url, headers = baseHeaders, timeout = 30_000L)
            val parsed = parseJson<SearchResponseData>(res.text)
            val results = parsed.results ?: emptyList()
            Log.d(TAG, "search END: ${results.size} results")
            results.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED: ${e.message}")
            emptyList()
        }
    }

    // ==================== load ====================

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load START: url='$url'")
        val animeId = url.removePrefix("$mainUrl/").removePrefix("$mainUrl|").trim()
        Log.d(TAG, "load: animeId=$animeId")

        return try {
            // 1. Fetch anime detail
            val detailUrl = "$mainUrl/api/anime/$animeId"
            val detailRes = app.get(detailUrl, headers = baseHeaders, timeout = 30_000L)
            val detailRoot = parseJson<com.fasterxml.jackson.databind.JsonNode>(detailRes.text)
            val dataNode = detailRoot.path("data")
            val detail = parseJson<AnimeDetail>(dataNode.toString())

            val slug = detail.slug ?: detail.id ?: animeId
            val title = detail.title?.userPreferred ?: detail.title?.english ?: detail.titleEnglish ?: "Unknown"
            val poster = detail.coverImage?.extraLarge ?: detail.coverImage?.large
            val banner = detail.bannerImage
            val plot = detail.description ?: detail.synopsis
            val year = detail.seasonYear
            val genres = detail.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }
            val totalEps = detail.episodes ?: detail.totalEpisodes ?: detail.episodeCount ?: 0
            val isMovie = detail.format == "MOVIE" || detail.type == "MOVIE"

            Log.d(TAG, "load: title='$title' slug=$slug episodes=$totalEps format=${detail.format}")

            // 2. Fetch servers via cfAppGet (handles _amx_id anti-bot bypass)
            val serversUrl = "$chadUrl/servers?id=$slug&epNum=1"
            val serversRes = cfAppGet(
                serversUrl,
                headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
            )
            Log.d(TAG, "load: servers code=${serversRes.code} size=${serversRes.text.length}")

            val servers = if (serversRes.code == 200 && !serversRes.text.contains("bot_detected") && !serversRes.text.contains("\"error\"")) {
                try { parseJson<ServersResponse>(serversRes.text) } catch (e: Exception) {
                    Log.e(TAG, "load: servers parse failed: ${e.message}")
                    ServersResponse()
                }
            } else {
                Log.e(TAG, "load: servers API failed (code=${serversRes.code})")
                ServersResponse()
            }

            val subProviders: List<Provider> = servers.subProviders?.filter { it.id.isNotBlank() } ?: emptyList()
            val dubProviders: List<Provider> = servers.dubProviders?.filter { it.id.isNotBlank() } ?: emptyList()

            Log.d(TAG, "load: subProviders=${subProviders.size} dubProviders=${dubProviders.size}")
            Log.d(TAG, "load: sub ids=${subProviders.map { it.id }}")
            Log.d(TAG, "load: dub ids=${dubProviders.map { it.id }}")

            if (totalEps <= 0) {
                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                }
            }

            // 3. Fetch REAL episode titles from the episodes endpoint
            // This returns English/Japanese titles, filler status, images, descriptions
            val episodeInfos: Map<Int, EpisodeInfo> = try {
                val epsUrl = "$chadUrl/episodes?id=$slug"
                Log.d(TAG, "load: fetching episodes -> $epsUrl")
                val epsRes = cfAppGet(epsUrl, headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json"))
                if (epsRes.code == 200) {
                    val epsList = parseJson<List<EpisodeInfo>>(epsRes.text)
                    Log.d(TAG, "load: got ${epsList.size} episode titles")
                    epsList.associateBy { it.number }
                } else {
                    Log.e(TAG, "load: episodes API code=${epsRes.code}")
                    emptyMap()
                }
            } catch (e: Exception) {
                Log.e(TAG, "load: episodes fetch failed: ${e.message}")
                emptyMap()
            }

            val hasSub = subProviders.isNotEmpty()
            val hasDub = dubProviders.isNotEmpty()

            val tvType = when {
                isMovie && hasDub -> TvType.Anime
                isMovie -> TvType.AnimeMovie
                else -> TvType.Anime
            }

            // 4. Build episode lists with REAL titles
            // Data format: "$mainUrl|$slug|$epNum|$type|${providerIds}|${tips}"
            val subEpisodes = if (hasSub) (1..totalEps).map { epNum ->
                val ids = subProviders.joinToString(",") { it.id }
                val tips = subProviders.joinToString(";;") { it.id + "=" + (it.tip ?: "") }
                val epInfo = episodeInfos[epNum]
                val epTitle = epInfo?.titles?.get("en")?.takeIf { it.isNotBlank() }
                    ?: epInfo?.titles?.get("x-jat")?.takeIf { it.isNotBlank() }
                    ?: epInfo?.titles?.get("ja")?.takeIf { it.isNotBlank() }
                val fillerSuffix = if (epInfo?.isFiller == true) " (Filler)" else ""
                newEpisode("$mainUrl|$slug|$epNum|sub|$ids|$tips") {
                    this.episode = epNum
                    this.name = if (epTitle.isNullOrBlank()) {
                        "Episode $epNum$fillerSuffix"
                    } else {
                        "$epTitle$fillerSuffix"
                    }
                    this.episode = epNum
                    this.posterUrl = epInfo?.img?.takeIf { it.isNotBlank() }
                    this.description = epInfo?.description?.takeIf { it.isNotBlank() }
                }
            } else emptyList()

            val dubEpisodes = if (hasDub) (1..totalEps).map { epNum ->
                val ids = dubProviders.joinToString(",") { it.id }
                val tips = dubProviders.joinToString(";;") { it.id + "=" + (it.tip ?: "") }
                val epInfo = episodeInfos[epNum]
                val epTitle = epInfo?.titles?.get("en")?.takeIf { it.isNotBlank() }
                    ?: epInfo?.titles?.get("x-jat")?.takeIf { it.isNotBlank() }
                    ?: epInfo?.titles?.get("ja")?.takeIf { it.isNotBlank() }
                val fillerSuffix = if (epInfo?.isFiller == true) " (Filler)" else ""
                newEpisode("$mainUrl|$slug|$epNum|dub|$ids|$tips") {
                    this.episode = epNum
                    this.name = if (epTitle.isNullOrBlank()) {
                        "Episode $epNum$fillerSuffix"
                    } else {
                        "$epTitle$fillerSuffix"
                    }
                    this.episode = epNum
                    this.posterUrl = epInfo?.img?.takeIf { it.isNotBlank() }
                    this.description = epInfo?.description?.takeIf { it.isNotBlank() }
                }
            } else emptyList()

            Log.d(TAG, "load: subEps=${subEpisodes.size} dubEps=${dubEpisodes.size}")

            return newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.tags = genres
                this.year = year
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED: ${e.message}")
            null
        }
    }

    // ==================== loadLinks (REWRITTEN FROM SCRATCH) ====================
    //
    // The core fix: pass the EXACT headers map from the API response to each
    // ExtractorLink. Different CDNs need different headers:
    //   - beep/mimi/yuki/loli → Referer only
    //   - uwu/kiwi            → Origin: https://animex.one
    //   - sora                → Referer: https://kaa.lt/ + Android User-Agent
    //
    // The old code extracted only Referer and fell back to anidap.lol/ — that's
    // why uwu/kiwi/sora always failed (403). Now we pass the FULL headers map.

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks START: data='$data'")

        // Parse: "$mainUrl|$slug|$epNum|$type|${ids}|${tips}"
        val cleanData = data.removePrefix("$mainUrl/").removePrefix("$mainUrl|").trim()
        val parts = cleanData.split("|")
        if (parts.size < 5) {
            Log.e(TAG, "loadLinks: invalid data format (parts=${parts.size})")
            return false
        }
        val slug = parts[0]
        val epNum = parts[1]
        val type = parts[2] // "sub" or "dub"
        val providerIds = parts[3].split(",").filter { it.isNotBlank() }
        // parts[4] = "id1=tip1;;id2=tip2;;..."
        val tipsMap: Map<String, String> = if (parts.size >= 5 && parts[4].isNotBlank()) {
            parts[4].split(";;").mapNotNull { entry ->
                val eqIdx = entry.indexOf('=')
                if (eqIdx > 0) entry.substring(0, eqIdx) to entry.substring(eqIdx + 1)
                else null
            }.toMap()
        } else emptyMap()

        Log.d(TAG, "loadLinks: slug=$slug epNum=$epNum type=$type providers=$providerIds")
        if (providerIds.isEmpty()) {
            Log.e(TAG, "loadLinks: no providers")
            return false
        }

        var found = false
        for (providerId in providerIds) {
            val tip = tipsMap[providerId]
            Log.d(TAG, "loadLinks: provider=$providerId type=$type tip=$tip")
            try {
                // 1. Fetch sources from the API with the correct type (sub/dub)
                val sourcesUrl = "$chadUrl/sources?id=$slug&epNum=$epNum&type=$type&providerId=$providerId"
                val sourcesRes = cfAppGet(
                    sourcesUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
                )
                Log.d(TAG, "loadLinks: $providerId code=${sourcesRes.code} size=${sourcesRes.text.length}")

                if (sourcesRes.code != 200 ||
                    sourcesRes.text.contains("bot_detected") ||
                    sourcesRes.text.contains("\"error\"")
                ) {
                    Log.e(TAG, "loadLinks: $providerId API failed (code=${sourcesRes.code})")
                    continue
                }

                val sourcesData = try {
                    parseJson<SourcesResponse>(sourcesRes.text)
                } catch (e: Exception) {
                    Log.e(TAG, "loadLinks: $providerId parse failed: ${e.message} body=${sourcesRes.text.take(200)}")
                    continue
                }

                val sources = sourcesData.sources ?: emptyList()
                val tracks = sourcesData.tracks ?: emptyList()
                //  use the EXACT headers map from the API response
                // This contains Referer, Origin, User-Agent — whatever the CDN needs
                val apiHeaders: Map<String, String> = sourcesData.headers ?: emptyMap()

                Log.d(TAG, "loadLinks: $providerId sources=${sources.size} tracks=${tracks.size} headers=$apiHeaders")

                if (sources.isEmpty()) {
                    Log.e(TAG, "loadLinks: $providerId no sources in response")
                    continue
                }

                // Subtitle files share the video CDN, so they need the same
                // Referer/Origin headers to avoid empty/403 responses.
                for (track in tracks) {
                    var trackUrl = track.url ?: continue
                    if (trackUrl.isBlank()) continue
                    trackUrl = trackUrl.replace("https:///", "https://").replace("http:///", "http://")
                    if (!trackUrl.startsWith("http")) {
                        trackUrl = "$chadHost/${trackUrl.removePrefix("/")}"
                    }
                    val label = track.label ?: track.lang ?: "Subtitle"
                    if (track.kind == "captions" || track.kind == "subtitles" || track.kind == "metadata") {
                        val subHeaders = when {
                            trackUrl.contains("lostproject.club") -> mapOf("Referer" to "https://megaplay.buzz/")
                            trackUrl.contains("megaplay.buzz") -> mapOf("Referer" to "https://megaplay.buzz/")
                            else -> apiHeaders
                        }
                        subtitleCallback.invoke(newSubtitleFile(label, trackUrl) {
                            this.headers = subHeaders
                        })
                        Log.d(TAG, "loadLinks: $providerId subtitle '$label' added")
                    }
                }

                // 3. Process each source URL
                // Some providers (uwu) return multiple sources at different qualities
                for (source in sources) {
                    val sourceUrl = source.url ?: continue
                    if (sourceUrl.isBlank()) continue
                    val sourceType = source.type ?: ""
                    val quality = source.quality ?: "auto"
                    val qualityInt = parseQuality(quality)

                    // Build a clear label with provider name, type, tip, and quality
                    val label = buildSourceLabel(providerId, tip, quality, type)
                    Log.d(TAG, "loadLinks: $providerId source url=${sourceUrl.take(80)} type=$sourceType quality=$quality")

                    val isM3u8 = sourceUrl.contains(".m3u8") ||
                        sourceType.contains("mpegurl", ignoreCase = true) ||
                        sourceType.contains("m3u8", ignoreCase = true)
                    val isMp4 = sourceUrl.contains(".mp4") || sourceUrl.contains(".webm") ||
                        sourceType.contains("mp4", ignoreCase = true) ||
                        sourceType.contains("webm", ignoreCase = true)
                    val isDASH = sourceUrl.contains(".mpd") || sourceType.contains("dash", ignoreCase = true)

                    when {
                        // ── HLS m3u8 ──
                        // ALWAYS use a direct ExtractorLink with ExtractorLinkType.M3U8 and
                        // the FULL headers map from the API. Do NOT use M3u8Helper — it
                        // fetches/parses the m3u8 internally and can silently return an empty
                        // list (causing the source to not appear). ExoPlayer handles m3u8
                        // natively and applies the headers to ALL requests (master playlist,
                        // sub-playlist, AND segments on cross-domain CDNs like yuki's
                        // ik234.ovexa.buzz segment host).
                        isM3u8 -> {
                            callback.invoke(
                                newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.M3U8) {
                                    this.headers = apiHeaders
                                    this.quality = qualityInt
                                }
                            )
                            found = true
                            Log.d(TAG, "loadLinks: $providerId m3u8 (direct, headers=${apiHeaders.keys}) added")
                        }

                        // ── DASH mpd ── (ExoPlayer supports DASH but CloudStream's
                        // ExtractorLink doesn't have a DASH type. Skip for now.)
                        isDASH -> {
                            Log.d(TAG, "loadLinks: $providerId DASH (mpd) — adding as direct link")
                            callback.invoke(
                                newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.VIDEO) {
                                    this.headers = apiHeaders
                                    this.quality = qualityInt
                                }
                            )
                            found = true
                        }

                        // ── Direct mp4/webm ──
                        isMp4 -> {
                            callback.invoke(
                                newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.VIDEO) {
                                    this.headers = apiHeaders
                                    this.quality = qualityInt
                                }
                            )
                            found = true
                            Log.d(TAG, "loadLinks: $providerId mp4 added")
                        }

                        // ── Unknown URL type ──
                        // Try CloudStream's built-in extractors first (kwik.cx, etc.)
                        // If that fails, add as direct link with the full headers
                        else -> {
                            val referer = apiHeaders["Referer"] ?: apiHeaders["referer"] ?: "$mainUrl/"
                            val loaded = try {
                                loadExtractor(sourceUrl, referer, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.e(TAG, "loadLinks: $providerId loadExtractor error: ${e.message}")
                                false
                            }
                            if (loaded) {
                                found = true
                                Log.d(TAG, "loadLinks: $providerId resolved via loadExtractor")
                            } else {
                                // Last resort — direct link with full headers
                                callback.invoke(
                                    newExtractorLink(label, label, sourceUrl, type = ExtractorLinkType.VIDEO) {
                                        this.headers = apiHeaders
                                        this.quality = qualityInt
                                    }
                                )
                                found = true
                                Log.d(TAG, "loadLinks: $providerId direct fallback added")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: $providerId FAILED: ${e.message}")
            }
        }

        Log.d(TAG, "loadLinks END: found=$found")
        return found
    }
}
