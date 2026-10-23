package com.justplay

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object MoviesDriveSite {
    private const val DEFAULT_DOMAIN = "https://new1.moviesdrive.beer"
    private const val DOMAIN_REGISTRY =
        "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

    private val driveLink = Regex("hubcloud|gdflix|gdlink", RegexOption.IGNORE_CASE)
    private val packText = Regex("""(?i)\b(zip|rar|7z|batch)\b""")

    @Volatile
    private var registryDomain: String? = null

    @Volatile
    private var registryCheckedAt = 0L

    private suspend fun registryDomain(): String? {
        val now = System.currentTimeMillis()
        if (registryDomain == null && now - registryCheckedAt > 10 * 60 * 1000L) {
            registryCheckedAt = now
            registryDomain = try {
                val text = app.get(DOMAIN_REGISTRY, timeout = 10L).text
                JSONObject(text).optString("moviesdrive").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        return registryDomain
    }

    private suspend fun candidates(): List<String> {
        val list = mutableListOf<String>()
        FirebaseDomainHelper.getDomain("justplay_moviesdrive")?.let { list.add(it) }
        FirebaseDomainHelper.getDomain("moviesdrive")?.let { list.add(it) }
        registryDomain()?.let { list.add(it) }
        list.add(DEFAULT_DOMAIN)
        return list.distinct()
    }

    private data class DrivePost(val title: String, val url: String, val imdbId: String)

    private suspend fun searchPosts(domain: String, query: String): List<DrivePost> {
        val text = PlayNet.retry {
            try {
                app.get(
                    "$domain/search.php?q=${Uri.encode(query)}",
                    headers = PlayNet.headers(),
                    timeout = 15L
                ).text
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        return try {
            val hits = JSONObject(text).optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val d = hits.optJSONObject(i)?.optJSONObject("document") ?: return@mapNotNull null
                val postTitle = d.optString("post_title")
                val permalink = d.optString("permalink")
                if (postTitle.isBlank() || permalink.isBlank()) return@mapNotNull null
                DrivePost(postTitle, PlayNet.absolute(permalink, domain), d.optString("imdb_id"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickPost(posts: List<DrivePost>, res: PlayLinkData): DrivePost? {
        if (res.title.isNullOrBlank()) return null
        res.imdbId?.takeIf { it.isNotBlank() }?.let { id ->
            posts.firstOrNull { it.imdbId.equals(id, ignoreCase = true) }?.let { return it }
        }
        val matched = posts.filter { PlayNet.titleMatchesFor(it.title, res) }
        if (res.season != null) {
            val seasonPosts = matched.filter { PlayNet.seasonsOf(it.title)?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { PlayNet.yearMatches(it.title, res.matchYear) } ?: seasonPosts.first()
            }
            return matched.firstOrNull { PlayNet.seasonsOf(it.title) == null }
        }
        // a post from another year is a different movie, war 2007 is not war 2019
        return matched.firstOrNull { PlayNet.yearMatches(it.title, res.matchYear) }
    }

    private suspend fun getDoc(url: String): Document? = try {
        app.get(url, headers = PlayNet.headers(PlayNet.getBaseUrl(url)), timeout = 20L).document
    } catch (_: Exception) {
        null
    }

    private fun cleanLabel(el: Element): String =
        el.text().replace(Regex("\\s+"), " ").trim()

    private suspend fun emitDriveLinks(
        archiveUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = getDoc(archiveUrl) ?: return
        val quality = if (label.isBlank()) null else PlayNet.getIndexQuality(label)
        val hrefs = doc.select("a[href]").map { it.attr("href").trim() }
            .filter { it.startsWith("http") && driveLink.containsMatchIn(it) }
            .distinct()
        coroutineScope {
            hrefs.forEach { href ->
                async(Dispatchers.IO) {
                    PlayNet.emitOwnLink("moviesdrive", href, label, quality, archiveUrl, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun emitMovie(
        postUrl: String,
        doc: Document,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val semaphore = Semaphore(4)
        coroutineScope {
            doc.select("h5 > a[href]").forEach { a ->
                val label = cleanLabel(a)
                if (packText.containsMatchIn(label)) return@forEach
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) return@forEach
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (driveLink.containsMatchIn(href)) {
                            PlayNet.emitOwnLink(
                                "moviesdrive", href, label,
                                PlayNet.getIndexQuality(label), postUrl, subtitleCallback, callback
                            )
                        } else {
                            emitDriveLinks(href, label, subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }

    private fun seasonHeadings(doc: Document, season: Int): List<Element> {
        val wanted = Regex("(?i)Season\\s*$season(?!\\d)|\\bS${season.toString().padStart(2, '0')}\\b")
        return doc.select("h5").filter { el ->
            wanted.containsMatchIn(el.text().replace('\u00A0', ' ')) && el.selectFirst("a[href]") == null
        }
    }

    private suspend fun emitEpisode(
        archiveUrl: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = getDoc(archiveUrl) ?: return
        val epRegex = Regex(
            "(?i)\\bE(?:p(?:isode)?)?\\.?\\s*0*$episode(?!\\d)|" +
                "\\bS0*$season\\s*[xXeE]\\s*0*$episode(?!\\d)"
        )
        val heading = doc.select("h5").firstOrNull { epRegex.containsMatchIn(it.text()) } ?: return
        val label = cleanLabel(heading)
        val quality = if (label.isBlank()) null else PlayNet.getIndexQuality(label)
        val hrefs = mutableListOf<String>()
        var sib = heading.nextElementSibling()
        while (sib != null && sib.tagName() == "h5" && !epRegex.containsMatchIn(sib.text())) {
            sib.selectFirst("a[href]")?.attr("href")?.trim()?.let { hrefs.add(it) }
            sib = sib.nextElementSibling()
        }
        coroutineScope {
            hrefs.distinct().forEach { href ->
                async(Dispatchers.IO) {
                    if (href.startsWith("http")) {
                        PlayNet.emitOwnLink(
                            "moviesdrive", href, label, quality, archiveUrl, subtitleCallback, callback
                        )
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
            if (res.title.isNullOrBlank()) return

            var chosen: DrivePost? = null
            outer@ for (domain in candidates()) {
                // the year keeps the lookup on the right post, the bare title alone
                // buries it under every war named series and movie
                for (query in PlayNet.searchQueries(res)) {
                    val found = searchPosts(domain, query)
                    if (found.isEmpty()) continue
                    val rewritten = found.map { it.copy(url = it.url.replaceFirst(Regex("^[^/]*//[^/]+"), domain)) }
                    chosen = pickPost(rewritten, res)
                    if (chosen != null) break@outer
                }
            }
            if (chosen == null) return
            val doc = getDoc(chosen.url) ?: return

            if (res.season == null) {
                emitMovie(chosen.url, doc, subtitleCallback, callback)
            } else {
                val semaphore = Semaphore(4)
                coroutineScope {
                    seasonHeadings(doc, res.season).forEach { head ->
                        val a = head.nextElementSibling()?.selectFirst("a[href]") ?: return@forEach
                        if (packText.containsMatchIn(a.text())) return@forEach
                        val href = a.attr("href").trim()
                        if (!href.startsWith("http")) return@forEach
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                emitEpisode(href, res.season, res.episode ?: return@withPermit, subtitleCallback, callback)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
