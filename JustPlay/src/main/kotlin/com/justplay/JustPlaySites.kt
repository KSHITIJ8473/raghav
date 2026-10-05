package com.justplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI

internal object DrivePages {
    // hosts that never carry a playable file: cloudflare walled mirrors, site plumbing and ad jumps,
    // gdtot, filebee and filepress stay out because loadExtractor may still resolve them
    private val junk = Regex(
        "gmpg\\.org|googleapis|googletagmanager|fonts\\.|schema\\.org|w3\\.org|" +
            "twitter\\.com|facebook\\.com|pinterest|whatsapp|telegram|t\\.me/|/tg/|catimages|tinyurl|bonuscaf|" +
            "winexch|a-ads|pixabay|i-poster|scene-source|vglist|vegamovies-apk|hdhub4u\\.download|hdhub4u\\.tv"
    )

    private fun isSelfLink(href: String, hosts: Set<String>): Boolean {
        return try {
            hosts.contains(URI(href).host?.lowercase())
        } catch (_: Exception) {
            false
        }
    }

    private fun episodeRegexes(episode: Int, season: Int?): List<Regex> {
        val s2 = (season ?: 1).toString().padStart(2, '0')
        return listOf(
            Regex("(?i)Episodes?\\s*:?\\s*0*$episode(?!\\d)"),
            Regex("(?i)\\b${s2}\\s*[xXeE]\\s*0*$episode(?!\\d)"),
            Regex("(?i)\\b${season ?: 1}\\s*[xX]\\s*$episode(?!\\d)")
        )
    }

    private fun headingLinks(doc: Document, regexes: List<Regex>): List<Pair<String, String>>? {
        val heads = doc.select("h2, h3, h4, h5").filter { el ->
            val text = el.text().replace('\u00A0', ' ')
            regexes.any { it.containsMatchIn(text) }
        }
        if (heads.isEmpty()) return null
        val links = mutableListOf<Pair<String, String>>()
        for (head in heads) {
            val label = head.text().replace(Regex("\\s+"), " ").trim()
            var sib = head.nextElementSibling()
            while (sib != null && sib.tagName() !in listOf("h2", "h3", "h4", "h5")) {
                sib.select("a[href]").forEach { a ->
                    links.add(a.attr("href").trim() to label)
                }
                sib = sib.nextElementSibling()
            }
        }
        return links.ifEmpty { null }
    }

    suspend fun emit(
        site: String,
        driveUrl: String,
        episode: Int?,
        season: Int? = null,
        label: String = "",
        siteDomain: String? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // mobilejsr rest loops redirects for some visitors, nexdrive serves the same app
            val fetchUrl = driveUrl.replace("mobilejsr.rest", "nexdrive.fit")
            val res = PlayNet.fetchDrivePage(fetchUrl, referer)
                ?: throw Exception("drive page unreachable")
            val doc = res.document
            // season packs arrive as zip archives, no player can open them
            val pageTitle = doc.title().substringBefore(" – ").substringBefore(" - ").trim()
            if (pageTitle.contains("zip", true)) return
            val driveHost = try {
                URI(res.url).host?.lowercase()
            } catch (_: Exception) {
                null
            }
            val hosts = listOfNotNull(driveHost, siteDomain?.let {
                try {
                    URI(it).host?.lowercase()
                } catch (_: Exception) {
                    null
                }
            }).toSet()

            if (episode != null) {
                val links = headingLinks(doc, episodeRegexes(episode, season))
                if (links != null) {
                    val picked = links.mapNotNull { (href, headLabel) ->
                        if (href.startsWith("http") && !junk.containsMatchIn(href) && !isSelfLink(href, hosts)) {
                            href to headLabel
                        } else null
                    }.distinctBy { it.first }
                    coroutineScope {
                        picked.forEach { (href, headLabel) ->
                            async(Dispatchers.IO) {
                                val quality = listOf(headLabel, label).firstNotNullOfOrNull {
                                    PlayNet.getIndexQuality(it).takeIf { q -> q != Qualities.Unknown.value }
                                }
                                PlayNet.emitOwnLink(site, href, "Episode $episode", quality, fetchUrl, subtitleCallback, callback)
                            }
                        }
                    }
                    return
                }
                val markers = episodeRegexes(episode, season)
                val row = doc.select("a[href]").firstOrNull { a ->
                    val href = a.attr("href").trim()
                    href.startsWith("http") && !junk.containsMatchIn(href) &&
                        markers.any { it.containsMatchIn(a.text()) }
                }
                if (row != null) {
                    val href = row.attr("href").trim()
                    if (!isSelfLink(href, hosts)) {
                        PlayNet.emitOwnLink(site, href, "Episode $episode", null, fetchUrl, subtitleCallback, callback)
                        return
                    }
                }
                return
            }

            val external = doc.select("a[href]").mapNotNull { el ->
                val href = el.attr("href").trim()
                if (!href.startsWith("http")) null
                else if (junk.containsMatchIn(href) || isSelfLink(href, hosts)) null
                else href
            }.distinct()
            // movies4u hands over a bare drive url, the drive page title carries
            // the same quality and size info the other sites put in headings
            val fallbackTitle = if (label.isBlank()) {
                doc.title().substringBefore(" – ").substringBefore(" - ").trim()
            } else ""
            val effectiveLabel = label.ifBlank { fallbackTitle }
            val quality = if (effectiveLabel.isBlank()) null else PlayNet.getIndexQuality(effectiveLabel)
            val info = effectiveLabel
            coroutineScope {
                external.forEach { href ->
                    async(Dispatchers.IO) {
                        PlayNet.emitOwnLink(site, href, info, quality, fetchUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object VegaMoviesSite {
    private const val DEFAULT_DOMAIN = "https://vegamovies.gallery"

    private fun headers(api: String): Map<String, String> = mapOf(
        "User-Agent" to PLAY_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "cookie" to "xla=s4t",
        "Referer" to "$api/"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaDoc(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("post_title") val postTitle: String? = null,
        @JsonProperty("permalink") val permalink: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaHit(val document: VegaDoc? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VegaResponse(val hits: List<VegaHit> = emptyList())

    private suspend fun fetchResults(api: String, query: String): List<VegaDoc> = try {
        val text = app.get(
            "$api/search.php?q=${Uri.encode(query)}",
            headers = headers(api),
            timeout = 15L
        ).text
        AppUtils.parseJson<VegaResponse>(text).hits.mapNotNull { it.document }
    } catch (_: Exception) {
        emptyList()
    }

    // the search api happily answers with pets movies for a walter mitty query,
    // so only a real title, year and season match is allowed to resolve
    private fun pickDoc(docs: List<VegaDoc>, res: PlayLinkData): VegaDoc? {
        val title = res.title ?: return null
        docs.firstOrNull { res.imdbId != null && it.imdbId.equals(res.imdbId, ignoreCase = true) }?.let { return it }
        val matched = docs.filter { PlayNet.titleMatches(it.postTitle, title) }
        if (res.season != null) {
            val seasonPosts = matched.filter { PlayNet.seasonsOf(it.postTitle ?: "")?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { PlayNet.yearMatches(it.postTitle ?: "", res.matchYear) } ?: seasonPosts.first()
            }
            return matched.firstOrNull { PlayNet.seasonsOf(it.postTitle ?: "") == null }
        }
        return matched.firstOrNull { PlayNet.yearMatches(it.postTitle ?: "", res.matchYear) }
            ?: matched.firstOrNull()
    }

    // the download anchors below a heading belong to that heading's label,
    // a button counts when it is a dwd button or points at a known drive host
    private fun collectRows(doc: Document, season: Int?): List<Pair<String, String>> {
        val seasonRegex = season?.let { Regex("(?i)Season\\s*$it(?!\\d)|\\bS${it.toString().padStart(2, '0')}\\b") }
        val heads = doc.select("h3, h4, h5").filter { el ->
            val text = el.text().replace('\u00A0', ' ')
            seasonRegex == null || seasonRegex.containsMatchIn(text)
        }
        val targets = mutableListOf<Pair<String, String>>()
        for (head in heads) {
            val label = head.text().replace(Regex("\\s+"), " ").trim()
            var sib = head.nextElementSibling()
            while (sib != null && sib.tagName() !in listOf("h3", "h4", "h5")) {
                for (a in sib.select("a[href]")) {
                    val text = a.text()
                    val href = a.attr("href").trim()
                    if (!href.startsWith("http")) continue
                    if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                    if (a.selectFirst("button.dwd-button") != null ||
                        text.contains("V-Cloud", true) || text.contains("G-Direct", true) ||
                        listOf(
                            "nexdrive", "mobilejsr", "fastdl", "vcloud", "hubcloud", "gdflix",
                            "gdlink", "gdtot", "filebee", "filepress", "pixeldrain", "gofile",
                            "dropgalaxy", "hubdrive", "hubcdn", "hblinks"
                        ).any { href.contains(it, true) }
                    ) {
                        targets.add(href to label)
                    }
                }
                sib = sib.nextElementSibling()
            }
        }
        return targets.distinctBy { it.first }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val api = FirebaseDomainHelper.getDomain("justplay_vegamovies")
                ?: FirebaseDomainHelper.getDomain("vegamovies")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val imdbId = res.imdbId?.takeIf { it.isNotBlank() }

            val docs = if (imdbId != null) {
                val byImdb = fetchResults(api, imdbId)
                val imdbMatch = byImdb.firstOrNull { it.imdbId.equals(imdbId, ignoreCase = true) }
                if (imdbMatch != null) listOf(imdbMatch) else fetchResults(api, title)
            } else {
                fetchResults(api, title)
            }
            if (docs.isEmpty()) return
            val target = pickDoc(docs, res) ?: return
            val permalink = target.permalink?.takeIf { it.isNotBlank() } ?: return
            val postUrl = if (permalink.startsWith("http")) permalink else api + permalink
            val doc = app.get(postUrl, headers = headers(api), timeout = 20L).document

            val rows = collectRows(doc, res.season)
            if (res.season == null) {
                coroutineScope {
                    rows.forEach { (link, label) ->
                        async(Dispatchers.IO) {
                            DrivePages.emit(
                                "vegamovies", link, null, null, label, api, api,
                                subtitleCallback, callback
                            )
                        }
                    }
                }
            } else {
                rows.forEach { (link, label) ->
                    DrivePages.emit(
                        "vegamovies", link, res.episode, res.season, label, api, api,
                        subtitleCallback, callback
                    )
                }
            }
        } catch (_: Exception) {}
    }
}

internal object HdHub4uSite {
    private const val DEFAULT_DOMAIN = "https://new6.hdhub4u.cl"

    private suspend fun typesenseSearch(domain: String, query: String): List<Pair<String, String>> {
        return try {
            val url = "https://search.pingora.fyi/collections/post/documents/search" +
                "?q=${Uri.encode(query)}" +
                "&query_by=post_title,category&query_by_weights=4,2" +
                "&sort_by=sort_by_date:desc&limit=20&highlight_fields=none&use_cache=true&page=1"
            val text = app.get(
                url,
                headers = mapOf("User-Agent" to PLAY_UA, "Referer" to "$domain/"),
                timeout = 15L
            ).text
            val hits = org.json.JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
                val postTitle = d.optString("post_title")
                val permalink = d.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) return@mapNotNull null
                val url2 = if (permalink.startsWith("http", true)) {
                    // the indexed permalinks point at dead mirrors, only the path is stable
                    val path = try {
                        URI(permalink).path
                    } catch (_: Exception) {
                        null
                    }
                    if (path.isNullOrBlank()) permalink else domain + path
                } else {
                    domain + permalink
                }
                postTitle to url2
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun emitResolved(
        url: String,
        label: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = if (url.contains("id=")) PlayNet.decryptIdLink(url, referer) ?: return else url
        if (target.isBlank()) return
        PlayNet.emitSiteLink("hdhub4u", target, label, PlayNet.getIndexQuality(label), referer, subtitleCallback, callback)
    }

    private suspend fun processPost(
        domain: String,
        postUrl: String,
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(postUrl, headers = PlayNet.headers("$domain/"), timeout = 20L).document
            if (res.season != null) {
                val epRegex = Regex("(?i)episode\\s*(\\d+)")
                for (h3 in doc.select("h3")) {
                    val links = h3.select("a[href]")
                    val epLink = links.firstOrNull { it.text().contains("episode", true) } ?: continue
                    val epNum = epRegex.find(epLink.text())?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (res.episode != null && epNum != res.episode) continue

                    val watch = links.firstOrNull { it.text().trim().equals("watch", true) }
                    if (watch != null) {
                        val watchHref = watch.absUrl("href").ifBlank { watch.attr("href") }
                        if (watchHref.startsWith("http")) {
                            PlayNet.emitSiteLink("hdhub4u", watchHref, "Episode $epNum Watch", null, domain, subtitleCallback, callback)
                        }
                    }

                    val href = epLink.absUrl("href").ifBlank { epLink.attr("href") }
                    if (!href.startsWith("http")) continue
                    emitResolved(href, "Episode $epNum", domain, subtitleCallback, callback)
                }
            } else {
                for (el in doc.select("h3 a:matches((?i)480|720|1080|2160|4K), h4 a:matches((?i)480|720|1080|2160|4K)")) {
                    val href = el.absUrl("href").ifBlank { el.attr("href") }
                    if (!href.startsWith("http")) continue
                    emitResolved(href, el.text().trim(), domain, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_hdhub4u")
                ?: FirebaseDomainHelper.getDomain("hdhub4u")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return
            val posts = typesenseSearch(domain, title)
            if (posts.isEmpty()) return

            val titleMatched = posts.filter { (postTitle, _) ->
                PlayNet.titleMatches(postTitle, title)
            }
            // a season request must never land on a post that only carries a
            // different season, and posts without any season tag come last
            val chosen = if (res.season != null) {
                val seasonPosts = titleMatched.filter { (postTitle, _) ->
                    PlayNet.seasonsOf(postTitle)?.contains(res.season) == true
                }
                if (seasonPosts.isNotEmpty()) seasonPosts
                else titleMatched.filter { (postTitle, _) -> PlayNet.seasonsOf(postTitle) == null }
            } else {
                titleMatched.filter { (postTitle, _) -> PlayNet.yearMatches(postTitle, res.matchYear) }
                    .ifEmpty { titleMatched }
            }.take(2)
            coroutineScope {
                chosen.forEach { (_, postUrl) ->
                    async(Dispatchers.IO) {
                        processPost(domain, postUrl, res, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object FourKhdHubSite {
    private const val DEFAULT_DOMAIN = "https://4khdhub.one"

    private fun seasonOf(text: String): Int? =
        Regex("""\bS(\d{1,2})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?i)Season\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun emitLinks(
        hrefs: List<String>,
        label: String,
        domain: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // a movie page carries a dozen encoded links, a burst that big gets
        // throttled by the linker so keep it at six in flight
        val semaphore = Semaphore(6)
        coroutineScope {
            hrefs.distinct().forEach { href ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!href.startsWith("http")) return@withPermit
                        val target = if (href.contains("id=")) PlayNet.decryptIdLink(href, domain) ?: return@withPermit else href
                        if (target.isNotBlank()) {
                            PlayNet.emitSiteLink(
                                "4khdhub", target, label, PlayNet.getIndexQuality(label),
                                domain, subtitleCallback, callback
                            )
                        }
                    }
                }
            }
        }
    }

    private fun blockLabel(el: Element): String {
        val fileTitle = el.selectFirst(".episode-file-title")?.text()?.trim().orEmpty()
        if (fileTitle.isNotBlank()) return fileTitle
        val movieTitle = el.selectFirst("div.file-title")?.text()?.trim().orEmpty()
        if (movieTitle.isNotBlank()) return movieTitle
        val header = el.selectFirst("div.download-header, div.episode-header")?.text()?.trim().orEmpty()
        if (header.isNotBlank()) return header.replace(Regex("\\s+"), " ")
        return ""
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = FirebaseDomainHelper.getDomain("justplay_4khdhub")
                ?: FirebaseDomainHelper.getDomain("4khdhub")
                ?: DEFAULT_DOMAIN
            val title = res.title ?: return

            val searchDoc = app.get(
                "$domain/?s=${Uri.encode(title)}",
                headers = PlayNet.headers(),
                timeout = 20L
            ).document
            val elements = searchDoc.select("div.card-grid > a.movie-card")
            fun contentOf(el: Element): String = el.selectFirst("div.movie-card-content")?.text()?.lowercase() ?: ""

            val matched = elements.firstOrNull { el ->
                val content = contentOf(el)
                PlayNet.titleMatches(content, title) && PlayNet.yearMatches(content, res.year)
            } ?: elements.firstOrNull { el ->
                PlayNet.titleMatches(contentOf(el), title)
            } ?: return

            val href = matched.attr("href").trim()
            val detailUrl = if (href.startsWith("http")) href else domain + href
            val doc = app.get(detailUrl, headers = PlayNet.headers(domain), timeout = 20L).document

            if (res.season != null) {
                val seasonText = "S" + res.season.toString().padStart(2, '0')
                val epRegex = if (res.episode != null) {
                    Regex("(?i)${seasonText}\\s*[xXeE]\\s*0*${res.episode}(?!\\d)")
                } else {
                    Regex("(?i)\\b$seasonText(?!\\d)")
                }
                val items = doc.select("div.episode-download-item").filter { el ->
                    epRegex.containsMatchIn(el.text().replace('\u00A0', ' '))
                }
                val hrefs = items.flatMap { el ->
                    el.select("div.episode-links > a, a[href]").map { it.attr("href").trim() }
                }
                val labels = items.map { blockLabel(it) }
                val label = labels.firstOrNull { it.isNotBlank() }?.let { l ->
                    res.episode?.let { "S${res.season}E$it - $l" } ?: l
                } ?: "S${res.season}${res.episode?.let { "E$it" } ?: ""}"
                emitLinks(hrefs, label, domain, subtitleCallback, callback)
            } else {
                val items = doc.select("div.download-item")
                if (items.isNotEmpty()) {
                    items.forEach { el ->
                        val hrefs = el.select("a[href]").map { it.attr("href").trim() }
                        val label = blockLabel(el).ifBlank { doc.title() }
                        emitLinks(hrefs, label, domain, subtitleCallback, callback)
                    }
                } else {
                    val hrefs = doc.select("div.download-item a[href], a[href*=id=]").map { it.attr("href").trim() }
                    emitLinks(hrefs, doc.title(), domain, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }
}

internal object Movies4uSite {
    private const val DEFAULT_DOMAIN = "https://new1.movies4u.garden"
    private const val DOMAIN_REGISTRY =
        "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private val driveHostRegex = Regex("mdrive\\.cloud|nexdrive\\.fit|mobilejsr\\.rest|mdisk")

    @Volatile
    private var registryDomain: String? = null

    @Volatile
    private var registryCheckedAt = 0L

    private val junk = Regex(
        "winexch|a-ads|tinyurl|t\\.me/|/tg/|telegram|googleapis|googletagmanager|schema\\.org|w3\\.org"
    )

    private suspend fun registryDomain(): String? {
        val now = System.currentTimeMillis()
        if (registryDomain == null && now - registryCheckedAt > 10 * 60 * 1000L) {
            registryCheckedAt = now
            registryDomain = try {
                val text = app.get(DOMAIN_REGISTRY, timeout = 10L).text
                JSONObject(text).optString("movies4u").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        return registryDomain
    }

    private suspend fun candidates(): List<String> {
        val list = mutableListOf<String>()
        FirebaseDomainHelper.getDomain("justplay_movies4u")?.let { list.add(it) }
        FirebaseDomainHelper.getDomain("movies4u")?.let { list.add(it) }
        registryDomain()?.let { list.add(it) }
        list.add(DEFAULT_DOMAIN)
        return list.distinct()
    }

    private suspend fun searchPosts(domain: String, query: String): List<Pair<String, String>>? {
        return try {
            val text = app.get(
                "$domain/lookup.php?q=${Uri.encode(query)}&page=1&per_page=30",
                headers = PlayNet.headers(),
                timeout = 15L
            ).text
            val hits = JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val hit = hits.optJSONObject(i) ?: return@mapNotNull null
                val postTitle = hit.optString("post_title")
                val permalink = hit.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) null
                else postTitle to PlayNet.absolute(permalink, domain)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findPosts(title: String): List<Pair<String, String>> {
        for (domain in candidates()) {
            val posts = searchPosts(domain, title) ?: continue
            return posts.map { (postTitle, url) ->
                postTitle to url.replaceFirst(Regex("^[^/]*//[^/]+"), domain)
            }
        }
        return emptyList()
    }

    private suspend fun getDoc(url: String): Document? = try {
        app.get(url, headers = PlayNet.headers(PlayNet.getBaseUrl(url)), timeout = 20L).document
    } catch (_: Exception) {
        null
    }

    // a post page only hands out m4ulinks buttons, the real hubcloud and
    // gdflix links sit behind those under h4 quality headings
    private fun qualityBlocks(doc: Document): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (block in doc.select("div.downloads-btns-div")) {
            var label = ""
            var sib = block.previousElementSibling()
            while (sib != null) {
                val text = sib.tagName().let { if (it == "h2" || it == "h3" || it == "h4") sib.text() else "" }
                if (text.isNotBlank()) {
                    label = text.replace(Regex("\\s+"), " ").trim()
                    break
                }
                sib = sib.previousElementSibling()
            }
            for (a in block.select("a[href]")) {
                val href = a.attr("href").trim()
                val text = a.text()
                if (!href.startsWith("http")) continue
                if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                if (junk.containsMatchIn(href)) continue
                out.add(href to label)
            }
        }
        return out.distinctBy { it.first }
    }

    private suspend fun emitM4uLinks(
        linksUrl: String,
        label: String,
        episode: Int?,
        season: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = getDoc(linksUrl) ?: return
        val quality = if (label.isBlank()) null else PlayNet.getIndexQuality(label)

        val rows = mutableListOf<Pair<String, String>>()
        if (episode != null) {
            val epRegex = Regex("(?i)Episodes?\\s*:\\s*0*$episode(?!\\d)")
            for (h5 in doc.select("h5")) {
                if (!epRegex.containsMatchIn(h5.text())) continue
                var sib = h5.nextElementSibling()
                while (sib != null && sib.tagName() != "h5") {
                    for (a in sib.select("a[href]")) {
                        val href = a.attr("href").trim()
                        if (href.startsWith("http") && !junk.containsMatchIn(href)) {
                            rows.add(href to label)
                        }
                    }
                    sib = sib.nextElementSibling()
                }
            }
        }
        if (rows.isEmpty()) {
            // the links page carries the real quality headings, one block per
            // file, so the label comes from the page instead of the caller
            for ((href, headLabel) in qualityBlocks(doc)) {
                rows.add(href to headLabel.ifBlank { label })
            }
        }
        if (rows.isEmpty()) {
            for (block in doc.select("div.downloads-btns-div")) {
                for (a in block.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val text = a.text()
                    if (!href.startsWith("http")) continue
                    if (text.contains("Batch", true) || text.contains("Zip", true)) continue
                    if (junk.containsMatchIn(href)) continue
                    rows.add(href to label)
                }
            }
        }

        coroutineScope {
            rows.distinctBy { it.first }.forEach { (href, rowLabel) ->
                async(Dispatchers.IO) {
                    val rowQuality = if (rowLabel.isBlank()) quality else PlayNet.getIndexQuality(rowLabel)
                    emitSource(href, rowLabel, rowQuality, linksUrl, season, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun emitSource(
        url: String,
        label: String,
        quality: Int?,
        referer: String?,
        season: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = PlayNet.hostOf(url)
        when {
            host.contains("m4ulinks") ->
                emitM4uLinks(url, label, null, season, subtitleCallback, callback)
            driveHostRegex.containsMatchIn(url) ->
                DrivePages.emit("movies4u", url, null, season, label, null, referer, subtitleCallback, callback)
            else ->
                PlayNet.emitOwnLink("movies4u", url, label, quality, referer, subtitleCallback, callback)
        }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val title = res.title ?: return
            val posts = findPosts(title)
            if (posts.isEmpty()) return

            val matched = posts.filter { (postTitle, _) -> PlayNet.titleMatches(postTitle, title) }
            val chosen = if (res.season != null) {
                val seasonPosts = matched.filter { (postTitle, _) ->
                    PlayNet.seasonsOf(postTitle)?.contains(res.season) == true
                }
                if (seasonPosts.isNotEmpty()) seasonPosts
                else matched.filter { (postTitle, _) -> PlayNet.seasonsOf(postTitle) == null }
            } else {
                matched.filter { (postTitle, _) -> PlayNet.yearMatches(postTitle, res.matchYear) }
                    .ifEmpty { matched }
            }.take(2)
            if (chosen.isEmpty()) return

            chosen.forEach { (_, postUrl) ->
                try {
                    val doc = getDoc(postUrl) ?: return@forEach
                    if (res.season == null) {
                        val blocks = qualityBlocks(doc)
                        coroutineScope {
                            blocks.forEach { (href, label) ->
                                async(Dispatchers.IO) {
                                    emitSource(href, label, PlayNet.getIndexQuality(label), postUrl, null, subtitleCallback, callback)
                                }
                            }
                        }
                    } else {
                        val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                        val blocks = qualityBlocks(doc).filter { (_, label) ->
                            seasonRegex.containsMatchIn(label)
                        }.ifEmpty {
                            qualityBlocks(doc).filter { (_, label) -> PlayNet.seasonsOf(label) == null }
                        }
                        blocks.forEach { (href, label) ->
                            emitM4uLinks(href, label, res.episode, res.season, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}

internal object TmfSite {

    private fun searchAnchors(doc: Document): List<Pair<String, String>> {
        return doc.select("article.latestpost a[id=featured-thumbnail]").mapNotNull { el ->
            val t = el.attr("title").trim().ifBlank { el.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            val href = el.attr("href").trim()
            if (t.isNotBlank() && href.startsWith("http")) t to href else null
        }
    }

    // whole season packs arrive as zip archives, they are not playable so the
    // button and its group are dropped before the drive page is fetched
    private val packRegex = Regex("""(?i)\b(zip|rar|7z|batch)\b""")

    private data class DriveGroup(val label: String, val redirectUrl: String)

    private fun downloadGroups(doc: Document): List<DriveGroup> {
        return doc.select("div.mfx-download-group").flatMap { div ->
            val label = div.selectFirst("h3")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            if (packRegex.containsMatchIn(label)) return@flatMap emptyList()
            div.select("a[href]").mapNotNull { a ->
                val text = a.text().trim()
                val href = a.attr("href").trim()
                when {
                    !href.startsWith("http") -> null
                    text.contains("Batch", true) || text.contains("Zip", true) -> null
                    packRegex.containsMatchIn(text) -> null
                    else -> DriveGroup(label, href)
                }
            }
        }.distinctBy { it.redirectUrl }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val title = res.title ?: return
            val domain = PlayTmfNet.domain()

            val searchDoc = PlayTmfNet.fetchPage("$domain/?s=${Uri.encode(title)}") ?: return
            val anchors = searchAnchors(searchDoc)
            if (anchors.isEmpty()) return

            val matched = anchors.filter { (t, _) -> PlayNet.titleMatches(t, title) }
            val target = matched.firstOrNull { (t, _) ->
                if (res.season != null) PlayNet.seasonsOf(t)?.contains(res.season) == true
                else PlayNet.yearMatches(t, res.matchYear)
            } ?: matched.firstOrNull() ?: anchors.firstOrNull() ?: return

            val postDoc = PlayTmfNet.fetchPage(target.second, domain) ?: return
            val groups = downloadGroups(postDoc)

            val driveUrls: List<String> = if (res.season == null) {
                groups.map { it.redirectUrl }
            } else {
                val seasonRegex = Regex("(?i)Season\\s*${res.season}(?!\\d)")
                groups.filter { (label, _) ->
                    seasonRegex.containsMatchIn(label) || PlayNet.seasonsOf(label)?.contains(res.season) == true
                }.map { it.redirectUrl }
            }
            if (driveUrls.isEmpty()) return

            coroutineScope {
                driveUrls.forEach { driveUrl ->
                    async(Dispatchers.IO) {
                        val page = PlayTmfNet.fetchDrivePage(driveUrl) ?: return@async
                        val hrefs = if (res.season != null && res.episode != null) {
                            page.episodes[res.episode] ?: emptyList()
                        } else {
                            page.links
                        }
                        if (hrefs.isEmpty()) return@async
                        PlayTmfSources.emitAll(hrefs, page.quality, page.info, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

internal object MultimoviesSite {
    private const val DEFAULT_DOMAIN = "https://multimovies.garden"

    private data class MmCard(
        val title: String,
        val url: String,
        val year: Int?,
        val isTv: Boolean
    )

    private suspend fun currentDomain(): String {
        val remote = FirebaseDomainHelper.getDomain("justplay_multimovies")
            ?: FirebaseDomainHelper.getDomain("multimovies")
            ?: DEFAULT_DOMAIN
        return remote.trimEnd('/')
    }

    private fun cardOf(el: Element): MmCard? {
        val raw = el.selectFirst("[data-save-title]")?.attr("data-save-title") ?: return null
        val data = try {
            JSONObject(raw.replace("&quot;", "\""))
        } catch (_: Exception) {
            return null
        }
        val url = data.optString("url")
        val title = data.optString("title")
        if (url.isBlank() || title.isBlank()) return null
        val isTv = data.optString("type") == "tv" || url.contains("/series/")
        return MmCard(title, PlayNet.absolute(url, DEFAULT_DOMAIN), data.optInt("year", 0).takeIf { it > 0 }, isTv)
    }

    private suspend fun searchCards(domain: String, query: String): List<MmCard> = try {
        val doc = PlayNet.fetchWithCf("$domain/search?q=${Uri.encode(query)}")?.document
            ?: return emptyList()
        doc.select("article.poster-card").mapNotNull { cardOf(it) }
    } catch (_: Exception) {
        emptyList()
    }

    // the series page renders one season at a time, later seasons come through
    // the query param, the episode card carries its own page url
    private suspend fun episodeHref(
        domain: String,
        seriesUrl: String,
        season: Int?,
        episode: Int?
    ): String? {
        val pages = mutableListOf(seriesUrl)
        if (season != null && season > 1) pages.add("$seriesUrl?season=$season")
        for (page in pages) {
            val doc = PlayNet.fetchWithCf(page)?.document ?: continue
            for (card in doc.select(".cinejoy-ep-card")) {
                val m = Regex("ep-item-(\\d+)-(\\d+)").find(card.attr("id")) ?: continue
                if (season != null && m.groupValues[1].toIntOrNull() != season) continue
                if (episode != null && m.groupValues[2].toIntOrNull() != episode) continue
                val href = card.selectFirst("a.cinejoy-ep-thumb-link")?.attr("href")?.trim().orEmpty()
                if (href.isNotBlank()) return PlayNet.absolute(href, domain)
            }
        }
        return null
    }

    private data class WatchServer(val id: String, val name: String, val url: String)

    private fun parseWatchConfig(html: String): List<WatchServer> {
        val root = try {
            JSONObject(extractBracedObject(html, "const watchConfig = "))
        } catch (_: Exception) {
            return emptyList()
        }
        val servers = root.optJSONArray("initialServers") ?: return emptyList()
        val out = mutableListOf<WatchServer>()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (url.isBlank()) continue
            out.add(WatchServer(s.optString("id"), s.optString("name"), url))
        }
        return out
    }

    // line ending agnostic json block reader, the site mixes crlf and lf
    private fun extractBracedObject(html: String, marker: String): String {
        val start = html.indexOf(marker)
        if (start < 0) return ""
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until html.length) {
            val ch = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                ch == '{' && !inString -> depth++
                ch == '}' && !inString -> {
                    depth--
                    if (depth == 0) return html.substring(braceStart, i + 1)
                }
            }
        }
        return ""
    }

    private data class TitleIds(val tmdbId: String?, val imdbId: String?)

    private fun extractIds(servers: List<WatchServer>): TitleIds {
        var tmdb: String? = null
        var imdb: String? = null
        for (server in servers) {
            if (tmdb == null) {
                tmdb = Regex("(?:movie\\?id=|watch/movie/|watch/tv/|embed/tmdb/tv\\?id=|/tv/)(\\d+)")
                    .find(server.url)?.groupValues?.get(1)
            }
            if (imdb == null) {
                imdb = Regex("(tt\\d{6,})").find(server.url)?.groupValues?.get(1)
            }
        }
        return TitleIds(tmdb, imdb)
    }

    private fun yearOf(doc: Document): String? {
        val text = doc.select(".cinejoy-meta-pill").eachText().joinToString(" ")
        return Regex("\\b((?:19|20)\\d{2})\\b").find(text)?.groupValues?.get(1)
    }

    // the resolver output keeps the server names the site itself hands out,
    // every link is renamed once here so the player list stays uniform
    private fun wrappedCallbacks(
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Pair<(ExtractorLink) -> Unit, (SubtitleFile) -> Unit> {
        val seenLinks = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )
        val seenSubs = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )
        val linkCb: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) {
                val name = "[Multimovies] - " + link.name.replace(Regex("\\s+"), " ").trim()
                callback(
                    ExtractorLink(
                        "Multimovies",
                        name,
                        link.url,
                        link.referer,
                        link.quality,
                        link.headers,
                        link.extractorData,
                        link.type,
                        link.audioTracks
                    )
                )
            }
        }
        val subCb: (SubtitleFile) -> Unit = { sub ->
            if (seenSubs.add(sub.url)) subtitleCallback(sub)
        }
        return linkCb to subCb
    }

    private suspend fun resolveServer(
        server: WatchServer,
        ids: TitleIds,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        title: String,
        year: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val host = MmNet.hostOf(server.url)
        return when {
            host.contains("modiplay.xyz") ->
                MmCineverse.resolve(server.url, server.name, callback)

            host.contains("iqsmartgames.com") ->
                MmGdMirror.resolve(server.url, server.name, callback)

            host.contains("filesforever.link") ->
                MmGdMirror.resolveFilesforever(server.url, server.name, callback)

            host.contains("vidout.pages.dev") -> {
                val tmdb = ids.tmdbId ?: return false
                MmVidout.resolve(tmdb, isTv, season, episode, server.name, subtitleCallback, callback)
            }

            host.contains("vidsync.pro") -> {
                val tmdb = ids.tmdbId
                val url = if (tmdb != null) server.url.replace("{tmdbId}", tmdb) else server.url
                MmVidsync.resolve(url, server.name, callback)
            }

            host.contains("bingr.one") -> {
                val tmdb = ids.tmdbId ?: return false
                MmBingr.resolve(isTv, tmdb, title, year, season, episode, server.name, subtitleCallback, callback)
            }

            host.contains("filmu.in") -> {
                val tmdb = ids.tmdbId ?: return false
                MmFilmu.resolve(isTv, tmdb, season, episode, server.name, subtitleCallback, callback)
            }

            host.contains("vidbolt.xyz") -> {
                val tmdb = ids.tmdbId ?: return false
                MmVidbolt.resolve(isTv, tmdb, ids.imdbId, title, year, season, episode, server.name, callback)
            }

            else -> {
                val html = MmNet.get(server.url, referer = "https://multimovies.garden/")
                if (html != null) {
                    val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
                        .findAll(html)
                        .map { MmNet.deEsc(it.groupValues.first()) }
                        .toSet()
                    for (u in urls) {
                        callback(newExtractorLink(server.name, server.name, u, type = ExtractorLinkType.M3U8))
                    }
                    urls.isNotEmpty()
                } else false
            }
        }
    }

    private suspend fun resolvePost(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = PlayNet.fetchWithCf(pageUrl) ?: return
            val servers = parseWatchConfig(res.text)
            if (servers.isEmpty()) return

            val ids = extractIds(servers)
            val isTv = pageUrl.contains("/series/")
            val season = Regex("/season/(\\d+)/episode/").find(pageUrl)?.groupValues?.get(1)?.toIntOrNull()
            val episode = Regex("/season/\\d+/episode/(\\d+)").find(pageUrl)?.groupValues?.get(1)?.toIntOrNull()
            val title = res.document.selectFirst("h1")?.text()?.trim().orEmpty()
            val year = yearOf(res.document)

            val (linkCb, subCb) = wrappedCallbacks(subtitleCallback, callback)
            coroutineScope {
                servers.forEach { server ->
                    async(Dispatchers.IO) {
                        try {
                            resolveServer(server, ids, isTv, season, episode, title, year, subCb, linkCb)
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val domain = currentDomain()
            val title = res.title ?: return
            val cards = searchCards(domain, title)

            if (res.season == null) {
                val movie = cards.filter { !it.isTv }
                    .firstOrNull { PlayNet.titleMatches(it.title, title) } ?: return
                resolvePost(movie.url, subtitleCallback, callback)
            } else {
                val show = cards.filter { it.isTv }
                    .firstOrNull { PlayNet.titleMatches(it.title, title) }
                    ?.let { card ->
                        if (res.matchYear == null || PlayNet.yearMatches(card.title, res.matchYear)) card else null
                    }
                    ?: cards.firstOrNull { it.isTv && PlayNet.titleMatches(it.title, title) }
                    ?: return
                val href = episodeHref(domain, show.url, res.season, res.episode) ?: return
                resolvePost(href, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }
}
