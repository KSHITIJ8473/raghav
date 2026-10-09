package com.justplay

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object HindMoviezSite {
    private const val DEFAULT_DOMAIN = "https://hindmovie.dev"
    private const val DOMAIN_REGISTRY =
        "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"

    private val shareSecret =
        base64Decode("NWU5NjA4NWM1NmUwZjU0ZWRhNjU3NzkwYWM1OGQxOWIyNzE0NzljNTA0MzY3ZmM5ZTZhNmMzM2YxZjgyNGU2Yg==")

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
                JSONObject(text).optString("hindmoviez").takeIf { it.startsWith("http") }
            } catch (_: Exception) {
                null
            }
        }
        return registryDomain
    }

    private suspend fun candidates(): List<String> {
        val list = mutableListOf<String>()
        FirebaseDomainHelper.getDomain("justplay_hindmoviez")?.let { list.add(it) }
        FirebaseDomainHelper.getDomain("hindmoviez")?.let { list.add(it) }
        registryDomain()?.let { list.add(it) }
        list.add(DEFAULT_DOMAIN)
        return list.distinct()
    }

    private fun base64Url(input: String): String =
        Base64.encodeToString(
            input.toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

    private fun signShare(rawId: String, domain: String): String {
        val t = System.currentTimeMillis() / 1000
        val encoded = base64Url(rawId)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(shareSecret.toByteArray(), "HmacSHA256"))
        val signature = mac.doFinal("$encoded|$t".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 16)
        return "$domain/r.php?d=${URLEncoder.encode(encoded, "UTF-8")}&t=$t&s=$signature"
    }

    private fun wrappedDirectUrl(href: String): String? {
        return try {
            val outer = href.substringAfter("url=")
            val layer1 = base64Decode(outer)
            val inner = layer1.substringAfter("url=").substringBefore("&")
            base64Decode(inner).trim().takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchPosts(domain: String, query: String): List<Pair<String, String>> {
        return try {
            val doc = app.get(
                "$domain/?s=${URLEncoder.encode(query, "UTF-8")}",
                headers = PlayNet.headers(),
                timeout = 15L
            ).document
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
        val title = res.title ?: return null
        val matched = posts.filter { PlayNet.titleMatches(it.first, title) }
        if (res.season != null) {
            val seasonPosts = matched.filter { PlayNet.seasonsOf(it.first)?.contains(res.season) == true }
            if (seasonPosts.isNotEmpty()) {
                return seasonPosts.firstOrNull { PlayNet.yearMatches(it.first, res.matchYear) } ?: seasonPosts.first()
            }
            return matched.firstOrNull { PlayNet.seasonsOf(it.first) == null }
        }
        return matched.firstOrNull { PlayNet.yearMatches(it.first, res.matchYear) }
            ?: matched.firstOrNull()
    }

    private fun buttonHeading(a: Element): String {
        val heading = a.parent()?.parent()?.previousElementSibling()?.text()
        return heading?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    }

    private suspend fun emitLink(
        url: String,
        info: String,
        quality: Int,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val name = PlayLabels.buildLabel("hindmoviez", "", info)
        callback(
            newExtractorLink(
                "[HindMoviez]",
                name,
                url,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = referer
            }
        )
    }

    private suspend fun emitShareLinks(
        shareHref: String,
        heading: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = shareHref.substringBefore("/?id=")
        if (base == shareHref) return
        val rawId = shareHref.substringAfter("id=")
        val doc = try {
            app.get(signShare(rawId, base), headers = PlayNet.headers(), timeout = 20L).document
        } catch (_: Exception) {
            null
        } ?: return

        val name = doc.selectFirst("p:contains(Name:)")?.text()
            ?.substringAfter("Name:")?.trim().orEmpty()
        val size = doc.selectFirst("p:contains(Size:)")?.text()
            ?.substringAfter("Size:")?.trim().orEmpty()
        val info = listOf(name, size).filter { it.isNotBlank() }.joinToString(" ").ifBlank { heading }
        val quality = PlayNet.getIndexQuality(info)

        val btnInfo = doc.selectFirst("a.btn-info")?.attr("href")?.trim().orEmpty()
        if (!btnInfo.startsWith("http")) return

        val urls = mutableListOf<String>()
        val page = PlayNet.fetchWithCf(btnInfo)
        page?.document?.select("a.button")?.forEach { a ->
            a.attr("href").trim().takeIf { it.startsWith("http") }?.let { urls.add(it) }
        }
        if (urls.isEmpty()) {
            wrappedDirectUrl(btnInfo)?.let { urls.add(it) }
        }
        urls.distinct().forEach { emitLink(it, info, quality, "$base/", callback) }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val title = res.title ?: return
            val imdbId = res.imdbId?.takeIf { it.isNotBlank() }

            var posts = emptyList<Pair<String, String>>()
            for (domain in candidates()) {
                val byId = if (imdbId != null) searchPosts(domain, imdbId) else emptyList()
                val target = byId.ifEmpty { searchPosts(domain, title) }
                if (target.isNotEmpty()) {
                    posts = target
                    break
                }
            }
            if (posts.isEmpty()) return
            val chosen = pickPost(posts, res) ?: return

            val postDoc = try {
                app.get(chosen.second, headers = PlayNet.headers(), timeout = 20L).document
            } catch (_: Exception) {
                null
            } ?: return

            val semaphore = Semaphore(3)
            coroutineScope {
                if (res.season == null) {
                    postDoc.select("a.maxbutton").forEach { a ->
                        val href = a.attr("href").trim()
                        if (!href.startsWith("http")) return@forEach
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                emitMovieLinks(href, buttonHeading(a), callback)
                            }
                        }
                    }
                } else {
                    val seasonButtons = postDoc.select("a.maxbutton").mapNotNull { a ->
                        val heading = buttonHeading(a)
                        val href = a.attr("href").trim()
                        if (!href.startsWith("http")) null else a to heading
                    }

                    val chosen = seasonButtons.filter { (_, heading) ->
                        PlayNet.seasonsOf(heading)?.contains(res.season) == true
                    }.ifEmpty { seasonButtons.filter { (_, heading) -> PlayNet.seasonsOf(heading) == null } }
                    chosen.forEach { (a, _) ->
                        val href = a.attr("href").trim()
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                emitEpisodeLinks(href, res.episode, callback)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun emitMovieLinks(
        linkUrl: String,
        heading: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(linkUrl, headers = PlayNet.headers(), timeout = 15L).document
        } catch (_: Exception) {
            null
        } ?: return
        val shareHref = doc.selectFirst("a.get-link-btn")?.attr("href")?.trim().orEmpty()
        if (shareHref.startsWith("http")) {
            emitShareLinks(shareHref, heading, callback)
        }
    }

    private suspend fun emitEpisodeLinks(
        linkUrl: String,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (episode == null) return
        val doc = try {
            app.get(linkUrl, headers = PlayNet.headers(), timeout = 15L).document
        } catch (_: Exception) {
            null
        } ?: return
        val epRegex = Regex("(?i)Episode\\s*:?\\s*0*$episode(?!\\d)")
        val anchor = doc.select("h3 > a[href]").firstOrNull { epRegex.containsMatchIn(it.text()) }
        val shareHref = anchor?.attr("href")?.trim().orEmpty()
        if (shareHref.startsWith("http")) {
            emitShareLinks(shareHref, "", callback)
        }
    }
}
