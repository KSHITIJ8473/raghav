package com.laddu100.animeinweb

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addPoster
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

class AnimeInWebProvider : MainAPI() {
    override var mainUrl = "https://animeinweb.com"
    override var name = "AnimeInWeb"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries)

    private val proxySecret = "animein-secure-proxy-key-123"
    private val imgHost = "https://xyz-api.animein.net"
    private val apiHeaders = mapOf(
        "x-proxy-secret" to proxySecret,
        "Accept" to "application/json",
        "User-Agent" to USER_AGENT
    )
    // the image host serves app user agents and referer carrying browser requests only
    private val posterHeaders get() = mapOf(
        "User-Agent" to IMG_USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    private val posterCache = ConcurrentHashMap<String, String>()
    private val posterSemaphore = Semaphore(POSTER_CONCURRENCY)

    // kitsu's cdn serves images to every client, the site's image hosts do not
    private suspend fun posterFor(title: String?, siteUrl: String?): String? {
        val fallback = fixPosterUrl(siteUrl) ?: return null
        val key = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return fallback
        posterCache[key]?.let { cached ->
            return cached.takeIf { it.isNotEmpty() } ?: fallback
        }
        return try {
            val found = posterSemaphore.withPermit { kitsuPoster(key) }
            // signed s3 urls expire in minutes, don't cache those
            if (found != null && !found.contains("X-Amz-")) posterCache[key] = found
            else if (found == null) posterCache[key] = ""
            found ?: fallback
        } catch (e: Exception) {
            Log.e(TAG, "poster lookup failed: ${e.message}")
            fallback
        }
    }

    private suspend fun kitsuPoster(key: String): String? {
        val query = URLEncoder.encode(key, "UTF-8")
        val text = app.get(
            "$KITSU_API_URL/anime?filter[text]=$query&page%5Blimit%5D=5",
            headers = mapOf(
                "Accept" to "application/vnd.api+json",
                "User-Agent" to USER_AGENT
            )
        ).text
        val site = normalizeTitle(key)
        var bestScore = 0
        var bestUrl: String? = null
        for (item in parseJson<KitsuEnvelope>(text).data) {
            val attrs = item.attributes
            val url = attrs.posterImage.large ?: attrs.posterImage.medium ?: attrs.posterImage.small ?: continue
            val candidates = sequenceOf(attrs.canonicalTitle, attrs.titles["en"], attrs.titles["en_jp"])
                .filterNotNull()
                .distinct()
            for (candidate in candidates) {
                val score = titleMatchScore(site, normalizeTitle(candidate))
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = url
                }
            }
        }
        return bestUrl
    }

    private fun normalizeTitle(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun titleTokens(value: String): List<String> =
        value.lowercase().split(TITLE_TOKEN_SEPARATOR).filter { it.isNotEmpty() }

    // exact, prefix stem, then word overlap, anything weaker falls back to the site poster
    private fun titleMatchScore(site: String, candidate: String): Int {
        if (site.isEmpty() || candidate.isEmpty()) return 0
        if (site == candidate) return 3
        if (minOf(site.length, candidate.length) >= 6 &&
            (candidate.startsWith(site) || site.startsWith(candidate))
        ) return 2
        val siteTokens = titleTokens(site).toSet()
        val candidateTokens = titleTokens(candidate).toSet()
        val shared = siteTokens.intersect(candidateTokens).size
        val smallest = minOf(siteTokens.size, candidateTokens.size)
        return if (shared >= 2 && shared * 10 >= smallest * 6) 1 else 0
    }

    private fun fixPosterUrl(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        return if (value.startsWith("http")) value else "$imgHost$value"
    }

    private fun apiUrl(path: String): String = "$mainUrl/api/proxy/3/2$path"

    private suspend inline fun <reified T : Any> fetchJson(url: String): T {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val text = app.get(url, headers = apiHeaders).text
                return parseJson<T>(text)
            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "request failed: ${e.message}")
                delay(800L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("fetch failed")
    }

    private fun tvTypeOf(type: String?): TvType = when (type?.uppercase()) {
        "MOVIE" -> TvType.AnimeMovie
        "OVA", "ONA", "SPECIAL" -> TvType.OVA
        "LIVE ACTION" -> TvType.TvSeries
        else -> TvType.Anime
    }

    private fun showStatusOf(status: String?): ShowStatus? = when (status?.uppercase()) {
        "ONGOING" -> ShowStatus.Ongoing
        "FINISHED" -> ShowStatus.Completed
        else -> null
    }

    private fun qualityValue(label: String?): Int = when {
        label?.contains("1080") == true -> Qualities.P1080.value
        label?.contains("720") == true -> Qualities.P720.value
        label?.contains("480") == true -> Qualities.P480.value
        label?.contains("360") == true -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    // the schedule api only answers full indonesian day names
    private fun todayIndonesianDay(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Jakarta"))
        val names = arrayOf("MINGGU", "SENIN", "SELASA", "RABU", "KAMIS", "JUMAT", "SABTU")
        return names[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun parseAirDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val sdf = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
            sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
            sdf.parse(value.trim())?.time
        }.getOrNull()
    }

    private suspend fun MovieItem.toSearchResponse(): SearchResponse {
        val url = "$mainUrl/anime/$id"
        val poster = posterFor(title, image_poster)
        val type = tvTypeOf(this.type)
        val titleValue = title?.takeIf { it.isNotBlank() } ?: "Unknown"
        return when (type) {
            TvType.TvSeries -> newTvSeriesSearchResponse(titleValue, url, TvType.TvSeries) {
                addPoster(poster, posterHeaders)
            }
            else -> newAnimeSearchResponse(titleValue, url, type) {
                addPoster(poster, posterHeaders)
            }
        }
    }

    // the explore endpoint can't sort newest first, home/data "new" is what the site shows as Terbaru
    override val mainPage = mainPageOf(
        "home:new" to "Terbaru",
        "views" to "Populer",
        "schedule" to "Jadwal Hari Ini",
        "home:hot" to "Hot",
        "home:waiting" to "Akan Tayang",
        "home:random" to "Acak"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("animeinweb") ?: mainUrl
        return when {
            request.data == "views" -> explorePage(page, request)
            request.data == "schedule" -> {
                // these sections have no more pages, the app starts every row at page 1
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val day = todayIndonesianDay()
                val items = fetchJson<ScheduleEnvelope>("${apiUrl("/schedule/data")}?day=$day")
                    .data.movie.map { it.toSearchResponse() }
                newHomePageResponse(request, items, false)
            }
            request.data.startsWith("home:") -> {
                if (page > 1) return newHomePageResponse(request, emptyList(), false)
                val key = request.data.removePrefix("home:")
                val data = fetchJson<HomeEnvelope>("${apiUrl("/home/data")}?day=${todayIndonesianDay()}&limit=16").data
                val list = when (key) {
                    "new" -> data.new
                    "hot" -> data.hot
                    "waiting" -> data.waiting
                    "random" -> data.random
                    else -> emptyList()
                }
                newHomePageResponse(request, list.map { it.toSearchResponse() }, false)
            }
            else -> explorePage(page, request)
        }
    }

    // the explore api pages start at 0, cloudstream at 1
    private suspend fun explorePage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = fetchJson<ExploreEnvelope>("${apiUrl("/explore/movie")}?page=${page - 1}&sort=views&keyword=")
        val items = res.data.movie.map { it.toSearchResponse() }
        return newHomePageResponse(request, items, items.size >= EXPLORE_PAGE_SIZE)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        mainUrl = FirebaseDomainHelper.getDomain("animeinweb") ?: mainUrl
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val encoded = URLEncoder.encode(query, "UTF-8")
        // search api pages start at 0, cloudstream at 1
        val res = fetchJson<ExploreEnvelope>("${apiUrl("/explore/movie")}?page=${page - 1}&sort=&keyword=$encoded")
        val items = res.data.movie.map { it.toSearchResponse() }
        return newSearchResponseList(items, items.size >= EXPLORE_PAGE_SIZE)
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("animeinweb") ?: mainUrl
        val id = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        val detail = fetchJson<DetailEnvelope>("${apiUrl("/movie/detail")}/$id").data
        val movie = detail.movie
        val title = movie.title?.takeIf { it.isNotBlank() } ?: return null
        val tvType = tvTypeOf(movie.type)

        val episodes = fetchEpisodePages(id)
            .reversed()
            .distinctBy { it.id }
            .mapIndexed { idx, ep ->
                newEpisode(ep.id) {
                    this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${ep.index ?: (idx + 1)}"
                    this.episode = ep.index?.toIntOrNull() ?: (idx + 1)
                    this.date = parseAirDate(ep.key_time)
                }
            }

        return if (tvType == TvType.TvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.plot = movie.synopsis
                this.tags = movie.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                this.year = movie.year?.toIntOrNull()
                this.showStatus = showStatusOf(movie.status)
                addPoster(posterFor(movie.title, movie.image_poster), posterHeaders)
            }
        } else {
            newAnimeLoadResponse(title, url, tvType) {
                this.plot = movie.synopsis
                this.tags = movie.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                this.year = movie.year?.toIntOrNull()
                this.showStatus = showStatusOf(movie.status)
                this.synonyms = movie.synonyms?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
                addPoster(posterFor(movie.title, movie.image_poster), posterHeaders)
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }
    }

    // the api serves 30 episodes per page, newest first
    private suspend fun fetchEpisodePages(id: String): List<EpisodeItem> {
        val first = episodePage(id, 0)
        if (first.size < EPISODES_PER_PAGE) return first
        val all = mutableListOf<EpisodeItem>()
        all.addAll(first)
        coroutineScope {
            var start = 1
            while (start <= MAX_EPISODE_PAGES) {
                val range = start until start + PAGE_BATCH
                val pages = range.map { p ->
                    async {
                        runCatching { episodePage(id, p) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
                pages.forEach { all.addAll(it) }
                if (pages.any { it.size < EPISODES_PER_PAGE }) break
                start += PAGE_BATCH
            }
        }
        return all
    }

    private suspend fun episodePage(id: String, page: Int): List<EpisodeItem> {
        val res = fetchJson<EpisodeListEnvelope>("${apiUrl("/movie/episode")}/$id?page=$page")
        return res.data.episode
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        // some app builds hand back the episode url instead of the raw id
        val epId = data.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        val stream = fetchJson<StreamEnvelope>("${apiUrl("/episode/streamnew")}/$epId").data
        var found = false

        for (server in stream.server) {
            val link = server.link?.takeIf { it.isNotBlank() } ?: continue
            when (server.type) {
                "direct" -> {
                    val label = server.name?.takeIf { it.isNotBlank() } ?: "RAPSODI"
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = link,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityValue(server.quality)
                            this.referer = "$mainUrl/"
                        }
                    )
                    found = true
                }
                else -> {
                    // only blogger and gdplayer semi hosts are worth trying, the rest are dead
                    val host = runCatching { URI(link).host ?: "" }.getOrDefault("")
                    if (host == "www.blogger.com" || host == "gdplayer.to") {
                        try {
                            if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) found = true
                        } catch (e: Exception) {
                            Log.e(TAG, "extractor failed for $host: ${e.message}")
                        }
                    }
                }
            }
        }

        return found
    }

    companion object {
        private const val TAG = "AnimeInWeb"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val IMG_USER_AGENT = "okhttp/4.12.0"
        private const val KITSU_API_URL = "https://kitsu.app/api/edge"
        private const val POSTER_CONCURRENCY = 8
        private val TITLE_TOKEN_SEPARATOR = Regex("[^a-z0-9]+")
        private const val EXPLORE_PAGE_SIZE = 60
        private const val EPISODES_PER_PAGE = 30
        private const val PAGE_BATCH = 6
        private const val MAX_EPISODE_PAGES = 60

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class MovieItem(
            val id: String = "",
            val title: String? = null,
            val synopsis: String? = null,
            val synonyms: String? = null,
            val image_poster: String? = null,
            val image_cover: String? = null,
            val type: String? = null,
            val year: String? = null,
            val day: String? = null,
            val status: String? = null,
            val studio: String? = null,
            val aired_start: String? = null,
            val genre: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class EpisodeItem(
            val id: String = "",
            val index: String? = null,
            val title: String? = null,
            val id_movie: String? = null,
            val key_time: String? = null,
            val image: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ServerItem(
            val id: String = "",
            val link: String? = null,
            val quality: String? = null,
            val key_file_size: String? = null,
            val name: String? = null,
            val type: String? = null,
            val domain: String? = null,
            val username: String? = null,
            val server_id: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class HomeData(
            val hot: List<MovieItem> = emptyList(),
            val `new`: List<MovieItem> = emptyList(),
            val popular: List<MovieItem> = emptyList(),
            val waiting: List<MovieItem> = emptyList(),
            val random: List<MovieItem> = emptyList(),
            val today: List<MovieItem> = emptyList()
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class HomeEnvelope(val data: HomeData = HomeData())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ExploreData(val movie: List<MovieItem> = emptyList())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ExploreEnvelope(val data: ExploreData = ExploreData())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ScheduleData(val movie: List<MovieItem> = emptyList())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class ScheduleEnvelope(val data: ScheduleData = ScheduleData())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class DetailData(val movie: MovieItem = MovieItem(), val episode: EpisodeItem? = null)

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class DetailEnvelope(val data: DetailData = DetailData())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class EpisodeListData(val episode: List<EpisodeItem> = emptyList())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class EpisodeListEnvelope(val data: EpisodeListData = EpisodeListData())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class StreamData(
            val episode: EpisodeItem? = null,
            val episode_next: EpisodeItem? = null,
            val server: List<ServerItem> = emptyList()
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class StreamEnvelope(val data: StreamData = StreamData())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class KitsuPosterImage(
            val small: String? = null,
            val medium: String? = null,
            val large: String? = null
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class KitsuAttributes(
            val canonicalTitle: String? = null,
            val titles: Map<String, String> = emptyMap(),
            val posterImage: KitsuPosterImage = KitsuPosterImage()
        )

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class KitsuItem(val attributes: KitsuAttributes = KitsuAttributes())

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class KitsuEnvelope(val data: List<KitsuItem> = emptyList())
    }
}
