package com.laddu100

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import com.raghav.donation.DonationManager

class AnimeTHProvider : MainAPI() {

    override var mainUrl = "https://anime-th.com"
    override var name = "AnimeTH"
    override var lang = "th"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    private val TAG = "AnimeTH"

    private val mapper = ObjectMapper().registerKotlinModule()

    companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        const val STREAM_BASE = "https://streaming.tonytonychopper.com"
        const val FILE_BASE = "https://anime.tonytonychopper.net"

        private const val MARKER_DUB = "dub"
        private const val MARKER_SUB = "sub"

        private val DUB_WORDS = listOf("พากย์ไทย", "เสียงไทย")
        private val SUB_WORDS = listOf("ซับไทย", "บรรยายไทย")
        private val NAME_SUFFIXES = DUB_WORDS + SUB_WORDS
        private val CATEGORY_LANG = mapOf(
            "พากย์ไทย" to MARKER_DUB,
            "ซับไทย" to MARKER_SUB,
            "อนิเมะจีนซับไทย" to MARKER_SUB,
        )

        private const val SECTION_LATEST = "อนิเมะอัพเดต"
        private const val SECTION_REVIEWED = "อนิเมะรีวิวเยอะสุด"

        private const val MIN_COUNTERPART_SCORE = 0.55

        private val EP_ID_REGEX = Regex("""watch/([A-Za-z0-9]+)(?:\.html)?/?(?:[?#].*)?$""")

        private val EP_NUM_REGEX = Regex("""ตอนที่[\s_]*(\d+)""")
        private val FILE_ID_REGEX = Regex("""anime\.tonytonychopper\.net/v2/([A-Za-z0-9]+)""")
        private val RX_PLAYBACK_SID = Regex("""playback/v/([A-Za-z0-9]+)/""")

        private val CLEAN_WORDS = listOf(
            "(Thai)", "อัพเดตตอนล่าสุด", "อัพเดตล่าสุด", "อัพเดตทุกตอน", "รวมทุกภาค", "ครบทุกตอน"
        )

        private val RX_THAI_WORD = Regex("""thai""", RegexOption.IGNORE_CASE)
        private val RX_GROUPS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
        private val RX_ORDINAL_SEASON = Regex("""(\d+)(st|nd|rd|th)\s+season""", RegexOption.IGNORE_CASE)
        private val RX_SEASON_EN = Regex("""season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val RX_SEASON_TH = Regex("""ภาค\s*(\d+)""")
        private val RX_SEASON_ALT_TH = Regex("""ซีซั่น\s*(\d+)(\s*-\s*\d+)?""")
        private val RX_YEAR_TH = Regex("""ปี\s*(\d+)(\s*-\s*\d+)?""")
        private val RX_PART_EN = Regex("""part\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val RX_DASH_BANG = Regex("""[-!]""")
        private val RX_SEASON_TOKEN = Regex("""(?:^|\s)([sp]\d+)(?:$|\s)""")

        fun similarity(a: String, b: String): Double {
            val ta = a.split(" ").filter { it.isNotBlank() }.toSet()
            val tb = b.split(" ").filter { it.isNotBlank() }.toSet()
            if (ta.isEmpty() || tb.isEmpty()) return 0.0
            return ta.intersect(tb).size.toDouble() / ta.union(tb).size
        }

        fun seasonToken(cleaned: String): String? {
            return RX_SEASON_TOKEN.find(cleaned)?.groupValues?.get(1)
        }

        fun cleanTitle(raw: String): String {
            var t = raw
            for (w in NAME_SUFFIXES) t = t.replace(w, " ")
            for (w in CLEAN_WORDS) t = t.replace(w, " ")
            t = RX_THAI_WORD.replace(t, " ")
            t = RX_GROUPS.replace(t, " ")
            t = RX_ORDINAL_SEASON.replace(t, "s$1")
            t = RX_SEASON_EN.replace(t, "s$1")
            t = RX_SEASON_TH.replace(t, "s$1")
            t = RX_SEASON_ALT_TH.replace(t, "s$1")
            t = RX_YEAR_TH.replace(t, "p$1")
            t = RX_PART_EN.replace(t, "p$1")
            t = RX_DASH_BANG.replace(t, " ")
            return t.split(" ").filter { it.isNotBlank() }.joinToString(" ").lowercase()
        }
    }

    override val mainPage = mainPageOf(
        "home_latest" to "Latest Updates",
        "top" to "Popular",
        "cat_ซับไทย" to "Thai Sub",
        "cat_พากย์ไทย" to "Thai Dub",
        "cat_อนิเมะจีนซับไทย" to "Chinese Anime (Sub)",
        "home_reviewed" to "Most Reviewed",
        "genre_action" to "Action",
        "genre_adventure" to "Adventure",
        "genre_comedy" to "Comedy",
        "genre_drama" to "Drama",
        "genre_fantasy" to "Fantasy",
        "genre_isekai" to "Isekai",
        "genre_mecha" to "Mecha",
        "genre_romance" to "Romance",
        "genre_horror" to "Horror",
        "genre_sports" to "Sports",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val items = when {
            request.data == "home_latest" || request.data == "home_reviewed" ->
                homeSection(request.data)
            request.data == "top" ->
                scoreTop()
            request.data.startsWith("cat_") ->
                gridCards(
                    app.get(
                        "$mainUrl/category/${request.data.removePrefix("cat_")}/",
                        headers = siteHeaders()
                    ).document
                )
            request.data.startsWith("genre_") ->
                gridCards(
                    app.get(
                        "$mainUrl/genre/${request.data.removePrefix("genre_")}/",
                        headers = siteHeaders()
                    ).document
                )
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun homeSection(which: String): List<SearchResponse> {
        val wanted = if (which == "home_latest") SECTION_LATEST else SECTION_REVIEWED
        val doc = app.get(mainUrl, headers = siteHeaders()).document
        for (section in doc.select("section.mb-10")) {
            if (section.selectFirst("h2")?.text()?.trim() != wanted) continue
            return (section.select("a.block") + section.select("a.flex"))
                .mapNotNull { cardFromAnchor(it) }
        }
        return emptyList()
    }

    private suspend fun scoreTop(): List<SearchResponse> {
        val doc = app.get("$mainUrl/scoretop/", headers = siteHeaders()).document
        return doc.select("div.space-y-2 > a[href*=/anime/]").mapNotNull { cardFromAnchor(it) }
    }

    private fun gridCards(doc: Document): List<SearchResponse> {
        return doc.select("a.block[href*=/anime/]").mapNotNull { cardFromAnchor(it) }
    }

    private fun cardFromAnchor(a: Element): SearchResponse? {
        val href = a.attr("href")
        if (!href.contains("/anime/")) return null
        val slug = slugFromHref(href)
        if (slug.isBlank()) return null
        val img = a.selectFirst("img")
        val title = a.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val poster = img?.attr("data-src")?.takeIf { it.startsWith("http") }
            ?: img?.attr("src")?.takeIf { it.startsWith("http") }
        val category = a.selectFirst(".cate-ribbon")?.text()?.trim()
        return searchResponse(title, slug, poster, category)
    }

    private fun siteHeaders(referer: String? = null, xhr: Boolean = false): Map<String, String> {
        val h = linkedMapOf("User-Agent" to UA, "Accept" to "*/*")
        if (xhr) h["X-Requested-With"] = "XMLHttpRequest"
        if (referer != null) h["Referer"] = referer
        return h
    }

    private suspend fun fetchPageRetry(url: String, referer: String? = null): NiceResponse? {
        repeat(2) { attempt ->
            val res = runCatching { app.get(url, headers = siteHeaders(referer)) }.getOrNull()
            if (res != null && res.code < 500) return res
            if (attempt == 0) delay(1200)
        }
        return null
    }

    private fun searchResponse(title: String, slug: String, poster: String?, category: String?): SearchResponse {
        val lang = detectLang(title, slug) ?: category?.let { CATEGORY_LANG[it] }
        return newAnimeSearchResponse(title, "$mainUrl/anime/$slug/", TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(
                dubExist = lang == MARKER_DUB,
                subExist = lang != MARKER_DUB,
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val bySlug = LinkedHashMap<String, SearchResponse>()

        runCatching { quickAjax(q) }.onSuccess { list ->
            list.forEach { resp -> bySlug[decodeSlug(resp.url)] = resp }
        }
        runCatching { queryCards(q) }.onSuccess { list ->
            list.forEach { resp -> bySlug.putIfAbsent(decodeSlug(resp.url), resp) }
        }

        val needle = q.lowercase()
        return bySlug.values.sortedByDescending { resp ->
            val title = resp.name.lowercase()
            when {
                title == needle -> 3
                title.startsWith(needle) -> 2
                title.contains(needle) -> 1
                else -> 0
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return runCatching { quickAjax(query.trim()) }.getOrDefault(emptyList())
    }

    private suspend fun quickAjax(q: String): List<SearchResponse> {
        return quickAjaxNodes(q).mapNotNull { r ->
            val slug = r.path("slug").asText("")
            val title = r.path("title").asText("").trim()
            if (slug.isBlank() || title.isEmpty()) return@mapNotNull null
            val cover = r.path("cover").asText("")
            searchResponse(
                title,
                slug,
                cover.takeIf { it.startsWith("http") } ?: cover.takeIf { it.isNotEmpty() }?.let { "$mainUrl/$it" },
                r.path("category").asText(""),
            )
        }
    }

    private suspend fun quickAjaxNodes(q: String): List<JsonNode> {
        val body = app.get(
            "$mainUrl/vendor/search-ajax.php?q=" + URLEncoder.encode(q, "UTF-8"),
            headers = siteHeaders("$mainUrl/", xhr = true)
        ).text
        val results = mapper.readTree(body).path("results")
        return (0 until results.size()).mapNotNull { results.get(it) }
    }

    private suspend fun queryCards(q: String): List<SearchResponse> {
        val html = app.post(
            "$mainUrl/query/",
            data = mapOf("query" to q),
            headers = siteHeaders("$mainUrl/search/", xhr = true)
        ).text
        return gridCards(org.jsoup.Jsoup.parse(html))
    }

    private class EpItem(val epId: String, val name: String, val num: Int)

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugFromHref(url)
        if (slug.isBlank()) return null

        val res = fetchPageRetry("$mainUrl/anime/$slug/")
        if (res == null || res.code >= 400) {
            Log.d(TAG, "detail page unavailable: $slug")
            return null
        }
        val doc = res.document
        val titleTag = doc.selectFirst("title")?.text()?.trim() ?: return null
        if (titleTag.contains("404")) return null

        val ld = parseJsonLd(doc.selectFirst("script[type=application/ld+json]"))
        val title = ld?.path("name")?.asText("")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: titleTag.removeSuffix("- Anime-TH").trim()
        val poster = ld?.path("image")?.asText("")?.takeIf { it.isNotEmpty() }
        val banner = doc.selectFirst("img[src*=/banner/]")?.attr("src")
        val plot = ld?.path("description")?.asText("")?.takeIf { it.isNotEmpty() }

        val ratingValue = ld?.path("aggregateRating")?.path("ratingValue")?.asDouble(0.0) ?: 0.0
        val year = ld?.path("datePublished")?.asText("")
            ?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
        val genres = ld?.path("genre")?.let { node ->
            (0 until node.size()).mapNotNull { node.get(it)?.asText("") }.filter { it.isNotEmpty() }
        } ?: emptyList()
        val studios = ld?.path("productionCompany")?.let { node ->
            (0 until node.size()).mapNotNull { node.get(it)?.path("name")?.asText("") }.filter { it.isNotEmpty() }
        } ?: emptyList()

        val showStatus = when {
            sidebarValue(doc, "สถานะ").contains("กำลังฉาย") -> ShowStatus.Ongoing
            sidebarValue(doc, "สถานะ").contains("จบแล้ว") -> ShowStatus.Completed
            else -> null
        }

        val epItems = doc.select("a.ep-item").mapNotNull { el -> epItemFromAnchor(el) }
        if (epItems.isEmpty()) {
            Log.d(TAG, "no episodes for $slug")
            return null
        }

        val ownLang = detectLang(titleTag, slug)
            ?: epItems.firstNotNullOfOrNull { langFromName(it.name) }

        val counterpart = findCounterpart(title, slug, ownLang)
        val counterpartLang = counterpart?.let { loadCounterpartLang(it) }
        val counterpartItems = if (counterpart != null && counterpartLang != null && counterpartLang != ownLang) {
            loadCounterpartEpisodes(counterpart)
        } else {
            emptyList()
        }

        val isMovie = ld?.path("@type")?.asText("") == "Movie" ||
            sidebarValue(doc, "รูปแบบ").equals("Movie", ignoreCase = true) ||
            (epItems.size == 1 && !epItems[0].name.contains("ตอนที่"))

        val ownEpisodes = buildEpisodes(epItems, ownLang ?: MARKER_SUB, isMovie, title)
        val mergedEpisodes = buildEpisodes(counterpartItems, counterpartLang ?: MARKER_SUB, isMovie, title)

        val subEps = if (ownLang == MARKER_DUB) mergedEpisodes else ownEpisodes
        val dubEps = if (ownLang == MARKER_DUB) ownEpisodes else mergedEpisodes

        val tvType = when {
            !isMovie -> TvType.Anime
            subEps.isNotEmpty() && dubEps.isNotEmpty() -> TvType.Anime
            else -> TvType.AnimeMovie
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$slug/", tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = banner
            this.plot = plot
            this.tags = (genres + listOfNotNull(studios.firstOrNull())).distinct()
            this.year = year
            if (ratingValue > 0.0) this.score = Score.from10("%.1f".format(ratingValue))
            if (!isMovie && showStatus != null) this.showStatus = showStatus
            if (subEps.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEps)
            if (dubEps.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEps)
        }
    }

    private fun parseJsonLd(el: Element?): JsonNode? {
        val raw = el?.data() ?: return null
        return runCatching { mapper.readTree(raw) }.getOrNull()
    }

    private fun sidebarValue(doc: Document, label: String): String {
        for (n in doc.select("div.text-al-text.text-xs")) {
            if (n.text().trim() == label) {
                return n.nextElementSibling()?.text()?.trim() ?: ""
            }
        }
        return ""
    }

    private fun epItemFromAnchor(el: Element): EpItem? {
        val epId = EP_ID_REGEX.find(el.attr("href"))?.groupValues?.get(1) ?: return null
        val rawName = el.text().trim()
        val num = EP_NUM_REGEX.find(rawName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return EpItem(epId, rawName, num)
    }

    private fun buildEpisodes(items: List<EpItem>, lang: String, isMovie: Boolean, title: String): List<Episode> {
        return items.map { item ->
            val data = mapper.createObjectNode().apply {
                put("epId", item.epId)
                put("lang", lang)
            }
            newEpisode(mapper.writeValueAsString(data)) {
                this.episode = item.num
                this.name = if (isMovie) title else stripMarkers(item.name)
            }
        }
    }

    private suspend fun findCounterpart(title: String, slug: String, ownLang: String?): String? {
        if (ownLang == null) return null
        val base = cleanTitle(title)
        val baseToken = seasonToken(base)
        val words = base.split(" ").filter { it.isNotBlank() && it.all { c -> c in 'a'..'z' } }
        val q = (if (words.size >= 2) words.take(4) else base.split(" ").take(3)).joinToString(" ")
        if (q.isBlank()) return null

        val best = arrayOfNulls<String>(1)
        var bestScore = 0.0

        runCatching { quickAjaxNodes(q) }.getOrNull()?.forEach { r ->
            val candSlug = r.path("slug").asText("")
            val candTitle = r.path("title").asText("").trim()
            if (candSlug.isBlank() || candTitle.isEmpty() || candSlug == slug) return@forEach
            val lang = detectLang(candTitle, candSlug)
                ?: CATEGORY_LANG[r.path("category").asText("")]
                ?: return@forEach
            if (lang == ownLang) return@forEach
            val candClean = cleanTitle(candTitle)
            val candToken = seasonToken(candClean)
            if (baseToken != null && candToken != null && baseToken != candToken) return@forEach
            val score = similarity(base, candClean)
            if (score > bestScore) {
                bestScore = score
                best[0] = candSlug
            }
        }

        runCatching { queryCards(q) }.getOrNull()?.forEach { resp ->
            val candSlug = decodeSlug(resp.url)
            if (candSlug == slug) return@forEach
            val lang = detectLang(resp.name, candSlug) ?: return@forEach
            if (lang == ownLang) return@forEach
            val candClean = cleanTitle(resp.name)
            val candToken = seasonToken(candClean)
            if (baseToken != null && candToken != null && baseToken != candToken) return@forEach
            val score = similarity(base, candClean)
            if (score > bestScore) {
                bestScore = score
                best[0] = candSlug
            }
        }

        return best[0]?.takeIf { bestScore >= MIN_COUNTERPART_SCORE }
    }

    private suspend fun loadCounterpartLang(slug: String): String? {
        return runCatching {
            val res = fetchPageRetry("$mainUrl/anime/$slug/") ?: return null
            if (res.code >= 400) return null
            detectLang(res.document.selectFirst("title")?.text()?.trim() ?: "", slug)
        }.getOrNull()
    }

    private suspend fun loadCounterpartEpisodes(slug: String): List<EpItem> {
        val res = fetchPageRetry("$mainUrl/anime/$slug/") ?: return emptyList()
        if (res.code >= 400) return emptyList()
        return runCatching {
            res.document.select("a.ep-item").mapNotNull { el -> epItemFromAnchor(el) }
        }.getOrDefault(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = runCatching { mapper.readTree(data) }.getOrNull() ?: return false
        val epId = payload.path("epId").asText("")
        if (epId.isBlank()) return false
        val label = if (payload.path("lang").asText(MARKER_SUB) == MARKER_DUB) "DUB" else "SUB"

        val fileId = resolveFileId(epId) ?: return false
        val masterUrl = "$FILE_BASE/file2/$fileId/"

        var master = fetchMaster(fileId)
        if (!master.startsWith("#EXTM3U")) {
            delay(1500)
            master = fetchMaster(fileId)
        }
        if (!master.startsWith("#EXTM3U")) {
            Log.d(TAG, "master playlist unavailable for $epId")
            return false
        }

        val linkName = "AnimeTH $label"
        val playHeaders = mapOf(
            "User-Agent" to UA,
            "Referer" to "$FILE_BASE/v2/$fileId",
            "Accept" to "*/*",
        )
        return try {
            val links = M3u8Helper.generateM3u8(linkName, masterUrl, "$FILE_BASE/", headers = playHeaders)
            if (links.isEmpty()) throw IllegalStateException("empty master")
            links.forEach(callback)
            true
        } catch (e: Exception) {
            Log.d(TAG, "m3u8 parse failed for $epId: ${e.message}")
            callback.invoke(
                newExtractorLink(
                    linkName,
                    "$label HLS (Adaptive)",
                    masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$FILE_BASE/v2/$fileId"
                    this.headers = playHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        }
    }

    private suspend fun resolveFileId(epId: String): String? {
        return runCatching {
            val baseJs = app.get(
                "$mainUrl/base/$epId/",
                headers = siteHeaders("$mainUrl/watch/$epId.html")
            ).text
            val sid = RX_PLAYBACK_SID.find(baseJs)?.groupValues?.get(1)
                ?: return@runCatching null
            delay(400)
            val page = app.get(
                "$STREAM_BASE/playback/v/$sid/",
                headers = siteHeaders("$mainUrl/")
            ).text
            FILE_ID_REGEX.find(page)?.groupValues?.get(1)
        }.onFailure { Log.d(TAG, "resolve failed for $epId: ${it.message}") }.getOrNull()
    }

    private suspend fun fetchMaster(fileId: String): String {
        return runCatching {
            app.get(
                "$FILE_BASE/file2/$fileId/",
                headers = siteHeaders("$FILE_BASE/v2/$fileId"),
                timeout = 20_000L
            ).text
        }.getOrDefault("")
    }

    private fun decodeSlug(url: String): String {
        return runCatching {
            URLDecoder.decode(url.removeSuffix("/").substringAfterLast("/"), "UTF-8")
        }.getOrDefault(url)
    }

    private fun slugFromHref(href: String): String {
        val clean = href.substringBefore("?").trimEnd('/')
        val idx = clean.lastIndexOf("/anime/")
        if (idx < 0) return ""
        return clean.substring(idx + "/anime/".length)
    }

    private fun detectLang(title: String, slug: String): String? {
        if (DUB_WORDS.any { title.contains(it) }) return MARKER_DUB
        if (SUB_WORDS.any { title.contains(it) }) return MARKER_SUB
        val text = title + " " + runCatching { URLDecoder.decode(slug, "UTF-8") }.getOrDefault(slug)
            .replace("-", " ")
        if (DUB_WORDS.any { text.contains(it) }) return MARKER_DUB
        if (SUB_WORDS.any { text.contains(it) }) return MARKER_SUB
        if (text.contains("sub", ignoreCase = true)) return MARKER_SUB
        return null
    }

    private fun langFromName(name: String): String? {
        if (DUB_WORDS.any { name.contains(it) }) return MARKER_DUB
        if (SUB_WORDS.any { name.contains(it) }) return MARKER_SUB
        return null
    }

    private fun stripMarkers(name: String): String {
        var t = name.trim()
        while (true) {
            val suffix = NAME_SUFFIXES.firstOrNull { t.endsWith(it) } ?: break
            t = t.removeSuffix(suffix).trim()
        }
        return t
    }
}
