package com.laddu100.reanime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import com.raghav.donation.DonationManager

class ReAnimeProvider : MainAPI() {

    override var mainUrl = ReAnimeApi.url()
    override var name = "Re:ANIME"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "popular" to "Popular",
        "latest-aired" to "Latest Episodes",
        "new-on-site" to "Recently Added",
        "upcoming" to "Upcoming"
    )

    private data class EpisodeRef(
        val slug: String,
        val ep: Int,
        val lang: String,
        val anilistId: Int?,
        val tmdbId: Int?,
        val season: Int?
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        DonationManager.checkAndShow()
        ReAnimeApi.refreshDomain()
        mainUrl = ReAnimeApi.url()
        when (request.data) {
            "popular" -> {
                val items = ReAnimeApi.searchSorted("popularity_desc", page)
                return newHomePageResponse(
                    request.name,
                    items.mapNotNull { it.toSearchResponse() },
                    hasNext = items.isNotEmpty()
                )
            }
            "latest-aired", "new-on-site", "upcoming" -> {
                val (list, hasMore) = ReAnimeApi.homeSection(request.data, page)
                return newHomePageResponse(
                    request.name,
                    list.mapNotNull { it.toSearchResponse() },
                    hasNext = hasMore
                )
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ReAnimeApi.refreshDomain()
        mainUrl = ReAnimeApi.url()

        if (query.isBlank()) return emptyList()
        return ReAnimeApi.search(query, 1).mapNotNull { it.toSearchResponse() }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val slug = animeId ?: return null
        val displayTitle = title?.display() ?: return null
        val romaji = title?.romaji
        val poster = coverImage?.best()
        val year = seasonYear
        val subCount = subbed ?: 0
        val dubCount = dubbed ?: 0
        val scoreValue = averageScore
        return newAnimeSearchResponse(displayTitle, "$mainUrl/anime/$slug") {
            this.posterUrl = poster
            this.year = year
            this.otherName = romaji
            addDubStatus(dubExist = dubCount > 0, subExist = subCount > 0)
            scoreValue?.let { score = Score.from100(it.toDouble()) }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        ReAnimeApi.refreshDomain()
        mainUrl = ReAnimeApi.url()

        val slug = url.removePrefix("$mainUrl/").removePrefix("/").removePrefix("anime/")
        val anime = ReAnimeApi.animeDetail(slug) ?: return null
        val eps = ReAnimeApi.episodes(slug)
        val title = anime.title?.display() ?: return null
        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val subEps = mutableListOf<Episode>()
        val dubEps = mutableListOf<Episode>()
        for (ep in eps) {
            val num = ep.episodeNumber ?: continue
            if (ep.subbed == true) subEps.add(ep.toEpisode(slug, num, "sub", anime))
            if (ep.dubbed == true) dubEps.add(ep.toEpisode(slug, num, "dub", anime))
        }
        if (subEps.isEmpty() && dubEps.isEmpty()) {
            for (ep in eps) {
                val num = ep.episodeNumber ?: continue
                subEps.add(ep.toEpisode(slug, num, "sub", anime))
            }
        }

        val type = when {
            anime.format == "MOVIE" && dubEps.isNotEmpty() -> TvType.Anime
            anime.format == "MOVIE" -> TvType.AnimeMovie
            anime.format == "OVA" || anime.format == "ONA" || anime.format == "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug", type) {
            this.engName = anime.title?.english
            this.japName = anime.title?.romaji ?: anime.title?.native
            this.posterUrl = anime.coverImage?.best()
            this.backgroundPosterUrl = anime.bannerImage
            this.year = anime.seasonYear
            this.showStatus = showStatus
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres ?: anime.tags?.mapNotNull { it.name } ?: emptyList()
            this.duration = anime.duration
            this.score = anime.averageScore?.let { Score.from100(it.toDouble()) }
            addAniListId(anime.anilistId ?: 0)
            addMalId(anime.malId ?: 0)
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun EpisodeEntry.toEpisode(
        slug: String,
        num: Int,
        lang: String,
        anime: AnimeDetail
    ): Episode {
        val ref = EpisodeRef(
            slug = slug,
            ep = num,
            lang = lang,
            anilistId = anime.anilistId,
            tmdbId = anime.themoviedbId,
            season = anime.externalSeasons?.tmdb
        )
        return newEpisode(ref.toJson()) {
            this.name = title?.takeIf { it.isNotBlank() && !it.startsWith("Episode ") }
            this.episode = num
            this.description = description?.takeIf { it.isNotBlank() }?.let(::stripHtml)
            this.posterUrl = thumbnail?.takeIf { it.startsWith("http") }
            parseAirDate(aired)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (_: Exception) {
            null
        }
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .trim()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ref = try {
            parseJson<EpisodeRef>(data)
        } catch (_: Exception) {
            null
        } ?: return false
        if (ref.ep <= 0) return false

        val servers = ReAnimeApi.flixServers(ref.anilistId, ref.tmdbId, ref.season, ref.ep)
        if (servers.isEmpty()) return false

        // Every server of an episode (HD-1/HD-2, sub/dub) points at the same flixcloud file:
        // one MKV with both the Japanese and English audio and all subtitles inside
        val embeds = servers.mapNotNull { s ->
            s.dataLink?.takeIf { it.startsWith("http") && FlixDownload.embedId(it) != null }
        }.distinctBy { FlixDownload.embedId(it) }

        val any = AtomicBoolean(false)

        coroutineScope {
            embeds.map { embedUrl ->
                async {
                    val file = FlixDownload.resolve(embedUrl) ?: return@async
                    any.set(true)
                    val label = "Re:ANIME - Sub & Dub" + (file.size?.let { " ($it)" } ?: "")
                    callback.invoke(
                        newExtractorLink(label, label, file.url, type = ExtractorLinkType.VIDEO) {
                            file.quality?.let { this.quality = it }
                            this.headers = file.headers
                        }
                    )
                }
            }
        }.forEach { it.join() }

        return any.get()
    }
}
