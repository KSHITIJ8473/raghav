package com.laddu100.reanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchEnvelope(
    @JsonProperty("results") val results: List<SearchItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("anime_id") val animeId: String? = null,
    @JsonProperty("title") val title: Title? = null,
    @JsonProperty("cover_image") val coverImage: CoverImage? = null,
    @JsonProperty("season_year") val seasonYear: Int? = null,
    @JsonProperty("subbed") val subbed: Int? = null,
    @JsonProperty("dubbed") val dubbed: Int? = null,
    @JsonProperty("average_score") val averageScore: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Title(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("native") val native: String? = null,
    @JsonProperty("user_preferred") val userPreferred: String? = null
) {
    fun display(): String? =
        english?.takeIf { it.isNotBlank() } ?: romaji?.takeIf { it.isNotBlank() }
            ?: native?.takeIf { it.isNotBlank() } ?: userPreferred?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoverImage(
    @JsonProperty("extra_large") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("medium") val medium: String? = null
) {
    fun best(): String? = extraLarge ?: large ?: medium
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class HomeEnvelope(
    @JsonProperty("data") val data: List<SearchItem>? = null,
    @JsonProperty("has_more") val hasMore: Boolean? = null,
    @JsonProperty("next_cursor") val nextCursor: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeDetail(
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
    @JsonProperty("external_seasons") val externalSeasons: ExternalSeasons? = null,
    @JsonProperty("title") val title: Title? = null,
    @JsonProperty("cover_image") val coverImage: CoverImage? = null,
    @JsonProperty("banner_image") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("tags") val tags: List<Tag>? = null,
    @JsonProperty("season_year") val seasonYear: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("average_score") val averageScore: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExternalSeasons(
    @JsonProperty("tmdb") val tmdb: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tag(
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesEnvelope(
    @JsonProperty("data") val data: List<EpisodeEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeEntry(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("aired") val aired: String? = null,
    @JsonProperty("subbed") val subbed: Boolean? = null,
    @JsonProperty("dubbed") val dubbed: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlixResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("servers") val servers: List<FlixServer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FlixServer(
    @JsonProperty("serverName") val serverName: String? = null,
    @JsonProperty("dataLink") val dataLink: String? = null
)
