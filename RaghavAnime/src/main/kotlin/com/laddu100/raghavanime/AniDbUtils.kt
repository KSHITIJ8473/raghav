package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper

private val mapper = ObjectMapper()

fun parseAnimeData(jsonString: String): MetaAnimeData? {
    return try {
        mapper.readValue(jsonString, MetaAnimeData::class.java)
    } catch (e: Exception) {
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
data class MetaMappings(
    val themoviedb_id: Int? = null,
    val imdb_id: String? = null,
    val anilist_id: Int? = null,
    val kitsu_id: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    val titles: Map<String, String?>? = null,
    val images: List<ImageData>? = null,
    val episodes: Map<String, MetaEpisode>? = null,
    val mappings: MetaMappings? = null,
)
