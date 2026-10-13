package com.justplay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object HiCineSite {
    private const val API = "https://api.hicine.biz"
    private const val TOKEN_TTL = 3 * 60 * 1000L

    private val URL_RE = Regex("""https?://[^\s,]+""")
    private val QUALITY_RE = Regex("""(?i)\b(2160p|1440p|1080p|720p|480p|360p|4K)\b""")
    private val SIZE_RE = Regex("""(?i)\d+(?:\.\d+)?\s*(?:MB|GB)""")
    private val EPISODE_RE = Regex("""Episode\s+(\d+)\s*:\s*(.*)""")

    private val SERVER_LABELS = mapOf(
        "fsl" to "FSL",
        "fsl2" to "FSLv2",
        "pixel" to "PixelDrain",
        "ten" to "10Gbps",
        "gofile" to "Gofile",
        "server1" to "Server 1"
    )

    private val MOVIE_TABLES = setOf("hollywood_movies", "bollywood_movies")
    private val SERIES_TABLES = setOf("hollywood_series", "bollywood_series")

    private val TITLE_YEAR_RE = Regex("""\s*\((19|20)\d{2}\)\s*$""")
    private val ANY_YEAR_RE = Regex("""\b(19|20)\d{2}\b""")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Item(
        @JsonProperty("record_id") val recordId: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("links") val links: String? = null,
        @JsonProperty("cloudlinks") val cloudlinks: String? = null,
        @JsonProperty("date") val date: String? = null,
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

        fun hasSeasons(): Boolean = seasons().any { !it.isNullOrBlank() }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchHit(
        @JsonProperty("source_table") val sourceTable: String? = null,
        @JsonProperty("data") val data: Item? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class FileEntry(
        val url: String,
        val quality: String? = null,
        val size: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ServerToken(
        val ts: String? = null,
        val sig: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class LinksResponse(
        val title: String? = null,
        val size: String? = null,
        val watchable: Boolean? = null,
        val tokens: Map<String, ServerToken>? = null
    )

    private fun tableMatches(table: String?, item: Item, wantSeries: Boolean): Boolean {
        return when (table) {
            in MOVIE_TABLES -> !wantSeries
            in SERIES_TABLES -> wantSeries
            "anime" -> item.hasSeasons() == wantSeries
            else -> true
        }
    }

    private suspend fun search(query: String): List<Pair<String, Item>> {
        val text = PlayNet.retry {
            try {
                app.get(
                    "$API/rpc/search/${URLEncoder.encode(query, "UTF-8")}?limit=50",
                    headers = PlayNet.headers(),
                    timeout = 15L
                ).text
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        return try {
            parseJson<List<SearchHit>>(text).mapNotNull { hit ->
                val item = hit.data ?: return@mapNotNull null
                if (item.recordId == null || item.title.isNullOrBlank()) return@mapNotNull null
                (hit.sourceTable ?: "") to item
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchDetail(table: String, recordId: Int): Item? {
        val text = PlayNet.retry {
            try {
                app.get("$API/api/$table/$recordId", headers = PlayNet.headers(), timeout = 15L).text
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return try {
            parseJson<Item>(text)
        } catch (_: Exception) {
            null
        }
    }

    // hicine keeps the release year inside the title, strip it so short
    // names compare on the name alone
    private fun cleanTitle(title: String?): String =
        (title ?: "").replace(TITLE_YEAR_RE, "").trim()

    private fun knownYears(item: Item): List<Int> {
        // the date column is an upload date, only the year inside the title
        // says when the title actually released
        return ANY_YEAR_RE.findAll(item.title ?: "")
            .mapNotNull { it.value.toIntOrNull() }.toList()
    }

    private fun yearOk(item: Item, want: Int?): Boolean {
        val years = knownYears(item)
        if (years.isEmpty() || want == null) return true
        return years.any { kotlin.math.abs(it - want) <= 1 }
    }

    private suspend fun pickAndLoad(
        hits: List<Pair<String, Item>>,
        res: PlayLinkData
    ): Item? {
        val title = res.title ?: return null
        val wantSeries = res.season != null
        val wanted = PlayNet.normalizeTitle(title)
        val candidates = hits.filter { (_, item) ->
            PlayNet.titleMatches(cleanTitle(item.title), title)
        }.filter { (table, item) ->
            tableMatches(table, item, wantSeries)
        }.ifEmpty { hits.filter { (_, item) -> PlayNet.titleMatches(cleanTitle(item.title), title) } }

        // an exact name that carries the wrong year is a different title,
        // hicine keeps spinoffs under near identical names so falling back
        // to looser matches would hand out the wrong show
        val exact = candidates.filter { (_, item) ->
            PlayNet.normalizeTitle(cleanTitle(item.title)) == wanted
        }
        val pool = if (exact.isNotEmpty()) {
            exact.filter { (_, item) -> yearOk(item, res.matchYear) }
        } else {
            candidates.filter { (_, item) -> yearOk(item, res.matchYear) }
        }
        // search rows can miss the season fields, the detail view decides the
        // real type so a wrong guess just moves on to the next candidate
        for ((table, item) in pool.take(3)) {
            val detail = fetchDetail(table, item.recordId ?: continue) ?: continue
            if (tableMatches(table, detail, wantSeries)) return detail
        }
        return null
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

    // entries inside an episode line hold url, size and quality in varying
    // order, sometimes with empty size slots
    private fun parseEntry(raw: String): FileEntry? {
        val url = URL_RE.find(raw)?.value ?: return null
        val quality = QUALITY_RE.find(raw)?.value
        val size = SIZE_RE.find(raw)?.value
        if (quality == null && size == null) return null
        return FileEntry(url, quality, size)
    }

    private fun episodeEntries(item: Item, season: Int?, episode: Int?): List<FileEntry> {
        if (season == null) return emptyList()
        val seasonText = item.seasons().getOrNull(season - 1) ?: return emptyList()
        val lines = seasonText?.lines().orEmpty()
        val parsed = if (episode != null) {
            lines.mapNotNull { line ->
                val match = EPISODE_RE.find(line) ?: return@mapNotNull null
                val epNum = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                if (epNum != episode) return@mapNotNull null
                match.groupValues[2].split(" : ").mapNotNull(::parseEntry)
            }.flatten()
        } else {
            lines.mapNotNull { line ->
                val match = EPISODE_RE.find(line) ?: return@mapNotNull null
                match.groupValues[2].split(" : ").mapNotNull(::parseEntry)
            }.flatten()
        }
        return parsed.sortedByDescending { it.quality != null }.distinctBy { it.url }
    }

    private suspend fun emitDirect(
        url: String,
        server: String,
        info: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!PlayNet.alive(url, ExtractorLinkType.VIDEO)) return
        val name = PlayLabels.buildLabel("hicine", server, info)
        callback(
            newExtractorLink("[HiCine]", name, url, ExtractorLinkType.VIDEO) {
                this.quality = PlayNet.getIndexQuality(info)
            }
        )
    }

    private suspend fun emitEntry(
        entry: FileEntry,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (entry.url.contains("pixeldrain")) {
            pixelDirect(entry.url)?.let {
                val info = listOfNotNull(entry.quality, entry.size).joinToString(" ")
                emitDirect(it, "PixelDrain", info, callback)
            }
            return
        }

        val base = entry.url.substringBefore("/?")
        val vcloud = entry.url.substringAfter("vcloud=", "")
        if (vcloud.isBlank()) return

        val links = serverTokens(base, vcloud) ?: return
        if (links.watchable == false) return
        // the panel knows the real file name for a vcloud, it makes the
        // richest label and beats the quality guessed from the post text
        val info = links.title
            ?: listOfNotNull(entry.quality, entry.size).joinToString(" ")
        coroutineScope {
            links.tokens.orEmpty().forEach { (type, token) ->
                val ts = token.ts ?: return@forEach
                val sig = token.sig ?: return@forEach
                async(Dispatchers.IO) {
                    val server = SERVER_LABELS[type] ?: return@async
                    val target = resolveServer(base, type, vcloud, ts, sig) ?: return@async
                    if (type == "gofile") {
                        // the gofile hop lands on a folder page, the shared
                        // extractor knows the account and token dance
                        PlayNet.emitSiteLink(
                            "hicine", target, info,
                            PlayNet.getIndexQuality(info), null, subtitleCallback, callback
                        )
                    } else {
                        emitDirect(target, server, info, callback)
                    }
                }
            }
        }
    }

    private val tokenCache = ConcurrentHashMap<String, Pair<Long, LinksResponse?>>()

    // the signed tokens live roughly five minutes, cache a bit shorter so a
    // cached resolve never hands out an expired link
    private suspend fun serverTokens(base: String, vcloud: String): LinksResponse? {
        val cached = tokenCache[vcloud]
        if (cached != null && System.currentTimeMillis() - cached.first < TOKEN_TTL) {
            return cached.second
        }
        val response = try {
            app.get(
                "$base/api/links?vcloud=${URLEncoder.encode(vcloud, "UTF-8")}",
                headers = PlayNet.headers(),
                timeout = 15L
            )
        } catch (_: Exception) {
            null
        }
        val parsed = response?.let {
            try {
                parseJson<LinksResponse>(it.text)
            } catch (_: Exception) {
                null
            }
        }
        tokenCache[vcloud] = System.currentTimeMillis() to parsed
        return parsed
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
            Request.Builder().url(url).header("User-Agent", PLAY_UA).build()
        ).execute().use { response ->
            if (response.code in 300..399) response.header("Location") else null
        }
    } catch (_: Exception) {
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

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val title = res.title ?: return
            val hits = search(title)
            if (hits.isEmpty()) return
            val item = pickAndLoad(hits, res) ?: return

            val entries = if (res.season == null) {
                parseMovieLinks(item.links).ifEmpty { parseMovieLinks(item.cloudlinks) }
                    .sortedByDescending { it.quality != null }.distinctBy { it.url }
            } else {
                episodeEntries(item, res.season, res.episode)
            }
            if (entries.isEmpty()) return

            coroutineScope {
                entries.forEach { entry ->
                    async(Dispatchers.IO) {
                        emitEntry(entry, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
