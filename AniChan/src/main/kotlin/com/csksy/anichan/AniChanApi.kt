package com.csksy.anichan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay

object AniChanApi {

    const val MAIN_URL = "https://anichan.net"
    private const val TAG = "AniChan"
    private const val SESSION_ATTEMPTS = 4

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val BASE_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json"
    )

    private inline fun <reified T> parse(text: String): T? =
        try {
            mapper.readValue(text, T::class.java)
        } catch (e: Exception) {
            Log.d(TAG, "parse failed: ${e.message}")
            null
        }

    private suspend fun getJson(url: String): String? = try {
        val resp = app.get(url, headers = BASE_HEADERS)
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        Log.d(TAG, "GET $url failed: ${e.message}")
        null
    }

    suspend fun suggest(query: String): List<CatalogItem> {
        val body = getJson("$MAIN_URL/api/suggest?q=${urlEncode(query)}") ?: return emptyList()
        return parse<CatalogEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun trending(page: Int): List<CatalogItem> {
        val body = getJson("$MAIN_URL/api/catalog/trending?page=$page") ?: return emptyList()
        return parse<CatalogEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun airing(page: Int): List<CatalogItem> {
        val body = getJson("$MAIN_URL/api/catalog/airing?page=$page") ?: return emptyList()
        return parse<CatalogEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun animeDetail(id: Int): CatalogItem? {
        val body = getJson("$MAIN_URL/api/catalog/anime/$id") ?: return null
        return parse<CatalogItem>(body)
    }

    suspend fun watchInfo(id: Int): WatchInfo? {
        val body = getJson("$MAIN_URL/api/watch/episodes?anilistId=$id") ?: return null
        return parse<WatchInfo>(body)
    }

    // the session cookie is single use, every servers call needs a fresh one
    private suspend fun newWatchSession(): String? {
        return try {
            val resp = app.post(
                "$MAIN_URL/api/watch/session",
                headers = BASE_HEADERS + mapOf("Origin" to MAIN_URL),
                json = mapOf("token" to "")
            )
            if (!resp.isSuccessful) return null
            for (cookie in resp.headers.values("set-cookie")) {
                if (cookie.startsWith("anichan_ws=")) {
                    return cookie.substringBefore(";").substringAfter("anichan_ws=")
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "watch session failed: ${e.message}")
            null
        }
    }

    suspend fun watchServers(anilistId: Int, ep: Int, category: String): List<Server> {
        val url = "$MAIN_URL/api/watch/servers?anilistId=$anilistId&ep=$ep&category=$category"
        repeat(SESSION_ATTEMPTS) {
            val cookie = newWatchSession() ?: return emptyList()
            try {
                val resp = app.get(
                    url,
                    headers = BASE_HEADERS + mapOf("Cookie" to "anichan_ws=$cookie")
                )
                if (resp.isSuccessful) {
                    return parse<ServersEnvelope>(resp.text)?.servers ?: emptyList()
                }
            } catch (e: Exception) {
                Log.d(TAG, "watch servers failed: ${e.message}")
            }
            delay(400)
        }
        return emptyList()
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
