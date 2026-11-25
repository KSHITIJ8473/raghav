package com.justplay

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object MoviesLinkBdSite {

    private const val TAG = "MoviesLinkBd"

    private const val HUB_URL = "https://movielinkbd.com"
    private const val DEFAULT_DOMAIN = "https://movielinkbd.tv"

    @Volatile
    private var workingDomain: String? = null

    @Volatile
    private var hubDomain: String? = null

    @Volatile
    private var hubCheckedAt = 0L

    // the hub page publishes the current content origin, subdomain mirrors are long gone
    private val mirrorRegex = Regex(
        """https?://((?:[a-z0-9-]+\.)?movielinkbd\.(?:tv|app|one|info|work|shop|li|net))(?![a-z0-9-])""",
        RegexOption.IGNORE_CASE
    )

    private val keyRegex = Regex("var K=\"([a-f0-9]{24})\"")

    // some ISPs answer movielinkbd lookups with a block page or NXDOMAIN,
    // so those hosts resolve over DoH before falling back to system DNS
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    private val dohDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!hostname.contains("movielinkbd")) return Dns.SYSTEM.lookup(hostname)
            dnsCache[hostname]?.let { return it }
            val resolved = resolveDnsDoH(hostname)
            if (resolved.isNotEmpty()) {
                dnsCache[hostname] = resolved
                return resolved
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohProbe = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun resolveDnsDoH(hostname: String): List<InetAddress> {
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://8.8.8.8/resolve?name=$hostname&type=A"
        )
        for (url in endpoints) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                dohProbe.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val ips = Regex("""\"data\"\s*:\s*\"([0-9.]+)\"""")
                        .findAll(body).map { it.groupValues[1] }.toList()
                    if (ips.isNotEmpty()) return ips.map { InetAddress.getByName(it) }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private val bdApp by lazy {
        Requests(app.baseClient.newBuilder().dns(dohDns).build())
    }

    private val cfLocks = ConcurrentHashMap<String, Mutex>()

    private fun usable(res: NiceResponse): Boolean =
        res.code in 200..399 || res.headers["location"] != null

    private suspend fun fetch(
        url: String,
        referer: String?,
        cookies: Map<String, String>,
        allowRedirects: Boolean,
        timeout: Long,
        client: Requests,
        interceptor: Interceptor? = null
    ): NiceResponse? = try {
        client.get(
            url,
            headers = PlayNet.headers(referer),
            cookies = cookies,
            allowRedirects = allowRedirects,
            timeout = timeout,
            interceptor = interceptor
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun bdGet(
        url: String,
        referer: String? = null,
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true,
        timeout: Long = 20L
    ): NiceResponse? {
        val plain = fetch(url, referer, cookies, allowRedirects, timeout, app)
        if (plain != null && !PlayNet.isCfChallenge(plain) && usable(plain)) return plain
        plain?.close()

        val doh = fetch(url, referer, cookies, allowRedirects, timeout, bdApp)
        if (doh != null && !PlayNet.isCfChallenge(doh) && usable(doh)) return doh

        val challenged = (plain != null && PlayNet.isCfChallenge(plain)) ||
            (doh != null && PlayNet.isCfChallenge(doh))
        doh?.close()
        if (!challenged) return null

        return cfLocks.getOrPut(PlayNet.hostOf(url)) { Mutex() }.withLock {
            val killer = PlayNet.killerFor(url)
            runCatching { killer.savedCookies.remove(URI(url).host) }
            val solved = fetch(url, referer, cookies, allowRedirects, 60L, bdApp, killer)
            if (solved != null && !PlayNet.isCfChallenge(solved) && usable(solved)) {
                solved
            } else {
                solved?.close()
                null
            }
        }
    }

    private fun decodeDl(payload: String, seedHex: String, rounds: Int): String? {
        return try {
            val raw = Base64.decode(payload, Base64.DEFAULT)
            var x = seedHex.toLong(16) and 0xFFFFFFFFL
            repeat(rounds) { x = (1103515245L * x + 12345L) and 0xFFFFFFFFL }
            for (i in raw.indices) {
                x = (1103515245L * x + 12345L) and 0xFFFFFFFFL
                raw[i] = ((x ushr 16).toInt() xor raw[i].toInt()).toByte()
            }
            String(raw, Charsets.UTF_8).trim()
                .takeIf { it.startsWith("/file/") }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeGo(token: String, key: String): String? {
        return try {
            var b64 = token.replace("-", "+").replace("_", "/")
            val rem = b64.length % 4
            if (rem != 0) b64 += "=".repeat(4 - rem)
            val raw = Base64.decode(b64, Base64.DEFAULT)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor key[i % key.length].code).toChar())
            }
            out.toString().trim().takeIf { it.startsWith("/go/") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun hubDomain(): String? {
        if (System.currentTimeMillis() - hubCheckedAt < 10 * 60_000L) return hubDomain
        hubCheckedAt = System.currentTimeMillis()
        val res = bdGet(HUB_URL, timeout = 15L) ?: return hubDomain
        val text = try {
            res.text
        } catch (_: Exception) {
            ""
        }
        res.close()
        val hosts = mirrorRegex.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
        val pick = hosts.firstOrNull { it == "movielinkbd.tv" }
            ?: hosts.firstOrNull { it.endsWith(".movielinkbd.tv") }
            ?: hosts.firstOrNull()
        hubDomain = pick?.let { "https://$it" }
        return hubDomain
    }

    private suspend fun candidates(): List<String> {
        val list = mutableListOf<String>()
        workingDomain?.let { list.add(it) }
        FirebaseDomainHelper.getDomain("justplay_movielinkbd")?.let { list.add(it) }
        FirebaseDomainHelper.getDomain("movielinkbd")?.let { list.add(it) }
        hubDomain()?.let { list.add(it) }
        list.add(DEFAULT_DOMAIN)
        return list.distinct()
    }

    private data class BdPost(val title: String, val url: String)

    private class BdSession {
        private val cookies = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun absorb(res: NiceResponse) {
            try {
                cookies.putAll(res.cookies)
            } catch (_: Exception) {
            }
        }

        fun with(vararg extra: Pair<String, String>): Map<String, String> {
            val out = HashMap<String, String>(cookies)
            for ((k, v) in extra) out[k] = v
            return out
        }
    }

    // null means the domain itself is unreachable, empty means the search had no hits
    private suspend fun searchPosts(domain: String, query: String, session: BdSession): List<BdPost>? {
        val res = bdGet(
            "$domain/search?q=${Uri.encode(query)}",
            cookies = session.with(),
            timeout = 15L
        ) ?: return null
        session.absorb(res)
        val doc = try {
            res.document
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            doc.select("div.movie-card").mapNotNull { card ->
                val a = card.selectFirst("a[href]") ?: return@mapNotNull null
                val href = a.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                val img = card.selectFirst("img")
                val title = img?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: a.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                BdPost(title, PlayNet.absolute(href, domain))
            }.distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickPost(posts: List<BdPost>, res: PlayLinkData): BdPost? {
        val matched = posts.filter { PlayNet.titleMatchesFor(it.title, res) }
        if (matched.isEmpty()) return null
        if (res.season != null) {
            val seasonPosts = matched.filter { PlayNet.seasonsOf(it.title)?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { PlayNet.yearMatches(it.title, res.matchYear) }
                    ?: seasonPosts.first()
            }
            return matched.firstOrNull { PlayNet.seasonsOf(it.title) == null }
        }
        return matched.firstOrNull { PlayNet.yearMatches(it.title, res.matchYear) }
    }

    private fun buttonLabel(a: Element): String {
        val bracket = Regex("\\[([^\\]]+)\\]").find(a.text())?.groupValues?.get(1)
        return bracket?.trim().orEmpty()
    }

    private fun decodeButton(a: Element): String? {
        val payload = a.attr("data-dl")
        val seed = a.attr("data-ds")
        val rounds = a.attr("data-dr").toIntOrNull() ?: return null
        if (payload.isBlank() || seed.isBlank()) return null
        return decodeDl(payload, seed, rounds)
    }

    private fun episodeCard(doc: Document, season: Int, episode: Int): Element? {
        return doc.select("div.ep-card").firstOrNull { card ->
            val cardSeason = card.attr("data-season-number").toIntOrNull()
            if (cardSeason != null && cardSeason != season) return@firstOrNull false
            if (card.attr("data-episode-number").toIntOrNull() == episode) return@firstOrNull true
            val start = card.attr("data-range-start").toIntOrNull()
            val end = card.attr("data-range-end").toIntOrNull()
            start != null && start == end && start == episode
        }
    }

    private fun episodeButtons(doc: Document, season: Int, episode: Int): List<Element> {
        val epTag = Regex("(?i)Episode\\s*:?\\s*0*$episode(?!\\d)")
        val seasonTag = Regex("(?i)\\bS0*$season\\b")
        val anySeason = Regex("(?i)\\bS\\d{1,2}\\b")
        for (h5 in doc.select("h5")) {
            val text = h5.text()
            if (!epTag.containsMatchIn(text)) continue
            if (anySeason.containsMatchIn(text) && !seasonTag.containsMatchIn(text)) continue
            val out = mutableListOf<Element>()
            var sib: Element? = h5.nextElementSibling()
            while (sib != null && sib.tagName() != "h5") {
                if (sib.tagName() == "a" && sib.hasAttr("data-dl")) out.add(sib)
                out.addAll(sib.select("a[data-dl][data-ds][data-dr]"))
                sib = sib.nextElementSibling()
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private data class BdDetail(
        val pageUrl: String,
        val files: List<BdFile>,
        val watchPath: String? = null
    )

    private data class BdFile(val info: String, val path: String)

    private suspend fun loadDetail(
        domain: String,
        postUrl: String,
        res: PlayLinkData,
        session: BdSession
    ): BdDetail? {
        val response = bdGet(
            postUrl,
            referer = "$domain/",
            cookies = session.with(),
            timeout = 20L
        ) ?: return null
        session.absorb(response)
        val doc = response.document
        val pageUrl = response.url
        val watchPath = doc.selectFirst("iframe#mlbdHdFrame[src]")?.attr("src")?.trim()
            ?.takeIf { it.startsWith("/p/") }
        val buttons: List<Element> = if (res.season == null) {
            doc.select("a[data-dl][data-ds][data-dr]")
        } else {
            val episode = res.episode ?: return null
            val scoped = episodeButtons(doc, res.season, episode)
            when {
                scoped.isNotEmpty() -> scoped
                else -> episodeCard(doc, res.season, episode)
                    ?.select("a[data-dl][data-ds][data-dr]")
                    ?: emptyList()
            }
        }
        val files = buttons.mapNotNull { a ->
            val path = decodeButton(a) ?: return@mapNotNull null
            val label = buttonLabel(a)
            val info = if (res.season != null && label.isNotBlank()) {
                "Episode ${res.episode} $label"
            } else {
                label
            }
            BdFile(info, path)
        }.distinctBy { it.path }
        return BdDetail(pageUrl, files, watchPath)
    }

    private class BdRow(val host: String, val path: String)

    private fun fileRows(doc: Document, key: String): List<BdRow> {
        val rows = mutableListOf<BdRow>()
        for (a in doc.select("a.dlh-arow")) {
            if (a.className().contains("dlh-promo")) continue
            val host = a.attr("data-host").trim().lowercase()
            if (host.endsWith("-promo")) continue
            val path = if (a.hasAttr("data-g")) {
                decodeGo(a.attr("data-g"), key) ?: continue
            } else {
                a.attr("href").trim().takeIf { it.startsWith("/") } ?: continue
            }
            val name = host.ifBlank {
                val text = a.text().lowercase()
                when {
                    text.contains("xcloud") -> "xcloud"
                    text.contains("instant") -> "instantcloud"
                    else -> ""
                }
            }
            rows.add(BdRow(name, path))
        }
        return rows.distinctBy { it.path }
    }

    private suspend fun goTarget(
        domain: String,
        path: String,
        session: BdSession,
        referer: String,
        key: String
    ): String? {
        val res = bdGet(
            PlayNet.absolute(path, domain),
            referer = referer,
            cookies = session.with("mlbd_gk" to key),
            allowRedirects = false,
            timeout = 15L
        ) ?: return null
        session.absorb(res)
        val loc = res.headers["location"]?.trim().orEmpty()
        res.close()
        return loc.takeIf { it.startsWith("http") }
    }

    private val directMedia = Regex(
        """(?i)\.(mp4|mkv|webm|mov|m3u8)(?:\?|$)|\.r2\.cloudflarestorage\.com/|googleusercontent\.com/|\.r2\.dev/"""
    )

    private suspend fun emitDirect(
        url: String,
        server: String,
        info: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return
        if (!PlayNet.alive(url, ExtractorLinkType.VIDEO)) return
        val name = PlayLabels.buildLabel("movielinkbd", server, info)
        try {
            callback(
                newExtractorLink("[MovieLinkBD]", name, url, ExtractorLinkType.VIDEO) {
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {}
    }

    private suspend fun emitFsl(
        url: String,
        info: String,
        quality: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = PlayNet.resolveRedirectTarget(url, referer)
        if (target != null && !PlayNet.hostOf(target).contains("fsldownload")) {
            emitDirect(target, "FSL", info, quality, callback)
            return
        }
        val res = PlayNet.fetchWithCf(url, referer) ?: return
        val doc = res.document
        res.close()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.startsWith("http") && directMedia.containsMatchIn(href)) {
                emitDirect(href, "FSL", info, quality, callback)
                return
            }
        }
    }

    private suspend fun emitSubFile(
        domain: String,
        path: String,
        info: String,
        quality: Int?,
        referer: String,
        session: BdSession,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = bdGet(
            PlayNet.absolute(path, domain),
            referer = referer,
            cookies = session.with(),
            timeout = 20L
        ) ?: return
        session.absorb(res)
        val doc = res.document
        val pageUrl = res.url
        res.close()
        for (a in doc.select("a.dlh-cta[href], div.fsl-action-area a[href], a[download][href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            when {
                href.contains("fsldownload") || PlayNet.hostOf(href).startsWith("fsl.") ->
                    emitFsl(href, info, quality, pageUrl, callback)
                directMedia.containsMatchIn(href) -> emitDirect(href, "Instant MP4", info, quality, callback)
                else -> PlayNet.emitOwnLink(
                    "movielinkbd", href, info, quality, pageUrl,
                    subtitleCallback, callback
                )
            }
        }
        try {
            PlayHubCloud.emitHubServers(
                doc, PlayNet.getBaseUrl(pageUrl), pageUrl, subtitleCallback
            ) { link ->
                callback(
                    ExtractorLink(
                        "[${PlayLabels.siteName("movielinkbd")}]",
                        PlayLabels.buildLabel("movielinkbd", link.name, info),
                        link.url,
                        link.referer,
                        quality ?: link.quality,
                        link.headers,
                        link.extractorData,
                        link.type
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun emitRow(
        domain: String,
        row: BdRow,
        info: String,
        quality: Int?,
        referer: String,
        key: String,
        session: BdSession,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            row.path.startsWith("/go/") -> {
                val target = goTarget(domain, row.path, session, referer, key) ?: return
                if (PlayNet.hostOf(target).contains("movielinkbd")) return
                if (row.host.contains("xcloud") || PlayNet.hostOf(target).contains("xcloud")) {
                    emitXCloud(target, info, quality, referer, subtitleCallback, callback)
                } else {
                    PlayNet.emitOwnLink(
                        "movielinkbd", target, info, quality, referer,
                        subtitleCallback, callback
                    )
                }
            }
            row.path.startsWith("/file/") -> {
                emitSubFile(domain, row.path, info, quality, referer, session, subtitleCallback, callback)
            }
        }
    }

    private suspend fun emitFile(
        domain: String,
        file: BdFile,
        pageUrl: String,
        session: BdSession,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileUrl = PlayNet.absolute(file.path, domain)
        val response = bdGet(
            fileUrl,
            referer = pageUrl,
            cookies = session.with(),
            timeout = 20L
        ) ?: return
        session.absorb(response)
        val text = response.text
        val key = keyRegex.find(text)?.groupValues?.get(1) ?: return
        val rows = fileRows(response.document, key)
        val quality = PlayNet.getIndexQuality(file.info)
        val semaphore = Semaphore(4)
        coroutineScope {
            rows.forEach { row ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        emitRow(domain, row, file.info, quality, fileUrl, key, session, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    private val xcloudGate = Mutex()
    private val mediaPattern = Regex("""(?i)\.(m3u8|mp4|mkv)(?:\?|$)""")

    private fun pd2Json(text: String): org.json.JSONObject? {
        val start = text.indexOf("window.__PD2")
        if (start < 0) return null
        var i = text.indexOf('{', start)
        if (i < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\' && inString) {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return try {
                            org.json.JSONObject(text.substring(text.indexOf('{', start), i + 1))
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private data class HlsVariant(val url: String, val height: Int)

    private fun parseVariants(master: String): List<HlsVariant> {
        val out = mutableListOf<HlsVariant>()
        val lines = master.lines()
        var pending: Int? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                pending = Regex("""RESOLUTION=\d+x(\d+)""").find(trimmed)
                    ?.groupValues?.get(1)?.toIntOrNull()
            } else if (!trimmed.startsWith("#") && trimmed.isNotEmpty() && pending != null) {
                out.add(HlsVariant(trimmed, pending))
                pending = null
            }
        }
        return out
    }

    private suspend fun emitWatch(
        domain: String,
        watchPath: String,
        res: PlayLinkData,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = try {
                bdGet(
                    PlayNet.absolute(watchPath, domain),
                    referer = "$domain/",
                    timeout = 15L
                )?.text
            } catch (_: Exception) {
                null
            } ?: return
            val config = pd2Json(page) ?: return
            var master = config.optString("master").takeIf { it.startsWith("http") }
            var label = "Watch"
            // the muxed progressive file survives hls worker token expiry, keep it as fallback
            var mp4Url: String? = null
            if (res.season != null) {
                val episode = res.episode ?: return
                val episodes = config.optJSONArray("episodes") ?: return
                var found: String? = null
                for (i in 0 until episodes.length()) {
                    val ep = episodes.optJSONObject(i) ?: continue
                    if (ep.optInt("season", -1) != res.season) continue
                    val from = ep.optInt("number", -1)
                    val to = ep.optInt("num_end", from)
                    if (episode in from..to) {
                        found = ep.optString("master").takeIf { it.startsWith("http") }
                        mp4Url = ep.optString("mp4").takeIf { it.startsWith("http") }
                        if (found != null) break
                    }
                }
                master = found
                label = "Episode $episode Watch"
            } else {
                mp4Url = config.optJSONArray("episodes")?.optJSONObject(0)
                    ?.optString("mp4")?.takeIf { it.startsWith("http") }
            }
            if (master == null && mp4Url == null) return

            val streamHeaders = mapOf("Origin" to domain, "Referer" to "$domain/")
            var best: Int? = null
            if (master != null) {
                val playlist = try {
                    app.get(master, headers = PlayNet.headers() + streamHeaders, timeout = 15L).text
                } catch (_: Exception) {
                    null
                }
                if (playlist != null && playlist.trimStart().startsWith("#EXTM3U")) {
                    val variants = parseVariants(playlist)
                    best = variants.maxOfOrNull { it.height } ?: Qualities.Unknown.value
                    val name = PlayLabels.buildLabel("movielinkbd", label, "Multi Audio")
                    try {
                        callback(
                            newExtractorLink(
                                "[${PlayLabels.siteName("movielinkbd")}]",
                                name,
                                master,
                                ExtractorLinkType.M3U8
                            ) {
                                this.quality = best
                                this.headers = streamHeaders
                            }
                        )
                    } catch (_: Exception) {}
                    variants.sortedByDescending { it.height }.take(4).forEach { variant ->
                        try {
                            callback(
                                newExtractorLink(
                                    "[${PlayLabels.siteName("movielinkbd")}]",
                                    PlayLabels.buildLabel("movielinkbd", "$label ${variant.height}p", ""),
                                    variant.url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.quality = variant.height
                                    this.headers = streamHeaders
                                }
                            )
                        } catch (_: Exception) {}
                    }
                }
            }
            mp4Url?.let { direct ->
                try {
                    callback(
                        newExtractorLink(
                            "[${PlayLabels.siteName("movielinkbd")}]",
                            PlayLabels.buildLabel("movielinkbd", "$label MP4", ""),
                            direct,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = best ?: Qualities.Unknown.value
                            this.headers = streamHeaders
                        }
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private suspend fun emitXCloud(
        url: String,
        info: String,
        quality: Int?,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = PlayNet.fetchWithCf(url, referer)
            if (res != null && res.isSuccessful) {
                val doc = res.document
                res.close()
                val emitted = PlayHubCloud.emitHubServers(
                    doc, PlayNet.getBaseUrl(url), url, subtitleCallback
                ) { link ->
                    callback(
                        ExtractorLink(
                            "[${PlayLabels.siteName("movielinkbd")}]",
                            PlayLabels.buildLabel("movielinkbd", link.name, info),
                            link.url,
                            link.referer,
                            quality ?: link.quality,
                            link.headers,
                            link.extractorData,
                            link.type
                        )
                    )
                }
                if (emitted) return
            } else {
                res?.close()
            }

            val resolved = xcloudGate.withLock {
                val resolver = WebViewResolver(
                    interceptUrl = mediaPattern,
                    additionalUrls = listOf(mediaPattern),
                    script = """document.querySelector('a#download, .vd a.btn, .vd a')?.click();""",
                    useOkhttp = false,
                    timeout = 30_000L
                )
                try {
                    app.get(url, referer = referer, interceptor = resolver).url
                } catch (_: Exception) {
                    null
                }
            } ?: return
            if (!mediaPattern.containsMatchIn(resolved)) return
            val type = if (resolved.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    "[${PlayLabels.siteName("movielinkbd")}]",
                    PlayLabels.buildLabel("movielinkbd", "XCloud", info),
                    resolved,
                    type
                ) {
                    this.quality = quality ?: Qualities.Unknown.value
                    this.headers = mapOf("Referer" to url)
                }
            )
        } catch (_: Exception) {}
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return

            var domain: String? = null
            var detail: BdDetail? = null
            val session = BdSession()
            outer@ for (candidate in candidates()) {
                for (query in PlayNet.searchQueries(res)) {
                    val found = searchPosts(candidate, query, session) ?: break
                    if (found.isEmpty()) continue
                    val pick = pickPost(found, res) ?: continue
                    val loaded = loadDetail(candidate, pick.url, res, session) ?: continue
                    if (loaded.files.isEmpty()) continue
                    domain = candidate
                    detail = loaded
                    break@outer
                }
            }
            if (domain == null || detail == null) {
                Log.d(TAG, "no source found for ${res.title}")
                return
            }
            workingDomain = domain

            detail.watchPath?.let { watchPath ->
                emitWatch(domain, watchPath, res, callback)
            }

            val semaphore = Semaphore(4)
            coroutineScope {
                detail.files.take(8).forEach { file ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            emitFile(domain, file, detail.pageUrl, session, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
