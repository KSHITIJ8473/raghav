package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class RaghavAniKage : MainAPI() {
    override var mainUrl = "https://anikage.cc"
    override var name = "AniKage"
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

    private fun proxyHeaders() = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl)

    private fun apiUrl(): String = "$mainUrl/api/media/anime"

    companion object {
        @Volatile
        private var cachedProxy: String? = null

        @Volatile
        private var cachedProxyAt = 0L

        private val runtimeCache = ConcurrentHashMap<String, Map<Int, Int>>()

        private suspend fun fallbackProxy(): String = FirebaseDomainHelper.getDomain("anikageproxy")
            ?: FirebaseDomainHelper.getDomain("akageproxy")
            ?: "https://og.bakayaro.live"

        private suspend fun proxyBase(siteUrl: String): String {
            if (System.currentTimeMillis() - cachedProxyAt < 10 * 60 * 1000L) {
                return cachedProxy ?: fallbackProxy()
            }
            val parsed = try {
                val html = app.get(siteUrl, timeout = 10L).text
                Regex(""""PUBLIC_PROXY_URL"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            }
            cachedProxyAt = System.currentTimeMillis()
            if (parsed != null) {
                cachedProxy = parsed
            }
            return cachedProxy ?: fallbackProxy()
        }
    }

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
        val runtime: Int? = null,
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
        val embedOptions: List<EmbedInfo>? = null,
        val mirrors: List<String>? = null,
        val stale: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SourceInfo(
        val url: String = "",
        val quality: String? = null,
        val label: String? = null,
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
        val server: String? = null,
        val key: String? = null,
        val label: String? = null
    )

    private data class HlsVariant(val url: String, val quality: Int?)

    private val variantResolutionRegex = Regex("""RESOLUTION=\d+x(\d+)""")
    private val variantNameRegex = Regex("""NAME="(\d{3,4})p?"""")
    private val dubPrefixRegex = Regex("^dub\\s+", RegexOption.IGNORE_CASE)

    private fun buildProxyUrl(path: String, proxy: String, type: String = "stream"): String {
        return when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("/m3u8/") || path.startsWith("/stream/") || path.startsWith("/hls/") -> "$proxy$path"
            path.startsWith("m3u8/") || path.startsWith("stream/") || path.startsWith("hls/") -> "$proxy/$path"
            else -> "$proxy/$type/$path"
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

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("anikage") ?: mainUrl
        if (query.isBlank()) return emptyList()

        val url = "${apiUrl()}/browse?q=${URLEncoder.encode(query, "UTF-8")}&sort=popularity&page=1&limit=25&adult=true"
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
            app.get("${apiUrl()}/$slug", headers = apiHeaders).text
        } catch (e: Exception) {
            return null
        }

        val detail = try {
            parseJson<AnimeDetailResponse>(detailResponse).anime
        } catch (e: Exception) {
            null
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
            return null
        }

        val episodes = try {
            parseJson<List<EpisodeInfo>>(episodesResponse)
        } catch (e: Exception) {
            return null
        }
        cacheRuntimes(slug, episodes)

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
        if (searchQueries.isEmpty()) return false

        var slug: String? = null
        for (query in searchQueries) {
            slug = findSlugByAnilistId(query, anilistId)
            if (slug != null) {
                break
            }
        }

        if (slug == null) return false

        val type = if (isDub) "dub" else "sub"
        return fetchSources(slug, episode.toString(), type, subtitleCallback, callback)
    }

    private suspend fun findSlugByAnilistId(query: String, anilistId: Int): String? {
        val url = "${apiUrl()}/browse?q=${URLEncoder.encode(query, "UTF-8")}&sort=popularity&page=1&limit=25&adult=true"
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

    private fun cacheRuntimes(slug: String, episodes: List<EpisodeInfo>) {
        val map = HashMap<Int, Int>()
        for (ep in episodes) {
            val runtime = ep.runtime?.takeIf { it > 0 } ?: continue
            if (ep.number > 0) map[ep.number] = runtime
        }
        if (map.isNotEmpty()) {
            if (runtimeCache.size > 40) runtimeCache.clear()
            runtimeCache[slug] = map
        }
    }

    private suspend fun episodeRuntime(slug: String, epNum: String): Int? {
        val number = epNum.toIntOrNull() ?: return null
        runtimeCache[slug]?.get(number)?.let { return it }
        val text = try {
            app.get("${apiUrl()}/$slug/episodes", headers = apiHeaders).text
        } catch (e: Exception) {
            return null
        }
        val episodes = try {
            parseJson<List<EpisodeInfo>>(text)
        } catch (e: Exception) {
            return null
        }
        cacheRuntimes(slug, episodes)
        return runtimeCache[slug]?.get(number)
    }

    private suspend fun getServerList(slug: String, epNum: String): List<ServerInfo> {
        val url = "${apiUrl()}/$slug/episodes/$epNum/servers"
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

        return parsed.servers.filter { it.id.isNotBlank() }
    }

    private suspend fun fetchSourcesResponse(
        slug: String,
        epNum: String,
        providerId: String,
        lang: String,
        serverId: String
    ): SourcesResponse? {
        val sourcesUrl = "${apiUrl()}/$slug/episodes/$epNum/sources?provider=$providerId&lang=$lang&server=$serverId"

        val responseText = try {
            app.get(sourcesUrl, headers = apiHeaders).text
        } catch (e: Exception) {
            return null
        }
        var parsed = parseSourcesResponse(responseText) ?: return null

        if (parsed.stale == true) {
            try {
                val freshText = app.get("$sourcesUrl&_=${System.currentTimeMillis()}", headers = apiHeaders).text
                parseSourcesResponse(freshText)?.let { parsed = it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        return parsed
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

        val proxy = proxyBase(mainUrl)
        val runtimeMin: Int?
        val servers: List<ServerInfo>
        coroutineScope {
            val runtimeJob = async(Dispatchers.IO) { episodeRuntime(slug, epNum) }
            val serversJob = async(Dispatchers.IO) { getServerList(slug, epNum) }
            runtimeMin = runtimeJob.await()
            servers = serversJob.await()
        }
        if (servers.isEmpty()) return false

        val found = AtomicBoolean(false)
        coroutineScope {
            servers.map { server ->
                async(Dispatchers.IO) {
                    try {
                        if (loadServer(slug, epNum, lang, server, proxy, runtimeMin, subtitleCallback, callback)) {
                            found.set(true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }.awaitAll()
        }

        return found.get()
    }

    private class PendingSource(
        val namePrefix: String,
        val url: String,
        val m3u8: Boolean,
        val qualityName: String?
    )

    private class PendingEmbed(val url: String, val label: String)

    private suspend fun loadServer(
        slug: String,
        epNum: String,
        lang: String,
        server: ServerInfo,
        proxy: String,
        runtimeMin: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverId = server.id
        if (server.subTypes.isNotEmpty() && !server.subTypes.contains(lang)) return false
        val providerId = server.providerId?.takeIf { it.isNotBlank() } ?: serverId

        val base = fetchSourcesResponse(slug, epNum, providerId, lang, serverId)
        val baseMirrors = base?.mirrors.orEmpty().filter { it.isNotBlank() }

        val responses = coroutineScope {
            val mirrorJobs = baseMirrors.map { mirror ->
                async(Dispatchers.IO) {
                    mirror to fetchSourcesResponse(slug, epNum, providerId, lang, mirror)
                }
            }
            (listOf(null to base) + mirrorJobs.awaitAll()).filter { it.second != null }
        }
        if (responses.isEmpty()) return false

        val found = AtomicBoolean(false)
        val seenSubs = LinkedHashSet<String>()
        val seenEmbedUrls = LinkedHashSet<String>()
        val seenLabels = LinkedHashSet<String>()
        val pendingSources = mutableListOf<PendingSource>()
        val pendingEmbeds = mutableListOf<PendingEmbed>()

        for ((mirror, parsed) in responses) {
            val response = parsed ?: continue

            for (sub in response.subtitles.orEmpty()) {
                if (sub.file.isBlank()) continue
                val label = sub.label?.takeIf { it.isNotBlank() } ?: lang
                val subUrl = buildProxyUrl(sub.file, proxy, "stream")
                if (seenSubs.add(subUrl)) {
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl) {
                        this.headers = proxyHeaders()
                    })
                }
            }

            val withSubs = response.subtitles.orEmpty().isNotEmpty()
            val serverSubType = if (lang == "sub") {
                if (withSubs) "Softsub" else "Hardsub"
            } else {
                "Dub"
            }
            val baseName = "AniKage ${serverId.replaceFirstChar { it.uppercase() }} $serverSubType".trim()

            for (src in response.sources) {
                val srcLabel = src.quality?.trim()
                    ?.replace(dubPrefixRegex, "")
                    ?.takeIf { it.isNotBlank() && it != "auto" }
                    ?: src.label?.takeIf { it.isNotBlank() }
                    ?: mirror
                if (srcLabel != null && !seenLabels.add(srcLabel)) continue

                val embedUrl = src.embedUrl?.takeIf { it.isNotBlank() }
                if (embedUrl != null && seenEmbedUrls.add(embedUrl)) {
                    val embedLabel = listOfNotNull(baseName, srcLabel?.replaceFirstChar { it.uppercase() })
                        .joinToString(" ")
                    pendingEmbeds.add(PendingEmbed(embedUrl, embedLabel))
                }

                if (src.url.isBlank()) continue

                val serverTag = src.server?.takeIf { it.isNotBlank() && it != serverId }
                    ?.replaceFirstChar { it.uppercase() }
                val namePrefix = listOfNotNull(
                    baseName,
                    serverTag,
                    srcLabel?.replaceFirstChar { it.uppercase() }
                ).joinToString(" ")

                val isM3u8 = src.isM3U8 == true
                pendingSources.add(
                    PendingSource(
                        namePrefix,
                        buildProxyUrl(src.url, proxy, if (isM3u8) "m3u8" else "stream"),
                        isM3u8,
                        src.quality
                    )
                )
            }

            val embedEntries = response.embeds.orEmpty().map { embed ->
                embed.url?.takeIf { it.isNotBlank() }?.let { url ->
                    PendingEmbed(
                        url,
                        "AniKage ${(embed.server ?: serverId).replaceFirstChar { it.uppercase() }} $serverSubType"
                    )
                }
            } + response.embedOptions.orEmpty().map { option ->
                option.url?.takeIf { it.isNotBlank() }?.let { url ->
                    val optionName = (option.label ?: option.key ?: serverId).replaceFirstChar { it.uppercase() }
                    PendingEmbed(url, "AniKage $optionName $serverSubType")
                }
            }
            for (entry in embedEntries.filterNotNull()) {
                if (seenEmbedUrls.add(entry.url)) {
                    pendingEmbeds.add(entry)
                }
            }
        }

        coroutineScope {
            val jobs = pendingSources.map { source ->
                async(Dispatchers.IO) {
                    try {
                        val emitted = if (source.m3u8) {
                            emitHlsSource(source.namePrefix, source.url, runtimeMin, callback)
                        } else {
                            emitVideoSource(source.namePrefix, source.url, source.qualityName, callback)
                        }
                        if (emitted) found.set(true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            } + pendingEmbeds.map { embed ->
                async(Dispatchers.IO) {
                    try {
                        if (RaghavEmbeds.resolveEmbed(embed.url, "$mainUrl/", embed.label, "AniKage", lang, subtitleCallback, callback)) {
                            found.set(true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
            jobs.awaitAll()
        }

        return found.get()
    }

    private fun parseVariants(masterUrl: String, masterText: String): List<HlsVariant> {
        val base = masterUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val out = mutableListOf<HlsVariant>()
        val lines = masterText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val quality = variantResolutionRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    ?: variantNameRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotEmpty()) {
                        out.add(HlsVariant(if (uri.startsWith("http")) uri else base + uri, quality))
                    }
                    i = j
                }
            }
            i++
        }
        return out.distinctBy { it.url }
    }

    private fun playlistDurationSeconds(body: String): Double? {
        if (!body.contains("#EXTM3U")) return null
        var total = 0.0
        for (line in body.lines()) {
            if (line.startsWith("#EXTINF:")) {
                line.substringAfter(":").trimEnd(',').toDoubleOrNull()?.let { total += it }
            }
        }
        return total
    }

    private suspend fun remotePlaylistDurationSeconds(url: String): Double? {
        return try {
            playlistDurationSeconds(app.get(url, headers = proxyHeaders(), timeout = 15L).text)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    // providers occasionally point at the wrong file, anything running far
    // shorter than the episode is different content and gets dropped
    private fun runtimeMatches(durationSeconds: Double?, runtimeMin: Int?): Boolean {
        if (durationSeconds == null || runtimeMin == null || runtimeMin <= 0) return true
        return durationSeconds >= runtimeMin * 60 * 0.55
    }

    private suspend fun emitHlsSource(
        namePrefix: String,
        url: String,
        runtimeMin: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val master = try {
            app.get(url, headers = proxyHeaders(), timeout = 15L).text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: return false
        if (!master.contains("#EXTM3U")) return false

        if (!master.contains("#EXT-X-STREAM-INF")) {
            if (!runtimeMatches(playlistDurationSeconds(master), runtimeMin)) return false
            callback.invoke(
                newExtractorLink(source = name, name = namePrefix, url = url, type = ExtractorLinkType.M3U8) {
                    this.headers = proxyHeaders()
                }
            )
            return true
        }

        // variant playlists are linked one by one because codec-less entries in
        // a master crash the ffmpeg renderer during track selection
        val variants = parseVariants(url, master)
        if (variants.isEmpty()) return false
        if (!runtimeMatches(remotePlaylistDurationSeconds(variants.first().url), runtimeMin)) return false

        for (variant in variants) {
            val suffix = variant.quality?.let { "${it}p" }
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = if (suffix == null) namePrefix else "$namePrefix $suffix",
                    url = variant.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    variant.quality?.let { this.quality = it }
                    this.headers = proxyHeaders()
                }
            )
        }
        return true
    }

    private suspend fun emitVideoSource(
        namePrefix: String,
        url: String,
        qualityName: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = namePrefix,
                url = url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = getQualityFromName(qualityName)
                this.headers = proxyHeaders()
            }
        )
        return true
    }
}
