package com.justplay

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object MoviesLinkBdSite {

    private const val HUB_URL = "https://movielinkbd.com"
    private const val DEFAULT_DOMAIN = "https://qhetvv.movielinkbd.at"

    @Volatile
    private var workingDomain: String? = null

    @Volatile
    private var hubDomain: String? = null

    @Volatile
    private var hubCheckedAt = 0L

    // the hub only lists mirrors that link straight into content paths
    private val mirrorRegex = Regex(
        "https?://([a-z0-9-]+\\.movielinkbd\\.(?:at|li|tv|app|net))(?=/[a-z])",
        RegexOption.IGNORE_CASE
    )

    private val keyRegex = Regex("var K=\"([a-f0-9]{24})\"")

    // the file token is base64 of the path, XORed with a keystream from the seed
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

    // the go token is base64url XORed with the page key
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
        val text = try {
            app.get(HUB_URL, headers = PlayNet.headers(), timeout = 15L).text
        } catch (_: Exception) {
            null
        } ?: return hubDomain
        val hosts = mirrorRegex.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .filter { it != "movielinkbd.com" && it != "www.movielinkbd.com" }
            .distinct()
            .toList()
        val pick = hosts.firstOrNull { it.endsWith(".movielinkbd.at") }
            ?: hosts.firstOrNull { it.endsWith(".movielinkbd.li") }
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

    // /go/ tokens only resolve for the session that opened the file page, the
    // cookie set has to ride along through the whole chain
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

    private suspend fun searchPosts(domain: String, query: String, session: BdSession): List<BdPost> {
        val res = PlayNet.retry {
            try {
                app.get(
                    "$domain/search?q=${Uri.encode(query)}",
                    headers = PlayNet.headers(),
                    cookies = session.with(),
                    timeout = 15L
                )
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
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
        // wrong year is a different movie
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
            // single season shows often ship an empty season attribute
            val cardSeason = card.attr("data-season-number").toIntOrNull()
            if (cardSeason != null && cardSeason != season) return@firstOrNull false
            if (card.attr("data-episode-number").toIntOrNull() == episode) return@firstOrNull true
            val start = card.attr("data-range-start").toIntOrNull()
            val end = card.attr("data-range-end").toIntOrNull()
            start != null && start == end && start == episode
        }
    }

    // the episode grid is built client side, the server markup only keeps
    // h5 headings with the download buttons as plain siblings after them
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

    private data class BdDetail(val pageUrl: String, val files: List<BdFile>)

    private data class BdFile(val info: String, val path: String)

    private suspend fun loadDetail(
        domain: String,
        postUrl: String,
        res: PlayLinkData,
        session: BdSession
    ): BdDetail? {
        val response = try {
            app.get(
                postUrl,
                headers = PlayNet.headers("$domain/"),
                cookies = session.with(),
                timeout = 20L
            )
        } catch (_: Exception) {
            null
        } ?: return null
        session.absorb(response)
        val doc = response.document
        val pageUrl = response.url
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
        return BdDetail(pageUrl, files)
    }

    private class GoRow(val server: String, val path: String)

    private suspend fun goTarget(
        domain: String,
        row: GoRow,
        session: BdSession,
        referer: String,
        key: String
    ): String? {
        return try {
            val res = app.get(
                PlayNet.absolute(row.path, domain),
                headers = PlayNet.headers(referer),
                cookies = session.with("mlbd_gk" to key),
                allowRedirects = false,
                timeout = 15L
            )
            session.absorb(res)
            val loc = res.headers["location"]?.trim().orEmpty()
            res.close()
            loc.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun emitInstant(
        fileUrl: String,
        info: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = fileUrl.trimEnd('/') + "/download"
        if (!PlayNet.alive(url, ExtractorLinkType.VIDEO)) return
        val name = PlayLabels.buildLabel("movielinkbd", "Instant Download", info)
        try {
            callback.invoke(
                newExtractorLink("[MovieLinkBD]", name, url, ExtractorLinkType.VIDEO) {
                    this.quality = quality ?: Qualities.Unknown.value
                }
            )
        } catch (_: Exception) {}
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
        val response = try {
            app.get(
                fileUrl,
                headers = PlayNet.headers(pageUrl),
                cookies = session.with(),
                timeout = 20L
            )
        } catch (_: Exception) {
            null
        } ?: return
        session.absorb(response)
        val text = response.text
        val key = keyRegex.find(text)?.groupValues?.get(1) ?: return
        val rows = try {
            response.document.select("a.dlh-arow[data-g]").mapNotNull { a ->
                val path = decodeGo(a.attr("data-g"), key) ?: return@mapNotNull null
                val row = a.text().lowercase()
                val server = when {
                    row.contains("hubcloud") -> "HubCloud"
                    row.contains("instant") -> "Instant Download"
                    else -> return@mapNotNull null
                }
                GoRow(server, path)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val quality = PlayNet.getIndexQuality(file.info)
        rows.forEach { row ->
            val target = goTarget(domain, row, session, fileUrl, key) ?: return@forEach
            if (row.server == "Instant Download") {
                emitInstant(target, file.info, quality, callback)
            } else {
                PlayNet.emitOwnLink(
                    "movielinkbd", target, file.info, quality, fileUrl,
                    subtitleCallback, callback
                )
            }
        }
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
                    val found = searchPosts(candidate, query, session)
                    if (found.isEmpty()) continue
                    val pick = pickPost(found, res) ?: continue
                    val loaded = loadDetail(candidate, pick.url, res, session) ?: continue
                    if (loaded.files.isEmpty()) continue
                    domain = candidate
                    detail = loaded
                    break@outer
                }
            }
            if (domain == null || detail == null) return
            workingDomain = domain

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
