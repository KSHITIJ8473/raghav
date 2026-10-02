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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class RaghavAniChan : MainAPI() {
    override var mainUrl = "https://anichan.to"
    override var name = "AniChan"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    suspend fun warm() {
        RaghavAniChanWeb.refreshDomain()
        mainUrl = RaghavAniChanWeb.url()
        try { RaghavAniChanWeb.warmPage() } catch (_: Exception) {}
    }

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (anilistId <= 0) return false
        RaghavAniChanWeb.refreshDomain()
        mainUrl = RaghavAniChanWeb.url()
        val category = if (isDub) "dub" else "sub"

        val raw = RaghavAniChanWeb.fetchServers(anilistId, episode, category) ?: return false
        val servers = try {
            parseJson<ServersEnvelope>(raw).servers ?: emptyList()
        } catch (e: Exception) {
            Log.e("RaghavAniChan", "bad servers payload: ${e.message}")
            emptyList()
        }
        if (servers.isEmpty()) return false

        val linkHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/"
        )
        val seenSubs = HashSet<String>()
        val found = AtomicBoolean(false)

        // direct streams go out first, embeds in parallel so a dead host cannot stall the list
        val embeds = ArrayList<Server>()
        for (server in servers) {
            if (server.type.equals("embed", true) && !server.embed.isNullOrBlank()) {
                embeds.add(server)
                continue
            }
            val stream = server.stream?.takeIf { it.startsWith("http") }
                ?: server.stream?.takeIf { it.startsWith("/") }?.let { "$mainUrl$it" }
                ?: continue
            callback.invoke(
                newExtractorLink(name, serverLabel(server, isDub), stream, type = ExtractorLinkType.M3U8) {
                    this.headers = linkHeaders
                }
            )
            found.set(true)

            for (sub in server.subtitles.orEmpty()) {
                val url = sub.url?.takeIf { it.startsWith("http") } ?: continue
                val lang = sub.lang ?: "English"
                if (seenSubs.add(lang)) {
                    subtitleCallback.invoke(SubtitleFile(lang, url))
                }
            }
        }

        if (embeds.isNotEmpty()) {
            coroutineScope {
                for (server in embeds) {
                    launch {
                        if (resolveEmbed(server, anilistId, episode, category, isDub, subtitleCallback, callback)) {
                            found.set(true)
                        }
                    }
                }
            }
        }
        return found.get()
    }

    private suspend fun resolveEmbed(
        server: Server,
        anilistId: Int,
        episode: Int,
        category: String,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = server.embed ?: return false
        val label = serverLabel(server, isDub)

        if (embed.contains("vidhawk")) {
            val vidServer = Regex("[?&]server=([^&]+)").find(embed)?.groupValues?.get(1)
                ?: "kari"
            val embedAudio = Regex("/embed/ani/\\d+/\\d+/([a-z]+)/").find(embed)?.groupValues?.get(1)
                ?: category
            var any = false
            for (link in RaghavAniChanVidhawk.resolveAll(anilistId, episode, embedAudio, vidServer, mainUrl)) {
                callback.invoke(
                    newExtractorLink(name, "AniChan ${link.label}", link.src, type = ExtractorLinkType.M3U8) {
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$mainUrl/"
                        )
                    }
                )
                any = true
            }
            return any
        }

        if (server.name == "anihq" || embed.substringAfter("//").substringBefore("/").startsWith("voe.")) {
            return emitVoeMaster(embed, label, subtitleCallback, callback)
        }

        if (server.name == "kiwi") {
            val (file, kwikPage) = RaghavAniChanKiwi.resolve(embed) ?: return false
            callback.invoke(
                newExtractorLink(
                    name, label, file,
                    if (file.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to kwikPage
                    )
                }
            )
            return true
        }

        return try {
            loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
    }

    // voe hands out one master plus per quality variants, the master alone is enough
    private suspend fun emitVoeMaster(
        embed: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val captured = ArrayList<ExtractorLink>()
        try {
            loadExtractor(embed, "$mainUrl/", subtitleCallback) { captured.add(it) }
        } catch (e: Exception) {
            return false
        }
        val master = captured.firstOrNull { it.url.substringBefore('?').endsWith("master.m3u8") }
            ?: captured.firstOrNull { it.url.contains(".m3u8") }
            ?: return false
        callback.invoke(
            newExtractorLink(
                name, label, master.url,
                if (master.url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = master.referer
                this.headers = master.headers
            }
        )
        return true
    }

    private fun serverLabel(server: Server, isDub: Boolean): String {
        val raw = (server.label ?: server.name ?: "AniChan")
            .replace("★", "")
            .replace(Regex("\\s*⧉\\s*\\(ads\\)"), "")
            .trim()
        val hardsub = server.subType.equals("hard", true)
        return when {
            hardsub -> "AniChan $raw (Hardsub)"
            isDub -> "AniChan $raw (Dub)"
            else -> "AniChan $raw"
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

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

// vidhawk proxies third party streams, tickets come from a race endpoint then a play call
internal object RaghavAniChanVidhawk {

    private const val MAIN_URL = "https://vidhawk.buzz"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    private data class Race(
        @JsonProperty("ticket") val ticket: String? = null,
        @JsonProperty("servers") val servers: List<RaceServer>? = null
    )

    private data class RaceServer(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("ticket") val ticket: String? = null
    )

    private data class Play(
        @JsonProperty("tracks") val tracks: List<Track>? = null
    )

    private data class Track(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("src") val src: String? = null
    )

    class Link(val label: String, val src: String)

    suspend fun resolveAll(
        anilistId: Int,
        ep: Int,
        audio: String,
        server: String,
        parentUrl: String
    ): List<Link> {
        return try {
            val parentHost = java.net.URI(parentUrl).host ?: "anichan.to"
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$parentUrl/"
            )
            val raceUrl = "$MAIN_URL/api/stream/race?episode=$ep&audio=$audio&server=$server" +
                "&anilistId=$anilistId&parentHost=$parentHost"
            val raceResp = app.get(raceUrl, headers = headers, timeout = 12L)
            val race = mapper.readValue(raceResp.text, Race::class.java)

            val candidates = race.servers?.filter { !it.ticket.isNullOrBlank() }
                ?.ifEmpty { listOfNotNull(race.ticket?.let { t -> RaceServer(ticket = t) }) }
                ?: emptyList()

            val wanted = if (audio.equals("dub", true)) listOf("dub", "hin") else listOf("sub")
            val links = mutableListOf<Link>()
            for (candidate in candidates) {
                val playResp = try {
                    app.get(
                        "$MAIN_URL/api/play?t=${java.net.URLEncoder.encode(candidate.ticket!!, "UTF-8")}",
                        headers = headers,
                        timeout = 10L
                    )
                } catch (e: Exception) {
                    continue
                }
                val play = try {
                    mapper.readValue(playResp.text, Play::class.java)
                } catch (e: Exception) {
                    continue
                }
                for (track in play.tracks.orEmpty()) {
                    val id = track.id?.lowercase() ?: continue
                    if (id !in wanted) continue
                    val src = track.src?.takeIf { it.startsWith("http") } ?: continue
                    if (!streamAlive(src, headers)) continue
                    val suffix = if (id == "hin") " Hindi" else ""
                    val serverTag = candidate.label?.takeIf { it.isNotBlank() } ?: candidate.id.orEmpty()
                    links.add(Link("vidHawk ${serverTag}${suffix}".trim(), src))
                }
            }
            links
        } catch (e: Exception) {
            emptyList()
        }
    }

    // third party upstreams die for days, one ranged request filters dead mirrors
    private suspend fun streamAlive(url: String, headers: Map<String, String>): Boolean {
        return try {
            val resp = app.get(
                url,
                headers = headers + ("Range" to "bytes=0-1023"),
                timeout = 8L
            )
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}

// the download mirrors are pahe.nekostream pages that chain into a kwik file page
internal object RaghavAniChanKiwi {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val pageHeaders = mapOf("User-Agent" to USER_AGENT)

    private val workersUrlRegex = Regex("""const\s+url\s*=\s*"(https?://[^"]+)"""")
    private val fallbackUrlRegex = Regex(""""(https?://[^"]*workers\.dev[^"]*)"""")
    private val sourceRegex = Regex("""source\s*=\s*["']([^"']+)["']""")
    private val formActionRegex = Regex("""action="([^"]+)"""")
    private val formTokenRegex = Regex("""value="([^"]+)"""")

    // returns the playable file and the kwik page it came from, the page is the referer
    suspend fun resolve(paheUrl: String): Pair<String, String>? {
        val kwikUrl = kwikFileUrl(paheUrl) ?: return null
        return kwikSource(kwikUrl, paheUrl)
    }

    private suspend fun kwikFileUrl(paheUrl: String): String? {
        val page = try {
            app.get(paheUrl, headers = pageHeaders, timeout = 15L).text
        } catch (e: Exception) {
            return null
        }
        val base = workersUrlRegex.find(page)?.groupValues?.get(1)
            ?: fallbackUrlRegex.find(page)?.groupValues?.get(1)
            ?: return null
        val redirector = base.trimEnd('/') + "/" + paheUrl.trimEnd('/').substringAfterLast('/')
        return try {
            app.get(redirector, headers = pageHeaders, allowRedirects = false)
                .headers["location"]?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun kwikSource(kwikUrl: String, referer: String): Pair<String, String>? {
        val page = try {
            app.get(
                kwikUrl,
                headers = pageHeaders + ("Referer" to referer),
                timeout = 20L
            )
        } catch (e: Exception) {
            return null
        }
        val html = page.text

        val packed = try {
            org.jsoup.Jsoup.parse(html).selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        } catch (e: Exception) {
            null
        }
        val unpacked = packed?.let {
            try {
                com.lagradost.cloudstream3.utils.getAndUnpack(it)
            } catch (e: Exception) {
                null
            }
        }

        if (unpacked != null) {
            sourceRegex.find(unpacked)?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http")) return src to page.url
            }
            val action = formActionRegex.find(unpacked)?.groupValues?.get(1)
            val token = formTokenRegex.find(unpacked)?.groupValues?.get(1)
            if (action != null && token != null) {
                return postForFile(action, token, page.url)
            }
        }

        sourceRegex.find(html)?.groupValues?.get(1)?.let { src ->
            if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4"))) {
                return src to page.url
            }
        }
        return null
    }

    // kwik answers the token POST with a 302 to the file only after a few tries
    private suspend fun postForFile(action: String, token: String, pageUrl: String): Pair<String, String>? {
        repeat(10) {
            try {
                val res = app.post(
                    action,
                    headers = pageHeaders + ("Referer" to pageUrl),
                    data = mapOf("_token" to token),
                    allowRedirects = false
                )
                if (res.code == 302) {
                    res.headers["location"]?.takeIf { it.startsWith("http") }?.let { return it to pageUrl }
                }
            } catch (e: Exception) {
            }
        }
        return null
    }
}
