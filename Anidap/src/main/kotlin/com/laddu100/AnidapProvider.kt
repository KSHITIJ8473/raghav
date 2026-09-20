package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

class AnidapProvider : MainAPI() {
    override var mainUrl = "https://anidap.lol"
    override var name = "Anidap"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // validating every provider against its cdn is slow when most are down
    override val loadLinksTimeoutMs: Long? = 3 * 60_000L

    private val chadHost = "https://chad.anidap.lol"
    private val chadUrl = "$chadHost/rest/api"
    private val graphqlHost = "https://graphql.anidap.lol"
    private val TAG = "Anidap"

    // cdnx.aniwatchtv.site 403s requests without browser-style cors headers
    private fun proxyHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    private fun baseHeaders(): Map<String, String> = mapOf(
        "Referer" to "$mainUrl/home",
        "Accept" to "application/json",
    )

    private fun normalizeTip(tip: String?): String? {
        if (tip.isNullOrBlank()) return null
        return tip.replace("Hard sub", "Hardsub", ignoreCase = false)
            .replace("Soft sub", "Soft Sub", ignoreCase = false)
    }

    private fun isHardsubProvider(tip: String?): Boolean =
        tip?.contains("Hard", ignoreCase = true) == true

    private fun qualityFromHeight(height: Int?): Int {
        val h = height ?: return Qualities.Unknown.value
        return when {
            h >= 2160 -> Qualities.P2160.value
            h >= 1440 -> Qualities.P1440.value
            h >= 1080 -> Qualities.P1080.value
            h >= 720 -> Qualities.P720.value
            h >= 480 -> Qualities.P480.value
            h >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun parseQualityToken(qualityStr: String?): Int {
        if (qualityStr.isNullOrBlank() || qualityStr.equals("auto", ignoreCase = true)) {
            return Qualities.Unknown.value
        }
        val height = Regex("(\\d{3,4})").find(qualityStr)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return qualityFromHeight(height)
    }

    // chad wraps its arrays differently per endpoint, unwrap them all here
    private fun unwrapArray(root: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode {
        if (root.isArray) return root
        val d = root.path("data")
        if (d.isArray) return d
        if (d.isObject && d.path("data").isArray) return d.path("data")
        if (root.path("results").isArray) return root
        return root
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/anime/trending" to "Trending",
        "$graphqlHost/api/recent" to "Recently Added",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = FirebaseDomainHelper.getDomain("anidap") ?: mainUrl
        return try {
            when (request.name) {
                "Recently Added" -> recentsPage(page)
                else -> trendingPage()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage failed: ${e.message}")
            newHomePageResponse(emptyList<HomePageList>(), hasNext = false)
        }
    }

    private fun buildTrendingItem(node: com.fasterxml.jackson.databind.JsonNode): SearchResponse? {
        val id = node.path("id").asText("")
        if (id.isBlank()) return null
        val title = node.path("title").let { t ->
            t.path("userPreferred").asText("").ifBlank { null }
                ?: t.path("english").asText("").ifBlank { null }
                ?: t.path("romaji").asText("").ifBlank { null }
        } ?: return null
        val poster = node.path("image").asText("").ifBlank { null }
            ?: node.path("cover").asText("").ifBlank { null }
        return newAnimeSearchResponse(title, "$mainUrl|$id", TvType.Anime) {
            this.posterUrl = poster
            addDubStatus(dubExist = true, subExist = true)
        }
    }

    private suspend fun trendingPage(): HomePageResponse {
        val res = app.get("$mainUrl/api/anime/trending", headers = baseHeaders(), timeout = 30_000L)
        val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(res.text)
        var results = root.path("data").path("data").path("results")
        if (!results.isArray || results.size() == 0) results = root.path("data").path("results")
        if (!results.isArray || results.size() == 0) results = root.path("results")
        val list = if (results.isArray) {
            results.mapNotNull { buildTrendingItem(it) }
        } else emptyList()
        return newHomePageResponse(
            listOf(HomePageList("Trending", list, isHorizontalImages = true)),
            hasNext = false
        )
    }

    private suspend fun recentsPage(page: Int): HomePageResponse {
        val res = app.get(
            "$graphqlHost/api/recent?page=$page",
            headers = mapOf("Accept" to "application/json"),
            timeout = 30_000L
        )
        val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(res.text)
        val results = root.path("results")
        val list = ArrayList<SearchResponse>()
        if (results.isArray) {
            for (node in results) {
                if (node.path("isAdult").asBoolean(false)) continue
                val anilistId = node.path("anilistId").asText("")
                if (anilistId.isBlank() || anilistId == "null") continue
                val title = node.path("titleEnglish").asText("").ifBlank { null }
                    ?: node.path("titleRomaji").asText("").ifBlank { null }
                    ?: continue
                val poster = node.path("coverImage").let { c ->
                    c.path("extraLarge").asText("").ifBlank { null }
                        ?: c.path("large").asText("").ifBlank { null }
                }
                list.add(
                    newAnimeSearchResponse(title, "$mainUrl|$anilistId", TvType.Anime) {
                        this.posterUrl = poster
                        val isDub = node.path("isDub").asBoolean(false)
                        val isSub = node.path("isSub").asBoolean(false)
                        addDubStatus(dubExist = isDub, subExist = isSub)
                    }
                )
            }
        }
        val hasNext = root.path("hasNextPage").asBoolean(false)
        return newHomePageResponse(
            listOf(HomePageList("Recently Added", list, isHorizontalImages = true)),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = FirebaseDomainHelper.getDomain("anidap") ?: mainUrl
        if (query.length < 2) return emptyList()
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get(
                "$mainUrl/api/anime/search?q=$encoded",
                headers = baseHeaders(),
                timeout = 30_000L
            )
            val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(res.text)
            val results = root.path("results")
            if (!results.isArray) emptyList()
            else results.mapNotNull { buildTrendingItem(it) }
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        mainUrl = FirebaseDomainHelper.getDomain("anidap") ?: mainUrl
        val animeId = url.substringAfterLast("|").substringAfterLast("/").trim()
        if (animeId.isBlank()) return null

        return try {
            val detailRes = app.get(
                "$mainUrl/api/anime/$animeId",
                headers = baseHeaders(),
                timeout = 30_000L
            )
            val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(detailRes.text)
            val data = root.path("data").let { if (it.isObject && it.size() > 0) it else root }

            // the detail id is the slug chad expects (e.g. one-piece-p8k27)
            val slug = data.path("id").asText("").ifBlank { animeId }
            val titles = data.path("titles")
            val titleEnglish = data.path("titleEnglish").asText("").ifBlank { null }
            val titleRomaji = data.path("titleRomaji").asText("").ifBlank { null }
            val titleJa = titles.path("ja").asText("").ifBlank { null }
            val title = titles.path("en").asText("").ifBlank { null }
                ?: titleEnglish
                ?: titleRomaji
                ?: titleJa
                ?: "Unknown"

            val cover = data.path("coverImage")
            val poster = cover.path("extraLarge").asText("").ifBlank { null }
                ?: cover.path("large").asText("").ifBlank { null }
            val banner = data.path("bannerImage").asText("").ifBlank { null }
            val plot = data.path("description").asText("").takeIf { it.isNotBlank() }
            val year = data.path("seasonYear").asInt(0).takeIf { it > 0 }
            val duration = data.path("duration").asInt(0).takeIf { it > 0 }
            val genres = data.path("genres")
                .mapNotNull { g -> g.path("name").asText("").takeIf { it.isNotBlank() } }
            val format = data.path("format").asText(data.path("type").asText("")).uppercase()

            data class Ep(
                val number: Int,
                val name: String?,
                val thumb: String?,
                val description: String?,
                val isFiller: Boolean,
                val hasSub: Boolean?,
                val hasDub: Boolean?,
            )

            val episodes: List<Ep>? = try {
                val epsRes = cfAppGet(
                    "$chadUrl/episodes?id=$slug",
                    headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
                )
                if (epsRes.code == 200) {
                    val epsRoot = parseJson<com.fasterxml.jackson.databind.JsonNode>(epsRes.text)
                    val arr = unwrapArray(epsRoot)
                    if (arr.isArray) arr.mapNotNull { e ->
                        val num = e.path("number").asInt(-1)
                        if (num < 1) null else Ep(
                            number = num,
                            name = e.path("titles").let { t ->
                                t.path("en").asText("").ifBlank { null }
                                    ?: t.path("x-jat").asText("").ifBlank { null }
                                    ?: t.path("ja").asText("").ifBlank { null }
                            },
                            thumb = e.path("img").asText("").ifBlank { null },
                            description = e.path("description").asText("").takeIf { it.isNotBlank() },
                            isFiller = e.path("isFiller").asBoolean(false),
                            hasSub = if (e.has("hasSub")) e.path("hasSub").asBoolean() else null,
                            hasDub = if (e.has("hasDub")) e.path("hasDub").asBoolean() else null,
                        )
                    } else null
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "episodes fetch failed: ${e.message}")
                null
            }

            // sub/dub flags can be missing per episode, ep1 servers covers those
            var ep1HasSub: Boolean? = null
            var ep1HasDub: Boolean? = null
            if (episodes == null || episodes.any { it.hasSub == null || it.hasDub == null }) {
                try {
                    val serversRes = cfAppGet(
                        "$chadUrl/servers?id=$slug&epNum=1",
                        headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
                    )
                    if (serversRes.code == 200) {
                        val sRoot = parseJson<com.fasterxml.jackson.databind.JsonNode>(serversRes.text)
                        ep1HasSub = sRoot.path("subProviders").size() > 0
                        ep1HasDub = sRoot.path("dubProviders").size() > 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "servers probe failed: ${e.message}")
                }
            }

            val totalEps = episodes?.maxOfOrNull { it.number }
                ?: data.path("episodeCount").asInt(0).takeIf { it > 0 }
                ?: data.path("episodes").asInt(0).takeIf { it > 0 }
                ?: 0

            val epByNumber = episodes?.associateBy { it.number } ?: emptyMap()

            fun episodeList(dub: Boolean): List<Episode> {
                val numbers: List<Int> = if (episodes != null) {
                    episodes.map { it.number }.filter { n ->
                        val e = epByNumber[n]
                        if (e == null) true
                        else if (dub) e.hasDub ?: (ep1HasDub ?: false)
                        else e.hasSub ?: (ep1HasSub ?: true)
                    }
                } else if (totalEps > 0) {
                    val anyEps = if (dub) ep1HasDub ?: false else ep1HasSub ?: true
                    if (anyEps) (1..totalEps).toList() else emptyList()
                } else emptyList()

                return numbers.map { n ->
                    val info = epByNumber[n]
                    val filler = if (info?.isFiller == true) " (Filler)" else ""
                    newEpisode("$mainUrl|$slug|$n|${if (dub) "dub" else "sub"}") {
                        this.name = info?.name?.let { "$it$filler" } ?: "Episode $n$filler"
                        this.episode = n
                        this.posterUrl = info?.thumb
                        this.description = info?.description
                    }
                }
            }

            val subEpisodes = episodeList(dub = false)
            val dubEpisodes = episodeList(dub = true)

            if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
                return newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = banner
                    this.plot = plot
                    this.tags = genres
                    this.year = year
                    this.duration = duration
                }
            }

            // the app hides the sub/dub switcher on movie types, so dual audio
            // movies are typed as regular anime to keep both reachable
            val tvType = when {
                format == "MOVIE" && dubEpisodes.isNotEmpty() -> TvType.Anime
                format == "MOVIE" -> TvType.AnimeMovie
                format == "OVA" || format == "ONA" || format == "SPECIAL" -> TvType.OVA
                else -> TvType.Anime
            }

            return newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = duration
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "load failed: ${e.message}")
            null
        }
    }

    private data class ServerProvider(val id: String, val tip: String?)

    // providers differ per episode, cache per slug|ep
    private val serversCache = ConcurrentHashMap<String, Pair<Long, Map<String, List<ServerProvider>>>>()

    private suspend fun serversForEpisode(slug: String, epNum: String): Map<String, List<ServerProvider>> {
        val key = "$slug|$epNum"
        val cached = serversCache[key]
        if (cached != null && System.currentTimeMillis() - cached.first < 10 * 60_000L) {
            return cached.second
        }
        val out = try {
            val res = cfAppGet(
                "$chadUrl/servers?id=$slug&epNum=$epNum",
                headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
            )
            if (res.code == 200) {
                val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(res.text)
                fun parseList(field: String): List<ServerProvider> {
                    val arr = root.path(field)
                    if (!arr.isArray) return emptyList()
                    return arr.mapNotNull { node ->
                        when {
                            node.isTextual -> ServerProvider(node.asText(), null)
                            node.isObject -> {
                                val id = node.path("id").asText("")
                                if (id.isBlank()) null else ServerProvider(id, node.path("tip").asText(""))
                            }
                            else -> null
                        }
                    }
                }
                // chad never lists the site's own adp server, the web client
                // puts it first in every list - do the same or those streams
                // never get requested
                fun withAdp(list: List<ServerProvider>): List<ServerProvider> =
                    if (list.any { it.id == "adp" }) list
                    else listOf(ServerProvider("adp", null)) + list
                mapOf(
                    "sub" to withAdp(parseList("subProviders")),
                    "dub" to withAdp(parseList("dubProviders")),
                )
            } else emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "servers fetch failed: ${e.message}")
            emptyMap()
        }
        serversCache[key] = System.currentTimeMillis() to out
        return out
    }

    private data class SourcesPayload(
        val sources: List<Triple<String, String?, String?>>,
        val tracks: List<Triple<String, String, String>>,
        val headers: Map<String, String>,
    )

    private suspend fun fetchSources(
        slug: String, epNum: String, type: String, providerId: String
    ): SourcesPayload? {
        return try {
            val res = cfAppGet(
                "$chadUrl/sources?id=$slug&epNum=$epNum&type=$type&providerId=$providerId",
                headers = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")
            )
            if (res.code != 200) return null
            val root = parseJson<com.fasterxml.jackson.databind.JsonNode>(res.text)
            val node = if (root.isObject && root.path("data").isObject &&
                root.path("data").has("sources")
            ) root.path("data") else root

            val srcArr = node.path("sources")
            if (!srcArr.isArray || srcArr.size() == 0) return null

            val sources = srcArr.mapNotNull { s ->
                val u = s.path("url").asText("")
                if (u.isBlank()) null else Triple(
                    u,
                    s.path("quality").asText("").ifBlank { null },
                    s.path("type").asText("").ifBlank { null },
                )
            }
            if (sources.isEmpty()) return null

            val tracks = node.path("tracks").let { t ->
                if (!t.isArray) node.path("subtitles") else t
            }
            val trackList = if (tracks.isArray) tracks.mapNotNull { t ->
                val u = t.path("url").asText("")
                if (u.isBlank()) null else Triple(
                    u,
                    t.path("label").asText(t.path("lang").asText("Subtitle")),
                    t.path("kind").asText("captions"),
                )
            } else emptyList()

            val headers = HashMap<String, String>()
            val headersNode = node.path("headers")
            if (headersNode.isObject) {
                headersNode.fieldNames().forEach { k ->
                    val value = headersNode.path(k).asText("")
                    if (value.isNotBlank()) headers[k] = value
                }
            }

            SourcesPayload(sources, trackList, headers)
        } catch (e: Exception) {
            Log.e(TAG, "sources $providerId failed: ${e.message}")
            null
        }
    }

    // walks master -> best variant -> first segment so dead hosts drop out
    // before the player ever sees them
    private fun validateHls(
        masterUrl: String,
        headers: Map<String, String>,
    ): List<AnidapUrl.Variant>? {
        val master = AnidapUrl.fetchText(masterUrl, headers) ?: return null
        if (!master.contains("#EXTM3U")) return null
        val variants = AnidapUrl.parseMaster(master, masterUrl)

        val probeTarget = variants.maxByOrNull { it.quality ?: 0 }?.url ?: masterUrl
        val playlist = AnidapUrl.fetchText(probeTarget, headers) ?: return null
        if (!playlist.contains("#EXTM3U")) return null

        val seg = AnidapUrl.firstSegment(playlist, probeTarget) ?: return null
        val bytes = AnidapUrl.fetchBytes(seg, headers) ?: return null
        if (!AnidapUrl.isVideoBytes(bytes)) return null

        return if (variants.isEmpty()) listOf(AnidapUrl.Variant(null, masterUrl)) else variants
    }

    private suspend fun emitSubtitles(
        payload: SourcesPayload,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        for ((rawUrl, label, kind) in payload.tracks) {
            val l = label.lowercase()
            if (kind.lowercase() == "thumbnails" || l == "thumbnails" || l == "thumbnail") continue
            var url = rawUrl
                .replace("https:///", "https://")
                .replace("http:///", "http://")
            if (!url.startsWith("http")) continue
            if (!seen.add(url)) continue
            val subHeaders = when {
                url.contains("lostproject.club") || url.contains("megaplay.buzz") ||
                    url.contains("vyrnex.top") ->
                    mapOf(
                        "Origin" to "https://megaplay.buzz",
                        "Referer" to "https://megaplay.buzz/",
                    )
                else -> payload.headers
            }
            try {
                subtitleCallback.invoke(newSubtitleFile(label, url) {
                    this.headers = subHeaders
                })
            } catch (e: Exception) {
                Log.e(TAG, "subtitle emit failed: ${e.message}")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        mainUrl = FirebaseDomainHelper.getDomain("anidap") ?: mainUrl
        // data: "<mainUrl>|<slug>|<epNum>|<type>"
        val rawParts = data.trim().split("|")
        val parts = if (rawParts.firstOrNull()?.startsWith("http") == true) rawParts.drop(1) else rawParts
        if (parts.size < 3) {
            Log.e(TAG, "loadLinks: invalid data")
            return false
        }
        val slug = parts[0]
        val epNum = parts[1]
        val type = if (parts[2] == "dub") "dub" else "sub"

        val providers = serversForEpisode(slug, epNum)[type] ?: emptyList()
        if (providers.isEmpty()) {
            Log.e(TAG, "loadLinks: no providers for $slug ep$epNum $type")
            return false
        }

        val found = java.util.concurrent.atomic.AtomicBoolean(false)
        val subSeen = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            providers.map { provider ->
                async {
                    try {
                        val payload = fetchSources(slug, epNum, type, provider.id) ?: return@async
                        val tip = normalizeTip(provider.tip)
                        val hardsub = isHardsubProvider(tip)
                        val referer = payload.headers["Referer"] ?: payload.headers["referer"]

                        emitSubtitles(payload, subSeen, subtitleCallback)

                        for ((srcUrl, qualityToken, srcType) in payload.sources) {
                            val proxyUrl = AnidapUrl.transform(srcUrl, provider.id, referer)
                            val label = buildString {
                                append(name)
                                append(" - ")
                                append(provider.id)
                                if (hardsub) append(" (Hardsub)")
                            }

                            when {
                                AnidapUrl.looksLikeDash(srcUrl, srcType) -> {
                                    val bytes = AnidapUrl.fetchBytes(srcUrl, payload.headers, 1023)
                                    if (bytes != null && bytes.size > 512) {
                                        callback.invoke(
                                            newExtractorLink(label, label, srcUrl, type = ExtractorLinkType.VIDEO) {
                                                this.headers = payload.headers
                                                this.quality = parseQualityToken(qualityToken)
                                            }
                                        )
                                        found.set(true)
                                    }
                                }

                                AnidapUrl.looksLikeHls(srcUrl, srcType) -> {
                                    // native apps can send the provider headers a browser cannot,
                                    // so try the raw url first and only then the site proxy
                                    var variants = validateHls(srcUrl, payload.headers)
                                    var headers = payload.headers
                                    if (variants == null && proxyUrl != srcUrl) {
                                        val proxied = validateHls(proxyUrl, proxyHeaders())
                                        if (proxied != null) {
                                            variants = proxied
                                            headers = proxyHeaders()
                                        }
                                    }
                                    if (variants == null) continue

                                    for (v in variants) {
                                        val q = v.quality ?: parseQualityToken(qualityToken).takeIf {
                                            it != Qualities.Unknown.value
                                        }
                                        val nameWithQ = if (q != null) "$label ${q}p" else label
                                        callback.invoke(
                                            newExtractorLink(nameWithQ, nameWithQ, v.url, type = ExtractorLinkType.M3U8) {
                                                this.headers = headers
                                                this.quality = qualityFromHeight(q)
                                            }
                                        )
                                    }
                                    found.set(true)
                                }

                                AnidapUrl.looksLikeMp4(srcUrl, srcType) -> {
                                    val bytes = AnidapUrl.fetchBytes(srcUrl, payload.headers, 2047)
                                    if (AnidapUrl.isVideoBytes(bytes)) {
                                        callback.invoke(
                                            newExtractorLink(label, label, srcUrl, type = ExtractorLinkType.VIDEO) {
                                                this.headers = payload.headers
                                                this.quality = parseQualityToken(qualityToken)
                                            }
                                        )
                                        found.set(true)
                                    } else if (proxyUrl != srcUrl) {
                                        val bytes2 = AnidapUrl.fetchBytes(proxyUrl, proxyHeaders(), 2047)
                                        if (AnidapUrl.isVideoBytes(bytes2)) {
                                            callback.invoke(
                                                newExtractorLink(label, label, proxyUrl, type = ExtractorLinkType.VIDEO) {
                                                    this.headers = proxyHeaders()
                                                    this.quality = parseQualityToken(qualityToken)
                                                }
                                            )
                                            found.set(true)
                                        }
                                    }
                                }

                                else -> {
                                    // embed page or something unknown - let the built-in
                                    // extractors try, fall back to a direct probe
                                    val refererForExtractor = payload.headers["Referer"]
                                        ?: payload.headers["referer"] ?: "$mainUrl/"
                                    val loaded = try {
                                        loadExtractor(srcUrl, refererForExtractor, subtitleCallback, callback)
                                    } catch (e: Exception) {
                                        false
                                    }
                                    if (loaded) {
                                        found.set(true)
                                    } else {
                                        val bytes = AnidapUrl.fetchBytes(srcUrl, payload.headers, 2047)
                                        if (AnidapUrl.isVideoBytes(bytes)) {
                                            callback.invoke(
                                                newExtractorLink(label, label, srcUrl, type = ExtractorLinkType.VIDEO) {
                                                    this.headers = payload.headers
                                                    this.quality = parseQualityToken(qualityToken)
                                                }
                                            )
                                            found.set(true)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadLinks ${provider.id} failed: ${e.message}")
                    }
                }
            }.forEach { it.await() }
        }

        return found.get()
    }
}
