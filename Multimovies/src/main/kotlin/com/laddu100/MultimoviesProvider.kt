package com.laddu100

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class MultimoviesProvider : MainAPI() {
    override var mainUrl = "https://multimovies.motorcycles"
    override var name = "Multimovies"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private var modiplayBase: String? = null

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    override val mainPage = mainPageOf(
        "/trending/" to "Trending",
        "/movies/" to "Latest Movies",
        "/tvshows/" to "Latest TV Shows",
        "/genre/anime-series/" to "Anime Series",
        "/genre/anime-movies/" to "Anime Movies",
        "/genre/bollywood-movies/" to "Bollywood",
    )

    private fun abs(base: String, url: String): String {
        val u = url.trim()
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return if (u.startsWith("/")) base + u else base + "/" + u
    }

    private fun deEsc(s: String): String =
        s.replace("\\/", "/").replace("\\\"", "\"").replace("&amp;", "&")

    private fun hostOf(url: String): String = try {
        URI(url).host?.lowercase() ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun originOf(url: String): String = try {
        val u = URI(url)
        "${u.scheme}://${u.host}"
    } catch (e: Exception) {
        ""
    }

    private fun firstImg(el: Element): String {
        val img = el.selectFirst("img") ?: return ""
        return img.attr("src").ifBlank { img.attr("data-src") }
    }

    private data class CineServer(val embed: String, val platform: String, val name: String, val code: String)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("multimovies") ?: mainUrl
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$mainUrl$base/" else "$mainUrl$base/page/$page/"
        return try {
            val doc = mmGet(url, headers = headers).document
            val items = doc.select("article.item, .items article").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            val hasNext = doc.selectFirst("a[href*='/page/${page + 1}/'], a.next.page-numbers") != null
            newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        mainUrl = FirebaseDomainHelper.getDomain("multimovies") ?: mainUrl
        return try {
            val doc = mmGet("$mainUrl/?s=${query.trim().replace(" ", "+")}", headers = headers).document
            doc.select(".result-item article").mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleA = selectFirst(".details .title a, .data h3 a")
        val a = titleA
            ?: selectFirst(".thumbnail a, a[href*='/movies/'], a[href*='/tvshows/']")
            ?: return null
        val href = a.attr("href").trim()
        if (href.isBlank()) return null
        var title = titleA?.text()?.trim() ?: ""
        if (title.isBlank()) {
            title = selectFirst("img")?.attr("alt")?.trim() ?: ""
        }
        if (title.isBlank()) return null

        val poster = firstImg(this)
        val isTv = href.contains("/tvshows/") || selectFirst(".tvshows, .item.tvshows, span.tvshows") != null
        val year = Regex("(19|20)\\d{2}").find(text())?.value?.toIntOrNull()

        return newMovieSearchResponse(title, href, if (isTv) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("multimovies") ?: mainUrl
        return try {
            val doc = mmGet(url, headers = headers).document
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst(".poster img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            } ?: ""
            val background = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            val genres = doc.select("a[href*='/genre/']").map { it.text().trim() }
                .filter { it.isNotBlank() }.distinct()
            val plot = doc.selectFirst(".wp-content p, .wp-content, [itemprop=description]")?.text()
                ?.trim()?.take(1000)
            val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.selectFirst("span.date")?.text() ?: "")?.value?.toIntOrNull()
            val rating = doc.selectFirst(".dt_rating_vgs, .rating")?.text()?.trim()?.toDoubleOrNull()
            val duration = doc.selectFirst(".runtime")?.text()?.trim()?.let {
                Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

            val isTv = url.contains("/tvshows/") || doc.selectFirst("div.se-c") != null
            val tvType = if (url.contains("anime") || genres.any { it.contains("anime", true) }) TvType.Anime else TvType.TvSeries

            if (isTv) {
                val episodes = parseEpisodes(doc)
                return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                }
            } else {
                return newMovieLoadResponse(title, url, if (url.contains("anime")) TvType.Anime else TvType.Movie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = background
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = rating?.let { Score.from10(it) }
                    this.duration = duration
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseEpisodes(doc: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (block in doc.select("div.se-c")) {
            val season = block.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull() ?: continue
            for (li in block.select("ul.episodios li")) {
                val a = li.selectFirst(".episodiotitle a") ?: continue
                val href = a.attr("href").trim()
                val name = a.text().trim()
                if (href.isBlank()) continue
                val numerando = li.selectFirst(".numerando")?.text() ?: ""
                val epNum = Regex("(\\d+)\\s*-\\s*(\\d+)").find(numerando)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("(?:\\d+)x(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                episodes.add(newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = epNum
                    this.posterUrl = firstImg(li)
                })
            }
        }
        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var any = false
        try {
            val doc = mmGet(data, headers = headers).document
            val options = doc.select("li.dooplay_player_option")
                .filter { !it.attr("id").contains("trailer") }
            if (options.isEmpty()) return false

            val embeds = mutableListOf<Pair<String, String>>()
            for (opt in options) {
                val postId = opt.attr("data-post").trim()
                val type = opt.attr("data-type").trim().ifBlank { "movie" }
                val nume = opt.attr("data-nume").trim()
                val label = opt.selectFirst(".title")?.text()?.trim()
                    ?.replace(" - Recommended", "", ignoreCase = true)
                    ?.replace(" Recommended", "", ignoreCase = true) ?: "Source"
                if (postId.isBlank() || nume.isBlank()) continue
                try {
                    val embed = fetchEmbedUrl(postId, nume, type, data)
                    if (embed.isNotBlank()) embeds.add(embed to label)
                } catch (e: Exception) {
                    continue
                }
            }

            // gdmirror streams are proxied through the modiplay host, so resolve
            // modiplay embeds first to learn that base url
            embeds.sortedByDescending { hostOf(it.first).contains("modiplay") }.forEach { (embed, label) ->
                try {
                    any = resolveEmbed(embed, label, subtitleCallback, callback) || any
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            return false
        }
        return any
    }

    private suspend fun fetchEmbedUrl(postId: String, nume: String, type: String, pageUrl: String): String {
        val body = mmPost(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf("action" to "doo_player_ajax", "post" to postId, "nume" to nume, "type" to type),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = pageUrl,
        ).text
        return Regex("\"embed_url\"\\s*:\\s*\"([^\"]+)\"").find(body)
            ?.groupValues?.get(1)?.let { deEsc(it) } ?: ""
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val host = hostOf(embedUrl)
            when {
                host.contains("modiplay") -> resolveModiplay(embedUrl, label, subtitleCallback, callback)
                host.contains("iqsmartgames") -> resolveGdmirror(embedUrl, label, subtitleCallback, callback)
                else -> {
                    val html = mmGet(embedUrl, headers = headers).text
                    extractM3u8Links(html, originOf(embedUrl).ifBlank { embedUrl }, label, callback)
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveModiplay(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val base = originOf(embedUrl)
        if (base.isBlank()) return false
        modiplayBase = base
        val html = mmGet(embedUrl, headers = headers).text

        val servers = Regex("switchServer\\('([^']+)','([^']+)','([^']+)','([^']+)','([^']*)'")
            .findAll(html).map { m ->
                CineServer(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.distinctBy { it.code }.toList()

        var any = false
        if (servers.isEmpty()) {
            if (extractM3u8Links(html, base, label, callback)) return true
            val iframeCode = Regex("data-code=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (iframeCode != null) {
                return resolveProxyFile(base, iframeCode, "", label, subtitleCallback, callback)
            }
            return false
        }

        loadSubs(base, embedUrl, subtitleCallback)

        // the proxy serves cached master urls whose cdn tokens go stale, so
        // prefer the fresh link straight from the hoster's own embed page and
        // only fall back to the proxy when that page has nothing usable
        val seenMasters = mutableSetOf<String>()
        for (server in servers) {
            val linkLabel = "$label - ${server.name}"
            var handled = false
            if (server.embed.startsWith("http")) {
                try {
                    handled = resolveViaEmbed(server.embed, linkLabel, seenMasters, callback)
                } catch (e: Exception) {
                    handled = false
                }
            }
            if (!handled) {
                try {
                    any = resolveProxyFile(
                        base, server.code, server.platform, linkLabel, subtitleCallback, callback
                    ) || any
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return any
    }

    private suspend fun resolveViaEmbed(
        embedUrl: String,
        linkLabel: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val html = mmGet(embedUrl, headers = headers).text
            val unpacked = JsPacker.parseAndUnpack(html) ?: return false
            val embedBase = originOf(embedUrl)
            val direct = Regex("\"hls2\":\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
                ?: Regex("\"hls3\":\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
            val streamPath = Regex("\"hls4\":\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
            val master = direct?.let { deEsc(it) }?.takeIf { it.startsWith("http") }
                ?: streamPath?.let { deEsc(it) }?.takeIf { it.isNotBlank() }?.let { abs(embedBase, it) }
                ?: return false
            if (!seen.add(master)) return true
            callback(
                newExtractorLink(
                    source = name,
                    name = linkLabel,
                    url = master,
                    type = ExtractorLinkType.M3U8,
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveProxyFile(
        base: String,
        fileCode: String,
        platform: String,
        linkLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val proxyUrl = "$base/proxy.php?p=$platform&c=$fileCode&title=&site_ref=&noredirect=1"
        val page = mmGet(proxyUrl, headers = headers).text

        val src = Regex("var\\s+src\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let { deEsc(it) }
        val segRef = Regex("var\\s+SEG_REF\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let { deEsc(it) }
        if (src.isNullOrBlank()) {
            return extractM3u8Links(page, base, linkLabel, callback)
        }
        val masterUrl = abs(base, src)
        val master = mmGet(masterUrl, headers = headers).text
        if (!master.contains("#EXTM3U")) return extractM3u8Links(page, base, linkLabel, callback)

        val audioTracks = Regex("#EXT-X-MEDIA:TYPE=AUDIO,[^\\n]*NAME=\"([^\"]+)\"[^\\n]*LANGUAGE=\"([^\"]+)\"[^\\n]*URI=\"([^\"]+)\"")
            .findAll(master).map { m ->
                Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            }.toList()
        val variants = Regex("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=(\\d+)x(\\d+)[^\\n]*\\n\\s*([^\\n]+)")
            .findAll(master).map { m ->
                Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].trim())
            }.toList()

        val refererMap = segRef?.let { mapOf("Referer" to it) } ?: mapOf("Referer" to base)

        if (audioTracks.isNotEmpty()) {
            val audioSpecific = variants.isNotEmpty() &&
                audioTracks.indices.all { idx -> variants.any { it.third.contains("-a${idx + 1}") } }
            if (audioSpecific) {
                audioTracks.forEachIndexed { idx, (name, _lang, _uri) ->
                    val best = variants.filter { it.third.contains("-a${idx + 1}") }.maxByOrNull { it.first }
                    if (best != null) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$linkLabel - $name",
                                url = abs(base, best.third),
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.headers = refererMap
                                this.quality = best.second
                            }
                        )
                    }
                }
                return true
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = if (audioTracks.size > 1) "$linkLabel (Multi Audio)" else linkLabel,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8,
                ) { this.headers = refererMap }
            )
            return true
        }

        val best = variants.maxByOrNull { it.first }
        if (best != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name = linkLabel,
                    url = abs(base, best.third),
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.headers = refererMap
                    this.quality = best.second
                }
            )
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = linkLabel,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8,
                ) { this.headers = refererMap }
            )
        }
        return true
    }

    private suspend fun loadSubs(base: String, embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val imdbId = Regex("[?&]id=(tt\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val tmdbId = Regex("[?&]id=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val season = Regex("[?&]s=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val ep = Regex("[?&]e=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            if (imdbId.isBlank() && tmdbId.isBlank()) return
            val seen = mutableSetOf<String>()
            for (lang in listOf("en", "hi", "")) {
                val resp = mmGet(
                    "$base/api/subtitle_fetch.php?tmdb_id=$tmdbId&imdb_id=$imdbId&season=$season&ep=$ep&lang=$lang",
                    headers = headers,
                ).text
                val m = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(resp) ?: continue
                val url = m.groupValues[1].let { deEsc(it) }
                val langName = Regex("\"lang\"\\s*:\\s*\"([^\"]+)\"").find(resp)
                    ?.groupValues?.get(1)?.ifBlank { null } ?: "English"
                val absUrl = abs(base, url)
                if (seen.add(absUrl)) {
                    subtitleCallback(newSubtitleFile(langName, absUrl))
                }
            }
        } catch (e: Exception) {
        }
    }

    private suspend fun resolveGdmirror(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = mmGet(embedUrl, headers = headers).text
        val finalId = Regex("let\\s+FinalID\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return false
        val idType = Regex("let\\s+idType\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: "imdbid"
        val myKey = Regex("let\\s+myKey\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
        val apiBase = Regex("let\\s+api_url\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
        val playerBase = Regex("let\\s+player_base\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: ""
        val season = Regex("let\\s+season\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val epname = Regex("let\\s+epname\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        if (apiBase.isBlank() || myKey.isBlank()) return false

        val apiUrl = if (season != null) {
            "$apiBase/myseriesapi?$idType=$finalId&season=$season&epname=${epname ?: ""}&key=$myKey"
        } else {
            "$apiBase/mymovieapi?$idType=$finalId&key=$myKey"
        }
        val json = try {
            mmGet(apiUrl, headers = headers).text
        } catch (e: Exception) {
            return false
        }
        if (!json.contains("\"success\"") || json.contains("\"success\":false") || json.contains("\"error\"")) {
            return false
        }

        val slugs = Regex("\"fileslug\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val names = Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        if (slugs.isEmpty() || playerBase.isBlank()) return false

        val proxyBase = modiplayBase
        val platformMap = mapOf(
            "smwh" to "streamhg", "flls" to "earnvids", "rpmshre" to "rpmshare",
            "upnshr" to "upnshare", "strmp2" to "streamp2p",
        )

        var any = false
        slugs.forEachIndexed { i, slug ->
            try {
                val helper = mmPost(
                    "$playerBase/embedhelper2.php",
                    data = mapOf("sid" to slug, "UserFavSite" to "", "currentDomain" to "[]"),
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    referer = "$playerBase/evid/$slug",
                ).text
                val mresult = Regex("\"mresult\"\\s*:\\s*\"([^\"]+)\"").find(helper)?.groupValues?.get(1)
                    ?: return@forEachIndexed
                val decoded = try {
                    String(Base64.decode(mresult, Base64.DEFAULT))
                } catch (e: Exception) {
                    return@forEachIndexed
                }
                val namePart = names.getOrNull(i) ?: slug
                val pairs = Regex("\"([a-z0-9]+)\"\\s*:\\s*\"([a-z0-9]+)\"").findAll(decoded).map { m ->
                    platformMap[m.groupValues[1]] to m.groupValues[2]
                }
                for ((platform, code) in pairs) {
                    if (platform == null || proxyBase == null) continue
                    try {
                        any = resolveProxyFile(
                            proxyBase, code, platform,
                            "$label - ${platform.replaceFirstChar { it.uppercase() }}",
                            subtitleCallback, callback,
                        ) || any
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
        }
        return any
    }

    private suspend fun extractM3u8Links(
        html: String,
        base: String,
        linkLabel: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val m3u8 = Regex("https?://[^\"'<>\\s\\\\]+\\.m3u8[^\"'<>\\s\\\\]*")
            .findAll(html).map { it.value.replace("\\/", "/") }.toList()
        val relM3u8 = Regex("[\"']([^\"']+\\.m3u8[^\"']*)[\"']").findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }.toList()
        val all = (m3u8 + relM3u8.map { abs(base, it) }).distinct()
        for (u in all) {
            callback(
                newExtractorLink(
                    source = name,
                    name = linkLabel,
                    url = u,
                    type = ExtractorLinkType.M3U8,
                ) { this.headers = mapOf("Referer" to base) }
            )
        }
        return all.isNotEmpty()
    }
}

private object JsPacker {
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
                payload = payload.replace(Regex("\\b${baseN(i, a)}\\b"), k[i])
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
        val payload = StringBuilder()
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
            payload.append(argsStr[i])
            i++
        }

        val unescapedPayload = payload.toString()
            .replace("\\$startChar", startChar.toString())
            .replace("\\\\", "\\")

        val rest = argsStr.substring(i + 1)
        val restQuoteMatch = Regex("[\"']").find(rest) ?: return null
        val quotePos = restQuoteMatch.range.first
        val restQuoteChar = restQuoteMatch.value

        val ints = Regex("\\b\\d+\\b").findAll(rest.substring(0, quotePos)).map { it.value.toInt() }.toList()
        if (ints.size < 2) return null
        val a = ints[0]
        val c = ints[1]

        val keysStr = StringBuilder()
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
            keysStr.append(rest[jj])
            jj++
        }

        val keys = keysStr.toString()
            .replace("\\$restQuoteChar", restQuoteChar)
            .replace("\\\\", "\\")
            .split("|")

        return unpack(unescapedPayload, a, c, keys)
    }
}
