package com.laddu100

import android.util.Base64
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
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Live sports events sourced from the PlayZTV backend.
 *
 * Displays ongoing and upcoming matches grouped by sport category.
 * Each event resolves to one or more streaming links (M3U8/DASH) with
 * optional ClearKey DRM.
 */
class PlayZTVLiveEventsProvider : MainAPI() {

    companion object {
        var context: android.content.Context? = null
    }

    // ── Provider metadata ─────────────────────────────────────────────────────

    override var mainUrl = "https://adsflw.xyz"
    override var name = "⚡ PlayZTV Live Events"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── Data transfer object ──────────────────────────────────────────────────

    data class EventPayload(
        val eventId: Int,
        val title: String,
        val poster: String,
        val slug: String,
        val formats: List<PlayZLiveEventFormat>,
        val eventInfo: PlayZLiveEventInfo?
    )

    // ── Stream link parsing ───────────────────────────────────────────────────

    /** Splits "url|Key1=Val1|Key2=Val2" into a URL and header map. */
    private fun splitLink(link: String): Pair<String, Map<String, String>> {
        val segments = link.split("|")
        var cleanUrl = segments.firstOrNull()?.trim() ?: ""
        cleanUrl = cleanUrl.replace("%2F", "/", ignoreCase = true)
        val headers = mutableMapOf<String, String>()
        for (i in 1 until segments.size) {
            val eqIdx = segments[i].indexOf('=')
            if (eqIdx > 0) {
                val k = segments[i].substring(0, eqIdx).trim()
                val v = segments[i].substring(eqIdx + 1).trim()
                headers[k] = v
            }
        }
        return cleanUrl to headers
    }

    /** Converts a hex KID or key to URL-safe Base64 (no padding). */
    private fun hex2B64(hex: String): String {
        val raw = hex.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ── Event display helpers ─────────────────────────────────────────────────

    private fun vsLabel(ev: PlayZLiveEventData): String {
        val info = ev.eventInfo ?: return ev.title
        val a = info.teamA
        val b = info.teamB
        return if (!a.isNullOrBlank() && !b.isNullOrBlank() && a != b) "$a vs $b"
        else a ?: ev.title
    }

    private fun statusBadge(ev: PlayZLiveEventData): String {
        val info = ev.eventInfo ?: return ""
        val now = System.currentTimeMillis()
        val pattern = "yyyy/MM/dd HH:mm:ss Z"
        return try {
            val sdf = SimpleDateFormat(pattern, Locale.US)
            val startMs = info.startTime?.let { sdf.parse(it)?.time }
            val endMs = info.endTime?.let { sdf.parse(it)?.time }
            when {
                endMs != null && now >= endMs -> "🔴"
                startMs != null && now >= startMs -> "🟢"
                startMs != null && now < startMs -> "🟡"
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun isLiveNow(ev: PlayZLiveEventData): Boolean {
        val info = ev.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val startMs = info.startTime?.let { sdf.parse(it)?.time } ?: return false
            val endMs = info.endTime?.let { sdf.parse(it)?.time }
            if (endMs != null && now >= endMs) false
            else now >= startMs
        } catch (_: Exception) { false }
    }

    private fun hasEnded(ev: PlayZLiveEventData): Boolean {
        val info = ev.eventInfo ?: return false
        val now = System.currentTimeMillis()
        return try {
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
            val endMs = info.endTime?.let { sdf.parse(it)?.time } ?: return false
            now >= endMs
        } catch (_: Exception) { false }
    }

    private fun cardUrl(ev: PlayZLiveEventData): String {
        val info = ev.eventInfo
        val enc: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8") }

        val titleParam = enc(info?.eventName ?: ev.title)
        val teamAParam = enc(info?.teamA ?: "Team A")
        val teamBParam = enc(info?.teamB ?: "Team B")
        val teamAImg = info?.teamAFlag ?: ""
        val teamBImg = info?.teamBFlag ?: ""
        val logo = info?.eventLogo ?: ""
        val live = isLiveNow(ev)
        val ended = hasEnded(ev)

        val timeParam = info?.startTime?.let { ts ->
            try {
                val inFmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                val outFmt = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.US)
                val parsed = inFmt.parse(ts)
                if (parsed != null) enc(outFmt.format(parsed)) else ""
            } catch (_: Exception) { "" }
        } ?: ""

        return buildString {
            append("https://live-card-png.cricify.workers.dev/?")
            append("title=$titleParam")
            append("&teamA=$teamAParam")
            append("&teamB=$teamBParam")
            if (teamAImg.isNotBlank()) append("&teamAImg=$teamAImg")
            if (teamBImg.isNotBlank()) append("&teamBImg=$teamBImg")
            if (logo.isNotBlank()) append("&eventLogo=$logo")
            if (timeParam.isNotBlank()) append("&time=$timeParam")
            append("&isLive=$live")
            append("&isEnded=$ended")
        }
    }

    // ── CloudStream API ───────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allEvents = PlayZTVProviderManager.fetchLiveEvents()
        val byCategory = allEvents.groupBy { it.eventInfo?.eventCat ?: it.cat ?: "Other" }

        val homeLists = byCategory.map { (cat, catEvents) ->
            val icon = sportIcon(cat)
            val items = catEvents
                .sortedByDescending { isLiveNow(it) }
                .map { ev ->
                    val label = vsLabel(ev)
                    val badge = statusBadge(ev)
                    val fullTitle = if (badge.isNotBlank()) "$badge $label" else label
                    val poster = cardUrl(ev)
                    val payload = EventPayload(
                        eventId = ev.id, title = label, poster = poster,
                        slug = ev.slug, formats = ev.formats ?: emptyList(),
                        eventInfo = ev.eventInfo
                    )
                    newLiveSearchResponse(fullTitle, payload.toJson(), TvType.Live) {
                        this.posterUrl = poster
                    }
                }
            HomePageList("$icon $cat", items, isHorizontalImages = true)
        }.sortedBy { list ->
            when {
                list.name.contains("Cricket", ignoreCase = true) -> 0
                list.name.contains("Football", ignoreCase = true) -> 1
                list.name.contains("Basketball", ignoreCase = true) -> 2
                else -> 10
            }
        }

        return newHomePageResponse(homeLists, hasNext = false)
    }

    private fun sportIcon(category: String): String = when (category.lowercase()) {
        "cricket" -> "🏏"
        "football" -> "⚽"
        "basketball" -> "🏀"
        "ice hockey" -> "🏒"
        "boxing" -> "🥊"
        "motorsport" -> "🏎️"
        "tennis" -> "🎾"
        "badminton" -> "🏸"
        "baseball" -> "⚾"
        else -> "🏆"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = PlayZTVProviderManager.fetchLiveEvents()
            .filter { ev ->
                val haystack = listOfNotNull(
                    ev.title, ev.eventInfo?.teamA, ev.eventInfo?.teamB,
                    ev.eventInfo?.eventName, ev.eventInfo?.eventType
                ).joinToString(" ")
                haystack.contains(query, ignoreCase = true)
            }
            .map { ev ->
                val label = vsLabel(ev)
                val badge = statusBadge(ev)
                val fullTitle = if (badge.isNotBlank()) "$badge $label" else label
                val poster = cardUrl(ev)
                val payload = EventPayload(
                    eventId = ev.id, title = label, poster = poster,
                    slug = ev.slug, formats = ev.formats ?: emptyList(),
                    eventInfo = ev.eventInfo
                )
                newLiveSearchResponse(fullTitle, payload.toJson(), TvType.Live) {
                    this.posterUrl = poster
                }
            }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = parseJson<EventPayload>(url)
        val info = payload.eventInfo
        val description = buildString {
            info?.let { i ->
                i.eventType?.let { append("📺 $it\n") }
                i.eventName?.let { append("📋 $it\n") }
                i.startTime?.let { ts ->
                    try {
                        val inFmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss Z", Locale.US)
                        val outFmt = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
                        val parsed = inFmt.parse(ts)
                        if (parsed != null) append("🕐 ${outFmt.format(parsed)}\n")
                    } catch (_: Exception) { append("🕐 $ts\n") }
                }
            }
            append("\n📡 Servers: ${payload.formats.size}")
        }
        return newLiveStreamLoadResponse(payload.title, url, url) {
            this.posterUrl = payload.poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = parseJson<EventPayload>(data)
        val streams = PlayZTVProviderManager.fetchChannelStreams(payload.slug)
            ?: return false
        if (streams.isEmpty()) return false

        for (stream in streams) {
            val label = stream.name ?: "Server"
            val rawLink = stream.link ?: continue
            val (url, headers) = splitLink(rawLink)
            if (url.isBlank()) continue

            runCatching {
                if (url.contains(".mpd")) {
                    emitDashLink(url, stream.api, headers, label, callback)
                } else {
                    emitHlsLink(url, headers, label, callback)
                }
            }.onFailure { it.printStackTrace() }
        }
        return true
    }

    private fun emitDashLink(
        url: String,
        apiField: String?,
        headers: Map<String, String>,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val drmParts = apiField?.split(":")
        if (drmParts != null && drmParts.size == 2) {
            val kidB64 = hex2B64(drmParts[0])
            val keyB64 = hex2B64(drmParts[1])
            callback(
                newDrmExtractorLink(name, label, url, INFER_TYPE, CLEARKEY_UUID) {
                    this.quality = Qualities.Unknown.value
                    this.key = keyB64
                    this.kid = kidB64
                    if (headers.isNotEmpty()) this.headers = headers
                }
            )
        } else {
            callback(
                newExtractorLink(name, label, url, ExtractorLinkType.DASH) {
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) this.headers = headers
                }
            )
        }
    }

    private fun emitHlsLink(
        url: String,
        headers: Map<String, String>,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val mergedHeaders = headers.toMutableMap()
        if (!mergedHeaders.containsKey("User-Agent")) {
            mergedHeaders["User-Agent"] = DEFAULT_UA
        }
        callback(
            newExtractorLink(name, label, url, ExtractorLinkType.M3U8) {
                this.quality = Qualities.Unknown.value
                if (mergedHeaders.isNotEmpty()) this.headers = mergedHeaders
            }
        )
    }

    // ── Video interceptor ─────────────────────────────────────────────────────

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            var req = chain.request()
            val fixed = req.url.toString().replace(Regex("(?i)%2f"), "/")
            req = req.newBuilder()
                .url(fixed)
                .header("User-Agent", DEFAULT_UA)
                .header("Accept", "*/*")
                .build()
            chain.proceed(req)
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
