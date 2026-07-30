package com.laddu100.cinestream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app

class CineStreamProvider : MainAPI() {
    override var mainUrl = "https://cinestream.kje.us"
    override var name = "CinestreamSite"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val TAG = "CineStream"

    @Volatile
    private var isUrlLoaded = false

    private suspend fun loadFirebaseUrl() {
        if (isUrlLoaded) return
        try {
            val response = app.get("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeout = 10_000L).text
            val config = parseJson<CineStreamFirebaseConfig>(response)
            val url = config.cinestream_url ?: config.cinestream ?: config.cine_url
            if (!url.isNullOrBlank()) {
                mainUrl = url.removeSuffix("/")
            }
            isUrlLoaded = true
        } catch (e: Exception) {
            isUrlLoaded = true
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiResponseWrapper(@JsonProperty("data") val data: String? = null)

    private fun decryptResponse(responseText: String): String? {
        return try {
            val wrapper = parseJson<ApiResponseWrapper>(responseText)
            if (wrapper.data.isNullOrBlank()) return null
            CineStreamCrypto.decrypt(wrapper.data)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt: ${e.message}")
            null
        }
    }

    override val mainPage = mainPageOf("home" to "Home")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadFirebaseUrl()
        return try {
            val response = cineStreamPost("$mainUrl/api/home", "{}", headers = mapOf(
                "Content-Type" to "application/json"
            ))
            val decrypted = decryptResponse(response.text) ?: return newHomePageResponse(request.name, emptyList())
            val home = parseJson<CineStreamHomeResponse>(decrypted)
            val sections = mutableListOf<HomePageList>()

            home.heroData?.let { hero ->
                val items = hero.items.mapNotNull { mapItemToSearchResponse(it) }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(hero.title.ifBlank { "Featured" }, items, false))
                }
            }

            for (section in home.data) {
                val items = section.items.mapNotNull { mapItemToSearchResponse(it) }
                if (items.isNotEmpty()) {
                    sections.add(HomePageList(section.title.ifBlank { "Section" }, items, false))
                }
            }

            newHomePageResponse(sections, hasNext = false)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun mapItemToSearchResponse(item: CineStreamItem): SearchResponse? {
        if (item._id.isBlank() || item.title.isBlank()) return null
        val rawType = item.contentType ?: item.type
        val type = if (rawType.equals("series", ignoreCase = true) || rawType.equals("tv", ignoreCase = true)) "series" else "movies"
        val tvType = if (type == "series") TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(item.title, "cine://$type/${item._id}", tvType) {
            this.posterUrl = item.posterPath
            this.year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        loadFirebaseUrl()
        return try {
            val searchBody = """{"q":${jsonEncode(query)},"limit":40}"""
            val response = cineStreamPost("$mainUrl/api/search", searchBody, headers = mapOf(
                "Content-Type" to "application/json"
            ))
            val searchResp = parseJson<CineStreamSearchResponse>(response.text)
            val results = mutableListOf<SearchResponse>()
            for (movie in searchResp.movies) {
                mapItemToSearchResponse(movie)?.let { results.add(it) }
            }
            for (series in searchResp.series) {
                mapItemToSearchResponse(series.copy(type = "series"))?.let { results.add(it) }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        loadFirebaseUrl()
        val payload = url.substringAfter("cine://")
        val parts = payload.split("/")
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1]
        if (type !in listOf("movies", "series") || id.isBlank()) return null

        return try {
            val detailsBody = """{"type":"$type","id":"$id"}"""
            val postResponse = cineStreamPost("$mainUrl/api/details", detailsBody, headers = mapOf(
                "Content-Type" to "application/json"
            ))
            val decrypted = decryptResponse(postResponse.text) ?: return null
            val detail = parseJson<CineStreamDetail>(decrypted)

            val tvType = if (type == "series") TvType.TvSeries else TvType.Movie
            val year = (detail.releaseDate ?: detail.firstAirDate)?.take(4)?.toIntOrNull()
            val cast = detail.cast?.take(15)?.map { ActorData(Actor(it.name, it.profilePath)) } ?: emptyList()
            val genres = detail.genres?.map { it.name } ?: emptyList()
            val rating = detail.voteAverage

            if (type == "series" && detail.seasons != null) {
                val episodes = mutableListOf<Episode>()
                for (season in detail.seasons) {
                    for (ep in season.episodes) {
                        val epData = "cine://series/${detail._id}/${season.seasonNumber}/${ep.episodeNumber}"
                        episodes.add(newEpisode(epData) {
                            this.season = season.seasonNumber
                            this.episode = ep.episodeNumber
                            this.name = ep.name ?: "S${season.seasonNumber}E${ep.episodeNumber}"
                            this.posterUrl = ep.stillPath ?: detail.backdropPath
                            this.description = ep.overview
                        })
                    }
                }
                newTvSeriesLoadResponse(detail.title, url, tvType, episodes) {
                    this.posterUrl = detail.posterPath
                    this.backgroundPosterUrl = detail.backdropPath
                    this.plot = detail.overview
                    this.year = year
                    this.tags = genres
                    this.actors = cast
                    this.score = rating?.let { Score.from10(it) }
                    this.duration = detail.runtime?.toInt()
                }
            } else {
                val movieData = "cine://movies/${detail._id}"
                newMovieLoadResponse(detail.title, url, tvType, movieData) {
                    this.posterUrl = detail.posterPath
                    this.backgroundPosterUrl = detail.backdropPath
                    this.plot = detail.overview
                    this.year = year
                    this.tags = genres
                    this.actors = cast
                    this.score = rating?.let { Score.from10(it) }
                    this.duration = detail.runtime?.toInt()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        return try {
            val payload = data.substringAfter("cine://")
            val parts = payload.split("/")
            val type = parts.getOrNull(0) ?: return false
            val id = parts.getOrNull(1) ?: return false

            val detailsBody = """{"type":"$type","id":"$id"}"""
            val postResponse = cineStreamPost("$mainUrl/api/details", detailsBody, headers = mapOf(
                "Content-Type" to "application/json"
            ))
            val decrypted = decryptResponse(postResponse.text) ?: return false
            val detail = parseJson<CineStreamDetail>(decrypted)

            val streamLinks: List<CineStreamStreamLink> = when {
                type == "movies" -> detail.streamingLinks ?: emptyList()
                type == "series" && parts.size >= 4 -> {
                    val seasonNum = parts[2].toIntOrNull() ?: 1
                    val episodeNum = parts[3].toIntOrNull() ?: 1
                    detail.seasons?.find { it.seasonNumber == seasonNum }
                        ?.episodes?.find { it.episodeNumber == episodeNum }
                        ?.streamingLinks ?: emptyList()
                }
                else -> emptyList()
            }

            if (streamLinks.isEmpty()) return false

            var found = false
            for (link in streamLinks) {
                if (!link.isActive || link.url.isBlank()) continue
                val quality = parseQuality(link.quality)

                try {
                    val links = M3u8Helper.generateM3u8(
                        source = "CinestreamSite",
                        streamUrl = link.url,
                        referer = mainUrl,
                        quality = quality,
                        headers = mapOf("Referer" to mainUrl),
                        name = "CinestreamSite - ${link.quality}"
                    )
                    links.forEach { m3u8Link ->
                        callback.invoke(m3u8Link)
                        found = true
                    }
                } catch (e: Exception) {
                    val el = newExtractorLink("CinestreamSite", "CinestreamSite - ${link.quality}", link.url, ExtractorLinkType.M3U8) {
                        this.quality = quality
                        this.referer = mainUrl
                    }
                    callback.invoke(el)
                    found = true
                }
            }
            found
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            false
        }
    }

    private fun parseQuality(q: String): Int {
        return when {
            q.contains("1080") -> Qualities.P1080.value
            q.contains("720") -> Qualities.P720.value
            q.contains("480") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun jsonEncode(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 32) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
