package com.laddu100

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
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
            "airing" -> AIRING_QUERY
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
            split.getOrNull(0) to split.getOrNull(1)
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
            episodesList.add(newEpisode("$anilistId|$malId|1|true") {
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
                        episodesList.add(newEpisode("$anilistId|$malId|$epNum|false") {
                            this.name = epTitle
                            this.episode = epNum
                            this.posterUrl = epData.image
                        })
                    }
                } else {
                    val totalEps = media.episodes ?: 0
                    val count = if (totalEps > 0) totalEps else 12
                    for (i in 1..count) {
                        episodesList.add(newEpisode("$anilistId|$malId|$i|false") {
                            this.name = "Episode $i"
                            this.episode = i
                        })
                    }
                }
            } catch (e: Exception) {
                val totalEps = media.episodes ?: 0
                val count = if (totalEps > 0) totalEps else 12
                for (i in 1..count) {
                    episodesList.add(newEpisode("$anilistId|$malId|$i|false") {
                        this.name = "Episode $i"
                        this.episode = i
                    })
                }
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
            if (episodesList.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodesList.map { ep ->
                    newEpisode("sub|${ep.data}") {
                        this.name = ep.name
                        this.episode = ep.episode
                        this.posterUrl = ep.posterUrl
                    }
                })
                addEpisodes(DubStatus.Dubbed, episodesList.map { ep ->
                    newEpisode("dub|${ep.data}") {
                        this.name = ep.name
                        this.episode = ep.episode
                        this.posterUrl = ep.posterUrl
                    }
                })
            }
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

        val validMalId = if (malId == "0" || malId.isBlank()) null else malId
        val isDubRequest = (dubOrSub == "dub")

        val sources = try {
            app.get("https://anidoor.me/assets/sources.json", headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json"
            )).takeIf { it.code == 200 }?.let {
                parseJson<List<AniDoorSourceConfig>>(it.text)
            } ?: parseJson<List<AniDoorSourceConfig>>(DEFAULT_SOURCES_JSON)
        } catch (_: Exception) {
            parseJson<List<AniDoorSourceConfig>>(DEFAULT_SOURCES_JSON)
        }

        if (sources.isEmpty()) return false

        val wantType = if (isMovie) "movie" else "anime"

        // Filter sources: prefer strict dub/sub match, fallback to all sources if dub has nothing
        var filteredSources = sources.filter { s ->
            (s.type == null || s.type.equals(wantType, ignoreCase = true)) &&
            (s.dub ?: false) == isDubRequest
        }

        // CRITICAL: Fallback to all sources if dub request fails (common when dub unavailable)
        if (filteredSources.isEmpty() && isDubRequest) {
            Log.d(name, "No dub sources found, trying all sources as fallback")
            filteredSources = sources.filter { s ->
                s.type == null || s.type.equals(wantType, ignoreCase = true)
            }
        }

        Log.d(name, "Loading $dubOrSub ep=$epNum type=$wantType sources=${filteredSources.size}")

        var linkCount = 0
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            linkCount++
            callback(link)
        }

        filteredSources.forEach { source ->
            val path = source.path ?: return@forEach
            val base = source.base ?: return@forEach

            if (path.contains("{mal}") && validMalId == null) return@forEach

            val embedUrl = base + path
                .replace("{al}", alId)
                .replace("{mal}", validMalId ?: "0")
                .replace("{s}", "1")
                .replace("{e}", epNum.toString())

            try {
                when {
                    embedUrl.contains("megaplay.buzz") -> AniDoorMegaPlay().getUrl(embedUrl, "https://anidoor.me/", subtitleCallback, trackingCallback)
                    embedUrl.contains("tryembed.us.cc") -> AniDoorTryEmbed().getUrl(embedUrl, "https://anidoor.me/", subtitleCallback, trackingCallback)
                    embedUrl.contains("vidnest.fun") -> AniDoorVidnest().getUrl(embedUrl, "https://anidoor.me/", subtitleCallback, trackingCallback)
                    embedUrl.contains("dropfile.cc") -> AniDoorDropfile().getUrl(embedUrl, "https://anidoor.me/", subtitleCallback, trackingCallback)
                    embedUrl.contains("nightslayer.workers.dev") -> AniDoorHD().getUrl(embedUrl, "https://anidoor.me/", subtitleCallback, trackingCallback)
                    else -> loadExtractor(embedUrl, "https://anidoor.me/", subtitleCallback, trackingCallback)
                }
            } catch (e: Exception) {
                Log.d(name, "Extractor failed: ${e.message}")
            }
        }

        return linkCount > 0
    }

    companion object {
        private val DEFAULT_SOURCES_JSON = """
            [
                {"id":"vidnest-ap-sub","name":"S1","base":"https://vidnest.fun","path":"/animepahe/{al}/{e}/sub","type":"anime","dub":false,"default":true},
                {"id":"vidnest-ap-movie-sub","name":"S1","base":"https://vidnest.fun","path":"/animepahe/{al}/1/sub","type":"movie","dub":false,"default":true},
                {"id":"vidnest-ap-dub","name":"D1","base":"https://vidnest.fun","path":"/animepahe/{al}/{e}/dub","type":"anime","dub":true,"default":false},
                {"id":"vidnest-ap-movie-dub","name":"D1","base":"https://vidnest.fun","path":"/animepahe/{al}/1/dub","type":"movie","dub":true,"default":false},
                {"id":"megaplay-sub","name":"S2","base":"https://megaplay.buzz","path":"/stream/ani/{al}/{e}/sub","type":"anime","dub":false,"default":false},
                {"id":"megaplay-movie-sub","name":"S2","base":"https://megaplay.buzz","path":"/stream/ani/{al}/1/sub","type":"movie","dub":false,"default":false},
                {"id":"megaplay-dub","name":"D2","base":"https://megaplay.buzz","path":"/stream/ani/{al}/{e}/dub","type":"anime","dub":true,"default":false},
                {"id":"megaplay-movie-dub","name":"D2","base":"https://megaplay.buzz","path":"/stream/ani/{al}/1/dub","type":"movie","dub":true,"default":false},
                {"id":"vidnest-anime-sub","name":"S3","base":"https://vidnest.fun","path":"/anime/{al}/{e}/sub","type":"anime","dub":false,"default":false},
                {"id":"vidnest-anime-movie-sub","name":"S3","base":"https://vidnest.fun","path":"/anime/{al}/1/sub","type":"movie","dub":false,"default":false},
                {"id":"vidnest-anime-dub","name":"D3","base":"https://vidnest.fun","path":"/anime/{al}/{e}/dub","type":"anime","dub":true,"default":false},
                {"id":"vidnest-anime-movie-dub","name":"D3","base":"https://vidnest.fun","path":"/anime/{al}/1/dub","type":"movie","dub":true,"default":false},
                {"id":"dropfile-cc-sub","name":"S4","base":"https://dropfile.cc","path":"/player/tv/mal-{mal}/1/{e}?audio=sub&lang=en&color=%237c6fe0","type":"anime","dub":false,"default":false},
                {"id":"dropfile-cc-movie-sub","name":"S4","base":"https://dropfile.cc","path":"/player/tv/mal-{mal}/1/1?audio=sub&lang=en&color=%237c6fe0","type":"movie","dub":false,"default":false},
                {"id":"dropfile-cc-dub","name":"D4","base":"https://dropfile.cc","path":"/player/tv/mal-{mal}/1/{e}?audio=dub&lang=en&color=%237c6fe0","type":"anime","dub":true,"default":false},
                {"id":"dropfile-cc-movie-dub","name":"D4","base":"https://dropfile.cc","path":"/player/tv/mal-{mal}/1/1?audio=dub&lang=en&color=%237c6fe0","type":"movie","dub":true,"default":false},
                {"id":"hd-sub","name":"HD","base":"https://stream.nightslayer.workers.dev","path":"/player/{al}/{e}/sub","type":"anime","dub":false,"default":false},
                {"id":"hd-movie-sub","name":"HD","base":"https://stream.nightslayer.workers.dev","path":"/player/{al}/1/sub","type":"movie","dub":false,"default":false},
                {"id":"hd-dub","name":"HD","base":"https://stream.nightslayer.workers.dev","path":"/player/{al}/{e}/dub","type":"anime","dub":true,"default":false},
                {"id":"hd-movie-dub","name":"HD","base":"https://stream.nightslayer.workers.dev","path":"/player/{al}/1/dub","type":"movie","dub":true,"default":false},
                {"id":"tryembed-sub","name":"S5","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/{e}/sub","type":"anime","dub":false,"default":false},
                {"id":"tryembed-movie-sub","name":"S5","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/1/sub","type":"movie","dub":false,"default":false},
                {"id":"tryembed-dub","name":"D5","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/{e}/dub","type":"anime","dub":true,"default":false},
                {"id":"tryembed-movie-dub","name":"D5","base":"https://tryembed.us.cc","path":"/embed/anime/{al}/1/dub","type":"movie","dub":true,"default":false}
            ]
        """.trimIndent()

        private val SEARCH_MUTATION = """
            query ($search: String, $page: Int) {
              Page(page: $page, perPage: 20) {
                media(search: $search, type: ANIME, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val INFO_QUERY = """
            query ($id: Int) {
              Media(id: $id, type: ANIME) {
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
            query ($page: Int) {
              Page(page: $page, perPage: 20) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val AIRING_QUERY = """
            query ($page: Int) {
              Page(page: $page, perPage: 20) {
                media(type: ANIME, status: RELEASING, sort: POPULARITY_DESC, isAdult: false) {
                  id
                  title { romaji english }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        private val POPULAR_QUERY = """
            query ($page: Int) {
              Page(page: $page, perPage: 20) {
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
            query ($page: Int) {
              Page(page: $page, perPage: 20) {
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
            query ($page: Int) {
              Page(page: $page, perPage: 20) {
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

// ── DATA CLASSES ─────────────────────────────
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
    @JsonProperty("dub") val dub: Boolean? = null,
    @JsonProperty("default") val default: Boolean? = null
)