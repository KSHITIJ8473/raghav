package com.csksy.anichan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app

object AniChanApi {

    private const val TAG = "AniChan"
    private const val DEFAULT_URL = "https://anichan.to"

    @Volatile
    private var host = DEFAULT_URL

    suspend fun refreshDomain() {
        FirebaseDomainHelper.getDomain("anichan")?.let { host = it }
    }

    fun url(): String = host

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
            Log.e(TAG, "parse failed: ${e.message}")
            null
        }

    private suspend fun getJson(url: String): String? = try {
        val resp = app.get(url, headers = BASE_HEADERS)
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        Log.e(TAG, "GET failed: ${e.message}")
        null
    }

    suspend fun suggest(query: String): List<CatalogItem> {
        val body = getJson("$host/api/suggest?q=${urlEncode(query)}") ?: return emptyList()
        return parse<CatalogEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun trending(page: Int): List<CatalogItem> {
        val body = getJson("$host/api/catalog/trending?page=$page") ?: return emptyList()
        return parse<CatalogEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun airing(page: Int): List<CatalogItem> {
        val body = getJson("$host/api/catalog/airing?page=$page") ?: return emptyList()
        return parse<CatalogEnvelope>(body)?.results ?: emptyList()
    }

    suspend fun animeDetail(id: Int): CatalogItem? {
        val body = getJson("$host/api/catalog/anime/$id") ?: return null
        return parse<CatalogItem>(body)
    }

    suspend fun watchInfo(id: Int): WatchInfo? {
        val body = getJson("$host/api/watch/episodes?anilistId=$id") ?: return null
        return parse<WatchInfo>(body)
    }

    fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
