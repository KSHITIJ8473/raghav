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

// both sites run the same theme, api and drive pages, one site object covers vegamovies and rogmovies
internal object VegaMoviesSite {

    private val domains = listOf("https://new1.vegamovies.lease", "https://rogmovies.one")

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

    // the typesense proxy throws 5xx under load, retries keep it usable
    private suspend fun searchPosts(domain: String, query: String): List<VegaDoc> {
        val url = "$domain/ts-search.php?q=${Uri.encode(query)}&page=1"
        val text = PlayNet.retry(attempts = 3, gapMs = 800L) {
            try {
                val res = app.get(url, headers = PlayNet.browserHeaders("$domain/"), timeout = 15L)
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

    private fun pickPost(posts: List<VegaDoc>, res: PlayLinkData): VegaDoc? {
        val title = res.title ?: return null
        val matched = posts.filter { PlayNet.titleMatchesFor(it.post_title, res) }
        if (matched.isEmpty()) return null
        return if (res.season != null) {
            val seasonPosts = matched.filter {
                PlayNet.seasonsOf(it.post_title ?: "")?.contains(res.season) == true &&
                    sameShow(it.post_title ?: "", title)
            }
            if (seasonPosts.isNotEmpty()) {
                seasonPosts.firstOrNull { PlayNet.yearMatches(it.post_title ?: "", res.matchYear) } ?: seasonPosts.first()
            }
            matched.firstOrNull { PlayNet.seasonsOf(it.post_title ?: "") == null }
        } else {
            // a post from another year is a different movie, war 2007 is not war 2019
            matched.firstOrNull { PlayNet.yearMatches(it.post_title ?: "", res.matchYear) }
        }
    }

    // a spin off keeps its own words between the show name and the season tag, a post
    // of the base show only ever continues with a season word, a part, a year or nothing
    private fun sameShow(postTitle: String, title: String): Boolean {
        var stripped = PlayNet.normalizeTitle(postTitle)
        for (prefix in listOf("download", "watch")) {
            if (stripped.startsWith(prefix)) stripped = stripped.substring(prefix.length)
        }
        val base = PlayNet.normalizeTitle(title)
        if (base.isBlank() || !stripped.startsWith(base)) return false
        val rest = stripped.substring(base.length)
        return rest.isEmpty() ||
            Regex("""^(season|part|chapter|s\d|vol(ume)?|19\d\d|20\d\d)""").containsMatchIn(rest)
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

    // a season headline followed by quality headlines whose paragraphs carry the server buttons
    private fun parsePost(html: String): PostDetail? {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst(".entry-content, .post-content, article") ?: doc
        val qualityRegex = Regex("""\b(480p|720p|1080p|2160p|4K|60FPS)\b""", RegexOption.IGNORE_CASE)

        fun groupsAfter(head: org.jsoup.nodes.Element): MutableList<QualityGroup> {
            val out = mutableListOf<QualityGroup>()
            var sib: org.jsoup.nodes.Element? = head.nextElementSibling()
            while (sib != null) {
                val tag = sib.tagName()
                if (tag == "h1" || tag == "h2") break
                if (tag == "h3" || tag == "h4" || tag == "h5") {
                    val label = sib.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isBlank() || !qualityRegex.containsMatchIn(label)) {
                        sib = sib.nextElementSibling()
                        continue
                    }
                    val group = QualityGroup(label)
                    var p: org.jsoup.nodes.Element? = sib.nextElementSibling()
                    while (p != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(p.tagName())) {
                        collectButtons(p, group)
                        p = p.nextElementSibling()
                    }
                    if (group.links.isNotEmpty()) out.add(group)
                }
                sib = sib.nextElementSibling()
            }
            return out
        }

        val seasons = mutableListOf<SeasonBlock>()
        val seasonHeads = content.select("h1, h2").filter { el ->
            Regex("""(?i)season\s*\d+|part\s*\d+""").containsMatchIn(el.text())
        }
        for (head in seasonHeads) {
            val num = Regex("""(?i)season\s*(\d+)""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""(?i)part\s*(\d+)""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val season = SeasonBlock(head.text().replace(Regex("""\s+"""), " ").trim(), num)
            season.groups.addAll(groupsAfter(head))
            if (season.groups.isNotEmpty()) seasons.add(season)
        }

        val movieGroups = mutableListOf<QualityGroup>()
        if (seasons.isEmpty()) {
            for (head in content.select("h3, h4, h5")) {
                val label = head.text().replace(Regex("""\s+"""), " ").trim()
                if (label.isBlank() || !qualityRegex.containsMatchIn(label)) continue
                val group = QualityGroup(label)
                var sib: org.jsoup.nodes.Element? = head.nextElementSibling()
                while (sib != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(sib.tagName())) {
                    collectButtons(sib, group)
                    sib = sib.nextElementSibling()
                }
                if (group.links.isNotEmpty()) movieGroups.add(group)
            }
        }
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

    // the middle hop every button leads to, it carries servers or one section per episode
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

    // a resolved server ready for the picker
    class Server(val name: String, val url: String)

    // routes one button to its direct files, every server the target page offers comes back named
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

    // a link that is already the file needs no hop, it only has to be named
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

    // vcloud blocks plain clients, the site's own js sets this cookie on first visit
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

    // the share page hides the token page in a double base64 variable, the token page carries every server
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

    // names the download buttons of a page after the server behind them
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

        // the page hides a second pixeldrain behind a js variable, the button alone misses it
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") && it.contains("pixeldrain") }
            ?.let { addServer(out, "PixelDrain", it) }

        return out.distinctBy { it.url }
    }

    // turns a named button into its direct file, dead ones stay out of the list
    private suspend fun addServer(out: MutableList<Server>, name: String, href: String) {
        when (name) {
            "10Gbps" -> gpdlDirect(href)?.let { out.add(Server(name, it)) }
            "GoFile" -> gofileDirect(href)?.let { out.add(Server(name, it)) }
            "PixelDrain" -> out.add(Server(name, pixelApi(href)))
            else -> out.add(Server(name, href))
        }
    }

    // a hubcloud share page only names the real servers on its generate page
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

    // vegadrive only answers skydrop on the json api and mints the google mirror asynchronously
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
        for (attempt in 0 until 3) {
            val res = try {
                app.get(
                    "$base/download-link/$token/skydrop",
                    headers = PlayNet.browserHeaders("$base/s/$token"),
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

    // the embed page wraps the google url in a redirect variable, the link= param is the file
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

    // the react page only adds a turnstile, the whole api sits on the short domain
    private suspend fun filepressDirect(url: String): String? {
        val id = filePressId.find(url)?.groupValues?.get(1) ?: return null
        val base = "https://filebee.xyz"

        // the dotflix route survives, the direct task worker hands out expiring links
        val share = filepressTask(id, "dotFlixDownlaod", base)
        if (share != null && share.startsWith("http")) {
            resolveDotFlix(share)?.let { return it }
        }

        val task = filepressTask(id, "indexDownlaod", base)
        if (task != null && task.matches(Regex("[a-f0-9]{16,40}"))) {
            return filepressFinal(task, base)
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

    // the share page carries a per file code in a btoa call, reversed base64 goes to the extract endpoint
    private suspend fun resolveDotFlix(shareUrl: String): String? {
        return try {
            val text = app.get(
                shareUrl,
                headers = PlayNet.browserHeaders("https://new3.dotflix.shop/"),
                timeout = 20L
            ).text
            val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1) ?: return null
            val obfuscated = java.util.Base64.getEncoder()
                .encodeToString(code.toByteArray()).reversed()
            val requestId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val timestamp = System.currentTimeMillis().toString()
            val res = app.post(
                "https://dotflix.fit/api/extract-download",
                headers = PlayNet.browserHeaders("https://new3.dotflix.shop/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("X-Request-ID", requestId)
                    put("X-Timestamp", timestamp)
                    put("Origin", "https://new3.dotflix.shop")
                },
                json = JSONObject()
                    .put("requestId", requestId)
                    .put("timestamp", timestamp)
                    .put("data", obfuscated)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = JSONObject(res.text)
            if (!parsed.optBoolean("success")) return null
            parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    // gofile folders hold sample files next to the video, the biggest child is the movie
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

    // the gpdl button bounces through a worker and a generator page that only names the file in its query
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

    // the post buttons all read download now, the quality headline above them is the real label
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

            // both catalogs are searched side by side, every round runs both domains
            suspend fun searchRound(q: String): List<VegaDoc> = coroutineScope {
                domains.map { domain ->
                    async(Dispatchers.IO) { searchPosts(domain, q) }
                }.flatMap { it.await() }
            }

            // the year keeps the lookup on the right post, the bare title alone buries
            // it under every war named series and movie the two catalogs carry
            var post: VegaDoc? = null
            for (query in PlayNet.searchQueries(res)) {
                val posts = searchRound(query)
                if (posts.isEmpty()) continue
                post = pickPost(posts, res)
                if (post != null) break
            }
            val post0 = post ?: return
            val permalink = post0.permalink?.takeIf { it.isNotBlank() } ?: return
            // permalinks come back relative, the domain the search ran on is the only reliable origin
            val domain = post0.domain.ifBlank {
                domains.firstOrNull { permalink.contains(it.removePrefix("https://"), true) }
                    ?: domains.first()
            }
            val postUrl = PlayNet.absolute(permalink, domain)
            val html = try {
                app.get(postUrl, headers = PlayNet.browserHeaders("$domain/"), timeout = 20L).text
            } catch (_: Exception) {
                return
            }
            val detail = parsePost(html) ?: return

            val groups: List<QualityGroup> = if (res.season != null) {
                val inSeason = detail.seasons.filter { it.number == res.season }.flatMap { it.groups }
                when {
                    inSeason.isNotEmpty() -> inSeason
                    detail.seasons.isNotEmpty() ->
                        detail.seasons.filter { s -> PlayNet.seasonsOf(s.title) == null }.flatMap { it.groups }
                    // a one season post keeps every quality block under the post title and
                    // the title names the season, so the blocks are that season's files
                    PlayNet.seasonsOf(post0.post_title ?: "")?.contains(res.season) == true -> detail.movieGroups
                    else -> emptyList()
                }
            } else {
                detail.movieGroups
            }
            if (groups.isEmpty()) return

            val claimed = ConcurrentHashMap.newKeySet<String>()
            val seenUrls = ConcurrentHashMap.newKeySet<String>()

            // the picker fills up as servers come in, one slot per server, quality and encode
            suspend fun offer(server: Server, tier: Int, info: String, dv: Boolean) {
                if (!seenUrls.add(server.url)) return
                val key = "${server.name}|$tier|${if (dv) "dv" else ""}"
                if (key in claimed) return
                if (!PlayNet.alive(server.url, ExtractorLinkType.VIDEO)) return
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

            suspend fun walkTargets(targets: List<Pair<String, String>>, tier: Int, info: String, dv: Boolean) {
                for ((_btn, href) in targets) {
                    val next = if (href.contains("nexdrive", true)) {
                        val nested = fetchDrivePage(href) ?: continue
                        if (res.episode != null) {
                            nested.episodes[res.episode] ?: nested.servers.takeIf { nested.episodes.isEmpty() }
                        } else {
                            nested.servers
                        }.orEmpty()
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

            coroutineScope {
                val gate = Semaphore(6)
                groups.forEach { group ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val tier = PlayNet.getIndexQuality(group.label)
                            val info = infoFrom(group.label)
                            val dv = isDvEncode(group.label)
                            for ((label, url) in group.links.distinctBy { it.second }) {
                                val page = fetchDrivePage(url) ?: continue
                                val targets = if (res.episode != null) {
                                    page.episodes[res.episode] ?: page.servers.takeIf { page.episodes.isEmpty() }
                                } else {
                                    page.servers
                                }.orEmpty()
                                walkTargets(targets, tier, info, dv)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
