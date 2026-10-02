package com.laddu100

import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

object TmfNet {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // the site blocks a bare user agent on a lot of networks, only the full
    // browser header set gets through
    fun browserHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = DESKTOP_UA
        h["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        h["Accept-Language"] = "en-US,en;q=0.9"
        h["sec-ch-ua"] = "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
        h["sec-ch-ua-mobile"] = "?0"
        h["sec-ch-ua-platform"] = "\"Windows\""
        h["Sec-Fetch-Dest"] = "document"
        h["Sec-Fetch-Mode"] = "navigate"
        h["Sec-Fetch-Site"] = if (referer != null) "same-origin" else "none"
        h["Sec-Fetch-User"] = "?1"
        h["Upgrade-Insecure-Requests"] = "1"
        referer?.let { h["Referer"] = it }
        return h
    }

    private val FALLBACK_DOMAINS = listOf(
        "https://themoviesflixhq.com",
        "https://moviesflixhq.com",
        "https://themoviesflix.actor",
        "https://moviesflixi.com"
    )

    private val domainMutex = Mutex()

    @Volatile
    private var activeDomain: String? = null

    // the firebase entry regularly points at a mirror that is dead or
    // cloudflare walled, whichever candidate answers first is used for the
    // rest of the session
    suspend fun domain(): String {
        activeDomain?.let { return it }
        return domainMutex.withLock {
            activeDomain?.let { return it }
            val candidates = listOfNotNull(
                FirebaseDomainHelper.getDomain("themoviesflix")?.removeSuffix("/"),
                *FALLBACK_DOMAINS.toTypedArray()
            ).distinct()
            for (candidate in candidates) {
                val res = try {
                    app.get("$candidate/?s=the", headers = browserHeaders(), timeout = 15L)
                } catch (_: Exception) {
                    null
                }
                if (res != null && res.isSuccessful) {
                    activeDomain = candidate
                    return candidate
                }
            }
            FALLBACK_DOMAINS.first()
        }
    }

    suspend fun fetchPage(url: String, referer: String? = null): Document? {
        val res = try {
            app.get(url, headers = browserHeaders(referer), timeout = 25L)
        } catch (_: Exception) {
            return null
        }
        if (!res.isSuccessful) return null
        return try {
            res.document
        } catch (_: Exception) {
            null
        }
    }

    class DrivePage(
        val title: String,
        val quality: Int?,
        val info: String,
        val links: List<String>,
        val episodes: Map<Int, List<String>>
    )

    private class CachedDrivePage(val savedAt: Long, val page: DrivePage?)

    private val driveCache = ConcurrentHashMap<String, CachedDrivePage>()
    private val driveMutex = Mutex()
    private const val DRIVE_CACHE_MS = 5 * 60 * 1000L

    fun qualityOf(text: String): Int? =
        Regex("""(\d{3,4})[pP]\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (text.contains("2160", true) || text.contains("4K", true) || text.contains("UHD", true)) 2160 else null

    fun infoOf(title: String): String =
        Regex("""[\[{(]([^\]})]+)[\]})]""").findAll(title)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() && !it.equals("links", true) }
            .joinToString(" · ")

    // links that belong to the drive page itself and never carry a file
    private val DRIVE_SELF = listOf(
        "nexdrive", "mobilejsr", "vglist", "w.org", "wordpress", "gmpg",
        "googleapis", "googletagmanager", "font-awesome", "schema", "category/",
        "t.me/+", "telegram"
    )

    private fun isDriveJunk(href: String): Boolean = DRIVE_SELF.any { href.contains(it, true) }

    private fun parseDrivePage(doc: Document): DrivePage {
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.title().substringBefore(" – ").substringBefore(" - ").trim()
        val root = doc.selectFirst("article") ?: doc.selectFirst("div.entry-content")
            ?: return DrivePage(title, qualityOf(title), infoOf(title), emptyList(), emptyMap())

        val links = root.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !isDriveJunk(href)) href else null
        }.distinct()

        // episode pages put each episode under an h4 header with its button
        // set in the paragraphs right below it
        val episodes = mutableMapOf<Int, MutableList<String>>()
        for (h4 in root.select("h4")) {
            val text = h4.text().trim()
            if (!text.contains("Episode", true)) continue
            val epNum = Regex("""Episodes?\s*:?\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: continue

            val epLinks = mutableListOf<String>()
            var sibling = h4.nextElementSibling()
            var attempts = 0
            while (sibling != null && attempts < 3) {
                for (a in sibling.select("a[href]")) {
                    val href = a.attr("href").trim()
                    if (href.startsWith("http") && !isDriveJunk(href)) epLinks.add(href)
                }
                if (epLinks.isNotEmpty()) break
                sibling = sibling.nextElementSibling()
                attempts++
            }
            if (epLinks.isNotEmpty()) {
                episodes.getOrPut(epNum) { mutableListOf() }.addAll(epLinks.distinct())
            }
        }

        return DrivePage(title, qualityOf(title), infoOf(title), links, episodes)
    }

    private suspend fun driveFetchOnce(url: String): NiceResponse? {
        val jar = mutableMapOf<String, String>()
        var current = url
        val seen = mutableSetOf<String>()
        repeat(12) {
            if (!seen.add(current)) return null
            val res = try {
                app.get(
                    current,
                    headers = browserHeaders("https://themoviesflixhq.com/"),
                    cookies = jar,
                    allowRedirects = false,
                    timeout = 20L
                )
            } catch (_: Exception) {
                return null
            }
            jar.putAll(res.cookies)
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) {
                if (res.code != 200) return null
                val body = try {
                    res.text
                } catch (_: Exception) {
                    return null
                }
                if (body.contains("challenge-platform") || body.contains("Just a moment", true)) {
                    return null
                }
                return res
            }
            current = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> originOf(current) + loc
                else -> current.trimEnd('/') + "/" + loc
            }
        }
        return null
    }

    private fun mirrorUrl(url: String): String = when {
        url.contains("nexdrive.fit") -> url.replace("nexdrive.fit", "mobilejsr.rest")
        url.contains("mobilejsr.rest") -> url.replace("mobilejsr.rest", "nexdrive.fit")
        else -> url
    }

    // nexdrive and mobilejsr serve the same drive app, when one host has a
    // bad day the other usually still answers, zip pack pages are skipped
    // because no player can open an archive
    suspend fun fetchDrivePage(url: String): DrivePage? {
        val key = url.replace("mobilejsr.rest", "nexdrive.fit")
        driveCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.savedAt < DRIVE_CACHE_MS) return cached.page
            driveCache.remove(key)
        }
        return driveMutex.withLock {
            driveCache[key]?.let { cached ->
                if (System.currentTimeMillis() - cached.savedAt < DRIVE_CACHE_MS) return cached.page
                driveCache.remove(key)
            }
            val res = driveFetchOnce(key) ?: driveFetchOnce(mirrorUrl(key))
            val page = res?.let {
                val doc = try {
                    it.document
                } catch (_: Exception) {
                    null
                }
                doc?.let { d ->
                    val parsed = parseDrivePage(d)
                    if (parsed.title.contains("zip", true)) null else parsed
                }
            }
            driveCache[key] = CachedDrivePage(System.currentTimeMillis(), page)
            page
        }
    }

    // one kilobyte range request, drops links whose file is gone before they
    // reach the player
    suspend fun probe(url: String, referer: String? = null): Int? {
        return try {
            val res = app.get(
                url,
                headers = browserHeaders(referer).toMutableMap().apply {
                    put("Range", "bytes=0-1023")
                },
                timeout = 15L
            )
            res.code
        } catch (_: Exception) {
            null
        }
    }

    fun originOf(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        url
    }
}
