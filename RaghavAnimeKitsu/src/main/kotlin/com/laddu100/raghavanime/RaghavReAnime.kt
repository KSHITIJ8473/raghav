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
import com.lagradost.cloudstream3.newSubtitleFile
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

        val servers = flixServers(anilistId, episode)
        if (servers.isEmpty()) {
            return false
        }

        val embeds = servers.mapNotNull { s ->
            val link = s.dataLink?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val server = s.serverName ?: "HD"
            link to server
        }.distinctBy { it.first.substringBefore("?") + it.second }

        val seenSubs = HashSet<String>()
        val seenUrls = HashSet<String>()
        val any = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            embeds.map { (embedUrl, serverName) ->
                async {
                    val res = FlixResolver.resolve(embedUrl, "$mainUrl/") ?: return@async
                    val proxyMaster = FlixProxy.registerMaster(res.m3u8, res.masterContent, res.pkKey)
                        ?: return@async
                    val hasEnglishAudio = res.masterContent.contains("""LANGUAGE="en""") ||
                        res.masterContent.contains("NAME=\"English\"")
                    val hasOtherAudio = Regex("""TYPE=AUDIO[^\n]*LANGUAGE="(?!en)[^"]*"""")
                        .containsMatchIn(res.masterContent) ||
                        (res.masterContent.contains("TYPE=AUDIO") && !hasEnglishAudio)

                    // the flix master carries every audio track, pick the one
                    // matching the requested language
                    val lang = when {
                        isDub && hasEnglishAudio -> "dub"
                        !isDub && (hasOtherAudio || !hasEnglishAudio) -> "sub"
                        isDub && !hasEnglishAudio -> "sub"
                        else -> "dub"
                    }

                    val suffix = if (serverName.isNotBlank() && !serverName.equals("HD-1", true)) " ($serverName)" else ""
                    val label = if (lang == "dub") "Re:ANIME Dub$suffix" else "Re:ANIME Sub$suffix"
                    val url = "$proxyMaster?lang=$lang"
                    if (seenUrls.add(url)) {
                        any.set(true)
                        val height = Regex("""RESOLUTION=\d+x(\d+)""")
                            .findAll(res.masterContent)
                            .mapNotNull { it.groupValues[1].toIntOrNull() }
                            .maxOrNull()
                        callback.invoke(
                            newExtractorLink(name, label, url, type = ExtractorLinkType.M3U8) {
                                height?.let { this.quality = it }
                                this.headers = mapOf("Referer" to "https://flixcloud.cc/")
                            }
                        )
                    }
                    for (sub in res.subtitles) {
                        if (!seenSubs.add(sub.url)) continue
                        val ext = sub.format?.uppercase()
                        val subName = if (ext != null) "${sub.language ?: "Subtitle"} ($ext)" else (sub.language ?: "Subtitle")
                        subtitleCallback.invoke(newSubtitleFile(subName, sub.url) {})
                    }
                }
            }
        }.forEach { it.join() }

        val found = any.get()
        return found
    }

    private suspend fun flixServers(anilistId: Int, episode: Int): List<FlixServer> {
        val url = "$mainUrl/api/flix/$anilistId/$episode"
        val body = try {
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to FlixResolver.USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/watch/"
                )
            )
            if (resp.isSuccessful) resp.text else null
        } catch (e: Exception) {
            Log.d("RaghavAnimeKitsu", "[ReAnime] flix servers request failed: ${e.message}")
            null
        } ?: return emptyList()
        return try {
            mapper.readValue(body, FlixResponse::class.java).servers ?: emptyList()
        } catch (e: Exception) {
            Log.d("RaghavAnimeKitsu", "[ReAnime] flix servers parse failed: ${e.message}")
            emptyList()
        }
    }
}
