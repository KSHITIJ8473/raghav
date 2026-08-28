package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import java.net.URL
import java.net.URLDecoder

class RaghavAniDao : MainAPI() {
    override var mainUrl = "https://anidao.to"
    override var name = "AniDao"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "trending" to "Top Trending",
        "recent" to "Recent Added",
        "ongoing" to "Ongoing Anime"
    )

    private val baseHeaders = mapOf("Referer" to "$mainUrl/")

    @Volatile private var homeCache: Document? = null
    private var homeTime = 0L
    private val homeTtl = 5 * 60 * 1000L

    private data class AnimeEntry(val url: String, val title: String, val poster: String)
    @Volatile private var indexCache: List<AnimeEntry>? = null
    private var indexTime = 0L
    private val indexTtl = 15 * 60 * 1000L

    private val listBatch = 20
    private val maxListPages = 120
    private val maxSearchResults = 80

    private val m3u8Regex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("RaghavAnime", "[AniDao] getMainPage page=$page name=${request.name}")
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val doc = homeDocument()
        val sectionId = when (request.data) {
            "trending" -> "an-top-trending-title"
            "recent" -> "an-recent-added-title"
            "ongoing" -> "an-ongoing-title"
            else -> return newHomePageResponse(request.name, emptyList())
        }
        val section = doc.selectFirst("""section[aria-labelledby="$sectionId"]""")
            ?: return newHomePageResponse(request.name, emptyList())

        val home = mutableListOf<SearchResponse>()
        for (item in section.select(".an-home-list-item, article.an-anime-card")) {
            val href = item.attr("href").ifEmpty {
                item.selectFirst("""a[href*="/watch-online/"]""")?.attr("href") ?: ""
            }
            if (!href.contains("/watch-online/")) continue
            val title = item.selectFirst("[data-an-name-en]")?.attr("data-an-name-en")?.trim()
                ?.ifEmpty { null }
                ?: item.selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
                ?: item.selectFirst(".an-anime-card__title a")?.text()?.trim()?.ifEmpty { null }
                ?: item.selectFirst("strong")?.text()?.trim()?.ifEmpty { null }
                ?: continue
            val poster = item.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            } ?: ""

            home.add(newAnimeSearchResponse(title, fixUrl(toAnimePath(href)), TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(dubExist = true, subExist = true)
            })
        }
        Log.d("RaghavAnime", "[AniDao] getMainPage ${request.name}: ${home.size} items")
        return newHomePageResponse(request.name, home)
    }

    private suspend fun homeDocument(): Document {
        val now = System.currentTimeMillis()
        val cached = homeCache
        if (cached != null && now - homeTime < homeTtl) return cached
        Log.d("RaghavAnime", "[AniDao] homeDocument: cache miss, fetching $mainUrl")
        val doc = app.get(mainUrl, headers = baseHeaders).document
        homeCache = doc
        homeTime = now
        return doc
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d("RaghavAnime", "[AniDao] search: query='$query'")
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        return currentIndex().asSequence()
            .filter { it.title.lowercase().contains(q) }
            .take(maxSearchResults)
            .map { e ->
                newAnimeSearchResponse(e.title, e.url, TvType.Anime) {
                    this.posterUrl = e.poster
                    addDubStatus(dubExist = true, subExist = true)
                }
            }
            .toList()
    }

    private suspend fun currentIndex(): List<AnimeEntry> {
        val now = System.currentTimeMillis()
        val cached = indexCache
        if (cached != null && now - indexTime < indexTtl) return cached
        Log.d("RaghavAnime", "[AniDao] currentIndex: cache miss (age=${now - indexTime}ms), building index")
        val built = buildIndex()
        Log.d("RaghavAnime", "[AniDao] currentIndex: index built with ${built.size} entries")
        indexCache = built
        indexTime = now
        return built
    }

    private suspend fun buildIndex(): List<AnimeEntry> {
        Log.d("RaghavAnime", "[AniDao] buildIndex: starting (batch=$listBatch maxPages=$maxListPages)")
        val results = mutableListOf<AnimeEntry>()
        val seen = mutableSetOf<String>()
        var start = 1
        while (start <= maxListPages) {
            val end = minOf(start + listBatch - 1, maxListPages)
            val batch = coroutineScope {
                (start..end).map { p -> async { parseListPage(p) } }.awaitAll()
            }
            var added = 0
            for (entries in batch) {
                for (e in entries) {
                    if (seen.add(e.url)) {
                        results.add(e)
                        added++
                    }
                }
            }
            Log.d("RaghavAnime", "[AniDao] buildIndex: pages $start-$end added $added entries (total ${results.size})")
            if (added == 0) break
            start = end + 1
        }
        Log.d("RaghavAnime", "[AniDao] buildIndex: done, ${results.size} entries total")
        return results
    }

    private suspend fun parseListPage(page: Int): List<AnimeEntry> {
        return try {
            val doc = app.get("$mainUrl/animelist?page=$page", headers = baseHeaders).document
            doc.select("article.an-anime-card").mapNotNull { card ->
                val a = card.selectFirst("""a[href*="/anime/"]""") ?: return@mapNotNull null
                val url = fixUrl(a.attr("href"))
                val title = a.attr("title").trim().ifEmpty {
                    card.selectFirst(".an-anime-card__title a")?.text()?.trim() ?: return@mapNotNull null
                }
                val poster = card.selectFirst("img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                } ?: ""
                AnimeEntry(url, title, poster)
            }
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniDao] parseListPage page=$page failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("RaghavAnime", "[AniDao] load: ${url.take(120)}")
        val doc = app.get(url, headers = baseHeaders).document

        val titleEl = doc.selectFirst("h1.an-section__title")
        val title = titleEl?.attr("data-an-name-en")?.trim()?.ifEmpty { null }
            ?: titleEl?.text()?.trim()
            ?: return null
        val jpName = titleEl?.attr("data-an-name-jp")?.trim()?.ifEmpty { null }
        val posterUrl = doc.selectFirst(".an-detail-hero__poster img")?.attr("src")
        val plot = doc.selectFirst(".an-detail-hero__content > p")?.text()?.trim()

        val metaSpans = doc.select(".an-detail-meta span")
        val year = metaSpans.firstOrNull { it.text().startsWith("Year:") }
            ?.text()?.removePrefix("Year:")?.trim()?.toIntOrNull()
        val tags = doc.select("a.an-genre-chip").map { it.text().trim() }.filter { it.isNotEmpty() }

        val statusStr = doc.selectFirst(".an-badge--status")?.text()?.trim()
        val showStatus = when (statusStr?.lowercase()) {
            "currently airing", "ongoing" -> ShowStatus.Ongoing
            "finished airing", "completed" -> ShowStatus.Completed
            else -> null
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val seenEps = mutableSetOf<String>()
        var epRows = doc.select("""[data-an-panel="oldest"] article.an-episode-row""")
        if (epRows.isEmpty()) epRows = doc.select("article.an-episode-row")
        val orderedRows = epRows.reversed()

        for (row in orderedRows) {
            val link = row.selectFirst("""a[href*="/watch-online/"]""") ?: continue
            val href = fixUrl(link.attr("href"))
            if (!seenEps.add(href)) continue

            val titleText = row.selectFirst(".an-episode-row__title a")?.text()?.trim() ?: ""
            val epNum = Regex("-episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
            val epName = titleText
                .replace(Regex("\\s*[Ee]pisode\\s*\\d+\\s*$"), "")
                .replace(Regex("^\\d+\\s+"), "")
                .trim()
                .ifEmpty { null }

            val hasHsub = row.select(".an-badge--hsub").isNotEmpty()
            val hasSub = row.select(".an-badge--sub").isNotEmpty()
            val hasDub = row.select(".an-badge--dub").isNotEmpty()

            if (hasSub || hasHsub) {
                subEpisodes.add(newEpisode("$href|sub") {
                    this.name = epName
                    this.episode = epNum
                })
            }
            if (hasDub) {
                dubEpisodes.add(newEpisode("$href|dub") {
                    this.name = epName
                    this.episode = epNum
                })
            }
        }

        Log.d("RaghavAnime", "[AniDao] load: '$title' sub=${subEpisodes.size} dub=${dubEpisodes.size} rows=${orderedRows.size}")
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.showStatus = showStatus
            if (jpName != null) this.japName = jpName
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val watchUrl = parts[0]
        val type = parts.getOrElse(1) { "sub" }
        Log.d("RaghavAnime", "[AniDao] loadLinks: url=${watchUrl.take(120)} type=$type")

        val doc = fetchWatchDoc(watchUrl)
        var found = false

        // Each tab only fetches its own panels: hsub+sub for Subbed, dub for Dubbed.
        val panels = if (type == "dub") {
            listOf("dub" to SourceKind.DUB)
        } else {
            listOf("hsub" to SourceKind.HARDSUB, "sub" to SourceKind.SUB)
        }

        for ((panel, kind) in panels) {
            Log.d("RaghavAnime", "[AniDao] loadLinks: panel=$panel (${kind.label})")
            val section = doc.selectFirst("""[data-an-panel="$panel"]""") ?: continue
            for (btn in section.select("button[data-an-video]")) {
                val embed = btn.attr("data-an-video")
                if (embed.isEmpty()) continue
                val label = "AniDao - ${domainName(embed)} (${kind.label})"
                Log.d("RaghavAnime", "[AniDao] resolving $label embed=${embed.take(120)}")
                passSubtitle(embed, subtitleCallback)
                if (resolveEmbed(embed, watchUrl, label, subtitleCallback, callback)) {
                    Log.d("RaghavAnime", "[AniDao] $label resolved OK")
                    found = true
                }
            }
        }
        Log.d("RaghavAnime", "[AniDao] loadLinks done: found=$found")
        return found
    }

    private enum class SourceKind(val label: String) {
        SUB("Sub"), DUB("Dub"), HARDSUB("Hardsub")
    }

    // AniDao soft-404s episode-1 URLs of long anime (e.g. one-piece-100-episode-1);
    private suspend fun fetchWatchDoc(url: String): Document {
        val doc = app.get(url, headers = baseHeaders).document
        Log.d("RaghavAnime", "[AniDao] fetchWatchDoc: primary hasPanels=${hasAnyPanel(doc)}")
        if (hasAnyPanel(doc)) return doc

        val altUrl = url.replace(Regex("-100-episode-"), "-episode-")
        Log.d("RaghavAnime", "[AniDao] fetchWatchDoc: no panels, trying alt=${altUrl.take(120)}")
        if (altUrl != url) {
            val altDoc = app.get(altUrl, headers = baseHeaders).document
            Log.d("RaghavAnime", "[AniDao] fetchWatchDoc: alt hasPanels=${hasAnyPanel(altDoc)}")
            if (hasAnyPanel(altDoc)) return altDoc
        }
        return doc
    }

    private fun hasAnyPanel(doc: Document) = doc.selectFirst(
        """[data-an-panel="sub"], [data-an-panel="dub"], [data-an-panel="hsub"]"""
    ) != null

    private suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                embedUrl.contains("vivibebe.site") || embedUrl.contains("bibiemb.xyz") -> {
                    val html = app.get(embedUrl, headers = baseHeaders).text
                    Log.d("RaghavAnime", "[AniDao] resolveEmbed: vivibebe/bibiemb fast path for $label, html len=${html.length}, m3u8=${extractM3u8(html)?.take(120)}")
                    extractM3u8(html)?.let { callback(m3u8Link(label, it, embedUrl)); true } ?: false
                }
                embedUrl.contains("otakuhg.site") || embedUrl.contains("otakuvid.online") -> {
                    val html = app.get(embedUrl, headers = baseHeaders).text
                    Log.d("RaghavAnime", "[AniDao] resolveEmbed: otaku fast path for $label, html len=${html.length}")
                    val m3u8 = extractM3u8(html)
                        ?: JsPacker.parseAndUnpack(html)?.let { extractM3u8(it) }
                    Log.d("RaghavAnime", "[AniDao] resolveEmbed: otaku m3u8=${m3u8?.take(120)}")
                    m3u8?.let { callback(m3u8Link(label, it, embedUrl)); true } ?: false
                }
                embedUrl.contains("playmogo.com") -> {
                    Log.d("RaghavAnime", "[AniDao] resolveEmbed: playmogo via loadExtractor: ${embedUrl.take(120)}")
                    loadExtractor(embedUrl, referer, subtitleCallback, callback)
                }
                else -> {
                    Log.d("RaghavAnime", "[AniDao] resolveEmbed: generic embed for $label: ${embedUrl.take(120)}")
                    if (loadExtractor(embedUrl, referer, subtitleCallback, callback)) {
                        true
                    } else {
                        val html = app.get(embedUrl, headers = baseHeaders).text
                        Log.d("RaghavAnime", "[AniDao] resolveEmbed: loadExtractor failed for $label, m3u8 scan html len=${html.length}, m3u8=${extractM3u8(html)?.take(120)}")
                        extractM3u8(html)?.let { callback(m3u8Link(label, it, embedUrl)); true } ?: false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniDao] resolveEmbed failed for ${embedUrl.take(120)}: ${e.message}")
            false
        }
    }

    private fun extractM3u8(html: String): String? = m3u8Regex.find(html)?.value

    private suspend fun m3u8Link(label: String, m3u8: String, referer: String) = newExtractorLink(
        source = name,
        name = label,
        url = m3u8,
        type = ExtractorLinkType.M3U8
    ) {
        this.headers = mapOf("Referer" to referer)
    }

    private fun passSubtitle(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val query = URL(embedUrl).query ?: return
            val sub = Regex("""(?:sub|caption_1|c1_file)=([^&]+)""").find(query)?.groupValues?.get(1)
                ?: return
            val decoded = URLDecoder.decode(sub, "UTF-8")
            val label = Regex("""(?:sub_1|c1_label)=([^&]+)""").find(query)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "English"
            Log.d("RaghavAnime", "[AniDao] subtitle: $label ${decoded.take(120)}")
            subtitleCallback.invoke(SubtitleFile(label, decoded))
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniDao] passSubtitle failed for ${embedUrl.take(120)}: ${e.message}")
        }
    }

    private fun domainName(url: String): String {
        return try {
            URL(url).host.substringBefore(".")
        } catch (e: Exception) {
            Log.e("RaghavAnime", "[AniDao] domainName failed: ${e.message}")
            "unknown"
        }
    }

    private fun toAnimePath(watchHref: String): String {
        val slug = watchHref.substringAfter("/watch-online/", "")
            .replace(Regex("-100-episode-\\d+.*"), "")
            .replace(Regex("-episode-\\d+.*"), "")
            .trim()
        return if (slug.isEmpty()) watchHref else "/anime/$slug"
    }
}

