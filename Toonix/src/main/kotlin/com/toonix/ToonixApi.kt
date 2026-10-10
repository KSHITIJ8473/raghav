package com.toonix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonixCard(
    val slug: String = "",
    val href: String = "",
    val poster: String? = null,
    val title: String = "",
    val year: Int? = null,
    val type: String = "SHOW",
    val category: String = "CARTOON",
    val meta: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonixRailEpisode(
    val number: Int? = null,
    val title: String? = null,
    val image: String? = null,
    val duration: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToonixRailSeason(
    val number: Int? = null,
    val title: String? = null,
    val episodes: List<ToonixRailEpisode> = emptyList()
)

data class ToonixShowInfo(
    val title: String,
    val year: Int?,
    val poster: String?,
    val synopsis: String?,
    val genres: List<String>,
    val episodeRefs: List<Pair<Int, Int>>,
    val seasonHrefs: List<Pair<Int, String>>
)

data class ToonixMovieInfo(
    val title: String,
    val year: Int?,
    val poster: String?,
    val synopsis: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?
)

data class ToonixVariant(val url: String, val width: Int?, val height: Int?)

class ToonixMaster(
    val isMaster: Boolean,
    val isMedia: Boolean,
    val isBusy: Boolean,
    val isDead: Boolean,
    val variants: List<ToonixVariant>
)

class ToonixException(message: String) : Exception(message)

object ToonixApi {
    const val BASE = "https://toonix.bond"

    private val mapper = ObjectMapper()
    private val cfKiller by lazy { CloudflareKiller() }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val flightChunkRegex = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
    private val sectionRegex = Regex(""""title":"([^"]+)","viewAllHref"""")
    private val slugHrefRegex = Regex(""""slug":"([^"]+)","href":"([^"]+)"""")
    private val fastSrcRegex = Regex(""""fastSrc":(?:"([^"]+)"|null)""")
    private val legacySrcRegex = Regex(""""legacySrc":(?:"([^"]+)"|null)""")
    private val yearShowRegex = Regex(""""children":\["\(",(\d{4}),"\)"\]""")
    private val yearMovieRegex = Regex(""""children":(\d{4})\}""")
    private val posterRegex = Regex(""""src":"([^"]+)","alt":"[^"]*","className":"aspect-\[2/3\][^"]*"""")
    private val synopsisRegex = Regex(""""className":"[^"]*leading-relaxed[^"]*","children":"([^"]{20,})"""")
    private val genreRegex = Regex("""\["\$","span","([^"]+)",\{"className":"rounded-md""")
    private val episodeRefsRegex = Regex(""""episodeRefs":\[(.*?)]""")
    private val runtimeRegex = Regex(""""children":"(\d+) min"""")
    private val titleTagRegex = Regex("""<title>(.*?)</title>""")

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun looksLikeChallenge(text: String): Boolean =
        text.contains("Just a moment", ignoreCase = true) ||
            text.contains("cf-browser-verification") ||
            text.contains("challenge-platform", ignoreCase = true) ||
            text.contains("Attention Required", ignoreCase = true)

    private suspend fun httpGet(url: String, useCf: Boolean = false): NiceResponse {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val resp = app.get(
                    url,
                    headers = headers(),
                    interceptor = if (useCf || attempt > 0) cfKiller else null
                )
                if (resp.code == 200) {
                    if (looksLikeChallenge(resp.text)) {
                        throw ToonixException("cloudflare challenge on $url")
                    }
                    return resp
                }
                if (resp.code == 404) return resp
                lastError = ToonixException("http ${resp.code} for $url")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: ToonixException("request failed for $url")
    }

    suspend fun fetchPage(url: String, checkTitle: Boolean = true): Pair<String, String> {
        val resp = httpGet(url)
        val html = resp.text
        if (html.isBlank()) throw ToonixException("empty page for $url")
        if (html.length < 500 && looksLikeChallenge(html)) {
            throw ToonixException("blocked by Cloudflare, retry in a minute")
        }
        val t = titleTagRegex.find(html)?.groupValues?.get(1).orEmpty()
        if (t.startsWith("Not found") || (checkTitle && t == "Toonix")) {
            throw ToonixException("this title is no longer on the site")
        }
        return html to parseFlight(html)
    }

    fun parseFlight(html: String): String {
        val sb = StringBuilder()
        for (m in flightChunkRegex.findAll(html)) {
            val escaped = m.groupValues[1]
            sb.append(try {
                mapper.readValue("\"$escaped\"", String::class.java)
            } catch (e: Exception) {
                unescapeFallback(escaped)
            })
        }
        return sb.toString()
    }

    private fun unescapeFallback(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    '/' -> { out.append('/'); i += 2 }
                    'u' -> {
                        if (i + 6 <= s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar()); i += 6
                            } else {
                                out.append(c); i++
                            }
                        } else {
                            out.append(c); i++
                        }
                    }
                    else -> { out.append(c); i++ }
                }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    fun parseCards(flight: String): List<ToonixCard> {
        val out = mutableListOf<ToonixCard>()
        val seen = mutableSetOf<String>()
        for (m in slugHrefRegex.findAll(flight)) {
            val href = m.groupValues[2]
            if (!href.startsWith("/show/") && !href.startsWith("/title/")) continue
            if (!seen.add(href)) continue
            val window = flight.substring(m.range.first, minOf(m.range.first + 700, flight.length))
            val poster = Regex(""""poster":"([^"]+)"""").find(window)?.groupValues?.get(1) ?: continue
            val title = Regex(""""title":"([^"]+)"""").find(window)?.groupValues?.get(1) ?: continue
            val type = Regex(""""type":"(SHOW|MOVIE)"""").find(window)?.groupValues?.get(1) ?: continue
            val yearRaw = Regex(""""year":("[^"]*"|\d+)""").find(window)?.groupValues?.get(1)
            val category = Regex(""""category":"(CARTOON|ANIME)"""").find(window)?.groupValues?.get(1)
            val meta = Regex(""""meta":"([^"]*)"""").find(window)?.groupValues?.get(1)
            val year = when {
                yearRaw == null -> null
                yearRaw.startsWith("\"") -> yearRaw.trim('"').toIntOrNull()
                else -> yearRaw.toIntOrNull()
            }
            out.add(
                ToonixCard(
                    slug = m.groupValues[1],
                    href = href,
                    poster = poster,
                    title = title,
                    year = year,
                    type = type,
                    category = category ?: "CARTOON",
                    meta = meta
                )
            )
        }
        return out
    }

    fun sectionCards(flight: String, sectionTitle: String): List<ToonixCard> {
        val markers = sectionRegex.findAll(flight).toList()
        val idx = markers.indexOfFirst { it.groupValues[1] == sectionTitle }
        if (idx < 0) return emptyList()
        val from = markers[idx].range.first
        val to = markers.getOrNull(idx + 1)?.range?.first ?: flight.length
        return parseCards(flight.substring(from, to))
    }

    fun parseSeasonsRail(flight: String): List<ToonixRailSeason> {
        val needle = "\"seasons\":["
        val start = flight.indexOf(needle)
        if (start < 0) return emptyList()
        var i = start + needle.length - 1
        var depth = 0
        var inString = false
        var escaped = false
        val from = i
        while (i < flight.length) {
            val c = flight[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                mapper.readValue(
                                    flight.substring(from, i + 1),
                                    Array<ToonixRailSeason>::class.java
                                ).toList()
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }
                }
            }
            i++
        }
        return emptyList()
    }

    private fun htmlTitle(html: String, fallback: String): String {
        val m = titleTagRegex.find(html) ?: return fallback
        var t = m.groupValues[1]
        if (t.endsWith(" · Toonix")) t = t.removeSuffix(" · Toonix")
        if (t.startsWith("Watch ")) t = t.removePrefix("Watch ")
        t = Jsoup.parse(t).text()
        return t.ifBlank { fallback }
    }

    fun parseShow(html: String, flight: String, slug: String): ToonixShowInfo {
        val title = htmlTitle(html, slug)
        val year = yearShowRegex.find(flight)?.groupValues?.get(1)?.toIntOrNull()
        val poster = posterRegex.find(flight)?.groupValues?.get(1)
        val synopsis = synopsisRegex.find(flight)?.groupValues?.get(1)
        val genres = genreRegex.findAll(flight).map { it.groupValues[1] }.distinct().take(8).toList()
        val refs = episodeRefsRegex.find(flight)?.groupValues?.get(1)
            ?.let { Regex(""""(\d+)/(\d+)"""").findAll(it).mapNotNull { r ->
                val s = r.groupValues[1].toIntOrNull()
                val e = r.groupValues[2].toIntOrNull()
                if (s != null && e != null) s to e else null
            }?.toList() } ?: emptyList()
        val seasonRegex = Regex(""""href":"(/show/${Regex.escape(slug)}/(\d+))".{0,900}?"alt":"([^"]+)\"""")
        val seasonHrefs = seasonRegex.findAll(flight).mapNotNull { m ->
            val num = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            num to m.groupValues[3]
        }.toList()
        return ToonixShowInfo(title, year, poster, synopsis, genres, refs, seasonHrefs)
    }

    fun parseMovie(html: String, flight: String, slug: String): ToonixMovieInfo {
        val title = htmlTitle(html, slug)
        val year = yearMovieRegex.find(flight)?.groupValues?.get(1)?.toIntOrNull()
        val poster = posterRegex.find(flight)?.groupValues?.get(1)
        val synopsis = synopsisRegex.find(flight)?.groupValues?.get(1)
        val genres = genreRegex.findAll(flight).map { it.groupValues[1] }.distinct().take(8).toList()
        val runtime = runtimeRegex.find(flight)?.groupValues?.get(1)?.toIntOrNull()
        return ToonixMovieInfo(title, year, poster, synopsis, genres, runtime)
    }

    fun parseStreamSrcs(flight: String): Pair<String?, String?> {
        val fast = fastSrcRegex.find(flight)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val legacy = legacySrcRegex.find(flight)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return fast to legacy
    }

    fun absolutize(url: String, base: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> BASE + url
        else -> base.substringBeforeLast('/') + "/" + url
    }

    suspend fun fetchMaster(url: String): ToonixMaster {
        return try {
            val resp = httpGet(url)
            val body = resp.text
            when {
                body.startsWith("#EXTM3U") && body.contains("#EXT-X-STREAM-INF") -> {
                    val variants = mutableListOf<ToonixVariant>()
                    var pendingW: Int? = null
                    var pendingH: Int? = null
                    for (line in body.lineSequence()) {
                        val t = line.trim()
                        if (t.startsWith("#EXT-X-STREAM-INF")) {
                            val res = Regex("""RESOLUTION=(\d+)x(\d+)""").find(t)
                            pendingW = res?.groupValues?.get(1)?.toIntOrNull()
                            pendingH = res?.groupValues?.get(2)?.toIntOrNull()
                        } else if (t.isNotEmpty() && !t.startsWith("#")) {
                            variants.add(ToonixVariant(absolutize(t, url), pendingW, pendingH))
                            pendingW = null
                            pendingH = null
                        }
                    }
                    ToonixMaster(isMaster = true, isMedia = false, isBusy = false, isDead = false, variants = variants)
                }
                body.startsWith("#EXTM3U") ->
                    ToonixMaster(isMaster = false, isMedia = true, isBusy = false, isDead = false, variants = emptyList())
                resp.code == 404 || body.contains("No source", ignoreCase = true) ->
                    ToonixMaster(isMaster = false, isMedia = false, isBusy = false, isDead = true, variants = emptyList())
                else ->
                    ToonixMaster(isMaster = false, isMedia = false, isBusy = true, isDead = false, variants = emptyList())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToonixMaster(isMaster = false, isMedia = false, isBusy = true, isDead = false, variants = emptyList())
        }
    }
}
