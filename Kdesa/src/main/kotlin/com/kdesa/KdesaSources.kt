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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "Kdesa"

private const val UA =
    "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"

// the player sits on its own host behind cloudflare, all api calls go
// through the same client so a solved challenge is shared between them
internal object VidriftClient {
    const val EMBED = "https://embed.vidrift.net"
    const val API_REFERER = "https://embed.vidrift.net/embed2/play"
    const val MEDIA_REFERER = "https://embed.vidrift.net/"

    private val killer = CloudflareKiller()
    private val warmup = Mutex()

    @Volatile
    private var ready = false

    private suspend fun fetch(url: String, extra: Map<String, String>) = try {
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "User-Agent" to UA,
            "Referer" to API_REFERER
        )
        headers.putAll(extra)
        app.get(url, headers = headers, interceptor = killer, timeout = 30L)
    } catch (e: Exception) {
        Log.d(TAG, "embed request failed: ${e.message}")
        null
    }

    // the webview solver is not safe to run from parallel requests, so the
    // first call goes alone and later ones reuse whatever it unlocked
    suspend fun get(url: String, extra: Map<String, String> = emptyMap()): NiceResponse? {
        if (!ready) {
            return warmup.withLock {
                if (ready) fetch(url, extra) else fetch(url, extra)?.also { ready = true }
            }
        }
        return fetch(url, extra)
    }
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
    @JsonProperty("proxyUrl") val proxyUrl: String? = null,
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
    val anime: List<String>? = null,
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
    @JsonProperty("proxyUrl") val proxyUrl: String? = null,
    val type: String? = null,
    val provider: String? = null,
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class SourceResp(
    val provider: String? = null,
    @JsonProperty("maxRes") val maxRes: Int? = null,
    val streams: List<ProviderStream>? = null,
    val subtitles: List<SubTrack>? = null
)

// the embed serialises missing values as the string "None"
private fun playableUrl(raw: String?): String? {
    val url = raw?.takeIf { it.isNotBlank() && it != "None" } ?: return null
    return if (url.startsWith("http")) url else VidriftClient.EMBED + url
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
        text.contains("1080", true) -> Qualities.P1080.value
        text.contains("720", true) -> Qualities.P720.value
        text.contains("480", true) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}

// vela hands out one playlist per resolution with the ladder in the path
private fun qualityFromUrl(url: String): Int {
    val match = Regex("[/\\-._]s?(2160|1440|1080|720|576|480|360)p([/\\-._]|$)").find(url)
    return match?.groupValues?.get(1)?.toIntOrNull()?.let { qualityFromHeight(it) }
        ?: Qualities.Unknown.value
}

class KdesaSources(private val parent: String) {

    private val parentHost = parent.removePrefix("https://").removePrefix("http://").trimEnd('/')
    private val seenUrls = ConcurrentHashMap<String, Boolean>()

    private suspend fun pushLink(
        label: String,
        url: String,
        quality: Int,
        type: ExtractorLinkType,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(source = "KDesa", name = label, url = url, type = type) {
                this.referer = VidriftClient.MEDIA_REFERER
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to VidriftClient.MEDIA_REFERER
                )
                this.quality = quality
            }
        )
    }

    private suspend fun pushSubtitle(track: SubTrack, subtitleCallback: (SubtitleFile) -> Unit) {
        val url = playableUrl(track.url) ?: return
        val label = track.label?.takeIf { it.isNotBlank() }
            ?: track.lang?.takeIf { it.isNotBlank() }
            ?: "Subtitle"
        subtitleCallback.invoke(
            newSubtitleFile(label, url) {
                this.lang = track.lang?.takeIf { it.isNotBlank() } ?: "en"
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to VidriftClient.MEDIA_REFERER
                )
            }
        )
    }

    private suspend fun pushOrionSubtitles(
        listUrl: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val url = playableUrl(listUrl) ?: return
        val response = VidriftClient.get(url) ?: return
        try {
            parseJson<SubtitleListResp>(response.text).tracks.orEmpty().forEach {
                pushSubtitle(it, subtitleCallback)
            }
        } catch (e: Exception) {
            Log.d(TAG, "orion subtitles: ${e.message}")
        }
    }

    private fun orionQuality(stream: OrionStream): Int {
        val height = stream.rungs.orEmpty().mapNotNull { it.height }.maxOrNull() ?: return Qualities.Unknown.value
        return qualityFromHeight(height)
    }

    private suspend fun fetchToken(type: String, tmdbId: Int, season: Int, episode: Int): String? {
        return try {
            val body = """{"tmdbId":$tmdbId,"type":"$type","season":$season,"episode":$episode}"""
            val response = app.post(
                "$parent/api/playback-token",
                json = body,
                headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to UA,
                    "Content-Type" to "application/json",
                    "Origin" to parent,
                    "Referer" to "$parent/${if (type == "tv") "tv" else "movie"}/$tmdbId"
                ),
                timeout = 30L
            )
            if (response.code != 200) null else parseJson<TokenResp>(response.text).token
        } catch (e: Exception) {
            Log.d(TAG, "playback token: ${e.message}")
            null
        }
    }

    private fun sourcePath(type: String, tmdbId: Int, season: Int, episode: Int): String {
        return if (type == "tv") {
            "/api/source/tv/$tmdbId/$season/$episode"
        } else {
            "/api/source/movie/$tmdbId"
        }
    }

    // the site expects one call per provider, the player walks the same
    // cascade: anime mirrors first when flagged, then vela to lyra
    private fun providerCascade(meta: BootMeta): List<String> {
        val cascade = meta.anime.orEmpty()
            .filter { it.isNotBlank() && it != "None" }
            .toMutableList()
        if (meta.orionStreams.isNullOrEmpty()) cascade.add("orion")
        cascade.addAll(listOf("vela", "titan", "atlas", "lyra"))
        return cascade
    }

    private suspend fun fetchProvider(
        provider: String,
        type: String,
        tmdbId: Int,
        season: Int,
        episode: Int,
        token: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val path = "${sourcePath(type, tmdbId, season, episode)}?token=$token&provider=$provider&parent=$parentHost"
        val response = VidriftClient.get(VidriftClient.EMBED + path) ?: return false
        if (response.code != 200) return false
        val parsed = try {
            parseJson<SourceResp>(response.text)
        } catch (e: Exception) {
            Log.d(TAG, "source $provider: ${e.message}")
            return false
        }
        val providerName = parsed.provider?.takeIf { it.isNotBlank() } ?: provider
        val maxRes = parsed.maxRes ?: 0
        parsed.streams.orEmpty().forEach { stream ->
            val url = playableUrl(stream.url) ?: playableUrl(stream.proxyUrl) ?: return@forEach
            if (seenUrls.putIfAbsent(url, true) != null) return@forEach
            val label = stream.name?.takeIf { it.isNotBlank() }
                ?: stream.provider?.takeIf { it.isNotBlank() }
                ?: providerName
            val quality = qualityFromUrl(url)
                .takeIf { it != Qualities.Unknown.value }
                ?: qualityFromHeight(maxRes).takeIf { it != Qualities.Unknown.value }
                ?: qualityFromLabel(label)
            val linkType = if (stream.type?.equals("mp4", true) == true) {
                ExtractorLinkType.VIDEO
            } else {
                ExtractorLinkType.M3U8
            }
            pushLink(label, url, quality, linkType, callback)
        }
        parsed.subtitles.orEmpty().forEach { pushSubtitle(it, subtitleCallback) }
        return parsed.streams.orEmpty().isNotEmpty()
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
        val token = fetchToken(type, tmdbId, seasonNum, episodeNum) ?: return false

        val bootPath = if (type == "tv") {
            "/api/boot/tv/$tmdbId/$seasonNum/$episodeNum?type=tv&id=$tmdbId&season=$seasonNum&episode=$episodeNum&source=0&shell=1&token=$token"
        } else {
            "/api/boot/movie/$tmdbId?type=movie&id=$tmdbId&source=0&shell=1&token=$token"
        }
        val bootResponse = VidriftClient.get(
            VidriftClient.EMBED + bootPath,
            mapOf("x-embed-parent" to parent)
        ) ?: return false
        if (bootResponse.code != 200) return false
        val boot = try {
            parseJson<BootResp>(bootResponse.text)
        } catch (e: Exception) {
            Log.d(TAG, "boot: ${e.message}")
            return false
        }
        val meta = boot.meta ?: return false
        val emitted = mutableSetOf<String>()

        playableUrl(meta.bigbangUrl)?.let {
            pushLink("BigBang", it, Qualities.Unknown.value, ExtractorLinkType.M3U8, callback)
            emitted.add("bigbang")
        }
        playableUrl(meta.vexonUrl)?.let {
            pushLink("Vexon", it, Qualities.Unknown.value, ExtractorLinkType.M3U8, callback)
            emitted.add("vexon")
        }
        playableUrl(meta.evionUrl)?.let {
            pushLink("Evion", it, Qualities.Unknown.value, ExtractorLinkType.M3U8, callback)
            emitted.add("evion")
        }
        playableUrl(meta.selfhostUrl)?.let { url ->
            val isMp4 = meta.selfhostKind?.contains("mp4", ignoreCase = true) == true
            val linkType = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            pushLink("Selfhost", url, Qualities.Unknown.value, linkType, callback)
            emitted.add("selfhost")
        }
        meta.orionStreams.orEmpty().forEach { stream ->
            val url = playableUrl(stream.url) ?: playableUrl(stream.proxyUrl) ?: return@forEach
            if (seenUrls.putIfAbsent(url, true) != null) return@forEach
            val label = stream.name?.takeIf { it.isNotBlank() } ?: "Orion"
            pushLink(label, url, orionQuality(stream), ExtractorLinkType.M3U8, callback)
            emitted.add("orion")
            if (boot.shell?.subtitles.isNullOrEmpty()) {
                pushOrionSubtitles(stream.subtitleListUrl, subtitleCallback)
            }
        }

        meta.bigbangSubtitles.orEmpty().forEach { pushSubtitle(it, subtitleCallback) }
        boot.shell?.subtitles.orEmpty().forEach { pushSubtitle(it, subtitleCallback) }

        providerCascade(meta).forEach { provider ->
            if (fetchProvider(provider, type, tmdbId, seasonNum, episodeNum, token, subtitleCallback, callback)) {
                emitted.add(provider)
            }
        }

        return emitted.isNotEmpty()
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
