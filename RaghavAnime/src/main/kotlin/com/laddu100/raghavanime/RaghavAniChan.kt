package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URLEncoder

class RaghavAniChan : MainAPI() {
    override var mainUrl = "https://anichan.to"
    override var name = "AniChan"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
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

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val category = if (isDub) "dub" else "sub"

        val servers = watchServers(anilistId, episode, category)
        if (servers.isEmpty()) {
            return false
        }

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )
        val seenSubs = HashSet<String>()
        var found = false

        for (server in servers) {
            val label = serverLabel(server, isDub)
            if (server.type == "embed") {
                val embed = server.embed ?: continue
                val vidServer = Regex("[?&]server=([^&]+)").find(embed)?.groupValues?.get(1)
                    ?: "kari"
                val track = vidhawkResolve(anilistId, episode, category, vidServer)
                    ?.firstOrNull { it.id.equals(category, true) && !it.src.isNullOrBlank() }
                    ?: continue
                val src = track.src?.takeIf { it.startsWith("http") } ?: continue
                callback.invoke(
                    newExtractorLink(name, label, src, type = ExtractorLinkType.M3U8) {
                        this.headers = linkHeaders
                    }
                )
                found = true
            } else {
                val stream = server.stream?.takeIf { it.startsWith("http") }
                    ?: server.stream?.takeIf { it.startsWith("/") }?.let { "$mainUrl$it" }
                    ?: continue
                callback.invoke(
                    newExtractorLink(name, label, stream, type = ExtractorLinkType.M3U8) {
                        this.headers = linkHeaders
                    }
                )
                found = true
            }

            for (sub in server.subtitles.orEmpty()) {
                val url = sub.url?.takeIf { it.startsWith("http") } ?: continue
                val lang = sub.lang ?: "English"
                if (seenSubs.add(lang)) {
                    subtitleCallback.invoke(SubtitleFile(lang, url))
                }
            }
        }
        return found
    }

    // the session cookie is single use, every servers call needs a fresh one
    private suspend fun newWatchSession(): String? {
        return try {
            val resp = app.post(
                "$mainUrl/api/watch/session",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Origin" to mainUrl
                ),
                json = mapOf("token" to "")
            )
            if (!resp.isSuccessful) return null
            for (cookie in resp.headers.values("set-cookie")) {
                if (cookie.startsWith("anichan_ws=")) {
                    return cookie.substringBefore(";").substringAfter("anichan_ws=")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun watchServers(anilistId: Int, ep: Int, category: String): List<Server> {
        val url = "$mainUrl/api/watch/servers?anilistId=$anilistId&ep=$ep&category=$category"
        repeat(SESSION_ATTEMPTS) {
            val cookie = newWatchSession()
            if (cookie != null) {
                try {
                    val resp = app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "Cookie" to "anichan_ws=$cookie"
                        )
                    )
                    if (resp.isSuccessful) {
                        return mapper.readValue(resp.text, ServersEnvelope::class.java).servers ?: emptyList()
                    }
                } catch (e: Exception) {
                    Log.d("RaghavAnime", "[AniChan] servers attempt failed: ${e.message}")
                }
            }
            delay(400)
        }
        return emptyList()
    }

    private suspend fun vidhawkResolve(
        anilistId: Int,
        ep: Int,
        audio: String,
        server: String
    ): List<VidhawkTrack>? {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
            val raceUrl = "https://vidhawk.buzz/api/stream/race?episode=$ep&audio=$audio&server=$server" +
                "&anilistId=$anilistId&parentHost=anichan.to"
            val raceResp = app.get(raceUrl, headers = headers)
            val race = mapper.readValue(raceResp.text, VidhawkRace::class.java)

            val ticket = race.servers?.firstOrNull { it.id.equals(server, true) }?.ticket
                ?: race.ticket
                ?: return null

            val playResp = app.get(
                "https://vidhawk.buzz/api/play?t=${URLEncoder.encode(ticket, "UTF-8")}",
                headers = headers
            )
            mapper.readValue(playResp.text, VidhawkPlay::class.java).tracks ?: emptyList()
        } catch (e: Exception) {
            null
        }
    }

    private fun serverLabel(server: Server, isDub: Boolean): String {
        val raw = (server.label ?: server.name ?: "AniChan")
            .replace("★", "")
            .replace(Regex("\\s*⧉\\s*\\(ads\\)"), "")
            .trim()
        val hardsub = server.subType.equals("hard", true)
        return when {
            hardsub -> "$raw (Hardsub)"
            isDub -> "$raw (Dub)"
            else -> raw
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val SESSION_ATTEMPTS = 4
    }
}
