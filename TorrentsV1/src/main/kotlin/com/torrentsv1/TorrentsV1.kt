package com.torrentsv1

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity.activity
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import com.raghav.donation.DonationManager

private const val ANILIST_URL = "https://graphql.anilist.co"
private const val ANIZIP_API = "https://api.ani.zip"
private const val TMDB_API = "https://api.themoviedb.org/3"
private val TMDB_KEY get() = BuildConfig.TMDB_KEY
private const val TMDB_IMG = "https://image.tmdb.org/t/p/w500"
private const val TMDB_IMG_ORIG = "https://image.tmdb.org/t/p/original"

private const val TORRENTIO_BASE = "https://torrentio.strem.fun"
private const val TORRENTSDB_BASE = "https://torrentsdb.com"
private const val TORRENTSDB_CFG = "eyJsaW1pdCI6IjMiLCJkZWJyaWRvcHRpb25zIjpbIm5vZG93bmxvYWRsaW5rcyJdfQ=="

// the old animetosho.xyz domain now bounces to the main site which dropped
// the json feeds, everything lives on the feed host now
private const val ANIMETOSHO_API = "https://feed.animetosho.net"

// nyaa.si is unreachable on many isp networks, nyaa.net mirrors the same
// index so the domains are tried until one answers and the winner is kept
private val NYAA_DOMAINS = listOf("https://nyaa.si", "https://nyaa.net")
private const val NYAA_TIMEOUT = 12L
private const val NYAA_TOTAL_MS = 40_000L

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val ANILIST_HEADERS = mapOf(
    "Accept" to "application/json",
    "Content-Type" to "application/json",
    "User-Agent" to BROWSER_UA,
    "Origin" to "https://anilist.co",
    "Referer" to "https://anilist.co/"
)

private val HARDCODED_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce"
)

internal const val KEY_ANILIST = "torrentsv1_anilist"
internal const val KEY_TMDB = "torrentsv1_tmdb"
internal const val KEY_TMDB_ON_TOP = "torrentsv1_tmdb_on_top"
internal const val KEY_TORRENTIO = "torrentsv1_torrentio"
internal const val KEY_TORRENTSDB = "torrentsv1_torrentsdb"
internal const val KEY_ANIMETOSHO = "torrentsv1_animetosho"
internal const val KEY_NYAA = "torrentsv1_nyaa"
internal const val KEY_STREMIO_ADDONS = "torrentsv1_stremio_addons"
internal const val KEY_DEBRID_PROVIDER = "torrentsv1_debrid_provider"
internal const val KEY_DEBRID_KEY = "torrentsv1_debrid_key"

internal fun getSetting(key: String, default: Boolean): Boolean =
    try { CloudStreamApp.getKey(key) ?: default } catch (_: Throwable) { default }

internal fun setSetting(key: String, value: Boolean) {
    try { CloudStreamApp.setKey(key, value) } catch (_: Throwable) {}
}

internal fun getStringSetting(key: String): String =
    try { CloudStreamApp.getKey<String>(key) ?: "" } catch (_: Throwable) { "" }

internal fun setStringSetting(key: String, value: String) {
    try { CloudStreamApp.setKey(key, value) } catch (_: Throwable) {}
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
    try { CloudStreamApp.setKey(KEY_STREMIO_ADDONS, raw) } catch (_: Throwable) {}
}

data class StremioAddon(val name: String, val url: String, val type: String)

private fun buildMagnet(
    infoHash: String?, fileIdx: Int?, sourceTrackers: List<String>?, displayName: String? = null
): String? {
    if (infoHash.isNullOrBlank()) return null
    val trackers = LinkedHashSet<String>()
    sourceTrackers?.forEach { src ->
        val t = if (src.startsWith("tracker:", true)) src.substringAfter("tracker:").trim() else src
        if (t.isNotBlank()) trackers.add(t)
    }
    HARDCODED_TRACKERS.forEach { trackers.add(it) }
    val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
    sb.append("&dn=").append(URLEncoder.encode(displayName ?: infoHash, "UTF-8"))
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

private fun getSeedersFromTitle(title: String?): Int? {
    if (title.isNullOrBlank()) return null
    return Regex("[\uD83D\uDC64\uD83D\uDC65]\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()
}

private fun simplifyTitle(title: String?): String {
    if (title.isNullOrBlank()) return ""
    return title.replace(Regex("[\uD83D\uDC64\uD83D\uDC65\u2699\uFE0F\uD83D\uDCBE]"), " ")
        .replace(Regex("\\s+"), " ").trim()
}

// anilist rate limits hard, so identical queries share one in flight request
// and every answer is reused for a short while instead of hammering the api
private val anilistCache = mutableMapOf<String, Pair<String, Long>>()
private const val ANILIST_CACHE_TTL = 10 * 60 * 1000L
private val anilistLocks = mutableMapOf<String, Mutex>()

private suspend fun anilistQuery(query: String, variables: Map<String, Any?>): String {
    val cacheKey = "$query|${variables.toJson()}"
    val now = System.currentTimeMillis()
    anilistCache[cacheKey]?.let { (cached, time) ->
        if (now - time < ANILIST_CACHE_TTL) return cached
    }

    val lock = synchronized(anilistLocks) { anilistLocks.getOrPut(cacheKey) { Mutex() } }
    if (lock.isLocked) {
        repeat(50) {
            delay(100)
            anilistCache[cacheKey]?.let { (cached, time) ->
                if (now - time < ANILIST_CACHE_TTL) return cached
            }
        }
    }
    anilistCache[cacheKey]?.let { (cached, time) ->
        if (now - time < ANILIST_CACHE_TTL) return cached
    }

    val requestBody = mapOf("query" to query, "variables" to variables)
        .toJson().toRequestBody("application/json".toMediaTypeOrNull())
    try {
        val text = app.post(
            ANILIST_URL, headers = ANILIST_HEADERS, requestBody = requestBody, timeout = 15L
        ).text
        if (text.isNotBlank() && !text.contains("\"errors\"")) {
            if (anilistCache.size > 200) anilistCache.clear()
            anilistCache[cacheKey] = text to now
            return text
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
    }

    // a stale answer beats no answer when the api hiccups
    anilistCache[cacheKey]?.let { (cached, _) -> return cached }
    throw Exception("AniList query failed")
}

private suspend fun tmdbGet(path: String): String {
    val sep = if (path.contains("?")) "&" else "?"
    return app.get("$TMDB_API$path${sep}api_key=$TMDB_KEY").text
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioStreamResponse(val streams: List<StremioStream>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitleResponse(val subtitles: List<StremioSubtitle>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StremioSubtitle(val url: String? = null, val lang: String? = null, val id: String? = null)
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
    val genres: List<String>? = null, val format: String? = null, val status: String? = null,
    val nextAiringEpisode: AniListNextAiring? = null
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AniListNextAiring(val episode: Int? = null)
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

@JsonIgnoreProperties(ignoreUnknown = true) data class AniZipResponse(val mappings: AniZipMappings? = null, val episodes: Map<String, AniZipEpisode>? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniZipMappings(val mal_id: Int? = null, val kitsu_id: Int? = null, val imdb_id: String? = null)
@JsonIgnoreProperties(ignoreUnknown = true) data class AniZipEpisode(val anidbEid: Int? = null, val title: Map<String, String>? = null, val image: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimetoshoEpisodeResponse(val data: AnimetoshoEpisodeData? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimetoshoEpisodeData(val releases: List<AnimetoshoRelease>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimetoshoListResponse(val data: List<AnimetoshoRelease>? = null)
@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimetoshoRelease(
    val title: String? = null, val magnet: String? = null,
    val seeders: Int? = null, val size_bytes: Long? = null, val is_batch: Boolean? = null
)

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
            nextAiringEpisode { episode }
        }
    }
""".trimIndent()

// animetosho search results carry everything in the release name,
// so these patterns pull the episode number out and drop packs and batches
private val batchWords = Regex(
    """batch|complete|collection|\bbox\s?set|\bvol(ume)?s?\.?\s*\d"""
)
private val tildeRange = Regex("""\d\s*~\s*\d""")

// a digit pair around a dash only reads as a range when it climbs and the
// left number stands alone, "part 3 - 01" and "s2 - 12" are season tags in
// front of an episode number and must survive the batch check
private val dashRange = Regex(
    """(?<!season )(?<!part )(?<!cour )(?<!stage )(?<!phase )(?<!series )(?<![a-z0-9])(\d{1,4})\s*-\s*(\d{1,4})"""
)

private fun isBatchTitle(title: String): Boolean {
    val lower = title.lowercase()
    if (batchWords.containsMatchIn(lower)) return true
    if (tildeRange.containsMatchIn(lower)) return true
    for (m in dashRange.findAll(lower)) {
        val from = m.groupValues[1].toIntOrNull() ?: continue
        val to = m.groupValues[2].toIntOrNull() ?: continue
        if (to > from) return true
    }
    return false
}

private val episodeMarkers = listOf(
    Regex("""\s-\s0*(\d{1,4})(?:v\d+)?\b"""),
    Regex("""\bep(?:isode)?\.?\s*0*(\d{1,4})\b"""),
    Regex("""\be0*(\d{1,4})\b"""),
    Regex("""\s0*(\d{1,4})\s*\[""")
)

private fun episodeMatch(title: String): Pair<Int, MatchResult>? {
    if (isBatchTitle(title)) return null
    val lower = title.lowercase()
    for (marker in episodeMarkers) {
        val found = marker.findAll(lower).mapNotNull { m ->
            m.groupValues[1].toIntOrNull()?.let { it to m }
        }.toList()
        if (found.isNotEmpty() && found.map { it.first }.distinct().size == 1) return found.first()
    }
    return null
}

private fun cleanForMatch(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

// anilist writes sequels as "2nd Season" while release names say "S2", the
// compact twin of a title catches those uploads
private fun compactSeasonTitle(s: String): String = s
    .replace(Regex("""(\d+)(?:st|nd|rd|th)\s+[Ss]eason"""), "S$1")
    .replace(Regex("""[Ss]eason\s+(\d+)"""), "S$1")
    .replace(":", "")

private fun releaseMatchesTitle(releaseTitle: String, animeTitle: String): Boolean {
    val needle = cleanForMatch(animeTitle)
    if (needle.isBlank()) return false
    return cleanForMatch(releaseTitle).contains(needle)
}

private data class NyaaItem(
    val title: String, val infoHash: String, val seeders: Int
)

private fun unescapeXmlEntities(s: String): String = s
    .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
    .replace(Regex("&#x([0-9A-Fa-f]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&amp;", "&")

private fun parseNyaaItems(xml: String): List<NyaaItem> {
    if (!xml.contains("<item>")) return emptyList()
    return Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { blockMatch ->
        val block = blockMatch.groupValues[1]
        fun tag(name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
        val title = tag("title")?.let { unescapeXmlEntities(it.trim()) } ?: return@mapNotNull null
        val infoHash = tag("nyaa:infoHash")?.trim()?.lowercase() ?: return@mapNotNull null
        if (infoHash.length !in 40..64) return@mapNotNull null
        if (tag("nyaa:remake")?.trim().equals("yes", ignoreCase = true)) return@mapNotNull null
        val seeders = tag("nyaa:seeders")?.trim()?.toIntOrNull() ?: 0
        NyaaItem(title, infoHash, seeders)
    }.toList()
}

private fun titlePattern(title: String): Regex? {
    val words = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    return words.joinToString("[^a-z0-9]*") { Regex.escape(it) }.toRegex()
}

// "One Piece - Gyojin Tou-hen - 01" is episode 1 of the fish-man island
// re-edit and not of the show that was picked, the episode number has to sit
// right behind the show name for a release to count
private fun episodeAfterShowName(releaseTitle: String, marker: MatchResult, animeTitle: String): Boolean {
    val pattern = titlePattern(animeTitle) ?: return false
    val prefix = releaseTitle.lowercase().substring(0, marker.range.first)
        .trimEnd { !it.isLetterOrDigit() }
    val match = pattern.find(prefix) ?: return false
    return match.range.last == prefix.length - 1
}

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

    private val anilistSections = listOf(
        "anilist_trending" to "Anime: Trending",
        "anilist_popular" to "Anime: Popular",
        "anilist_top" to "Anime: Top Rated",
        "anilist_action" to "Anime: Action",
        "anilist_fantasy" to "Anime: Fantasy",
        "anilist_comedy" to "Anime: Comedy",
        "anilist_drama" to "Anime: Drama",
        "anilist_scifi" to "Anime: Sci-Fi",
        "anilist_romance" to "Anime: Romance",
        "anilist_movies" to "Anime: Movies"
    )

    private val tmdbSections = listOf(
        "tmdb_trending" to "Trending Movies & TV",
        "tmdb_popular_movies" to "Popular Movies",
        "tmdb_popular_tv" to "Popular TV Shows",
        "tmdb_top_movies" to "Top Rated Movies",
        "tmdb_top_tv" to "Top Rated TV Shows",
        "tmdb_netflix" to "Netflix",
        "tmdb_amazon" to "Amazon Prime",
        "tmdb_disney" to "Disney+",
        "tmdb_hbo" to "HBO",
        "tmdb_korean" to "Korean Shows"
    )

    // the chosen catalog leads the home page, read on every access so a
    // restart is all it takes to swap the order
    override val mainPage: List<MainPageData>
        get() = if (getSetting(KEY_TMDB_ON_TOP, false)) mainPageOf(*(tmdbSections + anilistSections).toTypedArray())
        else mainPageOf(*(anilistSections + tmdbSections).toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val items = try {
            when {
                request.data.startsWith("anilist_") -> {
                    if (!getSetting(KEY_ANILIST, true)) emptyList()
                    else {
                        isAniListDown()
                        fetchAniListHome(request.data, page)
                    }
                }
                request.data.startsWith("tmdb_") -> {
                    if (!getSetting(KEY_TMDB, true)) emptyList()
                    else fetchTmdbHome(request.data, page)
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
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
        return try {
            val response = parseJson<AniListResponse>(anilistQuery(ANILIST_HOMEPAGE, variables))
            val media = response.data?.Page?.media ?: emptyList()
            if (media.isNotEmpty()) homePageCache["${data}_$page"] = media
            media.mapNotNull { it.toSearchResponse() }
        } catch (_: Throwable) {
            homePageCache["${data}_$page"]?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        }
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
                        isAniListDown()
                        val response = parseJson<AniListResponse>(
                            anilistQuery(ANILIST_SEARCH, mapOf("search" to query, "page" to 1, "perPage" to 15))
                        )
                        response.data?.Page?.media?.mapNotNull { it.toSearchResponse() }?.let { results.addAll(it) }
                    } catch (_: Throwable) {}
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
                    } catch (_: Throwable) {}
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
            else -> null
        }
    }

    private suspend fun loadAniList(url: String): LoadResponse? {
        val anilistId = Regex("""/anilist/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        isAniListDown()
        val media = try {
            parseJson<AniListResponse>(anilistQuery(ANILIST_INFO, mapOf("id" to anilistId))).data?.Media
        } catch (_: Throwable) { null } ?: return null
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
        val showStatus = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        val aniZip = try {
            val resp = app.get("$ANIZIP_API/mappings?anilist_id=$anilistId")
            if (resp.isSuccessful) parseJson<AniZipResponse>(resp.text) else null
        } catch (_: Exception) { null }

        val malId = aniZip?.mappings?.mal_id
        val kitsuId = aniZip?.mappings?.kitsu_id
        val imdbId = aniZip?.mappings?.imdb_id

        val anizipEpCount = aniZip?.episodes?.keys?.count { it.toIntOrNull() != null } ?: 0
        var totalEps = media.episodes ?: anizipEpCount
        // anilist keeps counting specials the tracker never lists as aired
        media.nextAiringEpisode?.episode?.let { nextEp ->
            if (totalEps >= nextEp) totalEps = nextEp - 1
        }
        if (format == "MOVIE" && totalEps == 0) totalEps = 1
        if (totalEps == 0) totalEps = 1

        val episodes = mutableListOf<Episode>()
        for (i in 1..totalEps) {
            val epData = aniZip?.episodes?.get(i.toString())
            val epTitle = epData?.title?.get("en") ?: epData?.title?.get("ja")
                ?: epData?.title?.get("x-jat") ?: "Episode $i"
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
            if (media.averageScore != null) this.score = Score.from10((media.averageScore / 10.0).toString())
            this.showStatus = showStatus
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
        val nyaaOn = getSetting(KEY_NYAA, true)
        val addons = getStremioAddons()
        val debridProvider = getStringSetting(KEY_DEBRID_PROVIDER)
        val debridKey = getStringSetting(KEY_DEBRID_KEY)
        val hasDebrid = debridProvider.isNotBlank() && debridKey.isNotBlank() && debridProvider != "None"

        runAllAsync(
            { if (torrentioOn) try { invokeTorrentio(stremioId, linkData, isMovie, hasDebrid, debridProvider, debridKey, callback) } catch (_: Throwable) {} },
            { if (torrentsDbOn) try { invokeTorrentsDB(stremioId, linkData, isMovie, callback) } catch (_: Throwable) {} },
            { if (animetoshoOn && linkData.source == "anilist") try { invokeAnimetosho(linkData, callback) } catch (_: Throwable) {} },
            { if (nyaaOn && linkData.source == "anilist") try { invokeNyaa(linkData, callback) } catch (_: Throwable) {} },
            { try { invokeCustomStremioAddons(addons, stremioId, linkData, isMovie, subtitleCallback, callback) } catch (_: Throwable) {}}
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

    // animetosho matches releases to episodes by name alone, so the feed of
    // one episode still carries batches, specials and sub series of the same
    // franchise, every release is checked against the show and episode that
    // was actually picked
    private fun matchesRequestedEpisode(release: AnimetoshoRelease, linkData: LinkData): Boolean {
        val title = release.title ?: return false
        val names = (listOfNotNull(linkData.jpTitle, linkData.title) +
            listOfNotNull(linkData.jpTitle, linkData.title).map { compactSeasonTitle(it) })
            .filter { it.isNotBlank() }.distinct()
        val match = episodeMatch(title)
        for (name in names) {
            if (!releaseMatchesTitle(title, name)) continue
            if (linkData.format == "MOVIE") return !isBatchTitle(title)
            if (match != null && match.first == linkData.episode &&
                episodeAfterShowName(title, match.second, name)
            ) return true
        }
        return false
    }

    private suspend fun invokeAnimetosho(linkData: LinkData, callback: (ExtractorLink) -> Unit) {
        val aniZip = try {
            val resp = app.get("$ANIZIP_API/mappings?anilist_id=${linkData.anilistId}")
            if (resp.isSuccessful) parseJson<AniZipResponse>(resp.text) else null
        } catch (_: Exception) { null }

        val anidbEid = aniZip?.episodes?.get(linkData.episode.toString())?.anidbEid
        if (anidbEid != null) {
            val res = try {
                app.get("$ANIMETOSHO_API/json/v1/episodes/$anidbEid", timeout = 30L)
                    .parsedSafe<AnimetoshoEpisodeResponse>()
            } catch (_: Throwable) { null }
            // the episode feed also picks up batches, specials and sub series
            // of the same franchise, only releases that fit the picked show
            // and episode reach the player
            val items = res?.data?.releases.orEmpty()
                .filter { !it.magnet.isNullOrBlank() && it.is_batch != true && (it.seeders ?: 0) > 0 }
                .filter { matchesRequestedEpisode(it, linkData) }
                .sortedByDescending { it.seeders ?: 0 }
            for (it in items) {
                val magnet = it.magnet ?: continue
                val title = it.title ?: continue
                val seeders = it.seeders ?: 0
                callback.invoke(
                    newExtractorLink("Animetosho", "Animetosho | $seeders | $title", magnet, ExtractorLinkType.MAGNET) {
                        this.quality = getQualityFromString(title)
                    }
                )
            }
            if (items.isNotEmpty()) return
        }

        // anidb mapping is missing for plenty of shows, a title search still
        // finds the releases as long as the episode can be read from the name
        searchAnimetoshoReleases(linkData, callback)
    }

    private suspend fun searchAnimetoshoReleases(linkData: LinkData, callback: (ExtractorLink) -> Unit) {
        val searchTitles = (listOfNotNull(linkData.jpTitle, linkData.title) +
            listOfNotNull(linkData.jpTitle, linkData.title).map { compactSeasonTitle(it) })
            .filter { it.isNotBlank() }.distinct()
        for (query in searchTitles) {
            val res = try {
                app.get(
                    "$ANIMETOSHO_API/json/v1/releases?q=${URLEncoder.encode(query, "UTF-8")}",
                    headers = mapOf("User-Agent" to BROWSER_UA), timeout = 30L
                ).parsedSafe<AnimetoshoListResponse>()
            } catch (_: Throwable) { null } ?: continue

            val matches = res.data.orEmpty()
                .filter { !it.magnet.isNullOrBlank() && it.is_batch != true && (it.seeders ?: 0) > 0 }
                .filter { matchesRequestedEpisode(it, linkData) }
                .sortedByDescending { it.seeders ?: 0 }

            if (matches.isNotEmpty()) {
                for (item in matches.take(20)) {
                    val magnet = item.magnet ?: continue
                    val title = item.title ?: continue
                    val seeders = item.seeders ?: 0
                    callback.invoke(
                        newExtractorLink("Animetosho", "Animetosho | $seeders | $title", magnet, ExtractorLinkType.MAGNET) {
                            this.quality = getQualityFromString(title)
                        }
                    )
                }
                return
            }
        }
    }

    private suspend fun invokeNyaa(linkData: LinkData, callback: (ExtractorLink) -> Unit) {
        val isMovie = linkData.format == "MOVIE"
        val searchTitles = (listOfNotNull(linkData.jpTitle, linkData.title) +
            listOfNotNull(linkData.jpTitle, linkData.title).map { compactSeasonTitle(it) })
            .filter { it.isNotBlank() }.distinct()
        if (searchTitles.isEmpty()) return

        // a dead mirror must never hold the whole link load open until the
        // app timeout kills it, everything nyaa does has to fit in here
        withTimeoutOrNull(NYAA_TOTAL_MS) {
            var host = nyaaHost
            var sweepsFailed = 0
            for (base in searchTitles) {
                val attempts = mutableListOf<String>()
                if (isMovie) {
                    attempts.add("\"$base\"")
                    attempts.add(base)
                } else {
                    val ep = if (linkData.episode < 10) "0${linkData.episode}" else "${linkData.episode}"
                    // nearly every release is named "title - nn", the quoted
                    // phrase pins that shape and keeps season packs out of the
                    // top of the feed, the loose queries catch the rest
                    attempts.add("\"$base - $ep\"")
                    attempts.add("$base $ep")
                    attempts.add(base)
                }

                for (query in attempts) {
                    val rss = fetchNyaaRss(query, host)
                    if (rss == null) {
                        // every domain just timed out, trying more queries on a
                        // blocked network is pointless so give up after a recheck
                        host = null
                        if (++sweepsFailed >= 2) return@withTimeoutOrNull
                        continue
                    }
                    sweepsFailed = 0
                    host = rss.first
                    nyaaHost = rss.first

                    val matches = parseNyaaItems(rss.second)
                        .filter { item ->
                            if (item.seeders <= 0) return@filter false
                            if (!releaseMatchesTitle(item.title, base)) return@filter false
                            if (isMovie) !isBatchTitle(item.title)
                            else episodeMatch(item.title)?.first == linkData.episode
                        }
                        .sortedByDescending { it.seeders }

                    if (matches.isNotEmpty()) {
                        for (item in matches.take(20)) {
                            val magnet = buildMagnet(item.infoHash, null, null, item.title) ?: continue
                            callback.invoke(
                                newExtractorLink("Nyaa", "Nyaa | ${item.seeders} | ${item.title}", magnet, ExtractorLinkType.MAGNET) {
                                    this.quality = getQualityFromString(item.title)
                                }
                            )
                        }
                        return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    // asks the pinned host, or walks every known domain when nothing is
    // pinned, and reports which host produced the feed
    private suspend fun fetchNyaaRss(query: String, pinned: String?): Pair<String, String>? {
        val hosts = if (pinned != null) listOf(pinned) else NYAA_DOMAINS
        for (host in hosts) {
            try {
                val text = app.get(
                    "$host/?page=rss&q=${URLEncoder.encode(query, "UTF-8")}&c=0_0&f=0&s=seeders&o=desc",
                    headers = mapOf("User-Agent" to BROWSER_UA), timeout = NYAA_TIMEOUT
                ).text
                if (text.isNotBlank()) return host to text
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
            }
        }
        return null
    }

    private suspend fun invokeCustomStremioAddons(
        addons: List<StremioAddon>, stremioId: String?, linkData: LinkData, isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        // most addons accept the IMDB ID; fall back to the kitsu: ID
        val id = linkData.imdbId ?: stremioId ?: return
        addons.amap { addon ->
            try {
                val base = addon.url.trimEnd('/').replace("/manifest.json", "")
                val resourcePath = if (isMovie) {
                    "movie/$id"
                } else {
                    "series/$id:${linkData.season ?: 1}:${linkData.episode}"
                }
                fetchStremioStreamsUniversal(addon.name, "$base/stream/$resourcePath.json", callback)
                if (addon.type.contains("SUBTITLE", ignoreCase = true)) {
                    fetchStremioSubtitles("$base/subtitles/$resourcePath.json", subtitleCallback)
                }
            } catch (_: Throwable) {}
        }
    }

    private suspend fun fetchStremioSubtitles(url: String, subtitleCallback: (SubtitleFile) -> Unit) {
        val res = try { app.get(url, timeout = 30L).parsedSafe<StremioSubtitleResponse>() } catch (_: Throwable) { null } ?: return
        res.subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            if (subUrl.isNotBlank()) {
                subtitleCallback.invoke(SubtitleFile(sub.lang ?: sub.id ?: "Subtitle", subUrl))
            }
        }
    }

    private suspend fun fetchStremioStreams(sourceName: String, url: String, callback: (ExtractorLink) -> Unit) {
        val res = try { app.get(url, timeout = 200L).parsedSafe<StremioStreamResponse>() } catch (_: Throwable) { null } ?: return
        val streams = res.streams ?: return
        for (stream in streams) {
            val infoHash = stream.infoHash ?: continue
            val rawTitle = stream.title ?: stream.name ?: ""
            val seeders = getSeedersFromTitle(rawTitle)
            if (seeders == 0) continue
            val magnet = buildMagnet(infoHash, stream.fileIdx, stream.sources) ?: continue
            callback.invoke(
                newExtractorLink(sourceName, "$sourceName | ${seeders ?: "?"} | ${simplifyTitle(rawTitle)}".trim(),
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
        callback: (ExtractorLink) -> Unit
    ) {
        val res = try { app.get(url, timeout = 200L).parsedSafe<StremioStreamResponse>() } catch (_: Throwable) { null } ?: return
        val streams = res.streams ?: return
        for (stream in streams) {
            val rawTitle = stream.title ?: stream.description ?: stream.name ?: ""

            if (stream.infoHash != null && stream.infoHash.isNotBlank()) {
                val magnet = buildMagnet(stream.infoHash, stream.fileIdx, stream.sources) ?: continue
                callback.invoke(
                    newExtractorLink(sourceName, "$sourceName | ${simplifyTitle(rawTitle)}", magnet, ExtractorLinkType.MAGNET) {
                        this.quality = getQualityFromString(rawTitle)
                    }
                )
            }

            if (stream.url != null && stream.url.isNotBlank()) {
                var streamUrl = stream.url

                // some addons (e.g. notorrent) wrap the real URL in a /redirect endpoint
                if (streamUrl.contains("/redirect")) {
                    try {
                        val resp = app.get(streamUrl, allowRedirects = false)
                        val location = resp.headers?.get("location") ?: ""
                        if (location.isNotBlank()) {
                            // the redirect target may carry the m3u8 URL in a query param,
                            // e.g. https://host/vid1.php?url=/vid/movies/720p/tt123.m3u8
                            val urlParam = Regex("""[?&]url=([^&]+\.m3u8)""").find(location)?.groupValues?.get(1)
                            if (urlParam != null) {
                                val base = Regex("""(https?://[^/]+)""").find(location)?.groupValues?.get(1) ?: ""
                                streamUrl = "$base$urlParam"
                            } else {
                                streamUrl = location
                            }
                        }
                    } catch (_: Throwable) {}
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

    private suspend fun isAniListDown(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastDownCheckTime < DOWN_CHECK_INTERVAL) {
            return anilistDownPopupShown
        }
        if (!downCheckLock.tryLock()) return anilistDownPopupShown
        try {
            lastDownCheckTime = now
            val testQuery = "query { Page(page:1, perPage:1) { media(type: ANIME) { id } } }"
            val responseText = try { anilistQuery(testQuery, emptyMap()) } catch (_: Exception) { return false }

            if (responseText.contains("temporarily disabled")) {
                showAniListDownPopup()
                return true
            }

            if (responseText.contains("\"data\"") && !responseText.contains("\"data\":null")) {
                anilistDownPopupShown = false
                return false
            }
            return false
        } finally {
            downCheckLock.unlock()
        }
    }

    private fun showAniListDownPopup() {
        if (anilistDownPopupShown) return
        anilistDownPopupShown = true
        val ctx = activity ?: return
        ctx.runOnUiThread {
            try {
                val cBg = Color.parseColor("#0A0A0A")
                val cAccent = Color.parseColor("#E53935")
                val cTextSub = Color.parseColor("#9E9E9E")
                val d = ctx.resources.displayMetrics.density
                fun Int.dp() = (this * d).toInt()

                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24.dp(), 28.dp(), 24.dp(), 24.dp())
                    setBackgroundColor(cBg)
                }

                container.addView(TextView(ctx).apply {
                    text = "AniList API is Down"
                    textSize = 20f
                    setTextColor(cAccent)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 12.dp())
                })

                container.addView(TextView(ctx).apply {
                    text = "TorrentsV1 depends on the AniList API for anime metadata, search, and homepage content.\n\nThis may be because the AniList API is disabled from their end, or something is wrong from our end. Whichever the case, it will soon be fixed.\n\nIf AniList is disabled from their end, everything will work again once AniList restores services.\n\nTMDB content keeps working in the meantime."
                    textSize = 13f
                    setTextColor(cTextSub)
                    setLineSpacing(1.4f, 1.0f)
                    setPadding(0, 0, 0, 20.dp())
                })

                val scroll = ScrollView(ctx).apply { addView(container) }
                val dialog = AlertDialog.Builder(ctx).setView(scroll).create()

                container.addView(Button(ctx).apply {
                    text = "Got it"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(cAccent)
                    }
                    setPadding(0, 14.dp(), 0, 14.dp())
                    setOnClickListener { dialog.dismiss() }
                })

                dialog.show()
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            } catch (_: Exception) {}
        }
    }

    companion object {
        private val homePageCache = mutableMapOf<String, List<AniListMedia>>()
        @Volatile private var anilistDownPopupShown = false
        private val downCheckLock = Mutex()
        @Volatile private var lastDownCheckTime = 0L
        private const val DOWN_CHECK_INTERVAL = 60_000L
        // remembers which nyaa domain answered last so blocked networks only
        // pay the fallback walk once per app session
        @Volatile private var nyaaHost: String? = null
    }
}
