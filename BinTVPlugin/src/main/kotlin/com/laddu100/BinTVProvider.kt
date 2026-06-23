package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

class BinTVProvider : MainAPI() {

    override var mainUrl = "https://www.bintv.net"
    override var name = "BinTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    // Data classes for internal state passing
    data class MatchSource(
        val name: String,
        val url: String,
        val type: String = "direct"
    )

    data class EventLoadData(
        val title: String,
        val poster: String?,
        val date: Long?,
        val endsAt: Long?,
        val category: String,
        val sources: List<MatchSource>,
        val isPPV: Boolean,
        val isBinTV: Boolean,
        val status: String? = null
    )

    data class StreamLoadData(
        val title: String,
        val streams: List<StreamInfo>
    )

    data class StreamInfo(
        val name: String,
        val url: String
    )

    // Data classes for parsing PPV API
    data class PpvStreamGroup(
        @JsonProperty("category") val category: String?,
        @JsonProperty("streams") val streams: List<PpvStream>?
    )

    data class PpvStream(
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String?,
        @JsonProperty("tag") val tag: String?,
        @JsonProperty("source_tag") val source_tag: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("starts_at") val starts_at: Long?,
        @JsonProperty("ends_at") val ends_at: Long?,
        @JsonProperty("always_live") val always_live: Int?,
        @JsonProperty("locale") val locale: String?,
        @JsonProperty("category_name") val category_name: String?,
        @JsonProperty("iframe") val iframe: String?,
        @JsonProperty("viewers") val viewers: String?,
        @JsonProperty("substreams") val substreams: List<PpvSubstream>?
    )

    data class PpvSubstream(
        @JsonProperty("id") val id: Long,
        @JsonProperty("name") val name: String?,
        @JsonProperty("tag") val tag: String?,
        @JsonProperty("uri_name") val uri_name: String?,
        @JsonProperty("source_tag") val source_tag: String?,
        @JsonProperty("locale") val locale: String?,
        @JsonProperty("iframe") val iframe: String?
    )

    data class PpvStreamsResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("streams") val streams: List<PpvStreamGroup>?
    )

    // Data classes for parsing streamed JSON extras
    data class ExtraMatch(
        @JsonProperty("title") val title: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("url") val url: List<ExtraSource>?
    )

    data class ExtraSource(
        @JsonProperty("source") val source: String,
        @JsonProperty("url") val url: String
    )

    data class ExtraConfig(
        @JsonProperty("matches") val matches: List<ExtraMatch>?
    )

    private fun slugify(text: String): String {
        return text.lowercase()
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""[^\w\-]"""), "")
            .replace(Regex("""\-\-+"""), "-")
            .trim('-')
    }

    private fun formatMatchDate(timestamp: Long?): String {
        if (timestamp == null) return "soon"
        return try {
            val date = java.util.Date(timestamp)
            val sdf = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(date)
        } catch (e: Exception) {
            "soon"
        }
    }

    // ── Main Page ─────────────────────────────────────────────────────────

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        // 1. Fetch extras config maps (posters and high priority sources)
        val extrasMap = mutableMapOf<String, ExtraMatch>()
        try {
            val extraText = app.get("https://prabashsapkota.github.io/Streamed-images-json/index.json", timeout = 15L).text
            val extraConfig = parseJson<ExtraConfig>(extraText)
            extraConfig.matches?.forEach { m ->
                val title = m.title?.trim()?.lowercase()
                if (!title.isNullOrBlank()) {
                    extrasMap[title] = m
                }
            }
        } catch (e: Exception) {
            println("BinTV: failed to load extras - ${e.message}")
        }

        // 2. Fetch BinTV matches
        val bintvMatches = mutableListOf<EventLoadData>()
        try {
            val bintvText = app.get("https://prabashsapkota.github.io/bintvjson/index.json", timeout = 15L).text
            val bintvList = parseJson<List<Map<String, Any>>>(bintvText)
            bintvList.forEach { item ->
                val name = item["name"] as? String ?: ""
                if (name.isBlank()) return@forEach
                val logo = item["logo"] as? String
                val category = item["category"] as? String ?: "Other"
                val time = item["time"] as? String ?: ""
                val isLive = time.lowercase() == "live"
                val dateMs = if (isLive) System.currentTimeMillis() else (System.currentTimeMillis() + 86400000)

                val sources = mutableListOf<MatchSource>()
                val urlVal = item["url"] as? String
                if (!urlVal.isNullOrBlank()) {
                    sources.add(MatchSource("Stream 1", urlVal))
                }
                item.forEach { (key, value) ->
                    if (key.startsWith("url_") && value is String && value.isNotBlank()) {
                        val srcName = key.substring(4).replace("_", " ")
                        sources.add(MatchSource(srcName, value))
                    }
                }

                bintvMatches.add(
                    EventLoadData(
                        title = name,
                        poster = logo,
                        date = dateMs,
                        endsAt = null,
                        category = category,
                        sources = sources,
                        isPPV = false,
                        isBinTV = true,
                        status = time
                    )
                )
            }
        } catch (e: Exception) {
            println("BinTV: failed to load bintvjson - ${e.message}")
        }

        // Apply extras to BinTV matches
        bintvMatches.forEachIndexed { index, m ->
            val key = m.title.trim().lowercase()
            val extra = extrasMap[key]
            if (extra != null) {
                val updatedPoster = extra.poster ?: m.poster
                val updatedSources = m.sources.toMutableList()
                extra.url?.forEach { es ->
                    updatedSources.add(0, MatchSource(es.source, es.url))
                }
                bintvMatches[index] = m.copy(poster = updatedPoster, sources = updatedSources)
            }
        }

        // 3. Fetch PPV matches
        val ppvMatches = mutableListOf<EventLoadData>()
        try {
            val ppvText = app.get("https://api.ppv.to/api/streams", timeout = 15L).text
            val ppvResponse = parseJson<PpvStreamsResponse>(ppvText)
            if (ppvResponse.success && !ppvResponse.streams.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                ppvResponse.streams.forEach { group ->
                    if (group.category == "24/7 Streams") return@forEach
                    group.streams?.forEach { stream ->
                        val name = stream.name ?: ""
                        if (name.isBlank()) return@forEach
                        val startMs = (stream.starts_at ?: 0) * 1000L
                        val endMs = (stream.ends_at ?: 0) * 1000L
                        // Skip matches that ended more than 10 mins ago
                        if (stream.ends_at != null && endMs < now - 600000L) return@forEach

                        val iframes = mutableListOf<MatchSource>()
                        if (!stream.iframe.isNullOrBlank()) {
                            iframes.add(MatchSource(stream.source_tag ?: "Admin", stream.iframe))
                        }
                        stream.substreams?.forEach { sub ->
                            if (!sub.iframe.isNullOrBlank() && iframes.none { it.url == sub.iframe }) {
                                iframes.add(MatchSource(sub.source_tag ?: "Admin", sub.iframe))
                            }
                        }

                        if (iframes.isEmpty()) return@forEach

                        val numberedSources = iframes.mapIndexed { idx, src ->
                            MatchSource(
                                name = if (src.name == "Admin") "Stream ${idx + 1}" else "${src.name} - Stream ${idx + 1}",
                                url = src.url
                            )
                        }

                        ppvMatches.add(
                            EventLoadData(
                                title = name,
                                poster = stream.poster,
                                date = startMs,
                                endsAt = endMs,
                                category = stream.category_name ?: group.category ?: "Other",
                                sources = numberedSources,
                                isPPV = true,
                                isBinTV = false
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("BinTV: failed to load PPV matches - ${e.message}")
        }

        // Apply extras to PPV matches
        ppvMatches.forEachIndexed { index, m ->
            val key = m.title.trim().lowercase()
            val extra = extrasMap[key]
            if (extra != null) {
                val updatedPoster = extra.poster ?: m.poster
                val updatedSources = m.sources.toMutableList()
                extra.url?.forEach { es ->
                    updatedSources.add(0, MatchSource(es.source, es.url))
                }
                ppvMatches[index] = m.copy(poster = updatedPoster, sources = updatedSources)
            }
        }

        // Merge matches, deduplicating by slugified title
        val existingTitles = bintvMatches.map { slugify(it.title) }.toSet()
        val filteredPpv = ppvMatches.filter { !existingTitles.contains(slugify(it.title)) }
        val allMatches = bintvMatches + filteredPpv

        // 4. Split into Live and Upcoming
        val now = System.currentTimeMillis()
        val liveList = mutableListOf<EventLoadData>()
        val upcomingList = mutableListOf<EventLoadData>()

        allMatches.forEach { m ->
            val isLive = if (m.isBinTV) {
                m.status?.lowercase() == "live"
            } else {
                val start = m.date ?: 0L
                val end = m.endsAt ?: Long.MAX_VALUE
                (now >= start - 3600000L && now <= end)
            }
            if (isLive) {
                liveList.add(m)
            } else {
                upcomingList.add(m)
            }
        }

        // Helper to convert Match data to SearchResponse
        fun matchToSearch(m: EventLoadData, prefix: String = ""): SearchResponse {
            val title = if (prefix.isNotEmpty()) "$prefix ${m.title}" else m.title
            return newLiveSearchResponse(title, m.toJson(), TvType.Live) {
                this.posterUrl = m.poster
            }
        }

        // 5. Populate sections
        // FIFA World Cup Live
        val wcLive = liveList.filter { m ->
            m.title.contains("world cup", ignoreCase = true) ||
            m.title.contains("fifa", ignoreCase = true) ||
            m.category.contains("world cup", ignoreCase = true)
        }
        if (wcLive.isNotEmpty()) {
            val items = wcLive.map { matchToSearch(it) }
            lists.add(HomePageList("🏆 FIFA World Cup - Live", items, isHorizontalImages = true))
        }

        // Sport Live sections
        val sports = listOf(
            Pair("Football", "🟢 Live Football"),
            Pair("Basketball", "🟢 Live Basketball"),
            Pair("Cricket", "🟢 Live Cricket"),
            Pair("Tennis", "🟢 Live Tennis"),
            Pair("Fight", "🟢 Live Fight / Combat")
        )

        sports.forEach { (catKey, sectionName) ->
            val sportMatches = liveList.filter { m ->
                !wcLive.contains(m) && (
                    m.category.contains(catKey, ignoreCase = true) ||
                    (catKey == "Fight" && (
                        m.category.contains("wrestling", ignoreCase = true) ||
                        m.category.contains("combat", ignoreCase = true) ||
                        m.category.contains("boxing", ignoreCase = true)
                    ))
                )
            }
            if (sportMatches.isNotEmpty()) {
                val items = sportMatches.map { matchToSearch(it) }
                lists.add(HomePageList(sectionName, items, isHorizontalImages = true))
            }
        }

        // Other Live Sports
        val otherLive = liveList.filter { m ->
            !wcLive.contains(m) && sports.none { (catKey, _) ->
                m.category.contains(catKey, ignoreCase = true) ||
                (catKey == "Fight" && (
                    m.category.contains("wrestling", ignoreCase = true) ||
                    m.category.contains("combat", ignoreCase = true) ||
                    m.category.contains("boxing", ignoreCase = true)
                ))
            }
        }
        if (otherLive.isNotEmpty()) {
            val items = otherLive.map { matchToSearch(it) }
            lists.add(HomePageList("🟢 Live Other Sports", items, isHorizontalImages = true))
        }

        // FIFA World Cup Upcoming
        val wcUpcoming = upcomingList.filter { m ->
            m.title.contains("world cup", ignoreCase = true) ||
            m.title.contains("fifa", ignoreCase = true) ||
            m.category.contains("world cup", ignoreCase = true)
        }
        if (wcUpcoming.isNotEmpty()) {
            val items = wcUpcoming.map { matchToSearch(it, "Upcoming:") }
            lists.add(HomePageList("🏆 FIFA World Cup - Upcoming", items, isHorizontalImages = true))
        }

        // General Upcoming Sports Schedule
        val otherUpcoming = upcomingList.filter { !wcUpcoming.contains(it) }
            .sortedBy { it.date ?: 0L }
        if (otherUpcoming.isNotEmpty()) {
            val items = otherUpcoming.map { m ->
                val dateStr = formatMatchDate(m.date)
                val displayTitle = "${m.title} [Starts: $dateStr]"
                newLiveSearchResponse(displayTitle, m.toJson(), TvType.Live) {
                    this.posterUrl = m.poster
                }
            }
            lists.add(HomePageList("📅 Upcoming Sports Schedule", items, isHorizontalImages = true))
        }

        // Empty State: Add a notification item if no live streams exist at all
        if (liveList.isEmpty()) {
            val dummyData = EventLoadData(
                title = "No live matches right now",
                poster = "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEi9H8iKbJ5ngROc8c8npv__rsAbMJDRjNNJFL3LrnstW0SWeQn9sQdb-_6wyTQPAL9P_B9n_DRjs3G1srZJTaaBH9LTqG2B1LWdvKkD-E8BRVjaY408MmWJPcinCS4cxFrOPMRlgoREqEs8sNCnQfpXEr0RmxjjPMn0GvWJXdJF1zov3pa7FgCwDOJ6_Q/s1853/bintv.png",
                date = null,
                endsAt = null,
                category = "None",
                sources = emptyList(),
                isPPV = false,
                isBinTV = false
            )
            val dummyItem = newLiveSearchResponse(
                "No live matches right now. Please check back later!",
                dummyData.toJson(),
                TvType.Live
            ) {
                this.posterUrl = dummyData.poster
            }
            lists.add(0, HomePageList("🟢 Live Sports", listOf(dummyItem), isHorizontalImages = true))
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        // Search matches simply by matching the title in the main feed
        return emptyList() // The plugin primarily relies on the mainpage categorizations
    }

    // ── Load ──────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val eventData = parseJson<EventLoadData>(url)
        val title = eventData.title
        val poster = eventData.poster

        if (title == "No live matches right now") {
            return newLiveStreamLoadResponse(title, url, this.name) {
                this.posterUrl = poster
                this.plot = "There are no live matches broadcasting at the moment. Please check the upcoming schedule list."
                this.dataUrl = url
            }
        }

        val streams = eventData.sources.map { src ->
            StreamInfo(name = src.name, url = src.url)
        }

        val streamData = StreamLoadData(title, streams)

        return newLiveStreamLoadResponse(title, url, this.name) {
            this.posterUrl = poster
            this.dataUrl = streamData.toJson()
        }
    }

    // ── Load Links ────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamData = try {
            parseJson<StreamLoadData>(data)
        } catch (e: Exception) {
            return false
        }

        if (streamData.streams.isEmpty()) return false

        var foundAny = false

        streamData.streams.forEach { stream ->
            try {
                // If it is a noooooads wrapper URL:
                // e.g. https://prabashsapkota.github.io/noooooads/?src=https://xyzstreams.shop/wc-5-embed
                var embedUrl = stream.url
                if (embedUrl.contains("noooooads/?src=")) {
                    val extracted = embedUrl.substringAfter("noooooads/?src=").substringBefore("&")
                    if (extracted.isNotBlank()) {
                        embedUrl = java.net.URLDecoder.decode(extracted, "UTF-8")
                    }
                }

                // If it is a direct m3u8 playlist link
                if (embedUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = stream.name,
                            url = embedUrl,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    foundAny = true
                } else if (embedUrl.contains("embedindia.st") || embedUrl.contains("ppv.to")) {
                    // Standard PPV embedindia iframe, load it via standard loadExtractor
                    try {
                        loadExtractor(embedUrl, "https://ppv.to/", subtitleCallback, callback)
                        foundAny = true
                    } catch (e: Exception) {
                        println("BinTV: loadExtractor failed for PPV stream - ${e.message}")
                    }
                } else {
                    // Parse the embed page HTML to extract direct M3U8 source links
                    val embedHost = try {
                        val uri = java.net.URI(embedUrl)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) {
                        null
                    }

                    val headers = if (embedHost != null) {
                        mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                            "Referer" to "$embedHost/"
                        )
                    } else {
                        mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }

                    val embedHtml = app.get(embedUrl, headers = headers, timeout = 15L).text

                    // Search for .m3u8 links
                    val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                    val matches = m3u8Pattern.findAll(embedHtml)
                    var foundM3u8 = false
                    matches.forEachIndexed { idx, match ->
                        val m3u8Url = match.value
                            .replace("\\u0026", "&")
                            .replace("\\/", "/")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = if (idx == 0) stream.name else "${stream.name} (Alt ${idx})",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                if (embedHost != null) {
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                                        "Referer" to "$embedHost/"
                                    )
                                }
                            }
                        )
                        foundM3u8 = true
                        foundAny = true
                    }

                    // Fallback to loadExtractor if direct m3u8 extraction failed
                    if (!foundM3u8) {
                        try {
                            loadExtractor(embedUrl, "$embedHost/", subtitleCallback, callback)
                            foundAny = true
                        } catch (e: Exception) {
                            println("BinTV: fallback loadExtractor failed - ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("BinTV: Failed to load stream link - ${e.message}")
            }
        }

        return foundAny
    }
}
