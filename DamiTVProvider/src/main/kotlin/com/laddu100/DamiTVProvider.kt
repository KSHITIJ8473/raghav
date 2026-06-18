package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * DamiTV Provider — scrapes live sports events from dami-tv.pro.
 *
 * Structure:
 *  - Homepage: list of events grouped by sport category (Cricket, Football, etc.)
 *  - Each event links to a detail page with embedded iframe(s)
 *  - Search supported via the site's search endpoint
 */
class DamiTVProvider : MainAPI() {

    override var mainUrl = "https://dami-tv.pro"
    override var name = "DamiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // ── Common request headers ────────────────────────────────────────────────

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    // ── Data classes ──────────────────────────────────────────────────────────

    data class EventLoadData(
        val title: String,
        val url: String,
        val posterUrl: String?,
        val category: String?
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String,
        val headers: Map<String, String> = emptyMap()
    )

    // ── Fetch helpers ─────────────────────────────────────────────────────────

    private suspend fun fetchDocument(url: String, referer: String? = null): Document? {
        return try {
            val headers = if (referer != null) {
                baseHeaders + ("Referer" to referer)
            } else baseHeaders

            val response = app.get(url, headers = headers, referer = referer ?: mainUrl)
            Jsoup.parse(response.text)
        } catch (e: Exception) {
            println("DamiTV: fetchDocument failed for $url — ${e.message}")
            null
        }
    }

    // ── Main Page ─────────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = fetchDocument("$mainUrl/") ?: return newHomePageResponse(emptyList(), false)

        val eventLists = mutableListOf<HomePageList>()

        // Strategy 1: Look for category sections with event cards
        // Common patterns: sections with headings like "Cricket", "Football" etc.
        // containing cards/links to individual events
        val categorySections = extractCategorySections(doc)
        if (categorySections.isNotEmpty()) {
            eventLists.addAll(categorySections)
        }

        // Strategy 2: Fallback — scan for all live-event links on the page
        if (eventLists.isEmpty()) {
            val fallbackItems = extractAllEventLinks(doc)
            if (fallbackItems.isNotEmpty()) {
                eventLists.add(HomePageList("? Live Events", fallbackItems, isHorizontalImages = true))
            }
        }

        // Strategy 3: Try parsing as JSON-driven page (common in modern sports sites)
        if (eventLists.isEmpty()) {
            val jsonItems = extractEventsFromJson(doc)
            if (jsonItems.isNotEmpty()) {
                eventLists.add(HomePageList("? Live Events", jsonItems, isHorizontalImages = true))
            }
        }

        return newHomePageResponse(eventLists, hasNext = false)
    }

    /**
     * Attempts to find category-grouped sections (e.g. "Cricket", "Football").
     * Looks for common HTML patterns:
     *  - h2/h3 headings followed by card grids
     *  - div containers with category class names
     */
    private fun extractCategorySections(doc: Document): List<HomePageList> {
        val lists = mutableListOf<HomePageList>()

        // Try common section patterns
        val possibleSections = doc.select(
            "section, .category, .sport-category, .match-category, " +
            "[class*=category], [class*=sport], .row.mb-4, .section"
        )

        possibleSections.forEach { section ->
            // Find a heading
            val heading = section.select("h1, h2, h3, h4, .heading, .title, .category-title").first()
                ?: section.select("h1, h2, h3, h4").first()
            val categoryName = heading?.text()?.trim() ?: return@forEach
            if (categoryName.isBlank()) return@forEach

            // Find event cards/links within this section
            val cards = section.select(
                "a[href], .card, .match-card, .event-card, .live-card, " +
                "[class*=card], [class*=event], [class*=match], .item, .col"
            )
            val items = cards.mapNotNull { card ->
                val link = card.select("a[href]").first() ?: card.takeIf { it.`is`("a[href]") }
                if (link == null) return@mapNotNull null
                elementToSearchResponse(link)
            }.filterNotNull()

            if (items.isNotEmpty()) {
                val icon = when {
                    categoryName.contains("Cricket", ignoreCase = true) -> "??"
                    categoryName.contains("Football", ignoreCase = true) || categoryName.contains("Soccer", ignoreCase = true) -> "?"
                    categoryName.contains("Basketball", ignoreCase = true) -> "??"
                    categoryName.contains("Tennis", ignoreCase = true) -> "??"
                    categoryName.contains("Hockey", ignoreCase = true) -> "??"
                    categoryName.contains("Boxing", ignoreCase = true) || categoryName.contains("MMA", ignoreCase = true) -> "??"
                    categoryName.contains("Baseball", ignoreCase = true) -> "??"
                    categoryName.contains("Rugby", ignoreCase = true) -> "??"
                    else -> "??"
                }
                lists.add(HomePageList("$icon $categoryName", items, isHorizontalImages = true))
            }
        }

        return lists
    }

    /**
     * Fallback: scan all links on the page that look like event links.
     */
    private fun extractAllEventLinks(doc: Document): List<SearchResponse> {
        return doc.select("a[href]").mapNotNull { link ->
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null
            // Filter for likely event/match links
            val isLikelyEvent = href.contains("live", ignoreCase = true) ||
                href.contains("match", ignoreCase = true) ||
                href.contains("event", ignoreCase = true) ||
                href.contains("stream", ignoreCase = true) ||
                href.contains("watch", ignoreCase = true) ||
                link.select("img").isNotEmpty() ||
                link.text().length > 10

            if (!isLikelyEvent) return@mapNotNull null
            elementToSearchResponse(link)
        }.filterNotNull()
    }

    /**
     * Try to find events embedded in JSON/script tags (common in React/Vue sites).
     */
    private fun extractEventsFromJson(doc: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        doc.select("script").forEach { script ->
            val content = script.html()
            if (content.isBlank()) return@forEach
            // Try to find JSON objects/arrays with event-like data
            try {
                val jsonRegex = Regex("""\{[^}]*"(?:title|name|event|match)"[^}]*\}""")
                jsonRegex.findAll(content).forEach { match ->
                    try {
                        val obj = parseJson<Map<String, Any?>>(match.value)
                        val title = obj["title"]?.toString() ?: obj["name"]?.toString()
                            ?: obj["event"]?.toString() ?: return@forEach
                        val url = obj["url"]?.toString() ?: obj["link"]?.toString()
                            ?: obj["href"]?.toString() ?: return@forEach
                        val poster = obj["image"]?.toString() ?: obj["poster"]?.toString()
                            ?: obj["thumbnail"]?.toString()
                        val category = obj["category"]?.toString() ?: obj["sport"]?.toString()

                        val loadData = EventLoadData(title, url, poster, category)
                        items.add(
                            newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
                                this.posterUrl = poster
                            }
                        )
                    } catch (_: Exception) { /* skip malformed */ }
                }
            } catch (_: Exception) { /* skip */ }
        }
        return items
    }

    // ── Search ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        // Try the site's search endpoint (common patterns)
        val searchPaths = listOf(
            "/search?q=${URLEncoder.encode(query, "UTF-8")}",
            "/search/${URLEncoder.encode(query, "UTF-8")}",
            "/?s=${URLEncoder.encode(query, "UTF-8")}",
            "/find?q=${URLEncoder.encode(query, "UTF-8")}",
        )

        for (path in searchPaths) {
            val doc = fetchDocument("$mainUrl$path") ?: continue
            val results = extractAllEventLinks(doc)
            if (results.isNotEmpty()) return results
        }

        // Fallback: scrape homepage and filter by query
        val doc = fetchDocument("$mainUrl/") ?: return emptyList()
        return extractAllEventLinks(doc).filter { item ->
            item.name.contains(query, ignoreCase = true)
        }
    }

    // ── Load (detail page) ────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        // If url is our JSON-encoded EventLoadData, parse it
        val eventData = try {
            parseJson<EventLoadData>(url)
        } catch (_: Exception) {
            // url is a direct page URL
            EventLoadData(
                title = url.substringAfterLast("/").replace("-", " ").replace("_", " "),
                url = url,
                posterUrl = null,
                category = null
            )
        }

        val doc = fetchDocument(eventData.url)
        val title = doc?.title()?.trim() ?: eventData.title

        // Extract poster
        val posterUrl = doc?.select("meta[property=og:image]")?.attr("content")
            ?.ifBlank { null }
            ?: doc?.select("img.poster, img.thumbnail, img.featured, .event-img img, .match-img img")
                ?.first()?.attr("src")
            ?: eventData.posterUrl

        // Extract description
        val plot = doc?.select("meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: doc?.select("meta[name=description]")?.attr("content")
            ?.ifBlank { null }
            ?: doc?.select(".description, .event-desc, .match-info, .event-info, p")
                ?.firstOrNull { it.text().length > 20 }?.text()

        // Extract stream iframes
        val iframes = doc?.select("iframe")?.mapNotNull { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) StreamInfo(
                name = iframe.attr("title").ifBlank { iframe.attr("name").ifBlank { "Stream" } },
                url = src
            ) else null
        } ?: emptyList()

        // Build stream data
        val streamData = StreamLoadData(title, iframes)

        return newLiveStreamLoadResponse(title, streamData.toJson(), eventData.url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    // ── Load Links (extract stream URLs) ──────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            println("DamiTV: loadLinks parse error — ${e.message}")
            return false
        }

        if (streamData.streams.isEmpty()) return false

        streamData.streams.forEach { stream ->
            val resolvedUrl = stream.url

            when {
                // MPD (DASH) streams
                resolvedUrl.contains(".mpd", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(name, stream.name, resolvedUrl, ExtractorLinkType.DASH) {
                            this.quality = Qualities.Unknown.value
                            if (stream.headers.isNotEmpty()) this.headers = stream.headers
                            this.referer = "$mainUrl/"
                        }
                    )
                }
                // M3U8 (HLS) streams
                resolvedUrl.contains(".m3u8", ignoreCase = true) || resolvedUrl.contains("&e=.m3u") -> {
                    callback.invoke(
                        newExtractorLink(name, stream.name, resolvedUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.Unknown.value
                            if (stream.headers.isNotEmpty()) this.headers = stream.headers
                            this.referer = "$mainUrl/"
                        }
                    )
                }
                // Iframe URLs — try to follow them and extract the embedded stream
                resolvedUrl.startsWith("http") && (
                    resolvedUrl.contains("iframe", ignoreCase = true) ||
                    resolvedUrl.contains("embed", ignoreCase = true) ||
                    resolvedUrl.contains("player", ignoreCase = true) ||
                    resolvedUrl.contains("stream", ignoreCase = true)
                ) -> {
                    val embeddedStreams = extractStreamsFromIframe(resolvedUrl)
                    embeddedStreams.forEach { embStream ->
                        callback.invoke(embStream)
                    }
                }
                // Generic URL — try to infer type
                else -> {
                    callback.invoke(
                        newExtractorLink(name, stream.name, resolvedUrl, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                            if (stream.headers.isNotEmpty()) this.headers = stream.headers
                            this.referer = "$mainUrl/"
                        }
                    )
                }
            }
        }

        return true
    }

    /**
     * Follows an iframe/embed URL and extracts the actual video stream links.
     */
    private suspend fun extractStreamsFromIframe(url: String): List<ExtractorLink> {
        val streams = mutableListOf<ExtractorLink>()

        try {
            val doc = fetchDocument(url, referer = "$mainUrl/") ?: return streams

            // Look for iframe within the iframe (nested)
            doc.select("iframe").forEach { innerIframe ->
                val src = innerIframe.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) {
                    streams.addAll(extractStreamsFromIframe(src))
                }
            }

            // Look for video sources
            doc.select("video source, video").forEach { video ->
                val src = video.attr("src")
                if (src.isNotBlank()) {
                    streams.add(
                        newExtractorLink(name, name, src, INFER_TYPE) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                }
            }

            // Look for stream URLs in scripts
            doc.select("script").forEach { script ->
                val content = script.html()
                // Common patterns: source: "url", file: "url", videoUrl: "url"
                val streamRegex = Regex(
                    """(?:source|file|videoUrl|streamUrl|src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mpd|mp4|ts)[^"']*)["']""",
                    RegexOption.IGNORE_CASE
                )
                streamRegex.findAll(content).forEach { match ->
                    val streamUrl = match.groupValues[1]
                    val type = when {
                        streamUrl.contains(".mpd") -> ExtractorLinkType.DASH
                        streamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                        else -> INFER_TYPE
                    }
                    streams.add(
                        newExtractorLink(name, name, streamUrl, type) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                }
            }

            // If no streams found, pass the iframe URL as-is (CloudStream may handle it)
            if (streams.isEmpty()) {
                streams.add(
                    newExtractorLink(name, name, url, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                    }
                )
            }
        } catch (e: Exception) {
            println("DamiTV: extractStreamsFromIframe failed for $url — ${e.message}")
            // Fallback: pass the iframe URL as-is
            streams.add(
                newExtractorLink(name, name, url, INFER_TYPE) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
        }

        return streams
    }

    // ── Helper: Element → SearchResponse ──────────────────────────────────────

    /**
     * Converts an HTML element (link or card) to a [SearchResponse].
     */
    private fun elementToSearchResponse(element: Element): SearchResponse? {
        val linkEl = if (element.tagName() == "a") element else element.select("a[href]").first()
            ?: return null

        val href = linkEl.attr("href").trim()
        if (href.isBlank() || href == "#") return null

        // Resolve relative URLs
        val fullUrl = if (href.startsWith("http")) href
        else if (href.startsWith("/")) "$mainUrl$href"
        else "$mainUrl/$href"

        // Extract title
        val title = linkEl.attr("title").ifBlank { null }
            ?: linkEl.select("img").attr("alt").ifBlank { null }
            ?: linkEl.text().trim().ifBlank { null }
            ?: href.substringAfterLast("/").replace("-", " ").replace("_", " ")

        // Extract poster image
        val posterUrl = linkEl.select("img").attr("src").ifBlank { null }
            ?: linkEl.select("img").attr("data-src").ifBlank { null }
            ?: element.select("img").attr("src").ifBlank { null }
            ?: element.select("img").attr("data-src").ifBlank { null }

        // Infer category
        val category = linkEl.attr("data-category").ifBlank { null }
            ?: element.attr("data-category").ifBlank { null }
            ?: element.parent()?.select("h1, h2, h3, h4")?.first()?.text()
            ?: element.parents().select("h1, h2, h3, h4").first()?.text()

        val loadData = EventLoadData(title, fullUrl, posterUrl, category)

        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = posterUrl
        }
    }

    // ── Video Interceptor ─────────────────────────────────────────────────────

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                var request = chain.request()

                // Fix encoded slash issues
                val fixedUrl = request.url.toString()
                    .replace(Regex("(?i)%2f"), "/")

                request = request.newBuilder()
                    .url(fixedUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                    )
                    .header("Referer", "$mainUrl/")
                    .build()

                return chain.proceed(request)
            }
        }
    }
}