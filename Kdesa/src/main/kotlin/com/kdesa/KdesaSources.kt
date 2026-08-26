package com.kdesa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

private const val TAG = "Kdesa"

private val mapper = ObjectMapper()

class KdesaSources {

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_BEARER =
            "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJjYTg3NmZkYmVhMjNhMzI3ODY0ZjRjN2U5MzMwZTYxNiIsIm5iZiI6MTc4MjIwOTQ0NC45OTksInN1YiI6IjZhM2E1YmE0ZmMzZGFiNGNmYzMzNjIxMCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.WlSOswQDdxdbKu0jARJoruV6PlteoTXB1Oj4gRaibBI"

        private const val CORNCLICK = "https://cornclick.com"
        private const val SEVENMOVIES = "https://7movies.in"
        private const val SEVENMOVIES_EMBED = "https://embed.animecurx.tech"
        private const val ONEEMBED = "https://1embed.cc"
        private const val VIXSRC = "https://vixsrc.to"
        private const val NOVA = "https://novahd.cc"
        private const val FSONLINE = "https://www3.fsonline.app"
        private const val CUEVANA3 = "https://www.cuevana3.eu"
        private const val ANIDAP = "https://anidap.lol"
        private const val ANIDAP_API = "https://chad.anidap.lol"

        private val TQQ_MIRRORS = listOf(
            "https://anikototv.to",
            "https://anikoto.cz",
            "https://anikoto.me",
            "https://anikoto.net",
            "https://anikoto.se"
        )
    }

    private fun hdr(vararg extra: Pair<String, String>): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to UA,
            "Accept-Language" to "en-US,en;q=0.9"
        )
        for ((k, v) in extra) map[k] = v
        return map
    }

    private fun parseJsonSafe(text: String): JsonNode? = try {
        mapper.readTree(text)
    } catch (e: Exception) {
        null
    }

    private fun JsonNode.str(field: String): String? =
        this.get(field)?.takeIf { !it.isNull }?.asText()

    private fun JsonNode.int(field: String): Int? =
        this.get(field)?.takeIf { !it.isNull }?.asInt()

    // Masters with separate EXT-X-MEDIA audio renditions (Nova Titan/Orion,
    // vidsrc multi-audio) must reach the player unsplit: generateM3u8() only
    // returns the video variants and drops the audio group, so those streams
    // would play completely silent.
    private data class HlsProbe(
        val audioLangs: List<String>, // EXT-X-MEDIA:TYPE=AUDIO tags
        val maxHeight: Int,           // best RESOLUTION height (0 = unknown)
        val variants: Int,            // EXT-X-STREAM-INF count
        val signature: String         // content signature for dedupe
    )

    private fun prettifyLang(raw: String): String {
        val c = raw.trim().lowercase()
        return when (c) {
            "en", "eng", "english" -> "English"
            "ja", "jp", "jpn", "japanese" -> "Japanese"
            "hi", "hin", "hindi" -> "Hindi"
            "kn", "kan", "kannada" -> "Kannada"
            "ml", "mal", "malayalam" -> "Malayalam"
            "ta", "tam", "tamil" -> "Tamil"
            "te", "tel", "telugu" -> "Telugu"
            "es", "spa", "spanish", "castilian", "latino", "latin" -> "Spanish"
            "fr", "fre", "fra", "french" -> "French"
            "it", "ita", "italian" -> "Italian"
            "de", "ger", "deu", "german" -> "German"
            "pt", "por", "portuguese" -> "Portuguese"
            "ru", "rus", "russian" -> "Russian"
            "ar", "ara", "arabic" -> "Arabic"
            "ko", "kor", "korean" -> "Korean"
            "zh", "chi", "zho", "chinese" -> "Chinese"
            "audio", "default", "und", "original" -> "Original"
            else -> raw.trim()
        }
    }

    private fun audioSuffix(langs: List<String>): String =
        if (langs.size > 1) " (Multi-Audio: ${langs.joinToString(", ") { prettifyLang(it) }})"
        else ""

    private fun hlsAudioTag(line: String): String? {
        val lang = Regex("""LANGUAGE="([^"]+)""").find(line)?.groupValues?.get(1)
        val name = Regex("""NAME="([^"]+)""").find(line)?.groupValues?.get(1)
        return (lang ?: name ?: return null).takeIf { it.isNotBlank() }
    }

    private suspend fun probeHls(url: String, headers: Map<String, String>): HlsProbe? {
        return try {
            val res = app.get(url, headers = headers, timeout = 15_000L)
            if (res.code != 200) return null
            val text = res.text
            if (!text.startsWith("#EXTM3U")) return null
            val audioLangs = mutableListOf<String>()
            val hashParts = mutableListOf<String>()
            var maxHeight = 0
            var variants = 0
            for (raw in text.lines()) {
                val l = raw.trim()
                if (l.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    hlsAudioTag(l)?.let { audioLangs.add(it) }
                } else if (l.startsWith("#EXT-X-STREAM-INF")) {
                    variants++
                    Regex("""RESOLUTION=\d+x(\d+)""").find(l)?.groupValues?.get(1)
                        ?.toIntOrNull()?.let { if (it > maxHeight) maxHeight = it }
                } else if (!l.startsWith("#") && l.isNotBlank() && hashParts.size < 8) {
                    // variant / segment url -> last two path chunks identify the content
                    val path = l.split("?")[0].trimEnd('/')
                    val last2 = path.split("/").takeLast(2).joinToString("/")
                    if (last2.isNotBlank() && last2 != "/") hashParts.add(last2)
                }
            }
            HlsProbe(audioLangs.distinct(), maxHeight, variants, (audioLangs.sorted() + hashParts.sorted()).joinToString("|"))
        } catch (e: Exception) {
            Log.d(TAG, "probeHls failed ${url.take(80)}: ${e.message}")
            null
        }
    }

    // Demuxed-audio masters are emitted as one unsplit link (marked
    // "Multi-Audio"), muxed masters are split into per-quality links.
    private suspend fun emitHls(
        label: String,
        url: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        probe: HlsProbe? = null
    ): Int {
        val info = probe ?: probeHls(url, headers)
        if (info == null) {
            callback.invoke(
                newExtractorLink(label, label, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                }
            )
            return 1
        }
        if (info.audioLangs.isNotEmpty()) {
            val name = label + audioSuffix(info.audioLangs)
            val q = if (info.maxHeight > 0) info.maxHeight else Qualities.Unknown.value
            Log.d(TAG, "emitHls '$label' -> unsplit master audio=[${info.audioLangs.joinToString(",")}] max=${info.maxHeight}p variants=${info.variants}")
            callback.invoke(
                newExtractorLink(label, name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = headers
                    this.quality = q
                }
            )
            return 1
        }
        return try {
            val links = M3u8Helper.generateM3u8(label, url, referer, headers = headers)
            links.forEach(callback)
            links.size
        } catch (e: Exception) {
            Log.e(TAG, "emitHls: generateM3u8 failed for '$label': ${e.message}")
            0
        }
    }

    suspend fun resolveMovie(
        tmdbId: Int,
        title: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "resolveMovie tmdbId=$tmdbId title='$title'")
        var any = false
        any = sourceCornClick(tmdbId, null, null, subtitleCallback, callback) || any
        any = sourceSevenMovies(tmdbId, null, null, subtitleCallback, callback) || any
        any = sourceOneEmbed(tmdbId, null, null, subtitleCallback, callback) || any
        any = sourceVixSrc(tmdbId, null, null, subtitleCallback, callback) || any
        any = sourceAnidap(tmdbId, title, 1, subtitleCallback, callback) || any
        any = sourceTqq(title, 1, 1, subtitleCallback, callback) || any
        any = sourceCuevana3(tmdbId, title, null, null, subtitleCallback, callback) || any
        any = sourceNova(tmdbId, null, null, subtitleCallback, callback) || any
        any = sourceFsonline(tmdbId, title, null, null, subtitleCallback, callback) || any
        Log.d(TAG, "resolveMovie done any=$any")
        return any
    }

    suspend fun resolveShow(
        tmdbId: Int,
        title: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "resolveShow tmdbId=$tmdbId title='$title' s=$season e=$episode")
        var any = false
        any = sourceCornClick(tmdbId, season, episode, subtitleCallback, callback) || any
        any = sourceSevenMovies(tmdbId, season, episode, subtitleCallback, callback) || any
        any = sourceOneEmbed(tmdbId, season, episode, subtitleCallback, callback) || any
        any = sourceVixSrc(tmdbId, season, episode, subtitleCallback, callback) || any
        any = sourceAnidap(tmdbId, title, episode, subtitleCallback, callback) || any
        any = sourceTqq(title, season, episode, subtitleCallback, callback) || any
        any = sourceCuevana3(tmdbId, title, season, episode, subtitleCallback, callback) || any
        any = sourceNova(tmdbId, season, episode, subtitleCallback, callback) || any
        any = sourceFsonline(tmdbId, title, season, episode, subtitleCallback, callback) || any
        Log.d(TAG, "resolveShow done any=$any")
        return any
    }

    // CornClick - TMDB direct JSON API
    private suspend fun sourceCornClick(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "CornClick"
        try {
            val path = if (season != null && episode != null) {
                "/player/tv/$tmdbId/$season/$episode"
            } else {
                "/player/movie/$tmdbId"
            }
            val res = app.get(
                "$CORNCLICK$path",
                headers = hdr(
                    "Referer" to "$CORNCLICK/",
                    "Origin" to CORNCLICK,
                    "Accept" to "application/json"
                ),
                timeout = 30_000L
            )
            if (res.code != 200) {
                Log.e(TAG, "[$label] HTTP ${res.code} for $path")
                return false
            }
            val root = parseJsonSafe(res.text) ?: run {
                Log.e(TAG, "[$label] invalid JSON response")
                return false
            }
            val sources = root.get("sources")?.takeIf { it.isArray } ?: run {
                Log.e(TAG, "[$label] no sources in response")
                return false
            }
            val subs = root.get("subtitles")?.takeIf { it.isArray }
            var count = 0
            for (src in sources) {
                val url = src.str("url") ?: continue
                if (!url.startsWith("http")) continue
                val provider = src.get("provider")?.str("id") ?: "vaplayer"
                val quality = src.str("quality") ?: ""
                val type = src.str("type") ?: "hls"
                if (type.equals("hls", true)) {
                    count += emitHls(
                        "$label $provider",
                        url,
                        "$CORNCLICK/",
                        hdr("Referer" to "$CORNCLICK/", "Origin" to CORNCLICK),
                        callback
                    )
                } else {
                    callback.invoke(
                        newExtractorLink(label, "$label $provider $quality", url, ExtractorLinkType.VIDEO) {
                            this.headers = hdr("Referer" to "$CORNCLICK/", "Origin" to CORNCLICK)
                        }
                    )
                    count++
                }
            }
            var subCount = 0
            if (subs != null) {
                for (sub in subs) {
                    val url = sub.str("url") ?: continue
                    val subLabel = sub.str("label") ?: sub.str("lang") ?: "English"
                    // opensubtitles .gz subs are gzip-compressed srt the player cannot render
                    if (url.endsWith(".gz")) continue
                    subtitleCallback.invoke(newSubtitleFile(subLabel, url) {})
                    subCount++
                }
            }
            Log.d(TAG, "[$label] links=$count subs=$subCount")
            return count > 0
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // 7Movies - playback token + source API
    private suspend fun sourceSevenMovies(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "7Movies"
        try {
            val isShow = season != null && episode != null
            val body = if (isShow) {
                mapOf("tmdbId" to tmdbId, "type" to "tv", "season" to season, "episode" to episode)
            } else {
                mapOf("tmdbId" to tmdbId, "type" to "movie")
            }
            val tokenRes = app.post(
                "$SEVENMOVIES/api/playback-token",
                json = body,
                headers = hdr(
                    "Content-Type" to "application/json",
                    "Origin" to SEVENMOVIES,
                    "Referer" to "$SEVENMOVIES/",
                    "Accept" to "application/json"
                ),
                timeout = 30_000L
            )
            if (tokenRes.code != 200) {
                Log.e(TAG, "[$label] token HTTP ${tokenRes.code}")
                return false
            }
            val token = parseJsonSafe(tokenRes.text)?.str("token")
            if (token.isNullOrBlank()) {
                Log.e(TAG, "[$label] no token in response")
                return false
            }
            val srcPath = if (isShow) {
                "/api/source/tv/$tmdbId/$season/$episode"
            } else {
                "/api/source/movie/$tmdbId"
            }
            val srcUrl = "$SEVENMOVIES_EMBED$srcPath?token=${URLEncoder.encode(token, "UTF-8")}&provider=vaplayer"
            val srcRes = app.get(
                srcUrl,
                headers = hdr(
                    "Origin" to SEVENMOVIES,
                    "Referer" to "$SEVENMOVIES/",
                    "Accept" to "application/json"
                ),
                timeout = 30_000L
            )
            if (srcRes.code != 200) {
                Log.e(TAG, "[$label] source HTTP ${srcRes.code}")
                return false
            }
            val root = parseJsonSafe(srcRes.text) ?: return false
            if (root.get("success")?.asBoolean() != true) {
                Log.e(TAG, "[$label] success=false: ${root.str("error")}")
                return false
            }
            val streams = root.get("streams")?.takeIf { it.isArray } ?: run {
                Log.e(TAG, "[$label] no streams")
                return false
            }
            var count = 0
            val seenSignature = mutableSetOf<String>()
            for ((idx, stream) in streams.withIndex()) {
                var url = stream.str("url")
                if (url.isNullOrBlank() || !url.startsWith("http")) {
                    val proxyUrl = stream.str("proxyUrl") ?: continue
                    val full = if (proxyUrl.startsWith("http")) proxyUrl else "$SEVENMOVIES_EMBED${if (proxyUrl.startsWith("/")) "" else "/"}$proxyUrl"
                    url = try {
                        val inner = URL(full).query?.split("&")
                            ?.firstOrNull { it.startsWith("url=") }
                            ?.substringAfter("url=")
                            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        if (inner != null && inner.startsWith("http")) inner else full
                    } catch (e: Exception) {
                        full
                    }
                }
                val provider = stream.str("provider") ?: "stream$idx"
                val quality = root.str("quality")
                // dedupe identical mirrors; demuxed-audio masters must stay unsplit
                val probe = probeHls(url, hdr())
                if (probe != null && !seenSignature.add(probe.signature)) continue
                count += emitHls(
                    "$label $provider${if (quality != null) " $quality" else ""}",
                    url,
                    "$SEVENMOVIES_EMBED/",
                    hdr(),
                    callback,
                    probe
                )
            }
            Log.d(TAG, "[$label] links=$count")
            return count > 0
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // 1Embed - token + server API (vidsrc, goated, emp, night)
    private var oneEmbedToken: Pair<String, Long>? = null

    private suspend fun oneEmbedToken(): String? {
        val cached = oneEmbedToken
        if (cached != null && System.currentTimeMillis() < cached.second) return cached.first
        return try {
            val res = app.get(
                "$ONEEMBED/api/token",
                headers = hdr("Referer" to "$ONEEMBED/", "Accept" to "application/json"),
                timeout = 20_000L
            )
            if (res.code != 200) {
                Log.e(TAG, "[1Embed] token HTTP ${res.code}")
                return null
            }
            val token = parseJsonSafe(res.text)?.str("token")
            if (token.isNullOrBlank()) {
                Log.e(TAG, "[1Embed] token missing")
                return null
            }
            oneEmbedToken = token to (System.currentTimeMillis() + 25 * 60 * 1000)
            token
        } catch (e: Exception) {
            Log.e(TAG, "[1Embed] token failed: ${e.message}")
            null
        }
    }

    private suspend fun sourceOneEmbed(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "1Embed"
        try {
            val token = oneEmbedToken() ?: return false
            val isShow = season != null && episode != null
            var any = false
            for (server in listOf("vidsrc", "goated", "emp", "night")) {
                try {
                    val path = if (isShow) {
                        "/server/$server/id=$tmdbId?type=tv&season=$season&episode=$episode&_st=$token"
                    } else {
                        "/server/$server/id=$tmdbId?type=movie&_st=$token"
                    }
                    val res = app.get(
                        "$ONEEMBED$path",
                        headers = hdr(
                            "Referer" to "$ONEEMBED/",
                            "Origin" to ONEEMBED,
                            "Accept" to "application/json"
                        ),
                        timeout = 30_000L
                    )
                    if (res.code != 200) {
                        Log.e(TAG, "[$label] $server HTTP ${res.code}")
                        continue
                    }
                    val root = parseJsonSafe(res.text) ?: continue
                    if (root.get("success")?.asBoolean() != true) {
                        Log.e(TAG, "[$label] $server success=false ${root.str("error")}")
                        continue
                    }
                    // raw_m3u8 is IP-locked to the API server, the proxy streamUrl works
                    val streamUrl = root.str("streamUrl")
                        ?: root.get("streams")?.str("proxy_m3u8")
                        ?: root.get("streams")?.str("raw_m3u8")
                    if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) {
                        Log.e(TAG, "[$label] $server no streamUrl")
                        continue
                    }
                    val audioNames = root.get("audioTracks")?.takeIf { it.isArray }
                        ?.mapNotNull { it.str("name") ?: it.str("language") } ?: emptyList()

                    // the master playlist is handed to the player unsplit so every
                    // audio rendition stays selectable in its track selector
                    val name = when {
                        audioNames.size > 1 -> "$label $server (Multi-Audio: ${audioNames.joinToString(", ")})"
                        audioNames.size == 1 -> "$label $server (${audioNames[0]})"
                        else -> "$label $server"
                    }
                    callback.invoke(
                        newExtractorLink(label, name, streamUrl, ExtractorLinkType.M3U8) {
                            this.headers = hdr("Referer" to "$ONEEMBED/")
                        }
                    )
                    any = true

                    val subs = root.get("subtitles")?.takeIf { it.isArray }
                    if (subs != null) {
                        for (sub in subs) {
                            val subUrl = sub.str("url") ?: sub.str("rawUrl") ?: continue
                            val subLabel = sub.str("label") ?: sub.str("language") ?: continue
                            // urls can carry query params after the extension
                            if (!subUrl.contains(".vtt") && !subUrl.contains(".srt")) continue
                            subtitleCallback.invoke(newSubtitleFile(subLabel, subUrl) {})
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$label] server $server failed: ${e.message}")
                }
            }
            Log.d(TAG, "[$label] done any=$any")
            return any
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // VixSrc (Italian) - api -> embed page -> master playlist with token
    private suspend fun sourceVixSrc(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "VixSrc"
        try {
            val apiPath = if (season != null && episode != null) {
                "/api/tv/$tmdbId/$season/$episode?lang=it"
            } else {
                "/api/movie/$tmdbId?lang=it"
            }
            val apiRes = app.get(
                "$VIXSRC$apiPath",
                headers = hdr(
                    "Referer" to "$VIXSRC/",
                    "Origin" to VIXSRC,
                    "Accept" to "text/html,application/json,*/*"
                ),
                timeout = 20_000L
            )
            if (apiRes.code != 200) {
                Log.e(TAG, "[$label] api HTTP ${apiRes.code}")
                return false
            }
            val src = parseJsonSafe(apiRes.text)?.str("src")
            if (src.isNullOrBlank()) {
                Log.e(TAG, "[$label] api returned no src")
                return false
            }
            val embedUrl = if (src.startsWith("http")) src else "$VIXSRC$src"
            // the embed token expires ~10s after the api call, fetch immediately
            val embedRes = app.get(
                embedUrl,
                headers = hdr("Referer" to "$VIXSRC/", "Origin" to VIXSRC),
                timeout = 20_000L
            )
            if (embedRes.code != 200) {
                Log.e(TAG, "[$label] embed HTTP ${embedRes.code}")
                return false
            }
            val html = embedRes.text

            val masterUrl = Regex("""url:\s*'(https://[^']*playlist/[^']*)'""")
                .find(html)?.groupValues?.get(1)
            val token = Regex("""'token'\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val expires = Regex("""'expires'\s*:\s*'([^']+)'""").find(html)?.groupValues?.get(1)
            val asn = Regex("""'asn'\s*:\s*'([^']*)'""").find(html)?.groupValues?.get(1) ?: ""
            val canPlayFhd = html.contains("window.canPlayFHD = true")
            if (masterUrl.isNullOrBlank() || token.isNullOrBlank() || expires.isNullOrBlank()) {
                Log.e(TAG, "[$label] masterUrl/token/expires missing (master=${masterUrl?.take(30)} token=${token != null} expires=${expires != null})")
                return false
            }
            Log.d(TAG, "[$label] master=$masterUrl fhd=$canPlayFhd")

            // the playlist endpoint rejects requests without the asn param even
            // when it is empty - always send token, expires and asn together
            val sep = if (masterUrl.contains("?")) "&" else "?"
            var playlist = "$masterUrl${sep}token=$token&expires=$expires&asn=$asn"
            if (canPlayFhd) playlist += "&h=1"

            // master playlist carries Italian/English audio + subtitle tracks,
            // ExoPlayer handles track selection natively
            callback.invoke(
                newExtractorLink(label, "$label (Italian)", playlist, ExtractorLinkType.M3U8) {
                    this.headers = hdr("Referer" to "$VIXSRC/", "Origin" to VIXSRC)
                }
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // Anidap - AniList search -> anidap api -> chad sources (sub/dub)
    private fun normalizeAnimeTitle(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD)
            .replace(Regex("[\\p{Mn}]"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private suspend fun anilistSearch(title: String, year: Int?): Int? {
        return try {
            val query = """
                query (${'$'}search: String, ${'$'}type: MediaType) {
                  Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: ${'$'}type, sort: POPULARITY_DESC) {
                      id
                      format
                      seasonYear
                      title { romaji english native }
                    }
                  }
                }
            """.trimIndent()
            val payload = mapOf(
                "query" to query,
                "variables" to mapOf("search" to title, "type" to "ANIME")
            )
            val res = app.post(
                "https://graphql.anilist.co",
                json = payload,
                headers = hdr("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 20_000L
            )
            if (res.code != 200) {
                Log.e(TAG, "[Anidap] anilist HTTP ${res.code}")
                return null
            }
            val media = parseJsonSafe(res.text)
                ?.get("data")?.get("Page")?.get("media")?.takeIf { it.isArray } ?: return null
            if (media.size() == 0) return null
            val want = normalizeAnimeTitle(title)
            var best: JsonNode? = null
            var bestScore = -1
            for (item in media) {
                val titles = listOfNotNull(
                    item.get("title")?.str("romaji"),
                    item.get("title")?.str("english"),
                    item.get("title")?.str("native")
                ).map { normalizeAnimeTitle(it) }
                var score = 0
                if (titles.contains(want)) score += 100
                else if (titles.any { it.contains(want) || want.contains(it) }) score += 50
                val seasonYear = item.get("seasonYear")?.asInt(0) ?: 0
                if (year != null && seasonYear > 0) {
                    score += maxOf(0, 20 - kotlin.math.abs(seasonYear - year) * 4)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = item
                }
            }
            val chosen = best ?: media.get(0)
            val id = chosen.get("id")?.asInt()
            Log.d(TAG, "[Anidap] anilist matched '${chosen.get("title")?.str("romaji")}' id=$id score=$bestScore")
            id
        } catch (e: Exception) {
            Log.e(TAG, "[Anidap] anilist failed: ${e.message}")
            null
        }
    }

    private suspend fun sourceAnidap(
        tmdbId: Int,
        title: String,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "Anidap"
        try {
            val anilistId = anilistSearch(title, null)
            if (anilistId == null) {
                Log.e(TAG, "[$label] no anilist id for '$title'")
                return false
            }
            val animeRes = app.get(
                "$ANIDAP/api/anime/$anilistId",
                headers = hdr(
                    "Referer" to "$ANIDAP/",
                    "Origin" to ANIDAP,
                    "Accept" to "application/json"
                ),
                timeout = 20_000L
            )
            if (animeRes.code != 200) {
                Log.e(TAG, "[$label] anime HTTP ${animeRes.code}")
                return false
            }
            val animeId = parseJsonSafe(animeRes.text)?.get("data")?.str("id")
            if (animeId.isNullOrBlank()) {
                Log.e(TAG, "[$label] anime not on Anidap")
                return false
            }

            var count = 0
            outer@ for (audioType in listOf("sub", "dub")) {
                for (provider in listOf("yuki", "beep", "uwu")) {
                    try {
                        val srcRes = app.get(
                            "$ANIDAP_API/rest/api/sources?id=${URLEncoder.encode(animeId, "UTF-8")}&epNum=$episode&type=$audioType&providerId=$provider",
                            headers = hdr(
                                "Referer" to "$ANIDAP/",
                                "Origin" to ANIDAP,
                                "Accept" to "application/json"
                            ),
                            timeout = 30_000L
                        )
                        if (srcRes.code != 200) {
                            Log.e(TAG, "[$label] $provider/$audioType HTTP ${srcRes.code}")
                            continue
                        }
                        val root = parseJsonSafe(srcRes.text) ?: continue
                        if (root.has("error") || root.get("sources")?.takeIf { it.isArray } == null) {
                            Log.e(TAG, "[$label] $provider/$audioType error=${root.str("error")}")
                            continue
                        }
                        val resHeaders = root.get("headers")?.takeIf { it.isObject }
                        val referer = resHeaders?.str("Referer") ?: resHeaders?.str("referer")
                        val origin = resHeaders?.str("Origin") ?: resHeaders?.str("origin")
                        val tracks = root.get("tracks")?.takeIf { it.isArray }

                        for (src in root.get("sources")) {
                            val url = src.str("url") ?: continue
                            if (!url.startsWith("http")) continue
                            val streamHeaders = when {
                                referer != null -> hdr("Referer" to referer, "Origin" to (origin ?: ""))
                                    .filterValues { it.isNotBlank() }
                                Regex("megap\\.|norami\\.|shiora\\.|megaplay").containsMatchIn(url) ->
                                    hdr("Referer" to "https://megaplay.buzz/", "Origin" to "https://megaplay.buzz")
                                Regex("vivibebe|hawk\\.aniwatchtv|anizara").containsMatchIn(url) ->
                                    hdr("Referer" to "$ANIDAP/", "Origin" to ANIDAP)
                                else -> hdr()
                            }
                            val audioLabel = if (audioType == "dub") "English Dub" else "Japanese"
                            val streamReferer = streamHeaders["Referer"] ?: ""
                            count += emitHls(
                                "$label $provider ($audioLabel)",
                                url,
                                streamReferer,
                                streamHeaders,
                                callback
                            )

                            if (tracks != null) {
                                for (track in tracks) {
                                    val kind = track.str("kind") ?: "captions"
                                    if (kind != "captions" && kind != "subtitles") continue
                                    val tUrl = track.str("url") ?: continue
                                    val tLabel = track.str("label") ?: track.str("lang") ?: "English"
                                    if (tUrl.contains("thumbnail", true)) continue
                                    if (tUrl.contains(".vtt") || tUrl.contains(".srt")) {
                                        subtitleCallback.invoke(newSubtitleFile(tLabel, tUrl) {})
                                    }
                                }
                            }
                        }
                        if (count >= 2) break@outer
                    } catch (e: Exception) {
                        Log.e(TAG, "[$label] $provider/$audioType failed: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "[$label] links=$count")
            return count > 0
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // TQQ (Anime) - AniKoto mirrors with sub/hsub/dub servers
    private data class TqqAnime(val slug: String, val title: String, val altTitle: String?, val animeId: String?)

    private fun tqqNormalize(t: String): String =
        t.trim().lowercase().replace(Regex("['\":]"), "").replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')

    private fun tqqHasSeasonMarker(t: String, season: Int): Boolean {
        val n = tqqNormalize(t)
        return n.contains("_season_$season") || n.contains("_${season}st_season") ||
            n.contains("_${season}nd_season") || n.contains("_${season}rd_season") ||
            n.contains("_${season}th_season")
    }

    private fun tqqIsSpecial(t: String): Boolean {
        val n = tqqNormalize(t)
        return n.contains("_special") || n.contains("_ova") || n.contains("_ona") ||
            n.contains("_movie") || n.contains("_reawakening")
    }

    private fun tqqMatchScore(candidate: String, target: String): Int {
        val a = tqqNormalize(candidate)
        val b = tqqNormalize(target)
        if (a == b) return 1000
        return if (a.contains(b) && b.length >= 4) 100 else if (b.contains(a) && a.length >= 4) 80 else 0
    }

    private suspend fun tqqSearch(base: String, keyword: String): List<TqqAnime> {
        val res = app.get(
            "$base/filter?keyword=${URLEncoder.encode(keyword, "UTF-8")}",
            headers = hdr(
                "Referer" to "$base/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30_000L
        )
        if (res.code != 200) {
            Log.e(TAG, "[TQQ] search HTTP ${res.code} on $base")
            return emptyList()
        }
        val doc = res.document
        val out = mutableListOf<TqqAnime>()
        doc.select("#list-items > .item").forEach { item ->
            val href = item.select("a").first()?.attr("href") ?: ""
            val slug = href.substringAfter("/watch/", "").split("/").firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            val titleEl = item.select(".info .b1 a.name.d-title").first() ?: return@forEach
            val title = titleEl.text().trim()
            if (title.isBlank()) return@forEach
            val altTitle = titleEl.attr("data-jp")?.trim()?.takeIf { it.isNotBlank() }
            val animeId = item.select(".ani.poster.tip").first()?.attr("data-tip")?.takeIf { it.isNotBlank() }
            out.add(TqqAnime(slug, title, altTitle, animeId))
        }
        return out
    }

    private suspend fun sourceTqq(
        title: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "TQQ"
        for (base in TQQ_MIRRORS) {
            try {
                Log.d(TAG, "[$label] trying mirror $base")
                val ok = tqqResolveMirror(base, title, season, episode, subtitleCallback, callback)
                if (ok) return true
            } catch (e: Exception) {
                Log.e(TAG, "[$label] mirror $base failed: ${e.message}")
            }
        }
        return false
    }

    private suspend fun tqqResolveMirror(
        base: String,
        title: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "TQQ"
        var candidates = tqqSearch(base, title)
        if (season > 1) {
            candidates = candidates + tqqSearch(base, "$title Season $season")
        }
        if (candidates.isEmpty()) return false
        // season > 1 prefers entries with a season marker, season 1 skips
        // specials/ova/ona/movie entries
        val seasonMarked = candidates.filter { c ->
            listOf(c.title, c.altTitle).filterNotNull().any { tqqHasSeasonMarker(it, season) }
        }
        val pool = if (season > 1 && seasonMarked.isNotEmpty()) {
            seasonMarked
        } else {
            val nonSpecial = candidates.filter { c ->
                listOf(c.title, c.altTitle).filterNotNull().none { tqqIsSpecial(it) }
            }
            if (nonSpecial.isNotEmpty()) nonSpecial else candidates
        }
        var best: TqqAnime? = null
        var bestScore = 0
        for (c in pool) {
            val names = listOf(c.title, c.altTitle).filterNotNull()
            val score = names.maxOfOrNull { tqqMatchScore(it, title) } ?: 0
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        if (best == null || bestScore < 80) {
            Log.e(TAG, "[$label] no close title match for '$title' (best=$bestScore)")
            return false
        }
        val anime = best
        Log.d(TAG, "[$label] matched '${anime.title}' slug=${anime.slug} score=$bestScore")

        val watchRes = app.get(
            "$base/watch/${anime.slug}/ep-1",
            headers = hdr("Referer" to "$base/", "Accept" to "text/html"),
            timeout = 30_000L
        )
        if (watchRes.code != 200) {
            Log.e(TAG, "[$label] watch page HTTP ${watchRes.code}")
            return false
        }
        val animeId = watchRes.document.selectFirst("#watch-main")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: anime.animeId
        if (animeId.isNullOrBlank()) {
            Log.e(TAG, "[$label] could not resolve anime id")
            return false
        }

        val ajaxHeaders = hdr(
            "Referer" to "$base/watch/${anime.slug}",
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01"
        )
        val epRes = app.get("$base/ajax/episode/list/$animeId", headers = ajaxHeaders, timeout = 30_000L)
        if (epRes.code != 200) {
            Log.e(TAG, "[$label] episode list HTTP ${epRes.code}")
            return false
        }
        val epRoot = parseJsonSafe(epRes.text)
        val epHtml = epRoot?.str("result") ?: epRes.text
        // anchor attrs can appear in either order, scan all <a> tags
        var serverIds: String? = null
        for (m in Regex("""<a([^>]*)>""").findAll(epHtml)) {
            val attrs = m.groupValues[1]
            val num = Regex("""data-num="(\d+)"""").find(attrs)?.groupValues?.get(1)
            val ids = Regex("""data-ids="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            if (num == episode.toString() && ids != null) {
                serverIds = ids
                break
            }
        }
        if (serverIds.isNullOrBlank()) {
            Log.e(TAG, "[$label] episode $episode not found")
            return false
        }

        val listRes = app.get(
            "$base/ajax/server/list?servers=${URLEncoder.encode(serverIds, "UTF-8")}",
            headers = ajaxHeaders,
            timeout = 30_000L
        )
        if (listRes.code != 200) {
            Log.e(TAG, "[$label] server list HTTP ${listRes.code}")
            return false
        }
        val listRoot = parseJsonSafe(listRes.text)
        val listHtml = listRoot?.str("result") ?: listRes.text

        data class Server(val type: String, val name: String, val linkId: String)

        val servers = mutableListOf<Server>()
        Regex("""<div[^>]*class="type"[^>]*data-type="([^"]*)"[^>]*>([\s\S]*?)</div>""").findAll(listHtml)
            .forEach { typeMatch ->
                val type = typeMatch.groupValues[1].ifBlank { "sub" }.lowercase()
                Regex("""<li[^>]*data-link-id="([^"]*)"[^>]*>([\s\S]*?)</li>""").findAll(typeMatch.groupValues[2])
                    .forEach { liMatch ->
                        val linkId = liMatch.groupValues[1]
                        val name = Regex("<[^>]*>").replace(liMatch.groupValues[2], "").trim()
                        if (linkId.isNotBlank()) servers.add(Server(type, name, linkId))
                    }
            }
        if (servers.isEmpty()) {
            Log.e(TAG, "[$label] no servers parsed")
            return false
        }
        // sub first, then hsub, then dub; hd-1 first, then vidplay, vidstream
        val typeOrder = mapOf("sub" to 0, "hsub" to 1, "dub" to 2)
        val nameOrder = { n: String ->
            when {
                n.lowercase().contains("hd-1") -> 0
                n.lowercase().contains("vidplay") -> 1
                n.lowercase().contains("vidstream") -> 2
                else -> 3
            }
        }
        servers.sortWith(compareBy({ typeOrder[it.type] ?: 3 }, { nameOrder(it.name) }))
        Log.d(TAG, "[$label] servers=${servers.joinToString { "${it.type}/${it.name}" }}")

        var emitted = 0
        val seenAudio = mutableSetOf<String>()
        for (server in servers) {
            val audioLang = when (server.type) {
                "dub" -> "en"
                "hsub" -> "ja"
                else -> "ja"
            }
            if (seenAudio.contains(audioLang)) continue
            if (emitted >= 4) break
            try {
                val serverRes = app.get(
                    "$base/ajax/server?get=${URLEncoder.encode(server.linkId, "UTF-8")}",
                    headers = ajaxHeaders,
                    timeout = 30_000L
                )
                if (serverRes.code != 200) {
                    Log.e(TAG, "[$label] server HTTP ${serverRes.code} (${server.type}/${server.name})")
                    continue
                }
                val serverRoot = parseJsonSafe(serverRes.text)
                var embedUrl = serverRoot?.get("result")?.str("url")
                if (embedUrl == null && serverRoot?.get("result")?.isObject == true) {
                    embedUrl = try {
                        val inner = serverRoot.get("result").asText()
                        parseJsonSafe(inner)?.str("url")
                    } catch (e: Exception) { null }
                }
                if (embedUrl.isNullOrBlank()) {
                    Log.e(TAG, "[$label] no embed url for ${server.type}/${server.name}")
                    continue
                }
                // per-type rewrite: swap /sub and /dub path segments to the requested audio
                if (server.type == "dub") embedUrl = embedUrl.replace("/sub/", "/dub/")
                else if (embedUrl.contains("/dub/") && server.type != "dub") embedUrl = embedUrl.replace("/dub/", "/sub/")

                if (tqqResolveEmbed(embedUrl, server.type, server.name, subtitleCallback, callback)) {
                    emitted++
                    seenAudio.add(audioLang)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$label] server ${server.type}/${server.name} failed: ${e.message}")
            }
        }
        return emitted > 0
    }

    private suspend fun tqqResolveEmbed(
        embedUrl: String,
        audioType: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "TQQ"
        val host = try {
            val u = URL(embedUrl)
            "https://${u.host}"
        } catch (e: Exception) {
            return false
        }
        val res = app.get(
            embedUrl,
            headers = hdr(
                "Referer" to embedUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            ),
            timeout = 30_000L
        )
        if (res.code != 200) {
            Log.e(TAG, "[$label] embed HTTP ${res.code} for $embedUrl")
            return false
        }
        val html = res.text
        val dataId = Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""/stream/s-\d+/(\d+)""").find(embedUrl)?.groupValues?.get(1)
        if (dataId.isNullOrBlank()) {
            Log.e(TAG, "[$label] no data-id on embed page")
            return false
        }
        val srcRes = app.get(
            "$host/stream/getSources?id=$dataId",
            headers = hdr(
                "Referer" to embedUrl,
                "Origin" to host,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            ),
            timeout = 30_000L
        )
        if (srcRes.code != 200) {
            Log.e(TAG, "[$label] getSources HTTP ${srcRes.code}")
            return false
        }
        val root = parseJsonSafe(srcRes.text) ?: return false
        val sourcesEl = root.get("sources") ?: return false
        val m3u8 = when {
            sourcesEl.isObject -> sourcesEl.str("file")
            sourcesEl.isArray && sourcesEl.size() > 0 -> sourcesEl.get(0).str("file")
            else -> null
        }
        if (m3u8.isNullOrBlank()) {
            Log.e(TAG, "[$label] no m3u8 in getSources")
            return false
        }
        val audioLabel = when (audioType) {
            "dub" -> "English Dub"
            "hsub" -> "Japanese (HS)"
            else -> "Japanese Sub"
        }
        Log.d(TAG, "[$label] m3u8=${m3u8.take(90)} audio=$audioLabel")
        val playbackHeaders = hdr(
            "Referer" to "$host/",
            "Origin" to host
        )
        val tqqName = if (serverName.isBlank()) "TQQ" else "TQQ $serverName"
        val emitted = emitHls(
            "$tqqName ($audioLabel)",
            m3u8,
            "$host/",
            playbackHeaders,
            callback
        )
        if (emitted == 0) {
            Log.e(TAG, "[$label] no playable link from embed $serverName ($audioLabel)")
            return false
        }

        val tracks = root.get("tracks")?.takeIf { it.isArray }
        if (tracks != null) {
            for (track in tracks) {
                val kind = track.str("kind") ?: "captions"
                if (kind != "captions" && kind != "subtitles") continue
                val file = track.str("url") ?: track.str("file") ?: continue
                val tLabel = track.str("label") ?: "English"
                val trackUrl = if (file.startsWith("http")) file else "$host/${file.removePrefix("/")}"
                val subHeaders = when {
                    trackUrl.contains("lostproject.club") -> mapOf("Referer" to "https://megaplay.buzz/")
                    trackUrl.contains("nekostream.site") -> mapOf("Referer" to "$host/")
                    else -> playbackHeaders
                }
                subtitleCallback.invoke(newSubtitleFile(tLabel, trackUrl) {
                    this.headers = subHeaders
                })
            }
        }
        return true
    }

    // Nova - novahd.cc API behind Cloudflare
    private suspend fun sourceNova(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "Nova"
        try {
            val path = if (season != null && episode != null) {
                "/api/sources?type=show&tmdbId=$tmdbId&season=$season&episode=$episode"
            } else {
                "/api/sources?type=movie&tmdbId=$tmdbId"
            }
            val novaHeaders = hdr(
                "Referer" to "$NOVA/",
                "Origin" to NOVA,
                "Accept" to "application/json"
            )
            // headers for the actual stream playback (no api Accept header)
            val playbackHeaders = hdr("Referer" to "$NOVA/", "Origin" to NOVA)
            var root: JsonNode? = null
            for (attempt in 1..3) {
                val res = KdesaCF.get("$NOVA$path", headers = novaHeaders)
                if (res.code != 200) {
                    Log.e(TAG, "[$label] HTTP ${res.code} attempt $attempt")
                    if (res.code == 403 || res.code == 503) return false
                    continue
                }
                val parsed = parseJsonSafe(res.text) ?: run {
                    Log.e(TAG, "[$label] invalid JSON attempt $attempt")
                    continue
                }
                if (parsed.get("ready")?.asBoolean() == false) {
                    delay(1200)
                    continue
                }
                root = parsed
                break
            }
            if (root == null) {
                Log.e(TAG, "[$label] gave up after retries")
                return false
            }
            val sources = root.get("sources")?.takeIf { it.isArray } ?: run {
                Log.e(TAG, "[$label] no sources")
                return false
            }
            Log.d(TAG, "[$label] got ${sources.size()} sources")

            val subs = root.get("subtitles")?.takeIf { it.isArray }
            if (subs != null) {
                for (sub in subs) {
                    val url = sub.str("url") ?: continue
                    val subUrl = when {
                        url.startsWith("http") -> url
                        url.startsWith("/") -> "$NOVA$url"
                        else -> continue
                    }
                    val subLabel = sub.str("label") ?: sub.str("lang") ?: continue
                    if (!subUrl.contains(".vtt") && !subUrl.contains(".srt")) continue
                    subtitleCallback.invoke(newSubtitleFile(subLabel, subUrl) {})
                }
            }

            // the api returns provider x language x quality entries (20+ for the
            // same episode) - collapse them into one link per language
            data class NovaSrc(
                val url: String,
                val provider: String,
                val type: String,
                val quality: String,
                val lang: String
            )
            val all = mutableListOf<NovaSrc>()
            for (src in sources) {
                val url = src.str("url") ?: continue
                // nova may return site-relative paths
                val fullUrl = when {
                    url.startsWith("http") -> url
                    url.startsWith("/") -> "$NOVA$url"
                    else -> continue
                }
                all.add(
                    NovaSrc(
                        url = fullUrl,
                        provider = (src.str("provider") ?: "edge").lowercase(),
                        type = (src.str("type") ?: "hls").lowercase(),
                        quality = src.str("quality") ?: "",
                        lang = (src.str("language") ?: "").trim()
                    )
                )
            }

            val providerRank = mapOf(
                "titan" to 0, "orion" to 1, "vega" to 2, "falcon" to 3, "heron" to 4, "orca" to 5
            )
            fun qualityRank(q: String) = when {
                q.contains("2160") || q.contains("4k", true) -> 5
                q.contains("1080") -> 4
                q.contains("720") -> 3
                q.contains("480") -> 2
                else -> 3 // "Auto" masters are adaptive (usually up to 1080p)
            }
            val seenKeys = mutableSetOf<String>()
            val groups = all
                .filter { seenKeys.add("${it.provider}|${it.lang}|${it.quality}") }
                .groupBy { it.lang }

            var count = 0
            for ((lang, list) in groups) {
                val hlsCandidates = list.filter { it.type != "mp4" }
                    .sortedWith(
                        compareByDescending<NovaSrc> { qualityRank(it.quality) }
                            .thenBy { providerRank[it.provider] ?: 9 }
                    )
                var bestUrl: String? = null
                var bestHeight = 0
                var probedLangs: List<String> = emptyList()
                var apiQuality = ""
                // probe up to three candidates so one dead edge does not kill the language
                for (cand in hlsCandidates.take(3)) {
                    val probe = probeHls(cand.url, playbackHeaders) ?: continue
                    bestUrl = cand.url
                    bestHeight = probe.maxHeight
                    probedLangs = probe.audioLangs
                    apiQuality = cand.quality
                    break
                }
                if (bestUrl != null) {
                    // fall back to the manifest's own audio tag when the api language is blank
                    val langLabel = when {
                        lang.isNotBlank() -> prettifyLang(lang)
                        probedLangs.size == 1 -> prettifyLang(probedLangs[0])
                        else -> "Auto"
                    }
                    val qPart = when {
                        bestHeight > 0 -> " ${bestHeight}p"
                        apiQuality.isNotBlank() && !apiQuality.equals("Auto", true) -> " $apiQuality"
                        else -> ""
                    }
                    var name = "$label $langLabel$qPart"
                    if (probedLangs.size > 1) name += audioSuffix(probedLangs)
                    callback.invoke(
                        newExtractorLink(label, name, bestUrl, ExtractorLinkType.M3U8) {
                            this.headers = playbackHeaders
                            this.quality = if (bestHeight > 0) bestHeight else Qualities.Unknown.value
                        }
                    )
                    count++
                } else {
                    // no working hls master for this language, fall back to mp4
                    val mp4 = list.filter { it.type == "mp4" }.firstOrNull()
                    if (mp4 != null) {
                        val langLabel = if (lang.isNotBlank()) prettifyLang(lang) else "Auto"
                        val qPart = mp4.quality.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
                            ?.let { " $it" } ?: ""
                        callback.invoke(
                            newExtractorLink(label, "$label $langLabel$qPart (mp4)", mp4.url, ExtractorLinkType.VIDEO) {
                                this.headers = playbackHeaders
                            }
                        )
                        count++
                    } else {
                        Log.e(TAG, "[$label] no playable stream for lang=$lang")
                    }
                }
            }
            Log.d(TAG, "[$label] links=$count (from ${all.size} raw sources)")
            return count > 0
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // FSOnline - dooplay site behind Cloudflare (Filemoon/Doodstream)
    private fun fsoSlug(title: String): String =
        Normalizer.normalize(title.trim(), Normalizer.Form.NFD)
            .lowercase()
            .replace(Regex("[^a-zA-Z0-9. ]+"), "")
            .replace(".", " ")
            .split(" ").filter { it.isNotBlank() }.joinToString("-")

    private suspend fun sourceFsonline(
        tmdbId: Int,
        title: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "FSOnline"
        try {
            val year = tmdbYear(tmdbId, season != null)
            val slugs = mutableListOf<String>()
            if (year != null) slugs.add(fsoSlug("$title $year"))
            slugs.add(fsoSlug(title))

            val pageHeaders = hdr(
                "Referer" to "$FSONLINE/",
                "Origin" to FSONLINE,
                "sec-fetch-dest" to "iframe",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "cross-site"
            )

            var movieId: String? = null
            var usedSlug = ""
            for (slug in slugs) {
                val url = if (season != null && episode != null) {
                    "$FSONLINE/episoade/$slug-sezonul-$season-episodul-$episode/"
                } else {
                    "$FSONLINE/film/$slug/"
                }
                val res = KdesaCF.get(url, headers = pageHeaders)
                if (res.code != 200) {
                    Log.e(TAG, "[$label] HTTP ${res.code} for slug '$slug'")
                    continue
                }
                movieId = res.document.selectFirst("#show_player_lazy")?.attr("movie-id")?.takeIf { it.isNotBlank() }
                if (movieId != null) {
                    usedSlug = slug
                    break
                }
            }
            if (movieId == null) {
                Log.e(TAG, "[$label] movie not found on fsonline")
                return false
            }

            val ajaxRes = KdesaCF.post(
                "$FSONLINE/wp-admin/admin-ajax.php",
                headers = hdr(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "Referer" to "$FSONLINE/film/$usedSlug/",
                    "Origin" to FSONLINE
                ),
                data = mapOf("action" to "lazy_player", "movieID" to movieId)
            )
            if (ajaxRes.code != 200) {
                Log.e(TAG, "[$label] lazy_player HTTP ${ajaxRes.code}")
                return false
            }
            val doc = ajaxRes.document
            val options = mutableMapOf<String, String>()
            doc.select("li.dooplay_player_option").forEach { li ->
                val name = li.select("span").first()?.text()?.trim() ?: return@forEach
                val vs = li.attr("data-vs").takeIf { it.isNotBlank() } ?: return@forEach
                options[name] = vs
            }
            Log.d(TAG, "[$label] player options=${options.keys}")

            var any = false
            for (hostName in listOf("Filemoon", "Doodstream")) {
                val embed = options[hostName] ?: continue
                val loaded = resolveEmbed(embed, "$FSONLINE/", "$label $hostName", subtitleCallback, callback)
                if (loaded) any = true
            }
            Log.d(TAG, "[$label] done any=$any")
            return any
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // Cuevana3 - Spanish site with streamwish/filemoon/vidhide/voe embeds
    private suspend fun tmdbTitle(type: String, tmdbId: Int, language: String): String? {
        return try {
            val res = app.get(
                "$TMDB_API/$type/$tmdbId?language=$language",
                headers = hdr(
                    "Authorization" to "Bearer $TMDB_BEARER",
                    "Accept" to "application/json"
                ),
                timeout = 20_000L
            )
            if (res.code != 200) {
                Log.e(TAG, "tmdbTitle HTTP ${res.code}")
                return null
            }
            val root = parseJsonSafe(res.text) ?: return null
            root.str("title") ?: root.str("name")
        } catch (e: Exception) {
            Log.e(TAG, "tmdbTitle failed: ${e.message}")
            null
        }
    }

    private suspend fun tmdbYear(tmdbId: Int, isTv: Boolean): Int? {
        try {
            val type = if (isTv) "tv" else "movie"
            val res = app.get(
                "$TMDB_API/$type/$tmdbId?language=en-US",
                headers = hdr(
                    "Authorization" to "Bearer $TMDB_BEARER",
                    "Accept" to "application/json"
                ),
                timeout = 20_000L
            )
            if (res.code != 200) return null
            val root = parseJsonSafe(res.text) ?: return null
            val date = root.str("release_date") ?: root.str("first_air_date") ?: return null
            return date.take(4).toIntOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "tmdbYear failed: ${e.message}")
            return null
        }
    }

    private fun cuevanaSlug(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("[\\u0300-\\u036f]"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')

    private suspend fun sourceCuevana3(
        tmdbId: Int,
        title: String,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val label = "Cuevana3"
        try {
            val isShow = season != null && episode != null
            val type = if (isShow) "tv" else "movie"
            val esTitle = tmdbTitle(type, tmdbId, "es-ES")
            Log.d(TAG, "[$label] tmdbId=$tmdbId esTitle='$esTitle' enTitle='$title'")

            val candidates = mutableListOf<Pair<String, String>>() // slug -> langTag
            if (!esTitle.isNullOrBlank()) {
                val slug = cuevanaSlug(esTitle)
                if (slug.isNotBlank()) candidates.add(slug to "es")
            }
            val enSlug = cuevanaSlug(title)
            if (enSlug.isNotBlank() && enSlug != candidates.firstOrNull()?.first) {
                candidates.add(enSlug to "en")
            }

            for ((slug, _) in candidates) {
                val url = if (isShow) {
                    "$CUEVANA3/episodio/$slug-temporada-$season-episodio-$episode"
                } else {
                    "$CUEVANA3/ver-pelicula/$slug"
                }
                val res = app.get(url, headers = hdr(), timeout = 30_000L)
                if (res.code != 200) {
                    Log.e(TAG, "[$label] HTTP ${res.code} for slug '$slug'")
                    continue
                }
                val html = res.text
                val jsonStart = html.indexOf("{\"props\":{\"pageProps\":")
                if (jsonStart == -1) {
                    Log.e(TAG, "[$label] no pageProps JSON for slug '$slug'")
                    continue
                }
                val jsonEnd = html.indexOf("</script>", jsonStart)
                val rawJson = html.substring(jsonStart, jsonEnd).trim().trimEnd(';')
                val root = parseJsonSafe(rawJson) ?: run {
                    Log.e(TAG, "[$label] pageProps JSON parse failed")
                    continue
                }
                val pageProps = root.get("props")?.get("pageProps") ?: continue
                val videosNode = if (isShow) {
                    pageProps.get("episode")?.get("videos")
                } else {
                    pageProps.get("thisMovie")?.get("videos")
                }?.takeIf { it.isObject } ?: run {
                    Log.e(TAG, "[$label] no videos object")
                    continue
                }
                val langFields = mutableListOf<String>()
                videosNode.fieldNames().forEach { langFields.add(it) }
                Log.d(TAG, "[$label] videos langs=$langFields")

                var any = false
                for (langField in langFields) {
                    val arr = videosNode.get(langField)?.takeIf { it.isArray } ?: continue
                    // one working host per language is enough - iterating every
                    // embed floods the source list with duplicates
                    var langDone = false
                    for (video in arr) {
                        if (langDone) break
                        val resultUrl = video.str("result") ?: continue
                        try {
                            val playerRes = app.get(resultUrl, headers = hdr(), timeout = 20_000L)
                            if (playerRes.code != 200) continue
                            val embed = Regex("""var url = '([^']+)'""")
                                .find(playerRes.text)?.groupValues?.get(1) ?: continue
                            val langLabel = when (langField) {
                                "latino" -> "Latino"
                                "spanish" -> "Spanish"
                                "english" -> "English"
                                "japanese" -> "Japanese"
                                else -> langField
                            }
                            val host = try { URL(embed).host } catch (e: Exception) { continue }
                            val hostLabel = when {
                                host.contains("filemoon") -> "Filemoon"
                                host.contains("streamwish") -> "StreamWish"
                                host.contains("vidhide") -> "VidHide"
                                host.contains("voe") -> "Voe"
                                else -> continue
                            }
                            if (resolveEmbed(
                                    embed, "$CUEVANA3/", "$label $langLabel ($hostLabel)",
                                    subtitleCallback, callback
                                )
                            ) {
                                any = true
                                langDone = true
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[$label] player.php failed: ${e.message}")
                        }
                    }
                }
                if (any) return true
            }
            Log.e(TAG, "[$label] no playable embeds")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "[$label] failed: ${e.message}")
            return false
        }
    }

    // Shared embed resolution: built-in extractors first, then inline
    // fallbacks for mirror domains the built-ins do not cover

    private suspend fun loadExtractorRelabeled(
        url: String,
        referer: String,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val collected = mutableListOf<ExtractorLink>()
        val loaded = com.lagradost.cloudstream3.utils.loadExtractor(
            url, referer, subtitleCallback
        ) { link ->
            collected.add(link)
        }
        for (link in collected) {
            callback.invoke(
                newExtractorLink(link.source, "$labelPrefix - ${link.name}", link.url, link.type) {
                    this.referer = link.referer
                    this.headers = link.headers
                    this.quality = link.quality
                    this.extractorData = link.extractorData
                }
            )
        }
        Log.d(TAG, "loadExtractor($url) -> $loaded (emitted=${collected.size})")
        return loaded
    }

    private suspend fun resolveEmbed(
        url: String,
        referer: String,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (loadExtractorRelabeled(url, referer, labelPrefix, subtitleCallback, callback)) return true
        Log.d(TAG, "resolveEmbed: built-in extractor missed $url, trying inline fallbacks")
        if (filemoonInline(url, labelPrefix, subtitleCallback, callback)) return true
        if (doodInline(url, labelPrefix, callback)) return true
        if (webViewIntercept(url, referer, labelPrefix, callback)) return true
        Log.e(TAG, "resolveEmbed: all methods failed for $url")
        return false
    }

    // filemoon-style: embed page -> iframe -> packed js -> file:"..." (m3u8)
    private suspend fun filemoonInline(
        url: String,
        labelPrefix: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val mobileUa =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
            val origin = try {
                val u = URL(url)
                "${u.protocol}://${u.host}"
            } catch (e: Exception) {
                return false
            }
            val pageHeaders = mapOf(
                "User-Agent" to mobileUa,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Referer" to "$origin/",
                "Accept-Language" to "en-GB,en-US;q=0.9,en;q=0.8",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-User" to "?1",
                "Upgrade-Insecure-Requests" to "1"
            )
            val doc = app.get(url, headers = pageHeaders, timeout = 30_000L).document
            var iframeSrc = doc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
            var scriptData: String? = null

            if (iframeSrc != null) {
                if (!iframeSrc.startsWith("http")) iframeSrc = "$origin${if (iframeSrc.startsWith("/")) "" else "/"}$iframeSrc"
                Log.d(TAG, "filemoonInline: iframe -> $iframeSrc")
                val iframeDoc = app.get(iframeSrc, headers = pageHeaders, timeout = 30_000L).document
                scriptData = iframeDoc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
            } else {
                scriptData = doc.selectFirst("script:containsData(eval(function(p,a,c,k,e,d)))")?.data()
            }
            if (scriptData.isNullOrBlank()) {
                Log.e(TAG, "filemoonInline: no packed script for $url")
                return false
            }
            val unpacked = JsUnpacker(scriptData).unpack()
            if (unpacked.isNullOrBlank()) {
                Log.e(TAG, "filemoonInline: unpack failed for $url")
                return false
            }
            val m3u8 = Regex("""file:"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
            if (m3u8.isNullOrBlank() || !m3u8.startsWith("http")) {
                Log.e(TAG, "filemoonInline: no file url in unpacked js")
                return false
            }
            Log.d(TAG, "filemoonInline: m3u8=${m3u8.take(90)}")
            val emitted = emitHls(
                labelPrefix,
                m3u8,
                "$origin/",
                mapOf("Referer" to "$origin/", "User-Agent" to mobileUa),
                callback
            )
            return emitted > 0
        } catch (e: Exception) {
            Log.e(TAG, "filemoonInline failed for $url: ${e.message}")
            return false
        }
    }

    // doodstream-style: pass_md5 flow -> direct mp4
    private suspend fun doodInline(
        url: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val iphoneUa =
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1"
            val pageHeaders = mapOf(
                "User-Agent" to iphoneUa,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-Mode" to "navigate",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-Fetch-Dest" to "document",
                "Connection" to "keep-alive"
            )
            val res = app.get(url, headers = pageHeaders, timeout = 30_000L)
            val finalUrl = res.url
            val html = res.text
            val passMd5 = Regex("""\$\.get\(['"](\/pass_md5\/[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (passMd5.isNullOrBlank()) {
                Log.e(TAG, "doodInline: no pass_md5 for $url")
                return false
            }
            val host = try {
                val u = URL(finalUrl)
                "${u.protocol}://${u.host}"
            } catch (e: Exception) {
                return false
            }
            val token = Regex("""token["']?\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val md5Res = app.get(
                "$host$passMd5",
                headers = pageHeaders + ("Referer" to finalUrl),
                timeout = 30_000L
            )
            val md5Body = md5Res.text.trim()
            if (md5Body.isBlank() || !md5Body.startsWith("http")) {
                Log.e(TAG, "doodInline: pass_md5 gave no url")
                return false
            }
            val random = buildString {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                repeat(10) { append(chars[(0..61).random()]) }
            }
            val final = if (token != null) {
                "$md5Body$random?token=$token&expiry=${System.currentTimeMillis()}"
            } else {
                md5Body
            }
            Log.d(TAG, "doodInline: mp4=${final.take(90)}")
            callback.invoke(
                newExtractorLink(labelPrefix, labelPrefix, final, ExtractorLinkType.VIDEO) {
                    this.headers = mapOf("Referer" to "$host/", "User-Agent" to iphoneUa)
                }
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "doodInline failed for $url: ${e.message}")
            return false
        }
    }

    // last resort: load the embed in a WebView and intercept the first m3u8/mp4
    private suspend fun webViewIntercept(
        url: String,
        referer: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d(TAG, "webViewIntercept: $url")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(url, referer = referer, interceptor = resolver).url
            if (resolved.isBlank()) {
                Log.e(TAG, "webViewIntercept: nothing intercepted for $url")
                return false
            }
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    val emitted = emitHls(labelPrefix, resolved, url, headers, callback)
                    emitted > 0
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(labelPrefix, labelPrefix, resolved, ExtractorLinkType.VIDEO) {
                            this.headers = headers
                        }
                    )
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "webViewIntercept failed for $url: ${e.message}")
            false
        }
    }
}
