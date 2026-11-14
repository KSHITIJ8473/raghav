package com.play4u

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchPage(
    val ok: Boolean? = false,
    val items: List<SearchItem> = emptyList(),
    val found: Int? = 0,
    val page: Int? = 1,
    val pages: Int? = 1
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    val id: Int? = null,
    val title: String? = null,
    val imdb_id: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val poster_url: String? = null,
    val backdrop: String? = null,
    val media_type: String? = null,
    val watch: String? = null,
    val quality: String? = null,
    val age_rating: String? = null,
    val langs: List<String>? = null,
    val genre_names: List<String>? = null,
    val runtime_minutes: Int? = null,
    val series_badge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayPayload(
    val ok: Boolean? = false,
    val item: PlayItem? = null,
    val streams: List<PlayStream> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayItem(
    val viewkey: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val runtime_minutes: Int? = null,
    val poster_url: String? = null,
    val cover_url: String? = null,
    val tags: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val cast: List<Person>? = null,
    val directors: List<Person>? = null,
    val trailer_key: String? = null,
    val languages: List<AudioLang>? = null
) {
    fun year(): Int? = release_date?.take(4)?.toIntOrNull()
        ?: tags.firstNotNullOfOrNull { t ->
            Regex("\\((19|20)\\d{2}\\)").findAll(t).lastOrNull()?.value
                ?.substring(1, 5)?.toIntOrNull()
        }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Person(
    val name: String? = null,
    val character: String? = null,
    val profile: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayStream(
    val id: Long? = null,
    val label: String? = null,
    val m3u8_url: String? = null,
    val m3u8_path: String? = null,
    val domain_name: String? = null,
    val languages: List<AudioLang>? = null,
    val qualities: List<StreamQuality>? = null,
    val print_name: String? = null,
    val has_imax: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioLang(
    val code: String? = null,
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamQuality(
    val slug: String? = null,
    val label: String? = null,
    val height: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeriesPayload(
    val key: String? = null,
    val imdb_id: String? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val seasons: Map<String, SeasonInfo> = emptyMap()
) {
    fun year(): Int? = seasons.values.mapNotNull { it.year }.minOrNull()
    fun running(): Boolean = seasons.values.any { it.completed != true }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonInfo(
    val number: Int? = null,
    val name: String? = null,
    val year: Int? = null,
    val completed: Boolean? = null,
    val poster: String? = null,
    val episodes: Map<String, EpisodeInfo> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeInfo(
    val number: Int? = null,
    val title: String? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val still: String? = null,
    val hls: String? = null,
    val languages: List<AudioLang>? = null,
    val qualities: List<StreamQuality>? = null
)

// goes straight into the episode url, loadLinks reads it back without
// another page fetch
@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeRef(
    val u: String? = null,
    val q: String? = null,
    val a: String? = null
)
