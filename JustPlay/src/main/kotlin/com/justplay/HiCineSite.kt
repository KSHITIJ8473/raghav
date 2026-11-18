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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

internal object HiCineSite {
    private const val DEFAULT_API = "https://hicine.biz"

    @Volatile
    private var verifiedApi: String? = null

    private suspend fun servesApi(base: String): Boolean {
        return try {
            app.get("$base/hapi/home", headers = PlayNet.headers(referer = "$base/"), timeout = 8L)
                .text.trimStart().startsWith("{")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun api(): String {
        verifiedApi?.let { return it }
        val remote = FirebaseDomainHelper.getDomain("justplay_hicine")?.trimEnd('/')
            ?.takeIf { it.startsWith("http") }
        for (base in listOfNotNull(remote, DEFAULT_API)) {
            if (servesApi(base)) {
                verifiedApi = base
                return base
            }
        }
        verifiedApi = DEFAULT_API
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

    private val HOST_ORDER = listOf("vcloud", "gdirect", "vdrive")

    // these only open an html download page, they cannot be played
    private val PAGE_HOSTS = setOf("transferit", "buzzheavier", "vikingfile")

    private val QUALITY_ORDER = listOf("2160p", "1080p", "720p", "480p", "1440p", "360p")

    // the dl endpoint tolerates a few parallel hits, go does not
    private val dlGate = Semaphore(4)

    // go refuses everything when called in a burst and answers a lone call
    // after a pause, exactly like a person pressing download again would
    private val goPace = Mutex()
    private var goNotBefore = 0L
    private var goGapMs = 550L
    private const val GO_GAP_MIN = 450L
    private const val GO_GAP_MAX = 2400L

    // when this many servers in a row stay dead the ip is throttled for now
    private const val DEAD_STREAK_LIMIT = 8
    private const val RESOLVE_BUDGET_MS = 50_000L

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

    private class Slot(
        val group: Group,
        val hostKey: String,
        val options: List<DlOption>
    )

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

    private suspend fun <T> atGoPace(block: suspend () -> T): T = goPace.withLock {
        val wait = goNotBefore - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        val out = block()
        goNotBefore = System.currentTimeMillis() + goGapMs
        out
    }

    private fun paceUp() {
        goGapMs = (goGapMs - 120L).coerceAtLeast(GO_GAP_MIN)
    }

    private fun paceDown() {
        goGapMs = (goGapMs + 350L).coerceAtMost(GO_GAP_MAX)
    }

    private suspend fun fetchGoUrl(key: String): String? {
        return atGoPace {
            val text = fetchJson("${api()}/hapi/go?k=${URLEncoder.encode(key, "UTF-8")}&m=u")
            val url = if (text == null) {
                null
            } else {
                try {
                    parseJson<GoResponse>(text).url?.takeIf { it.startsWith("http") }
                } catch (_: Exception) {
                    null
                }
            }
            if (url != null) paceUp() else paceDown()
            url
        }
    }

    // ks and k are two separate tickets for the same file, a refused
    // ks can still leave k playable so both get a chance
    private suspend fun resolveOption(option: DlOption): String? {
        val short = option.ks?.takeIf { it.isNotBlank() }
        val long = option.k?.takeIf { it.isNotBlank() }
        if (short != null) {
            fetchGoUrl(short)?.let { return it }
        }
        if (long != null && long != short) {
            fetchGoUrl(long)?.let { return it }
        }
        return null
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

    private fun optionRank(id: String?): Int {
        val idx = OPTION_ORDER.indexOf(id)
        return if (idx < 0) OPTION_ORDER.size else idx
    }

    private fun hostRank(id: String?): Int {
        val idx = HOST_ORDER.indexOf(id)
        return if (idx < 0) HOST_ORDER.size else idx
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

    private suspend fun collectSlots(
        groups: List<Group>,
        season: Int?,
        episode: Int?
    ): List<Slot> {
        return coroutineScope {
            groups.map { group ->
                async(Dispatchers.IO) {
                    val items = fetchGroup(group.k ?: return@async emptyList())
                    val scoped = if (season != null && episode != null) {
                        val exact = items.filter { it.ep == episode }
                        if (exact.isNotEmpty()) exact else items.filter { it.ep == null }
                    } else {
                        items.filter { it.ep == null }.ifEmpty { items.take(1) }
                    }
                    val item = scoped.firstOrNull() ?: return@async emptyList()
                    item.hosts.orEmpty()
                        .distinctBy { it.id }
                        .sortedBy { hostRank(it.id) }
                        .take(3)
                        .mapNotNull { host ->
                            val key = host.k?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val options = fetchDlOptions(key).filter {
                                it.id !in PAGE_HOSTS && (!it.k.isNullOrBlank() || !it.ks.isNullOrBlank())
                            }
                            if (options.isEmpty()) null else Slot(group, key, options)
                        }
                }
            }.flatMap { it.await() }
        }
    }

    private suspend fun resolveAndEmit(
        slots: List<Slot>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // every encode gets its best server before any encode gets a second one,
        // so a short allowance still covers every quality
        val targets = slots.flatMap { slot ->
            slot.options.sortedBy { optionRank(it.id) }.map { slot to it }
        }.sortedBy { (_, option) -> optionRank(option.id) }

        val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
        val failed = mutableListOf<Pair<Slot, DlOption>>()
        var deadStreak = 0

        coroutineScope {
            for ((slot, option) in targets) {
                if (deadStreak >= DEAD_STREAK_LIMIT || System.currentTimeMillis() > deadline) break
                val url = resolveOption(option)
                if (url == null) {
                    failed.add(slot to option)
                    deadStreak++
                    continue
                }
                deadStreak = 0
                val head = slot.group.head.orEmpty()
                val info = head.ifBlank { listOfNotNull(slot.group.quality, slot.group.size).joinToString(" ") }
                val quality = PlayNet.getIndexQuality(head)
                async(Dispatchers.IO) {
                    emitResolved(option, url, info, quality, subtitleCallback, callback)
                }
            }
        }

        if (failed.isEmpty() || System.currentTimeMillis() > deadline) return
        retryWithFreshKeys(failed, deadline, subtitleCallback, callback)
    }

    private suspend fun retryWithFreshKeys(
        failed: List<Pair<Slot, DlOption>>,
        deadline: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // dl mints new keys on every call, so the ids that came back
        // dead get one more round from a fresh batch
        val wanted = failed.groupBy({ it.first }, { it.second.id })
        var misses = 0
        coroutineScope {
            for ((slot, ids) in wanted) {
                if (System.currentTimeMillis() > deadline || misses >= 3) break
                val fresh = fetchDlOptions(slot.hostKey).filter { it.id in ids }
                if (fresh.isEmpty()) continue
                for (option in fresh) {
                    if (System.currentTimeMillis() > deadline) return@coroutineScope
                    val url = resolveOption(option)
                    if (url == null) {
                        misses++
                    } else {
                        misses = 0
                        val head = slot.group.head.orEmpty()
                        val info = head.ifBlank {
                            listOfNotNull(slot.group.quality, slot.group.size).joinToString(" ")
                        }
                        val quality = PlayNet.getIndexQuality(head)
                        async(Dispatchers.IO) {
                            emitResolved(option, url, info, quality, subtitleCallback, callback)
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

            val slots = collectSlots(groups, res.season, res.episode)
            if (slots.isEmpty()) return

            resolveAndEmit(slots, subtitleCallback, callback)
        } catch (_: Exception) {}
    }
}
