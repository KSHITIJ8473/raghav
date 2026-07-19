package com.laddu100

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class ReplayZoneProvider : MainAPI() {
    override var mainUrl = "https://replay-exc.pages.dev"
    override var name = "ReplayZone"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val dataUrl = "https://replay.adityapangshe.workers.dev/replays.txt"
    private val workerUrl = "https://replay.adityapangshe.workers.dev"
    private val TAG = "ReplayZone"

    // Parsed replay data
    data class ReplayEmbed(
        val label: String,
        val type: String, // "hls" or "iframe"
        val url: String
    )

    data class Replay(
        val title: String,
        val category: String,
        val sub: String,
        val thumb: String,
        val date: String,
        val embeds: List<ReplayEmbed>
    )

    // Load data passed from search → loadLinks
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val title: String,
        val embeds: List<ReplayEmbed>,
        val posterUrl: String? = null
    )

    // Cache the parsed replays
    private var cachedReplays: List<Replay>? = null

    private suspend fun fetchReplays(): List<Replay> {
        cachedReplays?.let { return it }
        return try {
            val res = app.get(dataUrl, timeout = 30_000L)
            Log.d(TAG, "fetchReplays: HTTP ${res.code}, size=${res.text.length}")
            val text = res.text
            val replays = mutableListOf<Replay>()
            var current: Replay? = null

            for (line in text.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.startsWith("# ")) {
                    // New replay title
                    if (current != null && current.embeds.isNotEmpty()) {
                        replays.add(current)
                    }
                    current = Replay(
                        title = trimmed.substring(2).trim(),
                        category = "", sub = "", thumb = "", date = "",
                        embeds = mutableListOf()
                    )
                } else if (trimmed.startsWith("~ ") && current != null) {
                    // Metadata line: ~ Category\tSub\tThumb\tDate
                    val parts = trimmed.substring(2).split("\t")
                    current = current.copy(
                        category = parts.getOrNull(0)?.trim() ?: "",
                        sub = parts.getOrNull(1)?.trim() ?: "",
                        thumb = parts.getOrNull(2)?.trim() ?: "",
                        date = parts.getOrNull(3)?.trim() ?: ""
                    )
                } else if (trimmed.isNotEmpty() && current != null) {
                    // Embed line: Label\tType\tURL
                    val parts = trimmed.split("\t")
                    if (parts.size >= 3) {
                        val label = parts[0].trim()
                        val type = parts[1].trim()
                        val url = parts[2].trim()
                        if (url.isNotEmpty()) {
                            (current.embeds as MutableList).add(
                                ReplayEmbed(label, if (type == "hls") "hls" else "iframe", url)
                            )
                        }
                    }
                }
            }
            // Don't forget the last one
            if (current != null && current.embeds.isNotEmpty()) {
                replays.add(current)
            }

            Log.d(TAG, "fetchReplays: parsed ${replays.size} replays")
            cachedReplays = replays
            replays
        } catch (e: Exception) {
            Log.e(TAG, "fetchReplays FAILED: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "getMainPage START: section='${request.name}' page=$page")
        val lists = mutableListOf<HomePageList>()

        try {
            val replays = fetchReplays()
            if (replays.isEmpty()) {
                Log.e(TAG, "getMainPage: no replays found")
                return newHomePageResponse(lists, hasNext = false)
            }

            // Sort by date descending (newest first)
            val sorted = replays.sortedByDescending { it.date }

            when (request.name) {
                "All" -> {
                    // Recently Added (top 30)
                    val recent = sorted.take(30)
                    if (recent.isNotEmpty()) {
                        val items = recent.mapNotNull { it.toSearchResponse() }
                        lists.add(HomePageList("🕐 Recently Added", items, isHorizontalImages = true))
                    }

                    // Group by category
                    val categories = listOf("Football", "Baseball", "Rugby", "Motorsport")
                    for (cat in categories) {
                        val catReplays = sorted.filter { it.category == cat }.take(30)
                        if (catReplays.isNotEmpty()) {
                            val emoji = when (cat) {
                                "Football" -> "⚽"
                                "Baseball" -> "⚾"
                                "Rugby" -> "🏉"
                                "Motorsport" -> "🏎"
                                else -> "📺"
                            }
                            val items = catReplays.mapNotNull { it.toSearchResponse() }
                            lists.add(HomePageList("$emoji $cat", items, isHorizontalImages = true))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED: ${e.message}")
        }

        Log.d(TAG, "getMainPage END: ${lists.size} sections, ${lists.sumOf { it.list.size }} items")
        return newHomePageResponse(lists, hasNext = false)
    }

    override val mainPage = mainPageOf(
        dataUrl to "All"
    )

    private fun Replay.toSearchResponse(): SearchResponse? {
        if (title.isBlank()) return null
        val loadData = LoadData(title = title, embeds = embeds, posterUrl = thumb.ifBlank { null })
        return newLiveSearchResponse(title, loadData.toJson(), TvType.Live) {
            this.posterUrl = thumb.ifBlank { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("replayzone") ?: mainUrl
        Log.d(TAG, "search START: query='$query'")
        if (query.isBlank()) return emptyList()
        return try {
            val replays = fetchReplays()
            val results = replays
                .filter { it.title.contains(query, ignoreCase = true) }
                .mapNotNull { it.toSearchResponse() }
            Log.d(TAG, "search END: '$query' -> ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("replayzone") ?: mainUrl
        Log.d(TAG, "load START: url='$url'")
        return try {
            val loadData = parseJson<LoadData>(url)
            Log.d(TAG, "load: title='${loadData.title}' embeds=${loadData.embeds.size}")

            newLiveStreamLoadResponse(loadData.title, url, this.name) {
                this.posterUrl = loadData.posterUrl
                this.plot = "${loadData.embeds.size} sources available"
                this.dataUrl = loadData.toJson()
            }
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val loadData = try {
            parseJson<LoadData>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: parse error: ${e.message}")
            return false
        }

        if (loadData.embeds.isEmpty()) {
            Log.e(TAG, "loadLinks: no embeds")
            return false
        }

        var found = false
        for (embed in loadData.embeds) {
            try {
                when {
                    // HLS type — direct m3u8 (may be soccerfull proxied through worker)
                    embed.type == "hls" -> {
                        val m3u8Url = if (embed.url.contains("soccerfull.net/hls/")) {
                            // Proxy through worker
                            "$workerUrl/hls?u=${java.net.URLEncoder.encode(embed.url, "UTF-8")}"
                        } else {
                            embed.url
                        }
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - ${embed.label}",
                                name = "$name - ${embed.label}",
                                url = m3u8Url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }

                    // soccerfull.net/play/ — scrape the play page for m3u8 URL
                    embed.url.contains("soccerfull.net/play/") -> {
                        try {
                            val playRes = app.get(embed.url, timeout = 15_000L)
                            val playHtml = playRes.text
                            // Extract m3u8 URL from: var m3u8Url = "/hls/XXXXX.m3u8"
                            val m3u8Match = Regex("""m3u8Url\s*=\s*["']([^"']+)["']""").find(playHtml)
                            if (m3u8Match != null) {
                                val m3u8Path = m3u8Match.groupValues[1]
                                val fullM3u8Url = if (m3u8Path.startsWith("http")) {
                                    m3u8Path
                                } else {
                                    "https://soccerfull.net$m3u8Path"
                                }
                                // Proxy through worker since soccerfull.net returns 403 directly
                                val proxiedUrl = "$workerUrl/hls?u=${java.net.URLEncoder.encode(fullM3u8Url, "UTF-8")}"
                                callback.invoke(
                                    newExtractorLink(
                                        source = "$name - ${embed.label}",
                                        name = "$name - ${embed.label}",
                                        url = proxiedUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                found = true
                            } else {
                                Log.e(TAG, "loadLinks: '${embed.label}' no m3u8 found in play page")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "loadLinks: '${embed.label}' soccerfull scrape failed: ${e.message}")
                        }
                    }

                    // ok.ru — built-in extractor
                    embed.url.contains("ok.ru") || embed.url.contains("ok.ru/videoembed") -> {
                        val realUrl = if (embed.url.contains("videoembed")) {
                            embed.url.replace("videoembed/", "video/")
                        } else embed.url
                        val loaded = loadExtractor(realUrl, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            found = true
                        } else {
                            Log.e(TAG, "loadLinks: '${embed.label}' ok.ru loadExtractor failed")
                        }
                    }

                    // dailymotion — extract video ID and build direct URL
                    embed.url.contains("dailymotion.com") -> {
                        val videoId = Regex("""video=([a-zA-Z0-9]+)""").find(embed.url)?.groupValues?.get(1)
                        if (videoId != null) {
                            val dmUrl = "https://www.dailymotion.com/video/$videoId"
                            val loaded = loadExtractor(dmUrl, "$mainUrl/", subtitleCallback, callback)
                            if (loaded) {
                                found = true
                            } else {
                                Log.e(TAG, "loadLinks: '${embed.label}' dailymotion loadExtractor failed")
                            }
                        } else {
                            Log.e(TAG, "loadLinks: '${embed.label}' no dailymotion video ID found")
                        }
                    }

                    // bysesukior.com — try loadExtractor
                    embed.url.contains("bysesukior.com") -> {
                        val loaded = loadExtractor(embed.url, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            found = true
                        } else {
                            Log.e(TAG, "loadLinks: '${embed.label}' bysesukior loadExtractor failed")
                        }
                    }

                    // Direct m3u8 URLs
                    embed.url.contains(".m3u8") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - ${embed.label}",
                                name = "$name - ${embed.label}",
                                url = embed.url,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        found = true
                    }

                    // Direct mp4 URLs
                    embed.url.contains(".mp4") -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "$name - ${embed.label}",
                                name = "$name - ${embed.label}",
                                url = embed.url,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                        found = true
                    }

                    // Fallback — try loadExtractor for any other URL
                    else -> {
                        val loaded = loadExtractor(embed.url, "$mainUrl/", subtitleCallback, callback)
                        if (loaded) {
                            found = true
                            Log.d(TAG, "loadLinks: '${embed.label}' resolved via fallback")
                        } else {
                            Log.e(TAG, "loadLinks: '${embed.label}' fallback failed")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks: '${embed.label}' FAILED: ${e.message}")
            }
        }

        Log.d(TAG, "loadLinks END: found=$found")
        return found
    }
}
