package com.justplay

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uSearchPage(
    val ok: Boolean? = false,
    val items: List<Play4uSearchItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uSearchItem(
    val title: String? = null,
    val year: String? = null,
    val media_type: String? = null,
    val watch: String? = null,
    val imdb_id: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uPlayPayload(
    val ok: Boolean? = false,
    val movie: Play4uMovieMeta? = null,
    val streams: List<Play4uPlayStream> = emptyList()
)

// only real movie pages carry the movie block, a watch page that plays a
// series episode ships the item block alone
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uMovieMeta(
    val title: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uPlayStream(
    val label: String? = null,
    val m3u8_url: String? = null,
    val m3u8_path: String? = null,
    val domain_name: String? = null,
    val languages: List<Play4uAudioLang>? = null,
    val qualities: List<Play4uQuality>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uAudioLang(
    val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uQuality(
    val slug: String? = null,
    val height: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uSeriesPayload(
    val seasons: Map<String, Play4uSeasonInfo> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uSeasonInfo(
    val number: Int? = null,
    val episodes: Map<String, Play4uEpisodeInfo> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Play4uEpisodeInfo(
    val number: Int? = null,
    val hls: String? = null,
    val languages: List<Play4uAudioLang>? = null,
    val qualities: List<Play4uQuality>? = null
)

internal object Play4uSite {

    private const val TAG = "Play4uSite"
    private const val DEFAULT_MAIN = "https://play4u.org"
    private const val SEAL_PREFIX = "pa!~"
    private const val DEFAULT_SEAL_KEY = "pa4u-stream-seal-v1"

    @Volatile
    private var domainOverride: String? = null

    private suspend fun base(): String {
        domainOverride?.let { return it }
        val remote = FirebaseDomainHelper.getDomain("justplay_play4u")
            ?: FirebaseDomainHelper.getDomain("play4u")
        if (remote != null) {
            val trimmed = remote.trimEnd('/')
            domainOverride = trimmed
            return trimmed
        }
        return DEFAULT_MAIN
    }

    private fun apiHeaders(base: String): Map<String, String> = mapOf(
        "User-Agent" to PLAY_UA,
        "Accept" to "application/json, text/html;q=0.9, */*;q=0.8",
        "Referer" to "$base/"
    )

    // the stream hosts serve an html decoy unless the site origin rides along
    private fun playHeaders(base: String): Map<String, String> = mapOf(
        "Referer" to "$base/",
        "Origin" to base
    )

    // the site helper swaps the url safe alphabet for the standard one before
    // atob, so the decode runs on the standard table too
    private fun unseal(value: String?, key: String): String {
        if (value.isNullOrBlank()) return ""
        if (!value.startsWith(SEAL_PREFIX)) return value
        var b64 = value.substring(SEAL_PREFIX.length)
            .replace("-", "+")
            .replace("_", "/")
        when (b64.length % 4) {
            1 -> return ""
            2 -> b64 += "=="
            3 -> b64 += "="
        }
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            val kb = key.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(raw.size)
            for (i in raw.indices) {
                out.append(((raw[i].toInt() and 0xFF) xor (kb[i % kb.size].toInt() and 0xFF)).toChar())
            }
            val text = out.toString()
            if (text.isEmpty() || text.any { it < ' ' || it > '~' }) "" else text
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "unseal: ${e.message}")
            ""
        }
    }

    private val sealKeyPattern = Regex("""window\.__PA_SEAL_KEY__\s*=\s*"([^"]*)"""")

    private fun sealKeyOf(html: String): String =
        sealKeyPattern.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_SEAL_KEY

    private fun extractVar(html: String, name: String): String? {
        val marker = "window.$name = "
        val at = html.indexOf(marker)
        if (at < 0) return null
        var start = html.indexOf('=', at) + 1
        while (start < html.length && html[start].isWhitespace()) start++
        if (start >= html.length || html[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '"' -> inString = !inString
                '{', '[' -> if (!inString) depth++
                '}', ']' -> if (!inString) {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun playVar(html: String): Play4uPlayPayload? =
        extractVar(html, "__PA_PLAY__")?.let {
            try {
                parseJson<Play4uPlayPayload>(it)
            } catch (e: Exception) {
                Log.d(TAG, "play var: ${e.message}")
                null
            }
        }

    private fun seriesVar(html: String): Play4uSeriesPayload? =
        extractVar(html, "__PA_SERIES__")?.let {
            try {
                parseJson<Play4uSeriesPayload>(it)
            } catch (e: Exception) {
                Log.d(TAG, "series var: ${e.message}")
                null
            }
        }

    private suspend fun fetchPage(url: String, base: String): String? {
        return try {
            val res = app.get(url, headers = apiHeaders(base), timeout = 25L)
            if (res.isSuccessful) res.text else null
        } catch (e: Exception) {
            Log.d(TAG, "page $url: ${e.message}")
            null
        }
    }

    private suspend fun searchItems(query: String, base: String): List<Play4uSearchItem> {
        val url = "$base/api/search.php?q=${URLEncoder.encode(query, "UTF-8")}&page=1&per_page=40"
        return try {
            val res = app.get(url, headers = apiHeaders(base), timeout = 20L)
            if (!res.isSuccessful) return emptyList()
            parseJson<Play4uSearchPage>(res.text).items
        } catch (e: Exception) {
            Log.d(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    private val titleYearTail = Regex("""\s*\((19|20)\d{2}\)\s*$""")

    private fun cleanTitle(title: String?): String =
        (title ?: "").replace(titleYearTail, "").trim()

    private fun yearOk(item: Play4uSearchItem, want: Int?): Boolean {
        if (want == null) return true
        val year = item.year?.take(4)?.toIntOrNull() ?: return true
        return kotlin.math.abs(year - want) <= 1
    }

    private fun typeOk(item: Play4uSearchItem, wantSeries: Boolean): Boolean = when (item.media_type) {
        "series" -> wantSeries
        "movie" -> !wantSeries
        else -> true
    }

    private fun isMoviePage(html: String): Boolean {
        val play = playVar(html) ?: return false
        return play.ok == true && play.movie != null
    }

    private fun isSeriesPage(html: String): Boolean = seriesVar(html) != null

    private suspend fun resolvePage(
        res: PlayLinkData,
        base: String
    ): String? {
        val wantSeries = res.season != null
        // the watch urls are imdb based, a tmdb imdb id skips the search hop
        val imdb = res.imdbId?.takeIf { it.startsWith("tt") && it.length > 2 }
        if (imdb != null) {
            val direct = if (wantSeries) "$base/series/$imdb" else "$base/watch/$imdb"
            val html = fetchPage(direct, base)
            if (html != null) {
                val payload = if (wantSeries) isSeriesPage(html) else isMoviePage(html)
                if (payload) return html
            }
        }

        val title = res.title?.takeIf { it.isNotBlank() } ?: return null
        for (query in PlayNet.searchQueries(res)) {
            val hits = searchItems(query, base)
            if (hits.isEmpty()) continue
            val wanted = PlayNet.normalizeTitle(title)
            val candidates = hits
                .filter { typeOk(it, wantSeries) }
                .filter { PlayNet.titleMatches(cleanTitle(it.title), title) }
            val pool = candidates.filter { yearOk(it, res.matchYear) }.ifEmpty { candidates }
            val ranked = pool.sortedByDescending {
                PlayNet.normalizeTitle(cleanTitle(it.title)) == wanted
            }
            for (item in ranked.take(3)) {
                val watch = item.watch?.takeIf { it.startsWith("/") } ?: continue
                val html = fetchPage("$base$watch", base) ?: continue
                val payload = if (wantSeries) isSeriesPage(html) else isMoviePage(html)
                if (payload) return html
            }
        }
        return null
    }

    private fun maxHeight(list: List<Play4uQuality>?): Int? {
        return list.orEmpty()
            .mapNotNull { q ->
                q.height ?: q.slug?.removeSuffix("p")?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            }
            .maxOrNull()
    }

    private fun audioLabel(list: List<Play4uAudioLang>?): String? =
        list.orEmpty()
            .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .joinToString("/")
            .takeIf { it.isNotBlank() }

    private fun usableLink(raw: String, base: String): String? = when {
        raw.startsWith("https://") || raw.startsWith("http://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$base$raw"
        else -> null
    }

    private suspend fun emit(
        server: String,
        info: String,
        quality: Int?,
        url: String,
        base: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val name = PlayLabels.buildLabel("play4u", server, info)
        callback.invoke(
            newExtractorLink("[Play4u]", name, url, ExtractorLinkType.M3U8) {
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = playHeaders(base)
            }
        )
    }

    private suspend fun emitStreams(
        streams: List<Play4uPlayStream>,
        sealKey: String,
        base: String,
        callback: (ExtractorLink) -> Unit
    ) {
        for (stream in streams) {
            // a page that ships the plain path plus its cdn domain still
            // resolves without the sealed url
            val link = usableLink(unseal(stream.m3u8_url, sealKey), base)
                ?: stream.let {
                    val host = it.domain_name?.takeIf { d -> d.isNotBlank() }
                    val path = it.m3u8_path?.takeIf { p -> p.startsWith("/") }
                    if (host != null && path != null) "https://$host$path" else null
                }
            if (link == null) continue
            val server = stream.label?.takeIf { it.isNotBlank() } ?: "Play4u"
            val info = listOfNotNull(
                maxHeight(stream.qualities)?.let { "${it}p" },
                audioLabel(stream.languages)
            ).joinToString(" ")
            emit(server, info, maxHeight(stream.qualities), link, base, callback)
        }
    }

    private suspend fun emitEpisode(
        series: Play4uSeriesPayload,
        sealKey: String,
        res: PlayLinkData,
        base: String,
        callback: (ExtractorLink) -> Unit
    ) {
        // some shows sit in one season on the site while tmdb splits them,
        // a single season catalog entry is used for any requested number
        val season = series.seasons.entries.firstOrNull { (key, s) ->
            s.number == res.season || key.toIntOrNull() == res.season
        }?.value ?: series.seasons.values.singleOrNull()

        val episode = season?.episodes?.values?.firstOrNull { it.number == res.episode }
        val hls = episode?.hls ?: return
        val link = usableLink(unseal(hls, sealKey), base) ?: return
        val quality = maxHeight(episode.qualities)
        val info = listOfNotNull(
            quality?.let { "${it}p" },
            audioLabel(episode.languages)
        ).joinToString(" ")
        emit("Play4u", info, quality, link, base, callback)
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val base = base()
            val html = resolvePage(res, base) ?: return
            val sealKey = sealKeyOf(html)
            if (res.season != null) {
                val series = seriesVar(html) ?: return
                emitEpisode(series, sealKey, res, base, callback)
            } else {
                val play = playVar(html) ?: return
                if (play.ok != true || play.movie == null) return
                emitStreams(play.streams, sealKey, base, callback)
            }
        } catch (e: Exception) {
            Log.d(TAG, "invoke: ${e.message}")
        }
    }
}
