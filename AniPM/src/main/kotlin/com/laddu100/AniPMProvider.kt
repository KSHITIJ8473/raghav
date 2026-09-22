package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class AniPMProvider : MainAPI() {
    override var mainUrl = AniPMApi.MAIN_URL
    override var name = "AniPM"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private companion object {
        const val TAG = "AniPM"
        const val SETTLAR_REFERER = "https://embed.settlar.io/"
        const val MEGAPLAY_REFERER = "https://megaplay.buzz/"

        val genericEpisodeTitle = Regex("""^Episode \d+(\.\d+)?$""")
    }

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "popular" to "Popular",
        "score" to "Top Rated",
        "latest" to "Latest Episodes",
        "movies" to "Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.data) {
            "latest" -> latestPage(page, request.name)
            "movies" -> browsePage("popular", page, "Movie", request.name)
            "trending", "popular", "score" -> browsePage(request.data, page, null, request.name)
            else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private suspend fun browsePage(
        sort: String,
        page: Int,
        format: String?,
        sectionName: String
    ): HomePageResponse {
        val res = AniPMApi.browse(sort, page, format)
        val list = res?.items.orEmpty().mapNotNull { titleResponse(it) }
        return newHomePageResponse(sectionName, list, hasNext = res?.hasNextPage == true)
    }

    private suspend fun latestPage(page: Int, sectionName: String): HomePageResponse {
        val res = AniPMApi.latestEpisodes(page)
        val list = res?.items.orEmpty().mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newAnimeSearchResponse(title, "$mainUrl|$id", TvType.Anime) {
                posterUrl = AniPMApi.absolute(item.poster)
                addDubStatus(dubExist = item.dub == true, subExist = item.sub == true)
            }
        }
        return newHomePageResponse(sectionName, list, hasNext = res?.hasNextPage == true)
    }

    private fun titleResponse(item: AniPMTitle): SearchResponse? {
        val id = item.id ?: return null
        val title = item.title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(title, "$mainUrl|$id", TvType.Anime) {
            posterUrl = AniPMApi.absolute(item.poster)
            year = item.year
            addDubStatus(
                dubExist = (item.dubCount ?: 0) > 0,
                subExist = (item.subCount ?: 0) > 0
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return AniPMApi.search(query).mapNotNull { titleResponse(it) }
    }

    private fun expandRanges(ranges: List<List<Int>>?): Set<Int> {
        if (ranges.isNullOrEmpty()) return emptySet()
        val out = mutableSetOf<Int>()
        for (range in ranges) {
            if (range.isEmpty()) continue
            val start = range[0]
            val end = range.getOrElse(1) { start }
            if (start > 0 && end >= start) out.addAll(start..end)
        }
        return out
    }

    private fun parseDuration(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val hours = Regex("(\\d+)\\s*hr").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*min").find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hours > 0 || minutes > 0) return hours * 60 + minutes
        return value.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toInt()
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("|").trim().toIntOrNull() ?: return null
        val series = AniPMApi.series(id) ?: return null
        val title = series.title?.takeIf { it.isNotBlank() } ?: return null

        val filler = AniPMApi.filler(series.anilistId, title)
        val fillerNumbers = expandRanges(filler?.filler)
        val mixedNumbers = expandRanges(filler?.mixed)
        val packages = AniPMApi.packages(series.anilistId)

        fun episodeData(number: Int, dub: Boolean): String {
            val channel = if (dub) "dub" else "sub"
            val hard = packages?.episodes?.get(number.toString())
                ?.let { if (dub) it.dubhard == true else it.subhard == true } == true
            return "$mainUrl|$id|$number|$channel|${if (hard) "hs" else ""}" +
                "|${series.anilistId.orEmpty()}|${series.malId.orEmpty()}"
        }

        fun episodeList(dub: Boolean): List<Episode> {
            return series.episodes.orEmpty().mapNotNull { ep ->
                val number = ep.number ?: return@mapNotNull null
                if (number < 1) return@mapNotNull null
                if (if (dub) ep.dub != true else ep.sub != true) return@mapNotNull null
                val mark = when (number) {
                    in fillerNumbers -> " (Filler)"
                    in mixedNumbers -> " (Mixed)"
                    else -> ""
                }
                val realName = ep.title?.takeIf { it.isNotBlank() && !genericEpisodeTitle.matches(it) }
                newEpisode(episodeData(number, dub)) {
                    this.name = realName?.let { "$it$mark" } ?: "Episode $number$mark"
                    this.episode = number
                    this.description = ep.description?.takeIf { it.isNotBlank() }
                    this.posterUrl = AniPMApi.absolute(ep.thumbnail)
                }
            }
        }

        val subEpisodes = episodeList(dub = false)
        val dubEpisodes = episodeList(dub = true)

        val format = series.type?.lowercase()
        val tvType = when {
            format == "movie" && dubEpisodes.isNotEmpty() -> TvType.Anime
            format == "movie" -> TvType.AnimeMovie
            format == "ova" || format == "ona" || format == "special" || format == "music" -> TvType.OVA
            else -> TvType.Anime
        }

        val statusLower = series.status?.lowercase()
        val showStatus = when {
            statusLower == null -> null
            statusLower.startsWith("releasing") || statusLower.startsWith("ongoing") ||
                statusLower.startsWith("currently") -> ShowStatus.Ongoing
            statusLower.startsWith("finished") || statusLower.startsWith("completed") -> ShowStatus.Completed
            else -> null
        }

        Log.d(TAG, "load $title: ${subEpisodes.size} sub / ${dubEpisodes.size} dub episodes")

        return newAnimeLoadResponse(title, url, tvType) {
            posterUrl = AniPMApi.absolute(series.poster)
            backgroundPosterUrl = AniPMApi.absolute(series.banner)
            year = series.year
            plot = series.synopsis?.replace(Regex("<[^>]*>"), "")
            tags = series.genres.orEmpty()
            series.score?.takeIf { it > 0 }?.let { score = Score.from10(it.toString()) }
            showStatus?.let { this.showStatus = it }
            duration = parseDuration(series.duration)
            contentRating = series.rating?.takeIf { it.isNotBlank() }
            series.anilistId?.toIntOrNull()?.let { addAniListId(it) }
            series.malId?.toIntOrNull()?.let { addMalId(it) }
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
        val seriesId = parts[1].toIntOrNull() ?: return false
        val episode = parts[2].toIntOrNull() ?: return false
        val channel = if (parts[3] == "dub") "dub" else "sub"
        val hardAvailable = parts.getOrNull(4) == "hs"
        val anilistId = parts.getOrNull(5).orEmpty()
        val malId = parts.getOrNull(6).orEmpty()

        val bootstrap = AniPMApi.bootstrap(seriesId, episode, channel)
        val selection = bootstrap?.settlarSelection?.takeIf { it.isNotBlank() }

        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubLabels = ConcurrentHashMap.newKeySet<String>()

        val tasks = mutableListOf<suspend () -> Boolean>()
        tasks.add {
            emitSettlar(
                selection, episode, channel, name,
                seenLinks, seenSubUrls, seenSubLabels,
                subtitleCallback, callback
            )
        }
        // burned in subtitle channel, only a handful of titles carry it
        if (hardAvailable) {
            tasks.add {
                emitSettlar(
                    selection, episode, "${channel}hard", "$name Hardsub",
                    seenLinks, seenSubUrls, seenSubLabels,
                    subtitleCallback, callback
                )
            }
        }
        tasks.add {
            emitBackup(
                bootstrap?.backupEmbed, anilistId, malId, episode, channel,
                seenLinks, seenSubUrls, seenSubLabels,
                subtitleCallback, callback
            )
        }

        val results = coroutineScope {
            tasks.map { async { it() } }.awaitAll()
        }
        return results.any { it }
    }

    private suspend fun emitSubtitle(
        label: String,
        url: String,
        headers: Map<String, String>,
        seenUrls: MutableSet<String>,
        seenLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!url.startsWith("http")) return
        if (!seenUrls.add(url)) return
        if (!seenLabels.add(label.trim().lowercase())) return
        try {
            subtitleCallback.invoke(newSubtitleFile(label, url) {
                this.headers = headers
            })
        } catch (e: Exception) {
            Log.d(TAG, "subtitle emit failed: ${e.message}")
        }
    }

    private suspend fun emitSettlar(
        selection: String?,
        episode: Int,
        channel: String,
        label: String,
        seenLinks: MutableSet<String>,
        seenSubUrls: MutableSet<String>,
        seenSubLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (selection == null) {
            Log.d(TAG, "no settlar selection for ep$episode $channel")
            return false
        }

        val embedUrl = AniPMApi.settlarSession(selection, episode, channel) ?: return false
        val stream = AniPMApi.settlarResolve(embedUrl) ?: return false
        val master = stream.source?.takeIf { it.startsWith("http") } ?: return false

        val playHeaders = mapOf(
            "User-Agent" to AniPMApi.USER_AGENT,
            "Referer" to SETTLAR_REFERER
        )

        stream.subtitles.orEmpty().forEach { sub ->
            emitSubtitle(
                sub.label ?: "Subtitle",
                sub.url.orEmpty(),
                playHeaders, seenSubUrls, seenSubLabels, subtitleCallback
            )
        }

        if (seenLinks.add(master)) {
            callback.invoke(
                newExtractorLink(name, label, master, type = ExtractorLinkType.M3U8) {
                    referer = SETTLAR_REFERER
                    headers = playHeaders
                }
            )
        }
        return true
    }

    private suspend fun emitBackup(
        backupEmbed: AniPMBackupEmbed?,
        anilistId: String,
        malId: String,
        episode: Int,
        channel: String,
        seenLinks: MutableSet<String>,
        seenSubUrls: MutableSet<String>,
        seenSubLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = mutableListOf<String>()
        if (backupEmbed?.available == true) {
            backupEmbed.url?.takeIf { it.startsWith("http") }?.let { candidates.add(it) }
        }
        // megaplay also serves ani and mal style addresses when the site lists no backup
        if (anilistId.isNotBlank()) {
            candidates.add("https://megaplay.buzz/stream/ani/$anilistId/$episode/$channel")
        }
        if (malId.isNotBlank()) {
            candidates.add("https://megaplay.buzz/stream/mal/$malId/$episode/$channel")
        }

        val playHeaders = mapOf(
            "User-Agent" to AniPMApi.USER_AGENT,
            "Referer" to MEGAPLAY_REFERER
        )

        for (embedUrl in candidates) {
            val stream = MegaPlayBackup.resolveStream(embedUrl, "$mainUrl/") ?: continue
            for ((label, url) in stream.subtitles) {
                emitSubtitle(label, url, playHeaders, seenSubUrls, seenSubLabels, subtitleCallback)
            }
            MegaPlayBackup.emitVariantLinks(
                name, "MegaPlay", stream.m3u8, MEGAPLAY_REFERER, playHeaders, seenLinks, callback
            )
            return true
        }
        Log.d(TAG, "megaplay resolve failed for ep$episode $channel")
        return false
    }
}
