package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

@JsonIgnoreProperties(ignoreUnknown = true)
data class AninekoAniListSearchResponse(@param:JsonProperty("data") val data: AninekoAniListData? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AninekoAniListData(@param:JsonProperty("Media") val Media: AninekoAniListMedia? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AninekoAniListMedia(@param:JsonProperty("id") val id: Int? = null)

private val ANILIST_ID_QUERY = """
    query(${'$'}search: String) {
        Media(search: ${'$'}search, type: ANIME) {
            id
        }
    }
""".trimIndent()

suspend fun getAnilistId(title: String): Int? {
    return try {
        val requestData = mapOf(
            "query" to ANILIST_ID_QUERY,
            "variables" to mapOf("search" to title)
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val res = app.post(
            "https://graphql.anilist.co",
            headers = ANILIST_HEADERS,
            requestBody = requestData
        ).parsedSafe<AninekoAniListSearchResponse>()

        res?.data?.Media?.id
    } catch (e: Exception) {
        Log.e("RaghavAnime", "[Anineko] getAnilistId failed for '$title': ${e.message}")
        null
    }
}
