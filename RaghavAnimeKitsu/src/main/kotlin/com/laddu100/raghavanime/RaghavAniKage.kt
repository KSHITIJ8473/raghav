package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class RaghavAniKage : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val proxyUrl = "https://gg.akage.lol"

    private val apiHeaders = mapOf("Accept" to "application/json")
    private val proxyHeaders get() = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)

    private fun apiUrl(): String = "$mainUrl/api/media/anime"

    // region JSON models

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
        val type: String? = null,
        val seasonYear: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EpisodeInfo(
        val number: Int = 0,
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
        val providerId: String? = null,
        val subTypes: List<String> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourcesResponse(
        val sources: List<SourceInfo> = emptyList(),
        val subtitles: List<SubtitleInfo>? = null,
        val embeds: List<EmbedInfo>? = null,
        val stale: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceInfo(
        val url: String = "",
        val quality: String? = null,
        val isM3U8: Boolean? = null,
        val embedUrl: String? = null,
        val type: String? = null,
        val server: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubtitleInfo(
        val file: String = "",
        val label: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbedInfo(
        val url: String? = null,
        val type: String? = null,
        val server: String? = null
    )

    // endregion

    // region URL helpers

    private fun buildProxyUrl(path: String, type: String = "stream"): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/m3u8/") || path.startsWith("/stream/") || path.startsWith("/hls/") -> "$proxyUrl$path"
            path.startsWith("m3u8/") || path.startsWith("stream/") || path.startsWith("hls/") -> "$proxyUrl/$path"
            else -> "$proxyUrl/$type/$path"
        }
    }

    private fun getQualityFromName(quality: String?): Int {
        return when {
            quality?.contains("1080") == true -> Qualities.P1080.value
            quality?.contains("720") == true -> Qualities.P720.value
            quality?.contains("480") == true -> Qualities.P480.value
            quality?.contains("360") == true -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }

    // endregion

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RaghavAnime", "[AniKage] search: q='${query.take(40)}'")
        mainUrl = FirebaseDomainHelper.getDomain("anikage") ?: mainUrl
        if (query.isBlank()) return emptyList()

        val url = "${apiUrl()}/browse?q=${URLEncoder.encode(query, "UTF-8")}&sort=popularity&page=1&limit=25&adult=true"
        val response = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] search fetch failed: ${e.message}")
            return emptyList()
        }

        val parsed = try {
            parseJson<BrowseResponse>(response)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] search parse failed: ${e.message}")
            return emptyList()
        }

        Log.d("RaghavAnime", "[AniKage] search: ${parsed.data.size} results")
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
        Log.d("RaghavAnime", "[AniKage] load: slug=$slug")

        val detailResponse = try {
            app.get("${apiUrl()}/$slug", headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] load detail fetch failed: ${e.message}")
            return null
        }

        val detail = try {
            parseJson<AnimeDetailResponse>(detailResponse).anime
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] load detail parse failed: ${e.message}")
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

        val fmt = detail.format?.uppercase()
        val tvType = when {
            fmt == "MOVIE" || detail.type?.contains("movie", ignoreCase = true) == true -> TvType.AnimeMovie
            fmt == "OVA" || fmt == "ONA" || fmt == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        val episodesResponse = try {
            app.get("${apiUrl()}/$slug/episodes", headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] episodes fetch failed: ${e.message}")
            return null
        }

        // The API returns a plain JSON array of episodes
        val episodes = try {
            parseJson<List<EpisodeInfo>>(episodesResponse)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] episodes parse failed: ${e.message}")
            return null
        }

        Log.d("RaghavAnime", "[AniKage] load: ${episodes.size} episodes")
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

        Log.d("RaghavAnime", "[AniKage] load ok: ${subEpisodes.size} sub, ${dubEpisodes.size} dub episodes")
        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            detail.anilistId?.let { addAniListId(it) }
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

        Log.d("RaghavAnime", "[AniKage] loadLinks: slug=$slug ep=$epNum type=$type")
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
        Log.d("RaghavAnime", "[AniKage] loadLinksByAnilistId: anilist=$anilistId ep=$episode dub=$isDub title='${title.take(40)}'")
        val searchQueries = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        if (searchQueries.isEmpty()) {
            Log.w("RaghavAnime", "[AniKage] no search queries for anilist=$anilistId")
            return false
        }

        var slug: String? = null
        for (query in searchQueries) {
            slug = findSlugByAnilistId(query, anilistId)
            if (slug != null) {
                break
            }
        }

        if (slug == null) {
            Log.w("RaghavAnime", "[AniKage] no slug match for anilist=$anilistId")
            return false
        }

        Log.d("RaghavAnime", "[AniKage] anilist=$anilistId matched slug=$slug")
        val type = if (isDub) "dub" else "sub"
        return fetchSources(slug, episode.toString(), type, subtitleCallback, callback)
    }

    private suspend fun findSlugByAnilistId(query: String, anilistId: Int): String? {
        Log.d("RaghavAnime", "[AniKage] findSlug: query='${query.take(40)}' anilist=$anilistId")
        val url = "${apiUrl()}/browse?q=${URLEncoder.encode(query, "UTF-8")}&sort=popularity&page=1&limit=25&adult=true"
        val response = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] browse failed: ${e.message}")
            return null
        }

        val parsed = try {
            parseJson<BrowseResponse>(response)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] browse parse failed: ${e.message}")
            return null
        }

        val match = parsed.data.firstOrNull { it.anilistId == anilistId }
        if (match == null) {
            Log.d("RaghavAnime", "[AniKage] no anilist match: ${parsed.data.size} results for '${query.take(40)}'")
        }
        return match?.slug?.takeIf { it.isNotBlank() }
    }

    private suspend fun getServerList(slug: String, epNum: String): List<ServerInfo> {
        val url = "${apiUrl()}/$slug/episodes/$epNum/servers"
        val response = try {
            app.get(url, headers = apiHeaders).text
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] servers fetch failed for ep=$epNum: ${e.message}")
            return emptyList()
        }

        val parsed = try {
            parseJson<ServersResponse>(response)
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniKage] servers parse failed for ep=$epNum: ${e.message}")
            return emptyList()
        }

        Log.d(
            "RaghavAnime",
            "[AniKage] getServerList: ${parsed.servers.joinToString { s ->
                s.id + (if (s.subTypes.isEmpty()) "" else "[${s.subTypes.joinToString("+")}]")
            }}"
        )
        return parsed.servers.filter { it.id.isNotBlank() }
    }

    private fun parseSourcesResponse(text: String): SourcesResponse? {
        return try {
            parseJson<SourcesResponse>(text)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSources(
        slug: String,
        epNum: String,
        type: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        mainUrl = FirebaseDomainHelper.getDomain("anikage") ?: mainUrl
        val lang = if (type == "dub") "dub" else "sub"
        Log.d("RaghavAnime", "[AniKage] fetchSources: slug=$slug ep=$epNum lang=$lang")

        val servers = getServerList(slug, epNum)
        if (servers.isEmpty()) {
            Log.d("RaghavAnime", "[AniKage] no servers for slug=$slug ep=$epNum")
            return false
        }

        var found = false
        for (server in servers) {
            val serverId = server.id
            if (serverId.isBlank()) continue

            // The API enforces subTypes server-side anyway; skipping early saves a request
            if (server.subTypes.isNotEmpty() && !server.subTypes.contains(lang)) {
                Log.d("RaghavAnime", "[AniKage] skip server=$serverId: lang '$lang' not in subTypes=${server.subTypes.joinToString("+")}")
                continue
            }

            val providerId = server.providerId?.takeIf { it.isNotBlank() } ?: serverId
            try {
                val sourcesUrl = "${apiUrl()}/$slug/episodes/$epNum/sources?provider=$providerId&lang=$lang&server=$serverId"
                Log.d("RaghavAnime", "[AniKage] fetching sources: server=$serverId provider=$providerId")

                val responseText = app.get(sourcesUrl, headers = apiHeaders).text
                var parsed = parseSourcesResponse(responseText)
                if (parsed == null) {
                    Log.e("RaghavAnime", "[AniKage] sources parse failed for server=$serverId")
                    continue
                }

                // megg/dib can serve stale cached tokens that 401 on the proxy;
                // a cache-busted re-request rotates them
                if (parsed.stale == true) {
                    Log.d("RaghavAnime", "[AniKage] server=$serverId stale cache, rotating tokens")
                    try {
                        val freshText = app.get("$sourcesUrl&_=${System.currentTimeMillis()}", headers = apiHeaders).text
                        parseSourcesResponse(freshText)?.let { parsed = it }
                    } catch (e: Exception) {
                        Log.d("RaghavAnime", "[AniKage] token rotation failed for server=$serverId: ${e.message}")
                    }
                }

                val subtitles = parsed.subtitles.orEmpty()
                val seenSubs = LinkedHashSet<String>()
                for (sub in subtitles) {
                    if (sub.file.isBlank()) continue
                    val label = sub.label?.takeIf { it.isNotBlank() } ?: lang
                    val subUrl = buildProxyUrl(sub.file, "stream")
                    if (seenSubs.add(subUrl)) {
                        Log.d("RaghavAnime", "[AniKage] subtitle: $label ${subUrl.take(80)}")
                        subtitleCallback.invoke(newSubtitleFile(label, subUrl) {
                            this.headers = proxyHeaders
                        })
                    }
                }

                val subType = if (lang == "sub") {
                    if (subtitles.isNotEmpty()) "Softsub" else "Hardsub"
                } else {
                    "Dub"
                }
                val baseName = "AniKage ${serverId.replaceFirstChar { it.uppercase() }} $subType".trim()

                val usedEmbedUrls = LinkedHashSet<String>()
                for (src in parsed.sources) {
                    if (src.url.isBlank() && src.embedUrl.isNullOrBlank()) continue

                    val embedUrl = src.embedUrl?.takeIf { it.isNotBlank() }
                    if (embedUrl != null && usedEmbedUrls.add(embedUrl)) {
                        try {
                            Log.d("RaghavAnime", "[AniKage] embed via loadExtractor: ${embedUrl.take(100)}")
                            if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) found = true
                        } catch (e: Exception) {
                            Log.e("RaghavAnime", "[AniKage] embed failed for server=$serverId: ${e.message}")
                        }
                    }

                    if (src.url.isNotBlank()) {
                        val isM3u8 = src.isM3U8 == true
                        val videoUrl = buildProxyUrl(src.url, if (isM3u8) "m3u8" else "stream")
                        val qualityClean = src.quality?.trim()
                            ?.replace(Regex("^dub\\s+", RegexOption.IGNORE_CASE), "")
                            ?.takeIf { it.isNotBlank() }
                        val serverTag = src.server?.takeIf { it.isNotBlank() && it != serverId }
                            ?.replaceFirstChar { it.uppercase() }
                        val nameStr = listOfNotNull(
                            baseName,
                            serverTag,
                            qualityClean?.replaceFirstChar { it.uppercase() }
                        ).joinToString(" ")

                        Log.d("RaghavAnime", "[AniKage] link: $nameStr url=${videoUrl.take(100)}")
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = nameStr,
                                url = videoUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromName(src.quality)
                                this.headers = proxyHeaders
                            }
                        )
                        found = true
                    }
                }

                for (embed in parsed.embeds.orEmpty()) {
                    val embedUrl = embed.url?.takeIf { it.isNotBlank() } ?: continue
                    if (usedEmbedUrls.add(embedUrl)) {
                        try {
                            Log.d("RaghavAnime", "[AniKage] embeds[] via loadExtractor: ${embedUrl.take(100)}")
                            if (loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)) found = true
                        } catch (e: Exception) {
                            Log.e("RaghavAnime", "[AniKage] embeds[] failed for server=$serverId: ${e.message}")
                        }
                    }
                }

                Log.d(
                    "RaghavAnime",
                    "[AniKage] server=$serverId done: ${parsed.sources.size} sources, ${subtitles.size} subtitles, ${parsed.embeds?.size ?: 0} embeds"
                )
            } catch (e: Exception) {
                Log.e("RaghavAnime", "[AniKage] server=$serverId sources failed: ${e.message}")
            }
        }

        Log.d("RaghavAnime", "[AniKage] fetchSources done: found=$found")
        return found
    }
}
