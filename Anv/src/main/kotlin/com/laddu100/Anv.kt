package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.Score
import com.raghav.donation.DonationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnvEpData(
    val id: Int? = null,
    val malId: Int? = null,
    val slug: String? = null,
    val episode: Int? = null,
    val variant: String? = null
)

class Anv : MainAPI() {
    override var mainUrl = AnvApi.BASE
    override var name = "Anv"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"

    override val mainPage = mainPageOf(
        "TRENDING" to "Trending",
        "POPULAR" to "Popular",
        "RECENT" to "Latest Episodes"
    )

    private fun mediaTitle(media: AnvMedia): String? {
        return media.title?.english ?: media.title?.romaji ?: media.title?.native
    }

    private fun posterOf(media: AnvMedia): String? {
        return media.coverImage?.extraLarge ?: media.coverImage?.large
    }

    private fun typeOf(format: String?): TvType = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun card(media: AnvMedia): SearchResponse? {
        if (media.id == null || media.slug.isNullOrBlank()) return null
        if (media.isAdult == true) return null
        val title = mediaTitle(media) ?: return null
        val type = typeOf(media.format)
        return newAnimeSearchResponse(title, "$mainUrl/anime/${media.slug}", type) {
            posterUrl = posterOf(media)
            year = media.seasonYear
            media.averageScore?.let { score = Score.from10(it / 10.0) }
            addDubStatus(dubExist = true, subExist = true, dubEpisodes = media.episodes, subEpisodes = media.episodes)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val home = mutableListOf<SearchResponse>()
        var hasNext = false
        when (request.data) {
            "RECENT" -> {
                // the endpoint hands out one page only
                AnvApi.recentEpisodes()?.schedules?.forEach { sched ->
                    sched.media?.let { card(it) }?.let(home::add)
                }
            }
            "TRENDING" -> {
                val resp = AnvApi.catalog("PopularAnime", mapOf("page" to page, "sort" to listOf("TRENDING_DESC")))
                val pageData = resp?.data?.page
                pageData?.media?.forEach { card(it)?.let { home.add(it) } }
                hasNext = pageData?.pageInfo?.hasNextPage == true
            }
            else -> {
                val resp = AnvApi.catalog(
                    "BrowseAnime",
                    mapOf("page" to page, "perPage" to 20, "sort" to listOf("POPULARITY_DESC"))
                )
                val pageData = resp?.data?.page
                pageData?.media?.forEach { card(it)?.let { home.add(it) } }
                hasNext = pageData?.pageInfo?.hasNextPage == true
            }
        }
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.isBlank()) return null
        val resp = AnvApi.catalog(
            "BrowseAnime",
            mapOf("page" to 1, "perPage" to 28, "sort" to listOf("POPULARITY_DESC"), "search" to query)
        )
        val media = resp?.data?.page?.media ?: return emptyList()
        return media.mapNotNull { card(it) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("$mainUrl/anime/", "").substringBefore("?").trim('/')
        if (slug.isBlank()) return null
        val media = AnvApi.detail(slug) ?: return null
        val id = media.id ?: return null
        val title = mediaTitle(media) ?: return null

        val isMovie = media.format == "MOVIE"
        val plot = media.description?.replace(Regex("<[^>]*>"), "")?.trim()
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }
        val recs = media.recommendations?.nodes.orEmpty().mapNotNull { node ->
            val m = node.mediaRecommendation ?: return@mapNotNull null
            if (m.id == null || m.slug.isNullOrBlank() || m.isAdult == true) return@mapNotNull null
            val rTitle = m.title?.english ?: m.title?.romaji ?: m.title?.native ?: return@mapNotNull null
            newAnimeSearchResponse(rTitle, "$mainUrl/anime/${m.slug}", typeOf(m.format)) {
                posterUrl = m.coverImage?.extraLarge ?: m.coverImage?.large
                year = m.seasonYear
            }
        }

        // one probe call tells whether the title carries a dub track at all,
        // the episode lists are built from the answer
        val hasDub = AnvApi.episode(id, media.idMal, 1, "$mainUrl/anime/$slug-episode-1")
            ?.variants
            ?.any { it.id == "dub" && !it.sources.isNullOrEmpty() } == true

        if (isMovie) {
            val data = AnvEpData(id, media.idMal, slug, 1, "both").toJson()
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
                posterUrl = posterOf(media)
                backgroundPosterUrl = media.bannerImage
                year = media.seasonYear
                this.plot = plot
                duration = media.duration
                media.averageScore?.let { score = Score.from10(it / 10.0) }
                tags = media.genres.orEmpty()
                recommendations = recs
                media.idMal?.let { addMalId(it) }
            }
        }

        val streamEps = media.streamingEpisodes.orEmpty()
        val aired = media.nextAiringEpisode?.episode?.let { it - 1 } ?: 0
        // the site lists what it can actually play, the planned count is only
        // a fallback for titles without a streamed episode list
        val total = if (streamEps.isEmpty()) {
            maxOf(media.episodes ?: 0, aired)
        } else {
            maxOf(streamEps.size, aired)
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (n in 1..total) {
            val streamEp = streamEps.getOrNull(n - 1)
            // every entry is "Episode N - name", the ones without a real name
            // repeat the number on both sides of the dash
            val epName = streamEp?.title
                ?.substringAfter("—", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Episode $n"
            val thumb = streamEp?.thumbnail
            subEps.add(
                newEpisode(AnvEpData(id, media.idMal, slug, n, "sub").toJson()) {
                    name = epName
                    episode = n
                    posterUrl = thumb
                }
            )
            if (hasDub) {
                dubEps.add(
                    newEpisode(AnvEpData(id, media.idMal, slug, n, "dub").toJson()) {
                        name = epName
                        episode = n
                        posterUrl = thumb
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, typeOf(media.format)) {
            posterUrl = posterOf(media)
            backgroundPosterUrl = media.bannerImage
            year = media.seasonYear
            this.plot = plot
            duration = media.duration
            media.averageScore?.let { score = Score.from10(it / 10.0) }
            tags = media.genres.orEmpty()
            this.showStatus = showStatus
            recommendations = recs
            media.idMal?.let { addMalId(it) }
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ep = try {
            parseJson<AnvEpData>(data)
        } catch (_: Exception) {
            return false
        }
        val n = ep.episode ?: return false
        val id = ep.id ?: return false
        val slug = ep.slug ?: return false
        val pageUrl = "$mainUrl/anime/$slug-episode-$n"

        val resp = AnvApi.episode(id, ep.malId, n, pageUrl) ?: return false
        if (resp.status != "ready") return false

        val wanted = when (ep.variant) {
            "dub" -> listOf("dub")
            "both" -> listOf("sub", "dub")
            else -> listOf("sub")
        }

        val cookie = AnvSession.current()
        val reqHeaders = AnvApi.plainHeaders() + buildMap<String, String> {
            if (cookie != null) put("Cookie", cookie)
            put("Referer", pageUrl)
        }

        class Pending(val variantLabel: String, val label: String, val url: String, val tracks: List<AnvTrack>)

        val pending = mutableListOf<Pending>()
        for (variant in resp.variants.orEmpty()) {
            val vid = variant.id ?: continue
            if (vid !in wanted) continue
            val variantLabel = if (vid == "dub") "Dub" else "Sub"
            for (source in variant.sources.orEmpty()) {
                val url = AnvApi.absolute(source.url) ?: continue
                val label = source.label?.trim() ?: continue
                pending.add(Pending(variantLabel, label, url, source.tracks.orEmpty()))
            }
        }

        val qualities = coroutineScope {
            pending.map { p -> async { AnvApi.masterQuality(p.url, pageUrl) } }.awaitAll()
        }

        val seenSubs = mutableSetOf<String>()
        var found = false
        pending.forEachIndexed { i, p ->
            callback(
                newExtractorLink(
                    "Anv",
                    "${p.variantLabel} - ${p.label}",
                    p.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.quality = qualities[i] ?: 0
                    this.headers = reqHeaders
                }
            )
            found = true
            for (track in p.tracks) {
                val trackUrl = AnvApi.absolute(track.src) ?: continue
                if (seenSubs.add(trackUrl)) {
                    subtitleCallback(
                        newSubtitleFile(AnvApi.trackName(track), trackUrl) {
                            this.headers = reqHeaders
                        }
                    )
                }
            }
        }
        return found
    }
}
