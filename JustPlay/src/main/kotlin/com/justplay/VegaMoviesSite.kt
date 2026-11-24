package com.justplay

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal object VegaMoviesSite {

    private val baseDomains = listOf("https://vegamovies.lease", "https://rogmovies.autos", "https://rogmovies.im")

    @Volatile
    private var remoteDomains: List<String>? = null

    @Volatile
    private var remoteDomainsAt = 0L

    private suspend fun domains(): List<String> {
        val cached = remoteDomains
        if (cached != null && System.currentTimeMillis() - remoteDomainsAt < 5 * 60_000L) return cached
        val overrides = mutableListOf<String>()
        FirebaseDomainHelper.getDomain("justplay_vegamovies")?.let { overrides.add(it) }
        FirebaseDomainHelper.getDomain("vegamovies")?.let { overrides.add(it) }
        FirebaseDomainHelper.getDomain("justplay_rogmovies")?.let { overrides.add(it) }
        FirebaseDomainHelper.getDomain("rogmovies")?.let { overrides.add(it) }
        // the retired hosts all 301 into the live pair, keeping them in the
        // round only stacks duplicate hits on the same two sites
        val merged = if (overrides.isNotEmpty()) overrides.distinct() else baseDomains
        if (overrides.isNotEmpty()) {
            remoteDomains = merged
            remoteDomainsAt = System.currentTimeMillis()
        }
        return merged
    }

    private val deadHosts = Regex("""(?i)megaup|viking|vik1ng|telegram|t\.me""")

    private val junk = Regex(
        """(?i)vglist|t\.me|telegram|imdb\.com|google\.com/search|whatsapp|share|facebook|""" +
            """twitter|pinterest|report|hubcloud\.(foo|cx)|/admin|login|signup|\bidm\b|\bida\b|""" +
            """category|/tag/|/author/|/page/"""
    )

    private val buttonHosts = Regex(
        """(?i)nexdrive|vcloud|vegadrive|fastdl|filebee|filepress|fpgo|gofile|hubcloud|""" +
            """pixeldrain|gpdl|\.r2\.dev|\.r2\.cloudflarestorage\.com|buzzserver|10gbps"""
    )

    private fun isServerLink(href: String): Boolean {
        if (deadHosts.containsMatchIn(href)) return false
        return href.startsWith("http") && buttonHosts.containsMatchIn(href)
    }

    data class VegaDoc(
        val post_title: String? = null,
        val permalink: String? = null,
        val post_thumbnail: String? = null,
        val domain: String = ""
    )

    private suspend fun searchPosts(domain: String, query: String): List<VegaDoc> {
        val url = "$domain/ts-search.php?q=${Uri.encode(query)}&page=1"
        val text = PlayNet.retry(attempts = 2, gapMs = 600L) {
            try {
                val res = PlayNet.fetchWithCf(url, "$domain/", timeout = 12L) ?: return@retry null
                if (res.code == 200) res.text else null
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        return try {
            JSONObject(text).optJSONArray("hits")?.let { hits ->
                (0 until hits.length()).mapNotNull { i ->
                    hits.optJSONObject(i)?.optJSONObject("document")?.let { d ->
                        val title = d.optString("post_title")
                        val link = d.optString("permalink")
                        if (title.isBlank() || link.isBlank()) null
                        else VegaDoc(title, link, d.optString("post_thumbnail"), domain)
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickPosts(posts: List<VegaDoc>, res: PlayLinkData): List<VegaDoc> {
        val title = res.title ?: return emptyList()
        val matched = posts.filter { PlayNet.titleMatchesFor(it.post_title, res) }
        if (matched.isEmpty()) return emptyList()
        if (res.season == null) {
            return matched.sortedByDescending { PlayNet.yearMatches(it.post_title ?: "", res.matchYear) }
        }
        val seasonPosts = matched.filter {
            PlayNet.seasonsOf(it.post_title ?: "")?.contains(res.season) == true &&
                sameShow(it.post_title ?: "", title)
        }
        val withYear = seasonPosts.filter { PlayNet.yearMatches(it.post_title ?: "", res.matchYear) }
        val rest = seasonPosts.filter { it !in withYear }
        // a seasonless post only helps when it is a series itself, otherwise
        // the request lands on some movie that shares the name
        val seasonless = matched.filter {
            PlayNet.seasonsOf(it.post_title ?: "") == null && PlayNet.isSeriesPost(it.post_title)
        }
        return withYear + rest + seasonless
    }

    // credits sometimes lead the name, "Netflix Bridgerton (Season 1-3)"
    private val creditLead = Regex(
        """^(?:netflix|amazonprime|amazon|jiocinema|jiohotstar|jio|hotstar|sonyliv|sony|zee5|zee|""" +
            """disneyplus|disney|appletv|apple|hbo|max|paramount|peacock|hulu|voot|mxplayer|mx|""" +
            """altbalaji|ullu|discovery|tvfplay|tvf|crunchyroll|sunnxt|aha|originals|original|webseries)+"""
    )

    private fun sameShow(postTitle: String, title: String): Boolean {
        var stripped = PlayNet.normalizeTitle(postTitle)
        for (prefix in listOf("download", "watch")) {
            if (stripped.startsWith(prefix)) stripped = stripped.substring(prefix.length)
        }
        val base = PlayNet.normalizeTitle(title)
        if (base.isBlank()) return false
        if (!stripped.startsWith(base)) {
            Regex("^\\d{1,2}").find(stripped)?.let { tag ->
                val cut = stripped.substring(tag.value.length)
                if (cut.startsWith(base)) stripped = cut
            }
        }
        if (!stripped.startsWith(base)) {
            creditLead.find(stripped)?.let { lead ->
                val cut = stripped.substring(lead.value.length)
                if (cut.startsWith(base)) stripped = cut
            }
        }
        if (!stripped.startsWith(base)) return false
        val rest = stripped.substring(base.length)
        // streaming credits and release years sit between the name and the
        // season line, a marker still has to show up close behind the name
        return rest.isEmpty() || Regex("""season|s\d|part|chapter|vol""").containsMatchIn(rest.take(40))
    }

    class QualityGroup(
        val label: String,
        val links: MutableList<Pair<String, String>> = mutableListOf()
    )

    class SeasonBlock(val title: String, val number: Int) {
        val groups: MutableList<QualityGroup> = mutableListOf()
    }

    class PostDetail(
        val seasons: List<SeasonBlock>,
        val movieGroups: List<QualityGroup>
    )

    private fun parsePost(html: String): PostDetail? {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst(".entry-content, .post-content, article") ?: doc
        val qualityRegex = Regex("""\b(480p|720p|1080p|2160p|4K|60FPS)\b""", RegexOption.IGNORE_CASE)

        val seasons = mutableListOf<SeasonBlock>()
        val movieGroups = mutableListOf<QualityGroup>()

        // layouts differ per post: "Season 8 ... 480p" can sit in any heading
        // level, some posts use a lone season line with quality lines below it
        val seasonMark = Regex("""(?i)season\s*(\d{1,2})|part\s*(\d{1,2})|\bS(\d{1,2})\b""")
        val skipHead = Regex("""(?i)info|synopsis|screenshot|comment|winding""")

        fun groupsBelow(head: org.jsoup.nodes.Element, group: QualityGroup) {
            var p: org.jsoup.nodes.Element? = head.nextElementSibling()
            while (p != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(p.tagName())) {
                collectButtons(p, group)
                p = p.nextElementSibling()
            }
        }

        fun blockFor(num: Int, label: String): SeasonBlock =
            seasons.firstOrNull { it.number == num } ?: SeasonBlock(label, num).also { seasons.add(it) }

        var openSeason: SeasonBlock? = null
        for (head in content.select("h1, h2, h3, h4, h5")) {
            val label = head.text().replace(Regex("""\s+"""), " ").trim()
            // title and intro lines name the whole post, not one quality
            if (label.isBlank() || skipHead.containsMatchIn(label)) continue
            // a download heading that names exactly one quality is the
            // button line itself, the rest name the whole post
            val qualityCount = qualityRegex.findAll(label).count()
            if (label.contains("Download", true) && qualityCount != 1) continue
            val m = seasonMark.find(label)
            val num = m?.groupValues?.let { g ->
                g[1].toIntOrNull() ?: g[2].toIntOrNull() ?: g[3].toIntOrNull()
            }
            val hasQuality = qualityRegex.containsMatchIn(label)
            when {
                num != null && hasQuality -> {
                    val block = blockFor(num, label)
                    val group = QualityGroup(label)
                    groupsBelow(head, group)
                    if (group.links.isNotEmpty()) block.groups.add(group)
                    openSeason = null
                }
                num != null -> {
                    // some posts name the season on its own line with the
                    // buttons right below and no quality word at all
                    val block = blockFor(num, label)
                    val group = QualityGroup(label)
                    groupsBelow(head, group)
                    if (group.links.isNotEmpty()) block.groups.add(group)
                    openSeason = block
                }
                hasQuality -> {
                    val group = QualityGroup(label)
                    groupsBelow(head, group)
                    if (group.links.isEmpty()) continue
                    val block = openSeason
                    if (block != null) block.groups.add(group) else movieGroups.add(group)
                }
            }
        }
        seasons.removeAll { it.groups.isEmpty() }
        if (seasons.isEmpty() && movieGroups.isEmpty()) return null
        return PostDetail(seasons, movieGroups)
    }

    private fun collectButtons(scope: org.jsoup.nodes.Element, group: QualityGroup) {
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!isServerLink(href)) continue
            if (junk.containsMatchIn(href)) continue
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            group.links.add(label to href)
        }
    }

    class DrivePage(
        val servers: List<Pair<String, String>>,
        val episodes: Map<Int, List<Pair<String, String>>>
    )

    private val pageCache = ConcurrentHashMap<String, DrivePage>()

    private suspend fun fetchDrivePage(url: String): DrivePage? {
        pageCache[url]?.let { return it }
        if (pageCache.size > 40) pageCache.clear()
        val res = PlayNet.fetchDrivePage(url, "https://nexdrive.beer/") ?: return null
        val html = res.text
        if (Regex("""(?i)file (is )?deleted|not found""").containsMatchIn(html)) return null
        val doc = res.document
        val scope = doc.selectFirst(".entry, article, .post-inner") ?: doc

        val servers = mutableListOf<Pair<String, String>>()
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank() || !isServerLink(href)) continue
            if (junk.containsMatchIn(href) || junk.containsMatchIn(label)) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            servers.add(label to href)
        }

        val episodes = LinkedHashMap<Int, List<Pair<String, String>>>()
        for (head in scope.select("h3, h4, h5")) {
            val text = head.text()
            val num = Regex("""(?i)episodes?\s*:?\s*0*(\d{1,3})""").find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val links = mutableListOf<Pair<String, String>>()
            var sib = head.nextElementSibling()
            while (sib != null && !listOf("h3", "h4", "h5").contains(sib.tagName())) {
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                        ?: a.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isBlank() || !isServerLink(href)) continue
                    if (junk.containsMatchIn(href) || junk.containsMatchIn(label)) continue
                    links.add(label to href)
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty() && !episodes.containsKey(num)) {
                episodes[num] = links
            }
        }
        val page = DrivePage(servers.distinctBy { it.second }, episodes)
        pageCache[url] = page
        return page
    }

    class Server(val name: String, val url: String)

    suspend fun resolve(href: String): List<Server> {
        if (!href.startsWith("http") || deadHosts.containsMatchIn(href)) return emptyList()
        val host = hostOf(href)
        return try {
            when {
                host.contains("vcloud") -> vcloudServers(href)
                host.contains("vegadrive") -> listOfNotNull(vegadriveSkydrop(href))
                host.contains("fastdl") -> fastdlDirect(href)?.let { listOf(Server("G-Direct", it)) } ?: emptyList()
                host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") ->
                    filepressDirect(href)?.let { listOf(Server("Filepress", it)) } ?: emptyList()
                host.contains("gofile") -> gofileDirect(href)?.let { listOf(Server("GoFile", it)) } ?: emptyList()
                host.contains("gpdl") -> gpdlDirect(href)?.let { listOf(Server("10Gbps", it)) } ?: emptyList()
                host.contains("hubcloud") -> hubcloudServers(href)
                else -> directServer(href)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun directServer(href: String): List<Server> {
        val h = href.lowercase()
        return when {
            h.contains(".r2.cloudflarestorage.com") -> listOf(Server("FSLv2", href))
            h.contains(".r2.dev") -> listOf(Server("FSL", href))
            h.contains("pixeldrain") -> listOf(Server("PixelDrain", pixelApi(href)))
            else -> emptyList()
        }
    }

    private fun pixelApi(href: String): String {
        val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
        return "${PlayNet.getBaseUrl(href)}/api/file/$id"
    }

    private fun vcloudHeaders(referer: String?): Map<String, String> {
        val h = PlayNet.browserHeaders(referer).toMutableMap()
        h["Cookie"] = "xla=s4t"
        return h
    }

    private suspend fun vcloudGet(url: String, referer: String?): String? {
        return try {
            app.get(url, headers = vcloudHeaders(referer), timeout = 25L).text
        } catch (_: Exception) {
            null
        }
    }

    private val vcloudTokenRegex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]""")
    private val plainUrlRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")

    private suspend fun vcloudTarget(url: String): Pair<String, String>? {
        val first = vcloudGet(url, "https://nexdrive.beer/") ?: return null
        var target = vcloudTokenRegex.find(first)?.groupValues?.get(1)
            ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
            ?: plainUrlRegex.find(first)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        if (target != null && target.startsWith("http")) return url to target
        val hop = Jsoup.parse(first).selectFirst("div.main h4 a")?.attr("href")?.trim() ?: return null
        val hopUrl = if (hop.startsWith("http")) hop else PlayNet.getBaseUrl(url) + hop
        val hopHtml = vcloudGet(hopUrl, url) ?: return null
        target = vcloudTokenRegex.find(hopHtml)?.groupValues?.get(1)
            ?.let { runCatching { base64Decode(base64Decode(it.trim())) }.getOrNull() }
            ?: plainUrlRegex.find(hopHtml)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
        return target?.takeIf { it.startsWith("http") }?.let { hopUrl to it }
    }

    private fun serverNameOf(label: String, href: String): String? {
        val low = label.lowercase()
        val h = href.lowercase()
        return when {
            low.contains("fslv2") || low.contains("fsl v2") || low.contains("fsl2") -> "FSLv2"
            low.contains("fsl") -> "FSL"
            low.contains("pixelserver") || low.contains("pixel server") || low.contains("pixeldrain") -> "PixelDrain"
            low.contains("gofile") -> "GoFile"
            low.contains("10gbps") -> "10Gbps"
            low.contains("buzzserver") -> "BuzzServer"
            low.contains("instant download") || low.contains("instant dl") -> "Instant Download"
            low.contains("vegadrive") || low.contains("v-drive") || low.contains("skydrop") -> "V-Drive"
            low.contains("vcloud") || low.contains("v-cloud") -> "V-Cloud"
            low.contains("g-direct") || low.contains("gdirect") || low.contains("fastdl") -> "G-Direct"
            low.contains("filepress") || low.contains("filebee") -> "Filepress"
            h.contains(".r2.cloudflarestorage.com") -> "FSLv2"
            h.contains(".r2.dev") -> "FSL"
            h.contains("pixeldrain") -> "PixelDrain"
            h.contains("gpdl") -> "10Gbps"
            else -> null
        }
    }

    private suspend fun vcloudServers(url: String): List<Server> {
        val (from, target) = vcloudTarget(url) ?: return emptyList()
        val html = vcloudGet(target, from) ?: return emptyList()
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Server>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (junk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            val name = serverNameOf(label, href) ?: continue
            addServer(out, name, href)
        }

        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && it.contains("pixeldrain") }
            ?.let { addServer(out, "PixelDrain", it) }

        return out.distinctBy { it.url }
    }

    private suspend fun addServer(out: MutableList<Server>, name: String, href: String) {
        when (name) {
            "10Gbps" -> gpdlDirect(href)?.let { out.add(Server(name, it)) }
            "GoFile" -> gofileDirect(href)?.let { out.add(Server(name, it)) }
            "PixelDrain" -> out.add(Server(name, pixelApi(href)))
            else -> out.add(Server(name, href))
        }
    }

    private suspend fun hubcloudServers(url: String): List<Server> {
        val res = try {
            app.get(url, headers = PlayNet.browserHeaders(), timeout = 20L)
        } catch (_: Exception) {
            return emptyList()
        }
        val doc = Jsoup.parse(res.text)
        val header = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
        if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return emptyList()
        val generate = doc.select("a.btn, a[download]")
            .firstOrNull { it.text().contains("generate", true) }
            ?.attr("href")?.trim() ?: return emptyList()
        val genUrl = if (generate.startsWith("http")) generate else PlayNet.getBaseUrl(res.url) + generate
        val genRes = try {
            app.get(genUrl, headers = PlayNet.browserHeaders(res.url), timeout = 25L)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<Server>()
        for (a in Jsoup.parse(genRes.text).select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (junk.containsMatchIn(href)) continue
            val label = a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            val name = serverNameOf(label, href) ?: continue
            addServer(out, name, href)
        }
        return out.distinctBy { it.url }
    }

    // vegadrive gate keeps a laravel session, the bridge needs those cookies
    // before download-link answers
    private suspend fun vegadriveSkydrop(url: String): Server? {
        val page = try {
            app.get(url, headers = PlayNet.browserHeaders("https://nexdrive.beer/"), timeout = 20L)
        } catch (_: Exception) {
            return null
        }
        val base = PlayNet.getBaseUrl(page.url)
        val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#")
            .substringAfter("/d/").substringBefore("/")
        if (token.isBlank() || token == url) return null

        val jar = mutableMapOf<String, String>()
        fun collect(res: com.lagradost.nicehttp.NiceResponse) {
            for (cookie in res.headers.values("set-cookie")) {
                val pair = cookie.substringBefore(";").trim()
                if (pair.contains("=")) jar[pair.substringBefore("=")] = pair
            }
        }
        fun cookieHeader(): String = jar.values.joinToString("; ")

        val gate = try {
            val h = PlayNet.browserHeaders("$base/s/$token").toMutableMap()
            if (cookieHeader().isNotBlank()) h["Cookie"] = cookieHeader()
            app.get("$base/d/$token/skydrop", headers = h, timeout = 15L)
        } catch (_: Exception) {
            return null
        }
        if (gate.code != 200) return null
        collect(gate)
        val prepareUrl = Regex("""["']([^"']*__vega_bridge/prepare/[^"']+)["']""")
            .find(gate.text)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return null

        for (attempt in 0 until 4) {
            val res = try {
                val h = PlayNet.browserHeaders("$base/d/$token/skydrop").toMutableMap()
                h["Accept"] = "application/json"
                if (cookieHeader().isNotBlank()) h["Cookie"] = cookieHeader()
                app.get(prepareUrl, headers = h, timeout = 15L)
            } catch (_: Exception) {
                return null
            }
            collect(res)
            val parsed = runCatching { JSONObject(res.text) }.getOrNull()
            if (res.code == 200 && parsed?.optBoolean("ready") != true) {
                delay((parsed?.optInt("retry_after_ms", 2500) ?: 2500).toLong().coerceAtMost(3000L))
                continue
            }
            if (res.code != 200) return null
            break
        }

        for (attempt in 0 until 3) {
            val res = try {
                val h = PlayNet.browserHeaders("$base/d/$token/skydrop").toMutableMap()
                h["Accept"] = "application/json"
                if (cookieHeader().isNotBlank()) h["Cookie"] = cookieHeader()
                app.get(
                    "$base/download-link/$token/skydrop",
                    headers = h,
                    timeout = 15L
                )
            } catch (_: Exception) {
                return null
            }
            val parsed = runCatching { JSONObject(res.text) }.getOrNull()
            val link = parsed?.optString("download_url").orEmpty()
            if (res.code == 200 && link.startsWith("http")) return Server("V-Drive", link)
            if (res.code != 202) return null
            delay((parsed?.optInt("retry_after_ms", 2500) ?: 2500).toLong().coerceAtMost(3000L))
        }
        return null
    }

    private suspend fun fastdlDirect(url: String): String? {
        return try {
            val res = app.get(url, headers = PlayNet.browserHeaders(), timeout = 20L)
            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(res.text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(res.text)?.groupValues?.get(1)
                ?: return null
            val carrier = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(reurl)?.groupValues?.get(1)?.let { wrapped ->
                val padded = if (wrapped.length % 4 > 0) {
                    wrapped + "=".repeat(4 - wrapped.length % 4)
                } else wrapped
                runCatching { base64Decode(padded) }.getOrNull()
            } ?: reurl
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1)
                ?: return null
            val direct = URLDecoder.decode(encoded, "UTF-8")
            if (direct.startsWith("http")) direct else null
        } catch (_: Exception) {
            null
        }
    }

    private val filePressId = Regex("""/file/([a-f0-9]{16,40})""")

    // the filebee api died, its ids answer on the filepress domain now,
    // dotflix shares moved behind a turnstile so only the index task answers
    private suspend fun filepressDirect(url: String): String? {
        val id = filePressId.find(url)?.groupValues?.get(1) ?: return null
        for (base in listOf("https://new4.filepress.lat", "https://filebee.xyz")) {
            val task = filepressTask(id, "indexDownlaod", base)
            if (task != null && task.matches(Regex("[a-f0-9]{16,40}"))) {
                filepressFinal(task, base)?.let { return it }
            }
        }
        return null
    }

    private suspend fun filepressTask(id: String, method: String, base: String): String? {
        return try {
            val res = app.post(
                "$base/api/file/downlaod/",
                headers = PlayNet.browserHeaders("$base/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", base)
                },
                json = JSONObject()
                    .put("captchaValue", "")
                    .put("id", id)
                    .put("method", method)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("status")) return null
            (parsed.opt("data") as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun filepressFinal(taskId: String, base: String): String? {
        return try {
            val res = app.post(
                "$base/api/file/downlaod2/",
                headers = PlayNet.browserHeaders("$base/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", base)
                },
                json = JSONObject()
                    .put("captchaValue", JSONObject.NULL)
                    .put("id", taskId)
                    .put("method", "indexDownlaod")
                    .toString(),
                timeout = 35L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.startsWith("http") }
                is org.json.JSONArray -> (0 until data.length())
                    .firstNotNullOfOrNull { i -> data.optString(i).takeIf { it.startsWith("http") } }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun gofileDirect(url: String): String? {
        val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
        if (code.isBlank()) return null
        return try {
            val api = "https://api.gofile.io"
            val token = JSONObject(app.post("$api/accounts", timeout = 15L).text)
                .getJSONObject("data").getString("token")
            val wt = runCatching {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15L).text
                )?.groupValues?.get(1)
            }.getOrNull()
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
            val data = JSONObject(
                app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = 20L).text
            ).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return null
            var best: String? = null
            var bestSize = -1L
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(child.optString("name"))) continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                if (child.optLong("size", 0L) > bestSize) {
                    bestSize = child.optLong("size", 0L)
                    best = link
                }
            }
            best
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun gpdlDirect(url: String): String? {
        var cur = url
        try {
            for (i in 0 until 5) {
                val res = app.get(cur, headers = PlayNet.browserHeaders(), allowRedirects = false, timeout = 15L)
                val loc = res.headers["location"]
                if (loc.isNullOrBlank() || res.code !in 301..308) break
                cur = if (loc.startsWith("http")) loc else PlayNet.getBaseUrl(cur) + loc
            }
        } catch (_: Exception) {
            return null
        }
        for (pair in cur.substringAfter("?", "").split("&")) {
            if (pair.substringBefore("=") != "link") continue
            val value = pair.substringAfter("=")
            val direct = if (value.contains("%")) URLDecoder.decode(value, "UTF-8") else value
            return direct.takeIf { it.startsWith("http") }
        }
        return null
    }

    private fun infoFrom(groupLabel: String): String =
        groupLabel.replace(Regex("""(?i)\bdownload\s+now\b|\bdownload\b"""), "")
            .replace(Regex("""\s+"""), " ").trim()

    private fun isDvEncode(label: String): Boolean =
        Regex("""(?i)\bDV\b|dolby|hybrid""").containsMatchIn(label)

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return

            val candidates = domains()
            suspend fun searchRound(q: String): List<VegaDoc> = coroutineScope {
                candidates.map { domain ->
                    async(Dispatchers.IO) { searchPosts(domain, q) }
                }.flatMap { it.await() }
            }

            var ranked: List<VegaDoc> = emptyList()
            for (query in PlayNet.searchQueries(res)) {
                val posts = searchRound(query)
                if (posts.isEmpty()) continue
                ranked = pickPosts(posts, res)
                if (ranked.isNotEmpty()) break
            }
            // mirrors of the same post answer on one page, trying them twice
            // only burns time
            val unique = ranked.distinctBy { it.permalink?.substringAfterLast("download") ?: it.permalink }
            if (unique.isEmpty()) return

            val claimed = ConcurrentHashMap.newKeySet<String>()
            val seenUrls = ConcurrentHashMap.newKeySet<String>()

            // presigned cdn links answer or the host is gone, probing them
            // only stalls the first links
            val trustedCdn = Regex(
                """(?i)\.r2\.dev|\.r2\.cloudflarestorage\.com|pixeldrain\.dev/api|""" +
                    """googleusercontent\.com"""
            )

            suspend fun offer(server: Server, tier: Int, info: String, dv: Boolean) {
                if (!seenUrls.add(server.url)) return
                val key = "${server.name}|$tier|${if (dv) "dv" else ""}"
                if (key in claimed) return
                if (!trustedCdn.containsMatchIn(server.url) &&
                    !PlayNet.alive(server.url, ExtractorLinkType.VIDEO)
                ) return
                if (claimed.add(key)) {
                    callback(
                        newExtractorLink(
                            "[VegaMovies]",
                            PlayLabels.buildLabel("vegamovies", server.name, info),
                            server.url,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = tier
                        }
                    )
                }
            }

            fun targetsOf(page: DrivePage): List<Pair<String, String>> {
                return if (res.episode != null) {
                    page.episodes[res.episode] ?: page.servers.takeIf { page.episodes.isEmpty() }
                } else {
                    page.servers
                }.orEmpty()
            }

            // series pages chain two drive hops before a playable url shows up,
            // walking them one by one burns the site budget on slow networks
            suspend fun walkTargets(targets: List<Pair<String, String>>, tier: Int, info: String, dv: Boolean) {
                coroutineScope {
                    val gate = Semaphore(4)
                    targets.forEach { (_btn, href) ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                val next = if (href.contains("nexdrive", true)) {
                                    val nested = fetchDrivePage(href) ?: return@withPermit
                                    targetsOf(nested)
                                } else {
                                    listOf("" to href)
                                }
                                for ((_btn2, href2) in next) {
                                    for (server in resolve(href2)) {
                                        offer(server, tier, info, dv)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // pages age out or their buttons move behind a join gate, the next
            // candidate post still carries the same files
            for (post0 in unique.take(3)) {
                val permalink = post0.permalink?.takeIf { it.isNotBlank() } ?: continue
                val domain = post0.domain.ifBlank {
                    candidates.firstOrNull { permalink.contains(it.removePrefix("https://"), true) }
                        ?: candidates.first()
                }
                val postUrl = PlayNet.absolute(permalink, domain)
                val html = try {
                    PlayNet.retry(attempts = 2, gapMs = 500L) {
                        val res = PlayNet.fetchWithCf(postUrl, "$domain/") ?: return@retry null
                        if (res.code == 200) res.text else null
                    }
                } catch (_: Exception) {
                    null
                } ?: continue
                val detail = parsePost(html) ?: continue

                val groups: List<QualityGroup> = if (res.season != null) {
                    val inSeason = detail.seasons.filter { it.number == res.season }.flatMap { it.groups }
                    when {
                        inSeason.isNotEmpty() -> inSeason
                        PlayNet.seasonsOf(post0.post_title ?: "")?.contains(res.season) == true -> detail.movieGroups
                        detail.seasons.size == 1 -> detail.seasons.first().groups
                        else -> emptyList()
                    }
                } else {
                    // "Part 1" in a movie title parses as one fake season, the
                    // post is still a movie when no series words show up
                    if (detail.movieGroups.isEmpty() && detail.seasons.isNotEmpty() &&
                        !PlayNet.isSeriesPost(post0.post_title ?: "")
                    ) {
                        detail.seasons.flatMap { it.groups }
                    } else {
                        detail.movieGroups
                    }
                }
                if (groups.isEmpty()) continue

                coroutineScope {
                    val gate = Semaphore(6)
                    groups.forEach { group ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                val tier = PlayNet.getIndexQuality(group.label)
                                val info = infoFrom(group.label)
                                val dv = isDvEncode(group.label)
                                coroutineScope {
                                    val inner = Semaphore(4)
                                    group.links.distinctBy { it.second }.forEach { (_label, url) ->
                                        async(Dispatchers.IO) {
                                            inner.withPermit {
                                                val page = fetchDrivePage(url) ?: return@withPermit
                                                walkTargets(targetsOf(page), tier, info, dv)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return
            }
        } catch (_: Exception) {}
    }
}
