package com.laddu100

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class AnimaxAnime : MainAPI() {
    override var mainUrl = "https://animaxanime.vercel.app"
    override var name = "Animax Anime"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    private val jikanApi = "https://api.jikan.moe/v4"
    private val providerApi = "https://animax-api.animax-providers-api.workers.dev"
    private val apiKey = "661f70810072:8Sa2W7GtYw_WJ9XlrgU4ikGSsc3tj32Cz3Qcflga1Bg"

    private val defaultHeaders = mapOf(
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "X-Animax-Api-Key" to apiKey,
        "Accept" to "application/json, text/plain, */*",
    )

    override val mainPage = mainPageOf(
        "recent" to "Latest Episode",
        "airing" to "Currently Airing",
        "bypopularity" to "Popular Anime",
        "upcoming" to "Upcoming",
    )

    private suspend fun jikanGet(path: String, params: Map<String, String> = emptyMap()): String {
        return app.get(
            "$jikanApi$path",
            params = params,
            headers = mapOf("Referer" to "$mainUrl/"),
        ).text
    }

    private suspend fun providerGet(path: String): String {
        return app.get(
            "$providerApi${if (path.startsWith("/")) path else "/$path"}",
            headers = defaultHeaders,
        ).text
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = when (request.data) {
            "recent" -> "/seasons/now"
            else -> "/top/anime"
        }

        val params = when (request.data) {
            "recent" -> mapOf("page" to page.toString(), "limit" to "25")
            "airing" -> mapOf("page" to page.toString(), "limit" to "25", "filter" to "airing")
            "upcoming" -> mapOf("page" to page.toString(), "limit" to "25", "filter" to "upcoming")
            else -> mapOf("page" to page.toString(), "limit" to "25", "filter" to "bypopularity")
        }

        val response = parseJson<JikanListResponse>(jikanGet(path, params))
        val home = response.data?.mapNotNull { it.toSearchResponse(null) } ?: emptyList()
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = parseJson<JikanListResponse>(
            jikanGet(
                "/anime",
                mapOf(
                    "q" to query,
                    "page" to "1",
                    "limit" to "24",
                    "order_by" to "score",
                    "sort" to "desc",
                )
            )
        )
        return response.data?.mapNotNull { anime ->
            val providerInfo = runCatching {
                parseJson<ProviderEpisodeResponse>(providerGet("/anime/${anime.malId}/episodes?fast=true"))
            }.getOrNull()
            anime.toSearchResponse(providerInfo)
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val malId = extractMalId(url) ?: return null
        val detail = parseJson<JikanAnimeResponse>(jikanGet("/anime/$malId/full")).data ?: return null
        val providerInfo = runCatching {
            parseJson<ProviderEpisodeResponse>(providerGet("/anime/$malId/episodes?fast=true"))
        }.getOrNull()

        val title = detail.titleEnglish ?: detail.title ?: return null
        val tvType = detail.toTvType()
        val plot = detail.synopsis?.replace("<br>", "\n")
        val year = detail.year ?: detail.aired?.from?.take(4)?.toIntOrNull()
        val tags = buildList {
            addAll(detail.genres?.mapNotNull { it.name }.orEmpty())
            addAll(detail.explicitGenres?.mapNotNull { it.name }.orEmpty())
            addAll(detail.themes?.mapNotNull { it.name }.orEmpty())
            addAll(detail.demographics?.mapNotNull { it.name }.orEmpty())
        }.distinct()

        val showStatus = when (detail.status?.lowercase()) {
            "currently airing" -> ShowStatus.Ongoing
            "finished airing" -> ShowStatus.Completed
            else -> null
        }

        val episodesByLang = providerInfo?.toEpisodeLists(malId).orEmpty()
        val subEpisodes = episodesByLang[DubStatus.Subbed].orEmpty()
        val dubEpisodes = episodesByLang[DubStatus.Dubbed].orEmpty()

        return newAnimeLoadResponse(title, "$mainUrl/anime/$malId", tvType) {
            posterUrl = detail.images?.jpg?.largeImageUrl ?: detail.images?.jpg?.imageUrl
            backgroundPosterUrl = detail.images?.jpg?.largeImageUrl ?: detail.images?.jpg?.imageUrl
            this.plot = plot
            this.year = year
            this.tags = tags
            this.showStatus = showStatus
            this.rating = detail.score?.times(100)?.toInt()
            this.duration = detail.duration
            this.japName = detail.titleJapanese
            addDubStatus(
                dubExist = dubEpisodes.isNotEmpty(),
                subExist = subEpisodes.isNotEmpty(),
                dubEpisodes = dubEpisodes.size.takeIf { it > 0 },
                subEpisodes = subEpisodes.size.takeIf { it > 0 },
            )
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
        if (parts.size < 4) return false

        val provider = parts[0]
        val malId = parts[1]
        val audio = parts[2].lowercase()
        val providerEpisode = parts[3]

        val endpoint = "/watch/$provider/$malId/$audio/$provider-$providerEpisode"
        val response = parseJson<WatchResponse>(providerGet(endpoint))
        var found = false

        response.availableLanguages
            ?.filter { it.url?.isNotBlank() == true }
            ?.forEachIndexed { index, langInfo ->
                val playlist = langInfo.url ?: return@forEachIndexed
                callback(
                    newExtractorLink(
                        source = "$name ${provider.prettyName()}",
                        name = "$name ${provider.prettyName()} ${langInfo.label ?: langInfo.value ?: "Track ${index + 1}"}",
                        url = playlist,
                        type = if (playlist.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    ) {
                        referer = "$mainUrl/"
                        headers = defaultHeaders + mapOf("Referer" to "$mainUrl/")
                        quality = getQualityFromName(langInfo.label ?: langInfo.value ?: "auto")
                    }
                )
                found = true
            }

        response.streams
            ?.filter { it.isActive != false }
            ?.sortedByDescending { it.priority ?: 0 }
            ?.forEach { stream ->
                val streamUrl = stream.url ?: stream.embed ?: return@forEach
                val streamType = stream.type?.lowercase().orEmpty()
                val streamAudio = stream.audio?.lowercase() ?: audio
                if (streamAudio != audio) return@forEach
                val referer = stream.referer?.takeIf { it.isNotBlank() }
                    ?: stream.embed?.takeIf { it.isNotBlank() }
                    ?: "$mainUrl/"

                extractSubtitleFromUrl(streamUrl)?.let { subtitleUrl ->
                    subtitleCallback(SubtitleFile("English", subtitleUrl))
                }

                when {
                    streamType == "hls" || streamUrl.contains(".m3u8", true) -> {
                        callback(
                            newExtractorLink(
                                source = "$name - ${stream.server ?: provider.prettyName()}",
                                name = "$name - ${stream.server ?: provider.prettyName()} ${streamAudio.uppercase()}",
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.referer = referer
                                this.headers = defaultHeaders + mapOf("Referer" to referer)
                                quality = getQualityFromName("auto")
                            }
                        )
                        found = true
                    }
                    streamType == "embed" || stream.embed != null -> {
                        val loaded = loadExtractor(streamUrl, referer, subtitleCallback, callback)
                        if (!loaded) {
                            callback(
                                newExtractorLink(
                                    source = "$name - ${stream.server ?: provider.prettyName()}",
                                    name = "$name - ${stream.server ?: provider.prettyName()} ${streamAudio.uppercase()}",
                                    url = streamUrl,
                                    type = ExtractorLinkType.INFER_TYPE,
                                ) {
                                    this.referer = referer
                                    this.headers = defaultHeaders + mapOf("Referer" to referer)
                                }
                            )
                        }
                        found = true
                    }
                    else -> {
                        callback(
                            newExtractorLink(
                                source = "$name - ${stream.server ?: provider.prettyName()}",
                                name = "$name - ${stream.server ?: provider.prettyName()} ${streamAudio.uppercase()}",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = referer
                                this.headers = defaultHeaders + mapOf("Referer" to referer)
                                quality = getQualityFromName(streamUrl)
                            }
                        )
                        found = true
                    }
                }
            }

        return found
    }

    private fun ProviderEpisodeResponse.toEpisodeLists(malId: Int): Map<DubStatus, List<Episode>> {
        val providerOrder = listOf("anineko", "allmanga", "anidbapp", "animegg", "animepahe", "crunchyroll")

        fun build(providerKey: String, audio: String, block: ProviderBlock, items: List<ProviderEpisode>): List<Episode> {
            val providerMetaId = block.meta?.id?.takeIf { it.isNotBlank() }
            return items.sortedBy { it.number ?: Int.MAX_VALUE }.mapNotNull { episode ->
                val number = episode.number ?: return@mapNotNull null
                val watchId = providerMetaId ?: malId.toString()
                val providerEpisode = episode.id?.takeIf { it.isNotBlank() }
                    ?: number.toString()
                val data = listOf(providerKey, watchId, audio, providerEpisode).joinToString("|")
                newEpisode(data) {
                    this.name = episode.title?.takeIf { !it.startsWith("Episode ", true) } ?: "Episode $number"
                    this.episode = number
                    this.posterUrl = episode.image
                    this.description = episode.description
                }
            }
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        providerOrder.forEach { providerKey ->
            val block = getProviderBlock(providerKey) ?: return@forEach
            val subItems = block.episodes?.sub.orEmpty()
            if (subItems.isNotEmpty() && subEpisodes.isEmpty()) {
                subEpisodes += build(providerKey, "sub", block, subItems)
            }
            val dubItems = block.episodes?.dub.orEmpty()
            if (dubItems.isNotEmpty() && dubEpisodes.isEmpty()) {
                dubEpisodes += build(providerKey, "dub", block, dubItems)
            }
        }

        if (subEpisodes.isEmpty()) {
            val count = mappings?.episodes ?: 0
            if (count > 0) {
                subEpisodes += (1..count).map { number ->
                    newEpisode(listOf("anineko", malId.toString(), "sub", number.toString()).joinToString("|")) {
                        this.name = "Episode $number"
                        this.episode = number
                    }
                }
            }
        }

        return buildMap {
            if (subEpisodes.isNotEmpty()) put(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) put(DubStatus.Dubbed, dubEpisodes)
        }
    }

    private fun ProviderEpisodeResponse.getProviderBlock(provider: String): ProviderBlock? {
        return when (provider) {
            "animepahe" -> animepahe
            "allmanga" -> allmanga
            "anidbapp" -> anidbapp
            "animegg" -> animegg
            "anineko" -> anineko
            "crunchyroll" -> crunchyroll
            else -> null
        }
    }

    private fun extractMalId(url: String): Int? {
        return url.substringAfterLast("/").substringBefore("?").toIntOrNull()
            ?: Regex("""(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractSubtitleFromUrl(url: String): String? {
        val match = Regex("""(?:sub|caption)_1=([^&]+)""").find(url) ?: return null
        return runCatching { java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
    }

    private fun String.prettyName(): String = replaceFirstChar { it.uppercase() }

    private fun JikanAnime.toSearchResponse(providerInfo: ProviderEpisodeResponse?): SearchResponse? {
        val animeId = malId ?: return null
        val title = titleEnglish ?: title ?: return null
        val statuses = providerInfo?.toEpisodeLists(animeId).orEmpty()
        val subEpisodes = statuses[DubStatus.Subbed].orEmpty()
        val dubEpisodes = statuses[DubStatus.Dubbed].orEmpty()
        return newAnimeSearchResponse(title, "$mainUrl/anime/$animeId", toTvType()) {
            posterUrl = images?.jpg?.largeImageUrl ?: images?.jpg?.imageUrl
            addDubStatus(
                dubExist = dubEpisodes.isNotEmpty(),
                subExist = subEpisodes.isNotEmpty(),
                dubEpisodes = dubEpisodes.size.takeIf { it > 0 },
                subEpisodes = subEpisodes.size.takeIf { it > 0 },
            )
        }
    }

    private fun JikanAnime.toTvType(): TvType {
        return when (type?.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    data class JikanListResponse(
        val data: List<JikanAnime>? = null,
    )

    data class JikanAnimeResponse(
        val data: JikanAnime? = null,
    )

    data class JikanAnime(
        val mal_id: Int? = null,
        val title: String? = null,
        val title_english: String? = null,
        val title_japanese: String? = null,
        val synopsis: String? = null,
        val type: String? = null,
        val status: String? = null,
        val year: Int? = null,
        val episodes: Int? = null,
        val duration: String? = null,
        val score: Double? = null,
        val aired: AiredInfo? = null,
        val images: JikanImages? = null,
        val genres: List<MalNamedItem>? = null,
        val explicit_genres: List<MalNamedItem>? = null,
        val themes: List<MalNamedItem>? = null,
        val demographics: List<MalNamedItem>? = null,
    ) {
        val malId get() = mal_id
        val titleEnglish get() = title_english
        val titleJapanese get() = title_japanese
    }

    data class JikanImages(
        val jpg: JikanImageSet? = null,
    )

    data class JikanImageSet(
        val image_url: String? = null,
        val large_image_url: String? = null,
    ) {
        val imageUrl get() = image_url
        val largeImageUrl get() = large_image_url
    }

    data class AiredInfo(
        val from: String? = null,
    )

    data class MalNamedItem(
        val name: String? = null,
    )

    data class ProviderEpisodeResponse(
        val page: Int? = null,
        val type: String? = null,
        val mappings: ProviderMappings? = null,
        val animepahe: ProviderBlock? = null,
        val allmanga: ProviderBlock? = null,
        val anidbapp: ProviderBlock? = null,
        val animegg: ProviderBlock? = null,
        val anineko: ProviderBlock? = null,
        val crunchyroll: ProviderBlock? = null,
    )

    data class ProviderMappings(
        val episodes: Int? = null,
    )

    data class ProviderBlock(
        val meta: ProviderMeta? = null,
        val episodes: ProviderEpisodeMap? = null,
        val error: String? = null,
    )

    data class ProviderMeta(
        val id: String? = null,
        val title: String? = null,
    )

    data class ProviderEpisodeMap(
        val sub: List<ProviderEpisode>? = null,
        val dub: List<ProviderEpisode>? = null,
    )

    data class ProviderEpisode(
        val id: String? = null,
        val number: Int? = null,
        val title: String? = null,
        val duration: Int? = null,
        val audio: String? = null,
        val filler: Boolean? = null,
        val uncensored: Boolean? = null,
        val description: String? = null,
        val image: String? = null,
        val airDate: String? = null,
    )

    data class WatchResponse(
        val anilistId: Int? = null,
        val episode: Int? = null,
        val providerEpisode: Int? = null,
        val audio: String? = null,
        val streams: List<WatchStream>? = null,
        val availableLanguages: List<AvailableLanguage>? = null,
    )

    data class WatchStream(
        val url: String? = null,
        val type: String? = null,
        val embed: String? = null,
        val audio: String? = null,
        val server: String? = null,
        val priority: Int? = null,
        val referer: String? = null,
        val isActive: Boolean? = null,
    )

    data class AvailableLanguage(
        val value: String? = null,
        val label: String? = null,
        val url: String? = null,
        val isActive: Boolean? = null,
    )
}
