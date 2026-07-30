package com.laddu100.cinestream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamHomeResponse(
    @JsonProperty("heroData") val heroData: CineStreamSection? = null,
    @JsonProperty("data") val data: List<CineStreamSection> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamSection(
    @JsonProperty("sectionType") val sectionType: String? = null,
    @JsonProperty("title") val title: String = "",
    @JsonProperty("items") val items: List<CineStreamItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamItem(
    @JsonProperty("_id") val _id: String,
    @JsonProperty("tmdbId") val tmdbId: Long? = null,
    @JsonProperty("title") val title: String = "",
    @JsonProperty("subTitle") val subTitle: String? = null,
    @JsonProperty("type") val type: String = "movie",
    @JsonProperty("contentType") val contentType: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null,
    @JsonProperty("runtime") val runtime: Long? = null,
    @JsonProperty("voteAverage") val voteAverage: Double? = null,
    @JsonProperty("genres") val genres: List<CineStreamGenre>? = null,
    @JsonProperty("categories") val categories: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamGenre(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamSearchResponse(
    @JsonProperty("movies") val movies: List<CineStreamItem> = emptyList(),
    @JsonProperty("series") val series: List<CineStreamItem> = emptyList(),
    @JsonProperty("actors") val actors: List<Any> = emptyList(),
    @JsonProperty("total") val total: Int = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamDetail(
    @JsonProperty("_id") val _id: String,
    @JsonProperty("tmdbId") val tmdbId: Long? = null,
    @JsonProperty("title") val title: String = "",
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("backdropPath") val backdropPath: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("firstAirDate") val firstAirDate: String? = null,
    @JsonProperty("runtime") val runtime: Long? = null,
    @JsonProperty("numberOfSeasons") val numberOfSeasons: Int? = null,
    @JsonProperty("voteAverage") val voteAverage: Double? = null,
    @JsonProperty("voteCount") val voteCount: Long? = null,
    @JsonProperty("originalLanguage") val originalLanguage: String? = null,
    @JsonProperty("trailerUrl") val trailerUrl: String? = null,
    @JsonProperty("genres") val genres: List<CineStreamGenre>? = null,
    @JsonProperty("cast") val cast: List<CineStreamCast>? = null,
    @JsonProperty("streamingLinks") val streamingLinks: List<CineStreamStreamLink>? = null,
    @JsonProperty("seasons") val seasons: List<CineStreamSeason>? = null,
    @JsonProperty("enableStream") val enableStream: Boolean = true,
    @JsonProperty("enableDownload") val enableDownload: Boolean = true
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamCast(
    @JsonProperty("tmdbId") val tmdbId: Long? = null,
    @JsonProperty("name") val name: String,
    @JsonProperty("character") val character: String? = null,
    @JsonProperty("profilePath") val profilePath: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamStreamLink(
    @JsonProperty("quality") val quality: String = "",
    @JsonProperty("url") val url: String,
    @JsonProperty("type") val type: String = "hls",
    @JsonProperty("isActive") val isActive: Boolean = true
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamSeason(
    @JsonProperty("tmdbId") val tmdbId: Long? = null,
    @JsonProperty("seasonNumber") val seasonNumber: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("posterPath") val posterPath: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("episodeCount") val episodeCount: Int = 0,
    @JsonProperty("episodes") val episodes: List<CineStreamEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamEpisode(
    @JsonProperty("tmdbId") val tmdbId: Long? = null,
    @JsonProperty("episodeNumber") val episodeNumber: Int,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("stillPath") val stillPath: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("runtime") val runtime: Long? = null,
    @JsonProperty("voteAverage") val voteAverage: Double? = null,
    @JsonProperty("streamingLinks") val streamingLinks: List<CineStreamStreamLink>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CineStreamFirebaseConfig(
    @JsonProperty("cinestream_url") val cinestream_url: String? = null,
    @JsonProperty("cinestream") val cinestream: String? = null,
    @JsonProperty("cine_url") val cine_url: String? = null
)
