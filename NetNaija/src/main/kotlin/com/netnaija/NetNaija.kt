package com.netnaija

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * NetNaija — netnaija.film
 *
 * WeFeed platform (same architecture as MovieBox, different deployment).
 *
 * API: h5-api.aoneroom.com (wefeed-h5api-bff)
 * CDN: bcdnxw.hakunaymatata.com (video), cacdn.hakunaymatata.com (subtitles)
 *
 * Auth flow:
 *   1. ANY API request returns `x-user` response header with JSON: {"token":"eyJ..."}
 *   2. Extract JWT from x-user header, cache it, use as Authorization: Bearer {jwt}
 *   3. If no JWT yet, use X-Client-Token: {timestamp},{md5(reversed_timestamp)}
 *   4. Play API requires X-Source: webNetnaijaSite header (CRITICAL)
 *
 * Stream API returns:
 *   - streams[]: MP4 files (360p, 480p, 720p, 1080p)
 *   - dash[]: DASH manifest (adaptive quality)
 *   - hls[]: HLS playlist (when available)
 *
 * MP4 URLs require Referer: https://netnaija.film/ header for playback.
 * Subtitles: 12+ languages via /subject/caption endpoint.
 */
class NetNaija : MainAPI() {
    override var mainUrl = "https://netnaija.film"
    override var name = "NetNaija"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val apiUrl = "https://h5-api.aoneroom.com"

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "X-Request-Lang" to "en",
        "X-Client-Info" to """{"timezone":"Asia/Kolkata"}"""
    )

    private val playHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    )

    private var jwtToken: String? = null

    /** Generate X-Client-Token: {timestamp},{md5(reversed_timestamp)}. */
    private fun generateXClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val reversed = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(reversed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    /** Extract JWT from x-user response header (JSON: {"token":"eyJ..."}). */
    private fun extractTokenFromResponse(response: com.lagradost.nicehttp.NiceResponse): String? {
        try {
            val xUser = response.headers?.get("x-user") ?: return null
            if (xUser.isBlank()) return null
            val token = jacksonObjectMapper().readTree(xUser)["token"]?.asText()
            if (!token.isNullOrBlank()) {
                jwtToken = token
                return token
            }
        } catch (e: Exception) {
        }
        return null
    }

    /** Ensure we have a JWT token; fetch one if needed. */
    private suspend fun ensureToken(): String {
        jwtToken?.let { return it }
        return try {
            val headers = baseHeaders.toMutableMap()
            headers["X-Client-Token"] = generateXClientToken()
            val response = app.get("$apiUrl/wefeed-h5api-bff/home", headers = headers)
            extractTokenFromResponse(response) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** Build auth headers with BOTH Cookie and Bearer (play needs Cookie, search needs Bearer). */
    private suspend fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = ensureToken()
        val headers = baseHeaders.toMutableMap()
        if (token.isNotEmpty()) {
            // Send BOTH — play API uses Cookie, search API uses Bearer
            headers["Cookie"] = "token=$token"
            headers["Authorization"] = "Bearer $token"
        } else {
            // Fallback: use X-Client-Token if no JWT yet
            headers["X-Client-Token"] = generateXClientToken()
        }
        headers.putAll(extra)
        return headers
    }

    override val mainPage = mainPageOf(
        Pair("trending", "Trending Now")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val headers = authHeaders()
            val response = app.get(
                "$apiUrl/wefeed-h5api-bff/subject/trending?page=$page&perPage=20",
                headers = headers
            )
            extractTokenFromResponse(response)
            val data = parseJson<NetNaijaTrendingResponse>(response.text)
            val items = data.data?.subjectList?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            newHomePageResponse(request.name, items, hasNext = data.data?.pager?.hasMore ?: false)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        if (query.isBlank()) return emptyList()
        return try {
            val headers = authHeaders(mapOf("Content-Type" to "application/json"))
            val encoded = URLEncoder.encode(query, "UTF-8")
            val body = """{"keyword":"$encoded","page":1,"perPage":30,"subjectType":0}"""
            val response = app.post(
                "$apiUrl/wefeed-h5api-bff/subject/search",
                headers = headers,
                requestBody = body.toRequestBody("application/json".toMediaType())
            )
            extractTokenFromResponse(response) // Capture token for later
            val data = parseJson<NetNaijaSearchResponse>(response.text)
            data.data?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        val detailPath = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val headers = authHeaders()
            val response = app.get(
                "$apiUrl/wefeed-h5api-bff/detail?detailPath=$detailPath",
                headers = headers
            )
            extractTokenFromResponse(response)
            val data = parseJson<NetNaijaDetailResponse>(response.text).data ?: return null
            val subject = data.subject ?: return null
            val title = subject.title ?: return null

            val poster = subject.cover?.url
            val plot = subject.description
            val genres = subject.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val year = subject.releaseDate?.substringBefore("-")?.toIntOrNull()

            val tvType = when (subject.subjectType) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie
            }

            val seasons = data.resource?.seasons ?: emptyList()
            // Collect all audio/subtitle variants (dubs)
            val dubs = subject.dubs ?: emptyList()

            if (tvType == TvType.Movie || seasons.isEmpty()) {
                // Movie — store all dub subjectIds so loadLinks can fetch each audio
                val movieData = NetNaijaEpisodeData(
                    dubs = dubs.ifEmpty { listOf(NetNaijaDub(subjectId = subject.subjectId ?: "", detailPath = detailPath, lanName = "Original", lanCode = "en", type = 0)) },
                    season = 0,
                    episode = 0
                ).toJson()
                return newMovieLoadResponse(title, url, tvType, movieData) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                }
            } else {
                val episodes = mutableListOf<Episode>()
                seasons.forEach { season ->
                    val seasonNum = season.se ?: return@forEach
                    val maxEp = season.maxEp ?: 0
                    for (ep in 1..maxEp) {
                        val epData = NetNaijaEpisodeData(
                            dubs = dubs.ifEmpty { listOf(NetNaijaDub(subjectId = subject.subjectId ?: "", detailPath = detailPath, lanName = "Original", lanCode = "en", type = 0)) },
                            season = seasonNum,
                            episode = ep
                        ).toJson()
                        episodes.add(newEpisode(epData) {
                            this.season = seasonNum
                            this.episode = ep
                            this.name = "Episode $ep"
                        })
                    }
                }
                return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = try {
            parseJson<NetNaijaEpisodeData>(data)
        } catch (e: Exception) {
            return false
        }

        val dubs = epData.dubs

        var found = false
        var subtitlesLoaded = false

        // Each dub (audio language) is a separate source, labeled with language name.
        // This follows the same pattern as MovieBox (phisher98).
        // CloudStream's audioTracks field uses SingleSampleMediaSource which only
        // works with single-file audio URLs (like YouTube), NOT DASH manifests.
        // So we must use separate sources for each audio language.
        dubs.forEach { dub ->
            val dubSubjectId = dub.subjectId ?: return@forEach
            val dubDetailPath = dub.detailPath ?: return@forEach
            val lanName = dub.lanName ?: "Original"
            val isDub = dub.type == 0 // type 0 = dub, type 1 = sub
            val audioLabel = if (isDub) lanName.replace("dub", "Audio").replace("Dub", "Audio")
                             else lanName.replace("sub", "Sub").replace("Sub", "Sub")

            val playReferer = "$mainUrl/videoPlayPage/$dubDetailPath"
            val headers = authHeaders(mapOf(
                "X-Source" to "webNetnaijaSite",
                "Referer" to playReferer
            ))

            val playUrl = "$apiUrl/wefeed-h5api-bff/subject/play?subjectId=$dubSubjectId" +
                "&se=${epData.season}&ep=${epData.episode}&detailPath=$dubDetailPath"

            val playData = try {
                val resp = app.get(playUrl, headers = headers)
                extractTokenFromResponse(resp)
                parseJson<NetNaijaPlayResponse>(resp.text).data
            } catch (e: Exception) {
                return@forEach
            } ?: return@forEach

            // MP4 streams
            val mp4Streams = playData.streams ?: emptyList()
            mp4Streams.forEach { stream ->
                val url = stream.url ?: return@forEach
                val resolution = stream.resolutions ?: return@forEach
                val qualityInt = resolution.toIntOrNull()
                val qualityLabel = when (qualityInt) {
                    360 -> Qualities.P360.value
                    480 -> Qualities.P480.value
                    720 -> Qualities.P720.value
                    1080 -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }
                val sizeBytes = stream.size?.toLongOrNull() ?: 0
                val sizeLabel = if (sizeBytes > 0) " (${sizeBytes / (1024 * 1024)}MB)" else ""

                callback.invoke(
                    newExtractorLink(
                        "NetNaija $audioLabel",
                        "$audioLabel ${resolution}p${sizeLabel}",
                        url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityLabel
                        this.headers = playHeaders
                    }
                )
                found = true

                // Fetch subtitles only once (from first dub's first stream)
                if (!subtitlesLoaded && stream == mp4Streams.first()) {
                    fetchSubtitles(
                        streamId = stream.id ?: return@forEach,
                        subjectId = dubSubjectId,
                        detailPath = dubDetailPath,
                        headers = headers,
                        subtitleCallback = subtitleCallback
                    )
                    subtitlesLoaded = true
                }
            }

            // DASH stream
            playData.dash?.forEach { dashStream ->
                val url = dashStream.url ?: return@forEach
                callback.invoke(
                    newExtractorLink(
                        "NetNaija $audioLabel",
                        "$audioLabel DASH (Adaptive)",
                        url,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = playHeaders
                    }
                )
                found = true
            }

            // HLS stream
            playData.hls?.forEach { hlsStream ->
                val url = hlsStream.url ?: return@forEach
                val resolution = hlsStream.resolutions ?: "0"
                try {
                    M3u8Helper.generateM3u8(
                        "NetNaija $audioLabel HLS ${resolution}p",
                        url,
                        "$mainUrl/",
                        headers = playHeaders
                    ).forEach(callback)
                    found = true
                } catch (e: Exception) {
                    callback.invoke(
                        newExtractorLink(
                            "NetNaija $audioLabel",
                            "$audioLabel HLS ${resolution}p",
                            url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = playHeaders
                        }
                    )
                    found = true
                }
            }
        }

        return found
    }

    /** Fetch and emit all available subtitles. */
    private suspend fun fetchSubtitles(
        streamId: String,
        subjectId: String,
        detailPath: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val captionUrl = "$apiUrl/wefeed-h5api-bff/subject/caption" +
                "?format=MP4&id=$streamId&subjectId=$subjectId&detailPath=$detailPath"
            val response = app.get(captionUrl, headers = headers)
            val data = parseJson<NetNaijaCaptionResponse>(response.text).data
            data?.captions?.forEach { caption ->
                val url = caption.url ?: return@forEach
                val lang = caption.lanName ?: caption.lan ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, url))
            }
        } catch (e: Exception) {
        }
    }

    private fun NetNaijaSubject.toSearchResponse(): SearchResponse? {
        val title = this.title ?: return null
        val path = detailPath ?: return null
        val poster = cover?.url
        val isMovie = subjectType == 1
        return if (isMovie) {
            newMovieSearchResponse(title, "$mainUrl/movieDetail/$path", TvType.Movie) {
                this.posterUrl = poster
                this.year = releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/movieDetail/$path", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        }
    }
}

data class NetNaijaDub(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("detailPath") val detailPath: String? = null,
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("lanCode") val lanCode: String? = null,
    @JsonProperty("type") val type: Int? = null
)

data class NetNaijaEpisodeData(
    val dubs: List<NetNaijaDub>,
    val season: Int,
    val episode: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCover(
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaSubject(
    @JsonProperty("subjectId") val subjectId: String? = null,
    @JsonProperty("subjectType") val subjectType: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("cover") val cover: NetNaijaCover? = null,
    @JsonProperty("countryName") val countryName: String? = null,
    @JsonProperty("dubs") val dubs: List<NetNaijaDub>? = null,
    @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
    @JsonProperty("detailPath") val detailPath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaPager(
    @JsonProperty("hasMore") val hasMore: Boolean? = null,
    @JsonProperty("page") val page: String? = null,
    @JsonProperty("perPage") val perPage: Int? = null,
    @JsonProperty("totalCount") val totalCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrendingData(
    @JsonProperty("subjectList") val subjectList: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrendingResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaTrendingData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaHomeSection(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("subjects") val subjects: List<NetNaijaSubject>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaHomeData(
    @JsonProperty("operatingList") val operatingList: List<NetNaijaHomeSection>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaHomeResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaHomeData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaSearchData(
    @JsonProperty("items") val items: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaSearchResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaSearchData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaResolution(
    @JsonProperty("resolution") val resolution: Int? = null,
    @JsonProperty("epNum") val epNum: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaSeason(
    @JsonProperty("se") val se: Int? = null,
    @JsonProperty("maxEp") val maxEp: Int? = null,
    @JsonProperty("resolutions") val resolutions: List<NetNaijaResolution>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaResource(
    @JsonProperty("seasons") val seasons: List<NetNaijaSeason>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaDetailData(
    @JsonProperty("subject") val subject: NetNaijaSubject? = null,
    @JsonProperty("resource") val resource: NetNaijaResource? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaDetailResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaDetailData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaStream(
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("resolutions") val resolutions: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("codecName") val codecName: String? = null,
    @JsonProperty("vipLocked") val vipLocked: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaPlayData(
    @JsonProperty("streams") val streams: List<NetNaijaStream>? = null,
    @JsonProperty("hls") val hls: List<NetNaijaStream>? = null,
    @JsonProperty("dash") val dash: List<NetNaijaStream>? = null,
    @JsonProperty("limited") val limited: Boolean? = null,
    @JsonProperty("hasResource") val hasResource: Boolean? = null,
    @JsonProperty("vipLocked") val vipLocked: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaPlayResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaPlayData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCaption(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("lan") val lan: String? = null,
    @JsonProperty("lanName") val lanName: String? = null,
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCaptionData(
    @JsonProperty("captions") val captions: List<NetNaijaCaption>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaCaptionResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaCaptionData? = null
)
