package com.elitestreams

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
    private const val JIOTV_API = "https://jiotv-api.manishjaiswal.workers.dev/"
    private const val JIO_STB = "https://premiumplugx.top/jiostb/mjelo.php?view=raw"

    private const val CACHE_TTL = 5 * 60 * 1000L

    // a 530 body parses to zero channels, holding that for the full ttl
    // would keep a recovered server hidden, so failures retry sooner
    private const val FAILED_TTL = 45 * 1000L
    private const val HOTSTAR_WRITE_TTL = 10 * 60 * 1000L
    private const val JIO_WARM_TTL = 30 * 60 * 1000L

    // a cookie close to its expiry can die mid stream, only trust ones
    // with some runway left
    private const val EXPIRY_MARGIN_MS = 2 * 60 * 1000L

    private const val KEY_JIO_STREAMS = "ES_JIO_STREAMS"
    private const val KEY_HOTSTAR_FEED = "ES_HOTSTAR_FEED"

    // zee5 and dangal groups ride along inside the big jio feed, skip them
    private val EXCLUDED_GROUPS = setOf("ZEE5 India", "ZEE5 Worldwide", "Dangal Play")
    private val BIGGBOSS_KEEP = "BIGG BOSS LIVE 24/7"

    private const val JIO_UA = "plaYtv/7.1.5"

    // the feeds call it star maa hd, the curated list kept the short form
    private val FEED_NAME_ALIASES = mapOf("maa hd" to "star maa hd")

    private val COOKIE_EXP_RE = Regex("""(?:^|[~=])exp=(\d{9,12})""")
    private val COOKIE_ACL_RE = Regex("""acl=([^~]+)""")

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

    internal data class JioStatic(val jioId: String, val name: String, val logo: String)

    private val JIO_PLUS = listOf(
        JioStatic("1108", "Star Sports 1 Hindi HD", "https://img.media.jio.com/tvpimages/5/6/301982_1749665314605_l_medium.jpg"),
        JioStatic("1106", "Star Sports 1 HD", "https://img.media.jio.com/tvpimages/76/32/301981_1749665664018_l_medium.jpg"),
        JioStatic("362", "Star Sports 1 Hindi", "https://img.media.jio.com/tvpimages/13/81/302112_1749665154709_l_medium.jpg"),
        JioStatic("1142", "Star Sports 1", "https://img.media.jio.com/tvpimages/56/92/302115_1749665579801_l_medium.jpg"),
        JioStatic("1109", "Star Sports 2 HD", "https://img.media.jio.com/tvpimages/41/51/301983_1749666169105_l_medium.jpg"),
        JioStatic("1141", "Star Sports 2", "https://img.media.jio.com/tvpimages/11/30/302116_1749666029079_l_medium.jpg"),
        JioStatic("460", "Star Sports Select 1 HD", "https://img.media.jio.com/tvpimages/96/65/302113_1749664252703_l_medium.jpg"),
        JioStatic("1123", "Star Sports Select 1", "https://img.media.jio.com/tvpimages/13/82/301994_1749664069587_l_medium.jpg"),
        JioStatic("461", "Star Sports Select 2 HD", "https://img.media.jio.com/tvpimages/98/79/302114_1749665414817_l_medium.jpg"),
        JioStatic("1114", "Star Sports Select 2", "https://img.media.jio.com/tvpimages/98/48/301989_1749664475828_l_medium.jpg"),
        JioStatic("3277", "Star Sports 1 Tamil HD", "https://img.media.jio.com/tvpimages/19/19/302122_1749662923298_l_medium.jpg"),
        JioStatic("1124", "Star Sports 1 Tamil", "https://img.media.jio.com/tvpimages/69/73/301995_1749664384503_l_medium.jpg"),
        JioStatic("3278", "Star Sports 1 Telugu HD", "https://img.media.jio.com/tvpimages/19/57/302123_1749663029798_l_medium.jpg"),
        JioStatic("1651", "Star Sports 1 Telugu", "https://img.media.jio.com/tvpimages/26/67/302119_1749662981095_l_medium.jpg"),
        JioStatic("1650", "Star Sports 1 Kannada", "https://img.media.jio.com/tvpimages/13/91/302118_1749663894692_l_medium.jpg"),
        JioStatic("162", "Sony LIV Sports 1", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyLIV_Sports_1.png"),
        JioStatic("3373", "Sony LIV Sports 2", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyLIV_Sports_2.png"),
        JioStatic("3374", "Sony LIV Sports 3", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyLIV_Sports_3.png"),
        JioStatic("3399", "Sony LIV Sports 5", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyLIV_Sports_5.png"),
        JioStatic("144", "Colors HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ColorsHD.png"),
        JioStatic("291", "SET HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sony_HD.png"),
        JioStatic("471", "Sony SAB HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonySAB.png"),
        JioStatic("167", "Zee TV HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeTVHD.png"),
        JioStatic("931", "Star Bharat HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Bharat_HD.png"),
        JioStatic("472", "And TV HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/AndTVHD.png"),
        JioStatic("474", "Sony Pal", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyPAL.png"),
        JioStatic("279", "Colors Rishtey", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ColorsRishtey.png"),
        JioStatic("156", "Star Gold HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Gold_HD.png"),
        JioStatic("165", "Zee Cinema HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeCinemaHD.png"),
        JioStatic("476", "Sony Max HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX.png"),
        JioStatic("482", "Colors Cineplex", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Colors_Cineplex.png"),
        JioStatic("483", "Sony MAX2", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyMAX2.png"),
        JioStatic("484", "Zee Cinema", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeCinema.png"),
        JioStatic("487", "Zee Bollywood", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeBollywood.png"),
        JioStatic("488", "Zee Action", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeAction.png"),
        JioStatic("185", "And Pictures HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/AndPicturesHD.png"),
        JioStatic("1104", "Star Movies HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Movies_HD.png"),
        JioStatic("151", "Movies Now HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Movies_Now_HD.png"),
        JioStatic("182", "B4U Movies", "https://jiotv.catchup.cdn.jio.com/dare_images/images/b4u_movies.png"),
        JioStatic("173", "Aaj Tak", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Aaj_Tak.png"),
        JioStatic("177", "ABP News India", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ABP_News_India.png"),
        JioStatic("235", "India TV", "https://jiotv.catchup.cdn.jio.com/dare_images/images/India_TV.png"),
        JioStatic("258", "NDTV India", "https://jiotv.catchup.cdn.jio.com/dare_images/images/NDTV_India.png"),
        JioStatic("255", "NDTV 24x7", "https://jiotv.catchup.cdn.jio.com/dare_images/images/NDTV_24x7.png"),
        JioStatic("383", "Times NOW", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Times_NOW.png"),
        JioStatic("412", "Wion", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Wion.png"),
        JioStatic("1499", "News 18 India", "https://jiotv.catchup.cdn.jio.com/dare_images/images/IBN_7.png"),
        JioStatic("504", "Zee News", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Zee_News.png"),
        JioStatic("1403", "Republic Bharat", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Republic_Bharat.png"),
        JioStatic("463", "Discovery HD English", "https://jiotv.catchup.cdn.jio.com/dare_images/images/DiscoveryHDEng.png"),
        JioStatic("286", "Animal Planet HD English", "https://jiotv.catchup.cdn.jio.com/dare_images/images/AnimalPlanetHDEng.png"),
        JioStatic("479", "TLC HD English", "https://jiotv.catchup.cdn.jio.com/dare_images/images/TLCHDEnglish.png"),
        JioStatic("146", "History TV18 HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/History_HD.png"),
        JioStatic("248", "MTV", "https://jiotv.catchup.cdn.jio.com/dare_images/images/MTV.png"),
        JioStatic("440", "9X Jalwa", "https://jiotv.catchup.cdn.jio.com/dare_images/images/9XJalwa.png"),
        JioStatic("183", "B4U Music", "https://jiotv.catchup.cdn.jio.com/dare_images/images/B4U_Music.png"),
        JioStatic("250", "Music India", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Music_India.png"),
        JioStatic("1373", "Disney Channel", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Disney_Channel.png"),
        JioStatic("1391", "Hungama", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Hungama.png"),
        JioStatic("1392", "Super Hungama", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Super_Hungama.png"),
        JioStatic("166", "Cartoon Network Telugu", "https://jiotv.catchup.cdn.jio.com/dare_images/images/CNTelugu.png"),
        JioStatic("872", "Sony Yay Hindi", "https://jiotv.catchup.cdn.jio.com/dare_images/images/SonyYAYHin.png"),
        JioStatic("896", "Sun TV HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sun_TV_HD.png"),
        JioStatic("894", "KTV HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/KTV_HD.png"),
        JioStatic("897", "Sun Gemini HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Gemini_TV_HD.png"),
        JioStatic("900", "Sun Surya HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Surya_HD.png"),
        JioStatic("901", "Sun Udaya HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Udaya_HD.png"),
        JioStatic("368", "Star Vijay HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Vijay_HD.png"),
        JioStatic("1112", "Maa HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Maa_HD.png"),
        JioStatic("443", "Asianet HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Asianet_HD.png"),
        JioStatic("317", "Star Jalsha HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Jalsha_HD.png"),
        JioStatic("336", "Star Pravah HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Pravah_HD.png"),
        JioStatic("1360", "Zee Marathi HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeMarathiHD.png"),
        JioStatic("1362", "Zee Kannada HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/ZeeKannadaHD.png"),
        JioStatic("2258", "Animax", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Animax.png"),
        JioStatic("2327", "Lionsgate Play", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Lionsgate_Play.png"),
    )

    private val JIO_PLUS_2 = listOf(
        JioStatic("896", "Sun TV HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Sun_TV_HD.png"),
        JioStatic("894", "KTV HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/KTV_HD.png"),
        JioStatic("368", "Star Vijay HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Vijay_HD.png"),
        JioStatic("897", "Sun Gemini HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Gemini_TV_HD.png"),
        JioStatic("1112", "Maa HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Maa_HD.png"),
        JioStatic("443", "Asianet HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Asianet_HD.png"),
        JioStatic("317", "Star Jalsha HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Jalsha_HD.png"),
        JioStatic("336", "Star Pravah HD", "https://jiotv.catchup.cdn.jio.com/dare_images/images/Star_Pravah_HD.png"),
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class JioApiEntry(
        val id: String? = null,
        val url: String? = null,
        val name: String? = null,
        val key: String? = null,
        val keyId: String? = null,
        val cookie: String? = null
    )

    private class FeedCache {
        var at = 0L
        var channels: List<ChannelData> = emptyList()
    }

    private class JioApiCache {
        var at = 0L
        var entries: List<JioApiEntry> = emptyList()
    }

    private val caches = ConcurrentHashMap<String, FeedCache>()
    private val jioApiCache = JioApiCache()

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

    private fun feedBaseName(name: String): String {
        sources.forEach { source ->
            val suffix = " (${source.label})"
            if (name.endsWith(suffix)) return name.removeSuffix(suffix)
        }
        return name
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

    // the standalone app keeps its whole channel database on disk, these
    // stores mirror that so a stream resolved hours ago still plays while
    // every server is throwing 530s
    private val jioStoreLock = Any()
    private var jioStore: MutableMap<String, StreamData>? = null

    @Volatile
    private var hotstarWriteAt = 0L

    @Volatile
    private var jioWarmAt = 0L

    private fun jioStreamStore(): MutableMap<String, StreamData> {
        jioStore?.let { return it }
        synchronized(jioStoreLock) {
            jioStore?.let { return it }
            val loaded = try {
                CloudStreamApp.getKey<String>(KEY_JIO_STREAMS)
                    ?.let { parseJson<Map<String, StreamData>>(it).toMutableMap() }
            } catch (_: Exception) {
                null
            } ?: mutableMapOf()
            jioStore = loaded
            return loaded
        }
    }

    private fun rememberJioStreams(entries: Map<String, StreamData>) {
        if (entries.isEmpty()) return
        synchronized(jioStoreLock) {
            val store = jioStreamStore()
            var changed = false
            entries.forEach { (id, stream) ->
                if (store[id] != stream) {
                    store[id] = stream
                    changed = true
                }
            }
            if (changed) {
                try {
                    CloudStreamApp.setKey(KEY_JIO_STREAMS, store.toJson())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun cachedJioStream(jioId: String): StreamData? = try {
        jioStreamStore()[jioId]?.takeIf { streamUsable(it) }
    } catch (_: Exception) {
        null
    }

    private fun cacheHotstarFeed(channels: List<ChannelData>) {
        if (System.currentTimeMillis() - hotstarWriteAt < HOTSTAR_WRITE_TTL) return
        hotstarWriteAt = System.currentTimeMillis()
        try {
            CloudStreamApp.setKey(KEY_HOTSTAR_FEED, channels.toJson())
        } catch (_: Exception) {
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

    // the feeds and the jio api both hand out urls with doubled slashes in
    // the path or a dangling empty query, the jio cdns reject those outright
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
                    } catch (_: Exception) {}
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

    private fun jioPlusChannels(): List<ChannelData> = JIO_PLUS.map {
        ChannelData(
            key = "jio-tv-plus|${it.name}",
            name = "${it.name} (JioTV+)",
            sourceId = "jio-tv-plus",
            sourceLabel = "JioTV+",
            logo = it.logo,
            streams = emptyList(),
            jioId = it.jioId,
            category = nameCategory(it.name)
        )
    }

    private fun jioPlus2Channels(): List<ChannelData> = JIO_PLUS_2.map {
        ChannelData(
            key = "jio-tv-plus-2|${it.name}",
            name = "${it.name} (JioTV+ 2)",
            sourceId = "jio-tv-plus-2",
            sourceLabel = "JioTV+ 2",
            logo = it.logo,
            streams = emptyList(),
            jioId = it.jioId,
            category = nameCategory(it.name)
        )
    }

    // the api reuses a few ids across two different channels and drops
    // channels from time to time, the channel name sorts both cases out
    private fun jioCandidates(jioId: String, name: String?): List<JioApiEntry> {
        val byId = jioApiCache.entries.filter { it.id == jioId }
        val byName = if (name.isNullOrBlank()) {
            emptyList()
        } else {
            jioApiCache.entries.filter { sameChannelName(it.name, name) }
        }
        return byId + byName.filterNot { it in byId }
    }

    // a premium cdn url carrying the shared mobile cookie always answers
    // 403, and a path scoped cookie on a different path means the url was
    // refreshed ahead of the cookie, both pairs are dead on arrival
    private fun entryUsable(url: String, rawCookie: String): Boolean {
        if (rawCookie.isBlank()) return true
        val cookie = rawCookie.split("&").first()
        val exp = cookieExpiryMs(cookie)
        if (exp != null && exp - System.currentTimeMillis() <= EXPIRY_MARGIN_MS &&
            (url.contains("cdn.jio.com") || url.contains("hotstar"))
        ) return false
        val acl = COOKIE_ACL_RE.find(cookie)?.groupValues?.get(1)
            ?.lowercase()?.replace("%2f", "/")
        if (acl == null) return true
        if (url.contains("jiotvpllive") && acl == "/*") return false
        if (acl != "/*" && !url.contains(acl.trimEnd('*'), ignoreCase = true)) return false
        return true
    }

    // api names drift a little from the curated list, "&" vs "and", word
    // order and spacing, none of that should break the lookup
    private fun sameChannelName(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val one = FEED_NAME_ALIASES[a.trim().lowercase()] ?: a.trim().lowercase()
        val two = FEED_NAME_ALIASES[b.trim().lowercase()] ?: b.trim().lowercase()
        if (one == two) return true
        val oneAmp = one.replace("&", " and ")
        val twoAmp = two.replace("&", " and ")
        if (oneAmp == twoAmp) return true
        if (oneAmp.replace(" ", "") == twoAmp.replace(" ", "")) return true
        val oneWords = oneAmp.split(Regex("\\s+")).filter { it.isNotBlank() }.sorted()
        val twoWords = twoAmp.split(Regex("\\s+")).filter { it.isNotBlank() }.sorted()
        return oneWords == twoWords
    }

    // resolved at play time so the cookie is fresh. the stb feed and the
    // pluglist mint url and cookie together, the api mixes stale mobile
    // cookies into premium urls while it refreshes, so it is validated and
    // only tried after both feeds, and the stored copy is the last resort
    // for when every server is down at once
    suspend fun resolveJio(jioId: String, fallbackName: String? = null): StreamData? {
        if (!fallbackName.isNullOrBlank()) {
            stbChannels().firstOrNull {
                sameChannelName(feedBaseName(it.name), fallbackName)
            }?.streams?.firstOrNull()?.let {
                if (streamUsable(it)) {
                    rememberJioStreams(mapOf(jioId to it))
                    return it
                }
            }
        }
        jioFallback(fallbackName)?.let {
            if (streamUsable(it)) {
                rememberJioStreams(mapOf(jioId to it))
                return it
            }
        }
        resolveJioApi(jioId, fallbackName)?.let {
            rememberJioStreams(mapOf(jioId to it))
            return it
        }
        return cachedJioStream(jioId)
    }

    private suspend fun stbChannels(): List<ChannelData> = loadFeed("jio-stb") {
        val text = fetchText(JIO_STB) ?: return@loadFeed emptyList()
        parseM3u(text).map { toChannel(it, "jio-stb", "JioTV+") }
    }

    private suspend fun resolveJioApi(jioId: String, fallbackName: String?): StreamData? {
        if (jioApiCache.at == 0L ||
            System.currentTimeMillis() - jioApiCache.at >= CACHE_TTL
        ) {
            val text = fetchText(JIOTV_API)
            if (!text.isNullOrBlank()) {
                try {
                    jioApiCache.entries = parseJson<List<JioApiEntry>>(text)
                } catch (_: Exception) {
                    jioApiCache.entries = emptyList()
                }
            }
            jioApiCache.at = System.currentTimeMillis()
        }
        val entry = jioCandidates(jioId, fallbackName).firstOrNull { e ->
            val url = e.url?.takeIf { it.startsWith("http") }?.let { cleanUrl(it) }
            url != null && entryUsable(url, e.cookie.orEmpty().trim())
        } ?: return null
        val base = entry.url?.takeIf { it.startsWith("http") }?.let { cleanUrl(it) }
            ?: return null

        val headers = mutableMapOf(
            "User-Agent" to JIO_UA,
            "Referer" to "https://www.jiotv.com/"
        )
        // some entries pack referer, origin and user-agent into the cookie
        // field behind ampersands, only the first segment is the cookie
        entry.cookie?.takeIf { it.isNotBlank() }?.let { raw ->
            val parts = raw.split("&")
            parts.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                headers["Cookie"] = it
            }
            parts.drop(1).forEach { part ->
                val name = part.substringBefore("=").trim().lowercase()
                val value = part.substringAfter("=", "").trim()
                if (value.isEmpty()) return@forEach
                when (name) {
                    "user-agent" -> headers["User-Agent"] = value
                    "referer", "referrer" -> headers["Referer"] = value
                    "origin" -> headers["Origin"] = value
                }
            }
        }

        // keyId holds either kid:key pairs or a bare kid with the key beside
        // it, and the bare shape turns up swapped on some entries, so both
        // orientations ride in and the player keeps the pair that decrypts
        val pairs = LinkedHashMap<String, String>()
        val keyIdRaw = entry.keyId.orEmpty().trim()
        val keyRaw = entry.key.orEmpty().trim()
        if (keyIdRaw.contains(":")) {
            keyIdRaw.split(",").forEach { pair ->
                val kid = pair.substringBefore(":").trim()
                val key = pair.substringAfter(":", "").trim()
                if (kid.length == 32 && key.length == 32) {
                    pairs[kid.lowercase()] = key.lowercase()
                }
            }
        } else if (keyIdRaw.length == 32 && keyRaw.length == 32) {
            pairs[keyIdRaw.lowercase()] = keyRaw.lowercase()
            pairs[keyRaw.lowercase()] = keyIdRaw.lowercase()
        }
        val kid = pairs.keys.firstOrNull()
            ?: return StreamData(url = base, headers = headers)
        return StreamData(
            url = base,
            headers = headers,
            kid = kid,
            key = pairs[kid],
            kids = if (pairs.size > 1) pairs else null
        )
    }

    // the api drops curated channels from time to time, the big pluglist
    // feed carries the same channels with inline keys and cookies
    private suspend fun jioFallback(fallbackName: String?): StreamData? {
        if (fallbackName.isNullOrBlank()) return null
        return pluglist().firstOrNull {
            sameChannelName(feedBaseName(it.name), fallbackName)
        }?.streams?.firstOrNull()
    }

    val sources = listOf(
        EsSource("jio-tv-plus", "JioTV+"),
        EsSource("jio-tv-plus-2", "JioTV+ 2"),
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
            { jioPlusChannels() },
            { jioPlus2Channels() },
            { pluglist() },
            { hotstar() },
            { sony() },
            { slivEvents() },
            { fancode() },
            { biggBoss() }
        )
        val deferred = loaders.map { async(Dispatchers.IO) { it() } }
        val channels = deferred.flatMap { runCatching { it.await() }.getOrDefault(emptyList()) }
        warmJioStore(channels)
        channels
    }

    // fills the stored streams for the whole curated list while the pluglist
    // is healthy, a channel never played before then still resolves during
    // a later outage
    private fun warmJioStore(channels: List<ChannelData>) {
        if (channels.isEmpty()) return
        if (System.currentTimeMillis() - jioWarmAt < JIO_WARM_TTL) return
        jioWarmAt = System.currentTimeMillis()
        try {
            val feedChannels = channels.filter { it.sourceId == "jio-tv-plus-3" }
            if (feedChannels.isEmpty()) return
            val found = LinkedHashMap<String, StreamData>()
            (JIO_PLUS + JIO_PLUS_2).distinctBy { it.jioId }.forEach { curated ->
                feedChannels.firstOrNull {
                    sameChannelName(feedBaseName(it.name), curated.name)
                }?.streams?.firstOrNull()?.let {
                    if (streamUsable(it)) found[curated.jioId] = it
                }
            }
            rememberJioStreams(found)
        } catch (_: Exception) {
        }
    }
}
