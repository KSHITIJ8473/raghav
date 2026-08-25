package com.laddu100

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.Pair
import okhttp3.OkHttpClient

class LIVETVLiveEventsProvider(
    private val customName: String = "⚡LIVE TV Live Events",
    private val customCatLink: String? = null
) : MainAPI() {

    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://adsflw.xyz"
    override var name = customName
    override var lang = "hi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun createDisplayTitle(event: LIVELiveEventData): String {
        val info = event.eventInfo ?: return event.title
        return if (!info.teamA.isNullOrBlank() && !info.teamB.isNullOrBlank() &&
            info.teamA != info.teamB
        ) "${info.teamA} vs ${info.teamB}"
        else info.teamA ?: event.title
    }

    private fun getEventStatus(event: LIVELiveEventData): String {
        val info = event.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end = info.endTime?.let { fmt.parse(it)?.time }
            when {
                end != null && now >= end -> "✅"
                start != null && now >= start -> "🔴"
                start != null && now < start -> "🔜"
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isEventLive(event: LIVELiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val start = info.startTime?.let { fmt.parse(it)?.time }
            val end = info.endTime?.let { fmt.parse(it)?.time }
            if (end != null && now >= end) false
            else start != null && now >= start
        } catch (_: Exception) {
            false
        }
    }

    private fun isEventEnded(event: LIVELiveEventData): Boolean {
        val info = event.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val end = info.endTime?.let { fmt.parse(it)?.time }
            end != null && now >= end
        } catch (_: Exception) {
            false
        }
    }

    private fun generateMatchCardUrl(event: LIVELiveEventData): String {
        val info = event.eventInfo
        val encode: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }

        val title = encode(info?.eventName ?: event.title)
        val teamA = encode(info?.teamA ?: "Team A")
        val teamB = encode(info?.teamB ?: "Team B")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val eventLogo = info?.eventLogo ?: ""
        val isLive = isEventLive(event)
        val isEnded = isEventEnded(event)

        val time = try {
            info?.startTime?.let {
                val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val disp = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
                df.parse(it)?.let { d -> encode(disp.format(d)) } ?: ""
            } ?: ""
        } catch (_: Exception) {
            ""
        }

        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$title")
            append("&teamA=$teamA")
            append("&teamB=$teamB")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (eventLogo.isNotBlank()) append("&eventLogo=$eventLogo")
            if (time.isNotBlank()) append("&time=$time")
            append("&isLive=$isLive")
            append("&isEnded=$isEnded")
        }
    }

    data class LiveEventLoadData(
        val eventId: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val formats: List<LIVELiveEventFormat>,
        val eventInfo: LIVELiveEventInfo?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val events = if (customCatLink != null) {
            LIVETVProviderManager.fetchCustomEvents(customCatLink)
        } else {
            LIVETVProviderManager.fetchLiveEvents()
        }
        val grouped = events.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val pages = grouped
            .map { (category, catEvents) ->
                val icon = when (category.lowercase()) {
                    "cricket" -> "🏏"
                    "football" -> "⚽"
                    "basketball" -> "🏀"
                    "ice hockey" -> "🏒"
                    "boxing" -> "🥊"
                    "motorsport" -> "🏎️"
                    "tennis" -> "🎾"
                    else -> "📺"
                }
                val items = catEvents
                    .sortedByDescending { isEventLive(it) }
                    .map { event ->
                        val displayTitle = createDisplayTitle(event)
                        val status = getEventStatus(event)
                        val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                        val poster = generateMatchCardUrl(event)
                        val loadData = LiveEventLoadData(
                            eventId = event.id,
                            title = displayTitle,
                            poster = poster,
                            slug = event.slug,
                            formats = event.formats ?: emptyList(),
                            eventInfo = event.eventInfo
                        )
                        newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
                            this.posterUrl = poster
                        }
                    }
                HomePageList("$icon $category", items, isHorizontalImages = true)
            }
            .sortedBy { list ->
                when {
                    list.name.contains("Cricket", ignoreCase = true) -> 0
                    list.name.contains("Football", ignoreCase = true) -> 1
                    list.name.contains("Basketball", ignoreCase = true) -> 2
                    else -> 10
                }
            }

        return newHomePageResponse(pages, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val events = if (customCatLink != null) {
            LIVETVProviderManager.fetchCustomEvents(customCatLink)
        } else {
            LIVETVProviderManager.fetchLiveEvents()
        }
        return events
            .filter { event ->
                listOfNotNull(
                    event.title,
                    event.eventInfo?.teamA,
                    event.eventInfo?.teamB,
                    event.eventInfo?.eventName,
                    event.eventInfo?.eventType
                ).joinToString(" ").contains(query, ignoreCase = true)
            }
            .map { event ->
                val displayTitle = createDisplayTitle(event)
                val status = getEventStatus(event)
                val fullTitle = if (status.isNotBlank()) "$status $displayTitle" else displayTitle
                val poster = generateMatchCardUrl(event)
                val loadData = LiveEventLoadData(
                    eventId = event.id,
                    title = displayTitle,
                    poster = poster,
                    slug = event.slug,
                    formats = event.formats ?: emptyList(),
                    eventInfo = event.eventInfo
                )
                newLiveSearchResponse(fullTitle, loadData.toJson(), TvType.Live) {
                    this.posterUrl = poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LiveEventLoadData>(url)
        val info = data.eventInfo
        val plot = buildString {
            info?.let { i ->
                i.eventType?.let { append("📌 $it\n") }
                i.eventName?.let { append("🏆 $it\n") }
                i.startTime?.let {
                    try {
                        val df = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val disp = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        df.parse(it)?.let { d -> append("🕐 ${disp.format(d)}\n") }
                    } catch (_: Exception) {
                        append("🕐 $it\n")
                    }
                }
            }
            append("\n📡 Available Servers: ${data.formats.size}")
        }
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LiveEventLoadData>(data)
        val streams = LIVETVProviderManager.fetchChannelStreams(loadData.slug)
        if (streams.isNullOrEmpty()) return false
        streams.forEach { stream ->
            val serverName = stream.name ?: "Server"
            val streamLink = stream.link ?: return@forEach
            val (url, headers) = parseStreamLink(streamLink)
            if (url.isBlank()) return@forEach

            try {
                when {
                    url.contains(".mpd") -> {
                        val drmInfo = stream.api?.split(":")
                        if (drmInfo != null && drmInfo.size == 2) {
                            val kidBase64 = hexToBase64(drmInfo[0])
                            val keyBase64 = hexToBase64(drmInfo[1])
                            callback.invoke(
                                newDrmExtractorLink(
                                    this.name, serverName, url,
                                    INFER_TYPE, CLEARKEY_UUID
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    this.key = keyBase64
                                    this.kid = kidBase64
                                    if (headers.isNotEmpty()) this.headers = headers
                                }
                            )
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    this.name, serverName, url,
                                    ExtractorLinkType.DASH
                                ) {
                                    this.quality = Qualities.Unknown.value
                                    if (headers.isNotEmpty()) this.headers = headers
                                }
                            )
                        }
                    }
                    else -> {
                        val finalHeaders = headers.toMutableMap()
                        if (!finalHeaders.containsKey("User-Agent")) {
                            finalHeaders["User-Agent"] =
                                "Mozilla/5.0 (Linux; Android 10; Pixel 3 XL) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/122.0.0.0 Mobile Safari/537.36"
                        }
                        callback.invoke(
                            newExtractorLink(
                                this.name, serverName, url,
                                ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                                if (finalHeaders.isNotEmpty()) this.headers = finalHeaders
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("LIVETV", "stream: ${e.message}")
            }
        }
        return true
    }

    private fun parseStreamLink(link: String): Pair<String, Map<String, String>> {
        val headers = linkedMapOf<String, String>()
        if (!link.contains("|")) {
            return Pair(link, headers)
        }
        val parts = link.split("|", limit = 2)
        val url = parts[0]
        if (parts.size > 1) {
            val headerPart = parts[1]
            headerPart.split("&").forEach { headerPair ->
                val keyValue = headerPair.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = keyValue[0].trim()
                    val value = keyValue[1].trim()
                    val headerName = when (key.lowercase(Locale.ROOT)) {
                        "cookie" -> "Cookie"
                        "origin" -> "Origin"
                        "user-agent" -> "User-Agent"
                        "referer" -> "Referer"
                        else -> key
                    }
                    headers[headerName] = value
                }
            }
        }
        return Pair(url, headers)
    }

    private fun hexToBase64(hex: String): String {
        val bytes = hex.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
