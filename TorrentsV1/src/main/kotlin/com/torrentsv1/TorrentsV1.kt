package com.torrentsv1
import android.util.Log

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

private const val ANILIST_URL = "https://graphql.anilist.co"
private const val ANIZIP_API = "https://api.ani.zip"
private const val TMDB_API = "https://api.themoviedb.org/3"
private val TMDB_KEY get() = BuildConfig.TMDB_KEY
private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
private const val TMDB_IMG_ORIG = "https://image.tmdb.org/t/p/original"
private const val CINEMETA = "https://cinemeta-catalogs.strem.io"
private const val CINEMETA_META = "https://v3-cinemeta.strem.io"

private const val TORRENTIO_BASE = "https://torrentio.strem.fun"
private const val TORRENTSDB_BASE = "https://torrentsdb.com"
private const val TORRENTSDB_CFG = "eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ=="
private const val ANIMETOSHO_API = "https://animetosho.xyz"

private val HARDCODED_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce"
)

internal const val KEY_ANILIST = "torrentsv1_anilist"
internal const val KEY_TMDB = "torrentsv1_tmdb"
internal const val KEY_CINEMETA = "torrentsv1_cinemeta"
internal const val KEY_TORRENTIO = "torrentsv1_torrentio"
internal const val KEY_TORRENTSDB = "torrentsv1_torrentsdb"
internal const val KEY_ANIMETOSHO = "torrentsv1_animetosho"
internal const val KEY_STREMIO_ADDONS = "torrentsv1_stremio_addons"
internal const val KEY_DEBRID_PROVIDER = "torrentsv1_debrid_provider"
internal const val KEY_DEBRID_KEY = "torrentsv1_debrid_key"

internal fun getSetting(key: String, default: Boolean): Boolean =
    try { CloudStreamApp.getKey(key) ?: default } catch (_: Throwable) { default }

internal fun setSetting(key: String, value: Boolean) {
    try { CloudStreamApp.setKey(key, value) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
}

internal fun getStringSetting(key: String): String =
    try { CloudStreamApp.getKey<String>(key) ?: "" } catch (_: Throwable) { "" }

internal fun setStringSetting(key: String, value: String) {
    try { CloudStreamApp.setKey(key, value) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
}

internal fun getStremioAddons(): List<StremioAddon> {
    val raw = try { CloudStreamApp.getKey<String>(KEY_STREMIO_ADDONS) } catch (_: Throwable) { null } ?: return emptyList()
    return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
        val parts = line.split("|", limit = 3)
        if (parts.size >= 2) StremioAddon(parts[0], parts[1], if (parts.size > 2) parts[2] else "HTTPS")
        else null
    }
}

internal fun saveStremioAddons(addons: List<StremioAddon>) {
    val raw = if (addons.isEmpty()) "" else addons.joinToString("\n") { "${it.name}|${it.url}|${it.type}" }
    try { CloudStreamApp.setKey(KEY_STREMIO_ADDONS, raw) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
}

data class StremioAddon(val name: String, val url: String, val type: String)

private fun buildMagnet(infoHash: String?, fileIdx: Int?, sourceTrackers: List<String>?): String? {
    if (infoHash.isNullOrBlank()) return null
    val trackers = LinkedHashSet<String>()
    sourceTrackers?.forEach { src ->
        val t = if (src.startsWith("tracker:", true)) src.substringAfter("tracker:").trim() else src
        if (t.isNotBlank()) trackers.add(t)
    }
    HARDCODED_TRACKERS.forEach { trackers.add(it) }
    val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
    sb.append("&dn=").append(URLEncoder.encode(infoHash, "UTF-8"))
    if (fileIdx != null) sb.append("&index=").append(fileIdx)
    trackers.forEach { sb.append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
    return sb.toString()
}

private fun getQualityFromString(title: String?): Int {
    if (title.isNullOrBlank()) return Qualities.Unknown.value
    val lower = title.lowercase()
    return when {
        lower.contains("4k") || lower.contains("2160") -> 2160
        lower.contains("1080") -> 1080
        lower.contains("720") -> 720
        lower.contains("480") -> 480
        else -> Qualities.Unknown.value
    }
}

private fun getSeedersFromTitle(title: String?): Int {
    if (title.isNullOrBlank()) return 0
    return Regex("""[👤👥]\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

private fun simplifyTitle(title: String?): String {
    if (title.isNullOrBlank()) return ""
    return title.replace(Regex("""[👤👥⚙️💾]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
    val jsonBody = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(
        mapOf("query" to query, "variables" to variables)
    )
    return app.post(
        ANILIST_URL,
        headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
        requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
    ).text
}

private suspend fun tmdbGet(path: String): String {
    val sep = if (path.contains("?")) "&" else "?"
    return app.get("$TMDB_API$path${sep}api_key=$TMDB_KEY").text
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioStreamResponse(val streams: List<StremioStream>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioStream(
    val name: String? = null, val title: String? = null, val description: String? = null,
    val url: String? = null, val infoHash: String? = null, val fileIdx: Int? = null,
    val sources: List<String>? = null, val behaviorHints: StremioBehaviorHints? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioBehaviorHints(
    val proxyHeaders: StremioProxyHeaders? = null, val headers: Map<String, String>? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioProxyHeaders(val request: StremioRequest? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioRequest(
    val Referer: String? = null, val Origin: String? = null,
    @JsonProperty("User-Agent") val userAgent: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true) data class AniListResponse(val data: AniListData? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniListData(val Page: AniListPage? = null, val Media: AniListMedia? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniListPage(val media: List<AniListMedia>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListMedia(
    val id: Int? = null, val title: AniListTitle? = null,
    val coverImage: AniListCover? = null, val bannerImage: String? = null,
    val description: String? = null, val episodes: Int? = null,
    val seasonYear: Int? = null, val averageScore: Int? = null,
    val genres: List<String>? = null, val format: String? = null, val status: String? = null
)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniListTitle(val english: String? = null, val romaji: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniListCover(val extraLarge: String? = null, val large: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true) data class TmdbResponse(val results: List<TmdbItem>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbItem(
    val id: Int? = null, val title: String? = null, val name: String? = null,
    val poster_path: String? = null, val backdrop_path: String? = null,
    val overview: String? = null, val release_date: String? = null,
    val first_air_date: String? = null, val vote_average: Double? = null,
    val media_type: String? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbDetail(
    val id: Int? = null, val title: String? = null, val name: String? = null,
    val poster_path: String? = null, val backdrop_path: String? = null,
    val overview: String? = null, val release_date: String? = null,
    val first_air_date: String? = null, val vote_average: Double? = null,
    val genres: List<TmdbGenre>? = null, val seasons: List<TmdbSeason>? = null
)
@JsonIgnoreProperties(ignoreUnknown = true) data class TmdbGenre(val name: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeason(val season_number: Int? = null, val name: String? = null, val episode_count: Int? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class TmdbExternalIds(val imdb_id: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true) data class CinemetaResponse(val metas: List<CinemetaMeta>? = null, val meta: CinemetaMeta? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemetaMeta(
    val imdb_id: String? = null, val name: String? = null, val type: String? = null,
    val poster: String? = null, val background: String? = null,
    val description: String? = null, val year: String? = null,
    val genres: List<String>? = null, val videos: List<CinemetaVideo>? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CinemetaVideo(val id: String? = null, val title: String? = null, val season: Int? = null, val episode: Int? = null, val thumbnail: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true) data class AniZipResponse(val mappings: AniZipMappings? = null, val episodes: Map<String, AniZipEpisode>? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniZipMappings(val mal_id: Int? = null, val kitsu_id: Int? = null, val imdb_id: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniZipEpisode(val anidbEid: Int? = null, val title: Map<String, String>? = null, val image: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true) data class AnimetoshoResponse(val data: AnimetoshoData? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AnimetoshoData(val entries: List<AnimetoshoEntry>? = null, val releases: List<AnimetoshoEntry>? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AnimetoshoEntry(val name: String? = null, val title: String? = null, val magnet: String? = null, val seeders: Int? = null, val size_bytes: Long? = null)

data class LinkData(
    val source: String, val anilistId: Int? = null, val malId: Int? = null,
    val kitsuId: Int? = null, val imdbId: String? = null, val tmdbId: Int? = null,
    val title: String, val jpTitle: String? = null, val episode: Int,
    val season: Int? = null, val year: Int? = null, val format: String? = null
)

private val ANILIST_HOMEPAGE = """
    query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}sort: [MediaSort], ${'$'}genreIn: [String], ${'$'}format: MediaFormat) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, sort: ${'$'}sort, genre_in: ${'$'}genreIn, format: ${'$'}format) {
                id title { romaji english } coverImage { large extraLarge }
                episodes seasonYear averageScore genres format status
            }
        }
    }
""".trimIndent()

private val ANILIST_SEARCH = """
    query (${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
        Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: ANIME, search: ${'$'}search, sort: POPULARITY_DESC) {
                id title { romaji english } coverImage { large extraLarge }
                episodes seasonYear averageScore genres format status
            }
        }
    }
""".trimIndent()

private val ANILIST_INFO = """
    query (${'$'}id: Int) {
        Media(id: ${'$'}id, type: ANIME) {
            id title { romaji english native }
            coverImage { large extraLarge } bannerImage description
            episodes seasonYear averageScore genres format status
        }
    }
""".trimIndent()

class TorrentsV1 : MainAPI() {
    override var mainUrl = "https://graphql.anilist.co"
    override var name = "TorrentsV1"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA,
        TvType.Movie, TvType.TvSeries, TvType.Torrent
    )

    // STATIC mainPage — CloudStream reads this once at init.
    // getMainPage() returns empty list for disabled catalogs.
    override val mainPage = mainPageOf(
        // AniList (10 sections)
        "anilist_trending" to "Anime: Trending",
        "anilist_popular" to "Anime: Popular",
        "anilist_top" to "Anime: Top Rated",
        "anilist_action" to "Anime: Action",
        "anilist_fantasy" to "Anime: Fantasy",
        "anilist_comedy" to "Anime: Comedy",
        "anilist_drama" to "Anime: Drama",
        "anilist_scifi" to "Anime: Sci-Fi",
        "anilist_romance" to "Anime: Romance",
        "anilist_movies" to "Anime: Movies",
        // TMDB (10 sections)
        "tmdb_trending" to "Trending Movies & TV",
        "tmdb_popular_movies" to "Popular Movies",
        "tmdb_popular_tv" to "Popular TV Shows",
        "tmdb_top_movies" to "Top Rated Movies",
        "tmdb_top_tv" to "Top Rated TV Shows",
        "tmdb_netflix" to "Netflix",
        "tmdb_amazon" to "Amazon Prime",
        "tmdb_disney" to "Disney+",
        "tmdb_hbo" to "HBO",
        "tmdb_korean" to "Korean Shows",
        // Cinemeta (10 sections)
        "cinemeta_top_movies" to "Cinemeta: Top Movies",
        "cinemeta_top_series" to "Cinemeta: Top Series",
        "cinemeta_year_movies" to "Cinemeta: New Movies",
        "cinemeta_year_series" to "Cinemeta: New Series",
        "cinemeta_imdb_rating_movies" to "Cinemeta: Rated Movies",
        "cinemeta_imdb_rating_series" to "Cinemeta: Rated Series",
        "cinemeta_imdb_popular_movies" to "Cinemeta: Popular Movies",
        "cinemeta_imdb_popular_series" to "Cinemeta: Popular Series",
        "cinemeta_last_voted_movies" to "Cinemeta: Voted Movies",
        "cinemeta_last_voted_series" to "Cinemeta: Voted Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = try {
            when {
                request.data.startsWith("anilist_") -> {
                    if (!getSetting(KEY_ANILIST, true)) emptyList()
                    else fetchAniListHome(request.data, page)
                }
                request.data.startsWith("tmdb_") -> {
                    if (!getSetting(KEY_TMDB, true)) emptyList()
                    else fetchTmdbHome(request.data, page)
                }
                request.data.startsWith("cinemeta_") -> {
                    if (!getSetting(KEY_CINEMETA, false)) emptyList()
                    else fetchCinemetaHome(request.data, page)
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun fetchAniListHome(data: String, page: Int): List<SearchResponse> {
        val variables = mutableMapOf<String, Any?>("page" to page, "perPage" to 20)
        when (data) {
            "anilist_trending" -> variables["sort"] = listOf("TRENDING_DESC", "POPULARITY_DESC")
            "anilist_popular" -> variables["sort"] = listOf("POPULARITY_DESC")
            "anilist_top" -> variables["sort"] = listOf("SCORE_DESC")
            "anilist_action" -> { variables["sort"] = listOf("TRENDING_DESC"); variables["genreIn"] = listOf("Action") }
            "anilist_fantasy" -> { variables["sort"] = listOf("TRENDING_DESC"); variables["genreIn"] = listOf("Fantasy") }
            "anilist_comedy" -> { variables["sort"] = listOf("TRENDING_DESC"); variables["genreIn"] = listOf("Comedy") }
            "anilist_drama" -> { variables["sort"] = listOf("TRENDING_DESC"); variables["genreIn"] = listOf("Drama") }
            "anilist_scifi" -> { variables["sort"] = listOf("TRENDING_DESC"); variables["genreIn"] = listOf("Sci-Fi") }
            "anilist_romance" -> { variables["sort"] = listOf("TRENDING_DESC"); variables["genreIn"] = listOf("Romance") }
            "anilist_movies" -> { variables["sort"] = listOf("SCORE_DESC"); variables["format"] = "MOVIE" }
            else -> variables["sort"] = listOf("TRENDING_DESC")
        }
        val response = parseJson<AniListResponse>(anilistQuery(ANILIST_HOMEPAGE, variables))
        return response.data?.Page?.media?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private suspend fun fetchTmdbHome(data: String, page: Int): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        when (data) {
            "tmdb_trending" -> {
                val json = tmdbGet("/trending/all/day?page=$page")
                parseJson<TmdbResponse>(json).results?.forEach { item ->
                    val type = item.media_type ?: return@forEach
                    if (type == "movie" || type == "tv") { item.toSearchResponse(type)?.let { results.add(it) } }
                }
            }
            "tmdb_popular_movies" -> { parseJson<TmdbResponse>(tmdbGet("/movie/popular?page=$page")).results?.forEach { it.toSearchResponse("movie")?.let { r -> results.add(r) } } }
            "tmdb_popular_tv" -> { parseJson<TmdbResponse>(tmdbGet("/tv/popular?page=$page")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
            "tmdb_top_movies" -> { parseJson<TmdbResponse>(tmdbGet("/movie/top_rated?page=$page")).results?.forEach { it.toSearchResponse("movie")?.let { r -> results.add(r) } } }
            "tmdb_top_tv" -> { parseJson<TmdbResponse>(tmdbGet("/tv/top_rated?page=$page")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
            "tmdb_netflix" -> { parseJson<TmdbResponse>(tmdbGet("/discover/tv?page=$page&with_networks=213")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
            "tmdb_amazon" -> { parseJson<TmdbResponse>(tmdbGet("/discover/tv?page=$page&with_networks=1024")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
            "tmdb_disney" -> { parseJson<TmdbResponse>(tmdbGet("/discover/tv?page=$page&with_networks=2739")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
            "tmdb_hbo" -> { parseJson<TmdbResponse>(tmdbGet("/discover/tv?page=$page&with_networks=49")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
            "tmdb_korean" -> { parseJson<TmdbResponse>(tmdbGet("/discover/tv?page=$page&with_original_language=ko")).results?.forEach { it.toSearchResponse("tv")?.let { r -> results.add(r) } } }
        }
        return results
    }

    private suspend fun fetchCinemetaHome(data: String, page: Int): List<SearchResponse> {
        // Parse cinemeta_xxx_movies or cinemeta_xxx_series
        // Format: cinemeta_{catalogId}_{type}
        val parts = data.split("_").toMutableList()
        val type = if (parts.removeLast() == "movies") "movie" else "series"
        val catalogId = parts.drop(1).joinToString("_") // drop "cinemeta", join rest
        val skip = (page - 1) * 50
        val url = "$CINEMETA/$catalogId/catalog/$type/$catalogId/skip=$skip.json"
        val json = try { app.get(url, timeout = 30L).text } catch (_: Throwable) { return emptyList() }
        val resp = try { parseJson<CinemetaResponse>(json) } catch (_: Throwable) { return emptyList() }
        return resp.metas?.mapNotNull { meta ->
            val imdbId = meta.imdb_id ?: return@mapNotNull null
            val title = meta.name ?: return@mapNotNull null
            newMovieSearchResponse(title, "$CINEMETA_META/meta/$type/$imdbId.json",
                if (type == "movie") TvType.Movie else TvType.TvSeries) {
                this.posterUrl = meta.poster
            }
        } ?: emptyList()
    }

    private fun AniListMedia.toSearchResponse(): SearchResponse? {
        val id = id ?: return null
        val title = title?.english ?: title?.romaji ?: return null
        val poster = coverImage?.extraLarge ?: coverImage?.large
        return newAnimeSearchResponse(title, "$mainUrl/anilist/$id", TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun TmdbItem.toSearchResponse(mediaType: String): SearchResponse? {
        val id = id ?: return null
        val title = if (mediaType == "movie") title ?: name else name ?: title
        if (title.isNullOrBlank()) return null
        val poster = poster_path?.let { "$TMDB_IMG$it" }
        val url = "https://api.themoviedb.org/3/$mediaType/$id"
        return if (mediaType == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        runAllAsync(
            {
                if (getSetting(KEY_ANILIST, true)) {
                    try {
                        val response = parseJson<AniListResponse>(
                            anilistQuery(ANILIST_SEARCH, mapOf("search" to query, "page" to 1, "perPage" to 15))
                        )
                        response.data?.Page?.media?.mapNotNull { it.toSearchResponse() }?.let { results.addAll(it) }
                    } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
                }
            },
            {
                if (getSetting(KEY_TMDB, true)) {
                    try {
                        val json = tmdbGet("/search/multi?query=${URLEncoder.encode(query, "UTF-8")}&page=1")
                        parseJson<TmdbResponse>(json).results?.forEach { item ->
                            val type = item.media_type ?: return@forEach
                            if (type == "movie" || type == "tv") {
                                item.toSearchResponse(type)?.let { results.add(it) }
                            }
                        }
                    } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
                }
            }
        )
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        return when {
            url.contains("/anilist/") -> loadAniList(url)
            url.contains("/3/movie/") -> loadTmdb(url, "movie")
            url.contains("/3/tv/") -> loadTmdb(url, "tv")
            url.contains("/meta/movie/") -> loadCinemeta(url, "movie")
            url.contains("/meta/series/") -> loadCinemeta(url, "series")
            else -> null
        }
    }

    private suspend fun loadAniList(url: String): LoadResponse? {
        val anilistId = Regex("""/anilist/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val media = parseJson<AniListResponse>(anilistQuery(ANILIST_INFO, mapOf("id" to anilistId))).data?.Media ?: return null
        val title = media.title?.english ?: media.title?.romaji ?: "Unknown"
        val jpTitle = media.title?.romaji
        val posterUrl = media.coverImage?.extraLarge ?: media.coverImage?.large
        val plot = media.description?.replace(Regex("<[^>]*>"), "")
        val year = media.seasonYear
        val tags = media.genres ?: emptyList()
        val format = media.format
        val tvType = when (format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA" -> TvType.OVA
            else -> TvType.Anime
        }

        val aniZip = try {
            val resp = app.get("$ANIZIP_API/mappings?anilist_id=$anilistId")
            if (resp.isSuccessful) parseJson<AniZipResponse>(resp.text) else null
        } catch (_: Exception) { null }

        val malId = aniZip?.mappings?.mal_id
        val kitsuId = aniZip?.mappings?.kitsu_id
        val imdbId = aniZip?.mappings?.imdb_id

        var totalEps = media.episodes ?: aniZip?.episodes?.size ?: 0
        if (format == "MOVIE" && totalEps == 0) totalEps = 1
        if (totalEps == 0) totalEps = 1

        val episodes = mutableListOf<Episode>()
        for (i in 1..totalEps) {
            val epData = aniZip?.episodes?.get(i.toString())
            val epTitle = epData?.title?.get("en") ?: epData?.title?.get("ja") ?: "Episode $i"
            val linkData = LinkData(
                source = "anilist", anilistId = anilistId, malId = malId,
                kitsuId = kitsuId, imdbId = imdbId, title = title, jpTitle = jpTitle,
                episode = i, season = null, year = year, format = format
            ).toJson()
            episodes.add(newEpisode(linkData) {
                this.episode = i
                this.name = epTitle
                this.posterUrl = epData?.image ?: posterUrl
            })
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = media.bannerImage
            this.year = year
            this.plot = plot
            this.tags = tags
            addAniListId(anilistId)
            addEpisodes(DubStatus.Subbed, episodes)
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    private suspend fun loadTmdb(url: String, type: String): LoadResponse? {
        val tmdbId = Regex("""/3/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)?.toIntOrNull() ?: return null
        val detail = parseJson<TmdbDetail>(tmdbGet("/$type/$tmdbId"))
        val title = if (type == "movie") detail.title ?: detail.name ?: "Unknown" else detail.name ?: detail.title ?: "Unknown"
        val posterUrl = detail.poster_path?.let { "$TMDB_IMG$it" }
        val bannerUrl = detail.backdrop_path?.let { "$TMDB_IMG_ORIG$it" }
        val plot = detail.overview
        val year = (if (type == "movie") detail.release_date else detail.first_air_date)?.substringBefore("-")?.toIntOrNull()
        val tags = detail.genres?.mapNotNull { it.name } ?: emptyList()

        val extIds = parseJson<TmdbExternalIds>(tmdbGet("/$type/$tmdbId/external_ids"))
        val imdbId = extIds.imdb_id

        return if (type == "movie") {
            val linkData = LinkData(
                source = "tmdb", tmdbId = tmdbId, imdbId = imdbId,
                title = title, episode = 1, season = null, year = year, format = "MOVIE"
            ).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                if (detail.vote_average != null) this.score = Score.from10(detail.vote_average.toString())
                if (imdbId != null) addImdbId(imdbId)
                addTMDbId(tmdbId.toString())
            }
        } else {
            val seasons = detail.seasons?.filter { (it.season_number ?: 0) > 0 } ?: emptyList()
            val episodes = mutableListOf<Episode>()
            for (season in seasons) {
                val seasonNum = season.season_number ?: continue
                val epCount = season.episode_count ?: 0
                for (ep in 1..epCount) {
                    val linkData = LinkData(
                        source = "tmdb", tmdbId = tmdbId, imdbId = imdbId,
                        title = title, episode = ep, season = seasonNum, year = year, format = "TV"
                    ).toJson()
                    episodes.add(newEpisode(linkData) {
                        this.season = seasonNum
                        this.episode = ep
                        this.name = "${season.name ?: "Season $seasonNum"} E$ep"
                    })
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = bannerUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                if (detail.vote_average != null) this.score = Score.from10(detail.vote_average.toString())
                if (imdbId != null) addImdbId(imdbId)
                addTMDbId(tmdbId.toString())
            }
        }
    }

    private suspend fun loadCinemeta(url: String, type: String): LoadResponse? {
        val imdbId = Regex("""/meta/(?:movie|series)/([^/.]+)""").find(url)?.groupValues?.get(1) ?: return null
        val json = app.get("$CINEMETA_META/meta/$type/$imdbId.json", timeout = 30L).text
        val meta = try { parseJson<CinemetaResponse>(json).meta } catch (_: Throwable) { null } ?: return null
        val title = meta.name ?: "Unknown"
        val posterUrl = meta.poster
        val plot = meta.description
        val year = meta.year?.toIntOrNull()
        val tags = meta.genres ?: emptyList()

        return if (type == "movie") {
            val linkData = LinkData(
                source = "cinemeta", imdbId = imdbId, title = title,
                episode = 1, season = null, year = year, format = "MOVIE"
            ).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = meta.background
                this.year = year
                this.plot = plot
                this.tags = tags
                addImdbId(imdbId)
            }
        } else {
            val episodes = mutableListOf<Episode>()
            meta.videos?.forEach { vid ->
                val season = vid.season ?: 1
                val ep = vid.episode ?: return@forEach
                val linkData = LinkData(
                    source = "cinemeta", imdbId = imdbId, title = title,
                    episode = ep, season = season, year = year, format = "TV"
                ).toJson()
                episodes.add(newEpisode(linkData) {
                    this.season = season
                    this.episode = ep
                    this.name = vid.title ?: "S${season}E$ep"
                    this.posterUrl = vid.thumbnail
                })
            }
            if (episodes.isEmpty()) {
                val linkData = LinkData(
                    source = "cinemeta", imdbId = imdbId, title = title,
                    episode = 1, season = 1, year = year, format = "TV"
                ).toJson()
                episodes.add(newEpisode(linkData) { this.episode = 1 })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = meta.background
                this.year = year
                this.plot = plot
                this.tags = tags
                addImdbId(imdbId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = try { parseJson<LinkData>(data) } catch (_: Throwable) { return false }
        val isMovie = linkData.format == "MOVIE"
        val stremioId: String? = when {
            isMovie -> linkData.imdbId
            linkData.source == "anilist" && linkData.kitsuId != null -> "kitsu:${linkData.kitsuId}"
            linkData.imdbId != null -> linkData.imdbId
            else -> null
        }

        val torrentioOn = getSetting(KEY_TORRENTIO, true)
        val torrentsDbOn = getSetting(KEY_TORRENTSDB, true)
        val animetoshoOn = getSetting(KEY_ANIMETOSHO, true)
        val addons = getStremioAddons()
        val debridProvider = getStringSetting(KEY_DEBRID_PROVIDER)
        val debridKey = getStringSetting(KEY_DEBRID_KEY)
        val hasDebrid = debridProvider.isNotBlank() && debridKey.isNotBlank() && debridProvider != "None"

        runAllAsync(
            { if (torrentioOn) try { invokeTorrentio(stremioId, linkData, isMovie, hasDebrid, debridProvider, debridKey, callback) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } } },
            { if (torrentsDbOn) try { invokeTorrentsDB(stremioId, linkData, isMovie, callback) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } } },
            { if (animetoshoOn && linkData.source == "anilist") try { invokeAnimetosho(linkData, callback) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } } },
            { try { invokeCustomStremioAddons(addons, stremioId, linkData, isMovie, subtitleCallback, callback) } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } } }
        )
        return true
    }

    private suspend fun invokeTorrentio(
        stremioId: String?, linkData: LinkData, isMovie: Boolean,
        hasDebrid: Boolean, debridProvider: String, debridKey: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = stremioId ?: return
        val debridParam = if (hasDebrid) "/${debridProvider.lowercase()}=$debridKey" else "/limit=4"
        val url = if (isMovie) {
            "$TORRENTIO_BASE$debridParam/stream/movie/$id.json"
        } else if (linkData.source == "anilist") {
            "$TORRENTIO_BASE$debridParam/stream/series/$id:${linkData.episode}.json"
        } else {
            "$TORRENTIO_BASE$debridParam/stream/series/$id:${linkData.season ?: 1}:${linkData.episode}.json"
        }
        if (hasDebrid) fetchStremioStreamsWithDebrid("Torrentio+", url, callback)
        else fetchStremioStreams("Torrentio", url, callback)
    }

    private suspend fun invokeTorrentsDB(stremioId: String?, linkData: LinkData, isMovie: Boolean, callback: (ExtractorLink) -> Unit) {
        val id = stremioId ?: return
        val url = if (isMovie) {
            "$TORRENTSDB_BASE/$TORRENTSDB_CFG/stream/movie/$id.json"
        } else if (linkData.source == "anilist") {
            "$TORRENTSDB_BASE/$TORRENTSDB_CFG/stream/series/$id:${linkData.episode}.json"
        } else {
            "$TORRENTSDB_BASE/$TORRENTSDB_CFG/stream/series/$id:${linkData.season ?: 1}:${linkData.episode}.json"
        }
        fetchStremioStreams("TorrentsDB", url, callback)
    }

    private suspend fun invokeAnimetosho(linkData: LinkData, callback: (ExtractorLink) -> Unit) {
        val aniZip = try {
            val resp = app.get("$ANIZIP_API/mappings?anilist_id=${linkData.anilistId}")
            if (resp.isSuccessful) parseJson<AniZipResponse>(resp.text) else null
        } catch (_: Exception) { null } ?: return
        val anidbEid = aniZip.episodes?.get(linkData.episode.toString())?.anidbEid ?: return
        val res = try { app.get("$ANIMETOSHO_API/json/v1/episodes/$anidbEid", timeout = 100L).parsedSafe<AnimetoshoResponse>() } catch (_: Throwable) { null } ?: return
        val items = (res.data?.entries ?: res.data?.releases ?: emptyList())
            .filter { !it.magnet.isNullOrBlank() }
            .sortedByDescending { it.seeders ?: -1 }
        for (it in items) {
            val magnet = it.magnet ?: continue
            val title = it.title ?: it.name ?: ""
            val seeders = it.seeders ?: 0
            callback.invoke(
                newExtractorLink("Animetosho🧲", "Animetosho 🧲 | 👤 $seeders | $title", magnet, ExtractorLinkType.MAGNET) {
                    this.quality = getQualityFromString(title)
                }
            )
        }
    }

    private suspend fun invokeCustomStremioAddons(
        addons: List<StremioAddon>, stremioId: String?, linkData: LinkData, isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        // For custom Stremio addons, prefer IMDB ID (most addons support it).
        // Fall back to kitsu: ID only if IMDB is not available.
        val id = linkData.imdbId ?: stremioId ?: return
        addons.amap { addon ->
            try {
                val base = addon.url.trimEnd('/').replace("/manifest.json", "")
                val url = if (isMovie) {
                    "$base/stream/movie/$id.json"
                } else {
                    "$base/stream/series/$id:${linkData.season ?: 1}:${linkData.episode}.json"
                }
                fetchStremioStreamsUniversal(addon.name, url, subtitleCallback, callback)
            } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
        }
    }

    private suspend fun fetchStremioStreams(sourceName: String, url: String, callback: (ExtractorLink) -> Unit) {
        val res = try { app.get(url, timeout = 200L).parsedSafe<StremioStreamResponse>() } catch (_: Throwable) { null } ?: return
        val streams = res.streams ?: return
        for (stream in streams) {
            val infoHash = stream.infoHash ?: continue
            val rawTitle = stream.title ?: stream.name ?: ""
            val seeders = getSeedersFromTitle(rawTitle)
            if (seeders < 25 && seeders > 0) continue
            val magnet = buildMagnet(infoHash, stream.fileIdx, stream.sources) ?: continue
            callback.invoke(
                newExtractorLink("$sourceName🧲", "$sourceName 🧲 | 👤 $seeders | ${simplifyTitle(rawTitle)}".trim(),
                    magnet, ExtractorLinkType.MAGNET) { this.quality = getQualityFromString(rawTitle) }
            )
        }
    }

    private suspend fun fetchStremioStreamsWithDebrid(sourceName: String, url: String, callback: (ExtractorLink) -> Unit) {
        val res = try { app.get(url, timeout = 200L).parsedSafe<StremioStreamResponse>() } catch (_: Throwable) { null } ?: return
        val streams = res.streams ?: return
        for (stream in streams) {
            val streamUrl = stream.url ?: continue
            val rawTitle = stream.title ?: stream.name ?: stream.description ?: ""
            callback.invoke(
                newExtractorLink(sourceName, "$sourceName | ${simplifyTitle(rawTitle)}".trim(), streamUrl, INFER_TYPE) {
                    this.quality = getQualityFromString(rawTitle)
                }
            )
        }
    }

    private suspend fun fetchStremioStreamsUniversal(
        sourceName: String, url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = try { app.get(url, timeout = 200L).parsedSafe<StremioStreamResponse>() } catch (_: Throwable) { null } ?: return
        val streams = res.streams ?: return
        for (stream in streams) {
            val rawTitle = stream.title ?: stream.description ?: stream.name ?: ""

            // Magnet stream
            if (stream.infoHash != null && stream.infoHash.isNotBlank()) {
                val magnet = buildMagnet(stream.infoHash, stream.fileIdx, stream.sources) ?: continue
                callback.invoke(
                    newExtractorLink("$sourceName 🧲", "$sourceName 🧲 | ${simplifyTitle(rawTitle)}", magnet, ExtractorLinkType.MAGNET) {
                        this.quality = getQualityFromString(rawTitle)
                    }
                )
            }

            // Direct URL stream
            if (stream.url != null && stream.url.isNotBlank()) {
                var streamUrl = stream.url

                // Resolve redirect URLs (e.g. notorrent addon uses /redirect?p=...)
                if (streamUrl.contains("/redirect")) {
                    try {
                        val resp = app.get(streamUrl, allowRedirects = false)
                        val location = resp.headers?.get("location") ?: ""
                        if (location.isNotBlank()) {
                            // The redirect target may contain the actual m3u8 URL in a query param
                            // e.g. https://host/vid1.php?url=/vid/movies/720p/tt123.m3u8
                            val urlParam = Regex("""[?&]url=([^&]+\.m3u8)""").find(location)?.groupValues?.get(1)
                            if (urlParam != null) {
                                val base = Regex("""(https?://[^/]+)""").find(location)?.groupValues?.get(1) ?: ""
                                streamUrl = "$base$urlParam"
                            } else {
                                streamUrl = location
                            }
                        }
                    } catch (e: Throwable) { e.message?.let { Log.d("Plugin", it) } }
                }
                val bh = stream.behaviorHints
                val headers = mutableMapOf<String, String>()
                bh?.proxyHeaders?.request?.let { req ->
                    req.Referer?.let { headers["Referer"] = it }
                    req.Origin?.let { headers["Origin"] = it }
                    req.userAgent?.let { headers["User-Agent"] = it }
                }
                bh?.headers?.forEach { (k, v) -> if (v.isNotBlank()) headers.putIfAbsent(k, v) }

                val type = if (streamUrl.contains(".m3u8") || streamUrl.contains("hls")) ExtractorLinkType.M3U8 else INFER_TYPE
                callback.invoke(
                    newExtractorLink(sourceName, "[$sourceName] ${simplifyTitle(rawTitle)}", streamUrl, type) {
                        this.quality = getQualityFromString(rawTitle)
                        if (headers.isNotEmpty()) this.headers = headers
                    }
                )
            }
        }
    }
}
