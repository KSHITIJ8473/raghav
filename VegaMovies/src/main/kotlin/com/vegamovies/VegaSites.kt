package com.vegamovies

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object VegaSites {

    class Site(val id: String, val label: String, val base: String) {
        val host: String get() = base.substringAfter("://")
    }

    val VEGA = Site("v", "Vegamovies", "https://new1.vegamovies.lease")
    val ROG = Site("r", "Rogmovies", "https://rogmovies.one")
    val ALL = listOf(VEGA, ROG)

    fun siteOf(url: String): Site? = ALL.firstOrNull { url.contains(it.host, true) }

    class Row(val site: Site, val path: String, val title: String)

    fun rows(): List<Row> = listOf(
        Row(VEGA, "", "Latest • Vegamovies"),
        Row(ROG, "", "Latest • Rogmovies"),
        Row(ROG, "bollywood", "Bollywood • Rogmovies"),
        Row(ROG, "hindi-dubbed-movies", "Hindi Dubbed • Rogmovies"),
        Row(VEGA, "dual-audio-movies", "Dual Audio Movies • Vegamovies"),
        Row(VEGA, "dual-audio-series", "Dual Audio Series • Vegamovies"),
        Row(ROG, "tamil", "Tamil • Rogmovies"),
        Row(ROG, "telugu", "Telugu • Rogmovies"),
        Row(ROG, "malayalam", "Malayalam • Rogmovies"),
        Row(ROG, "kannada", "Kannada • Rogmovies"),
        Row(ROG, "punjabi", "Punjabi • Rogmovies"),
        Row(ROG, "marathi", "Marathi • Rogmovies"),
        Row(ROG, "bengali", "Bengali • Rogmovies"),
        Row(ROG, "gujarati", "Gujarati • Rogmovies"),
        Row(VEGA, "anime-series", "Anime • Vegamovies"),
        Row(VEGA, "korean-series", "K-Drama • Vegamovies"),
        Row(VEGA, "web-series/netflix", "Netflix Series • Vegamovies"),
        Row(ROG, "web-series/netflix", "Netflix Series • Rogmovies"),
        Row(VEGA, "web-series/amazon-prime-video", "Prime Video Series • Vegamovies"),
        Row(ROG, "web-series/amazon-prime-video", "Prime Video Series • Rogmovies"),
        Row(VEGA, "web-series/apple-tv", "Apple TV+ Series • Vegamovies"),
        Row(VEGA, "web-series/jiohotstar", "JioHotstar Series • Vegamovies"),
        Row(VEGA, "wwe-show", "WWE Shows • Vegamovies"),
        Row(VEGA, "movies-by-genres/action", "Action • Vegamovies"),
        Row(VEGA, "movies-by-genres/comedy", "Comedy • Vegamovies"),
        Row(VEGA, "movies-by-genres/drama", "Drama • Vegamovies"),
        Row(VEGA, "movies-by-genres/thriller", "Thriller • Vegamovies"),
        Row(VEGA, "movies-by-genres/horror", "Horror • Vegamovies"),
        Row(VEGA, "movies-by-genres/sci-fi", "Sci-Fi • Vegamovies"),
        Row(VEGA, "movies-by-genres/romance", "Romance • Vegamovies"),
        Row(VEGA, "movies-by-genres/animation", "Animation • Vegamovies"),
        Row(VEGA, "movies-by-genres/crime", "Crime • Vegamovies"),
        Row(VEGA, "movies-by-genres/adventure", "Adventure • Vegamovies"),
        Row(VEGA, "movies-by-genres/fantasy", "Fantasy • Vegamovies"),
        Row(VEGA, "movies-by-genres/mystery", "Mystery • Vegamovies"),
        Row(VEGA, "movies-by-genres/documentary", "Documentary • Vegamovies"),
        Row(VEGA, "movies-by-genres/family", "Family • Vegamovies"),
        Row(VEGA, "movies-by-year/2026", "2026 • Vegamovies"),
        Row(ROG, "movies-by-year/2026", "2026 • Rogmovies"),
        Row(VEGA, "movies-by-quality/2160p", "4K UHD • Vegamovies"),
        Row(ROG, "movies-by-quality/2160p", "4K UHD • Rogmovies"),
        Row(VEGA, "movies-by-quality/1080p", "1080p • Vegamovies"),
        Row(VEGA, "movies-by-quality/60fps", "60FPS • Vegamovies")
    )

    fun cleanTitle(raw: String): String {
        var t = raw
            .replace("&#8211;", "-").replace("&#8217;", "'")
            .replace("&#038;", "&").replace("&amp;", "&")
            .substringBefore(" ~ ").substringBefore("~ VeGaMovies").substringBefore("~ RogMovies")
            .replace(Regex("""(?i)^\s*download\s+"""), "")
            .replace(Regex("""(?i)\s+all episodes.*$"""), "")
            .replace(Regex("""\s*\|\s*(480p|720p|1080p|2160p|4K).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?i)\s*[-–]\s*(480p|720p|1080p|2160p|4K).*$"""), "")
            .trim()
        val m = Regex("""^(.*?)\s*\((\d{4})\)\s*(.*)$""").find(t)
        if (m != null) {
            val tail = m.groupValues[3]
            t = if (tail.isBlank()) m.groupValues[1] else "${m.groupValues[1]} (${m.groupValues[2]})"
        }
        t = t.replace(Regex("""\s+"""), " ")
        return t.trim()
    }

    fun looksLikeSeries(raw: String): Boolean {
        return Regex("""(?i)\bseason\s*\d|\bS\d{1,2}\s*[-+Ee]|\bS\d{1,2}\b.*\bS\d{1,2}\b|complete series|\(S\d""").containsMatchIn(raw) ||
            (raw.contains("Series", true) && !raw.contains("Web Series", true)) ||
            raw.contains("TV Show", true)
    }

    fun searchQuality(text: String): SearchQuality? = when {
        Regex("""2160p|4k|uhd""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SearchQuality.FourK
        Regex("""1080p""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SearchQuality.HD
        Regex("""720p|480p""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SearchQuality.SD
        else -> null
    }

    fun toSearchResponse(
        api: MainAPI,
        site: Site,
        title: String,
        url: String,
        poster: String?,
        qualityText: String?
    ): SearchResponse? {
        if (title.isBlank() || url.isBlank()) return null
        val abs = if (url.startsWith("http")) url else "${site.base}${if (url.startsWith("/")) "" else "/"}$url"
        val name = cleanTitle(title)
        if (name.isBlank()) return null
        val quality = qualityText?.let { searchQuality(it) }
        return if (looksLikeSeries(title)) {
            api.newTvSeriesSearchResponse(name, abs, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            api.newMovieSearchResponse(name, abs, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    fun parseCards(api: MainAPI, site: Site, html: String): List<SearchResponse> {
        val doc: Document = try {
            Jsoup.parse(html)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<SearchResponse>()
        for (a: Element in doc.select("a[itemprop=itemListElement]")) {
            val href = a.attr("href").trim()
            val img = a.selectFirst("img")
            val title = a.selectFirst(".poster-title")?.text()?.trim()
                ?: img?.attr("alt")?.trim().orEmpty()
            val poster = img?.attr("src")?.takeIf { it.startsWith("http") }
                ?: img?.attr("data-src")?.takeIf { it.startsWith("http") }
            val qualityText = a.selectFirst(".poster-quality")?.text()?.trim()
            toSearchResponse(api, site, title, href, poster?.let(::posterFromUrl), qualityText)?.let(out::add)
        }
        return out.distinctBy { it.url }
    }

    suspend fun catalogPage(api: MainAPI, site: Site, path: String, page: Int): List<SearchResponse>? {
        val url = if (path.isBlank()) {
            if (page > 1) "${site.base}/page/$page/" else "${site.base}/"
        } else {
            if (page > 1) "${site.base}/$path/page/$page/" else "${site.base}/$path/"
        }
        val html = VegaNet.retry(attempts = 2) {
            try {
                val res = app.get(url, headers = VegaNet.browserHeaders("${site.base}/"), timeout = 20L)
                if (res.code == 200) res.text else null
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return parseCards(api, site, html)
    }

    // the typesense proxy throws 5xx under load, a few retries keep it usable
    suspend fun tsSearch(api: MainAPI, site: Site, query: String, page: Int): List<SearchResponse> {
        val url = "${site.base}/ts-search.php?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
        val text = VegaNet.retry(attempts = 3, gapMs = 800L) {
            try {
                val res = app.get(
                    url,
                    headers = VegaNet.browserHeaders("${site.base}/search.html?q=$query"),
                    timeout = 15L
                )
                if (res.code == 200) res.text else null
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()
        val hits = try {
            JSONObject(text).optJSONArray("hits") ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until hits.length()) {
            val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
            val title = d.optString("post_title")
            val permalink = d.optString("permalink")
            val poster = d.optString("post_thumbnail").takeIf { it.startsWith("http") }
            val qualityText = d.optJSONArray("category")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }.firstOrNull { searchQuality(it) != null }
            }
            toSearchResponse(api, site, title, permalink, poster?.let(::posterFromUrl), qualityText)?.let(out::add)
        }
        return out
    }

    class QualityGroup(
        val label: String,
        val links: MutableList<Pair<String, String>> = mutableListOf()
    )

    class SeasonBlock(
        val title: String,
        val number: Int,
        val groups: MutableList<QualityGroup> = mutableListOf()
    )

    class Detail(
        val title: String,
        val poster: String?,
        val year: Int?,
        val plot: String,
        val imdbId: String?,
        val imdbScore: Double?,
        val infoTags: List<String>,
        val isSeries: Boolean,
        val movieGroups: List<QualityGroup>,
        val seasons: List<SeasonBlock>
    )

    private fun htmlUnescape(s: String): String {
        return s.replace("&#038;", "&").replace("&amp;", "&")
            .replace("&#8211;", "-").replace("&#8217;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
    }

    // the og:image proxy host does not resolve on most networks, the u= param carries the real poster
    private fun posterFromUrl(raw: String): String? {
        val unescaped = htmlUnescape(raw)
        if (!unescaped.startsWith("http")) return null
        Regex("""[?&]u=([A-Za-z0-9+/=]+)""").find(unescaped)?.groupValues?.get(1)?.let { enc ->
            VegaNet.decodeParam(enc)?.let { return it }
        }
        return unescaped.takeUnless { Regex("""(?i)^https?://img\.""").containsMatchIn(it) }
    }

    private fun posterFrom(doc: Document): String? {
        val og = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: return null
        return posterFromUrl(htmlUnescape(og))
    }

    fun parseDetail(html: String, url: String): Detail? {
        val doc: Document = try {
            Jsoup.parse(html)
        } catch (_: Exception) {
            return null
        }
        val content = doc.selectFirst(".entry-content, .post-content, article") ?: doc

        val h3 = doc.select("h3").map { it.text().replace(Regex("""\s+"""), " ").trim() }
            .firstOrNull { it.contains("Download", true) }
        val rawTitle = h3 ?: doc.title().substringBefore("~").substringBefore("–").trim()
        val title = cleanTitle(rawTitle.ifBlank { doc.title() })
        if (title.isBlank()) return null

        val poster = posterFrom(doc)
        val imdbId = Regex("""imdb\.com/title/(tt\d+)""").find(html)?.groupValues?.get(1)

        val year = Regex("""\((19\d{2}|20\d{2})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()

        val plot = content.select("h3, h4")
            .firstOrNull { Regex("""(?i)synopsis|plot""").containsMatchIn(it.text()) }
            ?.nextElementSibling()?.text()?.trim().orEmpty()

        val imdbScore = Regex("""IMDb\s*(?:Rating)?\s*[:\-]?\s*(\d(?:\.\d)?)""").find(html)
            ?.groupValues?.get(1)?.toDoubleOrNull()

        val tags = mutableListOf<String>()
        val infoHead = content.select("h3, h4")
            .firstOrNull { Regex("""(?i)movie info|series info""").containsMatchIn(it.text()) }
        if (infoHead != null) {
            var sib: Element? = infoHead.nextElementSibling()
            while (sib != null && !Regex("""(?i)h[2-4]|synopsis""").containsMatchIn("<" + sib.tagName() + ">")) {
                val text = sib.text().replace(Regex("""\s+"""), " ").trim()
                for (field in listOf("Language", "Quality", "Format", "Subtitle", "Genre")) {
                    Regex("""(?i)$field\s*:?\s*([^|]{1,40})""").find(text)?.groupValues?.get(1)
                        ?.trim()?.takeIf { it.isNotBlank() && it.length < 40 }?.let { tags.add(it) }
                }
                if (tags.size > 6) break
                sib = sib.nextElementSibling()
            }
        }

        val seasons = mutableListOf<SeasonBlock>()
        val movieGroups = mutableListOf<QualityGroup>()

        // a season headline followed by quality headlines whose paragraphs carry the server buttons
        val seasonHeads = content.select("h1, h2").filter { el ->
            Regex("""(?i)season\s*\d+|part\s*\d+""").containsMatchIn(el.text())
        }
        if (seasonHeads.isNotEmpty()) {
            for (head in seasonHeads) {
                val num = Regex("""(?i)season\s*(\d+)""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)part\s*(\d+)""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                val season = SeasonBlock(head.text().replace(Regex("""\s+"""), " ").trim(), num)
                var sib: Element? = head.nextElementSibling()
                while (sib != null) {
                    val tag = sib.tagName()
                    if (tag == "h1" || tag == "h2") break
                    if (tag == "h3" || tag == "h4" || tag == "h5") {
                        val label = sib.text().replace(Regex("""\s+"""), " ").trim()
                        if (label.isBlank() || Regex("""(?i)info|synopsis|screenshot|comment""").containsMatchIn(label)) {
                            sib = sib.nextElementSibling()
                            continue
                        }
                        if (!Regex("""\b(480p|720p|1080p|2160p|4K|60FPS)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
                            sib = sib.nextElementSibling()
                            continue
                        }
                        val group = QualityGroup(label)
                        var p: Element? = sib.nextElementSibling()
                        while (p != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(p.tagName())) {
                            collectButtons(p, group)
                            p = p.nextElementSibling()
                        }
                        if (group.links.isNotEmpty()) season.groups.add(group)
                    }
                    sib = sib.nextElementSibling()
                }
                if (season.groups.isNotEmpty()) seasons.add(season)
            }
        }

        if (seasons.isEmpty()) {
            // an h5 quality line followed by a paragraph with the download button
            for (head in content.select("h3, h4, h5")) {
                val label = head.text().replace(Regex("""\s+"""), " ").trim()
                if (label.isBlank()) continue
                if (Regex("""(?i)info|synopsis|screenshot|comment|download\s+.*\b(?:movie|series)\b.*(?:480p|720p|1080p)\s*(?:,|&|and)""").containsMatchIn(label)) continue
                val hasQuality = Regex("""\b(480p|720p|1080p|2160p|4K|60FPS)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)
                if (!hasQuality) continue
                val group = QualityGroup(label)
                var sib: Element? = head.nextElementSibling()
                while (sib != null && !listOf("h1", "h2", "h3", "h4", "h5").contains(sib.tagName())) {
                    collectButtons(sib, group)
                    sib = sib.nextElementSibling()
                }
                if (group.links.isNotEmpty()) movieGroups.add(group)
            }
        }

        val isSeries = seasons.isNotEmpty() ||
            (movieGroups.isEmpty() && looksLikeSeries(title)) ||
            looksLikeSeries(rawTitle)

        return Detail(
            title = title,
            poster = poster,
            year = year,
            plot = plot,
            imdbId = imdbId,
            imdbScore = imdbScore,
            infoTags = tags.distinct().take(5),
            isSeries = isSeries,
            movieGroups = movieGroups,
            seasons = seasons
        )
    }

    private val buttonJunk = Regex(
        """(?i)vglist|telegram|t\.me|imdb|google\.com/search|whatsapp|share|facebook|twitter|pinterest|report"""
    )

    private fun collectButtons(scope: Element, group: QualityGroup) {
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.startsWith("http")) continue
            if (buttonJunk.containsMatchIn(href)) continue
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank()) continue
            if (Regex("""(?i)batch|zip\b|\.rar|\.7z""").containsMatchIn(label)) continue
            if (VegaSources.isServerLink(href)) {
                group.links.add(label to href)
            }
        }
    }
}
