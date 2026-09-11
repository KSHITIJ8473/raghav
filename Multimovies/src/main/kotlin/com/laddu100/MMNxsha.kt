package com.laddu100

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object MMNxsha {

    private const val TAG = "MM_Nxsha"
    private const val PASSPHRASE = "S8x!Jk4ZP1uG8\$my"
    private const val BASE = "https://nxsha.space"
    private const val TMDB_PROXY = "https://db.speedracelight.com/3"

    private const val REFERER = "$BASE/"

    private val json = ObjectMapper().registerModule(KotlinModule.Builder().build())

    data class NxServer(
        val id: Int?,
        val name: String,
        val scraper: String,
        val webSupport: Boolean?,
    )

    data class NxSource(
        val id: String?,
        val provider: String?,
        val url: String?,
        val quality: String?,
        val label: String?,
        val isEmbed: Boolean?,
        val type: String?,
    )

    data class NxSubtitles(val subtitles: List<NxSubtitle>?)
    data class NxSubtitle(
        val title: String?,
        val language: String?,
        val uri: String?,
    )

    private class NxServersResp(val servers: List<NxServer>?)
    private class NxSourcesResp(val sources: List<NxSource>?)

    private fun encodeParams(payload: Map<String, String>): String? {
        val obj = payload.toMutableMap()
        obj["_req_ts"] = System.currentTimeMillis().toString()
        obj["_req_salt"] = (1..10).map { ('a' + (0..35).random()) }.joinToString("")
        return MMCrypto.aesEncrypt(json.writeValueAsString(obj), PASSPHRASE)
            ?.replace("+", "-")
            ?.replace("/", "_")
            ?.replace("=", "")
    }

    private suspend fun apiGet(path: String, payload: Map<String, String>, embedReferer: String): String? {
        val q = encodeParams(payload) ?: return null
        val url = "$BASE$path?q=${MMNet.urlEncode(q)}"
        return MMNet.getText(url, referer = embedReferer)
    }

    private inline fun <reified T> decodeHash(body: String?): T? {
        if (body.isNullOrBlank()) return null
        // responses are {"_hash":"<base64url aes json>"}
        val hash = Regex("\"_hash\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?: return null
        val plain = MMCrypto.aesDecrypt(hash, PASSPHRASE) ?: return null
        return try {
            json.readValue(plain, T::class.java)
        } catch (e: Exception) {
            Log.d(TAG, "decode failed: ${e.message?.take(60)}")
            null
        }
    }

    suspend fun imdbToTmdb(imdbId: String, type: String): String? {
        val body = MMNet.getText("$TMDB_PROXY/find/$imdbId?external_source=imdb_id") ?: return null
        val arrayKey = if (type == "tv") "tv_results" else "movie_results"
        val m = Regex("\"$arrayKey\"\\s*:\\s*\\[\\s*\\{[^}]*?\"id\"\\s*:\\s*(\\d+)")
            .find(body) ?: return null
        return m.groupValues[1]
    }

    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val movieMatch = Regex("/embed/movie/(tt\\d+)").find(embedUrl)
            val tvMatch = Regex("/embed/tv/(\\d+)/(\\d+)/(\\d+)").find(embedUrl)

            var tmdbId: String? = null
            var imdbId = ""
            var type = "movie"
            var season = ""
            var episode = ""

            if (tvMatch != null) {
                tmdbId = tvMatch.groupValues[1]
                season = tvMatch.groupValues[2]
                episode = tvMatch.groupValues[3]
                type = "tv"
            } else if (movieMatch != null) {
                imdbId = movieMatch.groupValues[1]
                tmdbId = imdbToTmdb(imdbId, "movie")
                    // some embeds carry a numeric tmdb id instead of imdb
                    ?: Regex("/embed/movie/(\\d+)").find(embedUrl)?.groupValues?.get(1)
            } else {
                return@withContext false
            }

            val tmdb = tmdbId ?: return@withContext false

            val serversBody = apiGet(
                "/api/servers",
                mapOf(
                    "tmdbId" to tmdb, "imdb_id" to imdbId, "type" to type,
                    "season" to season, "episode" to episode,
                ),
                embedUrl,
            )
            val servers = decodeHash<NxServersResp>(serversBody)?.servers ?: return@withContext false

            var any = false
            coroutineScope {
                servers.map { server ->
                    async {
                        if (server.webSupport == false) return@async
                        val sourcesBody = try {
                            apiGet(
                                "/api/sources",
                                mapOf(
                                    "ex_lang" to "false",
                                    "provider" to server.scraper,
                                    "tmdbId" to tmdb, "imdb_id" to imdbId, "type" to type,
                                    "season" to season, "episode" to episode,
                                ),
                                embedUrl,
                            )
                        } catch (e: Exception) {
                            null
                        } ?: return@async
                        val sources = decodeHash<NxSourcesResp>(sourcesBody)?.sources ?: return@async
                        for (src in sources) {
                            val url = src.url?.trim()?.takeIf { it.startsWith("http") } ?: continue
                            if (src.isEmbed == true) continue // nested embed, not resolvable here
                            val name = "$label • ${server.name}"
                            val qualityLabel = src.label ?: src.quality
                            val linkType = when (src.type?.lowercase()) {
                                "m3u8", "hls" -> ExtractorLinkType.M3U8
                                "mpd", "dash" -> ExtractorLinkType.DASH
                                "mp4", "video" -> ExtractorLinkType.VIDEO
                                else -> ExtractorLinkType.M3U8
                            }
                            val qualityNum = Regex("(1080|720|480|360|2160)")
                                .find(qualityLabel ?: "")?.groupValues?.get(1)?.toIntOrNull()
                            callback(
                                newExtractorLink(name, name, url, type = linkType) {
                                    // nitro 403s without the nxsha referer, others accept it
                                    this.headers = mapOf("Referer" to REFERER)
                                    qualityNum?.let { this.quality = it }
                                }
                            )
                            any = true
                        }
                    }
                }.forEach { it.join() }
            }

            try {
                val subsBody = apiGet(
                    "/api/subtitles",
                    mapOf(
                        "tmdbId" to tmdb, "imdb_id" to imdbId, "type" to type,
                        "season" to season, "episode" to episode,
                    ),
                    embedUrl,
                )
                val subs = decodeHash<NxSubtitles>(subsBody)?.subtitles
                for (sub in subs.orEmpty()) {
                    val uri = sub.uri?.trim()?.takeIf { it.startsWith("http") } ?: continue
                    val name = sub.title?.takeIf { it.isNotBlank() }
                        ?: sub.language?.takeIf { it.isNotBlank() } ?: "English"
                    subtitleCallback(newSubtitleFile(name, uri) {})
                }
            } catch (e: Exception) {
                Log.d(TAG, "subtitles: ${e.message?.take(60)}")
            }

            any
        } catch (e: Exception) {
            Log.d(TAG, "resolve failed: ${e.message?.take(80)}")
            false
        }
    }
}
