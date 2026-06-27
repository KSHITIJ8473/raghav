package com.laddu100

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import java.net.URL
import java.net.URLDecoder

class Anikai : MainAPI() {
    override var mainUrl = "https://www3.anikai.cc"
    override var name = "Anikai"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    /**
     * Standard browser headers sent with every request.
     * anikai.cc returns 403 to bare Java/OkHttp UAs, so a real Android Chrome UA is required.
     * The Referer is set per-request by callers using `headersWith(referer)`.
     */
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun headersWith(referer: String = mainUrl): Map<String, String> =
        baseHeaders + ("Referer" to referer)

    override val mainPage = mainPageOf(
        "latest-updates" to "Latest Updates",
        "New Releases" to "New Releases",
        "Top Airing" to "Top Airing",
        "Completed" to "Completed"
    )

    // ============================================================
    //  A. HOMEPAGE
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home", headers = baseHeaders).document
        val home = mutableListOf<SearchResponse>()
        val category = request.data

        if (category == "latest-updates") {
            val items = doc.select(".r-update .aitem, .load-widget .aitem")
            for (item in items) {
                val aTag = item.selectFirst("a.poster") ?: continue
                var href = aTag.attr("href")
                href = href.replace(Regex("/ep-\\d+$"), "")
                val fixHref = fixUrl(href)
                val title = item.selectFirst(".title")?.text()?.trim() ?: continue
                val img = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

                // Detect real dub availability from the card badges
                val hasDub = item.select(".dub").isNotEmpty()
                val hasSub = item.select(".sub").isNotEmpty()

                home.add(newAnimeSearchResponse(title, fixHref, TvType.Anime) {
                    this.posterUrl = img
                    addDubStatus(dubExist = hasDub, subExist = hasSub || !hasDub)
                })
            }
        } else {
            val section = doc.select("div.inner, section.swiper-slide").firstOrNull { sec ->
                sec.select("span.stitle").text().contains(category, ignoreCase = true)
            }
            if (section != null) {
                val items = section.select(".aitem")
                for (item in items) {
                    var href = item.attr("href").ifEmpty { item.selectFirst("a")?.attr("href") } ?: continue
                    href = href.replace(Regex("/ep-\\d+$"), "")
                    val fixHref = fixUrl(href)
                    val title = item.selectFirst(".title")?.text()?.trim() ?: continue
                    val img = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

                    val hasDub = item.select(".dub").isNotEmpty()
                    val hasSub = item.select(".sub").isNotEmpty()

                    home.add(newAnimeSearchResponse(title, fixHref, TvType.Anime) {
                        this.posterUrl = img
                        addDubStatus(dubExist = hasDub, subExist = hasSub || !hasDub)
                    })
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // ============================================================
    //  B. SEARCH
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/browser?keyword=${java.net.URLEncoder.encode(query, "utf-8")}"
        val doc = app.get(url, headers = baseHeaders).document
        val results = mutableListOf<SearchResponse>()

        val items = doc.select(".aitem")
        for (item in items) {
            val aTag = item.selectFirst("a.poster") ?: continue
            var href = aTag.attr("href")
            href = href.replace(Regex("/ep-\\d+$"), "")
            val fixHref = fixUrl(href)
            val title = item.selectFirst(".title")?.text()?.trim() ?: continue
            val img = item.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""

            val hasDub = item.select(".dub").isNotEmpty()
            val hasSub = item.select(".sub").isNotEmpty()

            results.add(newAnimeSearchResponse(title, fixHref, TvType.Anime) {
                this.posterUrl = img
                addDubStatus(dubExist = hasDub, subExist = hasSub || !hasDub)
            })
        }

        return results
    }

    // ============================================================
    //  C. LOAD (details + episode list with sub/dub separation)
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders).document

        val title = doc.selectFirst("h1.title")?.text()?.trim() ?: return null
        val jpTitle = doc.selectFirst("h1.title")?.attr("data-jp")
        val posterUrl = doc.selectFirst(".poster img")?.attr("src")
            ?: doc.selectFirst("img[itemprop=image]")?.attr("src")
        val backgroundUrl = doc.selectFirst(".watch-section-bg")?.attr("style")?.let { style ->
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
        }
        val plot = doc.selectFirst(".desc.text-expand")?.text()?.trim()
            ?: doc.selectFirst(".desc")?.text()?.trim()
        val year = doc.selectFirst("a[href*=year]")?.text()?.trim()?.toIntOrNull()
        val tags = doc.select("a[href*=/genres/]").map { it.text().replace(Regex("^,\\s*"), "").trim() }

        val statusStr = doc.select("div").firstOrNull { it.text().contains("Status:") }
            ?.selectFirst("span")?.text()?.trim()
        val showStatus = when (statusStr?.lowercase()) {
            "currently airing" -> ShowStatus.Ongoing
            "finished airing", "completed" -> ShowStatus.Completed
            else -> null
        }

        val typeStr = doc.select("div").firstOrNull { it.text().contains("Type:") }
            ?.selectFirst("span")?.text()?.trim() ?: ""
        val tvType = when (typeStr.lowercase()) {
            "movie" -> TvType.AnimeMovie
            "ova", "ona", "special" -> TvType.OVA
            else -> TvType.Anime
        }

        // ---- Episode parsing with per-episode sub/dub awareness ----
        // Each <a> in the eplist carries data-sub / data-dub / data-hsub / data-raw flags.
        // Sub episodes use the "sub" prefix, Dub episodes use the "dub" prefix.
        // The same episode URL can appear under both DubStatus.Subbed and DubStatus.Dubbed
        // because the WATCH URL is identical — the audio type is selected later in loadLinks
        // by picking the correct <div class="server-items" data-id="sub|dub|hsub|raw"> group.
        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        val epLinks = doc.select(".eplist ul.range li a")
        for (ep in epLinks) {
            val epHref = ep.attr("href")
            if (epHref.isEmpty()) continue
            val epNum = ep.attr("data-num").toIntOrNull() ?: continue

            // Cleaner episode name: prefer the <span data-jp="..."> text, fallback to "Episode N"
            val name = ep.selectFirst("span[data-jp]")?.attr("data-jp")?.trim()?.ifEmpty { null }
                ?: ep.selectFirst("span[data-jp]")?.text()?.trim()?.ifEmpty { null }
                ?: "Episode $epNum"

            val hasHsub = ep.attr("data-hsub") == "1"
            val hasSub  = ep.attr("data-sub") == "1"
            val hasDub  = ep.attr("data-dub") == "1"

            // "Subbed" in CloudStream = soft-sub OR hard-sub (both are Japanese audio).
            // We prefer soft-sub (data-sub) when available because it ships a real VTT subtitle
            // track; if only hard-sub exists, we still add it under Subbed.
            if (hasSub || hasHsub) {
                // Pick the best audio type to request in loadLinks: prefer "sub" (soft) over "hsub" (hard)
                val subTag = if (hasSub) "sub" else "hsub"
                subEpisodes.add(newEpisode("$subTag|${fixUrl(epHref)}") {
                    this.name = name
                    this.episode = epNum
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("dub|${fixUrl(epHref)}") {
                    this.name = name
                    this.episode = epNum
                })
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            if (jpTitle != null) this.japName = jpTitle
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ============================================================
    //  D. LOAD LINKS (the Sub/Dub fix lives here)
    // ============================================================
    //
    //  Episode data string format:  "<audioType>|<watchUrl>"
    //    audioType ∈ { "sub", "dub", "hsub", "raw" }
    //
    //  On the watch page, anikai renders one <div class="server-items lang-group"
    //  data-id="<audioType>"> PER audio type, each containing the SAME server names
    //  (HD-1, HD-2, StreamHG, Earnvids, Doodstream) but with DIFFERENT data-video
    //  embed URLs. The dub embed URL points to a completely separate stream on the
    //  host (e.g. vivibebe.site/e20b6702cacec394 vs the sub's /60cbbc019415ceb3).
    //
    //  FIX: We select ONLY the server-items group whose data-id matches the
    //  requested audioType. This guarantees that when the user picks "Dub" in
    //  CloudStream, only the dub embed URLs are extracted — never the sub ones.
    //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Use limit=2 so a URL containing '|' (defensive) doesn't break the split.
        val parts = data.split("|", limit = 2)
        if (parts.size < 2) return false

        val audioType = parts[0]   // "sub" | "dub" | "hsub" | "raw"
        val watchUrl  = parts[1]

        val doc = app.get(watchUrl, headers = headersWith(mainUrl)).document

        // === BULLETPROOF SELECTOR ===
        // Pick ONLY the server-items group matching the requested audio type.
        // No filter / no flatMap — a single explicit CSS attribute selector.
        // If the group doesn't exist (e.g. user picked Dub but this episode has no dub),
        // we return false so CloudStream shows "No sources found" instead of falling
        // back to the sub sources.
        val serverGroup = doc.selectFirst("div.server-items[data-id=$audioType]")
            ?: return false

        val servers = serverGroup.select("span.server-video")
        if (servers.isEmpty()) return false

        var foundAny = false

        for (server in servers) {
            val embedUrl = server.attr("data-video").trim()
            if (embedUrl.isEmpty()) continue
            val serverName = server.text().trim()
            val label = "$serverName (${audioType.uppercase()})"

            // Step 1: extract any subtitle track baked into the embed URL query string
            extractSubtitleFromUrl(embedUrl, subtitleCallback)

            // Step 2: resolve the embed URL to a playable m3u8/mp4
            try {
                when {
                    "vivibebe.site" in embedUrl || "bibiemb.xyz" in embedUrl -> {
                        foundAny = extractDirectM3u8(embedUrl, label, audioType, callback) || foundAny
                    }
                    "otakuhg.site" in embedUrl || "otakuvid.online" in embedUrl -> {
                        foundAny = extractPackedM3u8(embedUrl, label, audioType, callback) || foundAny
                    }
                    "playmogo.com" in embedUrl -> {
                        // playmogo.com is a Doodstream mirror — CloudStream's built-in
                        // Doodstream extractor isn't registered for this domain, so try
                        // the built-in one first (in case the user's CS build supports it),
                        // then fall back to our custom extractor.
                        val loaded = loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                        if (!loaded) {
                            foundAny = extractDoodstream(embedUrl, label, audioType, callback) || foundAny
                        } else {
                            foundAny = true
                        }
                    }
                    else -> {
                        val loaded = loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                        if (loaded) foundAny = true
                        else foundAny = extractDirectM3u8(embedUrl, label, audioType, callback) || foundAny
                    }
                }
            } catch (_: Exception) { }
        }

        return foundAny
    }

    // ============================================================
    //  Helper: extract subtitle URL from embed query string
    // ============================================================
    //  Anikai bakes the VTT subtitle URL into the embed URL using different
    //  query param names depending on the host:
    //    vivibebe / bibiemb   →  ?sub=<vtt-url>
    //    otakuhg / otakuvid   →  ?caption_1=<vtt-url>&sub_1=English
    //    playmogo (Doodstream) →  ?c1_file=<vtt-url>&c1_label=English
    private fun extractSubtitleFromUrl(embedUrl: String, callback: (SubtitleFile) -> Unit) {
        try {
            val urlObj = URL(embedUrl)
            val query = urlObj.query ?: return
            val subParam = Regex("""(?:sub|caption_1|c1_file)=([^&]+)""").find(query)?.groupValues?.get(1)
                ?: return
            val decodedSub = URLDecoder.decode(subParam, "UTF-8")
            val label = Regex("""(?:sub_1|c1_label)=([^&]+)""").find(query)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "English"
            callback.invoke(newSubtitleFile(label, decodedSub))
        } catch (_: Exception) { }
    }

    // ============================================================
    //  Helper: extract m3u8 from a plain embed page (vivibebe / bibiemb)
    // ============================================================
    private suspend fun extractDirectM3u8(
        embedUrl: String,
        label: String,
        audioType: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedHtml = app.get(embedUrl, headers = headersWith(mainUrl)).text
        // Try double-quoted, single-quoted, and bare URLs
        val m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(embedHtml)?.groupValues?.get(1)
            ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = label,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to URL(embedUrl).protocol + "://" + URL(embedUrl).host
                )
            }
        )
        return true
    }

    // ============================================================
    //  Helper: extract m3u8 from a JS-packed embed (otakuhg / otakuvid)
    // ============================================================
    private suspend fun extractPackedM3u8(
        embedUrl: String,
        label: String,
        audioType: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedHtml = app.get(embedUrl, headers = headersWith(mainUrl)).text
        // First try without unpacking — some pages have a bare m3u8 in a <source> tag
        var m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(embedHtml)?.groupValues?.get(1)

        // Otherwise unpack the eval(function(p,a,c,k,e,d){...}) block and scan again
        if (m3u8Url == null) {
            val unpacked = JsPacker.parseAndUnpack(embedHtml)
            if (unpacked != null) {
                m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
            }
        }

        if (m3u8Url == null) return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = label,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "Referer" to embedUrl,
                    "Origin" to URL(embedUrl).protocol + "://" + URL(embedUrl).host
                )
            }
        )
        return true
    }

    // ============================================================
    //  Helper: custom Doodstream extractor for playmogo.com
    // ============================================================
    //  Flow:
    //    1. GET embed page → extract /pass_md5/... path
    //    2. GET https://<host>/pass_md5/... with Referer = embed URL → returns a short token string
    //    3. Build final URL: https://<host>/d/<token> and return as MP4
    private suspend fun extractDoodstream(
        embedUrl: String,
        label: String,
        audioType: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedHtml = app.get(embedUrl, headers = headersWith(mainUrl)).text
        val passMd5Path = Regex("""(/pass_md5/[^"'\s]+)""").find(embedHtml)?.groupValues?.get(1)
            ?: return false

        val host = URL(embedUrl).let { "${it.protocol}://${it.host}" }
        val tokenResp = app.get(
            "$host$passMd5Path",
            headers = headersWith(embedUrl)
        ).text.trim()

        if (tokenResp.isEmpty() || tokenResp.equals("RELOAD", ignoreCase = true)) return false

        // Doodstream final URL: https://<host>/d/<token>
        val finalUrl = "$host/d/$tokenResp"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = label,
                url = finalUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf(
                    "Referer" to embedUrl,
                    "User-Agent" to baseHeaders["User-Agent"]!!
                )
            }
        )
        return true
    }
}

// ============================================================
//  JsPacker — unpacks eval(function(p,a,c,k,e,d){...}) obfuscation
//  used by otakuhg.site and otakuvid.online embeds.
// ============================================================
object JsPacker {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun baseN(num: Int, base: Int): String {
        if (num == 0) return CHARS[0].toString()
        var temp = num
        val sb = StringBuilder()
        while (temp > 0) {
            sb.append(CHARS[temp % base])
            temp /= base
        }
        return sb.reverse().toString()
    }

    fun unpack(p: String, a: Int, c: Int, k: List<String>): String {
        var payload = p
        for (i in c - 1 downTo 0) {
            if (i < k.size && k[i].isNotEmpty()) {
                val key = k[i]
                val baseStr = baseN(i, a)
                val regex = Regex("\\b$baseStr\\b")
                payload = payload.replace(regex, key)
            }
        }
        return payload
    }

    fun parseAndUnpack(html: String): String? {
        val startIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
        val actualStart = if (startIdx != -1) startIdx else html.indexOf("function(p,a,c,k,e,d)")
        if (actualStart == -1) return null

        val openBraceIdx = html.indexOf("{", actualStart)
        if (openBraceIdx == -1) return null

        var braceCount = 1
        var j = openBraceIdx + 1
        while (j < html.length && braceCount > 0) {
            if (html[j] == '{') braceCount++
            else if (html[j] == '}') braceCount--
            j++
        }

        val argsStartIdx = html.indexOf("(", j - 1)
        if (argsStartIdx == -1) return null

        var argsParenCount = 1
        var kIdx = argsStartIdx + 1
        while (kIdx < html.length && argsParenCount > 0) {
            if (html[kIdx] == '(') argsParenCount++
            else if (html[kIdx] == ')') argsParenCount--
            kIdx++
        }

        val argsStr = html.substring(argsStartIdx + 1, kIdx - 1).trim()
        if (argsStr.isEmpty()) return null

        val startChar = argsStr.first()
        var payload = ""
        var i = 1
        while (i < argsStr.length) {
            if (argsStr[i] == startChar) {
                var backslashCount = 0
                var m = i - 1
                while (m >= 0 && argsStr[m] == '\\') {
                    backslashCount++
                    m--
                }
                if (backslashCount % 2 == 0) break
            }
            payload += argsStr[i]
            i++
        }

        payload = payload.replace("\\$startChar", startChar.toString()).replace("\\\\", "\\")

        val rest = argsStr.substring(i + 1)
        val restQuoteMatch = Regex("[\"']").find(rest) ?: return null
        val quotePos = restQuoteMatch.range.first
        val restQuoteChar = restQuoteMatch.value

        val ints = Regex("\\b\\d+\\b").findAll(rest.substring(0, quotePos)).map { it.value.toInt() }.toList()
        if (ints.size < 2) return null
        val a = ints[0]
        val c = ints[1]

        var keysStr = ""
        var jj = quotePos + 1
        while (jj < rest.length) {
            if (rest[jj].toString() == restQuoteChar) {
                var backslashCount = 0
                var m = jj - 1
                while (m >= 0 && rest[m] == '\\') {
                    backslashCount++
                    m--
                }
                if (backslashCount % 2 == 0) break
            }
            keysStr += rest[jj]
            jj++
        }

        keysStr = keysStr.replace("\\$restQuoteChar", restQuoteChar).replace("\\\\", "\\")
        val keys = keysStr.split("|")

        return unpack(payload, a, c, keys)
    }
}
