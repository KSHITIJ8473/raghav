package com.laddu100.reanime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

object ReAnimeApi {

    const val MAIN_URL = "https://reanime.to"
    const val FLIX_EMBED_BASE = "https://flixcloud.cc"

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val BROWSER_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Referer" to "$MAIN_URL/"
    )

    private val homeMutex = Mutex()
    private val homeCursors = mutableMapOf<String, String?>()

    private inline fun <reified T> parse(text: String): T? =
        try {
            mapper.readValue(text, T::class.java)
        } catch (e: Exception) {
            null
        }

    private suspend fun getJson(
        url: String,
        headers: Map<String, String> = BROWSER_HEADERS
    ): String? = try {
        val resp = app.get(url, headers = headers)
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        null
    }

    suspend fun search(query: String, page: Int, limit: Int = 40): List<SearchItem> {
        val offset = (page - 1) * limit
        val body = getJson("$MAIN_URL/api/v1/search?q=${urlEncode(query)}&limit=$limit&offset=$offset")
            ?: return emptyList()
        return parse<SearchEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun searchSorted(sort: String, page: Int, limit: Int = 40): List<SearchItem> {
        val offset = (page - 1) * limit
        val body = getJson("$MAIN_URL/api/v1/search?limit=$limit&offset=$offset&sort=$sort")
            ?: return emptyList()
        return parse<SearchEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun homeSection(
        section: String,
        page: Int,
        limit: Int = 40
    ): Pair<List<SearchItem>, Boolean> {
        val cursor = homeMutex.withLock {
            if (page <= 1) null else homeCursors["$section-$page"]
        }
        val sep = if (cursor == null) "?" else "&cursor=${urlEncode(cursor)}"
        val body = getJson("$MAIN_URL/api/v1/home/$section?limit=$limit$sep")
            ?: return Pair(emptyList<SearchItem>(), false)
        val env = parse<HomeEnvelope>(body) ?: return Pair(emptyList<SearchItem>(), false)
        val items = env.data ?: emptyList()
        if (page >= 1 && env.nextCursor != null) {
            homeMutex.withLock {
                homeCursors["$section-${page + 1}"] = env.nextCursor
            }
        }
        return items to (env.hasMore == true)
    }

    suspend fun animeDetail(slug: String): AnimeDetail? {
        val body = getJson("$MAIN_URL/api/v1/anime/${urlEncode(slug)}") ?: return null
        return parse<AnimeDetail>(body)
    }

    suspend fun episodes(slug: String): List<EpisodeEntry> {
        val body = getJson("$MAIN_URL/api/v1/anime/${urlEncode(slug)}/episodes?limit=2000")
            ?: return emptyList()
        return parse<EpisodesEnvelope>(body)?.data ?: emptyList()
    }

    suspend fun flixServers(
        anilistId: Int?,
        tmdbId: Int?,
        season: Int?,
        episode: Int
    ): List<FlixServer> {
        val url = when {
            anilistId != null && anilistId > 0 ->
                "$MAIN_URL/api/flix/$anilistId/$episode"
            tmdbId != null && tmdbId > 0 ->
                "$MAIN_URL/api/flix/0/$episode?tmdb=$tmdbId&season=${season ?: 1}"
            else -> return emptyList()
        }
        val body = getJson(url, BROWSER_HEADERS + mapOf("Referer" to "$MAIN_URL/watch/"))
            ?: return emptyList()
        val res = parse<FlixResponse>(body) ?: return emptyList()
        if (res.success != true) return emptyList()
        return res.servers ?: emptyList()
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
