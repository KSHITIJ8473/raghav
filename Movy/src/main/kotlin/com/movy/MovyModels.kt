package com.movy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyTmdbPage(
    @JsonProperty("results") val results: List<MovyTmdbItem>? = null,
    @JsonProperty("total_pages") val totalPages: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyTmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyExternalIds(
    @JsonProperty("imdb_id") val imdbId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyTranslationData(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyTranslation(
    @JsonProperty("iso_639_1") val iso: String? = null,
    @JsonProperty("data") val data: MovyTranslationData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyTranslations(
    @JsonProperty("translations") val translations: List<MovyTranslation>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyMovieDetails(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("genres") val genres: List<MovyGenre>? = null,
    @JsonProperty("external_ids") val externalIds: MovyExternalIds? = null,
    @JsonProperty("translations") val translations: MovyTranslations? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovySeasonSummary(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyTvDetails(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<MovyGenre>? = null,
    @JsonProperty("seasons") val seasons: List<MovySeasonSummary>? = null,
    @JsonProperty("external_ids") val externalIds: MovyExternalIds? = null,
    @JsonProperty("translations") val translations: MovyTranslations? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovyEpisodeEntry(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovySeasonDetails(
    @JsonProperty("episodes") val episodes: List<MovyEpisodeEntry>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovySeedReply(
    @JsonProperty("seed") val seed: String? = null,
    @JsonProperty("ttlMs") val ttlMs: Long? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovySource(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovySubtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovySourcesReply(
    @JsonProperty("sources") val sources: List<MovySource>? = null,
    @JsonProperty("subtitles") val subtitles: List<MovySubtitle>? = null,
)
