package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.util.*

/**
 * PpvPlugin Provider - Live sports from ppv.to
 *
 * This provider fetches the public API at https://api.ppv.to/api/streams and lists live matches.
 * Each entry links to the iframe URL returned by the API. The iframe is used directly as an
 * ExtractorLink – CloudStream's internal WebView resolver will handle any additional JS
 * decryption if required.
 */
open class PpvProvider : MainAPI() {
    override var mainUrl = "https://ppv.to/"
    override var name = "PPV Live"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    // Simple data class that will be stored in the SearchResponse loadData field
    data class LoadData(
        val title: String,
        val iframe: String,
        val poster: String?
    )

    /** Helper to fetch the API and return the raw JSON string */
    private suspend fun fetchApi(): String? {
        return try {
            val resp = app.get("https://api.ppv.to/api/streams", timeout = 30L)
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            null
        }
    }

    /** Convert a JSON object representing a single stream into a SearchResponse */
    private fun jsonToSearch(obj: JSONObject): SearchResponse? {
        val title = obj.optString("name")
        if (title.isBlank()) return null
        val iframe = obj.optString("iframe")
        if (iframe.isBlank()) return null
        val poster = obj.optString("poster", "")
        // Store minimal LoadData as JSON string for later loading
        val load = JSONObject().apply {
            put("title", title)
            put("iframe", iframe)
            if (poster.isNotBlank()) put("poster", poster)
        }.toString()
        return newLiveSearchResponse(
            title,
            load,
            TvType.Live
        ) {
            this.posterUrl = poster
        }
    }

    /** List live events on the home page */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val jsonStr = fetchApi() ?: return newHomePageResponse(emptyList())
        val root = JSONObject(jsonStr)
        val streamsArray = root.optJSONArray("streams") ?: return newHomePageResponse(emptyList())
        val now = System.currentTimeMillis() / 1000
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until streamsArray.length()) {
            val catObj = streamsArray.optJSONObject(i) ?: continue
            val catStreams = catObj.optJSONArray("streams") ?: continue
            for (j in 0 until catStreams.length()) {
                val streamObj = catStreams.optJSONObject(j) ?: continue
                val starts = streamObj.optLong("starts_at", 0)
                val ends = streamObj.optLong("ends_at", Long.MAX_VALUE)
                if (now >= starts && now <= ends) {
                    jsonToSearch(streamObj)?.let { items.add(it) }
                }
            }
        }
        return if (items.isEmpty()) {
            val dummyLoad = JSONObject().apply {
                put("title", "No live matches right now")
                put("iframe", "dummy")
                put("poster", "https://i.imgur.com/6X8K9RM.png")
            }.toString()
            val dummyItem = newLiveSearchResponse(
                "No live matches right now",
                dummyLoad,
                TvType.Live
            ) {
                this.posterUrl = "https://i.imgur.com/6X8K9RM.png"
            }
            val list = HomePageList("No live matches right now. Please check back later!", listOf(dummyItem), isHorizontalImages = true)
            newHomePageResponse(listOf(list), hasNext = false)
        } else {
            val list = HomePageList("Live Matches (PPV)", items, isHorizontalImages = true)
            newHomePageResponse(listOf(list), hasNext = false)
        }
    }

    /** Load details – return a simple description page */
    override suspend fun load(url: String): LoadResponse? {
        // The url argument is the JSON string we stored in the SearchResponse
        val obj = try { JSONObject(url) } catch (e: Exception) { null } ?: return null
        val title = obj.optString("title")
        val iframe = obj.optString("iframe")
        val poster = obj.optString("poster", "")
        if (iframe == "dummy") {
            return newLiveStreamLoadResponse(title, iframe, this.name) {
                this.posterUrl = if (poster.isNotBlank()) poster else null
                this.plot = "There are no live PPV streams scheduled at the moment. Please check back when a match is live!"
                this.dataUrl = url
            }
        }
        return newLiveStreamLoadResponse(title, iframe, this.name) {
            this.posterUrl = if (poster.isNotBlank()) poster else null
            this.plot = "Live stream from PPV. Open the link to start playback."
            this.dataUrl = url
        }
    }

    /** Extract the actual video link – we simply return the iframe URL. */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = try { JSONObject(data) } catch (e: Exception) { return false }
        val title = obj.optString("title")
        val iframe = obj.optString("iframe")
        if (iframe.isBlank() || iframe == "dummy") return false
        callback(
            newExtractorLink(
                source = name,
                name = title,
                url = iframe,
                type = ExtractorLinkType.VIDEO
            )
        )
        return true
    }
}

// Helper data class – only used for JSON serialization via JSONObject
private data class LoadData(val title: String, val iframe: String, val poster: String? = null)
