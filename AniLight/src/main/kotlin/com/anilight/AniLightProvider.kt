package com.anilight

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
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AniLightProvider : MainAPI() {

    override var mainUrl = AniLightApi.site()
    override var name = "AniLight"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "home:trending" to "Trending Now",
        "home:popular" to "Most Popular",
        "home:seasonal" to "This Season",
        "home:movies" to "Popular Movies",
        "sort:SCORE_DESC" to "Top Rated",
        "home:recent" to "Recently Added",
        "home:upcoming" to "Upcoming",
        "genre:Action" to "Action",
        "genre:Adventure" to "Adventure",
        "genre:Comedy" to "Comedy",
        "genre:Drama" to "Drama",
        "genre:Fantasy" to "Fantasy",
        "genre:Horror" to "Horror",
        "genre:Mecha" to "Mecha",
        "genre:Mystery" to "Mystery",
        "genre:Psychological" to "Psychological",
        "genre:Romance" to "Romance",
        "genre:Sci-Fi" to "Sci-Fi",
        "genre:Slice of Life" to "Slice of Life",
        "genre:Sports" to "Sports",
        "genre:Supernatural" to "Supernatural",
        "genre:Thriller" to "Thriller"
    )

    companion object {
        private const val TAG = "AniLight"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        DonationManager.checkAndShow()
        AniLightApi.refreshDomain()
        mainUrl = AniLightApi.site()
        val data = request.data
        return when {
            data.startsWith("home:") -> {
                if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                val home = AniLightApi.homepage() ?: return null
                val items = when (data.substringAfter("home:")) {
                    "trending" -> home.trending?.media
                    "popular" -> home.popular?.media
                    "seasonal" -> home.seasonal?.media
                    "movies" -> home.popularMovies?.media
                    "recent" -> home.recentlyAddedEpisodes
                    "upcoming" -> home.upcoming?.media
                    else -> null
                }.orEmpty().mapNotNull { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = false)
            }
            data.startsWith("sort:") -> {
                val sort = data.substringAfter("sort:")
                val feed = AniLightApi.filter(page, sort = sort) ?: return null
                val items = feed.media.orEmpty().mapNotNull { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = feed.pageInfo?.hasNextPage == true)
            }
            data.startsWith("genre:") -> {
                val genre = data.substringAfter("genre:")
                val feed = AniLightApi.filter(page, genre = genre) ?: return null
                val items = feed.media.orEmpty().mapNotNull { it.toSearchResponse() }
                newHomePageResponse(request.name, items, hasNext = feed.pageInfo?.hasNextPage == true)
            }
            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        AniLightApi.refreshDomain()
        mainUrl = AniLightApi.site()
        return AniLightApi.search(query).mapNotNull { it.toSearchResponse() }
    }

    private fun AnimeMedia.toSearchResponse(): SearchResponse? {
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val title = this.title?.display() ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug", tvTypeOf(format)) {
            this.posterUrl = coverImage?.best()
            this.year = seasonYear
            this.otherName = this@toSearchResponse.title?.romaji
            averageScore?.let { score = Score.from100(it) }
        }
    }

    private fun tvTypeOf(format: String?): TvType = when (format) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL" -> TvType.OVA
        else -> TvType.Anime
    }

    override suspend fun load(url: String): LoadResponse? {
        AniLightApi.refreshDomain()
        mainUrl = AniLightApi.site()
        val slug = url.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        val anime = AniLightApi.animeDetail(slug) ?: return null
        val title = anime.title?.display() ?: return null
        val animeId = anime.id ?: return null

        val watch = AniLightApi.watch(slug)
        val entries = watch?.episodes.orEmpty()

        val hasDub = entries.any { it.dub == true }
        val hasSub = entries.any { it.sub == true }

        // the app hides the sub dub switcher on movie types, so dual audio movies stay regular anime
        val tvType = when {
            anime.format == "MOVIE" && hasDub -> TvType.Anime
            else -> tvTypeOf(anime.format)
        }

        val subEpisodes = if (hasSub) {
            entries.filter { it.sub == true }.map { it.toEpisode(animeId, "sub") }
        } else {
            emptyList()
        }
        val dubEpisodes = if (hasDub) {
            entries.filter { it.dub == true }.map { it.toEpisode(animeId, "dub") }
        } else {
            emptyList()
        }

        val showStatus = when (anime.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val response = newAnimeLoadResponse(title, "$mainUrl/anime/$slug", tvType) {
            this.engName = anime.title?.english
            this.japName = anime.title?.romaji ?: anime.title?.native
            this.posterUrl = anime.coverImage?.best()
            this.backgroundPosterUrl = anime.bannerImage?.takeIf { it.isNotBlank() }
            this.plot = anime.description?.let(::stripHtml)
            this.tags = anime.genres.orEmpty()
            this.year = anime.seasonYear
            this.duration = anime.duration
            this.showStatus = showStatus
            anime.averageScore?.let { score = Score.from100(it) }
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
        anime.trailer?.let { trailer ->
            if (trailer.site == "youtube" && !trailer.id.isNullOrBlank()) {
                try {
                    response.addTrailer("https://www.youtube.com/watch?v=${trailer.id}")
                } catch (e: Exception) {
                    Log.d(TAG, "trailer: ${e.message}")
                }
            }
        }
        return response
    }

    private fun EpisodeEntry.toEpisode(animeId: Int, audio: String): Episode {
        val num = number ?: 1
        val data = AniLightEpisodeData(
            animeId = animeId,
            episode = num,
            audio = audio
        ).toJson()
        return newEpisode(data) {
            this.episode = num
            // blank names fall back to the plain number so the row never shows gaps
            this.name = title?.takeIf { it.isNotBlank() } ?: "Episode $num"
            this.posterUrl = img?.takeIf { it.startsWith("http") }
            this.description = buildString {
                if (isFiller == true) append("Filler episode")
                airedAt?.let {
                    if (isNotEmpty()) append(" | ")
                    append("Aired ${it.substringBefore("T").take(10)}")
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" | ")
                    append(stripHtml(it))
                }
            }.takeIf { it.isNotBlank() }
            parseAirDate(airedAt)?.let { addDate(it) }
        }
    }

    private fun parseAirDate(raw: String?): Date? {
        if (raw.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(raw.substringBefore("T").take(10))
        } catch (e: Exception) {
            Log.d(TAG, "air date parse: ${e.message}")
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
            parseJson<AniLightEpisodeData>(data)
        } catch (e: Exception) {
            Log.d(TAG, "episode data parse: ${e.message}")
            return false
        }
        val animeId = ref.animeId ?: return false
        val episode = ref.episode ?: return false
        val language = if (ref.audio == "dub") "dub" else "sub"
        val audioLabel = if (language == "dub") "DUB" else "SUB"

        val seenSubs = mutableSetOf<String>()
        val seenLinks = mutableSetOf<String>()
        var emitted = false

        // the site's own library, one row per quality, direct mp4 files
        val own = AniLightApi.sources(animeId, episode, language, backup = false)
        own?.sources.orEmpty().forEach { entry ->
            val token = entry.tokens().firstOrNull() ?: return@forEach
            val streamUrl = AniLightApi.resolveStream(token) ?: return@forEach
            if (!seenLinks.add(streamUrl)) return@forEach
            val height = qualityHeight(entry.quality)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = buildString {
                        append("AniLight ").append(audioLabel)
                        height?.let { append(" ${it}p") }
                    },
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    height?.let { quality = it }
                }
            )
            emitted = true
        }
        own?.tracks.orEmpty().let { emitSubtitles(it, seenSubs, subtitleCallback) }

        // the megaplay scraped mirrors, hls playlists behind the same balancer
        val backup = AniLightApi.sources(animeId, episode, language, backup = true)
        backup?.sources.orEmpty().forEach { entry ->
            val tokens = entry.tokens()
            val results = coroutineScope {
                tokens.mapIndexed { index, token ->
                    async(Dispatchers.IO) {
                        val streamUrl = AniLightApi.resolveStream(token) ?: return@async null
                        val master = AniLightApi.hlsMaster(streamUrl) ?: return@async null
                        index to master
                    }
                }
            }.mapNotNull { it.await() }

            results.sortedBy { it.first }.forEach { (_, master) ->
                val (url, maxHeight) = master
                if (!seenLinks.add(url)) return@forEach
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = buildString {
                            append("MegaPlay ").append(audioLabel)
                            maxHeight?.let { append(" ${it}p") } ?: append(" HLS")
                        },
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        maxHeight?.let { quality = it }
                    }
                )
                emitted = true
            }
        }
        backup?.tracks.orEmpty().let { emitSubtitles(it, seenSubs, subtitleCallback) }

        return emitted
    }

    // only caption rows are kept, sprite tracks cannot be played by the player
    private suspend fun emitSubtitles(
        tracks: List<CaptionTrack>,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        tracks.forEach { track ->
            if (track.kind != null && track.kind != "captions") return@forEach
            val token = track.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val rawLabel = track.label?.takeIf { it.isNotBlank() }
                ?: track.lang?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val label = SubtitleLabels.prettify(rawLabel)
            if (label.isBlank() || !seen.add(label.lowercase())) return@forEach
            try {
                subtitleCallback.invoke(
                    newSubtitleFile(label, AniLightApi.captionsUrl(token))
                )
            } catch (e: Exception) {
                Log.d(TAG, "subtitle emit: ${e.message}")
            }
        }
    }

    // quality labels arrive as pixel heights like 1080p or 1036p
    private fun qualityHeight(label: String?): Int? {
        val digits = label?.filter { it.isDigit() } ?: return null
        return digits.toIntOrNull()?.takeIf { it in 100..4320 }
    }
}
