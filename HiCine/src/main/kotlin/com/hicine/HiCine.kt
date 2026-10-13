package com.hicine

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HiCine : MainAPI() {

    override var mainUrl = "https://hicine.biz"
    override var name = "HiCine"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)
    override var lang = "hi"

    companion object {
        private const val API = "https://api.hicine.biz"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36"

        private val URL_RE = Regex("""https?://[^\s,]+""")
        private val QUALITY_RE = Regex("""(?i)\b(2160p|1440p|1080p|720p|480p|360p|4K)\b""")
        private val SIZE_RE = Regex("""(?i)\d+(?:\.\d+)?\s*(?:MB|GB)""")
        private val YEAR_RE = Regex("""\((\d{4})\)""")
        private val EPISODE_RE = Regex("""Episode\s+(\d+)\s*:\s*(.*)""")
        private val SEASON_PACK_RE = Regex("""Season\s+(\d+)\s*:\s*(.*)""")

        private const val TOKEN_TTL = 3 * 60 * 1000L

        private val SERVER_LABELS = mapOf(
            "fsl" to "FSL",
            "fsl2" to "FSLv2",
            "pixel" to "PixelDrain",
            "ten" to "10Gbps",
            "gofile" to "Gofile",
            "server1" to "Server 1"
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiItem(
        @JsonProperty("record_id") val recordId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("featured_image") val featuredImage: String? = null,
        @JsonProperty("links") val links: String? = null,
        @JsonProperty("cloudlinks") val cloudlinks: String? = null,
        @JsonProperty("categories") val categories: String? = null,
        @JsonProperty("date") val date: String? = null,
        @JsonProperty("excerpt") val excerpt: String? = null,
        @JsonProperty("season_zip") val seasonZip: String? = null,
        @JsonProperty("source_table") val sourceTable: String? = null,
        @JsonProperty("season_1") val season1: String? = null,
        @JsonProperty("season_2") val season2: String? = null,
        @JsonProperty("season_3") val season3: String? = null,
        @JsonProperty("season_4") val season4: String? = null,
        @JsonProperty("season_5") val season5: String? = null,
        @JsonProperty("season_6") val season6: String? = null,
        @JsonProperty("season_7") val season7: String? = null,
        @JsonProperty("season_8") val season8: String? = null,
        @JsonProperty("season_9") val season9: String? = null,
        @JsonProperty("season_10") val season10: String? = null,
        @JsonProperty("season_11") val season11: String? = null,
        @JsonProperty("season_12") val season12: String? = null,
        @JsonProperty("season_13") val season13: String? = null,
        @JsonProperty("season_14") val season14: String? = null,
        @JsonProperty("season_15") val season15: String? = null
    ) {
        fun seasons(): List<String?> = listOf(
            season1, season2, season3, season4, season5,
            season6, season7, season8, season9, season10,
            season11, season12, season13, season14, season15
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiListResponse(
        @JsonProperty("data") val data: List<ApiItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchWrapper(
        @JsonProperty("source_table") val sourceTable: String? = null,
        @JsonProperty("data") val data: ApiItem? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FileEntry(
        val url: String,
        val quality: String? = null,
        val size: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Payload(
        val entries: List<FileEntry> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ServerToken(
        val ts: String? = null,
        val sig: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinksResponse(
        val tokens: Map<String, ServerToken>? = null
    )

    override val mainPage = mainPageOf(
        "static:/rpc/recent/30?limit=40&contentType=all" to "Recently Added",
        "static:/rpc/featured?limit=40" to "Featured",
        "static:/rpc/platform/netflix?limit=40" to "Netflix",
        "static:/rpc/platform/prime?limit=40" to "Prime Video",
        "page:/api/hollywood_movies" to "Hollywood Movies",
        "page:/api/hollywood_series" to "Hollywood Series",
        "page:/api/bollywood_movies" to "Bollywood Movies",
        "page:/api/bollywood_series" to "Bollywood Series",
        "page:/api/anime" to "Anime",
        "static:/rpc/category/dual?limit=40" to "Dual Audio",
        "static:/rpc/latest/bollywood/movies?limit=40" to "Latest Bollywood Movies",
        "static:/rpc/latest/bollywood/series?limit=40" to "Latest Bollywood Series",
        "static:/rpc/genre/sci-fi?limit=40" to "Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val parts = request.data.split(":", limit = 2)
        if (parts.size < 2) return newHomePageResponse(request.name, emptyList())

        val path = parts[1]
        val items = if (parts[0] == "page") {
            fetchItems("$path?page=$page&limit=24")
        } else {
            if (page > 1) emptyList() else fetchItems(path)
        }
        return newHomePageResponse(request.name, items.mapNotNull(::toSearchResponse))
    }

    private suspend fun fetchItems(path: String): List<ApiItem> {
        val text = try {
            app.get("$API$path", timeout = 20L).text
        } catch (e: Exception) {
            return emptyList()
        }
        return parseItems(text)
    }

    private fun parseItems(text: String): List<ApiItem> = try {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            parseJson<ApiListResponse>(trimmed).data.orEmpty()
        } else {
            parseJson<List<ApiItem>>(trimmed)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun toSearchResponse(item: ApiItem): SearchResponse? {
        val title = item.title ?: return null
        val rid = item.recordId ?: return null
        val table = item.sourceTable ?: return null
        val url = "$mainUrl/$table/$rid"
        val type = tableType(item)
        return if (type == TvType.Movie || type == TvType.AnimeMovie) {
            newMovieSearchResponse(title, url, type) { this.posterUrl = item.featuredImage }
        } else {
            newTvSeriesSearchResponse(title, url, type) { this.posterUrl = item.featuredImage }
        }
    }

    private fun tableType(item: ApiItem): TvType {
        return when (item.sourceTable) {
            "hollywood_movies", "bollywood_movies" -> TvType.Movie
            "hollywood_series", "bollywood_series" -> TvType.TvSeries
            "anime" -> if (item.seasons().any { !it.isNullOrBlank() }) TvType.Anime else TvType.AnimeMovie
            else -> if (item.seasons().any { !it.isNullOrBlank() }) TvType.TvSeries else TvType.Movie
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val text = try {
            app.get("$API/rpc/search/$encoded?limit=50", timeout = 20L).text
        } catch (e: Exception) {
            return emptyList()
        }
        val wrapped = try {
            parseJson<List<SearchWrapper>>(text)
        } catch (e: Exception) {
            emptyList()
        }
        return wrapped.mapNotNull { w ->
            val inner = w.data ?: return@mapNotNull null
            toSearchResponse(inner.copy(sourceTable = w.sourceTable ?: inner.sourceTable))
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.removePrefix("$mainUrl/").split("/")
        val table = parts.getOrNull(0) ?: return null
        val recordId = parts.getOrNull(1)?.toIntOrNull() ?: return null

        val text = try {
            app.get("$API/api/$table/$recordId", timeout = 20L).text
        } catch (e: Exception) {
            return null
        }
        val item = try {
            parseJson<ApiItem>(text).copy(sourceTable = table)
        } catch (e: Exception) {
            return null
        }

        val title = item.title ?: return null
        val year = YEAR_RE.find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: item.date?.take(4)?.toIntOrNull()
        val tags = item.categories?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.length < 24 }
            ?.take(12)
            ?.distinct()

        val type = tableType(item)
        val isSeries = item.seasons().any { !it.isNullOrBlank() }

        if (!isSeries) {
            val entries = parseMovieLinks(item.links).ifEmpty { parseMovieLinks(item.cloudlinks) }
            if (entries.isEmpty()) return null
            return newMovieLoadResponse(title, url, type, Payload(entries).toJson()) {
                this.posterUrl = item.featuredImage
                this.year = year
                this.tags = tags
                this.plot = item.excerpt
            }
        }

        val episodes = mutableListOf<Episode>()
        item.seasons().forEachIndexed { idx, seasonText ->
            val season = idx + 1
            parseEpisodeLines(seasonText).forEach { (epNum, entries) ->
                episodes.add(newEpisode(Payload(entries).toJson()) {
                    this.season = season
                    this.episode = epNum
                    this.name = "Episode $epNum"
                })
            }
        }
        item.seasonZip?.lines()?.forEach { line ->
            val match = SEASON_PACK_RE.find(line) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val entries = match.groupValues[2].split(" : ").mapNotNull(::parseEntry)
            if (entries.isNotEmpty()) {
                episodes.add(newEpisode(Payload(entries).toJson()) {
                    this.season = season
                    this.episode = 0
                    this.name = "Season Pack"
                })
            }
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = item.featuredImage
            this.year = year
            this.tags = tags
            this.plot = item.excerpt
        }
    }

    // movie lines carry "URL, Link2..7, quality, size" where usually only the
    // first slot holds a real url and the rest are placeholder labels
    private fun parseMovieLinks(raw: String?): List<FileEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().flatMap { line ->
            val urls = URL_RE.findAll(line).map { it.value }.toList()
            if (urls.isEmpty()) return@flatMap emptyList()
            val parts = line.split(", ")
            val qualityText = parts.getOrNull(parts.size - 2).orEmpty()
            val sizeText = parts.getOrNull(parts.size - 1).orEmpty()
            val quality = QUALITY_RE.find(qualityText)?.value
            val size = SIZE_RE.find(sizeText)?.value ?: SIZE_RE.find(qualityText)?.value
            urls.map { url -> FileEntry(url, quality, size) }
        }
    }

    private fun parseEpisodeLines(raw: String?): List<Pair<Int, List<FileEntry>>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val match = EPISODE_RE.find(line) ?: return@mapNotNull null
            val epNum = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val entries = match.groupValues[2].split(" : ").mapNotNull(::parseEntry)
            if (entries.isEmpty()) null else epNum to entries
        }
    }

    // entries inside an episode or season pack line hold url, size and
    // quality in varying order, sometimes with empty size slots
    private fun parseEntry(raw: String): FileEntry? {
        val url = URL_RE.find(raw)?.value ?: return null
        val quality = QUALITY_RE.find(raw)?.value
        val size = SIZE_RE.find(raw)?.value
        if (quality == null && size == null) return null
        return FileEntry(url, quality, size)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try {
            parseJson<Payload>(data)
        } catch (e: Exception) {
            return false
        }
        if (payload.entries.isEmpty()) return false

        coroutineScope {
            payload.entries.map { entry ->
                async(Dispatchers.IO) {
                    emitEntry(entry, callback)
                }
            }.awaitAll()
        }
        return true
    }

    private suspend fun emitEntry(entry: FileEntry, callback: (ExtractorLink) -> Unit) {
        if (entry.url.contains("pixeldrain")) {
            val direct = pixelDirect(entry.url)
            if (direct != null) {
                emit(callback, "PixelDrain", direct, entry)
            }
            return
        }

        val base = entry.url.substringBefore("/?")
        val vcloud = entry.url.substringAfter("vcloud=", "")
        if (vcloud.isBlank()) return

        val tokens = serverTokens(base, vcloud) ?: return
        tokens.forEach { (type, token) ->
            val ts = token.ts ?: return@forEach
            val sig = token.sig ?: return@forEach
            val label = SERVER_LABELS[type] ?: type
            val target = resolveServer(base, type, vcloud, ts, sig) ?: return@forEach
            emit(callback, label, target, entry)
        }
    }

    private suspend fun emit(callback: (ExtractorLink) -> Unit, server: String, url: String, entry: FileEntry) {
        val name = buildString {
            append(server)
            entry.quality?.let { append(" $it") }
            entry.size?.let { append(" [$it]") }
        }
        callback(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.quality = qualityValue(entry.quality)
            }
        )
    }

    private fun qualityValue(quality: String?): Int = when (quality?.lowercase()) {
        "2160p", "4k" -> Qualities.P2160.value
        "1440p" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private val tokenCache = ConcurrentHashMap<String, Pair<Long, LinksResponse?>>()

    // the signed tokens live roughly five minutes, cache a bit shorter so a
    // cached resolve never hands out an expired link
    private suspend fun serverTokens(base: String, vcloud: String): Map<String, ServerToken>? {
        val cached = tokenCache[vcloud]
        if (cached != null && System.currentTimeMillis() - cached.first < TOKEN_TTL) {
            return cached.second?.tokens
        }
        val response = try {
            app.get(
                "$base/api/links?vcloud=${URLEncoder.encode(vcloud, "UTF-8")}",
                timeout = 15L
            )
        } catch (e: Exception) {
            null
        }
        val parsed = response?.let {
            try {
                parseJson<LinksResponse>(it.text)
            } catch (e: Exception) {
                null
            }
        }
        tokenCache[vcloud] = System.currentTimeMillis() to parsed
        return parsed?.tokens
    }

    // redirects are followed manually because the targets are full video
    // files and an automatic client would start pulling the body
    private val noRedirectClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .followRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private fun hopLocation(url: String): String? = try {
        noRedirectClient.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        ).execute().use { response ->
            if (response.code in 300..399) response.header("Location") else null
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun resolveServer(
        base: String,
        type: String,
        vcloud: String,
        ts: String,
        sig: String
    ): String? {
        val enc = URLEncoder.encode(vcloud, "UTF-8")
        val goUrl = "$base/go?type=$type&vcloud=$enc&ts=$ts&sig=$sig"
        return when (type) {
            "pixel" -> hopLocation(goUrl)?.let { pixelDirect(it) }
            "ten" -> resolveTen(goUrl)
            "gofile" -> hopLocation(goUrl)?.let { gofileDirect(it) }
            else -> hopLocation(goUrl)
        }
    }

    private fun pixelDirect(pageUrl: String): String? {
        val id = pageUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (id.length !in 5..15) return null
        return "https://pixeldrain.dev/api/file/$id?download"
    }

    // 10Gbps hops through hubcloud and a workers proxy before landing on a
    // generator page that only carries the real url in its query
    private fun resolveTen(goUrl: String): String? {
        val hubcloud = hopLocation(goUrl) ?: return null
        val workerHop = hopLocation(hubcloud) ?: return null
        val dlPage = hopLocation(workerHop) ?: return null
        val link = dlPage.substringAfter("link=", "").substringBefore("&")
        return link.takeIf { it.startsWith("http") }
    }

    private var gofileToken: String? = null

    // gofile needs a guest account for the contents api, many uploaders
    // skip the website token gate so this only works for open folders
    private suspend fun gofileDirect(pageUrl: String): String? {
        val contentId = pageUrl.trimEnd('/').substringAfterLast('/')
        if (contentId.length !in 5..20) return null
        return try {
            if (gofileToken == null) {
                val account = app.post("https://api.gofile.io/accounts", timeout = 15L).text
                gofileToken = parseJson<GofileAccount>(account).data?.token
            }
            val token = gofileToken ?: return null
            val contents = app.get(
                "https://api.gofile.io/contents/$contentId",
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 15L
            ).text
            parseJson<GofileContents>(contents).data?.children?.values
                ?.firstOrNull { it.link != null }?.link
        } catch (e: Exception) {
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GofileAccount(
        val data: GofileAccountData? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GofileAccountData(
        val token: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GofileContents(
        val data: GofileFolder? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GofileFolder(
        val children: Map<String, GofileChild>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GofileChild(
        val link: String? = null
    )
}
