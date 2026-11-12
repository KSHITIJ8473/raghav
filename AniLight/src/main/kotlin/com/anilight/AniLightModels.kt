package com.anilight

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class Titles(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null
) {
    fun display(): String? = english?.takeIf { it.isNotBlank() }
        ?: romaji?.takeIf { it.isNotBlank() }
        ?: userPreferred?.takeIf { it.isNotBlank() }
        ?: native?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null
) {
    fun best(): String? = extraLarge?.takeIf { it.isNotBlank() }
        ?: large?.takeIf { it.isNotBlank() }
        ?: medium?.takeIf { it.isNotBlank() }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnimeMedia(
    val id: Int? = null,
    val slug: String? = null,
    val anilistId: Int? = null,
    val idMal: Int? = null,
    val title: Titles? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val subEpisodes: Int? = null,
    val dubEpisodes: Int? = null,
    val duration: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Double? = null,
    val popularity: Int? = null,
    val isAdult: Boolean? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val startDate: FuzzyDate? = null,
    val ageRating: String? = null,
    val trailer: Trailer? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FuzzyDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Trailer(
    val id: String? = null,
    val site: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaSection(
    val media: List<AnimeMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HomepageResponse(
    val trending: MediaSection? = null,
    val popular: MediaSection? = null,
    val seasonal: MediaSection? = null,
    val previousSeason: MediaSection? = null,
    val popularMovies: MediaSection? = null,
    val topRated: MediaSection? = null,
    val upcoming: MediaSection? = null,
    val recentlyAddedEpisodes: List<AnimeMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PageInfo(
    val currentPage: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
    val hasNextPage: Boolean? = null,
    val lastPage: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FilterResponse(
    val pageInfo: PageInfo? = null,
    val media: List<AnimeMedia>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchResponse(
    val id: Int? = null,
    val subEpisodes: Int? = null,
    val dubEpisodes: Int? = null,
    val episodes: List<EpisodeEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeEntry(
    val number: Int? = null,
    val title: String? = null,
    @JsonProperty("jp_title") val jpTitle: String? = null,
    val description: String? = null,
    val img: String? = null,
    val isFiller: Boolean? = null,
    @JsonProperty("embed_url") val embedUrl: Map<String, String>? = null,
    val airedAt: String? = null,
    val sub: Boolean? = null,
    val dub: Boolean? = null,
    val subReleased: Boolean? = null,
    val dubReleased: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourcesResponse(
    val tracks: List<CaptionTrack>? = null,
    val sources: List<SourceEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaptionTrack(
    val id: String? = null,
    val url: String? = null,
    val kind: String? = null,
    val lang: String? = null,
    val label: String? = null,
    @JsonProperty("default") val isDefault: Boolean? = null
)

// the self hosted rows carry one token per quality entry while the backup row packs every mirror token into a single list
@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceEntry(
    val url: Any? = null,
    val quality: String? = null
) {
    fun tokens(): List<String> = when (val u = url) {
        is String -> if (u.isBlank()) emptyList() else listOf(u)
        is List<*> -> u.filterIsInstance<String>().filter { it.isNotBlank() }
        else -> emptyList()
    }
}

object SubtitleLabels {

    private val names = mapOf(
        "ar" to "Arabic", "baq" to "Basque", "cat" to "Catalan", "cze" to "Czech",
        "dan" to "Danish", "de" to "German", "dut" to "Dutch", "en" to "English",
        "es" to "Spanish", "fil" to "Filipino", "fin" to "Finnish", "fr" to "French",
        "glg" to "Galician", "gre" to "Greek", "heb" to "Hebrew", "hrv" to "Croatian",
        "hun" to "Hungarian", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese",
        "ko" to "Korean", "may" to "Malay", "nob" to "Norwegian", "pol" to "Polish",
        "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "rum" to "Romanian",
        "swe" to "Swedish", "th" to "Thai", "tur" to "Turkish", "ukr" to "Ukrainian",
        "vi" to "Vietnamese", "zh" to "Chinese"
    )

    // iso codes are expanded so the player list reads the same language on both rows
    fun prettify(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.any { it.isWhitespace() || it.isUpperCase() }) return trimmed
        val parts = trimmed.split("-")
        val base = names[parts.first().lowercase()] ?: return trimmed
        return when (parts.getOrNull(1)) {
            null -> base
            "signs" -> "$base (Signs)"
            "sdh" -> "$base (SDH)"
            else -> "$base (${parts.drop(1).joinToString("-")})"
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SessionResponse(
    val sessionToken: String? = null,
    val expiresAt: Long? = null
)

data class AniLightEpisodeData(
    val animeId: Int? = null,
    val episode: Int? = null,
    val audio: String? = null
)
