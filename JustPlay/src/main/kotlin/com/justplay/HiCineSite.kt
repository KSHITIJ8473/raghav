package com.justplay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

internal object HiCineSite {
    private const val DEFAULT_API = "https://hicine.biz"

    @Volatile
    private var apiOverride: String? = null

    private suspend fun api(): String {
        apiOverride?.let { return it }
        val remote = FirebaseDomainHelper.getDomain("justplay_hicine")?.trimEnd('/')
        if (remote != null) {
            apiOverride = remote
            return remote
        }
        return DEFAULT_API
    }

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

    private val QUALITY_ORDER = listOf("2160p", "1080p", "720p", "480p", "1440p", "360p")

    // the api 429s wide bursts, so keep the dl and go calls throttled
    private val dlGate = Semaphore(6)
    private val goGate = Semaphore(3)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchItem(
        val id: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val kind: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SearchResponse(val items: List<SearchItem>? = null)

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
        val kind: String? = null,
        val groups: List<Group>? = null
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

    private suspend fun fetchJson(url: String): String? {
        return try {
            val text = app.get(
                url,
                headers = PlayNet.headers(referer = PlayNet.getBaseUrl(url)),
                timeout = 12L
            ).text
            text.takeIf { it.trimStart().startsWith("{") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun search(query: String): List<SearchItem> {
        val text = fetchJson("${api()}/hapi/search?q=${URLEncoder.encode(query, "UTF-8")}&page=1")
            ?: return emptyList()
        return try {
            parseJson<SearchResponse>(text).items.orEmpty().filter { !it.id.isNullOrBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTitle(id: String): TitleResponse? {
        val text = fetchJson("${api()}/hapi/t?c=${URLEncoder.encode(id, "UTF-8")}") ?: return null
        return try {
            parseJson<TitleResponse>(text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchGroup(k: String): List<GroupItem> {
        val text = fetchJson("${api()}/hapi/g?k=${URLEncoder.encode(k, "UTF-8")}")
            ?: return emptyList()
        return try {
            parseJson<GroupResponse>(text).items.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchDlOptions(k: String): List<DlOption> {
        val text = dlGate.withPermit {
            fetchJson("${api()}/hapi/dl?k=${URLEncoder.encode(k, "UTF-8")}")
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
            fetchJson("${api()}/hapi/go?k=${URLEncoder.encode(key, "UTF-8")}&m=u")
        } ?: return null
        return try {
            parseJson<GoResponse>(text).url?.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun yearOk(item: SearchItem, want: Int?): Boolean {
        if (want == null || item.year == null) return true
        return kotlin.math.abs(item.year - want) <= 1
    }

    private fun pickTitle(hits: List<SearchItem>, res: PlayLinkData): SearchItem? {
        val wantSeries = res.season != null
        val title = res.title ?: return null
        val matches = hits.filter { hit ->
            hit.title != null && PlayNet.titleMatches(hit.title, title)
        }
        val byKind = matches.filter { (it.kind == "series") == wantSeries }
        val pool = if (byKind.isNotEmpty()) byKind else matches
        return pool.firstOrNull { yearOk(it, res.matchYear) } ?: pool.firstOrNull()
    }

    // every group is a separate encode, the same quality can show up many times
    private fun pickGroups(groups: List<Group>, res: PlayLinkData): List<Group> {
        val withKey = groups.filter { !it.k.isNullOrBlank() }
        val pool = if (res.season != null) {
            withKey.filter { it.season == res.season }.ifEmpty { withKey }
        } else {
            withKey
        }
        return pool.sortedBy { g ->
            val idx = QUALITY_ORDER.indexOf(g.quality?.lowercase() ?: "")
            if (idx < 0) QUALITY_ORDER.size else idx
        }
    }

    private fun pixelDirect(pageUrl: String): String? {
        val id = pageUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (id.length !in 5..15) return null
        return "https://pixeldrain.dev/api/file/$id"
    }

    private suspend fun emitDirect(
        url: String,
        server: String,
        info: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!PlayNet.alive(url, ExtractorLinkType.VIDEO)) return
        val name = PlayLabels.buildLabel("hicine", server, info)
        callback(
            newExtractorLink("[HiCine]", name, url, ExtractorLinkType.VIDEO) {
                this.quality = quality ?: PlayNet.getIndexQuality(info)
            }
        )
    }

    private fun optionRank(id: String?): Int {
        val idx = OPTION_ORDER.indexOf(id)
        return if (idx < 0) OPTION_ORDER.size else idx
    }

    private suspend fun emitResolved(
        option: DlOption,
        url: String,
        info: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = option.id ?: ""
        val server = SERVER_LABELS[id] ?: option.name?.takeIf { it.isNotBlank() } ?: id.ifBlank { "Server" }
        when {
            id == "pixel" || url.contains("pixeldrain.dev/u/") ->
                pixelDirect(url)?.let { emitDirect(it, server, info, quality, callback) }
            id == "gofile" || url.contains("gofile.io") ->
                PlayNet.emitSiteLink("hicine", url, info, quality, null, subtitleCallback, callback)
            url.contains("transfer.it") || url.contains("bzzhr.co") ||
                url.contains("buzzheavier") || url.contains("vikingfile") -> Unit
            else -> emitDirect(url, server, info, quality, callback)
        }
    }

    private suspend fun emitHosts(
        hosts: List<Host>,
        info: String,
        quality: Int?,
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
                            emitResolved(option, url, info, quality, subtitleCallback, callback)
                        }
                    }
                    if (failed.isEmpty()) return@async
                    // the go call drops keys it already used, so ask dl for new ones
                    delay(600L)
                    val second = fetchDlOptions(hostKey).filter { it.id !in PAGE_HOSTS }
                    failed.forEach { option ->
                        val retry = second.firstOrNull { it.id == option.id } ?: return@forEach
                        goUrl(retry)?.let { url ->
                            emitResolved(retry, url, info, quality, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
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
            val picked = pickTitle(hits, res) ?: return
            val detail = fetchTitle(picked.id ?: return) ?: return
            val groups = pickGroups(detail.groups.orEmpty(), res)
            if (groups.isEmpty()) return

            coroutineScope {
                groups.forEach { group ->
                    async(Dispatchers.IO) {
                        val items = fetchGroup(group.k ?: return@async)
                        val scoped = if (res.season != null && res.episode != null) {
                            val exact = items.filter { it.ep == res.episode }
                            if (exact.isNotEmpty()) exact else items.filter { it.ep == null }
                        } else {
                            items.filter { it.ep == null }.ifEmpty { items.take(1) }
                        }
                        val item = scoped.firstOrNull() ?: return@async
                        val head = group.head.orEmpty()
                        val info = head.ifBlank { listOfNotNull(group.quality, group.size).joinToString(" ") }
                        emitHosts(
                            item.hosts.orEmpty(),
                            info,
                            PlayNet.getIndexQuality(head),
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
