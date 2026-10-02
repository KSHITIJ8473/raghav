package com.justplay

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.net.URI

internal const val PLAY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal object PlayNet {

    val cfKiller: CloudflareKiller by lazy { CloudflareKiller() }

    fun headers(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf("User-Agent" to PLAY_UA)
        if (referer != null) h["Referer"] = referer
        h.putAll(extra)
        return h
    }

    // themoviesflix and its drive hosts block a bare user agent on a lot of
    // networks, only the full browser header set gets through
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

    // one kilobyte range request, drops links whose file is gone before
    // they reach the player
    suspend fun probe(url: String, referer: String? = null): Int? {
        return try {
            val res = app.get(
                url,
                headers = browserHeaders(referer).toMutableMap().apply { put("Range", "bytes=0-1023") },
                timeout = 15L
            )
            res.code
        } catch (_: Exception) {
            null
        }
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

    // short names match half the catalog without a full compare, and short
    // phrases like The Boys also match To All the Boys, so posts must start with them
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

    // okhttp gives up after 20 redirects and some drive pages bounce between
    // ad mirrors forever, walking the location headers by hand survives that,
    // the jar matters because cloudflare hands out the clearance cookie on
    // one hop and expects it back on the next one
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

    // a real cloudflare challenge answers with the cf-mitigated header or the
    // interstitial body, a rate limit or an outage answers with neither and
    // only the real challenge is worth a webview solve
    private fun isCfChallenge(res: NiceResponse): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        val body = try { res.text.lowercase() } catch (_: Exception) { "" }
        return body.contains("just a moment") || body.contains("challenge-platform") ||
            body.contains("checking your browser")
    }

    // the killer keeps its cookies per host and never re-solves once it has
    // some, dropping them for this host is what forces a fresh webview pass
    // when the saved ones went stale
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

        runCatching { cfKiller.savedCookies.remove(URI(url).host) }
        val solved = try {
            app.get(url, headers = headers(referer), interceptor = cfKiller, timeout = solveTimeout)
        } catch (_: Exception) {
            null
        }
        if (solved != null && solved.code == 200 && !isCfChallenge(solved)) return solved
        return null
    }

    // the drive hosts sit behind cloudflare, a plain request either dies in a
    // redirect loop or lands on a challenge page, the killer solves the
    // challenge in a webview, the manual walk with cookies survives the loop
    // and the mirror host is the last resort
    suspend fun fetchDrivePage(url: String, referer: String?): NiceResponse? {
        for (candidate in listOf(url, driveMirror(url))) {
            fetchWithCf(candidate, referer)?.let { return it }
            followManually(candidate, referer)?.let { return it }
        }
        return null
    }

    // the 10gbps buttons hide a google drive file behind a chain of worker
    // redirects, the final url is the only playable one
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

    // greenmotors and the wp shorteners answer with a redirect first, the payload
    // with the real target only sits on the page after following it
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
        // the hubcloud family names its links "Server [file | size]", the
        // part before the bracket is the server, the rest is info
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
            for (link in collected) {
                buildSiteLink(site, label, quality, link)?.let(callback)
            }
        } catch (_: Exception) {}
    }

    // routes through justplay's own extractors because loadExtractor picks
    // whichever extension registered last for a host
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
            for (link in collected) {
                buildSiteLink(site, label, quality, link)?.let(callback)
            }
        } catch (_: Exception) {}
    }
}
