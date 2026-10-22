package com.justplay

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal object PlayTmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val KEY = "1865f43a0549ca50d341dd9ab8b29f49"
    private const val KEY_ALT = "98ae14df2b8d8f8f8136499daf79f0e0"

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val query = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        return try {
            JSONObject(app.get("$BASE$path?api_key=$KEY&$query", timeout = 12000L).text)
        } catch (e: Exception) {
            try {
                JSONObject(app.get("$BASE$path?api_key=$KEY_ALT&$query", timeout = 12000L).text)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun imageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
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

object PlayHistory {
    private const val KEY_WATCHED = "JUSTPLAY_WATCHED"
    private const val MAX_WATCHED = 50
    private val lock = Any()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WatchEntry(
        val id: Int? = null,
        val type: String? = null,
        val genres: List<Int> = emptyList(),
        val ts: Long = 0L
    )

    fun read(): List<WatchEntry> {
        val raw = try {
            CloudStreamApp.getKey<String>(KEY_WATCHED)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return try {
            parseJson<Array<WatchEntry>>(raw).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun record(detail: JSONObject, type: String) {
        val id = detail.optInt("id", 0)
        if (id == 0) return
        val genres = ArrayList<Int>(4)
        detail.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) {
                val gid = arr.optJSONObject(i)?.optInt("id", 0) ?: 0
                if (gid != 0) genres.add(gid)
            }
        }
        val entry = WatchEntry(id, type, genres, System.currentTimeMillis())
        synchronized(lock) {
            val kept = read().filterNot { it.id == entry.id && it.type == entry.type }
            val updated = (kept + entry).takeLast(MAX_WATCHED)
            try {
                CloudStreamApp.setKey(KEY_WATCHED, updated.toJson())
            } catch (_: Exception) {
            }
        }
    }

    fun topGenres(entries: List<WatchEntry>, count: Int): List<Int> =
        entries.flatMap { it.genres }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(count)
}

internal object PlayDiscovery {

    const val ROW_SURPRISE = "surprise_me"
    const val ROW_RECOMMENDED = "recommended"
    const val ROW_GENRE = "mixed_genre"

    fun genreData(genreId: Int): String = "$ROW_GENRE&with_genres=$genreId"

    val genreRows = listOf(
        28 to "Action",
        35 to "Comedy",
        18 to "Drama",
        53 to "Thriller",
        12 to "Adventure",
        80 to "Crime",
        16 to "Animation",
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
        LanguageRow("ja", "Japanese Movies", 50),
        LanguageRow("ko", "Korean Movies", 20)
    )

    fun languageData(row: LanguageRow): String =
        "/discover/movie&with_original_language=${row.code}" +
            "&sort_by=popularity.desc&vote_count.gte=${row.minVotes}"

    fun recommendedRowName(): String =
        if (PlayHistory.read().isEmpty()) "Fresh Picks" else "Recommended for You"

    // capped so the seen set never grows without bound, it just starts over
    private val shownUrls = ConcurrentHashMap.newKeySet<String>()
    private const val SHOWN_CAP = 3000
    private const val SURPRISE_BATCH = 20

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

    // movie and tv calls run side by side, it halves the wait on every mixed row
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

    private suspend fun topRatedBatch(
        api: MainAPI,
        path: String,
        maxPage: Int,
        fallbackType: String
    ): List<SearchResponse> {
        val json = PlayTmdb.get(
            path,
            mapOf("page" to "${Random.nextInt(1, maxPage + 1)}", "language" to "en-US")
        ) ?: return emptyList()
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<SearchResponse>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("poster_path").isBlank()) continue
            PlayTmdb.toSearchResponse(api, item, fallbackType)?.let(out::add)
        }
        return out
    }

    suspend fun surprisePage(api: MainAPI, name: String): HomePageResponse {
        if (shownUrls.size > SHOWN_CAP) shownUrls.clear()
        val out = ArrayList<SearchResponse>(SURPRISE_BATCH + 4)
        var attempts = 0
        while (out.size < SURPRISE_BATCH && attempts < 3) {
            attempts++
            val batch = coroutineScope {
                val movies = async { topRatedBatch(api, "/movie/top_rated", 500, "movie") }
                val shows = async { topRatedBatch(api, "/tv/top_rated", 120, "tv") }
                (movies.await() + shows.await()).shuffled()
            }
            for (card in batch) {
                if (out.size >= SURPRISE_BATCH + 4) break
                if (shownUrls.add(card.url)) out.add(card)
            }
        }
        return newHomePageResponse(name, out, hasNext = true)
    }

    private fun tmdbIdOf(url: String): Int? = try {
        parseJson<PlayTmdbData>(url).id
    } catch (_: Exception) {
        null
    }

    private suspend fun freshPage(api: MainAPI, page: Int, name: String): HomePageResponse {
        val (list, hasNext) = mixedDiscover(
            api,
            "/movie/now_playing", emptyMap(),
            "/tv/on_the_air", emptyMap(),
            page
        )
        return newHomePageResponse(name, list, hasNext = hasNext)
    }

    suspend fun recommendedPage(api: MainAPI, page: Int, name: String): HomePageResponse {
        val entries = PlayHistory.read()
        if (entries.isEmpty()) return freshPage(api, page, name)
        val genres = PlayHistory.topGenres(entries, 2)
        if (genres.isEmpty()) return freshPage(api, page, name)
        val watched = entries.mapNotNull { it.id }.toHashSet()
        val genreParam = genres.joinToString(",")
        val (mixed, more) = mixedDiscover(
            api,
            "/discover/movie",
            mapOf(
                "with_genres" to genreParam,
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "300"
            ),
            "/discover/tv",
            mapOf(
                "with_genres" to genreParam,
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "100"
            ),
            page
        )
        val unseen = mixed.filterNot { watched.contains(tmdbIdOf(it.url)) }
        // fall back to the unfiltered mix when every title on the page was already opened
        val list = if (unseen.isEmpty() && mixed.isNotEmpty()) mixed else unseen
        return newHomePageResponse(name, list, hasNext = more && page < 40)
    }
}
