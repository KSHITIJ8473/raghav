package com.laddu100.senshi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SenshiAnime(
    val id: Int? = null,
    val public_id: String? = null,
    val anime_picture: String? = null,
    val trailer: String? = null,
    val title: String? = null,
    val title_english: String? = null,
    val synonyms: String? = null,
    val type: String? = null,
    val ani_source: String? = null,
    val ani_episodes: String? = null,
    val ani_status: String? = null,
    val airing_date: String? = null,
    val duration: String? = null,
    val rating: String? = null,
    val score: Double? = null,
    val scored_by: Int? = null,
    val ani_description: String? = null,
    val ani_season: String? = null,
    val ani_year: Int? = null,
    val genres: String? = null,
    val producers: String? = null,
    val studios: String? = null,
    val anilist_id: Int? = null,
    val sub_count: Int? = null,
    val dub_count: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SenshiEpisode(
    val id: Int? = null,
    val ep_id: Int? = null,
    val mal_id: Int? = null,
    val ep_title: String? = null,
    val ep_filler: Boolean? = null,
    val ep_recap: Boolean? = null,
    val ep_thumbnail: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SenshiEmbed(
    val id: Int? = null,
    val public_id: String? = null,
    val remote_source_id: Int? = null,
    val url: String? = null,
    val status: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SenshiFilterResponse(
    val data: List<SenshiAnime> = emptyList(),
    val total: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SenshiLatestEmbed(
    val remote_source_id: Int? = null,
    val mal_id: Int? = null,
    val ep_id: Int? = null,
    val status: String? = null,
    val isBatch: Boolean? = null,
    val anime: SenshiAnime? = null,
    val episode: SenshiEpisode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidcloudFile(
    val src: String? = null,
    val quality: String? = null,
    val audio: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidcloudTrack(
    val url: String? = null,
    val vtt_url: String? = null,
    val label: String? = null,
    val html: String? = null,
    val default: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidcloudSource(
    val source: VidcloudFile? = null,
    val tracks: List<VidcloudTrack> = emptyList()
)
