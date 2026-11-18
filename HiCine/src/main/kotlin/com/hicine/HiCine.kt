package com.hicine

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

class HiCine : MainAPI() {
    override var mainUrl = "https://hicine.biz"
    override var name = "HiCine"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val instantLinkLoading = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)
    override var lang = "hi"

    companion object {
        private const val DEFAULT_API = "https://hicine.biz"

        // the firebase entry can point at a host that stopped serving the api
        private val FALLBACK_APIS = listOf("https://hicine.biz", "https://hicine.lol")

        @Volatile
        private var API = DEFAULT_API

        @Volatile
        private var domainChecked = false

        private val SERVER_LABELS = mapOf(
            "fsl" to "FSL",
            "fsl2" to "FSLv2",
            "pixel" to "PixelDrain",
            "ten" to "10Gbps",
            "gdirect" to "G-Direct",
            "server1" to "Server 1",
            "gofile" to "Gofile"
        )

        // same order the site's player uses when it picks a server
        private val OPTION_ORDER = listOf("pixel", "fsl2", "fsl", "server1", "ten", "gdirect")

        // these only open an html download page, they cannot be played
        private val PAGE_HOSTS = setOf("transferit", "buzzheavier", "vikingfile")

        // the api 429s wide bursts, so keep the dl and go calls throttled
        private val dlGate = Semaphore(6)
        private val goGate = Semaphore(3)

        private val variantRegex = Regex(
            """(?i)\b(2160p|1440p|1080p|720p|480p|360p|4k|60fps|imax|10bit|8bit|""" +
                """x264|x265|h\.?264|h\.?265|hevc|av1|dv|hdr10\+|hdr10|hdr|sdr|""" +
                """ddp5\.1|dd5\.1|atmos|ddp|aac)\b"""
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Card(
        val id: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val kind: String? = null,
        val poster: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Row(val title: String? = null, val items: List<Card>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class HomeResponse(val rows: List<Row>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BrowseResponse(
        val items: List<Card>? = null,
        val hasMore: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchReply(val items: List<Card>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Meta(
        val poster: String? = null,
        val description: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Group(
        val head: String? = null,
        val season: Int? = null,
        val quality: String? = null,
        val size: String? = null,
        val k: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TitleResponse(
        val title: String? = null,
        val year: Int? = null,
        val kind: String? = null,
        val imdb: String? = null,
        val plot: String? = null,
        val groups: List<Group>? = null,
        val meta: Meta? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class Host(
        val id: String? = null,
        val name: String? = null,
        val k: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GroupItem(
        val ep: Int? = null,
        val hosts: List<Host>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GroupResponse(val items: List<GroupItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DlOption(
        val id: String? = null,
        val name: String? = null,
        val kind: String? = null,
        val k: String? = null,
        val ks: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DlResponse(val options: List<DlOption>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GoResponse(val url: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class LinkPayload(
        val id: String = "",
        val season: Int? = null,
        val ep: Int? = null
    )

    private suspend fun servesApi(base: String): Boolean {
        return try {
            app.get("$base/hapi/home", headers = mapOf("Referer" to "$base/"), timeout = 8L)
                .text.trimStart().startsWith("{")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun refreshDomain() {
        if (domainChecked) return
        domainChecked = true
        val remote = FirebaseDomainHelper.getDomain("hicine")?.trimEnd('/')
        val candidates = buildList {
            remote?.takeIf { it.startsWith("http") }?.let { add(it) }
            addAll(FALLBACK_APIS)
        }
        for (base in candidates) {
            if (servesApi(base)) {
                API = base
                mainUrl = base
                return
            }
        }
    }

    private suspend fun fetchJson(url: String): String? {
        return try {
            val text = app.get(url, headers = mapOf("Referer" to "$API/"), timeout = 12L).text
            text.takeIf { it.trimStart().startsWith("{") }
        } catch (_: Exception) {
            null
        }
    }

    override val mainPage = mainPageOf(
        "latest" to "Latest Additions",
        "row:Latest in Hollywood" to "Latest in Hollywood",
        "row:Latest in Bollywood" to "Latest in Bollywood",
        "row:Netflix" to "Netflix",
        "row:Prime Video" to "Prime Video",
        "row:JioHotstar" to "JioHotstar",
        "row:Anime" to "Anime",
        "row:K-Drama" to "K-Drama",
        "row:MX Player" to "MX Player"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        refreshDomain()

        if (request.data == "latest") {
            val text = fetchJson("$API/hapi/browse?page=$page") ?: return emptyPage(request.name)
            val parsed = try {
                parseJson<BrowseResponse>(text)
            } catch (_: Exception) {
                null
            } ?: return emptyPage(request.name)
            val cards = parsed.items.orEmpty().mapNotNull(::toCard)
            return newHomePageResponse(request.name, cards, hasNext = parsed.hasMore == true)
        }

        if (page > 1) return emptyPage(request.name)
        val rowName = request.data.removePrefix("row:")
        val text = fetchJson("$API/hapi/home") ?: return emptyPage(request.name)
        val home = try {
            parseJson<HomeResponse>(text)
        } catch (_: Exception) {
            null
        } ?: return emptyPage(request.name)
        val cards = home.rows.orEmpty()
            .firstOrNull { it.title == rowName }
            ?.items.orEmpty()
            .mapNotNull(::toCard)
        return newHomePageResponse(request.name, cards, hasNext = false)
    }

    private fun emptyPage(name: String): HomePageResponse =
        newHomePageResponse(name, emptyList<SearchResponse>(), hasNext = false)

    private fun toCard(item: Card): SearchResponse? {
        val id = item.id?.takeIf { it.isNotBlank() } ?: return null
        val title = item.title?.takeIf { it.isNotBlank() } ?: return null
        val url = "$mainUrl/title/$id"
        return if (item.kind == "series") {
            newTvSeriesSearchResponse(title, url) {
                this.posterUrl = item.poster
                this.year = item.year
            }
        } else {
            newMovieSearchResponse(title, url) {
                this.posterUrl = item.poster
                this.year = item.year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        refreshDomain()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val text = fetchJson("$API/hapi/search?q=$encoded&page=1") ?: return emptyList()
        val parsed = try {
            parseJson<SearchReply>(text)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val seen = mutableSetOf<String>()
        return parsed.items.orEmpty()
            .filter { !it.id.isNullOrBlank() && !it.title.isNullOrBlank() }
            .filter { seen.add(it.id!!) }
            .mapNotNull(::toCard)
    }

    override suspend fun load(url: String): LoadResponse? {
        refreshDomain()
        val id = url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: return null
        val text = fetchJson("$API/hapi/t?c=${URLEncoder.encode(id, "UTF-8")}") ?: return null
        val detail = try {
            parseJson<TitleResponse>(text)
        } catch (_: Exception) {
            null
        } ?: return null
        val title = detail.title?.takeIf { it.isNotBlank() } ?: return null
        val groups = detail.groups.orEmpty().filter { !it.k.isNullOrBlank() }
        val poster = detail.meta?.poster
        val plot = detail.meta?.description ?: detail.plot

        val seasons = groups.mapNotNull { it.season }.distinct().sorted()
        if (seasons.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, LinkPayload(id).toJson()) {
                this.posterUrl = poster
                this.year = detail.year
                this.plot = plot
            }
        }

        val episodesBySeason = coroutineScope {
            seasons.map { season ->
                async(Dispatchers.IO) {
                    val seasonGroups = groups.filter { it.season == season }
                    var collected: List<GroupItem> = emptyList()
                    for (group in seasonGroups) {
                        val items = fetchGroup(group.k ?: continue)
                        if (items.isNotEmpty()) {
                            collected = items
                            break
                        }
                    }
                    collected.filter { it.ep != null }.map { season to it }
                }
            }.flatMap { it.await() }
        }
        if (episodesBySeason.isEmpty()) return null

        val built = episodesBySeason.map { (season, item) ->
            newEpisode(LinkPayload(id, season, item.ep).toJson()) {
                this.season = season
                this.episode = item.ep
                this.name = "Episode ${item.ep}"
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, built) {
            this.posterUrl = poster
            this.year = detail.year
            this.plot = plot
        }
    }

    private suspend fun fetchGroup(k: String): List<GroupItem> {
        val text = fetchJson("$API/hapi/g?k=${URLEncoder.encode(k, "UTF-8")}") ?: return emptyList()
        return try {
            parseJson<GroupResponse>(text).items.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchDlOptions(k: String): List<DlOption> {
        val text = dlGate.withPermit {
            fetchJson("$API/hapi/dl?k=${URLEncoder.encode(k, "UTF-8")}")
        } ?: return emptyList()
        return try {
            parseJson<DlResponse>(text).options.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // a go key answers once, the site throws it away right after
    private suspend fun goUrl(option: DlOption): String? {
        val key = option.ks?.takeIf { it.isNotBlank() } ?: option.k ?: return null
        val text = goGate.withPermit {
            fetchJson("$API/hapi/go?k=${URLEncoder.encode(key, "UTF-8")}&m=u")
        } ?: return null
        return try {
            parseJson<GoResponse>(text).url?.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun optionRank(id: String?): Int {
        val idx = OPTION_ORDER.indexOf(id)
        return if (idx < 0) OPTION_ORDER.size else idx
    }

    private fun pixelDirect(pageUrl: String): String? {
        val id = pageUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (id.length !in 5..15) return null
        return "https://pixeldrain.dev/api/file/$id"
    }

    private fun variantLabel(head: String?): String {
        if (head.isNullOrBlank()) return ""
        val found = variantRegex.findAll(head).map { it.value.lowercase() }.distinct().toMutableList()
        if ("x265" in found) found.removeAll(listOf("hevc", "h.265", "h265"))
        if ("x264" in found) found.removeAll(listOf("h.264", "h264"))
        return found.joinToString(" ")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try {
            parseJson<LinkPayload>(data)
        } catch (_: Exception) {
            return false
        }
        if (payload.id.isBlank()) return false

        val text = fetchJson("$API/hapi/t?c=${URLEncoder.encode(payload.id, "UTF-8")}") ?: return false
        val detail = try {
            parseJson<TitleResponse>(text)
        } catch (_: Exception) {
            null
        } ?: return false

        // every group is a separate encode, the same quality can show up many times
        val all = detail.groups.orEmpty().filter { !it.k.isNullOrBlank() }
        val groups = if (payload.season != null) {
            all.filter { it.season == payload.season }.ifEmpty { all }
        } else {
            all
        }
        if (groups.isEmpty()) return false

        coroutineScope {
            groups.forEach { group ->
                async(Dispatchers.IO) {
                    val items = fetchGroup(group.k ?: return@async)
                    val scoped = if (payload.ep != null) {
                        val exact = items.filter { it.ep == payload.ep }
                        if (exact.isNotEmpty()) exact else items.filter { it.ep == null }
                    } else {
                        items.filter { it.ep == null }.ifEmpty { items.take(1) }
                    }
                    val item = scoped.firstOrNull() ?: return@async
                    val variant = variantLabel(group.head)
                    emitHosts(item.hosts.orEmpty(), variant, group.quality, group.size, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun emitHosts(
        hosts: List<Host>,
        variant: String,
        quality: String?,
        size: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        coroutineScope {
            hosts.distinctBy { it.id }.take(4).forEach { host ->
                val hostKey = host.k ?: return@forEach
                async(Dispatchers.IO) {
                    val first = fetchDlOptions(hostKey)
                        .filter { it.id !in PAGE_HOSTS }
                    if (first.isEmpty()) return@async
                    val failed = mutableListOf<DlOption>()
                    first.sortedBy { optionRank(it.id) }.forEach { option ->
                        val url = goUrl(option)
                        if (url == null) {
                            failed.add(option)
                        } else {
                            emitResolved(option, url, variant, quality, size, subtitleCallback, callback)
                        }
                    }
                    if (failed.isEmpty()) return@async
                    // the go call drops keys it already used, so ask dl for new ones
                    delay(600L)
                    val second = fetchDlOptions(hostKey).filter { it.id !in PAGE_HOSTS }
                    failed.forEach { option ->
                        val retry = second.firstOrNull { it.id == option.id } ?: return@forEach
                        goUrl(retry)?.let { url ->
                            emitResolved(retry, url, variant, quality, size, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }

    private suspend fun emitResolved(
        option: DlOption,
        url: String,
        variant: String,
        quality: String?,
        size: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = option.id ?: ""
        val server = SERVER_LABELS[id] ?: option.name?.takeIf { it.isNotBlank() } ?: id.ifBlank { "Server" }
        when {
            id == "pixel" || url.contains("pixeldrain.dev/u/") -> {
                pixelDirect(url)?.let { emit(it, server, variant, quality, size, callback) }
            }
            id == "gofile" || url.contains("gofile.io") -> {
                try {
                    val collected = mutableListOf<ExtractorLink>()
                    loadExtractor(url, null, subtitleCallback) { collected.add(it) }
                    collected.forEach { link ->
                        emit(
                            link.url,
                            server,
                            variant,
                            quality,
                            size,
                            callback,
                            link.type,
                            link.headers.takeIf { it.isNotEmpty() },
                            link.referer
                        )
                    }
                } catch (_: Exception) {}
            }
            url.contains("transfer.it") || url.contains("bzzhr.co") ||
                url.contains("buzzheavier") || url.contains("vikingfile") -> Unit
            else -> emit(url, server, variant, quality, size, callback)
        }
    }

    private suspend fun emit(
        url: String,
        server: String,
        variant: String,
        quality: String?,
        size: String?,
        callback: (ExtractorLink) -> Unit,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        headers: Map<String, String>? = null,
        referer: String? = null
    ) {
        val name = listOfNotNull(server, variant.ifBlank { null }, size?.let { "[$it]" })
            .joinToString(" ")
        callback(
            newExtractorLink("HiCine", name, url, type) {
                this.quality = qualityValue(quality)
                if (headers != null) this.headers = headers
                if (referer != null) this.referer = referer
            }
        )
    }
}
