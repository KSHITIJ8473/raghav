package com.csksy.anichan

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogEnvelope(
    @JsonProperty("results") val results: List<CatalogItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("titleRomaji") val titleRomaji: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("banner") val banner: String? = null,
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("score") val score: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("startDate") val startDate: StartDate? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("selfhost") val selfhost: Selfhost? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StartDate(
    @JsonProperty("year") val year: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Selfhost(
    @JsonProperty("cached_eps") val cachedEps: List<Int>? = null,
    @JsonProperty("count") val count: Int? = null,
    @JsonProperty("ep_meta") val epMeta: Map<String, EpMeta>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpMeta(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("image") val image: String? = null,
    @JsonProperty("airDate") val airDate: String? = null,
    @JsonProperty("overview") val overview: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchInfo(
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("dubAvailable") val dubAvailable: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ServersEnvelope(
    @JsonProperty("servers") val servers: List<Server>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Server(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("stream") val stream: String? = null,
    @JsonProperty("embed") val embed: String? = null,
    @JsonProperty("subType") val subType: String? = null,
    @JsonProperty("subtitles") val subtitles: List<Subtitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("url") val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkRace(
    @JsonProperty("ticket") val ticket: String? = null,
    @JsonProperty("servers") val servers: List<VidhawkServer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkServer(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("ticket") val ticket: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkPlay(
    @JsonProperty("tracks") val tracks: List<VidhawkTrack>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VidhawkTrack(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("src") val src: String? = null
)
