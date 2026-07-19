package com.playfy

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * PlayFy Live Events Provider — MAIN provider (always visible)
 *
 * Shows live sports events (cricket, football, etc.)
 * Fetches from: globalmediapoint.online/cats/live-events.json
 */
class PlayFyLiveEventsProvider : MainAPI() {
    override var mainUrl = PlayFyAPI.BASE_URL
    override var name = "PlayFy TV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val TAG = "PlayFy"

    override val mainPage = mainPageOf(
        Pair("cats/live-events.json", "Live Now"),
        Pair("cats/highlights.json", "Highlights")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val decrypted = PlayFyAPI.fetchDecrypted(request.data)
            if (decrypted == null) {
                Log.e(TAG, "getMainPage: Decryption returned null — helper API not running?")
                return newHomePageResponse(request.name, emptyList(), hasNext = false)
            }

            val events = parseJson<PlayFyAPI.EventsResponse>(decrypted)

            val home = events.events?.mapNotNull { event ->
                val id = event.id ?: return@mapNotNull null
                val title = createDisplayTitle(event)
                if (title.isBlank()) return@mapNotNull null

                Log.d(TAG, "getMainPage: Event: $title (id=$id)")
                newLiveSearchResponse(title, "$mainUrl/channel/$id", TvType.Live) {
                    this.posterUrl = event.image
                }
            } ?: emptyList()

            Log.d(TAG, "getMainPage: Built ${home.size} search responses")
            newHomePageResponse(request.name, home, hasNext = false)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: FAILED: " + e.message)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search: query='$query'")
        return try {
            val decrypted = PlayFyAPI.fetchDecrypted("cats/live-events.json") ?: return emptyList()
            val events = parseJson<PlayFyAPI.EventsResponse>(decrypted)
            events.events?.filter { event ->
                createDisplayTitle(event).contains(query, ignoreCase = true)
            }?.mapNotNull { event ->
                val id = event.id ?: return@mapNotNull null
                val title = createDisplayTitle(event)
                newLiveSearchResponse(title, "$mainUrl/channel/$id", TvType.Live) {
                    this.posterUrl = event.image
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "search: FAILED: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(TAG, "load: url=$url")
        val id = url.substringAfterLast("/").toIntOrNull() ?: return null
        return newLiveStreamLoadResponse("PlayFy TV", url, url) {
            this.plot = "Live TV channel"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.substringAfterLast("/").toIntOrNull() ?: run {
            Log.e(TAG, "loadLinks: Could not parse ID from $data")
            return false
        }

        val endpoint = "channels/$id.json"
        val decrypted = PlayFyAPI.fetchDecrypted(endpoint) ?: run {
            Log.e(TAG, "loadLinks: Decryption failed")
            return false
        }

        val streams = try {
            parseJson<List<PlayFyAPI.StreamUrl>>(decrypted)
        } catch (e: Exception) {
            try {
                parseJson<PlayFyAPI.ChannelStreamResponse>(decrypted).streamUrls
            } catch (e2: Exception) {
                Log.e(TAG, "loadLinks: Failed to parse streams: ${e2.message}")
                null
            }
        }

        if (streams.isNullOrEmpty()) {
            Log.e(TAG, "loadLinks: No streams found")
            return false
        }

        streams.forEach { stream ->
            val streamLink = stream.link ?: return@forEach
            val serverName = stream.title ?: "Server"

            val (url, headers) = PlayFyAPI.parseStreamLink(streamLink)
            if (url.isBlank()) {
                return@forEach
            }

            val type = stream.type ?: 0
            when (type) {
                1, 7 -> {
                    // MPD with DRM (ClearKey)
                    val drmApi = stream.api
                    if (!drmApi.isNullOrEmpty() && drmApi.contains(":")) {
                        val parts = drmApi.split(":")
                        if (parts.size == 2) {
                            val kid = parts[0].replace("-", "")
                            val key = parts[1].replace("-", "")

                            val kidBase64 = PlayFyAPI.hexToBase64Url(kid)
                            val keyBase64 = PlayFyAPI.hexToBase64Url(key)

                            callback.invoke(
                                newDrmExtractorLink(
                                    this.name,
                                    serverName,
                                    url,
                                    INFER_TYPE,
                                    CLEARKEY_UUID
                                ) {
                                    this.kid = kidBase64
                                    this.key = keyBase64
                                    if (headers.isNotEmpty()) {
                                        this.headers = headers
                                    }
                                }
                            )
                        }
                    } else {
                        callback.invoke(
                            newExtractorLink(this.name, serverName, url, type = INFER_TYPE) {
                                if (headers.isNotEmpty()) {
                                    this.headers = headers
                                }
                            }
                        )
                    }
                }
                else -> {
                    // m3u8 or mp4
                    callback.invoke(
                        newExtractorLink(this.name, serverName, url, type = INFER_TYPE) {
                            if (headers.isNotEmpty()) {
                                this.headers = headers
                            }
                        }
                    )
                    Log.d(TAG, "loadLinks:  Added stream link: $serverName")
                }
            }
        }

        Log.d(TAG, "loadLinks: DONE, returned ${streams.size} streams")
        return streams.isNotEmpty()
    }

    private fun createDisplayTitle(event: PlayFyAPI.EventItem): String {
        val info = event.eventInfo
        return if (info != null && !info.teamA.isNullOrBlank() && !info.teamB.isNullOrBlank()) {
            if (info.teamA == info.teamB) info.teamA ?: "Event"
            else "${info.teamA} vs ${info.teamB}"
        } else {
            event.title ?: "Event"
        }
    }
}
