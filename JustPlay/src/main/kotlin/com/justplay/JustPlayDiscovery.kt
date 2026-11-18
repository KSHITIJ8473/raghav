package com.justplay

import android.net.Uri
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

internal object PlayTmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private const val KEY_ALT = "98ae14df2b8d8f8f8136499daf79f0e0"

    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val CACHE_LIMIT = 64
    private const val TIMEOUT_MS = 10_000L
    private const val RETRY_GAP_MS = 500L
    private const val DEAD_NET_MS = 5_000L

    // the homepage opens ~35 rows at once, tmdb rejects that burst on mobile
    // networks so the calls are spread out instead
    private val gate = Semaphore(10)

    private class CacheEntry(val body: String, val at: Long)

    // the homepage hits the same endpoints on every visit, a short ttl cache keeps
    // a reopen from refetching every row over the network
    private val cache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
            size > CACHE_LIMIT
    }
    private val cacheLock = Any()

    // mobile networks drop single calls to tmdb all the time, both keys plus one
    // repeat ride out the bad moment
    private val attemptKeys = arrayOf(KEY, KEY_ALT, KEY)

    // when the network is fully down the remaining rows should not each burn
    // their whole retry chain, a short pause caps the damage
    @Volatile
    private var deadNetUntil = 0L

    private fun snapshot(url: String, maxAge: Long): JSONObject? {
        val hit = synchronized(cacheLock) { cache[url] } ?: return null
        if (System.currentTimeMillis() - hit.at >= maxAge) return null
        return try {
            JSONObject(hit.body)
        } catch (_: Exception) {
            null
        }
    }

    // lists carry results, detail pages and seasons carry id, anything else is
    // an error payload from the api
    private fun parseResult(body: String?): JSONObject? {
        if (body.isNullOrBlank()) return null
        return try {
            JSONObject(body).takeIf { it.has("results") || it.has("id") }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchRaw(path: String, key: String, query: String): String? {
        return try {
            val res = app.get("$BASE$path?api_key=$key&$query", timeout = TIMEOUT_MS)
            val body = res.text
            res.close()
            body
        } catch (_: Exception) {
            null
        }
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val query = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val url = "$BASE$path?api_key=$KEY&$query"
        snapshot(url, CACHE_TTL_MS)?.let { return it }
        if (System.currentTimeMillis() < deadNetUntil) return snapshot(url, Long.MAX_VALUE)

        gate.withPermit {
            snapshot(url, CACHE_TTL_MS)?.let { return it }
            var body: JSONObject? = null
            for (i in attemptKeys.indices) {
                if (i > 0) delay(RETRY_GAP_MS)
                body = parseResult(fetchRaw(path, attemptKeys[i], query))
                if (body != null) break
            }
            if (body == null) {
                deadNetUntil = System.currentTimeMillis() + DEAD_NET_MS
                return snapshot(url, Long.MAX_VALUE)
            }
            val raw = body.toString()
            synchronized(cacheLock) {
                cache[url] = CacheEntry(raw, System.currentTimeMillis())
            }
            return body
        }
    }

    fun imageUrl(path: String?): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return if (path.startsWith("http")) path else "https://image.tmdb.org/t/p/original$path"
    }

    fun toSearchResponse(api: MainAPI, item: JSONObject, fallbackType: String): SearchResponse? {
        val id = item.optInt("id", 0)
        if (id == 0) return null
        val mediaType = item.optString("media_type").ifBlank { fallbackType }
        if (mediaType != "movie" && mediaType != "tv") return null
        val title = item.optString("title").ifBlank { item.optString("name") }
        if (title.isBlank()) return null
        val poster = imageUrl(item.optString("poster_path"))
        val year = item.optString("release_date").ifBlank { item.optString("first_air_date") }.take(4).toIntOrNull()
        val type = if (mediaType == "movie") TvType.Movie else TvType.TvSeries
        return api.newMovieSearchResponse(title, PlayTmdbData(id, mediaType).toJson(), type) {
            this.posterUrl = poster
            this.year = year
            score = Score.from10(item.optDouble("vote_average", 0.0))
        }
    }
}

internal object PlayDiscovery {

    const val ROW_GENRE = "mixed_genre"

    fun genreData(genreId: Int): String = "$ROW_GENRE&with_genres=$genreId"

    val genreRows = listOf(
        28 to "Action",
        35 to "Comedy",
        18 to "Drama",
        53 to "Thriller",
        12 to "Adventure",
        80 to "Crime",
        27 to "Horror",
        10749 to "Romance",
        878 to "Sci-Fi",
        14 to "Fantasy",
        9648 to "Mystery",
        99 to "Documentary",
        10751 to "Family"
    )

    data class LanguageRow(val code: String, val label: String, val minVotes: Int)

    val languageRows = listOf(
        LanguageRow("en", "English Movies", 300),
        LanguageRow("hi", "Hindi Movies", 20),
        LanguageRow("ta", "Tamil Movies", 10),
        LanguageRow("te", "Telugu Movies", 10),
        LanguageRow("ml", "Malayalam Movies", 10),
        LanguageRow("kn", "Kannada Movies", 10),
        LanguageRow("pa", "Punjabi Movies", 5),
        LanguageRow("bn", "Bengali Movies", 5),
        LanguageRow("mr", "Marathi Movies", 5),
        LanguageRow("ko", "Korean Movies", 20)
    )

    fun languageData(row: LanguageRow): String =
        "/discover/movie&with_original_language=${row.code}" +
            "&sort_by=popularity.desc&vote_count.gte=${row.minVotes}"

    private fun emptyRow(name: String): HomePageResponse =
        newHomePageResponse(name, emptyList<SearchResponse>(), hasNext = false)

    private fun interleave(first: List<SearchResponse>, second: List<SearchResponse>): List<SearchResponse> {
        val out = ArrayList<SearchResponse>(first.size + second.size)
        var i = 0
        var j = 0
        while (i < first.size || j < second.size) {
            if (i < first.size) out.add(first[i++])
            if (j < second.size) out.add(second[j++])
        }
        return out
    }

    private suspend fun discoverPage(
        api: MainAPI,
        path: String,
        extra: Map<String, String>,
        page: Int,
        fallbackType: String
    ): Pair<List<SearchResponse>, Boolean> {
        val params = extra + mapOf(
            "language" to "en-US",
            "page" to "$page",
            "include_adult" to "false"
        )
        val json = PlayTmdb.get(path, params) ?: return emptyList<SearchResponse>() to false
        val arr = json.optJSONArray("results") ?: return emptyList<SearchResponse>() to false
        val out = ArrayList<SearchResponse>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            PlayTmdb.toSearchResponse(api, item, fallbackType)?.let(out::add)
        }
        val hasNext = json.optInt("page", 1) < json.optInt("total_pages", 1)
        return out to hasNext
    }

    private suspend fun mixedDiscover(
        api: MainAPI,
        moviePath: String,
        movieExtra: Map<String, String>,
        tvPath: String,
        tvExtra: Map<String, String>,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        val movies = async { discoverPage(api, moviePath, movieExtra, page, "movie") }
        val shows = async { discoverPage(api, tvPath, tvExtra, page, "tv") }
        val movieSide = movies.await()
        val tvSide = shows.await()
        interleave(movieSide.first, tvSide.first) to (movieSide.second || tvSide.second)
    }

    suspend fun genrePage(api: MainAPI, page: Int, data: String, name: String): HomePageResponse {
        if (!data.startsWith(ROW_GENRE)) return emptyRow(name)
        val genreId = data.substringAfter("with_genres=", "").toIntOrNull()
        if (genreId == null || genreId == 0) return emptyRow(name)
        val (list, hasNext) = mixedDiscover(
            api,
            "/discover/movie",
            mapOf(
                "with_genres" to "$genreId",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "100"
            ),
            "/discover/tv",
            mapOf(
                "with_genres" to "$genreId",
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "30"
            ),
            page
        )
        return newHomePageResponse(name, list, hasNext = hasNext)
    }
}
