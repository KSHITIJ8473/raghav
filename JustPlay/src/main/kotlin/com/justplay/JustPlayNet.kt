package com.justplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal const val PLAY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object PlayNet {

    private val cfKillers = ConcurrentHashMap<String, CloudflareKiller>()
    private val cfLocks = ConcurrentHashMap<String, Mutex>()

    fun killerFor(url: String): CloudflareKiller =
        cfKillers.getOrPut(hostOf(url)) { CloudflareKiller() }

    private fun lockFor(url: String): Mutex =
        cfLocks.getOrPut(hostOf(url)) { Mutex() }

    private val verifiedUrls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val verifyGate = Semaphore(8)

    suspend fun <T> retry(attempts: Int = 2, gapMs: Long = 600L, block: suspend () -> T?): T? {
        repeat(attempts) { i ->
            try {
                block()?.let { return it }
            } catch (_: Exception) {
            }
            if (i < attempts - 1) delay(gapMs)
        }
        return null
    }

    fun headers(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf("User-Agent" to PLAY_UA)
        if (referer != null) h["Referer"] = referer
        h.putAll(extra)
        return h
    }

    fun browserHeaders(referer: String? = null): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = PLAY_UA
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

    suspend fun probe(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Int? {
        return try {
            val h = browserHeaders(referer).toMutableMap()
            h.putAll(headers)
            h["Range"] = "bytes=0-1023"
            val res = app.get(url, headers = h, timeout = 12L)
            res.code
        } catch (_: Exception) {
            null
        }
    }

    suspend fun alive(
        url: String,
        type: ExtractorLinkType,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): Boolean {
        if (!url.startsWith("http")) return false
        if (url in verifiedUrls) return true
        return verifyGate.withPermit {
            if (url in verifiedUrls) return@withPermit true
            val merged = if (referer != null && headers.keys.none { it.equals("Referer", true) }) {
                headers + mapOf("Referer" to referer)
            } else {
                headers
            }
            val ok = try {
                when (type) {
                    ExtractorLinkType.M3U8 -> m3u8Alive(url, merged)
                    else -> probe(url, referer, merged)?.let { it in 200..399 } ?: false
                }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                if (verifiedUrls.size > 600) verifiedUrls.clear()
                verifiedUrls.add(url)
            }
            ok
        }
    }

    fun linkType(url: String): ExtractorLinkType {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return if (clean.endsWith(".mp4") || clean.endsWith(".mkv") || clean.endsWith(".avi") ||
            clean.endsWith(".webm") || clean.endsWith(".mov") || clean.endsWith(".m4a")
        ) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
    }

    suspend fun m3u8Alive(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!url.startsWith("http")) return false
        return try {
            val res = app.get(url, headers = headers, timeout = 12L)
            res.code == 200 && res.text.trimStart().startsWith("#EXTM3U")
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fileAlive(url: String, referer: String? = null): Boolean {
        if (!url.startsWith("http")) return false
        return probe(url, referer) in 200..299
    }

    fun getBaseUrl(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) {
        url
    }

    fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val lower = str.lowercase()
        return when {
            lower.contains("8k") -> 4320
            lower.contains("4k") || lower.contains("uhd") -> 2160
            lower.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

    fun rot13(input: String): String = buildString {
        for (c in input) {
            when (c) {
                in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                else -> append(c)
            }
        }
    }

    fun normalizeTitle(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")

    fun seasonsOf(text: String): Set<Int>? {
        val seasons = mutableSetOf<Int>()
        Regex("(?i)Season\\s*(\\d{1,2})\\s*[-\\u2013]\\s*(\\d{1,2})").findAll(text).forEach {
            val a = it.groupValues[1].toIntOrNull() ?: return@forEach
            val b = it.groupValues[2].toIntOrNull() ?: return@forEach
            (a..b).forEach { s -> if (s in 1..50) seasons.add(s) }
        }
        Regex("(?i)\\bS(\\d{1,2})\\s*[-\\u2013]\\s*S?(\\d{1,2})\\b").findAll(text).forEach {
            val a = it.groupValues[1].toIntOrNull() ?: return@forEach
            val b = it.groupValues[2].toIntOrNull() ?: return@forEach
            (a..b).forEach { s -> if (s in 1..50) seasons.add(s) }
        }
        Regex("(?i)Season\\s*(\\d{1,2})").findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let { s -> if (s in 1..50) seasons.add(s) }
        }
        Regex("""\bS(\d{1,2})\b""").findAll(text).forEach {
            it.groupValues[1].toIntOrNull()?.let { s -> if (s in 1..50) seasons.add(s) }
        }
        return seasons.ifEmpty { null }
    }

    fun titleMatches(postTitle: String?, query: String): Boolean {
        if (postTitle.isNullOrBlank()) return false
        val normQuery = normalizeTitle(query)
        if (normQuery.isBlank()) return false
        if (!normalizeTitle(postTitle).contains(normQuery)) return false
        if (normQuery.length < 4) return normalizeTitle(postTitle) == normQuery
        if (normQuery.length >= 10) return true
        var stripped = normalizeTitle(postTitle)
        for (prefix in listOf("download", "watch")) {
            if (stripped.startsWith(prefix) && stripped.length > prefix.length) {
                stripped = stripped.substring(prefix.length)
            }
        }
        return stripped.startsWith(normQuery)
    }

    fun yearMatches(text: String, year: Int?): Boolean {
        if (year == null) return true
        val years = Regex("(19|20)\\d{2}").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
        if (years.isEmpty()) return true
        return years.any { kotlin.math.abs(it - year) <= 1 }
    }

    fun deEsc(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("&amp;", "&")

    fun slugify(s: String): String {
        val cleaned = s.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), "").trim()
        return Regex("\\s+").replace(cleaned, "-").lowercase()
    }

    fun absolute(href: String, base: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base.trimEnd('/') + href
        else -> base.trimEnd('/') + "/" + href
    }

    fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (_: Exception) {
        ""
    }

    suspend fun followManually(url: String, referer: String?): NiceResponse? {
        var current = url
        val jar = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        repeat(15) {
            if (!seen.add(current)) return null
            val res = try {
                app.get(
                    current,
                    headers = headers(referer),
                    cookies = jar,
                    allowRedirects = false,
                    timeout = 15L
                )
            } catch (_: Exception) {
                return null
            }
            jar.putAll(res.cookies)
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) return res.takeIf { it.code == 200 }
            current = absolute(loc, current)
        }
        return null
    }

    private fun driveMirror(url: String): String = when {
        url.contains("nexdrive.fit") -> url.replace("nexdrive.fit", "mobilejsr.rest")
        url.contains("mobilejsr.rest") -> url.replace("mobilejsr.rest", "nexdrive.fit")
        else -> url
    }

    private fun isCfChallenge(res: NiceResponse): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        val body = try { res.text.lowercase() } catch (_: Exception) { "" }
        return (body.contains("just a moment") && body.contains("challenge-platform")) ||
            body.contains("checking your browser") ||
            body.contains("checking if the site connection is secure")
    }

    suspend fun fetchWithCf(
        url: String,
        referer: String? = null,
        timeout: Long = 20L,
        solveTimeout: Long = 60L
    ): NiceResponse? {
        val plain = try {
            app.get(url, headers = headers(referer), timeout = timeout)
        } catch (_: Exception) {
            null
        }
        if (plain != null && plain.code == 200 && !isCfChallenge(plain)) return plain

        return lockFor(url).withLock {
            val killer = killerFor(url)
            runCatching { killer.savedCookies.remove(URI(url).host) }
            val solved = try {
                app.get(url, headers = headers(referer), interceptor = killer, timeout = solveTimeout)
            } catch (_: Exception) {
                null
            }
            if (solved != null && solved.code == 200 && !isCfChallenge(solved)) solved else null
        }
    }

    suspend fun fetchDrivePage(url: String, referer: String?): NiceResponse? {
        for (candidate in listOf(url, driveMirror(url))) {
            fetchWithCf(candidate, referer)?.let { return it }
            followManually(candidate, referer)?.let { return it }
        }
        return null
    }

    suspend fun resolveRedirectTarget(url: String, referer: String? = null): String? {
        var current = url
        repeat(7) {
            val res = try {
                app.get(current, headers = headers(referer), allowRedirects = false, timeout = 8L)
            } catch (_: Exception) {
                return null
            }
            val loc = res.headers["location"]?.trim().orEmpty()
            if (loc.isEmpty()) {
                return current.takeIf { it.startsWith("http") }
            }
            current = absolute(loc, current)
        }
        return null
    }

    suspend fun decryptIdLink(url: String, referer: String? = null): String? {
        return try {
            val res = app.get(
                url,
                headers = headers(referer),
                allowRedirects = true,
                timeout = 15L
            )
            val text = res.text
            val m1 = Regex("""s\('o','([A-Za-z0-9+/=]+)'""").findAll(text)
                .map { it.groupValues[1] }.toList()
            val m2 = Regex("""ck\('_wp_http_\d+','([^']+)'""").findAll(text)
                .map { it.groupValues[1] }.toList()
            val concat = (m1 + m2).joinToString("")
            if (concat.isBlank()) return null
            val decoded = runCatching {
                base64Decode(rot13(base64Decode(base64Decode(concat))))
            }.getOrNull() ?: return null
            val obj = try {
                JSONObject(decoded)
            } catch (_: Exception) {
                null
            }
            if (obj == null) {
                val direct = runCatching { base64Decode(decoded) }.getOrNull() ?: decoded
                return direct.trim().takeIf { it.startsWith("http") }
            }
            val o = obj.optString("o").trim()
            val data = obj.optString("data").trim()
            val blog = obj.optString("blog_url").trim()
            if (data.isNotBlank() && blog.isNotBlank()) {
                val reRes = app.get(
                    "$blog?re=$data",
                    headers = headers(url),
                    allowRedirects = false,
                    timeout = 15L
                )
                val body = reRes.document.body().text().trim()
                return body.ifBlank { o }.ifBlank { null }
            }
            val target = o.ifBlank { obj.optString("l").trim() }
            if (target.startsWith("http")) return target
            val unbased = runCatching { base64Decode(target) }.getOrNull() ?: target
            return unbased.trim().takeIf { it.startsWith("http") } ?: target.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun buildSiteLink(
        site: String,
        label: String,
        quality: Int?,
        link: ExtractorLink
    ): ExtractorLink? {

        val server = link.name.substringBefore(" [").trim()
        val extras = link.name.substringAfter(" [", "").removeSuffix("]").trim()
        val info = listOf(extras, label).filter { it.isNotBlank() }.joinToString(" ")
        val name = PlayLabels.buildLabel(site, server, info)
        return newExtractorLink(
            "[${PlayLabels.siteName(site)}]",
            name,
            link.url,
            link.type
        ) {
            this.quality = quality ?: link.quality
            this.referer = link.referer
            this.headers = link.headers
            this.extractorData = link.extractorData
        }
    }

    suspend fun emitSiteLink(
        site: String,
        url: String,
        label: String,
        quality: Int? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.isBlank()) return
        try {
            val collected = mutableListOf<ExtractorLink>()
            loadExtractor(url, referer, subtitleCallback) { link ->
                collected.add(link)
            }
            emitChecked(site, label, quality, collected, callback)
        } catch (_: Exception) {}
    }

    suspend fun emitOwnLink(
        site: String,
        url: String,
        label: String,
        quality: Int? = null,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.isBlank()) return
        val host = hostOf(url)
        if (host.isEmpty()) return
        val resolver: (suspend (String?, (SubtitleFile) -> Unit, (ExtractorLink) -> Unit) -> Unit) = when {
            host.endsWith("hubcloud.ist") || host.endsWith("hubcloud.foo") ->
                { r, s, c -> PlayHubCloud().getUrl(url, r, s, c) }
            host.contains("gdflix") || host.contains("gdlink") ->
                { r, s, c -> PlayGDFlix().getUrl(url, r, s, c) }
            host.contains("hubdrive") -> { r, s, c -> PlayHubdrive().getUrl(url, r, s, c) }
            host.contains("hubcdn") -> { r, s, c -> PlayHubCdn().getUrl(url, r, s, c) }
            host.contains("hblinks") -> { r, s, c -> PlayHblinks().getUrl(url, r, s, c) }
            host.contains("gofile") -> { r, s, c -> PlayGofile().getUrl(url, r, s, c) }
            host.contains("fastdl") -> { r, s, c -> PlayFastDl().getUrl(url, r, s, c) }
            host.contains("vcloud") -> { r, s, c -> PlayVCloud().getUrl(url, r, s, c) }
            host.contains("vegadrive") -> { r, s, c -> PlayVegaDrive().getUrl(url, r, s, c) }
            host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") ->
                { r, s, c -> PlayFilePress().getUrl(url, r, s, c) }
            else -> {
                emitSiteLink(site, url, label, quality, referer, subtitleCallback, callback)
                return
            }
        }
        try {
            val collected = mutableListOf<ExtractorLink>()
            resolver(referer, subtitleCallback) { collected.add(it) }
            emitChecked(site, label, quality, collected, callback)
        } catch (_: Exception) {}
    }

    private suspend fun emitChecked(
        site: String,
        label: String,
        quality: Int?,
        links: List<ExtractorLink>,
        callback: (ExtractorLink) -> Unit
    ) {
        if (links.isEmpty()) return
        val checked = withTimeoutOrNull(45_000L) {
            coroutineScope {
                links.map { link ->
                    async(Dispatchers.IO) {
                        if (alive(link.url, link.type, link.headers, link.referer)) link else null
                    }
                }.mapNotNull { runCatching { it.await() }.getOrNull() }
            }
        } ?: return
        for (link in checked) {
            buildSiteLink(site, label, quality, link)?.let(callback)
        }
    }
}
