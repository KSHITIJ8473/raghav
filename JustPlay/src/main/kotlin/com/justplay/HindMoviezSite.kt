package com.justplay

import android.net.Uri
import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

internal object HindMoviezSite {
    private const val DEFAULT_DOMAIN = "https://v3.hindmovie.im"

    private suspend fun candidates(): List<String> {
        val list = mutableListOf<String>()
        FirebaseDomainHelper.getDomain("justplay_hindmoviez")?.let { list.add(it) }
        FirebaseDomainHelper.getDomain("hindmoviez")?.let { list.add(it) }
        list.add(DEFAULT_DOMAIN)
        return list.distinct()
    }

    private suspend fun searchPosts(domain: String, query: String): List<Pair<String, String>> {
        val doc = PlayNet.retry {
            try {
                app.get(
                    "$domain/?s=${URLEncoder.encode(query, "UTF-8")}",
                    headers = PlayNet.headers(),
                    timeout = 15L
                ).document
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        return try {
            doc.select("h2.entry-title > a[href]").mapNotNull { a ->
                val title = a.text().replace(Regex("\\s+"), " ").trim()
                val href = a.attr("href").trim()
                if (title.isBlank() || !href.startsWith("http")) null else title to href
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickPost(posts: List<Pair<String, String>>, res: PlayLinkData): Pair<String, String>? {
        val matched = posts.filter { PlayNet.titleMatchesFor(it.first, res) }
        if (matched.isEmpty()) return null
        if (res.season != null) {
            val seasonPosts = matched.filter { PlayNet.seasonsOf(it.first)?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { PlayNet.yearMatches(it.first, res.matchYear) } ?: seasonPosts.first()
            }
            return matched.firstOrNull { PlayNet.seasonsOf(it.first) == null }
        }
        return matched.firstOrNull { PlayNet.yearMatches(it.first, res.matchYear) }
    }

    private suspend fun fetchDoc(url: String, referer: String? = null): Document? {
        return try {
            app.get(url, headers = PlayNet.headers(referer), timeout = 20L).document
        } catch (_: Exception) {
            null
        }
    }

    private fun shareInfo(doc: Document): Pair<String, Int> {
        val name = doc.selectFirst("p:contains(Name:)")?.text()
            ?.substringAfter("Name:")?.trim().orEmpty()
        val size = doc.selectFirst("p:contains(Size:)")?.text()
            ?.substringAfter("Size:")?.trim().orEmpty()
        val info = listOf(name, size).filter { it.isNotBlank() }.joinToString(" ")
        return info to PlayNet.getIndexQuality(info)
    }

    private fun b64decode(value: String): String {
        var s = value.replace("-", "+").replace("_", "/")
        val rem = s.length % 4
        if (rem != 0) s += "=".repeat(4 - rem)
        return try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun hpageTarget(href: String): String? {
        val outer = href.substringAfter("url=", "").substringBefore("&")
        if (outer.isBlank()) return null
        val layer1 = b64decode(outer)
        val inner = layer1.substringAfter("url=", "").substringBefore("&")
        if (inner.isBlank()) return null
        val target = b64decode(inner)
        return target.takeIf { it.startsWith("http") }
    }

    private suspend fun gdshineTarget(shortId: String): String? {
        if (shortId.isBlank()) return null
        val fileId = try {
            val text = app.get(
                "https://gdshine.org/api/files/s/$shortId",
                headers = PlayNet.headers("https://gdshine.org/"),
                timeout = 15L
            ).text
            JSONObject(text).optJSONObject("data")?.optString("id")
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val text = app.get(
                "https://gdshine.org/api/gp/mirror/$fileId",
                headers = PlayNet.headers("https://gdshine.org/"),
                timeout = 15L
            ).text
            JSONObject(text).optJSONObject("data")?.optString("url")
        } catch (_: Exception) {
            null
        }?.takeIf { it.startsWith("http") }
    }

    private suspend fun emitLink(
        url: String,
        info: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!PlayNet.alive(url, ExtractorLinkType.VIDEO)) return
        val name = PlayLabels.buildLabel("hindmoviez", "", info)
        callback(
            newExtractorLink(
                "[HindMoviez]",
                name,
                url,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
            }
        )
    }

    private suspend fun emitShare(shareHref: String, callback: (ExtractorLink) -> Unit) {
        if (!shareHref.contains("?id=")) return
        val res = try {
            app.get(shareHref, headers = PlayNet.headers(), timeout = 20L)
        } catch (_: Exception) {
            null
        } ?: return
        val doc = try {
            res.document
        } catch (_: Exception) {
            null
        } ?: return
        val (info, quality) = shareInfo(doc)
        if (info.isBlank()) return

        for (a in doc.select("div.btn-group a.btn, a.btn")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }.trim()
            if (!href.startsWith("http")) continue
            when {
                a.classNames().contains("btn-info") ->
                    hpageTarget(href)?.let { emitLink(it, info, quality, callback) }
                href.contains("gdshine.org/s/") ->
                    gdshineTarget(href.substringAfter("/s/").substringBefore("?"))
                        ?.let { emitLink(it, info, quality, callback) }
            }
        }
    }

    private fun episodeMatch(a: Element, res: PlayLinkData): Boolean {
        if (res.episode == null) return false
        val ep = res.episode
        val fileName = Uri.decode(a.attr("href").substringAfter("?id=", ""))
        val sxxexx = Regex("(?i)S0*${res.season ?: 1}\\s*[-–]?\\s*E0*$ep(?!\\d)")
        val anyMarker = Regex("(?i)\\bS\\d{1,2}\\s*[-–]?\\s*E\\d{1,3}\\b")
        return when {
            anyMarker.containsMatchIn(fileName) -> sxxexx.containsMatchIn(fileName)
            else -> Regex("(?i)Episode\\s*:?\\s*0*$ep(?!\\d)").containsMatchIn(a.text())
        }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (res.title.isNullOrBlank()) return

            var chosen: Pair<String, String>? = null
            outer@ for (domain in candidates()) {
                for (query in PlayNet.searchQueries(res)) {
                    val found = searchPosts(domain, query)
                    if (found.isEmpty()) continue
                    chosen = pickPost(found, res)
                    if (chosen != null) break@outer
                }
            }
            if (chosen == null) return

            val postDoc = fetchDoc(chosen.second) ?: return
            val targets = postDoc.select("a.maxbutton").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) null else href
            }.distinct()
            if (targets.isEmpty()) return

            val semaphore = Semaphore(3)
            coroutineScope {
                targets.forEach { linkUrl ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val page = fetchDoc(linkUrl) ?: return@withPermit
                            if (res.season == null) {
                                page.select("a[href]").firstOrNull {
                                    it.attr("href").contains("?id=")
                                }?.attr("abs:href")?.trim()?.let {
                                    if (it.startsWith("http")) emitShare(it, callback)
                                }
                            } else {
                                page.select("a[href]").firstOrNull {
                                    it.attr("href").contains("?id=") && episodeMatch(it, res)
                                }?.attr("abs:href")?.trim()?.let {
                                    if (it.startsWith("http")) emitShare(it, callback)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
