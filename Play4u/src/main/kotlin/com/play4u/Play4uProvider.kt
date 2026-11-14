package com.play4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager

class Play4uProvider : MainAPI() {

    override var mainUrl = Play4uApi.MAIN_URL
    override var name = "Play4u"
    override var lang = "en"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "all" to "Latest",
        "type=movie" to "Movies",
        "type=series" to "Series",
        "industry=bollywood" to "Bollywood",
        "industry=hollywood" to "Hollywood",
        "industry=kollywood" to "Kollywood",
        "industry=tollywood" to "Tollywood",
        "industry=mollywood" to "Mollywood",
        "genre=action" to "Action",
        "genre=adventure" to "Adventure",
        "genre=animation" to "Animation",
        "genre=comedy" to "Comedy",
        "genre=crime" to "Crime",
        "genre=drama" to "Drama",
        "genre=family" to "Family",
        "genre=fantasy" to "Fantasy",
        "genre=history" to "History",
        "genre=horror" to "Horror",
        "genre=mystery" to "Mystery",
        "genre=reality" to "Reality",
        "genre=romance" to "Romance",
        "genre=science-fiction" to "Sci-Fi",
        "genre=thriller" to "Thriller",
        "genre=war" to "War"
    )

    private fun parseFilters(data: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (pair in data.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            out[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
        return out
    }

    private fun toSearchResponse(item: SearchItem): SearchResponse? {
        val url = item.watch?.takeIf { it.startsWith("/") } ?: return null
        val poster = item.poster_url?.takeIf { it.startsWith("http") }
        val year = item.year?.take(4)?.toIntOrNull()
        return when (item.media_type) {
            "series" -> newTvSeriesSearchResponse(item.title ?: "Unknown", url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(item.title ?: "Unknown", url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // the site api answers an empty query with nothing unless a facet rides
    // along, so the mixed row merges the movie and series listings
    private suspend fun mixedPage(page: Int): HomePageResponse? {
        val movies = Play4uApi.search(null, page, type = "movie") ?: return null
        val series = Play4uApi.search(null, page, type = "series")
        val seriesItems = series?.items ?: emptyList()
        val merged = mutableListOf<SearchItem>()
        val mi = movies.items.iterator()
        val si = seriesItems.iterator()
        while (mi.hasNext() || si.hasNext()) {
            if (mi.hasNext()) merged.add(mi.next())
            if (si.hasNext()) merged.add(si.next())
        }
        if (merged.isEmpty()) return null
        val items = merged.mapNotNull(::toSearchResponse)
        val hasNext = items.isNotEmpty() &&
            ((movies.page ?: page) < (movies.pages ?: 1) ||
                ((series?.page ?: page) < (series?.pages ?: 1)))
        return newHomePageResponse("Latest", items, hasNext = hasNext)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        DonationManager.checkAndShow()
        Play4uApi.refreshDomain()
        mainUrl = Play4uApi.MAIN_URL
        val filters = parseFilters(request.data)
        if (filters.isEmpty()) return mixedPage(page)
        val result = Play4uApi.search(
            query = null,
            page = page,
            type = filters["type"],
            genre = filters["genre"],
            industry = filters["industry"],
            lang = filters["lang"]
        ) ?: return null
        val items = result.items.mapNotNull(::toSearchResponse)
        val hasNext = items.isNotEmpty() && (result.page ?: page) < (result.pages ?: 1)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Play4uApi.refreshDomain()
        mainUrl = Play4uApi.MAIN_URL
        val result = Play4uApi.search(query, page = 1) ?: return emptyList()
        return result.items.mapNotNull(::toSearchResponse)
    }

    private fun maxHeight(list: List<StreamQuality>?): Int? {
        return list.orEmpty()
            .mapNotNull { q ->
                q.height ?: q.slug?.removeSuffix("p")?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            }
            .maxOrNull()
    }

    // site relative paths resolve against the site itself, a network path
    // rides the site scheme, anything else cannot be played
    private fun usableLink(raw: String): String? {
        return when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "${Play4uApi.MAIN_URL}$raw"
            else -> null
        }
    }

    private fun toEpisode(seasonNo: Int, info: EpisodeInfo, sealKey: String): Episode? {
        val number = info.number ?: return null
        val streamUrl = usableLink(Play4uApi.unseal(info.hls, sealKey)) ?: return null
        val top = maxHeight(info.qualities)
        val audio = info.languages.orEmpty()
            .mapNotNull { l -> l.name?.takeIf { it.isNotBlank() } }
        val ref = EpisodeRef(
            u = streamUrl,
            q = top?.toString(),
            a = audio.joinToString("/").takeIf { it.isNotBlank() }
        ).toJson()
        return newEpisode(ref) {
            this.season = seasonNo
            this.episode = number
            this.name = info.title?.takeIf { it.isNotBlank() } ?: "Episode $number"
            this.posterUrl = info.still?.takeIf { it.startsWith("http") }
            this.description = info.overview?.takeIf { it.isNotBlank() && it != "TBD" }
            this.runTime = info.runtime?.takeIf { it > 0 }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Play4uApi.refreshDomain()
        mainUrl = Play4uApi.MAIN_URL
        val html = Play4uApi.page(url) ?: return null

        if (url.contains("/watch/")) {
            val play = Play4uApi.playVar(html)
            val item = play?.item
            if (play?.ok != true || item == null || play.streams.isEmpty()) return null
            val response = newMovieLoadResponse(item.title ?: "Unknown", url, TvType.Movie, url) {
                this.posterUrl = item.poster_url?.takeIf { it.startsWith("http") }
                this.backgroundPosterUrl = item.cover_url?.takeIf { it.startsWith("http") }
                this.plot = item.overview?.takeIf { it.isNotBlank() }
                this.year = item.year()
                this.duration = item.runtime_minutes?.takeIf { it > 0 }
                this.tags = (item.categories + item.tags).filter { it.isNotBlank() }.distinct()
            }
            val actors = item.cast.orEmpty()
                .mapNotNull { p -> p.name?.takeIf { it.isNotBlank() }?.let { Actor(it, p.profile ?: "") to (p.character ?: "") } }
            if (actors.isNotEmpty()) response.addActors(actors)
            item.trailer_key?.takeIf { it.isNotBlank() }?.let { key ->
                try {
                    response.addTrailer("https://www.youtube.com/watch?v=$key")
                } catch (e: Exception) {
                    Log.d(name, "trailer: ${e.message}")
                }
            }
            return response
        }

        val series = Play4uApi.seriesVar(html) ?: return null
        val sealKey = Play4uApi.sealKeyOf(html)
        val episodes = mutableListOf<Episode>()
        var emitted = false
        for ((key, season) in series.seasons) {
            val seasonNo = season.number ?: key.toIntOrNull() ?: continue
            val list = season.episodes.values.mapNotNull { toEpisode(seasonNo, it, sealKey) }
            if (list.isEmpty()) continue
            episodes.addAll(list)
            emitted = true
        }
        if (!emitted) return null

        return newTvSeriesLoadResponse(series.title ?: "Unknown", url, TvType.TvSeries, episodes) {
            this.posterUrl = series.poster?.takeIf { it.startsWith("http") }
            this.backgroundPosterUrl = series.backdrop?.takeIf { it.startsWith("http") }
            this.plot = series.overview?.takeIf { it.isNotBlank() }
            this.year = series.year()
            this.showStatus = if (series.running()) ShowStatus.Ongoing else ShowStatus.Completed
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // an episode click arrives as the ref json built during load, so the
        // stream url never needs a second page fetch
        val ref = try {
            parseJson<EpisodeRef>(data)
        } catch (e: Exception) {
            null
        }
        if (ref?.u != null) {
            emitLink("Play4u", ref.u, ref.q, ref.a, callback)
            return true
        }

        val html = Play4uApi.page(data) ?: return false
        val play = Play4uApi.playVar(html) ?: return false
        if (play.ok != true) return false
        val sealKey = Play4uApi.sealKeyOf(html)
        var emitted = false
        for (stream in play.streams) {
            // a page that ships the plain path plus its cdn domain still
            // resolves without the sealed url
            val link = usableLink(Play4uApi.unseal(stream.m3u8_url, sealKey))
                ?: stream.let {
                    val host = it.domain_name?.takeIf { d -> d.isNotBlank() }
                    val path = it.m3u8_path?.takeIf { p -> p.startsWith("/") }
                    if (host != null && path != null) "https://$host$path" else null
                }
            if (link == null) continue
            val audio = stream.languages.orEmpty()
                .mapNotNull { l -> l.name?.takeIf { it.isNotBlank() } }
            emitLink(
                stream.label?.takeIf { it.isNotBlank() } ?: "Play4u",
                link,
                maxHeight(stream.qualities)?.toString(),
                audio.joinToString("/").takeIf { it.isNotBlank() },
                callback
            )
            emitted = true
        }
        return emitted
    }

    private suspend fun emitLink(
        label: String,
        url: String,
        quality: String?,
        audio: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val linkName = buildString {
            append(label)
            quality?.let { append(" ${it}p") }
            audio?.let { append(" · $it") }
        }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = linkName,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.quality = quality?.toIntOrNull() ?: 0
                this.headers = Play4uApi.streamHeaders
            }
        )
    }
}
