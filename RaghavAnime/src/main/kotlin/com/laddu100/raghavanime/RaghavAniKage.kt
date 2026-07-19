package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newAnimeSearchResponse
import java.net.URLEncoder

class RaghavAniKage : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiUrl = "https://anikage.cc/api/media/anime"
    private val proxyUrl = "https://prox.anicore.tv"
    private val apiHeaders = mapOf("Accept" to "application/json")
    private val proxyHeaders get() = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BrowseResponse(
        val count: Long = 0,
        val data: List<AnimeResult> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeResult(
        val slug: String = "",
        val anilistId: Int? = null,
        val title: AnimeTitle? = null,
        val coverImage: CoverImage? = null,
        val bannerImage: String? = null,
        val format: String? = null,
        val status: String? = null,
        val year: Int? = null,
        val totalEpisodes: Int? = null,
        val genres: List<String>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeTitle(
        val romaji: String? = null,
        val english: String? = null,
        val native: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CoverImage(
        val large: String? = null,
        val extraLarge: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetailResponse(val anime: AnimeDetail? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnimeDetail(
        val slug: String = "",
        val anilistId: Int? = null,
        val malId: Int? = null,
        val title: AnimeTitle? = null,
        val coverImage: CoverImage? = null,
        val bannerImage: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val status: String? = null,
        val format: String? = null,
        val seasonYear: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodesResponse(
        val total: Int = 0,
        val episodes: List<EpisodeInfo> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeInfo(
        val number: Int,
        val title: String? = null,
        val description: String? = null,
        val image: String? = null,
        val isFiller: Boolean = false
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServersResponse(
        val servers: List<ServerInfo> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerInfo(
        val id: String = "",
        val name: String? = null,
        val subTypes: List<String> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesResponse(
        val sources: List<SourceInfo> = emptyList(),
        val subtitles: List<SubtitleInfo>? = null,
        val embeds: List<EmbedInfo>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceInfo(
        val url: String = "",
        val quality: String? = null,
        val isM3U8: Boolean? = null,
        val embedUrl: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleInfo(
        val file: String = "",
        val label: String? = null,
        val embedUrl: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedInfo(
        val url: String? = null,
        val type: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaPlayResponse(val tracks: List<MegaPlayTrack>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MegaPlayTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("anikage") ?: mainUrl
        if (query.isBlank()) return emptyList()

        val url = "$apiUrl/browse?q=${URLEncoder.encode(query, "UTF-8")}&sort=popularity&page=1&limit=25&adult=true"
        val response = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = try {
            parseJson<BrowseResponse>(response)
        } catch (e: Exception) {
            return emptyList()
        }

        return parsed.data.mapNotNull { item ->
            val title = item.title?.english ?: item.title?.romaji ?: return@mapNotNull null
            val poster = item.coverImage?.extraLarge ?: item.coverImage?.large
            newAnimeSearchResponse(title, "$mainUrl/anime/${item.slug}", TvType.Anime) {
                this.posterUrl = poster
                this.year = item.year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("anikage") ?: mainUrl
        val slug = url.substringAfterLast("/")

        val detailResponse = try {
            app.get("$apiUrl/$slug", headers = apiHeaders).text
        } catch (e: Exception) {
            return null
        }

        val detail = try {
            parseJson<AnimeDetailResponse>(detailResponse).anime
        } catch (e: Exception) {
            return null
        } ?: return null

        val title = detail.title?.english ?: detail.title?.romaji ?: return null
        val poster = detail.coverImage?.extraLarge ?: detail.coverImage?.large
        val banner = detail.bannerImage
        val plot = detail.description?.replace(Regex("<[^>]+>"), "")
        val year = detail.seasonYear
        val tags = detail.genres?.filter { it.isNotBlank() } ?: emptyList()

        val showStatus = when (detail.status?.uppercase()) {
            "FINISHED" -> ShowStatus.Completed
            "RELEASING" -> ShowStatus.Ongoing
            else -> null
        }

        val tvType = when (detail.format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val episodesResponse = try {
            app.get("$apiUrl/$slug/episodes", headers = apiHeaders).text
        } catch (e: Exception) {
            return null
        }

        val episodes = try {
            parseJson<EpisodesResponse>(episodesResponse).episodes
        } catch (e: Exception) {
            return null
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (ep in episodes) {
            val epData = "$slug|${ep.number}|sub"
            val dubEpData = "$slug|${ep.number}|dub"

            subEpisodes.add(newEpisode(epData) {
                this.name = ep.title
                this.episode = ep.number
                this.posterUrl = ep.image
                this.description = ep.description
            })
            dubEpisodes.add(newEpisode(dubEpData) {
                this.name = ep.title
                this.episode = ep.number
                this.posterUrl = ep.image
                this.description = ep.description
            })
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
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
        val slug = parts[0]
        val epNum = parts[1]
        val type = parts[2]

        return fetchSources(slug, epNum, type, subtitleCallback, callback)
    }

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        title: String,
        jpTitle: String?,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val searchQueries = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        if (searchQueries.isEmpty()) {
            return false
        }

        var slug: String? = null
        for (query in searchQueries) {
            slug = findSlugByAnilistId(query, anilistId)
            if (slug != null) break
        }

        if (slug == null) {
            return false
        }

        val type = if (isDub) "dub" else "sub"
        return fetchSources(slug, episode.toString(), type, subtitleCallback, callback)
    }

    private suspend fun findSlugByAnilistId(query: String, anilistId: Int): String? {
        val url = "$apiUrl/browse?q=${URLEncoder.encode(query, "UTF-8")}&sort=popularity&page=1&limit=25&adult=true"
        val response = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            return null
        }

        val parsed = try {
            parseJson<BrowseResponse>(response)
        } catch (e: Exception) {
            return null
        }

        return parsed.data.firstOrNull { it.anilistId == anilistId }?.slug?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchSources(
        slug: String,
        epNum: String,
        type: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lang = if (type == "dub") "dub" else "sub"

        val serverIds = getServerIds(slug, epNum)
        if (serverIds.isEmpty()) {
            return false
        }


        var found = false
        for (serverId in serverIds) {
            try {
                val responseText = app.get(
                    "$apiUrl/$slug/episodes/$epNum/sources?lang=$lang&provider=$serverId",
                    headers = apiHeaders
                ).text

                val sourcesResponse = try {
                    parseJson<SourcesResponse>(responseText)
                } catch (e: Exception) {
                    continue
                }

                if (sourcesResponse.sources.isEmpty()) continue

                for (source in sourcesResponse.sources) {
                    val sourceUrl = source.url
                    if (sourceUrl.isBlank()) continue

                    val quality = source.quality ?: "auto"
                    val sourceType = source.type ?: ""
                    val isM3u8 = source.isM3U8 != false
                    val embedUrl = source.embedUrl

                    val subLabel = when {
                        sourceType.contains("soft", ignoreCase = true) -> "Soft Sub"
                        sourceType.contains("hard", ignoreCase = true) -> "Hardsub"
                        type == "dub" -> "Dub"
                        else -> "Sub"
                    }

                    val label = "AniKage ${serverId.replaceFirstChar { it.uppercase() }} ($quality $subLabel)"

                    val proxiedUrl = if (isM3u8) {
                        "$proxyUrl/m3u8/$sourceUrl"
                    } else {
                        "$proxyUrl/stream/$sourceUrl"
                    }

                    callback(newExtractorLink(
                        source = name,
                        name = label,
                        url = proxiedUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = proxyHeaders
                    })
                    found = true

                    if (embedUrl != null && embedUrl.contains("megaplay.buzz")) {
                        fetchMegaPlaySubtitles(embedUrl, subtitleCallback)
                    }
                }

                sourcesResponse.subtitles?.forEach { sub ->
                    if (sub.embedUrl != null && sub.embedUrl.contains("megaplay.buzz")) {
                        fetchMegaPlaySubtitles(sub.embedUrl, subtitleCallback)
                    }
                }
            } catch (e: Exception) {
            }
        }

        return found
    }

    private suspend fun getServerIds(slug: String, epNum: String): List<String> {
        val url = "$apiUrl/$slug/episodes/$epNum/servers"
        val response = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            return emptyList()
        }

        val parsed = try {
            parseJson<ServersResponse>(response)
        } catch (e: Exception) {
            return emptyList()
        }

        return parsed.servers.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
    }

    private suspend fun fetchMegaPlaySubtitles(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val id = Regex("""/stream/(?:s-\d+/)?(\w+)""").find(embedUrl)?.groupValues?.get(1)
                ?: embedUrl.substringAfterLast("/").substringBefore("/")

            val responseText = app.get(
                "https://megaplay.buzz/stream/getSources?id=$id",
                headers = mapOf(
                    "Referer" to "https://megaplay.buzz/",
                    "Origin" to "https://megaplay.buzz",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text

            val response = parseJson<MegaPlayResponse>(responseText)
            response.tracks?.forEach { track ->
                if (track.kind == "captions" || track.kind == "subtitles") {
                    val file = track.file ?: return@forEach
                    if (file.isNotBlank()) {
                        subtitleCallback.invoke(SubtitleFile(track.label ?: "English", file))
                    }
                }
            }
        } catch (e: Exception) {
        }
    }
}
