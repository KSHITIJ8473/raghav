package com.laddu100.kuroanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaTitle(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("romaji") val romaji: String? = null,
    @JsonProperty("native") val native: String? = null,
    @JsonProperty("userPreferred") val userPreferred: String? = null
) {
    val display: String? get() = userPreferred ?: english ?: romaji ?: native
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null,
    @JsonProperty("large") val large: String? = null,
    @JsonProperty("medium") val medium: String? = null
) {
    val best: String? get() = extraLarge ?: large ?: medium
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FuzzyDate(
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("month") val month: Int? = null,
    @JsonProperty("day") val day: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NextAiring(
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("airingAt") val airingAt: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("idMal") val idMal: Int? = null,
    @JsonProperty("title") val title: MediaTitle? = null,
    @JsonProperty("coverImage") val coverImage: CoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("season") val season: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("startDate") val startDate: FuzzyDate? = null,
    @JsonProperty("endDate") val endDate: FuzzyDate? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("meanScore") val meanScore: Int? = null,
    @JsonProperty("popularity") val popularity: Int? = null,
    @JsonProperty("favourites") val favourites: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("tags") val tags: List<String>? = null,
    @JsonProperty("isAdult") val isAdult: Boolean? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: NextAiring? = null,
    @JsonProperty("airedCount") val airedCount: Int? = null,
    @JsonProperty("subCount") val subCount: Int? = null,
    @JsonProperty("dubCount") val dubCount: Int? = null,
    @JsonProperty("hasDub") val hasDub: Boolean? = null,
    @JsonProperty("uploadedSub") val uploadedSub: Int? = null,
    @JsonProperty("uploadedDub") val uploadedDub: Int? = null,
    @JsonProperty("episodes_list") val episodesList: List<EpisodeMeta>? = null,
    @JsonProperty("studios") val studios: StudiosWrap? = null,
    @JsonProperty("trailer") val trailer: TrailerRef? = null
) {
    val displayTitle: String get() = title?.display ?: "Anime ${id ?: 0}"
    val animationStudio: String? get() = studios?.nodes?.firstOrNull { it.animation == true }?.name
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudiosWrap(
    @JsonProperty("nodes") val nodes: List<StudioNode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudioNode(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("animation") val animation: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrailerRef(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("site") val site: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeMeta(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleJa") val titleJa: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("filler") val filler: Boolean? = null,
    @JsonProperty("recap") val recap: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ListEnvelope(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("perPage") val perPage: Int? = null,
    @JsonProperty("total") val total: Int? = null,
    @JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    @JsonProperty("results") val results: List<MediaItem>? = null,
    @JsonProperty("items") val items: List<MediaItem>? = null
) {
    val all: List<MediaItem> get() = results ?: items ?: emptyList()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SelfHostResponse(
    @JsonProperty("episodes") val episodes: List<SelfHostEpisode>? = null,
    @JsonProperty("items") val items: List<MediaItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SelfHostEpisode(
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("intro_start") val introStart: Double? = null,
    @JsonProperty("intro_end") val introEnd: Double? = null,
    @JsonProperty("outro_start") val outroStart: Double? = null,
    @JsonProperty("outro_end") val outroEnd: Double? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AnidbAppEpisodes(
    @JsonProperty("episodes") val episodes: AudioEpisodes? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioEpisodes(
    @JsonProperty("sub") val sub: List<AudioEpisode>? = null,
    @JsonProperty("dub") val dub: List<AudioEpisode>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioEpisode(
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("filler") val filler: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchResponse(
    @JsonProperty("streams") val streams: List<StreamEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamEntry(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("resolution") val resolution: String? = null,
    @JsonProperty("downloadUrl") val downloadUrl: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("priority") val priority: Int? = null,
    @JsonProperty("isActive") val isActive: Boolean? = null,
    @JsonProperty("subtitles") val subtitles: List<TrackEntry>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExtractResult(
    @JsonProperty("ok") val ok: Boolean? = null,
    @JsonProperty("error") val error: String? = null,
    @JsonProperty("m3u8") val m3u8: String? = null,
    @JsonProperty("m3u8_proxied") val m3u8Proxied: String? = null,
    @JsonProperty("m3u8_direct") val m3u8Direct: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("tracks") val tracks: List<TrackEntry>? = null,
    @JsonProperty("intro") val intro: SkipSpan? = null,
    @JsonProperty("outro") val outro: SkipSpan? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrackEntry(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("file_proxied") val fileProxied: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("default") val default: Boolean? = null
) {
    val bestUrl: String? get() = fileProxied ?: file ?: url
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SkipSpan(
    @JsonProperty("start") val start: Double? = null,
    @JsonProperty("end") val end: Double? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroEpisodes(
    @JsonProperty("providers") val providers: Map<String, MiruroProvider>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MiruroProvider(
    @JsonProperty("episodes") val episodes: AudioEpisodes? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KyrenStreamResponse(
    @JsonProperty("ok") val ok: Boolean? = null,
    @JsonProperty("sources") val sources: List<KyrenSource>? = null,
    @JsonProperty("subtitles") val subtitles: List<KyrenSubtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KyrenSource(
    @JsonProperty("provider") val provider: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("quality") val quality: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class KyrenSubtitle(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SignResponse(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("exp") val exp: Long? = null
)
