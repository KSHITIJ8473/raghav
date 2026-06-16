package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class Anivexa : MainAPI() {
    override var mainUrl = "https://anivexa.vercel.app"
    override var name = "Anivexa"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "popular" to "Popular Anime",
        "upcoming" to "Upcoming Anime",
        "top" to "Top Rated All Time"
    )

    private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
        val requestData = mapOf(
            "query" to query,
            "variables" to variables
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        return app.post(
            "https://graphql.anilist.co",
            requestBody = requestData,
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
        val response = parseJson<AnivexaAniListSearchResponse>(responseText)
        val mediaList = response.data?.page?.media ?: emptyList()

        val home = mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large

            newAnimeSearchResponse(title, "$mainUrl/anime.html?id=$id", TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, home, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val variables = mapOf("search" to query, "page" to 1)
        val responseText = anilistQuery(SEARCH_MUTATION, variables)
        val response = parseJson<AnivexaAniListSearchResponse>(responseText)
        val mediaList = response.data?.page?.media ?: emptyList()

        return mediaList.mapNotNull { media ->
            val id = media.id ?: return@mapNotNull null
            val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
            val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large

            newAnimeSearchResponse(title, "$mainUrl/anime.html?id=$id", TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = Regex("""id=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        val variables = mapOf("id" to animeId)
        val responseText = anilistQuery(INFO_QUERY, variables)
        val response = parseJson<AnivexaAniListDetailsResponse>(responseText)
        val media = response.data?.media ?: return null

        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val bannerUrl = media.bannerImage
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val animeScore = media.averageScore

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

        val apiEpisodes = runCatching {
            parseJson<AnivexaEpisodeApiResponse>(anivexaApi("/episodes/$animeId"))
        }.getOrNull()

        val subEpisodes = buildEpisodeList(animeId, "sub", title, apiEpisodes)
        val dubEpisodes = buildEpisodeList(animeId, "dub", title, apiEpisodes)

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            val nextAiring = media.nextAiringEpisode?.episode
            val totalEps = media.episodes ?: (if (nextAiring != null) nextAiring - 1 else 1)
            val count = if (totalEps > 0) totalEps else 1
            for (i in 1..count) {
                subEpisodes.add(newEpisode("$animeId|$i|sub") {
                    this.name = if (media.format == "MOVIE") title else "Episode $i"
                    this.episode = i
                })
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
            addAniListId(animeId)
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
        if (parts.size < 3) return false

        val animeId = parts[0]
        val epNum = parts[1].toIntOrNull() ?: return false
        val audioType = parts[2].lowercase()
        var linksHarvested = false
        val seen = mutableSetOf<String>()

        try {
            val episodes = parseJson<AnivexaEpisodeApiResponse>(anivexaApi("/episodes/$animeId"))
            val candidates = collectEpisodeCandidates(episodes, audioType, epNum)

            for (candidate in candidates) {
                val safeEpisodeId = candidate.providerEpisodeId.urlEncode()
                val watchJson = runCatching {
                    anivexaApi("/watch/${candidate.provider}/$animeId/$audioType/$safeEpisodeId")
                }.getOrNull() ?: continue
                val watch = runCatching { parseJson<AnivexaWatchResponse>(watchJson) }.getOrNull() ?: continue

                collectSubtitles(watch).forEach { subtitle ->
                    val subtitleUrl = subtitle.file ?: subtitle.url ?: return@forEach
                    subtitleCallback(SubtitleFile(subtitle.label ?: subtitle.language ?: "English", subtitleUrl))
                }

                collectSources(watch)
                    .sortedWith(
                        compareByDescending<AnivexaWatchSource> { it.isActive == true }
                            .thenByDescending { it.priority ?: 0.0 }
                    )
                    .forEach { source ->
                        val streamUrl = source.url ?: source.file ?: source.link ?: return@forEach
                        if (!seen.add(streamUrl)) return@forEach
                        val sourceName = source.server ?: source.name ?: candidate.provider.displayProviderName()
                        val referer = source.referer ?: source.embed ?: source.headers?.get("Referer") ?: "$mainUrl/"
                        val headers = streamHeaders(referer, source.headers)
                        val label = "Anivexa $sourceName ${audioType.uppercase()}"
                        val streamType = source.type?.lowercase()

                        when {
                            streamUrl.contains(".m3u8", ignoreCase = true) || streamType == "hls" -> {
                                val generated = M3u8Helper.generateM3u8(label, streamUrl, referer, headers = headers)
                                if (generated.isNotEmpty()) {
                                    generated.forEach(callback)
                                } else {
                                    callback(
                                        newExtractorLink(
                                            source = "Anivexa $sourceName",
                                            name = label,
                                            url = streamUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = referer
                                            this.headers = headers
                                        }
                                    )
                                }
                                linksHarvested = true
                            }
                            streamUrl.contains(".mp4", ignoreCase = true) || streamType == "mp4" -> {
                                callback(
                                    newExtractorLink(
                                        source = "Anivexa $sourceName",
                                        name = "$label ${source.quality ?: ""}".trim(),
                                        url = streamUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = referer
                                        this.headers = headers
                                    }
                                )
                                linksHarvested = true
                            }
                            streamUrl.startsWith("http", ignoreCase = true) -> {
                                loadExtractor(streamUrl, referer, subtitleCallback, callback)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e("Anivexa", "API extraction failed: ${e.message}")
        }

        return linksHarvested
    }

    private suspend fun anivexaApi(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return app.get(
            "$mainUrl/api/anime$cleanPath",
            referer = "$mainUrl/",
            headers = anivexaHeaders()
        ).text
    }

    private suspend fun anivexaHeaders(): Map<String, String> {
        val pbt = runCatching {
            parseJson<AnivexaPbtResponse>(
                app.get(
                    "$mainUrl/api/pbt?t=${System.currentTimeMillis()}",
                    referer = "$mainUrl/",
                    headers = baseHeaders
                ).text
            ).t
        }.getOrNull()

        return baseHeaders + mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        ) + (pbt?.let { mapOf("X-KV-PBT" to it) } ?: emptyMap())
    }

    private fun buildEpisodeList(
        animeId: Int,
        audioType: String,
        movieTitle: String,
        response: AnivexaEpisodeApiResponse?
    ): MutableList<Episode> {
        val selected = selectEpisodes(response, audioType)
        return selected
            .mapNotNull { apiEpisode ->
                val number = apiEpisode.number ?: return@mapNotNull null
                newEpisode("$animeId|$number|$audioType") {
                    this.episode = number
                    this.name = apiEpisode.title?.takeIf { it.isNotBlank() }
                        ?: if (number == 1 && selected.size == 1) movieTitle else "Episode $number"
                    this.posterUrl = apiEpisode.image ?: apiEpisode.thumbnail
                }
            }
            .distinctBy { it.episode }
            .sortedBy { it.episode }
            .toMutableList()
    }

    private fun selectEpisodes(response: AnivexaEpisodeApiResponse?, audioType: String): List<AnivexaApiEpisode> {
        val providers = response?.providers ?: return emptyList()
        val allmanga = providers["allmanga"]?.episodesFor(audioType).orEmpty()
        if (allmanga.isNotEmpty()) return allmanga

        return providers.values
            .map { it.episodesFor(audioType) }
            .maxByOrNull { it.size }
            .orEmpty()
    }

    private fun collectEpisodeCandidates(
        response: AnivexaEpisodeApiResponse,
        audioType: String,
        episodeNumber: Int
    ): List<AnivexaEpisodeCandidate> {
        return response.providers.flatMap { (provider, providerData) ->
            providerData.episodesFor(audioType)
                .filter { it.number == episodeNumber }
                .mapNotNull { apiEpisode ->
                    val id = apiEpisode.id?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                    id?.let { AnivexaEpisodeCandidate(provider, it) }
                }
        }.sortedBy { candidate ->
            val index = providerOrder.indexOf(candidate.provider)
            if (index >= 0) index else providerOrder.size
        }
    }

    private fun AnivexaProviderData.episodesFor(audioType: String): List<AnivexaApiEpisode> {
        return if (audioType.equals("dub", ignoreCase = true)) {
            episodes?.dub.orEmpty()
        } else {
            episodes?.sub.orEmpty()
        }
    }

    private fun collectSources(response: AnivexaWatchResponse): List<AnivexaWatchSource> {
        val sources = mutableListOf<AnivexaWatchSource>()
        fun addPayload(payload: AnivexaWatchPayload?) {
            if (payload == null) return
            sources.addAll(payload.streams.orEmpty())
            sources.addAll(payload.sources.orEmpty())
            payload.allServers.orEmpty().forEach { sources.add(it.toSource()) }
            payload.m3u8?.let { sources.add(AnivexaWatchSource(url = it, type = "hls", server = payload.provider)) }
            payload.url?.let { sources.add(AnivexaWatchSource(url = it, type = payload.type, server = payload.provider)) }
        }

        sources.addAll(response.streams.orEmpty())
        sources.addAll(response.sources.orEmpty())
        response.allServers.orEmpty().forEach { sources.add(it.toSource()) }
        response.m3u8?.let { sources.add(AnivexaWatchSource(url = it, type = "hls")) }
        response.url?.let { sources.add(AnivexaWatchSource(url = it, type = response.type)) }
        addPayload(response.ssub)
        addPayload(response.sdub)
        addPayload(response.sub)
        addPayload(response.dub)
        addPayload(response.data)
        addPayload(response.result)
        return sources
    }

    private fun collectSubtitles(response: AnivexaWatchResponse): List<AnivexaSubtitleTrack> {
        return response.subtitles.orEmpty() +
            response.tracks.orEmpty() +
            response.captions.orEmpty() +
            response.ssub?.subtitles.orEmpty() +
            response.sdub?.subtitles.orEmpty() +
            response.sub?.subtitles.orEmpty() +
            response.dub?.subtitles.orEmpty() +
            response.data?.subtitles.orEmpty() +
            response.result?.subtitles.orEmpty()
    }

    private fun AnivexaAllServer.toSource(): AnivexaWatchSource {
        return AnivexaWatchSource(
            name = name,
            url = url ?: link ?: file,
            type = type,
            server = server ?: name,
            referer = referer,
            priority = priority,
            headers = headers
        )
    }

    private fun streamHeaders(referer: String, sourceHeaders: Map<String, String>?): Map<String, String> {
        return baseHeaders + sourceHeaders.orEmpty() + mapOf("Referer" to referer)
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun String.displayProviderName(): String {
        return split("-", "_").joinToString(" ") { part ->
            part.replaceFirstChar { char -> char.uppercase() }
        }
    }

    companion object {
        private val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
        )

        private val providerOrder = listOf(
            "anineko",
            "anikoto",
            "animepahe",
            "animegg",
            "reanime",
            "allmanga",
            "anidbapp",
            "mimi",
            "mochi",
            "shiro",
            "wave",
            "zen"
        )

        private val SEARCH_MUTATION = """
            query (${'$'}search: String, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(search: ${'$'}search, type: ANIME, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val INFO_QUERY = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                id format title { romaji english } description(asHtml: false)
                coverImage { extraLarge large } bannerImage averageScore
                seasonYear episodes status genres
                nextAiringEpisode { episode }
              }
            }
        """.trimIndent()

        private val TRENDING_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: TRENDING_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val POPULAR_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: POPULARITY_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val UPCOMING_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, status: NOT_YET_RELEASED, sort: POPULARITY_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()

        private val TOP_QUERY = """
            query (${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: 20) {
                media(type: ANIME, sort: SCORE_DESC, isAdult: false) {
                  id title { romaji english } coverImage { extraLarge large } format
                }
              }
            }
        """.trimIndent()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaPbtResponse(
    @JsonProperty("t") val t: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaEpisodeApiResponse(
    @JsonProperty("providers") val providers: Map<String, AnivexaProviderData> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaProviderData(
    @JsonProperty("episodes") val episodes: AnivexaEpisodeGroups? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaEpisodeGroups(
    @JsonProperty("sub") val sub: List<AnivexaApiEpisode> = emptyList(),
    @JsonProperty("dub") val dub: List<AnivexaApiEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaApiEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null
)

data class AnivexaEpisodeCandidate(
    val provider: String,
    val providerEpisodeId: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaWatchResponse(
    @JsonProperty("streams") val streams: List<AnivexaWatchSource>? = null,
    @JsonProperty("sources") val sources: List<AnivexaWatchSource>? = null,
    @JsonProperty("subtitles") val subtitles: List<AnivexaSubtitleTrack>? = null,
    @JsonProperty("tracks") val tracks: List<AnivexaSubtitleTrack>? = null,
    @JsonProperty("captions") val captions: List<AnivexaSubtitleTrack>? = null,
    @JsonProperty("allServers") val allServers: List<AnivexaAllServer>? = null,
    @JsonProperty("ssub") val ssub: AnivexaWatchPayload? = null,
    @JsonProperty("sdub") val sdub: AnivexaWatchPayload? = null,
    @JsonProperty("sub") val sub: AnivexaWatchPayload? = null,
    @JsonProperty("dub") val dub: AnivexaWatchPayload? = null,
    @JsonProperty("data") val data: AnivexaWatchPayload? = null,
    @JsonProperty("result") val result: AnivexaWatchPayload? = null,
    @JsonProperty("m3u8") val m3u8: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaWatchPayload(
    @JsonProperty("streams") val streams: List<AnivexaWatchSource>? = null,
    @JsonProperty("sources") val sources: List<AnivexaWatchSource>? = null,
    @JsonProperty("subtitles") val subtitles: List<AnivexaSubtitleTrack>? = null,
    @JsonProperty("allServers") val allServers: List<AnivexaAllServer>? = null,
    @JsonProperty("m3u8") val m3u8: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("provider") val provider: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaWatchSource(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("priority") val priority: Double? = null,
    @JsonProperty("isActive") val isActive: Boolean? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAllServer(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("priority") val priority: Double? = null,
    @JsonProperty("headers") val headers: Map<String, String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaSubtitleTrack(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("language") val language: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListSearchResponse(
    @JsonProperty("data") val data: AnivexaAniListSearchData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListSearchData(
    @JsonProperty("Page") val page: AnivexaAniListMediaPageContainer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListMediaPageContainer(
    @JsonProperty("media") val media: List<AnivexaAniListMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListDetailsResponse(
    @JsonProperty("data") val data: AnivexaAniListDetailsData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListDetailsData(
    @JsonProperty("Media") val media: AnivexaAniListMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: AnivexaAniListTitle? = null,
    @JsonProperty("coverImage") val coverImage: AnivexaAniListCoverImage? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: AnivexaNextAiring? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaNextAiring(
    @JsonProperty("episode") val episode: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListTitle(
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("english") val english: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnivexaAniListCoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null
)


