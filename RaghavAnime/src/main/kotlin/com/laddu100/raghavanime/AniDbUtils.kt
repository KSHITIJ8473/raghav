package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log

fun parseAnimeData(jsonString: String): MetaAnimeData? {
    Log.d("RaghavAnime", "[AniDb] parseAnimeData parsing mappings json (len ${jsonString.length})")
    return try {
        val objectMapper = ObjectMapper()
        objectMapper.readValue(jsonString, MetaAnimeData::class.java)
    } catch (e: Exception) {
        Log.e("RaghavAnime", "[AniDb] parseAnimeData failed: ${e.message}")
        null
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageData(
    val coverType: String? = null,
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    val episode: String? = null,
    val airdate: String? = null,
    val airDateUtc: String? = null,
    val length: Int? = null,
    val runtime: Int? = null,
    val image: String? = null,
    val title: Map<String, String?>? = null,
    val overview: String? = null,
    val rating: String? = null,
    val finaleType: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    val titles: Map<String, String?>? = null,
    val images: List<ImageData>? = null,
    val episodes: Map<String, MetaEpisode>? = null,
)
