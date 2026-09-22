package com.netnaija

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.api.Log
import android.os.Looper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

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
    private val bff = "$apiUrl/wefeed-h5api-bff"
    private val TAG = "NetNaija"

    private val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val baseHeaders = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "X-Request-Lang" to "en",
        "X-Client-Info" to """{"timezone":"Asia/Kolkata"}"""
    )

    private val playHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "User-Agent" to ua
    )

    // browse channels used by the site's movies / tv-series / animated-series pages
    private val moviesChannel = 1
    private val seriesChannel = 2
    private val animeChannel = 1006

    // ranking menu ids from the site's ranking-list page
    private val trendingAnimeRanking = "62133389738001440"
    private val top100AnimeRanking = "1513079728666723416"

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private var jwtToken: String? = null
    private val tokenMutex = Mutex()

    private fun generateXClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val reversed = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(reversed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private fun extractTokenFromResponse(response: com.lagradost.nicehttp.NiceResponse): String? {
        try {
            val xUser = response.headers?.get("x-user") ?: return null
            if (xUser.isBlank()) return null
            val token = mapper.readTree(xUser)["token"]?.asText()
            if (!token.isNullOrBlank()) {
                jwtToken = token
                return token
            }
        } catch (e: Exception) {
            Log.d(TAG, "x-user parse failed: ${e.message}")
        }
        return null
    }

    // rows all want a token at once, single flight on the lightest list endpoint
    private suspend fun ensureToken(): String {
        jwtToken?.let { return it }
        return tokenMutex.withLock {
            jwtToken?.let { return@withLock it }
            try {
                val headers = baseHeaders.toMutableMap()
                headers["X-Client-Token"] = generateXClientToken()
                val response = app.get("$bff/subject/trending?page=1&perPage=1", headers = headers)
                extractTokenFromResponse(response) ?: ""
            } catch (e: Exception) {
                Log.d(TAG, "token bootstrap failed: ${e.message}")
                ""
            }
        }
    }

    // play calls need the cookie, list calls need the bearer
    private suspend fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val token = ensureToken()
        val headers = baseHeaders.toMutableMap()
        if (token.isNotEmpty()) {
            headers["Cookie"] = "token=$token"
            headers["Authorization"] = "Bearer $token"
        } else {
            headers["X-Client-Token"] = generateXClientToken()
        }
        headers.putAll(extra)
        return headers
    }

    // one /home response holds every section and the rows load in parallel,
    // so cache it behind the mutex and share across them
    private val homeMutex = Mutex()
    private val homeCacheTtl = 10 * 60_000L

    @Volatile
    private var homeSectionsCache: Map<String, List<NetNaijaSubject>> = emptyMap()

    @Volatile
    private var homeCacheTime = 0L

    // /home sections are region targeted, build the rows from whatever it returns
    @Volatile
    private var dynamicRows: List<MainPageData> = emptyList()

    @Volatile
    private var homeRowsFuture: CompletableFuture<Unit>? = null

    private val doneFuture = CompletableFuture.completedFuture(Unit)

    private fun homeRowsFresh(): Boolean =
        homeSectionsCache.isNotEmpty() && System.currentTimeMillis() - homeCacheTime < homeCacheTtl

    @Synchronized
    private fun refreshHomeRowsAsync(): CompletableFuture<Unit> {
        if (homeRowsFresh()) return doneFuture
        homeRowsFuture?.let { return it }
        val future = CompletableFuture<Unit>()
        homeRowsFuture = future
        CoroutineScope(Dispatchers.IO).launch {
            try {
                homeSections()
            } catch (e: Exception) {
                Log.d(TAG, "home refresh failed: ${e.message}")
            } finally {
                homeRowsFuture = null
                future.complete(Unit)
            }
        }
        return future
    }

    // block briefly for the first /home on worker threads, never on the ui thread
    private fun awaitHomeRows() {
        if (homeRowsFresh()) return
        try {
            refreshHomeRowsAsync().get(4000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.d(TAG, "home rows wait failed: ${e.message}")
        }
    }

    private fun buildDynamicRows(byTitle: Map<String, List<NetNaijaSubject>>): List<MainPageData> {
        val used = HashSet<String>()
        val rows = ArrayList<MainPageData>(byTitle.size)
        byTitle.forEach { (rawTitle, _) ->
            val display = cleanSectionTitle(rawTitle).ifBlank { rawTitle }
            if (used.add(display.lowercase())) {
                rows.add(MainPageData(name = display, data = "home:$rawTitle"))
            }
        }
        return rows
    }

    private suspend fun homeSections(): Map<String, List<NetNaijaSubject>> {
        if (homeRowsFresh()) return homeSectionsCache
        return homeMutex.withLock {
            if (homeRowsFresh()) return@withLock homeSectionsCache
            val sections = try {
                val response = app.get("$bff/home", headers = authHeaders())
                extractTokenFromResponse(response)
                parseJson<NetNaijaHomeResponse>(response.text).data?.operatingList.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "home fetch failed: ${e.message}")
                emptyList()
            }
            val byTitle = LinkedHashMap<String, List<NetNaijaSubject>>()
            sections.forEach { section ->
                val subjects = section.subjects.orEmpty()
                    .filter { !it.title.isNullOrBlank() && !it.detailPath.isNullOrBlank() }
                if (subjects.isNotEmpty()) {
                    byTitle[section.title ?: return@forEach] = subjects
                }
            }
            if (byTitle.isNotEmpty()) {
                homeSectionsCache = byTitle
                homeCacheTime = System.currentTimeMillis()
                val rows = buildDynamicRows(byTitle)
                // keep the old list when unchanged so row indexes stay stable for pagination
                if (rows.map { it.data } != dynamicRows.map { it.data }) {
                    dynamicRows = rows
                }
            }
            byTitle
        }
    }

    fun warmUp() {
        refreshHomeRowsAsync()
    }

    private val staticMainPage = mainPageOf(
        "trending" to "Trending Now",
        "filter:$moviesChannel:Latest" to "Latest Movies",
        "filter:$seriesChannel:Latest" to "Latest Series",
        "filter:$animeChannel:Hottest" to "Anime",
        "filter:$animeChannel:Latest" to "Latest Anime",
        "rank:$trendingAnimeRanking" to "Trending Anime",
        "rank:$top100AnimeRanking" to "Top 100 Anime",
    )

    override val mainPage: List<MainPageData>
        get() {
            if (Looper.myLooper() === Looper.getMainLooper()) {
                refreshHomeRowsAsync()
            } else {
                awaitHomeRows()
            }
            val used = HashSet<String>()
            staticMainPage.forEach { used.add(it.name.lowercase()) }
            val rows = ArrayList<MainPageData>(staticMainPage.size + dynamicRows.size)
            rows.add(staticMainPage.first())
            dynamicRows.forEach { row ->
                if (used.add(row.name.lowercase())) rows.add(row)
            }
            rows.addAll(staticMainPage.drop(1))
            return rows
        }

    // the site decorates section titles with emoji and symbols
    private fun cleanSectionTitle(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N} &+\\[\\]().'-]"), "").trim()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        val key = request.data
        return try {
            when {
                key == "trending" -> {
                    val response = app.get("$bff/subject/trending?page=$page&perPage=24", headers = authHeaders())
                    extractTokenFromResponse(response)
                    val data = parseJson<NetNaijaTrendingResponse>(response.text).data
                    val items = data?.subjectList.orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = data?.pager?.hasMore == true)
                }

                key.startsWith("filter:") -> {
                    val parts = key.split(":")
                    val channelId = parts.getOrNull(1)?.toIntOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
                    val sort = parts.getOrNull(2) ?: "Hottest"
                    val body = """{"page":$page,"perPage":24,"channelId":$channelId,"sort":"$sort"}"""
                    val response = app.post(
                        "$bff/subject/filter",
                        headers = authHeaders(mapOf("Content-Type" to "application/json")),
                        requestBody = body.toRequestBody("application/json".toMediaType())
                    )
                    extractTokenFromResponse(response)
                    val data = parseJson<NetNaijaListResponse>(response.text).data
                    val items = data?.items.orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = data?.pager?.hasMore == true)
                }

                key.startsWith("rank:") -> {
                    val listId = key.removePrefix("rank:")
                    val response = app.get("$bff/ranking-list/content?id=$listId&page=$page&perPage=24", headers = authHeaders())
                    extractTokenFromResponse(response)
                    val data = parseJson<NetNaijaRankingResponse>(response.text).data
                    val items = data?.subjectList.orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = data?.pager?.hasMore == true)
                }

                key.startsWith("home:") -> {
                    // curated site sections carry a fixed set of items
                    if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
                    val rawTitle = key.removePrefix("home:")
                    val items = homeSections()[rawTitle].orEmpty().mapNotNull { it.toSearchResponse() }
                    newHomePageResponse(request.name, items, hasNext = false)
                }

                else -> newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "main page $key failed: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        if (query.isBlank()) return emptyList()
        // the search api needs the bearer from the token bootstrap, the rendered
        // page only backs it up for when that call gets refused
        apiSearch(query)?.let { return it }
        return try {
            val html = app.get(
                "$mainUrl/search-result?keyword=${URLEncoder.encode(query, "UTF-8")}",
                headers = mapOf("User-Agent" to ua)
            ).text
            parseSearchPage(html)
        } catch (e: Exception) {
            Log.d(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun apiSearch(query: String): List<SearchResponse>? {
        return try {
            val response = app.post(
                "$bff/subject/search",
                json = mapOf("keyword" to query, "page" to 1, "perPage" to 20),
                headers = authHeaders()
            )
            val parsed = parseJson<NetNaijaListResponse>(response.text)
            parsed.data?.items?.mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.d(TAG, "api search failed: ${e.message}")
            null
        }
    }

    private val nuxtDataRegex = Regex(
        """<script[^>]*id="__NUXT_DATA__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun parseSearchPage(html: String): List<SearchResponse> {
        val match = nuxtDataRegex.find(html) ?: return emptyList()
        val root = try {
            mapper.readTree(match.groupValues[1])
        } catch (e: Exception) {
            Log.d(TAG, "search page json failed: ${e.message}")
            return emptyList()
        }
        if (root !is ArrayNode) return emptyList()
        for (i in 0 until root.size()) {
            val node = root.get(i)
            if (node is ObjectNode && node.has("items") && node.has("pager") && node.get("items").isNumber) {
                val resolved = resolveNuxtNode(root, node.get("items").asInt(), 0) as? List<*> ?: continue
                return resolved.mapNotNull { entry ->
                    try {
                        mapper.convertValue(entry, NetNaijaSubject::class.java).toSearchResponse()
                    } catch (e: Exception) {
                        Log.d(TAG, "nuxt entry skipped: ${e.message}")
                        null
                    }
                }
            }
        }
        return emptyList()
    }

    // nuxt flattens the page state into one array of indexes, resolve one level at a time
    private fun resolveNuxtNode(arr: ArrayNode, idx: Int, depth: Int): Any? {
        if (idx < 0 || idx >= arr.size() || depth > 8) return null
        return when (val node = arr.get(idx)) {
            is ObjectNode -> {
                val out = LinkedHashMap<String, Any?>()
                val fields = node.fields()
                while (fields.hasNext()) {
                    val field = fields.next()
                    out[field.key] = resolveNuxtValue(arr, field.value, depth)
                }
                out
            }

            is ArrayNode -> {
                val out = ArrayList<Any?>(node.size())
                for (i in 0 until node.size()) {
                    out.add(resolveNuxtValue(arr, node.get(i), depth))
                }
                out
            }

            else -> plainNuxtValue(node)
        }
    }

    private fun resolveNuxtValue(arr: ArrayNode, value: JsonNode, depth: Int): Any? =
        if (value.isNumber) resolveNuxtNode(arr, value.asInt(), depth + 1) else plainNuxtValue(value)

    private fun plainNuxtValue(node: JsonNode): Any? = when {
        node.isTextual -> node.asText()
        node.isBoolean -> node.asBoolean()
        node.isInt || node.isLong -> node.asLong()
        node.isNumber -> node.asDouble()
        else -> null
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("netnaija") ?: mainUrl
        val detailPath = url.substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
            ?: return null

        return try {
            val response = app.get("$bff/detail?detailPath=$detailPath", headers = authHeaders())
            extractTokenFromResponse(response)
            val data = parseJson<NetNaijaDetailResponse>(response.text).data ?: return null
            val subject = data.subject ?: return null
            val title = subject.title ?: return null

            val poster = subject.cover?.url
            val plot = subject.description?.takeIf { it.isNotBlank() }
            val genres = subject.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val year = subject.releaseDate?.substringBefore("-")?.toIntOrNull()
            val score = subject.imdbRatingValue?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Score.from10(it) }.getOrNull() }
            val runtime = subject.duration?.takeIf { it > 0 }?.let { it / 60 }
            val cast = data.stars.orEmpty().mapNotNull { staff ->
                staff.name?.let { ActorData(Actor(it, staff.avatarUrl)) }
            }.take(15)
            val trailer = subject.trailer?.videoAddress?.url?.takeIf { it.isNotBlank() }
            val recommendations = fetchRecommendations(subject.subjectId, detailPath)

            val tvType = when (subject.subjectType) {
                1 -> TvType.Movie
                2 -> TvType.TvSeries
                else -> TvType.Movie
            }

            val seasons = data.resource?.seasons ?: emptyList()
            val dubs = subject.dubs.orEmpty().ifEmpty {
                listOf(
                    NetNaijaDub(
                        subjectId = subject.subjectId ?: "",
                        detailPath = detailPath,
                        lanName = "Original Audio",
                        lanCode = "en",
                        type = 0,
                        original = true
                    )
                )
            }

            NetNaijaSources.register(dubs.mapNotNull { sourceLabel(it) })

            if (tvType == TvType.Movie || seasons.isEmpty()) {
                // Movie - store all dub subjectIds so loadLinks can fetch each audio
                val movieData = NetNaijaEpisodeData(dubs = dubs, season = 0, episode = 0).toJson()
                return newMovieLoadResponse(title, url, tvType, movieData) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.score = score
                    this.duration = runtime
                    this.actors = cast
                    this.recommendations = recommendations
                    if (trailer != null) addTrailer(trailer)
                }
            } else {
                val episodes = mutableListOf<Episode>()
                seasons.forEach { season ->
                    val seasonNum = season.se ?: return@forEach
                    val maxEp = season.maxEp ?: 0
                    for (ep in 1..maxEp) {
                        val epData = NetNaijaEpisodeData(dubs = dubs, season = seasonNum, episode = ep).toJson()
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
                    this.score = score
                    this.duration = runtime
                    this.actors = cast
                    this.recommendations = recommendations
                    if (trailer != null) addTrailer(trailer)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "load failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchRecommendations(subjectId: String?, detailPath: String): List<SearchResponse> {
        if (subjectId.isNullOrBlank()) return emptyList()
        return try {
            val response = app.get(
                "$bff/subject/detail-rec?subjectId=$subjectId&detailPath=$detailPath",
                headers = authHeaders()
            )
            val items = parseJson<NetNaijaListResponse>(response.text).data?.items.orEmpty()
            items.filter { it.detailPath != detailPath }.mapNotNull { it.toSearchResponse() }.take(12)
        } catch (e: Exception) {
            Log.d(TAG, "recommendations failed: ${e.message}")
            emptyList()
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
            Log.d(TAG, "episode data parse failed: ${e.message}")
            return false
        }

        NetNaijaSources.register(epData.dubs.mapNotNull { sourceLabel(it) })

        // a source turned off in the settings is skipped entirely, its play
        // endpoint is never touched
        val dubs = epData.dubs.filter { dub ->
            sourceLabel(dub)?.let { NetNaijaSources.isEnabled(it) } == true
        }
        var found = false

        // soft subs only hang off the original audio stream, pull them once at the end
        var captionStream: Triple<String, String, String>? = null

        // one source per audio track, cloudstream's audioTracks can't handle dash manifests
        dubs.forEach { dub ->
            val dubSubjectId = dub.subjectId ?: return@forEach
            val dubDetailPath = dub.detailPath ?: return@forEach
            val label = sourceLabel(dub)
            if (label == null) return@forEach

            val playReferer = "$mainUrl/videoPlayPage/$dubDetailPath"
            val headers = authHeaders(mapOf(
                "X-Source" to "webNetnaijaSite",
                "Referer" to playReferer
            ))

            val playUrl = "$bff/subject/play?subjectId=$dubSubjectId" +
                "&se=${epData.season}&ep=${epData.episode}&detailPath=$dubDetailPath"

            val playData = try {
                val resp = app.get(playUrl, headers = headers)
                extractTokenFromResponse(resp)
                parseJson<NetNaijaPlayResponse>(resp.text).data
            } catch (e: Exception) {
                Log.d(TAG, "play fetch failed for $label: ${e.message}")
                return@forEach
            } ?: return@forEach

            val mp4Streams = playData.streams ?: emptyList()
            mp4Streams.forEach { stream ->
                val url = stream.url ?: return@forEach
                val resolution = stream.resolutions ?: return@forEach
                if (stream.vipLocked == true) return@forEach

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
                        "NetNaija $label",
                        "$label ${resolution}p${sizeLabel}",
                        url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityLabel
                        this.headers = playHeaders
                    }
                )
                found = true
            }

            if ((dub.original == true || captionStream == null) && mp4Streams.isNotEmpty()) {
                mp4Streams.first().id?.let { streamId ->
                    captionStream = Triple(streamId, dubSubjectId, dubDetailPath)
                }
            }

            playData.dash?.forEach { dashStream ->
                val url = dashStream.url ?: return@forEach
                callback.invoke(
                    newExtractorLink(
                        "NetNaija $label",
                        "$label DASH (Adaptive)",
                        url,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = playHeaders
                    }
                )
                found = true
            }

            playData.hls?.forEach { hlsStream ->
                val url = hlsStream.url ?: return@forEach
                val resolution = hlsStream.resolutions ?: "0"
                try {
                    M3u8Helper.generateM3u8(
                        "NetNaija $label HLS ${resolution}p",
                        url,
                        "$mainUrl/",
                        headers = playHeaders
                    ).forEach(callback)
                    found = true
                } catch (e: Exception) {
                    callback.invoke(
                        newExtractorLink(
                            "NetNaija $label",
                            "$label HLS ${resolution}p",
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

        captionStream?.let { (streamId, subjectId, detailPath) ->
            fetchSubtitles(streamId, subjectId, detailPath, subtitleCallback)
        }

        return found
    }

    // "English dub" -> English Dub, "Arabic sub" -> Arabic Hardsub
    private fun sourceLabel(dub: NetNaijaDub): String? {
        val lanName = dub.lanName ?: return null
        if (dub.original == true) return "Original"
        val base = lanName.substringBefore(" dub").substringBefore(" sub").trim()
        if (base.isEmpty()) return null
        val pretty = when (base.lowercase()) {
            "ptbr" -> "PT-BR"
            "esla" -> "ES-LA"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        return if (dub.type == 1) "$pretty Hardsub" else "$pretty Dub"
    }

    private suspend fun fetchSubtitles(
        streamId: String,
        subjectId: String,
        detailPath: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val captionUrl = "$bff/subject/caption" +
                "?format=MP4&id=$streamId&subjectId=$subjectId&detailPath=$detailPath"
            val response = app.get(captionUrl, headers = authHeaders(mapOf("X-Source" to "webNetnaijaSite")))
            val data = parseJson<NetNaijaCaptionResponse>(response.text).data
            data?.captions?.forEach { caption ->
                val url = caption.url ?: return@forEach
                val lang = caption.lanName ?: caption.lan ?: "Unknown"
                subtitleCallback.invoke(newSubtitleFile(lang, url))
            }
        } catch (e: Exception) {
            Log.d(TAG, "caption fetch failed: ${e.message}")
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
    @JsonProperty("type") val type: Int? = null,
    @JsonProperty("original") val original: Boolean? = null
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
    @JsonProperty("trailer") val trailer: NetNaijaTrailer? = null,
    @JsonProperty("detailPath") val detailPath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrailer(
    @JsonProperty("videoAddress") val videoAddress: NetNaijaTrailerVideo? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaTrailerVideo(
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaStaff(
    @JsonProperty("staffId") val staffId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("avatarUrl") val avatarUrl: String? = null
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
data class NetNaijaListData(
    @JsonProperty("items") val items: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaListResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaListData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaRankingData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("subjectList") val subjectList: List<NetNaijaSubject>? = null,
    @JsonProperty("pager") val pager: NetNaijaPager? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetNaijaRankingResponse(
    @JsonProperty("code") val code: Int? = null,
    @JsonProperty("data") val data: NetNaijaRankingData? = null
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
    @JsonProperty("resource") val resource: NetNaijaResource? = null,
    @JsonProperty("stars") val stars: List<NetNaijaStaff>? = null
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
