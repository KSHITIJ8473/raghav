package com.laddu100.raghavanime

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class RaghavReAnime : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Re:ANIME"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

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

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (anilistId <= 0 || episode <= 0) return false
        mainUrl = FirebaseDomainHelper.getDomain("reanime") ?: mainUrl

        val servers = flixServers(anilistId, episode)
        if (servers.isEmpty()) {
            return false
        }

        // Every server of an episode (HD-1/HD-2, sub/dub) points at the same flixcloud file:
        // one MKV with both the Japanese and English audio and all subtitles inside
        val embeds = servers.mapNotNull { s ->
            s.dataLink?.takeIf { it.startsWith("http") && FlixDownload.embedId(it) != null }
        }.distinctBy { FlixDownload.embedId(it) }

        val any = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            embeds.map { embedUrl ->
                async {
                    val file = FlixDownload.resolve(embedUrl) ?: return@async
                    any.set(true)
                    val label = "Re:ANIME Sub & Dub" + (file.size?.let { " ($it)" } ?: "")
                    callback.invoke(
                        newExtractorLink(name, label, file.url, type = ExtractorLinkType.VIDEO) {
                            file.quality?.let { this.quality = it }
                            this.headers = file.headers
                        }
                    )
                }
            }
        }.forEach { it.join() }

        return any.get()
    }

    private suspend fun flixServers(anilistId: Int, episode: Int): List<FlixServer> {
        val url = "$mainUrl/api/flix/$anilistId/$episode"
        val body = try {
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to FlixDownload.USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/watch/"
                )
            )
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return try {
            mapper.readValue(body, FlixResponse::class.java).servers ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
