package com.laddu100.rareanimes

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.UUID
import com.raghav.donation.DonationManager

@JsonIgnoreProperties(ignoreUnknown = true)
data class RAIVariant(
    @JsonProperty("n") val n: String,
    @JsonProperty("u") val u: String,
    @JsonProperty("k") val k: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RAIEpisodeData(
    @JsonProperty("v") val v: List<RAIVariant> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JuicyDataInner(
    @JsonProperty("token") val token: String? = null,
    @JsonProperty("routes") val routes: JuicyRoutes? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JuicyRoutes(
    @JsonProperty("links") val links: String? = null,
    @JsonProperty("ping") val ping: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JuicyDataWrapper(
    @JsonProperty("data") val data: JuicyDataInner? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgonQuality(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("link") val link: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArgonLinks(
    @JsonProperty("qualities") val qualities: List<ArgonQuality>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamBetaLink(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileAccountData(
    @JsonProperty("token") val token: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileAccount(
    @JsonProperty("data") val data: GoFileAccountData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileChild(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("link") val link: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileContentData(
    @JsonProperty("children") val children: Map<String, GoFileChild>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GoFileContent(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("data") val data: GoFileContentData? = null
)

class RareAnimesProvider : MainAPI() {
    override var mainUrl = "https://www.rareanimes.mov"
    override var name = "Rare Toons India"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Cartoon, TvType.TvSeries, TvType.Movie)

    private val SOURCE = "Rare Toons India"
    private val EPISODE_HEADER = Regex("""^(?:Episode|EP)\s*[.\-]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val SEASON_MARKER = Regex("""^\s*Seasons?\s*[-\u2013:.]?\s*(\d{1,2})\s*$""", RegexOption.IGNORE_CASE)
    private val GOFILE_WT_SECRET = "12af056dacea0b"

    override val mainPage = mainPageOf(
        "hindi-dub" to "Hindi Dub",
        "cartoon-network" to "Cartoon Network",
        "disney-xd" to "Disney XD",
        "hungama" to "Hungama",
        "marvel-hq" to "Marvel HQ",
        "pokemon" to "Pokemon"
    )

    private data class PageEntry(val title: String, val url: String, val poster: String?)

    private fun parseListings(html: String): List<PageEntry> {
        val doc = Jsoup.parse(html)
        return doc.select("article").mapNotNull { art ->
            val link = art.selectFirst("h2.entry-title a, .entry-title a")?.attr("abs:href")
            if (link.isNullOrBlank() || !link.contains(mainUrl)) return@mapNotNull null
            val title = art.selectFirst("h2.entry-title, .entry-title")?.text()?.trim()
                ?: return@mapNotNull null
            val poster = art.selectFirst("img")?.let { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("http") -> src
                    else -> null
                }
            }
            PageEntry(title, link, poster)
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        return try {
            val url = if (page <= 1) "$mainUrl/hindi/category/${request.data}/"
            else "$mainUrl/hindi/category/${request.data}/page/$page/"
            val response = raiGet(url)
            val entries = parseListings(response.text)
            val items = entries.map {
                newMovieSearchResponse(it.title, it.url, TvType.Anime) {
                    this.posterUrl = it.poster
                }
            }
            newHomePageResponse(request.name, items, hasNext = entries.isNotEmpty())
        } catch (_: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val results = mutableListOf<PageEntry>()
            for (pageNum in 1..3) {
                val url = if (pageNum == 1) "$mainUrl/?s=$encoded"
                else "$mainUrl/page/$pageNum/?s=$encoded"
                val response = raiGet(url)
                val entries = parseListings(response.text)
                if (entries.isEmpty()) break
                results.addAll(entries)
            }
            results.distinctBy { it.url }.map {
                newMovieSearchResponse(it.title, it.url, TvType.Anime) {
                    this.posterUrl = it.poster
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class SourceRef(val label: String, val url: String, val kind: String, val contextKey: String)

    private data class ArchiveEpisode(
        val name: String,
        val variants: List<RAIVariant>
    )

    private data class ArchiveResult(
        val dubKey: String,
        val sourceName: String,
        val episodes: List<ArchiveEpisode>
    )

    private data class DirectEpisode(
        val season: Int,
        val epNum: Int,
        val name: String,
        val variants: MutableList<RAIVariant>
    )

    private data class DirectParse(
        val episodes: List<DirectEpisode>,
        val orphans: List<RAIVariant>
    )

    private fun dubKeyOf(text: String): String {
        val t = " $text ".lowercase()
        val isCn = t.contains("cartoon network") || t.contains("caroon network") ||
            t.contains("cn dub") || Regex("""[\s(\[-]cn[)\],\s–-]""").containsMatchIn(t)
        val isXd = t.contains("hungama") || t.contains("disney xd") || t.contains("marvel hq")
        if (isCn && isXd) return ""
        if (isCn) return "cn"
        if (isXd) return "xd"
        val langs = listOf(
            "tamil" to "ta", "telugu" to "te", "english" to "en",
            "bengali" to "be", "hindi" to "hi"
        ).filter { t.contains(it.first) }.map { it.second }.distinct()
        return when (langs.size) {
            1 -> langs.first()
            0 -> ""
            else -> "multi"
        }
    }

    private fun dubLabel(key: String): String {
        return when (key) {
            "cn" -> "Hindi (Cartoon Network)"
            "xd" -> "Hindi (Hungama XD)"
            "hi" -> "Hindi"
            "ta" -> "Tamil"
            "te" -> "Telugu"
            "en" -> "English"
            "be" -> "Bengali"
            "multi" -> "Multi Audio"
            else -> "Main"
        }
    }

    private fun extractSources(doc: Document): List<SourceRef> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val sources = mutableListOf<SourceRef>()
        var context = ""
        val all: List<Element> = content.allElements
        for (el in all) {
            when (el.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val t = el.text().trim()
                    if (t.isNotBlank()) context = t
                }
                "a" -> {
                    if (isProseRow(el)) continue
                    val href = el.attr("abs:href")
                    val text = el.text().trim()
                    when {
                        href.contains("$STORE_HOST/archives/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "archive", dubKeyOf(context)))
                        href.contains("$CODEDEW_HOST/zipper/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "zipper", dubKeyOf(context)))
                        href.contains("$CODEDEW_HOST/zipcloud/") ->
                            sources.add(SourceRef(text.ifBlank { context }, href, "zipcloud", dubKeyOf(context)))
                    }
                }
            }
        }
        return sources.distinctBy { it.url }
    }

    // bare links inside long paragraphs are seo keyword links, real buttons are always bracket wrapped
    private fun isProseRow(a: Element): Boolean {
        val row = blockRowOf(a)
        val tag = row.tagName().lowercase()
        if (tag == "p" || tag == "li") {
            if (row.text().length <= 80) return false
            val html = row.html()
            val total = Regex("""<a\s""").findAll(html).count()
            val wrapped = Regex("""\[\s*<a\s""").findAll(html).count()
            return total == 0 || wrapped != total
        }
        return false
    }

    private fun blockRowOf(a: Element): Element {
        var cur = a
        while (true) {
            val parent = cur.parent() ?: return cur
            when (parent.tagName().lowercase()) {
                "p", "li", "h1", "h2", "h3", "h4", "h5", "h6" -> return parent
            }
            cur = parent
        }
    }

    private fun archiveSourceName(title: String, fallback: String): String {
        val t = title.lowercase()
        return when {
            t.contains("multiquality") -> "WatchMultiQuality"
            t.contains("hubcloud") -> "HubCloud"
            t.contains("watchnow") -> "WatchNow"
            else -> fallback.ifBlank { "Watch" }
        }
    }

    // linker pages sometimes label every row with the button name instead of the episode
    private fun isSourceLabel(name: String): Boolean {
        return when (name.trim().lowercase()) {
            "multiquality", "watchmultiquality", "quickmulti", "watchnow",
            "hubcloud", "mega", "zip", "download", "watch", "dlbeta" -> true
            else -> false
        }
    }

    private fun parseArchiveEpisodes(html: String): List<Pair<String, String>> {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst("div.entry-content") ?: doc.body() ?: return emptyList()
        return content.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href")
            val text = a.text().trim()
            val isCodedew = href.contains("$CODEDEW_HOST/zipper/") ||
                    href.contains("$CODEDEW_HOST/zipcloud/")
            if (isCodedew && text.isNotBlank()) text to href else null
        }.distinctBy { it.second }
    }

    private fun languageFromText(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("telugu") -> "Telugu"
            t.contains("tamil") -> "Tamil"
            t.contains("bengali") -> "Bengali"
            t.contains("english") || t.contains(" eng") -> "English"
            t.contains("hindi") -> "Hindi"
            else -> ""
        }
    }

    private fun parseDirectEpisodes(
        doc: Document,
        defaultSeason: Int
    ): DirectParse {
        val content = doc.selectFirst("div.entry-content") ?: return DirectParse(emptyList(), emptyList())
        val episodes = mutableListOf<DirectEpisode>()
        val orphans = mutableListOf<RAIVariant>()
        var current: DirectEpisode? = null
        var sectionContext = ""
        var sectionKey = ""
        var sectionSeason: Int? = null
        val usedOrphanNames = mutableSetOf<String>()
        for (el in content.children()) {
            val tag = el.tagName().lowercase()
            if (tag == "hr") continue
            val text = el.text().trim()
            if (text.isBlank()) continue

            val headerMatch = EPISODE_HEADER.find(text)
            val links = el.select(
                "a[href*=codedew.com/zipper/], a[href*=codedew.com/zipcloud/]"
            ).filter { !isProseRow(it) }

            var startedEpisode = false
            if (headerMatch != null) {
                val epNum = headerMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    current = DirectEpisode(sectionSeason ?: defaultSeason, epNum, text, mutableListOf())
                    episodes.add(current)
                    startedEpisode = true
                    if (links.isEmpty()) continue
                }
            }

            if (links.isEmpty()) {
                val marker = SEASON_MARKER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                val isHeading = tag.startsWith("h") && el.select("a").isEmpty()
                if (isHeading || marker != null) {
                    if (marker != null) sectionSeason = marker
                    sectionContext = text
                    sectionKey = dubKeyOf(text)
                    if (!startedEpisode) current = null
                }
                continue
            }

            val langSpan = el.selectFirst("span:not(.ra-serv-txt)")?.text()
            val lang = languageFromText(langSpan ?: text.substringBefore("["))
            for (a in links) {
                val href = a.attr("abs:href")
                if (href.isBlank()) continue
                val label = a.text().trim()
                if (label.isBlank()) continue
                val target = current
                if (target != null) {
                    val variantName = if (lang.isBlank()) label else "$lang $label"
                    target.variants.add(RAIVariant(variantName, href, dubKeyOf(lang.ifBlank { sectionContext })))
                } else {
                    val effectiveLang = if (lang.isBlank()) languageFromText(sectionContext) else lang
                    val base = if (effectiveLang.isBlank()) label else "$effectiveLang $label"
                    val variantName = if (usedOrphanNames.add(base)) base
                    else if (sectionContext.isBlank()) base
                    else "$sectionContext $base"
                    orphans.add(RAIVariant(variantName, href, sectionKey))
                }
            }
        }
        return DirectParse(episodes, orphans)
    }

    private suspend fun loadArchive(
        url: String,
        sourceLabel: String,
        contextKey: String,
        depth: Int
    ): ArchiveResult? {
        if (depth > 2) return null
        return try {
            val response = raiGet(url)
            val html = response.text
            val doc = Jsoup.parse(html)
            val docTitle = doc.title()
            val dubKey = dubKeyOf(docTitle).ifBlank { contextKey }
            val sourceName = archiveSourceName(docTitle, sourceLabel)
            val eps = parseArchiveEpisodes(html)

            when {
                eps.size > 1 -> ArchiveResult(
                    dubKey,
                    sourceName,
                    eps.map { (name, u) -> ArchiveEpisode(name, listOf(RAIVariant(sourceName, u, dubKey))) }
                )
                eps.size == 1 -> {
                    val single = eps.first()
                    val target = try {
                        CodedewResolver.resolveUrl(single.second)
                    } catch (_: Exception) {
                        null
                    }
                    if (target is ResolvedTarget.Archive) {
                        loadArchive(target.url, sourceLabel, dubKey, depth + 1)
                    } else {
                        ArchiveResult(
                            dubKey,
                            sourceName,
                            listOf(ArchiveEpisode(single.first, listOf(RAIVariant(sourceName, single.second, dubKey))))
                        )
                    }
                }
                else -> {
                    val codedew = doc.selectFirst(
                        "div.entry-content a[href*=codedew.com]"
                    )?.attr("abs:href") ?: return null
                    val target = try {
                        CodedewResolver.resolveUrl(codedew)
                    } catch (_: Exception) {
                        null
                    }
                    when (target) {
                        is ResolvedTarget.Archive -> loadArchive(target.url, sourceLabel, dubKey, depth + 1)
                        is ResolvedTarget.Argon ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        is ResolvedTarget.ArgonDownload ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        is ResolvedTarget.HubCloud ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        is ResolvedTarget.PixelDrain ->
                            ArchiveResult(dubKey, sourceName, listOf(ArchiveEpisode("Full", listOf(RAIVariant(sourceName, codedew, dubKey)))))
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEpisodeNumber(name: String): Pair<Int?, Int>? {
        val m = Regex("""S\s*(\d{1,2})\s*E\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(name)
        if (m != null) {
            val s = m.groupValues[1].toIntOrNull() ?: return null
            val e = m.groupValues[2].toIntOrNull() ?: return null
            return s to e
        }
        val sn = HUB_SEASON_NUMBER.find(name)?.groupValues?.get(1)?.toIntOrNull()
        val e2 = Regex("""\bE\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\bEpisode\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(name)
        if (e2 != null) {
            val e = e2.groupValues[1].toIntOrNull() ?: return null
            return sn to e
        }
        return null
    }

    private fun jsonEscape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
    }

    private fun variantsJson(variants: List<RAIVariant>): String {
        return """{"v":[""" + variants.joinToString(",") {
            """{"n":"${jsonEscape(it.n)}","u":"${jsonEscape(it.u)}","k":"${jsonEscape(it.k)}"}"""
        } + """]}"""
    }

    private fun extractMeta(doc: Document): Map<String, String> {
        val content = doc.selectFirst("div.entry-content")?.text() ?: ""
        val map = mutableMapOf<String, String>()
        Regex("""Season\s*No[.:]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["season"] = it.groupValues[1]
        }
        Regex("""Release\s*Year[.:]?\s*(\d{4})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["year"] = it.groupValues[1]
        }
        Regex("""Episodes[.:]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE).find(content)?.let {
            map["episodes"] = it.groupValues[1]
        }
        return map
    }

    private data class HubLink(
        val kind: String,
        val number: Int?,
        val name: String,
        val url: String
    )

    private data class HubEpisode(
        val season: Int,
        val epNum: Int,
        val name: String,
        val variants: MutableList<RAIVariant>
    )

    private data class PageContent(
        val sources: List<SourceRef>,
        val direct: DirectParse,
        val archives: List<ArchiveResult>,
        val defaultSeason: Int,
        val finalUrl: String = "",
        val seasonKnown: Boolean = false
    )

    private val HUB_SEASON_NUMBER =
        Regex("""\bSeasons?\s*[-\u2013:.]?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val HUB_MOVIE_NUMBER =
        Regex("""\bMovie\s*[-\u2013:.]?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val HUB_SELF_SLUG = Regex("""\ball-(?:movies|seasons?|series|episodes?)-""")

    private fun isInternalPostLink(href: String): Boolean {
        if (href.isBlank() || !href.contains(mainUrl)) return false
        if (href.contains("discord") || href.contains("?s=") || href.contains("#")) return false
        return try {
            val uri = android.net.Uri.parse(href)
            val path = uri.path ?: return false
            if (path.isBlank() || path == "/") return false
            if (path.startsWith("/hindi/category/") || path.startsWith("/category/") ||
                path.startsWith("/tag/") || path.startsWith("/author/") ||
                path.startsWith("/page/") || path.startsWith("/home")
            ) return false
            if (HUB_SELF_SLUG.containsMatchIn(path)) return false
            path.length > 8
        } catch (_: Exception) {
            false
        }
    }

    private fun parseHubHeadings(doc: Document): List<HubLink> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val out = mutableListOf<HubLink>()
        val seen = mutableSetOf<String>()
        for (h in content.select("h2, h3, h4")) {
            val candidates = h.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href")
                if (isInternalPostLink(href)) a else null
            }
            if (candidates.isEmpty()) continue
            val link = candidates.firstOrNull { it.text().isNotBlank() } ?: candidates.first()
            val href = link.attr("abs:href")
            if (!seen.add(href)) continue
            val headingText = h.text().trim()
            val text = link.text().trim().ifBlank { headingText }
            if (text.isBlank()) continue
            val seasonNumber = HUB_SEASON_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: HUB_SEASON_NUMBER.find(headingText)?.groupValues?.get(1)?.toIntOrNull()
            val movieNumber = if (seasonNumber == null) {
                HUB_MOVIE_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: HUB_MOVIE_NUMBER.find(headingText)?.groupValues?.get(1)?.toIntOrNull()
            } else null
            val kind = when {
                seasonNumber != null -> "season"
                movieNumber != null -> "movie"
                else -> "extra"
            }
            out.add(HubLink(kind, seasonNumber ?: movieNumber, text, href))
        }
        return out
    }

    private suspend fun parsePageContent(doc: Document, defaultSeason: Int): PageContent {
        val sources = extractSources(doc)
        val direct = parseDirectEpisodes(doc, defaultSeason)
        val archives = mutableListOf<ArchiveResult>()
        for (src in sources.filter { it.kind == "archive" }) {
            loadArchive(src.url, src.label, src.contextKey, 0)?.let { archives.add(it) }
        }
        return PageContent(sources, direct, archives, defaultSeason)
    }

    private fun buildSubPageEpisodes(content: PageContent): List<HubEpisode> {
        val out = LinkedHashMap<String, HubEpisode>()
        val dubKeys = mutableSetOf<String>()
        content.archives.forEach { if (it.dubKey.isNotBlank()) dubKeys.add(it.dubKey) }
        content.direct.episodes.forEach { ep -> ep.variants.forEach { if (it.k.isNotBlank()) dubKeys.add(it.k) } }
        val multiDub = dubKeys.size >= 2

        fun add(season: Int, epNum: Int, name: String, variant: RAIVariant) {
            val key = "$season:$epNum"
            val entry = out.getOrPut(key) { HubEpisode(season, epNum, name, mutableListOf()) }
            if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
        }

        for (ep in content.direct.episodes) {
            val parsed = parseEpisodeNumber(ep.name)
            val season = parsed?.first ?: ep.season
            val epNum = parsed?.second ?: ep.epNum
            for (v in ep.variants) {
                val name = if (multiDub && v.k.isNotBlank()) "${v.n} ${dubLabel(v.k)}" else v.n
                add(season, epNum, ep.name, RAIVariant(name, v.u, v.k))
            }
        }

        for (archive in content.archives) {
            val suffix = if (multiDub && archive.dubKey.isNotBlank()) " ${dubLabel(archive.dubKey)}" else ""
            archive.episodes.forEachIndexed { idx, ep ->
                val parsed = parseEpisodeNumber(ep.name)
                val season = parsed?.first ?: content.defaultSeason
                val epNum = parsed?.second ?: (idx + 1)
                val epName = if (isSourceLabel(ep.name)) "" else ep.name
                val used = mutableSetOf<String>()
                for (v in ep.variants) {
                    var name = "${v.n}$suffix"
                    if (!used.add(name)) {
                        var i = 2
                        while (!used.add("$name $i")) i++
                        name = "$name $i"
                    }
                    add(season, epNum, epName, RAIVariant(name, v.u, archive.dubKey))
                }
            }
        }

        return out.values.toList()
    }

    private fun buildSubPageVariants(content: PageContent): List<RAIVariant> {
        val variants = mutableListOf<RAIVariant>()
        val dubKeys = mutableSetOf<String>()
        content.archives.forEach { if (it.dubKey.isNotBlank()) dubKeys.add(it.dubKey) }
        content.direct.orphans.forEach { if (it.k.isNotBlank()) dubKeys.add(it.k) }
        content.direct.episodes.forEach { ep -> ep.variants.forEach { if (it.k.isNotBlank()) dubKeys.add(it.k) } }
        val multiDub = dubKeys.size >= 2
        for (archive in content.archives) {
            val suffix = if (multiDub && archive.dubKey.isNotBlank()) " ${dubLabel(archive.dubKey)}" else ""
            archive.episodes.forEachIndexed { idx, ep ->
                ep.variants.forEach { v ->
                    if (variants.none { it.u == v.u }) {
                        val label = when {
                            archive.episodes.size == 1 -> "${v.n}$suffix"
                            isSourceLabel(ep.name) -> "${v.n}$suffix ${idx + 1}"
                            else -> "${v.n}$suffix ${ep.name}"
                        }
                        variants.add(RAIVariant(label.trim(), v.u, archive.dubKey))
                    }
                }
            }
        }
        content.direct.orphans.forEach { v ->
            if (variants.none { it.u == v.u }) {
                val label = if (multiDub && v.k.isNotBlank()) "${v.n} ${dubLabel(v.k)}" else v.n
                variants.add(RAIVariant(label, v.u, v.k))
            }
        }
        content.sources.filter { it.kind != "archive" }.forEach { src ->
            if (variants.none { it.u == src.url }) {
                variants.add(RAIVariant(src.label, src.url, src.contextKey))
            }
        }
        if (content.archives.isEmpty()) {
            content.sources.filter { it.kind == "archive" }.forEach { src ->
                if (variants.none { it.u == src.url }) {
                    variants.add(RAIVariant(src.label, src.url, src.contextKey))
                }
            }
        }
        return variants
    }

    private suspend fun loadHubSubPage(link: HubLink): PageContent? {
        return try {
            val response = raiGet(link.url)
            val doc = Jsoup.parse(response.text)
            if (isHubPage(doc)) {
                null
            } else {
                val subMeta = extractMeta(doc)
                val subDefault = subMeta["season"]?.toIntOrNull() ?: 1
                val content = parsePageContent(doc, subDefault)
                content.copy(
                    finalUrl = response.url,
                    seasonKnown = pageHasSeasonIdentity(doc, content, subMeta)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pageHasSeasonIdentity(doc: Document, content: PageContent, meta: Map<String, String>): Boolean {
        if (meta.containsKey("season")) return true
        for (archive in content.archives) {
            for (ep in archive.episodes) {
                if (parseEpisodeNumber(ep.name)?.first != null) return true
            }
        }
        for (ep in content.direct.episodes) {
            if (parseEpisodeNumber(ep.name)?.first != null) return true
        }
        val contentEl = doc.selectFirst("div.entry-content") ?: return false
        return contentEl.select("h1,h2,h3,h4,h5,h6,p").any { SEASON_MARKER.matches(it.text().trim()) }
    }

    private fun isHubPage(doc: Document): Boolean {
        if (parseHubHeadings(doc).count { it.kind == "season" || it.kind == "movie" } >= 2) return true
        val probe = parseDirectEpisodes(doc, 1)
        if (probe.episodes.isNotEmpty() || probe.orphans.isNotEmpty()) return false
        if (extractSources(doc).isNotEmpty()) return false
        return parseParagraphHubLinks(doc).size >= 4
    }

    private fun parseParagraphHubLinks(doc: Document): List<HubLink> {
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val out = mutableListOf<HubLink>()
        val seen = mutableSetOf<String>()
        for (p in content.select("p")) {
            val candidates = p.select("a[href]").filter { isInternalPostLink(it.attr("abs:href")) }
            if (candidates.size != 1) continue
            val a = candidates.first()
            val text = a.text().trim()
            if (text.length < 8) continue
            val rowText = p.text().trim()
            val rest = rowText.replaceFirst(text, "").trim()
            if (rest.length > 15) continue
            val href = a.attr("abs:href")
            if (!seen.add(href)) continue
            val seasonNumber = HUB_SEASON_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: HUB_SEASON_NUMBER.find(rowText)?.groupValues?.get(1)?.toIntOrNull()
            val movieNumber = if (seasonNumber == null) {
                HUB_MOVIE_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: HUB_MOVIE_NUMBER.find(rowText)?.groupValues?.get(1)?.toIntOrNull()
            } else null
            val kind = when {
                seasonNumber != null -> "season"
                movieNumber != null -> "movie"
                else -> "extra"
            }
            out.add(HubLink(kind, seasonNumber ?: movieNumber, text, href))
        }
        return out
    }

    private fun seasonEpisodesForLink(link: HubLink, content: PageContent): List<HubEpisode> {
        val episodes = buildSubPageEpisodes(content)
        val target = link.number ?: return episodes
        // hub headings go stale when a post moves, the post itself is the safer source
        if (content.seasonKnown) return episodes
        return episodes.map { HubEpisode(target, it.epNum, it.name, it.variants) }
    }

    private suspend fun buildHubResponse(
        title: String,
        url: String,
        poster: String?,
        year: Int?,
        plot: String?,
        genres: List<String>,
        links: List<HubLink>
    ): LoadResponse? {
        val capped = links.take(40)
        val results = HashMap<Int, PageContent>(capped.size)

        for (chunkStart in capped.indices step 6) {
            val chunk = capped.withIndex().filter { it.index >= chunkStart && it.index < chunkStart + 6 }
            coroutineScope {
                chunk.map { (index, link) ->
                    async { index to loadHubSubPage(link) }
                }.awaitAll()
            }.forEach { (index, content) ->
                if (content != null) results[index] = content
            }
        }

        val epMap = LinkedHashMap<String, HubEpisode>()
        fun addEp(season: Int, epNum: Int, name: String, variant: RAIVariant) {
            val key = "$season:$epNum"
            val entry = epMap.getOrPut(key) { HubEpisode(season, epNum, name, mutableListOf()) }
            if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
        }

        val seasonNames = sortedMapOf<Int, String>()
        val movieEntries = mutableListOf<Pair<String, List<RAIVariant>>>()
        val extraSeries = mutableListOf<Pair<String, List<HubEpisode>>>()
        val filledSeasons = HashMap<Int, String>()
        val loadedUrls = mutableSetOf<String>()

        for ((index, link) in capped.withIndex()) {
            val content = results[index] ?: continue
            val finalUrl = content.finalUrl.ifBlank { link.url }
            val twin = !loadedUrls.add(finalUrl)
            if (link.kind == "season") {
                val episodes = seasonEpisodesForLink(link, content)
                if (episodes.isEmpty()) {
                    val variants = buildSubPageVariants(content)
                    if (variants.isNotEmpty()) movieEntries.add(link.name to variants)
                    continue
                }
                val landed = episodes.map { it.season }.toSet()
                val conflict = landed.any { filledSeasons.containsKey(it) && filledSeasons[it] != finalUrl }
                when {
                    twin -> {
                        if (link.number != null && landed.contains(link.number) &&
                            !seasonNames.containsKey(link.number)
                        ) {
                            seasonNames[link.number] = link.name
                        }
                    }
                    conflict -> extraSeries.add(link.name to episodes)
                    else -> {
                        episodes.forEach { e ->
                            e.variants.forEach { v -> addEp(e.season, e.epNum, e.name, v) }
                        }
                        landed.forEach { filledSeasons[it] = finalUrl }
                        if (link.number != null && landed.contains(link.number)) {
                            seasonNames[link.number] = link.name
                        }
                    }
                }
            } else {
                if (twin) continue
                val episodes = buildSubPageEpisodes(content)
                if (episodes.size > 1) {
                    extraSeries.add(link.name to episodes)
                } else {
                    val variants = buildSubPageVariants(content)
                    if (variants.isNotEmpty()) movieEntries.add(link.name to variants)
                }
            }
        }

        val realSeasons = epMap.values.map { it.season }.toSortedSet()
        var nextSeason = realSeasons.maxOrNull() ?: 0
        val moviesSeason = if (movieEntries.isNotEmpty()) {
            nextSeason += 1
            nextSeason
        } else 0

        val finalEpisodes = epMap.values.toMutableList()
        movieEntries.forEachIndexed { idx, entry ->
            finalEpisodes.add(HubEpisode(moviesSeason, idx + 1, entry.first, entry.second.toMutableList()))
        }
        extraSeries.forEach { (name, eps) ->
            val internal = eps.map { it.season }.distinct().sorted()
            if (internal.size > 1) {
                internal.forEachIndexed { i, s ->
                    nextSeason += 1
                    seasonNames[nextSeason] = "$name - Season ${i + 1}"
                    eps.filter { it.season == s }.forEach { e ->
                        finalEpisodes.add(HubEpisode(nextSeason, e.epNum, e.name, e.variants))
                    }
                }
            } else {
                nextSeason += 1
                seasonNames[nextSeason] = name
                eps.forEach { e ->
                    finalEpisodes.add(HubEpisode(nextSeason, e.epNum, e.name, e.variants))
                }
            }
        }

        if (finalEpisodes.isEmpty()) return null

        finalEpisodes.sortWith(compareBy({ it.season }, { it.epNum }))
        val seasonData = seasonNames.entries.map { (season, name) ->
            com.lagradost.cloudstream3.SeasonData(season, name)
        }.toMutableList()
        if (movieEntries.isNotEmpty()) {
            seasonData.add(com.lagradost.cloudstream3.SeasonData(moviesSeason, "Movies"))
        }

        val episodes = finalEpisodes.map { e ->
            newEpisode(variantsJson(e.variants)) {
                this.season = e.season
                this.episode = e.epNum
                if (e.name.isNotBlank()) this.name = e.name
            }
        }.toMutableList()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            this.seasonNames = seasonData
        }
    }

    private suspend fun buildNormalResponse(
        title: String,
        url: String,
        poster: String?,
        year: Int?,
        plot: String?,
        genres: List<String>,
        content: PageContent
    ): LoadResponse? {
        val sources = content.sources
        val direct = content.direct
        val archiveResults = content.archives
        val defaultSeason = content.defaultSeason

        data class EpEntry(
            val seasonIdx: Int,
            val realSeason: Int,
            val epNum: Int,
            val name: String,
            val variants: MutableList<RAIVariant>
        )

        val dubOrder = mutableListOf<String>()
        fun orderKey(k: String) {
            if (k.isNotBlank() && !dubOrder.contains(k)) dubOrder.add(k)
        }
        archiveResults.forEach { orderKey(it.dubKey) }
        direct.episodes.forEach { ep -> ep.variants.forEach { orderKey(it.k) } }
        val splitDubs = dubOrder.size >= 2

        val epMap = LinkedHashMap<String, EpEntry>()
        val usedVariantNames = mutableSetOf<String>()
        var anyNumbered = false

        fun addVariant(seasonIdx: Int, season: Int, epNum: Int, name: String, variant: RAIVariant) {
            val key = "$seasonIdx:$season:$epNum"
            val entry = epMap.getOrPut(key) { EpEntry(seasonIdx, season, epNum, name, mutableListOf()) }
            if (entry.variants.none { it.u == variant.u }) entry.variants.add(variant)
        }

        for (ep in direct.episodes) {
            anyNumbered = true
            val keys = ep.variants.map { it.k }.filter { it.isNotBlank() }.distinct()
            val groups = if (keys.isEmpty()) listOf("") else keys
            for (g in groups) {
                val idx = if (splitDubs) dubOrder.indexOf(g).let { if (it >= 0) it else 0 } else 0
                ep.variants.filter { it.k == g || (g.isEmpty() && it.k.isBlank()) }.forEach { v ->
                    addVariant(idx, ep.season, ep.epNum, ep.name, v)
                }
            }
        }

        for (archive in archiveResults) {
            var vName = archive.sourceName
            if (!usedVariantNames.add("${archive.dubKey}:$vName")) {
                var i = 2
                while (!usedVariantNames.add("${archive.dubKey}:$vName $i")) i++
                vName = "$vName $i"
            }
            archive.episodes.forEachIndexed { idx, ep ->
                val parsed = parseEpisodeNumber(ep.name)
                if (parsed != null) anyNumbered = true
                val season = parsed?.first ?: defaultSeason
                val epNum = parsed?.second ?: (idx + 1)
                val epName = if (isSourceLabel(ep.name)) "" else ep.name
                val idx0 = if (splitDubs) dubOrder.indexOf(archive.dubKey).let { if (it >= 0) it else 0 } else 0
                ep.variants.forEach { v ->
                    addVariant(idx0, season, epNum, epName, RAIVariant(vName, v.u, archive.dubKey))
                }
            }
        }

        val isMovie = !anyNumbered

        return if (isMovie) {
            val variants = mutableListOf<RAIVariant>()
            for (archive in archiveResults) {
                archive.episodes.forEachIndexed { idx, ep ->
                    ep.variants.forEach { v ->
                        if (variants.none { it.u == v.u }) {
                            val label = when {
                                archive.episodes.size == 1 -> v.n
                                isSourceLabel(ep.name) -> "${v.n} ${idx + 1}"
                                else -> "${v.n} ${ep.name}"
                            }
                            variants.add(RAIVariant(label, v.u, v.k))
                        }
                    }
                }
            }
            direct.orphans.forEach { v ->
                if (variants.none { it.u == v.u }) variants.add(v)
            }
            sources.filter { it.kind != "archive" }.forEach { src ->
                if (variants.none { it.u == src.url }) {
                    variants.add(RAIVariant(src.label, src.url, src.contextKey))
                }
            }
            if (archiveResults.isEmpty()) {
                sources.filter { it.kind == "archive" }.forEach { src ->
                    if (variants.none { it.u == src.url }) {
                        variants.add(RAIVariant(src.label, src.url, src.contextKey))
                    }
                }
            }
            if (variants.isEmpty()) return null
            newMovieLoadResponse(title, url, TvType.Movie, variantsJson(variants)) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
            }
        } else {
            val episodes = epMap.values.sortedWith(
                compareBy({ it.seasonIdx }, { it.realSeason }, { it.epNum })
            ).map { e ->
                newEpisode(variantsJson(e.variants)) {
                    this.season = if (splitDubs) e.seasonIdx + 1 else e.realSeason
                    this.episode = e.epNum
                    if (e.name.isNotBlank()) this.name = e.name
                }
            }.toMutableList()

            if (episodes.isEmpty()) return null

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                if (splitDubs) {
                    val usedIdx = epMap.values.map { it.seasonIdx }.toSortedSet()
                    this.seasonNames = usedIdx.mapNotNull { idx ->
                        dubOrder.getOrNull(idx)?.let { k ->
                            com.lagradost.cloudstream3.SeasonData(idx + 1, dubLabel(k))
                        }
                    }
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val response = raiGet(url)
            val doc = Jsoup.parse(response.text)

            val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".herald-post-thumbnail img, img.wp-post-image")?.attr("abs:src")

            val meta = extractMeta(doc)
            val year = meta["year"]?.toIntOrNull()
            val defaultSeason = meta["season"]?.toIntOrNull() ?: 1

            val plot = doc.select("div.entry-content p").map { it.text().trim() }
                .firstOrNull {
                    it.startsWith("Synopsis", true) || it.startsWith("Storyline", true) ||
                            it.startsWith("Story:", true)
                }
                ?.substringAfter(":")?.trim()
                ?: doc.select("div.entry-content p").map { it.text().trim() }
                    .filter { it.length > 120 }
                    .maxByOrNull { it.length }

            val genres = doc.select(".meta-category a, .herald-meta a[href*=category]").map {
                it.text().trim()
            }.filter { it.isNotBlank() }.distinct().take(8)

            var hubLinks = parseHubHeadings(doc)
            var useHub = hubLinks.count { it.kind == "season" || it.kind == "movie" } >= 2
            if (!useHub) {
                // some collections list their seasons as plain paragraph links instead of headings
                val probe = parseDirectEpisodes(doc, 1)
                if (probe.episodes.isEmpty() && probe.orphans.isEmpty() && extractSources(doc).isEmpty()) {
                    val paragraphLinks = parseParagraphHubLinks(doc)
                    if (paragraphLinks.size >= 4) {
                        hubLinks = paragraphLinks
                        useHub = true
                    }
                }
            }
            if (useHub) {
                val hub = try {
                    buildHubResponse(title, url, poster, year, plot, genres, hubLinks)
                } catch (_: Exception) {
                    null
                }
                if (hub != null) return hub
            }

            val content = parsePageContent(doc, defaultSeason)
            buildNormalResponse(title, url, poster, year, plot, genres, content)
        } catch (_: Exception) {
            null
        }
    }

    private fun qualityFromLabel(label: String): Int {
        return when {
            label.contains("2160", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            label.contains("720", ignoreCase = true) -> Qualities.P720.value
            label.contains("480", ignoreCase = true) -> Qualities.P480.value
            label.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private suspend fun resolveArgon(
        code: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val embed = raiGet(
                "https://$ARGON_HOST/embed/$code",
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Accept" to "text/html,*/*;q=0.8",
                    "Referer" to "https://$CODEDEW_HOST/"
                )
            )
            val config = JuicyCodes.decodeFromHtml(embed.text)
            val m3u8 = config?.let {
                Regex(""""file"\s*:\s*"([^"]*\.m3u8)"""").find(it)?.groupValues?.get(1)
            }?.replace("\\/", "/")
            if (!m3u8.isNullOrBlank() && m3u8.startsWith("http")) {
                // the config file is an HLS playlist, a direct link makes the player choke on the playlist text
                callback(
                    newExtractorLink(
                        SOURCE,
                        "MultiQuality [$suffix]",
                        m3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://$ARGON_HOST/"
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Origin" to "https://$ARGON_HOST",
                            "Sec-Fetch-Site" to "cross-site",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Dest" to "empty"
                        )
                    }
                )
                found = true
            }
        } catch (_: Exception) {}
        return found
    }

    private fun absorbSetCookies(jar: MutableMap<String, String>, response: NiceResponse) {
        for (raw in response.headers.values("set-cookie")) {
            val pair = raw.substringBefore(";").trim()
            val idx = pair.indexOf('=')
            if (idx > 0) jar[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
    }

    private fun cookieHeaderValue(jar: Map<String, String>): String {
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun resolveArgonDownload(
        code: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val jar = mutableMapOf<String, String>()
            RAICFStore.getCookies(ARGON_HOST)?.let { stored ->
                for (pair in stored.split("; ")) {
                    val idx = pair.indexOf('=')
                    if (idx > 0) jar[pair.substring(0, idx)] = pair.substring(idx + 1)
                }
            }

            val embedUrl = "https://$ARGON_HOST/embed/$code"
            val embed = raiGet(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Referer" to "https://$CODEDEW_HOST/",
                    "Accept" to "text/html,*/*;q=0.8"
                )
            )
            absorbSetCookies(jar, embed)
            val embedJuicy = Regex(
                """window\.juicyData\s*=\s*(\{.*?\})\s*</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(embed.text)?.groupValues?.get(1)
                ?.let { runCatching { parseJson<JuicyDataWrapper>(it) }.getOrNull() }
            val pingToken = embedJuicy?.data?.token
            val pingRoute = embedJuicy?.data?.routes?.ping
            if (!pingRoute.isNullOrBlank() && !pingToken.isNullOrBlank()) {
                val pingId = UUID.randomUUID().toString().replace("-", "")
                val ping = raiPostJson(
                    "https://$ARGON_HOST$pingRoute",
                    """{"_token":"$pingToken","__type":"dawn","pingID":"$pingId"}""",
                    headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Accept-Language" to ARGON_AL,
                        "Accept" to "*/*",
                        "Referer" to embedUrl,
                        "Origin" to "https://$ARGON_HOST",
                        "Cookie" to cookieHeaderValue(jar)
                    )
                )
                absorbSetCookies(jar, ping)
            }

            val dlUrl = "https://$ARGON_HOST/downlead/$code/"
            val dl = raiGet(
                dlUrl,
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Accept" to "text/html,*/*;q=0.8",
                    "Referer" to embedUrl,
                    "Cookie" to cookieHeaderValue(jar)
                )
            )
            absorbSetCookies(jar, dl)
            val wrapper = Regex(
                """window\.juicyData\s*=\s*(\{.*?\})\s*</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(dl.text)?.groupValues?.get(1)
                ?.let { runCatching { parseJson<JuicyDataWrapper>(it) }.getOrNull() }
            val token = wrapper?.data?.token
            val route = wrapper?.data?.routes?.links
            if (token.isNullOrBlank() || route.isNullOrBlank()) return false
            val api = raiPostJson(
                "https://$ARGON_HOST$route",
                """{"captcha":null,"_token":"$token"}""",
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Accept-Language" to ARGON_AL,
                    "Accept" to "application/json",
                    "Referer" to dlUrl,
                    "Origin" to "https://$ARGON_HOST",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Cookie" to cookieHeaderValue(jar)
                )
            )
            if (api.code != 200) {
                return false
            }
            val links = parseJson<ArgonLinks>(api.text)
            links.qualities?.forEach { q ->
                val link = q.link ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                val label = q.label ?: "Download"
                val size = q.size?.let { " ($it)" } ?: ""
                callback(
                    newExtractorLink(
                        SOURCE,
                        "DLBeta $label$size [$suffix]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityFromLabel(label)
                        this.referer = "https://$ARGON_HOST/"
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.9",
                            "Origin" to "https://$ARGON_HOST"
                        )
                    }
                )
                found = true
            }
        } catch (_: Exception) {}
        return found
    }

    private suspend fun emitPixelDrain(
        id: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                SOURCE,
                "PixelDrain [$suffix]",
                "https://$PIXELDRAIN_HOST/api/file/$id",
                ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to RAI_UA)
            }
        )
        return true
    }

    private fun pixelDrainId(url: String): String? {
        val m = Regex("""/(?:u|api/file)/([A-Za-z0-9]{6,12})""").find(url) ?: return null
        return m.groupValues[1]
    }

    private suspend fun probeOk(url: String, timeoutMs: Long = 9_000L): Boolean {
        return try {
            val r = com.lagradost.cloudstream3.app.get(
                url,
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Range" to "bytes=0-1023"
                ),
                timeout = timeoutMs
            )
            r.code in 200..299 || r.code == 206
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveStreamBeta(
        id: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val page = raiGet("https://$CODEDEW_HOST/streambeta/?url=" +
                java.net.URLEncoder.encode(id, "UTF-8"))
            if (page.code != 200) return false
            val m = Regex(
                """playerSources\s*=\s*(\[.*?\])\s*;""",
                RegexOption.DOT_MATCHES_ALL
            ).find(page.text) ?: return false
            val parsed = parseJson<List<StreamBetaLink>>(m.groupValues[1])
            if (parsed.isEmpty()) return false
            val emitted = mutableSetOf<String>()
            for (src in parsed) {
                val name = src.name ?: "Server"
                src.streamUrl?.takeIf { it.startsWith("http") }?.let { stream ->
                    if (!emitted.contains(stream) && probeOk(stream)) {
                        emitted.add(stream)
                        callback(
                            newExtractorLink(
                                SOURCE,
                                "WatchNow $name [$suffix]",
                                stream,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to RAI_UA)
                            }
                        )
                        found = true
                    }
                }
                src.url?.takeIf { it.startsWith("http") }?.let { dl ->
                    val pdId = if (dl.contains("pixeldra")) pixelDrainId(dl) else null
                    val target = if (pdId != null) "https://$PIXELDRAIN_HOST/api/file/$pdId" else dl
                    if (!emitted.contains(target) && !target.contains("mega.nz") && probeOk(target)) {
                        emitted.add(target)
                        callback(
                            newExtractorLink(
                                SOURCE,
                                "WatchNow $name DL [$suffix]",
                                target,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf("User-Agent" to RAI_UA)
                            }
                        )
                        found = true
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    private var goFileTokenCache: String? = null
    private var goFileTokenAt: Long = 0

    private suspend fun goFileToken(): String? {
        val cached = goFileTokenCache
        if (cached != null && System.currentTimeMillis() - goFileTokenAt < 3_600_000L) {
            return cached
        }
        return try {
            val account = parseJson<GoFileAccount>(
                com.lagradost.cloudstream3.app.post(
                    "https://api.gofile.io/accounts",
                    headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/"
                    ),
                    timeout = 20_000L
                ).text
            )
            val token = account.data?.token
            if (token.isNullOrBlank()) return null
            com.lagradost.cloudstream3.app.get(
                "https://api.gofile.io/accounts/website",
                headers = mapOf(
                    "User-Agent" to RAI_UA,
                    "Authorization" to "Bearer $token",
                    "Origin" to "https://gofile.io",
                    "Referer" to "https://gofile.io/"
                ),
                timeout = 20_000L
            )
            goFileTokenCache = token
            goFileTokenAt = System.currentTimeMillis()
            token
        } catch (_: Exception) {
            null
        }
    }

    private fun goFileWt(token: String): String {
        val bucket = System.currentTimeMillis() / 14_400_000L
        val payload = "$RAI_UA::en-US::$token::$bucket::$GOFILE_WT_SECRET"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun resolveGoFile(
        code: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val token = goFileToken() ?: return false
            val wt = goFileWt(token)
            val content = parseJson<GoFileContent>(
                com.lagradost.cloudstream3.app.get(
                    "https://api.gofile.io/contents/$code?pageSize=100&sortField=name&sortDirection=1",
                    headers = mapOf(
                        "User-Agent" to RAI_UA,
                        "Authorization" to "Bearer $token",
                        "X-Website-Token" to wt,
                        "X-BL" to "en-US",
                        "Origin" to "https://gofile.io",
                        "Referer" to "https://gofile.io/"
                    ),
                    timeout = 25_000L
                ).text
            )
            if (content.status != "ok") {
                goFileTokenCache = null
                return false
            }
            content.data?.children.orEmpty().values.forEach { child ->
                val link = child.link ?: return@forEach
                if (!link.startsWith("http")) return@forEach
                val sizeMb = child.size?.let {
                    if (it > 0) " ${it / 1048576}MB" else ""
                } ?: ""
                callback(
                    newExtractorLink(
                        SOURCE,
                        "GoFile ${(child.name ?: "file").take(40)}$sizeMb [$suffix]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityFromLabel(child.name ?: "")
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Authorization" to "Bearer $token"
                        )
                    }
                )
                found = true
            }
        } catch (_: Exception) {}
        return found
    }

    private suspend fun resolveMediaFire(
        url: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val page = raiGet(url)
            if (page.code != 200) return false
            val direct = Regex("""href="(https://download[0-9a-z]*\.mediafire\.com/[^"]+)"""")
                .find(page.text)?.groupValues?.get(1)
            if (direct.isNullOrBlank()) return false
            callback(
                newExtractorLink(
                    SOURCE,
                    "MediaFire [$suffix]",
                    direct,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to RAI_UA)
                }
            )
            found = true
        } catch (_: Exception) {}
        return found
    }

    private suspend fun resolveHubCloud(
        id: String,
        suffix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val page = raiGet("https://$HUBCLOUD_HOST/drive/$id")
            val resolverUrl = Regex("""var\s+url\s*=\s*'([^']+)'""").find(page.text)?.groupValues?.get(1)
            if (resolverUrl.isNullOrBlank()) return false
            val res = raiGet(resolverUrl, mapOf("Referer" to "https://$HUBCLOUD_HOST/"))
            val body = res.text

            Regex("""<a[^>]*href="(https?://[^"]+)"[^>]*id="fsl"""").find(body)?.let { m ->
                val fsl = htmlUnescape(m.groupValues[1])
                if (fsl.startsWith("http") && !fsl.contains("hubcloud.cx/favicon")) {
                    callback(
                        newExtractorLink(
                            SOURCE,
                            "HubCloud FSL [$suffix]",
                            fsl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to RAI_UA)
                        }
                    )
                    found = true
                }
            }
            if (!found) {
                Regex("""href="(https?://[^"]*cloudflarestorage\.com/[^"]+)"""").find(body)?.let { m ->
                    callback(
                        newExtractorLink(
                            SOURCE,
                            "HubCloud FSL [$suffix]",
                            htmlUnescape(m.groupValues[1]),
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to RAI_UA)
                        }
                    )
                    found = true
                }
            }

            Regex("""https://pixel\.hubcloud\.ist/\?id=[^"'\s<>]+""").find(body)?.let { pixelMatch ->
                try {
                    var hopUrl: String? = pixelMatch.value
                    for (hop in 0 until 3) {
                        val current = hopUrl ?: break
                        val hopResp = raiGet(current, allowRedirects = false)
                        val loc = hopResp.headers["location"]
                        if (loc.isNullOrBlank()) break
                        val next: String = if (loc.startsWith("http")) loc
                        else "https://pixel.hubcloud.ist$loc"
                        hopUrl = next
                        if (next.contains("dl.php?link=")) {
                            val direct = android.net.Uri.decode(
                                next.substringAfter("dl.php?link=")
                            )
                            if (direct.startsWith("http")) {
                                callback(
                                    newExtractorLink(
                                        SOURCE,
                                        "HubCloud 10Gbps [$suffix]",
                                        direct,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf("User-Agent" to RAI_UA)
                                    }
                                )
                                found = true
                            }
                            break
                        }
                    }
                } catch (_: Exception) {}
            }

            Regex("""https?://pixel(?:drain|dra)\.[a-z]+/u/([A-Za-z0-9]+)""")
                .findAll(body)
                .map { it.groupValues[1] }
                .distinct()
                .take(2)
                .forEach { pid ->
                    val api = "https://$PIXELDRAIN_HOST/api/file/$pid"
                    if (probeOk(api)) {
                        emitPixelDrain(pid, "$suffix mirror", callback)
                        found = true
                    }
                }
        } catch (_: Exception) {}
        return found
    }

    private suspend fun emitResolvedPopupLink(
        resolved: RAIResolvedLink,
        callback: (ExtractorLink) -> Unit
    ) {
        when (resolved.kind) {
            "hls" -> {
                // the webview link is signed for the webview locale and 403s in the player, re-sign when possible
                val embedCode = resolved.pageUrl
                    ?.takeIf { it.contains("$ARGON_HOST/embed/") }
                    ?.substringAfter("/embed/")
                    ?.substringBefore("?")
                    ?.substringBefore("/")
                if (!embedCode.isNullOrBlank() && resolveArgon(embedCode, "WebView", callback)) {
                    return
                }
                callback(
                    newExtractorLink(
                        SOURCE,
                        "MultiQuality [WebView]",
                        resolved.url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "https://$ARGON_HOST/"
                        this.headers = mapOf(
                            "User-Agent" to RAI_UA,
                            "Accept-Language" to ARGON_AL,
                            "Origin" to "https://$ARGON_HOST"
                        )
                    }
                )
            }
            "pixeldrain" -> callback(
                newExtractorLink(
                    SOURCE,
                    "PixelDrain [WebView]",
                    resolved.url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to RAI_UA)
                }
            )
            "pixeldrain_page" -> {
                val id = resolved.url.substringAfter("/u/").substringBefore("?").substringBefore("/")
                if (id.isNotBlank()) {
                    emitPixelDrain(id, "WebView", callback)
                }
            }
            "r2", "gvideo", "worker", "mediafire" -> callback(
                newExtractorLink(
                    SOURCE,
                    "Direct [WebView]",
                    resolved.url,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to RAI_UA)
                }
            )
        }
    }

    private suspend fun resolveTarget(
        t: ResolvedTarget,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when (t) {
            is ResolvedTarget.Argon -> resolveArgon(t.code, label, callback)
            is ResolvedTarget.ArgonDownload -> resolveArgonDownload(t.code, label, callback)
            is ResolvedTarget.StreamBeta -> resolveStreamBeta(t.id, label, callback)
            is ResolvedTarget.PixelDrain -> emitPixelDrain(t.id, label, callback)
            is ResolvedTarget.HubCloud -> resolveHubCloud(t.id, label, callback)
            is ResolvedTarget.GoFile -> resolveGoFile(t.code, label, callback)
            is ResolvedTarget.MediaFire -> resolveMediaFire(t.url, label, callback)
            is ResolvedTarget.Mega -> {
                false
            }
            is ResolvedTarget.Archive -> {
                var emitted = false
                try {
                    val resp = raiGet(t.url)
                    parseArchiveEpisodes(resp.text).take(3).forEach { (_, epUrl) ->
                        try {
                            val t2 = CodedewResolver.resolveUrl(epUrl)
                            if (resolveTarget(t2, label, callback)) emitted = true
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                emitted
            }
            is ResolvedTarget.Direct -> {
                callback(
                    newExtractorLink(
                        SOURCE,
                        "Direct [$label]",
                        t.url,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to RAI_UA)
                    }
                )
                true
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val variants = try {
            parseJson<RAIEpisodeData>(data).v
        } catch (_: Exception) {
            listOf(RAIVariant("Default", data))
        }.filter { it.u.isNotBlank() }

        if (variants.isEmpty()) return false

        var any = false
        for (v in variants) {
            try {
                if (resolveTarget(CodedewResolver.resolveUrl(v.u), v.n, callback)) any = true
            } catch (_: Exception) {}
        }

        if (!any) {
            val popup = showRAIResolverPopupAndWait(variants.first().u)
            if (popup != null) {
                emitResolvedPopupLink(popup, callback)
                any = true
            }
        }
        return any
    }
}
