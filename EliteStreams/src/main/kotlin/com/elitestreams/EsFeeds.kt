package com.elitestreams

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

object EsFeeds {

    private const val PLUGLIST = "https://premiumplugx.com/VIP/pluglist.php"
    private const val HOTSTAR = "https://premiumplugx.com/htt/hot.php?playlist=1"
    private const val SONY_JSON = "https://api.npoint.io/5122554ee81206923fa7"
    private const val SLIV_EVENTS =
        "https://raw.githubusercontent.com/drmlive/sliv-live-events/refs/heads/main/sonyliv.m3u"
    private const val FANCODE = "https://premiumplugx.me/fancode/fan.php"
    private const val FANCODE_BACKUP =
        "https://raw.githubusercontent.com/drmlive/fancode-live-events/refs/heads/main/fancode.m3u"
    private const val BIGGBOSS = "https://biggbossstreamx.livenoww.workers.dev/playlist.m3u"

    private const val CACHE_TTL = 5 * 60 * 1000L

    // a 530 body parses to zero channels, holding that for the full ttl
    // would keep a recovered server hidden, so failures retry sooner
    private const val FAILED_TTL = 45 * 1000L
    private const val HOTSTAR_WRITE_TTL = 10 * 60 * 1000L

    // a cookie close to its expiry can die mid stream, only trust ones
    // with some runway left
    private const val EXPIRY_MARGIN_MS = 2 * 60 * 1000L

    private const val KEY_HOTSTAR_FEED = "ES_HOTSTAR_FEED"

    // zee5 and dangal groups ride along inside the big jio feed, skip them
    private val EXCLUDED_GROUPS = setOf("ZEE5 India", "ZEE5 Worldwide", "Dangal Play")
    private val BIGGBOSS_KEEP = "BIGG BOSS LIVE 24/7"

    private val COOKIE_EXP_RE = Regex("""(?:^|[~=])exp=(\d{9,12})""")

    const val CAT_SPORTS = "sports"
    const val CAT_MOVIES = "movies"
    const val CAT_KIDS = "kids"

    data class StreamData(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val kid: String? = null,
        val key: String? = null,
        val quality: Int? = null,
        // multi audio channels carry several clearkey pairs, the manifest kid
        // picks the matching one at play time
        val kids: Map<String, String>? = null
    )

    data class ChannelData(
        val key: String,
        val name: String,
        val sourceId: String,
        val sourceLabel: String,
        val logo: String?,
        val streams: List<StreamData>,
        val jioId: String? = null,
        val category: String? = null
    )

    // the sony feed logos point at sonypicturesnetworks.com which rejects
    // every client with a 403, the jio cdn carries the same channel logos
    private val SONY_LOGOS = mapOf(
        "sony sab hd" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonySAB.png",
        "set hd" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_HD.png",
        "sony pal" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPAL.png",
        "sony wah" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyWAH.png",
        "sony max" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_Max_SD.png",
        "sony max hd" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX.png",
        "sony max2" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX2.png",
        "sony sports ten 1" to "https://jiotvimages.cdn.jio.com/dare_images/images/Ten_1.png",
        "sony sports ten 1 hd" to "https://jiotvimages.cdn.jio.com/dare_images/images/Ten_HD.png",
        "sony sports ten 2" to "https://jiotvimages.cdn.jio.com/dare_images/images/Ten_2.png",
        "sony sports ten 2 hd" to "https://jiotvimages.cdn.jio.com/dare_images/images/Ten2_HD.png",
        "sony sports ten 3" to "https://jiotvimages.cdn.jio.com/dare_images/images/Ten_3.png",
        "sony sports ten 3 hd" to "https://jiotvimages.cdn.jio.com/dare_images/images/Ten3_HD.png",
        "sony sports ten 4 tamil" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Tamil.png",
        "sony sports ten 4 telugu" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/Ten_4_Telugu.png",
        "sony sports ten 5" to "https://jiotvimages.cdn.jio.com/dare_images/images/Sony_Six_SD.png",
        "sony sports ten 5 hd" to "https://jiotvimages.cdn.jio.com/dare_images/images/Six_HD.png",
        "sony yay" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyYAYHin.png",
        "sony bbc earth hd" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyBBCEarthHin.png",
        "sony pix hd" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPIX.png",
        "sony marathi" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMarathi.png",
        "sony aath" to "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyAATH.png"
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class SonyEntry(
        val id: String? = null,
        val url: String? = null,
        val logo: String? = null,
        val name: String? = null
    )

    private class FeedCache {
        var at = 0L
        var channels: List<ChannelData> = emptyList()
    }

    private val caches = ConcurrentHashMap<String, FeedCache>()

    private fun cached(cache: FeedCache): List<ChannelData>? {
        if (cache.at <= 0) return null
        val ttl = if (cache.channels.isEmpty()) FAILED_TTL else CACHE_TTL
        return if (System.currentTimeMillis() - cache.at < ttl) cache.channels else null
    }

    private suspend fun fetchText(url: String): String? = try {
        app.get(url, timeout = 30L).text
    } catch (_: Exception) {
        null
    }

    private fun cookieExpiryMs(cookie: String): Long? =
        COOKIE_EXP_RE.find(cookie)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)

    private fun streamUsable(stream: StreamData): Boolean {
        val cookie = stream.headers["Cookie"] ?: return true
        val exp = cookieExpiryMs(cookie) ?: return true
        if (exp - System.currentTimeMillis() > EXPIRY_MARGIN_MS) return true
        // the token only gates the jio and hotstar cdns, mirrors on other
        // hosts keep playing after it dies
        return !stream.url.contains("cdn.jio.com") && !stream.url.contains("hotstar")
    }

    @Volatile
    private var hotstarWriteAt = 0L

    private fun cacheHotstarFeed(channels: List<ChannelData>) {
        if (System.currentTimeMillis() - hotstarWriteAt < HOTSTAR_WRITE_TTL) return
        hotstarWriteAt = System.currentTimeMillis()
        try {
            CloudStreamApp.setKey(KEY_HOTSTAR_FEED, channels.toJson())
        } catch (e: Exception) {
            Log.d("EliteStreams", "hotstar cache write failed: ${e.message}")
        }
    }

    private fun cachedHotstarFeed(): List<ChannelData> = try {
        val raw = CloudStreamApp.getKey<String>(KEY_HOTSTAR_FEED) ?: return emptyList()
        val channels = parseJson<List<ChannelData>>(raw)
        val alive = channels.firstOrNull()?.streams?.firstOrNull()?.let { streamUsable(it) } == true
        if (alive) channels else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun loadFeed(
        cacheId: String,
        loader: suspend () -> List<ChannelData>
    ): List<ChannelData> {
        val cache = caches.getOrPut(cacheId) { FeedCache() }
        cached(cache)?.let { return it }
        val loaded = try {
            loader()
        } catch (_: Exception) {
            emptyList()
        }
        cache.at = System.currentTimeMillis()
        cache.channels = loaded
        return loaded
    }

    private val QUALITY_URL_RE = Regex("""/(\d{3,4})p\.(?:m3u8|mpd)""")
    private val QUALITY_TAG_RE = Regex("""\[(\d{3,4})[pP]\]\s*$""")

    private fun attr(line: String, name: String): String? =
        Regex("""$name="([^"]*)"""").find(line)?.groupValues?.get(1)

    // the feeds hand out urls with doubled slashes in the path or a dangling
    // empty query, the jio cdns reject those outright
    private fun cleanUrl(raw: String): String {
        var url = raw.trim()
        val schemeAt = url.indexOf("://")
        if (schemeAt >= 0 && schemeAt + 3 < url.length) {
            val scheme = url.substring(0, schemeAt + 3)
            val rest = url.substring(schemeAt + 3)
            val queryAt = rest.indexOf('?')
            val path = if (queryAt >= 0) rest.substring(0, queryAt) else rest
            val query = if (queryAt >= 0) rest.substring(queryAt) else ""
            url = scheme + path.replace(Regex("/{2,}"), "/") + query
        }
        while (url.endsWith("?") || url.endsWith("&")) url = url.dropLast(1)
        return url
    }

    private fun extinfTitle(line: String): String {
        var inside = false
        var lastComma = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inside = !inside
                ',' -> if (!inside) lastComma = i
            }
        }
        return if (lastComma in 0 until line.length - 1) line.substring(lastComma + 1).trim()
        else line.substringAfterLast(",").trim()
    }

    private class M3uItem {
        var name: String = ""
        var logo: String? = null
        var group: String? = null
        var url: String = ""
        var kid: String? = null
        var key: String? = null
        val keyPairs = LinkedHashMap<String, String>()
        val headers = mutableMapOf<String, String>()
    }

    private fun parseM3u(text: String): List<M3uItem> {
        val items = mutableListOf<M3uItem>()
        var cur = M3uItem()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    cur = M3uItem()
                    cur.name = extinfTitle(line)
                    cur.logo = attr(line, "tvg-logo")
                    cur.group = attr(line, "group-title")
                }
                line.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                    val v = line.substringAfter("license_key=").trim()
                    if (!v.startsWith("http")) {
                        v.split(",").forEach { pair ->
                            val kid = pair.substringBefore(":").trim()
                            val key = pair.substringAfter(":", "").trim()
                            if (kid.length == 32 && key.length == 32) {
                                cur.keyPairs[kid.lowercase()] = key.lowercase()
                            }
                        }
                        cur.kid = cur.keyPairs.keys.firstOrNull()
                        cur.key = cur.keyPairs[cur.kid]
                    }
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=") ->
                    cur.headers["User-Agent"] = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-referrer=") ->
                    cur.headers["Referer"] = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-cookie=") ->
                    cur.headers["Cookie"] = line.substringAfter("=").trim()
                line.startsWith("#EXTVLCOPT:http-extra-headers=") -> {
                    val v = line.substringAfter("=").trim()
                    val h = v.substringBefore(":").trim()
                    if (h.isNotBlank()) cur.headers[h] = v.substringAfter(":", "").trim()
                }
                line.startsWith("#EXTHTTP:") -> {
                    try {
                        val map = parseJson<Map<String, String>>(line.removePrefix("#EXTHTTP:").trim())
                        map.forEach { (k, v) ->
                            val proper = when (k.lowercase()) {
                                "user-agent" -> "User-Agent"
                                "referer", "referrer" -> "Referer"
                                "cookie" -> "Cookie"
                                "origin" -> "Origin"
                                else -> k
                            }
                            cur.headers[proper] = v
                        }
                    } catch (e: Exception) {
                        Log.d("EliteStreams", "header mapping failed: ${e.message}")
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    cur.url = cleanUrl(line.substringBefore("|"))
                    for (param in line.substringAfter("|", "").split("&")) {
                        val kv = param.split("=", limit = 2)
                        if (kv.size != 2) continue
                        when (kv[0].trim().lowercase()) {
                            "cookie" -> cur.headers["Cookie"] = kv[1].trim()
                            "referer", "referrer" -> cur.headers["Referer"] = kv[1].trim()
                            "origin" -> cur.headers["Origin"] = kv[1].trim()
                            "user-agent" -> cur.headers["User-Agent"] = kv[1].trim()
                        }
                    }
                    if (cur.url.startsWith("http") && cur.name.isNotBlank()) {
                        if (cur.kid == null && cur.keyPairs.isNotEmpty()) {
                            cur.kid = cur.keyPairs.keys.firstOrNull()
                            cur.key = cur.keyPairs[cur.kid]
                        }
                        items.add(cur)
                    }
                    cur = M3uItem()
                }
            }
        }
        return items
    }

    private fun qualityOf(item: M3uItem): Int? {
        QUALITY_TAG_RE.find(item.name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return QUALITY_URL_RE.find(item.url)?.groupValues?.get(1)?.toIntOrNull()
    }

    // feed groups are authoritative when present, name matching covers the
    // mixed groups that bundle other sources' channels together
    private val SPORTS_NAMES = listOf(
        "star sports", "sony sports ten", "sony liv sports", "sony ten",
        "espn", "unite8 sports", "ipl"
    )
    private val MOVIES_NAMES = listOf(
        "star gold", "star movies", "movies now", "star maa gold", "star maa movies",
        "maa gold", "maa movies", "vijay super", "anmol cinema", "asianet movies",
        "jalsha movies", "colors cineplex", "colors bangla cinema", "colors gujarati cinema",
        "colors kannada cinema", "sony max", "sony wah", "sony pix", "gemini movies",
        "surya movies", "udaya movies", "ktv", "&pictures", "and pictures", "zee cinema",
        "zee bollywood", "zee action", "zee anmol cinema", "zee classic", "zee biskope",
        "zee talkies", "b4u movies", "goldmines", "lionsgate"
    )
    private val KIDS_NAMES = listOf(
        "disney", "nick", "sonic", "hungama", "cartoon network", "sony yay", "animax",
        "chintu tv", "chutti tv", "kochu", "kushi tv", "doraemon", "jungle book",
        "motu", "oggy", "shinchan"
    )

    private fun groupCategory(group: String?): String? {
        if (group.isNullOrBlank()) return null
        val g = group.trim().lowercase()
        return when {
            g == "sports" || g.endsWith("| sports") -> CAT_SPORTS
            g == "movies" || g.endsWith("| movies") -> CAT_MOVIES
            g == "kids" || g.endsWith("| kids") || g == "wow kidz" -> CAT_KIDS
            g.startsWith("sonyliv") -> CAT_SPORTS
            g.startsWith("fancode") -> CAT_SPORTS
            else -> null
        }
    }

    private fun nameCategory(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val n = name.lowercase()
        SPORTS_NAMES.forEach { if (n.contains(it)) return CAT_SPORTS }
        MOVIES_NAMES.forEach { if (n.contains(it)) return CAT_MOVIES }
        KIDS_NAMES.forEach { if (n.contains(it)) return CAT_KIDS }
        return null
    }

    private fun toChannel(
        item: M3uItem,
        sourceId: String,
        sourceLabel: String,
        nameOverride: String? = null
    ): ChannelData {
        val name = nameOverride ?: item.name
        return ChannelData(
            key = "$sourceId|${item.group ?: ""}|$name",
            name = "$name ($sourceLabel)",
            sourceId = sourceId,
            sourceLabel = sourceLabel,
            logo = item.logo,
            category = groupCategory(item.group) ?: nameCategory(item.name),
            streams = listOf(
                StreamData(
                    url = item.url,
                    headers = item.headers.toMap(),
                    kid = item.kid,
                    key = item.key,
                    quality = qualityOf(item),
                    kids = if (item.keyPairs.size > 1) item.keyPairs.toMap() else null
                )
            )
        )
    }

    // fancode lists every event once per quality, the qualities collapse
    // into links of a single event channel
    private fun parseFancode(text: String, sourceId: String, label: String): List<ChannelData> {
        val byEvent = LinkedHashMap<String, MutableList<M3uItem>>()
        val logos = HashMap<String, String>()
        for (item in parseM3u(text)) {
            val base = QUALITY_TAG_RE.replace(item.name, "").trim()
            if (base.isBlank()) continue
            byEvent.getOrPut(base) { mutableListOf() }.add(item)
            item.logo?.let { logos[base] = it }
        }
        return byEvent.map { (base, variants) ->
            ChannelData(
                key = "$sourceId||$base",
                name = "$base ($label)",
                sourceId = sourceId,
                sourceLabel = label,
                logo = logos[base],
                category = groupCategory(variants.firstOrNull()?.group) ?: nameCategory(base),
                streams = variants.map {
                    StreamData(
                        url = it.url,
                        headers = it.headers.toMap(),
                        kid = it.kid,
                        key = it.key,
                        quality = qualityOf(it)
                    )
                }
            )
        }
    }

    suspend fun pluglist(): List<ChannelData> = loadFeed("pluglist") {
        val text = fetchText(PLUGLIST) ?: return@loadFeed emptyList()
        parseM3u(text)
            .filter { it.group !in EXCLUDED_GROUPS }
            .map { toChannel(it, "jio-tv-plus-3", "JioTV+ 3") }
            .distinctBy { it.key }
    }

    suspend fun hotstar(): List<ChannelData> = loadFeed("hotstar") {
        // hot.php answers with short 530 bursts, one more try right away
        // clears most of them
        var text = fetchText(HOTSTAR)
        if (text.isNullOrBlank() || !text.contains("#EXTINF")) {
            text = fetchText(HOTSTAR)
        }
        if (text.isNullOrBlank() || !text.contains("#EXTINF")) {
            return@loadFeed cachedHotstarFeed()
        }
        val channels = parseM3u(text).map { toChannel(it, "jio-hotstar", "Jio Hotstar") }
        if (channels.isNotEmpty()) {
            cacheHotstarFeed(channels)
            channels
        } else {
            cachedHotstarFeed()
        }
    }

    suspend fun sony(): List<ChannelData> = loadFeed("sony") {
        val text = fetchText(SONY_JSON) ?: return@loadFeed emptyList()
        val entries = try {
            parseJson<List<SonyEntry>>(text)
        } catch (_: Exception) {
            emptyList()
        }
        entries.mapNotNull { e ->
            val url = e.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val name = e.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChannelData(
                key = "sony-liv|$name",
                name = "$name (Sony LIV)",
                sourceId = "sony-liv",
                sourceLabel = "Sony LIV",
                logo = SONY_LOGOS[name.lowercase()] ?: e.logo,
                streams = listOf(StreamData(url = url)),
                category = nameCategory(name)
            )
        }
    }

    suspend fun slivEvents(): List<ChannelData> = loadFeed("sliv-events") {
        val text = fetchText(SLIV_EVENTS) ?: return@loadFeed emptyList()
        parseM3u(text).map { toChannel(it, "sony-liv-events", "Sony Liv Events") }
    }

    suspend fun fancode(): List<ChannelData> = loadFeed("fancode") {
        val primary = fetchText(FANCODE)
        val text = if (primary.isNullOrBlank() || !primary.contains("#EXTINF")) {
            fetchText(FANCODE_BACKUP)
        } else {
            primary
        }
        if (text.isNullOrBlank()) return@loadFeed emptyList()
        parseFancode(text, "fancode", "FanCode")
    }

    suspend fun biggBoss(): List<ChannelData> = loadFeed("bigg-boss") {
        val text = fetchText(BIGGBOSS) ?: return@loadFeed emptyList()
        parseM3u(text)
            .filter { it.group == BIGGBOSS_KEEP }
            .map { toChannel(it, "bigg-boss", "Bigg Boss") }
    }

    val sources = listOf(
        EsSource("jio-tv-plus-3", "JioTV+ 3"),
        EsSource("jio-hotstar", "Jio Hotstar"),
        EsSource("sony-liv", "Sony LIV"),
        EsSource("sony-liv-events", "Sony Liv Events"),
        EsSource("fancode", "FanCode"),
        EsSource("bigg-boss", "Bigg Boss")
    )

    data class EsSource(val id: String, val label: String)

    suspend fun allChannels(): List<ChannelData> = coroutineScope {
        val loaders: List<suspend () -> List<ChannelData>> = listOf(
            { pluglist() },
            { hotstar() },
            { sony() },
            { slivEvents() },
            { fancode() },
            { biggBoss() }
        )
        val deferred = loaders.map { async(Dispatchers.IO) { it() } }
        deferred.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
    }
}
