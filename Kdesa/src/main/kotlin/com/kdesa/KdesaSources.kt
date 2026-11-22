package com.kdesa

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "KdesaSources"

private const val UA =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"

// the embed gate trusts the parent site, every playback call has to present it
@Volatile
var PARENT = "https://7movies.ac"
    internal set
private const val EMBED = "https://embed.vidrift.net"
private const val EMBED_REFERER = "https://embed.vidrift.net/"

private fun playbackHeaders(accept: String, referer: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val headers = mutableMapOf(
        "Accept" to accept,
        "User-Agent" to UA,
        "Referer" to referer
    )
    headers.putAll(extra)
    return headers
}

private fun NiceResponse.close() {
    try {
        okhttpResponse.close()
    } catch (_: Exception) {}
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TokenResp(val token: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SubTrack(
    val code: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val url: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OrionRung(val width: Int? = null, val height: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class OrionStream(
    val index: Int? = null,
    val url: String? = null,
    val name: String? = null,
    val rungs: List<OrionRung>? = null,
    @JsonProperty("subtitleListUrl") val subtitleListUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BootMeta(
    @JsonProperty("bigbangUrl") val bigbangUrl: String? = null,
    @JsonProperty("bigbangSubtitles") val bigbangSubtitles: List<SubTrack>? = null,
    @JsonProperty("evionUrl") val evionUrl: String? = null,
    @JsonProperty("vexonUrl") val vexonUrl: String? = null,
    @JsonProperty("selfhostUrl") val selfhostUrl: String? = null,
    @JsonProperty("selfhostKind") val selfhostKind: String? = null,
    @JsonProperty("orionStreams") val orionStreams: List<OrionStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BootShell(val subtitles: List<SubTrack>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BootResp(val meta: BootMeta? = null, val shell: BootShell? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SubtitleListResp(val tracks: List<SubTrack>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProviderStream(
    val index: Int? = null,
    val url: String? = null,
    val type: String? = null,
    val provider: String? = null,
    val name: String? = null,
    val direct: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SourceResp(
    val success: Boolean? = null,
    val provider: String? = null,
    val source: String? = null,
    @JsonProperty("maxRes") val maxRes: Int? = null,
    val quality: String? = null,
    val streams: List<ProviderStream>? = null,
    val subtitles: List<SubTrack>? = null
)

class KdesaSources {

    private val cfKillers = ConcurrentHashMap<String, CloudflareKiller>()
    private val cfLocks = ConcurrentHashMap<String, Mutex>()

    private fun killerFor(url: String): CloudflareKiller =
        cfKillers.getOrPut(url.substringBefore("://")) { CloudflareKiller() }

    private fun lockFor(url: String): Mutex =
        cfLocks.getOrPut(url.substringBefore("://")) { Mutex() }

    private fun isCfChallenge(res: NiceResponse): Boolean {
        if (res.headers["cf-mitigated"] == "challenge") return true
        val body = try {
            res.text.lowercase()
        } catch (_: Exception) {
            ""
        }
        return (body.contains("just a moment") && body.contains("challenge-platform")) ||
            body.contains("checking your browser")
    }

    // the embed host sits behind cloudflare and challenges whole ip ranges
    // without warning, the webview solver is the only way through
    private suspend fun embedGet(
        url: String,
        referer: String,
        extra: Map<String, String> = emptyMap()
    ): NiceResponse? {
        val headers = playbackHeaders("*/*", referer, extra)
        val plain = try {
            app.get(url, headers = headers, timeout = 30L)
        } catch (_: Exception) {
            null
        }
        if (plain != null && !isCfChallenge(plain)) {
            if (plain.code == 200) return plain
            plain.close()
            return null
        }
        plain?.close()

        return lockFor(url).withLock {
            val killer = killerFor(url)
            try {
                killer.savedCookies.remove(java.net.URI(url).host)
            } catch (_: Exception) {}
            val solved = try {
                app.get(url, headers = headers, interceptor = killer, timeout = 60L)
            } catch (_: Exception) {
                null
            }
            if (solved != null && solved.code == 200 && !isCfChallenge(solved)) solved else null
        }
    }

    private fun qualityFromHeight(height: Int): Int = when {
        height >= 2160 -> Qualities.P2160.value
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun qualityFromLabel(label: String?): Int {
        val text = label ?: return Qualities.Unknown.value
        return when {
            text.contains("2160", true) || text.contains("4k", true) -> Qualities.P2160.value
            text.contains("1080", true) || text.contains("full hd", true) -> Qualities.P1080.value
            text.contains("720", true) || text.contains("hd", true) -> Qualities.P720.value
            else -> Qualities.Unknown.value
        }
    }

    // a peek at the master playlist proves the url is live and gives the top resolution
    private suspend fun masterQuality(url: String): Int {
        return try {
            val text = app.get(
                url,
                headers = playbackHeaders("*/*", EMBED_REFERER),
                timeout = 15L
            ).text
            Regex("RESOLUTION=\\d+x(\\d+)")
                .findAll(text)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun pushLink(
        label: String,
        url: String,
        quality: Int,
        type: ExtractorLinkType,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(source = "KDesa", name = label, url = url, type = type) {
                this.referer = EMBED_REFERER
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to EMBED_REFERER
                )
                this.quality = quality
            }
        )
    }

    private suspend fun pushHls(
        label: String,
        url: String,
        fallbackQuality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val quality = masterQuality(url).let {
            if (it > 0) qualityFromHeight(it) else fallbackQuality
        }
        pushLink(label, url, quality, ExtractorLinkType.M3U8, callback)
    }

    private suspend fun pushSubtitle(track: SubTrack, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = track.url?.takeIf { it.isNotBlank() } ?: return
        val absolute = if (url.startsWith("http")) url else EMBED + url
        val label = track.label?.takeIf { it.isNotBlank() }
            ?: track.lang?.takeIf { it.isNotBlank() }
            ?: "Subtitle"
        val lang = track.lang?.takeIf { it.isNotBlank() } ?: "en"
        val headerMap = mapOf(
            "User-Agent" to UA,
            "Referer" to EMBED_REFERER
        )
        subtitleCallback.invoke(
            newSubtitleFile(label, absolute) {
                this.lang = lang
                this.headers = headerMap
            }
        )
    }

    // orion keeps its subtitle list behind the same referer gate as the stream
    private suspend fun pushOrionSubtitles(
        listUrl: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (listUrl.isNullOrBlank()) return
        try {
            val url = if (listUrl.startsWith("http")) listUrl else EMBED + listUrl
            val response = app.get(
                url,
                headers = playbackHeaders("*/*", EMBED_REFERER),
                timeout = 15L
            )
            parseJson<SubtitleListResp>(response.text).tracks.orEmpty().forEach {
                pushSubtitle(it, subtitleCallback)
            }
        } catch (e: Exception) {
            Log.d(TAG, "orion subtitles: ${e.message}")
        }
    }

    // the player cycles providers one call at a time, titan now, atlas next,
    // orion after that; every answer holds a different set of streams
    private suspend fun pullRotatingSources(
        type: String,
        tmdbId: Int,
        season: Int,
        episode: Int,
        token: String,
        seenUrls: ConcurrentHashMap<String, Boolean>,
        callback: (ExtractorLink) -> Unit
    ) {
        val path = if (type == "tv") {
            "$EMBED/api/source/tv/$tmdbId/$season/$episode?token=$token"
        } else {
            "$EMBED/api/source/movie/$tmdbId?token=$token"
        }
        val seenProviders = mutableSetOf<String>()
        repeat(4) {
            val response = embedGet(path, "$EMBED/embed2/play") ?: return@repeat
            val parsed = try {
                parseJson<SourceResp>(response.text)
            } catch (e: Exception) {
                Log.d(TAG, "source rotation: ${e.message}")
                return@repeat
            }
            response.close()
            val provider = parsed.provider ?: return@repeat
            if (!seenProviders.add(provider)) return@repeat
            val maxRes = parsed.maxRes ?: 0
            parsed.streams.orEmpty().forEach { stream ->
                val url = stream.url?.takeIf { it.isNotBlank() } ?: return@forEach
                if (!url.startsWith("http") && !url.startsWith("/")) return@forEach
                val absolute = if (url.startsWith("http")) url else EMBED + url
                if (seenUrls.putIfAbsent(absolute, true) != null) return@forEach
                val label = stream.name?.takeIf { it.isNotBlank() }
                    ?: stream.provider?.takeIf { it.isNotBlank() }
                    ?: provider.replaceFirstChar { it.uppercase() }
                val quality = qualityFromHeight(maxRes).takeIf { it != Qualities.Unknown.value }
                    ?: qualityFromLabel(label)
                if (stream.type?.equals("mp4", true) == true) {
                    pushLink(label, absolute, quality, ExtractorLinkType.VIDEO, callback)
                } else {
                    // the master playlist carries the real resolution for
                    // rotation streams that come without a maxRes hint
                    pushHls(label, absolute, quality, callback)
                }
            }
        }
    }

    private suspend fun resolve(
        type: String,
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seasonNum = season ?: 1
        val episodeNum = episode ?: 1
        val token = try {
            val body = """{"tmdbId":$tmdbId,"type":"$type","season":$seasonNum,"episode":$episodeNum}"""
            val response = app.post(
                "$PARENT/api/playback-token",
                json = body,
                headers = playbackHeaders(
                    "*/*",
                    "$PARENT/${if (type == "tv") "tv" else "movie"}/$tmdbId",
                    mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to PARENT
                    )
                ),
                timeout = 30L
            )
            if (response.code != 200) return false
            parseJson<TokenResp>(response.text).token ?: return false
        } catch (e: Exception) {
            Log.d(TAG, "token: ${e.message}")
            return false
        }

        val bootPath = if (type == "tv") {
            "/api/boot/tv/$tmdbId/$seasonNum/$episodeNum?type=tv&id=$tmdbId&season=$seasonNum&episode=$episodeNum&source=0&shell=1&token=$token"
        } else {
            "/api/boot/movie/$tmdbId?type=movie&id=$tmdbId&source=0&shell=1&token=$token"
        }
        val boot = try {
            val response = embedGet(EMBED + bootPath, "$EMBED/embed2/play", mapOf("x-embed-parent" to PARENT))
                ?: return false
            if (response.code != 200) return false
            parseJson<BootResp>(response.text)
        } catch (e: Exception) {
            Log.d(TAG, "boot: ${e.message}")
            return false
        }
        val meta = boot.meta
        val seenUrls = ConcurrentHashMap<String, Boolean>()
        val emitted = AtomicBoolean(false)

        meta?.bigbangUrl?.takeIf { it.isNotBlank() }?.let {
            pushHls("BigBang", it, Qualities.Unknown.value, callback)
            emitted.set(true)
        }
        meta?.vexonUrl?.takeIf { it.isNotBlank() }?.let {
            pushHls("Vexon", it, Qualities.Unknown.value, callback)
            emitted.set(true)
        }
        meta?.evionUrl?.takeIf { it.isNotBlank() }?.let {
            pushHls("Evion", it, Qualities.Unknown.value, callback)
            emitted.set(true)
        }
        meta?.selfhostUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val isMp4 = meta.selfhostKind?.contains("mp4", ignoreCase = true) == true
            if (isMp4) {
                pushLink("Selfhost", url, Qualities.Unknown.value, ExtractorLinkType.VIDEO, callback)
            } else {
                pushHls("Selfhost", url, Qualities.Unknown.value, callback)
            }
            emitted.set(true)
        }
        meta?.orionStreams.orEmpty().forEach { stream ->
            val url = stream.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val absolute = if (url.startsWith("http")) url else EMBED + url
            if (seenUrls.putIfAbsent(absolute, true) == null) {
                val label = stream.name?.takeIf { it.isNotBlank() } ?: "Orion"
                val height = stream.rungs.orEmpty().mapNotNull { it.height }.maxOrNull() ?: 0
                val quality = if (height > 0) qualityFromHeight(height) else Qualities.Unknown.value
                pushLink(label, absolute, quality, ExtractorLinkType.M3U8, callback)
                emitted.set(true)
            }
            // per stream lists only matter when the shell menu came back empty
            if (boot.shell?.subtitles.isNullOrEmpty()) {
                pushOrionSubtitles(stream.subtitleListUrl, subtitleCallback)
            }
        }

        meta?.bigbangSubtitles.orEmpty().forEach { pushSubtitle(it, subtitleCallback) }
        boot.shell?.subtitles.orEmpty().forEach { pushSubtitle(it, subtitleCallback) }

        try {
            pullRotatingSources(type, tmdbId, seasonNum, episodeNum, token, seenUrls) {
                emitted.set(true)
                callback(it)
            }
        } catch (e: Exception) {
            Log.d(TAG, "rotating sources: ${e.message}")
        }

        return emitted.get()
    }

    suspend fun resolveMovie(
        tmdbId: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = resolve("movie", tmdbId, null, null, subtitleCallback, callback)

    suspend fun resolveShow(
        tmdbId: Int,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = resolve("tv", tmdbId, season, episode, subtitleCallback, callback)
}
