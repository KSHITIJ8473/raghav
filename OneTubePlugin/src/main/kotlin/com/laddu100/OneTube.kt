package com.laddu100

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class OneTube : MainAPI() {
    override var mainUrl = "https://www.1tube.org"
    override var name = "OneTube"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * Standard browser headers — 1tube.org is behind Cloudflare and rejects
     * bare Java/OkHttp User-Agents with 403.
     */
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to mainUrl
    )

    private val imgBase = "https://image.tmdb.org/t/p/w500"

    // ============================================================
    //  Homepage — uses the /api/shawts endpoint
    // ============================================================
    override val mainPage = mainPageOf(
        "movie" to "Latest Movies",
        "tv" to "Latest Series",
        "all" to "Trending Now"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = request.data
        val url = "$mainUrl/api/shawts?type=$type&page=$page"
        val response = app.get(url, headers = baseHeaders).parsedSafe<ShawtsResponse>()
            ?: return newHomePageResponse(request.name, emptyList())
        val items = response.results.map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = response.has_more)
    }

    // ============================================================
    //  Search — uses the /api/search endpoint
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResult>()
        for (p in 1..3) {
            val url = "$mainUrl/api/search?query=${URLEncoder.encode(query, "utf-8")}&page=$p"
            val response = app.get(url, headers = baseHeaders).parsedSafe<SearchApiResponse>() ?: break
            allResults += response.results
            val totalPages = response.total_pages ?: 1
            if (p >= totalPages) break
        }
        return allResults.map { it.toSearchResponse() }
    }

    // ============================================================
    //  Load — fetches details + (for TV) all seasons' episodes
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val tmdbId = url.substringAfter("/watch/").substringBefore("?").toIntOrNull() ?: return null
        val mediaType = if (url.contains("type=tv")) "tv" else "movie"

        val detailsUrl = "$mainUrl/api/details/$tmdbId?type=$mediaType"
        val details = app.get(detailsUrl, headers = baseHeaders).parsedSafe<DetailsResponse>() ?: return null

        val title = details.title ?: details.name ?: return null
        val poster = details.poster_path?.let { imgBase + it }
        val bg = details.backdrop_path?.let { imgBase + it }
        val plot = details.overview
        val year = (details.release_date ?: details.first_air_date)?.substring(0, 4)?.toIntOrNull()
        val tags = details.genres?.map { it.name } ?: emptyList()

        val showStatus = when (details.status?.lowercase()) {
            "released", "ended", "canceled" -> ShowStatus.Completed
            "in production", "post production", "returning series", "airing" -> ShowStatus.Ongoing
            else -> null
        }

        val recUrl = "$mainUrl/api/recommendations/$tmdbId?type=$mediaType"
        val recs = app.get(recUrl, headers = baseHeaders).parsedSafe<RecommendationsResponse>()
            ?.results?.map { it.toSearchResponse() } ?: emptyList()

        if (mediaType == "movie") {
            // Movie — data string: movie|<tmdbId>|0|0
            return newMovieLoadResponse(title, url, TvType.Movie, "movie|$tmdbId|0|0") {
                this.posterUrl = poster
                this.backgroundPosterUrl = bg
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = details.vote_average?.let { Score.from10(it.toFloat()) }
                this.recommendations = recs
            }
        }

        // TV series — fetch every season's episodes
        val seasons = details.seasons?.filter { it.season_number != null } ?: emptyList()
        val allEpisodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        for (season in seasons) {
            val seasonNum = season.season_number!!
            if (seasonNum == 0 && seasons.size > 1) continue

            val seasonUrl = "$mainUrl/api/tv/$tmdbId/season/$seasonNum"
            val seasonData = app.get(seasonUrl, headers = baseHeaders).parsedSafe<SeasonResponse>()
            if (seasonData?.episodes == null) continue

            val episodes = seasonData.episodes.map { ep ->
                newEpisode("tv|$tmdbId|$seasonNum|${ep.episode_number ?: 1}") {
                    this.name = ep.name ?: "Episode ${ep.episode_number}"
                    this.episode = ep.episode_number ?: 1
                    this.season = seasonNum
                    this.posterUrl = ep.still_path?.let { imgBase + it }
                    this.description = ep.overview
                    this.score = ep.vote_average?.let { Score.from10(it.toFloat()) }
                    this.runTime = ep.runtime
                }
            }
            allEpisodes += episodes
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bg
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = details.vote_average?.let { Score.from10(it.toFloat()) }
            this.showStatus = showStatus
            this.recommendations = recs
        }
    }

    // ============================================================
    //  Load Links — builds server embed URLs and extracts video
    // ============================================================
    //
    //  The 1tube.org site offers 8 server tabs. Analysis of each:
    //
    //  WORKING (custom extractors implemented):
    //    MAIN_4 (vidlink.pro)  — enc-dec.app + /api/b/movie|tv/{enc}  → m3u8 + subtitles
    //    MAIN_5 (vidrock.ru)   — AES-encrypted ID + /api/{type}/{enc} → multiple m3u8/mp4
    //    MAIN_6 (vidzee.wtf)   — /api/server?id={tmdb}&sr=1..8         → encrypted (skip if decrypt fails)
    //
    //  SKIPPED (pure JS SPAs with no extractable API — would need WebView):
    //    MAIN_1 (videasy.to)   — needs external enc-dec.app + videasy.net API (complex)
    //    MAIN_2 (vidsrc.wtf/api/1)  — Next.js SPA, no JSON API
    //    MAIN_3 (vidup.to)     — Next.js SPA, no JSON API
    //    MULTILANGUAGE (vidsrc.wtf/api/2) — Next.js SPA, no JSON API
    //    PREMIUM_EMBEDS (vidsrc.wtf/api/4) — Next.js SPA, no JSON API
    //
    //  The 3 working servers provide plenty of sources for every movie/TV show.
    //
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 4) return false

        val mediaType = parts[0]        // "movie" or "tv"
        val tmdbId = parts[1].toIntOrNull() ?: return false
        val season = parts[2].toIntOrNull() ?: 0
        val episode = parts[3].toIntOrNull() ?: 0

        var foundAny = false

        // === Server 1: VidLink (Main 4) ===
        try {
            invokeVidlink(tmdbId, season.takeIf { mediaType == "tv" }, episode.takeIf { mediaType == "tv" },
                subtitleCallback, callback)
            foundAny = true
        } catch (_: Exception) { }

        // === Server 2: VidRock (Main 5) ===
        try {
            invokeVidrock(tmdbId, season.takeIf { mediaType == "tv" }, episode.takeIf { mediaType == "tv" },
                callback)
            foundAny = true
        } catch (_: Exception) { }

        // === Server 3: VidZee (Main 6) — tries all 8 servers ===
        try {
            invokeVidzee(tmdbId, season.takeIf { mediaType == "tv" }, episode.takeIf { mediaType == "tv" },
                subtitleCallback, callback)
            foundAny = true
        } catch (_: Exception) { }

        return foundAny
    }

    // ============================================================
    //  Extractor: VidLink (vidlink.pro)
    // ============================================================
    //  Flow:
    //    1. GET https://enc-dec.app/api/enc-vidlink?text={tmdbId}  → {"result":"<enc>"}
    //    2. GET https://vidlink.pro/api/b/{movie|tv}/{enc}[/S/E]    → JSON with stream.playlist (m3u8)
    //    3. Parse the playlist URL, extract referer/origin from ?headers= query param
    //    4. Use M3u8Helper to generate quality-specific links
    //    5. Extract subtitle captions (24+ languages for popular movies)
    //
    private suspend fun invokeVidlink(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$tmdbId"
        val encResponse = app.get(encUrl, headers = baseHeaders).text
        val encData = try {
            JSONObject(encResponse).optString("result")
        } catch (_: Exception) { "" }.takeIf { it.isNotEmpty() } ?: return

        val base = "https://vidlink.pro"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Referer" to "$base/",
            "Origin" to base
        )

        val apiUrl = if (season == null) {
            "$base/api/b/movie/$encData"
        } else {
            if (episode == null) return
            "$base/api/b/tv/$encData/$season/$episode"
        }

        val epResponse = app.get(apiUrl, headers = headers).text
        val json = JSONObject(epResponse)
        val stream = json.optJSONObject("stream") ?: return
        val m3u8 = stream.optString("playlist").takeIf { it.isNotEmpty() } ?: return

        // Extract referer/origin from ?headers= query param if present
        val headersJson = Regex("""[?&]headers=([^&]+)""").find(m3u8)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
        var referer = "$base/"
        var origin = base
        if (!headersJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(headersJson)
                obj.optString("referer").takeIf { it.isNotEmpty() }?.let { referer = it }
                obj.optString("origin").takeIf { it.isNotEmpty() }?.let { origin = it }
            } catch (_: Exception) { }
        }

        val m3u8url = m3u8.substringBefore("?")
        generateM3u8(
            "VidLink",
            m3u8url,
            referer = referer,
            headers = mapOf(
                "Origin"  to origin,
                "Referer" to "$origin/"
            )
        ).forEach(callback)

        // Extract subtitle captions
        val captions = stream.optJSONArray("captions") ?: json.optJSONArray("captions")
        if (captions != null) {
            for (i in 0 until captions.length()) {
                try {
                    val cap = captions.optJSONObject(i) ?: continue
                    val lang = cap.optString("language").takeIf { it.isNotEmpty() } ?: "Unknown"
                    val url = cap.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    subtitleCallback.invoke(newSubtitleFile(lang, url))
                } catch (_: Exception) { }
            }
        }
    }

    // ============================================================
    //  Extractor: VidRock (vidrock.ru)
    // ============================================================
    //  Flow:
    //    1. AES-CBC encrypt the TMDB ID (or "tmdb_s_e" for TV) using a hardcoded key
    //    2. GET https://vidrock.ru/api/{movie|tv}/{enc}  → JSON with multiple sources
    //    3. For each source: if URL contains "/playlist/", fetch the playlist JSON for resolutions
    //    4. Otherwise emit the m3u8/mp4 directly
    //
    private suspend fun invokeVidrock(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (season == null) "movie" else "tv"
        val encoded = vidrockEncode(tmdbId, type, season, episode)
        val apiUrl = "https://vidrock.ru/api/$type/$encoded"
        val response = app.get(apiUrl, headers = baseHeaders).text
        val sourcesJson = JSONObject(response)

        val vidrockHeaders = mapOf("Origin" to "https://vidrock.ru")

        for (key in sourcesJson.keys()) {
            try {
                val sourceObj = sourcesJson.optJSONObject(key) ?: continue
                val rawUrl = sourceObj.optString("url", "")
                val lang = sourceObj.optString("language", "Unknown")
                if (rawUrl.isBlank() || rawUrl == "null") continue

                val safeUrl = if (rawUrl.contains("%")) {
                    URLDecoder.decode(rawUrl, "UTF-8")
                } else rawUrl

                val displayName = "VidRock [$key] $lang"

                when {
                    safeUrl.contains("/playlist/") -> {
                        // Fetch the playlist JSON to get resolution-specific URLs
                        try {
                            val playlistResponse = app.get(safeUrl, headers = vidrockHeaders).text
                            val playlistArray = org.json.JSONArray(playlistResponse)
                            for (j in 0 until playlistArray.length()) {
                                val item = playlistArray.optJSONObject(j) ?: continue
                                val itemUrl = item.optString("url", "")
                                if (itemUrl.isBlank()) continue
                                val res = item.optInt("resolution", 0)
                                callback.invoke(
                                    newExtractorLink(
                                        source = "VidRock-$key",
                                        name = displayName,
                                        url = itemUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.headers = vidrockHeaders
                                        this.quality = when (res) {
                                            360 -> Qualities.P360.value
                                            480 -> Qualities.P480.value
                                            720 -> Qualities.P720.value
                                            1080 -> Qualities.P1080.value
                                            else -> Qualities.Unknown.value
                                        }
                                    }
                                )
                            }
                        } catch (_: Exception) { }
                    }
                    safeUrl.contains(".m3u8", ignoreCase = true) -> {
                        generateM3u8(
                            "VidRock-$key",
                            safeUrl,
                            referer = "https://vidrock.ru/",
                            headers = vidrockHeaders
                        ).forEach(callback)
                    }
                    safeUrl.contains(".mp4", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "VidRock-$key",
                                name = "$displayName MP4",
                                url = safeUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = vidrockHeaders
                            }
                        )
                    }
                    else -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "VidRock-$key",
                                name = displayName,
                                url = safeUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.headers = vidrockHeaders
                            }
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // VidRock AES-CBC encryption of the TMDB ID
    private fun vidrockEncode(tmdb: Int, type: String, season: Int?, episode: Int?): String {
        // Base64-decoded key: "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9" (32 bytes, used as both key and IV[:16])
        val zw = "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9"
        val s = if (type == "tv" && season != null && episode != null) {
            "${tmdb}_${season}_${episode}"
        } else {
            tmdb.toString()
        }
        val keyBytes = zw.toByteArray(Charsets.UTF_8)
        val ivBytes = zw.substring(0, 16).toByteArray(Charsets.UTF_8)
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(s.toByteArray(Charsets.UTF_8))
        // URL-safe base64 (no padding)
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    // ============================================================
    //  Extractor: VidZee (player.vidzee.wtf)
    // ============================================================
    //  Flow:
    //    1. For each server sr=1..8, GET https://player.vidzee.wtf/api/server?id={tmdb}&sr={sr}[&ss=S&ep=E]
    //    2. Response JSON has a "url" array with encrypted "link" fields
    //    3. Each link is base64-encoded "iv_b64:ciphertext_b64"
    //    4. AES-CBC decrypt with key "pleasedontscrapemesaywallah" (padded to 32 bytes)
    //    5. Emit the decrypted m3u8/mp4 URL
    //
    private suspend fun invokeVidzee(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Secret key (base64 of "pleasedontscrapemesaywallah"), padded to 32 bytes
        val secret = "pleasedontscrapemesaywallah"
        val keyBytes = secret.padEnd(32, '\u0000').toByteArray(Charsets.UTF_8)
        val defaultReferer = "https://player.vidzee.wtf/"

        for (sr in 1..8) {
            try {
                val apiUrl = if (season == null) {
                    "https://player.vidzee.wtf/api/server?id=$tmdbId&sr=$sr"
                } else {
                    "https://player.vidzee.wtf/api/server?id=$tmdbId&sr=$sr&ss=$season&ep=${episode ?: 1}"
                }

                val response = app.get(apiUrl, headers = baseHeaders).text
                val json = JSONObject(response)

                // Collect global headers from the response
                val globalHeaders = mutableMapOf<String, String>()
                json.optJSONObject("headers")?.let { headersObj ->
                    for (key in headersObj.keys()) {
                        globalHeaders[key] = headersObj.getString(key)
                    }
                }

                // Process each URL entry
                val urls = json.optJSONArray("url") ?: continue
                for (i in 0 until urls.length()) {
                    try {
                        val obj = urls.optJSONObject(i) ?: continue
                        val encryptedLink = obj.optString("link").takeIf { it.isNotBlank() } ?: continue
                        val name = obj.optString("name", "VidZee")
                        val type = obj.optString("type", "hls")
                        val lang = obj.optString("lang", "Unknown")
                        val flag = obj.optString("flag", "")

                        val finalUrl = try {
                            decryptVidzeeUrl(encryptedLink, keyBytes)
                        } catch (_: Exception) {
                            // If decryption fails, skip this link rather than emit garbage
                            continue
                        }

                        // Validate the URL
                        if (!finalUrl.startsWith("http")) continue

                        val headersMap = globalHeaders.toMutableMap()
                        val referer = headersMap["referer"] ?: defaultReferer
                        val displayName = if (flag.isNotBlank()) {
                            "VidZee $name ($lang - $flag)"
                        } else {
                            "VidZee $name ($lang)"
                        }

                        val linkType = if (type.equals("hls", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = "VidZee",
                                name = displayName,
                                url = finalUrl,
                                type = linkType
                            ) {
                                this.referer = referer
                                this.headers = headersMap
                                this.quality = Qualities.P1080.value
                            }
                        )
                    } catch (_: Exception) { }
                }

                // Extract subtitle tracks
                val subs = json.optJSONArray("tracks")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        try {
                            val sub = subs.optJSONObject(i) ?: continue
                            val subLang = sub.optString("lang", "Unknown")
                            val subUrl = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                            subtitleCallback.invoke(newSubtitleFile(subLang, subUrl))
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
                // One failing server shouldn't kill the rest
            }
        }
    }

    // VidZee URL decryption: base64 → "iv_b64:ct_b64" → AES-CBC decrypt
    private fun decryptVidzeeUrl(encryptedUrl: String, keyBytes: ByteArray): String {
        val decoded = String(Base64.decode(encryptedUrl, Base64.DEFAULT), Charsets.UTF_8)
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid encrypted URL format")

        val iv = Base64.decode(parts[0], Base64.DEFAULT)
        val ciphertext = Base64.decode(parts[1], Base64.DEFAULT)

        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    // ============================================================
    //  Helpers: convert JSON results to SearchResponse
    // ============================================================
    private fun SearchResult.toSearchResponse(): SearchResponse {
        val id = this.id ?: return newMovieSearchResponse("", "", TvType.Movie) {}
        val mediaType = this.media_type ?: if (first_air_date != null) "tv" else "movie"
        val title = this.title ?: this.name ?: ""
        val poster = poster_path?.let { imgBase + it }
        val year = (release_date ?: first_air_date)?.substring(0, 4)?.toIntOrNull()
        val url = "$mainUrl/watch/$id?type=$mediaType"

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun RecResult.toSearchResponse(): SearchResponse {
        val id = this.id ?: return newMovieSearchResponse("", "", TvType.Movie) {}
        val mediaType = this.media_type ?: if (first_air_date != null) "tv" else "movie"
        val title = this.title ?: this.name ?: ""
        val poster = poster_path?.let { imgBase + it }
        val year = (release_date ?: first_air_date)?.substring(0, 4)?.toIntOrNull()
        val url = "$mainUrl/watch/$id?type=$mediaType"

        return if (mediaType == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ============================================================
    //  Data classes for JSON parsing
    // ============================================================
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ShawtsResponse(
        @JsonProperty("results") val results: List<SearchResult>,
        @JsonProperty("page") val page: Int,
        @JsonProperty("has_more") val has_more: Boolean
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchApiResponse(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("results") val results: List<SearchResult>,
        @JsonProperty("total_pages") val total_pages: Int?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("media_type") val media_type: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("vote_average") val vote_average: Double?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecommendationsResponse(
        @JsonProperty("results") val results: List<RecResult>
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RecResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("media_type") val media_type: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DetailsResponse(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val original_title: String?,
        @JsonProperty("original_name") val original_name: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("episode_run_time") val episode_run_time: List<Int>?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("genres") val genres: List<Genre>?,
        @JsonProperty("seasons") val seasons: List<Season>?,
        @JsonProperty("number_of_seasons") val number_of_seasons: Int?,
        @JsonProperty("number_of_episodes") val number_of_episodes: Int?,
        @JsonProperty("imdb_id") val imdb_id: String?,
        @JsonProperty("homepage") val homepage: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("episode_count") val episode_count: Int?,
        @JsonProperty("air_date") val air_date: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("overview") val overview: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SeasonResponse(
        @JsonProperty("id") val id: String?,
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("episodes") val episodes: List<Episode>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Episode(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("episode_number") val episode_number: Int?,
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("air_date") val air_date: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("vote_average") val vote_average: Double?
    )
}
