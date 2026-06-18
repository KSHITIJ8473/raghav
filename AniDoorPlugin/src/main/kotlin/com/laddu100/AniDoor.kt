package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import java.net.URI

class AniDoor : MainAPI() {
    override var mainUrl = "https://anidoor.me"
    override var name = "AniDoor"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "airing" to "Airing Anime",
        "popular" to "Popular Anime",
        "upcoming" to "Upcoming Anime",
        "top" to "Top Rated All Time"
    )

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        return app.post(
            "https://graphql.anilist.co",
            json = mapOf("query" to query, "variables" to variables),
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json")
        ).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = when (request.data) {
            "trending" -> TRENDING_QUERY
            "popular" -> POPULAR_QUERY
            "upcoming" -> UPCOMING_QUERY
            "top" -> TOP_QUERY
            else -> TRENDING_QUERY
        }

        val variables = mapOf("page" to page)
        val responseText = anilistQuery(query, variables)
        val response = parseJson<AniListSearchResponse>(responseText)
        val mediaList = response.data?.page?.media ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/watch/?al=$id", TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf("search" to query, "page" to 1)
        val responseText = anilistQuery(SEARCH_MUTATION, variables)
        val response = parseJson<AniListSearchResponse>(responseText)
        val mediaList = response.data?.page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/watch/?al=$id", TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = URI(url)
        val queryParams = uri.query?.split("&")?.associate {
            val split = it.split("=")
            val key = split.getOrNull(0) ?: ""
            val value = split.getOrNull(1) ?: ""
            key to value
        } ?: emptyMap()
        
        val anilistId = queryParams["al"]?.toIntOrNull() 
            ?: Regex("""al=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() 
            ?: return null

        val variables = mapOf("id" to anilistId)
        val responseText = anilistQuery(INFO_QUERY, variables)
        val response = parseJson<AniListDetailsResponse>(responseText)
        val media = response.data?.media ?: return null

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore
        val malId = media.idMal ?: 0

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

        val episodesList = mutableListOf<Episode>()

        if (media.format == "MOVIE") {
            val dataStr = "$anilistId|$malId|1|true"
            episodesList.add(newEpisode(dataStr) {
                this.name = title
                this.episode = 1
            })
        } else {
            try {
                val aniZipRes = app.get("https://api.ani.zip/mappings?anilist_id=$anilistId").text
                val aniZipData = parseJson<AniZipResponse>(aniZipRes)
                val aniZipEps = aniZipData.episodes ?: emptyMap()

                if (aniZipEps.isNotEmpty()) {
                    aniZipEps.forEach { (epKey, epData) ->
                        val epNum = epKey.toIntOrNull() ?: return@forEach
                        val epTitle = epData.title?.get("en") ?: epData.title?.get("x-jat") ?: "Episode $epNum"
                        val epDesc = epData.overview ?: epData.summary
                        val epThumb = epData.image
                        val dataStr = "$anilistId|$malId|$epNum|false"

                        episodesList.add(newEpisode(dataStr) {
                            this.name = epTitle
                            this.episode = epNum
                            this.description = epDesc
                            this.posterUrl = epThumb
                        })
                    }
                } else {
                    val totalEps = media.episodes ?: 0
                    val count = if (totalEps > 0) totalEps else 12
                    for (i in 1..count) {
                        val dataStr = "$anilistId|$malId|$i|false"
                        episodesList.add(newEpisode(dataStr) {
                            this.name = "Episode $i"
                            this.episode = i
                        })
                    }
                }
            } catch (e: Exception) {
                val totalEps = media.episodes ?: 0
                val count = if (totalEps > 0) totalEps else 12
                for (i in 1..count) {
                    val dataStr = "$anilistId|$malId|$i|false"
                    episodesList.add(newEpisode(dataStr) {
                        this.name = "Episode $i"
                        this.episode = i
                    })
                }
            }
        }

        val subEpisodes = episodesList.map { ep ->
            newEpisode("sub|${ep.data}") {
                this.name = ep.name
                this.episode = ep.episode
                this.description = ep.description
                this.posterUrl = ep.posterUrl
            }
        }

        val dubEpisodes = episodesList.map { ep ->
            newEpisode("dub|${ep.data}") {
                this.name = ep.name
                this.episode = ep.episode
                this.description = ep.description
                this.posterUrl = ep.posterUrl
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = bannerUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            if (animeScore != null) this.score = Score.from10((animeScore / 10.0).toString())
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
        val parts = data.split("|")
        if (parts.size < 5) return false

        val dubOrSub = parts[0]
        val alId = parts[1]
        val malId = parts[2]
        val epNum = parts[3].toIntOrNull() ?: 1
        val isMovie = parts[4].toBoolean()

        // Hardcoded fallback sources (always used as baseline)
        // MegaPlay: MAL URLs work for most anime, ani URLs return 404 for ~90% of anime
        // VidNest: all backends currently return 502/404
        // TryEmbed: RAW_PAYLOAD only has meta (no providers), API returns 403
        val hardcodedSourcesJson = """[
            {"id":"megaplay-sub-mal","name":"S2","base":"https://megaplay.buzz","path":"/stream/mal/{mal}/{e}/sub","dub":false},
            {"id":"megaplay-movie-sub-mal","name":"S2","base":"https://megaplay.buzz","path":"/stream/mal/{mal}/1/sub","dub":false},
            {"id":"megaplay-dub-mal","name":"D2","base":"https://megaplay.buzz","path":"/stream/mal/{mal}/{e}/dub","dub":true},
            {"id":"megaplay-movie-dub-mal","name":"D2","base":"https://megaplay.buzz","path":"/stream/mal/{mal}/1/dub","dub":true},
            {"id":"megaplay-sub-ani","name":"S2(ani)","base":"https://megaplay.buzz","path":"/stream/ani/{al}/{e}/sub","dub":false},
            {"id":"megaplay-movie-sub-ani","name":"S2(ani)","base":"https://megaplay.buzz","path":"/stream/ani/{al}/1/sub","dub":false},
            {"id":"megaplay-dub-ani","name":"D2(ani)","base":"https://megaplay.buzz","path":"/stream/ani/{al}/{e}/dub","dub":true},
            {"id":"megaplay-movie-dub-ani","name":"D2(ani)","base":"https://megaplay.buzz","path":"/stream/ani/{al}/1/dub","dub":true},
            {"id":"tryembed-sub","name":"S4","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/{e}/sub","dub":false},
            {"id":"tryembed-movie-sub","name":"S4","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/1/sub","dub":false},
            {"id":"tryembed-dub","name":"D4","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/{e}/dub","dub":true},
            {"id":"tryembed-movie-dub","name":"D4","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/1/dub","dub":true},
            {"id":"vidnest-ap-sub","name":"S1","base":"https://vidnest.fun","path":"/animepahe/{al}/{e}/sub","dub":false},
            {"id":"vidnest-ap-movie-sub","name":"S1","base":"https://vidnest.fun","path":"/animepahe/{al}/1/sub","dub":false},
            {"id":"vidnest-ap-dub","name":"D1","base":"https://vidnest.fun","path":"/animepahe/{al}/{e}/dub","dub":true},
            {"id":"vidnest-ap-movie-dub","name":"D1","base":"https://vidnest.fun","path":"/animepahe/{al}/1/dub","dub":true},
            {"id":"vidnest-anime-sub","name":"S3","base":"https://vidnest.fun","path":"/anime/{al}/{e}/sub","dub":false},
            {"id":"vidnest-anime-movie-sub","name":"S3","base":"https://vidnest.fun","path":"/anime/{al}/1/sub","dub":false},
            {"id":"vidnest-anime-dub","name":"D3","base":"https://vidnest.fun","path":"/anime/{al}/{e}/dub","dub":true},
            {"id":"vidnest-anime-movie-dub","name":"D3","base":"https://vidnest.fun","path":"/anime/{al}/1/dub","dub":true}
        ]"""

        // Parse hardcoded sources as the baseline
        val hardcodedSources = try {
            parseJson<List<AniDoorSourceConfig>>(hardcodedSourcesJson)
        } catch (e: Exception) {
            emptyList()
        }

        // Try to fetch live sources.json and merge with hardcoded (dedup by id)
        val liveSources = try {
            val liveJson = app.get("https://anidoor.me/assets/sources.json").text
            parseJson<List<AniDoorSourceConfig>>(liveJson)
        } catch (e: Exception) {
            emptyList()
        }

        // Merge: start with hardcoded, add live entries with new ids not already present
        val hardcodedIds = hardcodedSources.mapNotNull { it.id }.toSet()
        val mergedSources = hardcodedSources.toMutableList()
        for (liveSource in liveSources) {
            if (liveSource.id != null && liveSource.id !in hardcodedIds) {
                mergedSources.add(liveSource)
            }
        }
        val sources = mergedSources

        val wantType = if (isMovie) "movie" else "anime"
        val filteredSources = sources.filter { s ->
            // Live sources.json has no "type" field; infer from path: if path has {e} it's anime, else movie
            val pathType = if (s.path?.contains("{e}") == true) "anime" else "movie"
            (s.type ?: pathType).equals(wantType, ignoreCase = true)
        }

        val isDubRequest = (dubOrSub == "dub")
        val audioFilteredSources = filteredSources.filter { s ->
            (s.dub ?: false) == isDubRequest
        }

        var found = false
        var linkCount = 0
        var subCount = 0

        // Wrapper callback that tracks actual link delivery
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            linkCount++
            callback(link)
        }
        val trackingSubCallback: (SubtitleFile) -> Unit = { sub ->
            subCount++
            subtitleCallback(sub)
        }

        audioFilteredSources.forEach { source ->
            val path = source.path ?: return@forEach
            val base = source.base ?: return@forEach

            if (path.contains("{mal}") && (malId == "0" || malId.isBlank())) {
                return@forEach
            }

            val resolvedPath = path
                .replace("{al}", alId)
                .replace("{mal}", malId)
                .replace("{s}", "1")
                .replace("{e}", epNum.toString())

            val embedUrl = base + resolvedPath

            val beforeCount = linkCount

            val loaded = try {
                if (embedUrl.contains("megaplay.buzz") || embedUrl.contains("tryembed.us.cc") || embedUrl.contains("vidnest.fun")) {
                    false
                } else {
                    loadExtractor(embedUrl, "https://anidoor.me/", trackingSubCallback, trackingCallback)
                }
            } catch (e: Exception) {
                false
            }

            if (loaded) {
                if (linkCount > beforeCount) found = true
            } else {
                try {
                    if (embedUrl.contains("megaplay.buzz")) {
                        AniDoorMegaPlay().getUrl(embedUrl, "https://anidoor.me/", trackingSubCallback, trackingCallback)
                        if (linkCount > beforeCount) found = true
                    } else if (embedUrl.contains("tryembed.us.cc")) {
                        AniDoorTryEmbed().getUrl(embedUrl, "https://anidoor.me/", trackingSubCallback, trackingCallback)
                        if (linkCount > beforeCount) found = true
                    } else if (embedUrl.contains("vidnest.fun")) {
                        AniDoorVidnest().getUrl(embedUrl, "https://anidoor.me/", trackingSubCallback, trackingCallback)
                        if (linkCount > beforeCount) found = true
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        return found
    }

    companion object {
        private val SEARCH_MUTATION = """
            query (${'$'}search: String, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(search: ${'$'}search, type: ANIME, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val INFO_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id
                idMal
                format
                title { romaji english }
                description(asHtml: false)
                coverImage { extraLarge large }
                bannerImage
                averageScore
                seasonYear
                episodes
                status
                genres
              }
            }
        """.trimIndent()

        private val TRENDING_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val POPULAR_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val UPCOMING_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, status: NOT_YET_RELEASED, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val TOP_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: SCORE_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()
    }
}

// ── DATA CLASSES FOR JACKSON PARSING ──────────────────────────────────
data class AniListSearchResponse(
    @JsonProperty("data") val data: AniListSearchData? = null
)
data class AniListSearchData(
    @JsonProperty("Page") val page: AniListMediaPageContainer? = null
)
data class AniListMediaPageContainer(
    @JsonProperty("media") val media: List<AniListMedia>? = null
)

data class AniListDetailsResponse(
    @JsonProperty("data") val data: AniListDetailsData? = null
)
data class AniListDetailsData(
    @JsonProperty("Media") val media: AniListMedia? = null
)

data class AniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: AniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AniListCoverImage? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null
)

data class AniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null
)

data class AniListCoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null
)

data class AniZipResponse(
    @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
)

data class AniZipEpisode(
    @JsonProperty("title") val title: Map<String, String>? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("summary") val summary: String? = null
)

data class AniDoorSourceConfig(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("base") val base: String? = null,
    @JsonProperty("path") val path: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("dub") val dub: Boolean? = null
)
