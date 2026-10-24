package com.livetgtv

import android.util.Base64
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import org.json.JSONObject

class LiveTgTvProvider : MainAPI() {

    override var mainUrl = TgTvApi.SITE
    override var name = "LiveTgTv"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    // the stream function fronts a live jiotv fetch and can take half a minute
    override val loadLinksTimeoutMs: Long? = 3 * 60_000L

    override val mainPage = listOf(
        MainPageData("All Channels", "all"),
        MainPageData("Sports", "Sports"),
        MainPageData("Movies", "Movies"),
        MainPageData("Entertainment", "Entertainment"),
        MainPageData("News", "News"),
        MainPageData("Kids", "Kids"),
        MainPageData("Music", "Music"),
        MainPageData("Devotional", "Devotional"),
        MainPageData("Infotainment", "Infotainment"),
        MainPageData("Lifestyle", "Lifestyle"),
        MainPageData("Educational", "Educational"),
        MainPageData("Business News", "Business News"),
        MainPageData("English", "English"),
        MainPageData("Tamil", "Tamil"),
        MainPageData("Telugu", "Telugu"),
        MainPageData("Malayalam", "Malayalam"),
        MainPageData("Kannada", "Kannada"),
        MainPageData("Marathi", "Marathi"),
        MainPageData("Bengali", "Bengali"),
        MainPageData("Punjabi", "Punjabi"),
        MainPageData("Gujarati", "Gujarati"),
        MainPageData("Urdu", "Urdu"),
        MainPageData("Odia", "Odia"),
        MainPageData("Assamese", "Assamese"),
        MainPageData("Bhojpuri", "Bhojpuri")
    )

    companion object {
        private const val PAGE_SIZE = 60
    }

    private fun toSearch(c: JSONObject): SearchResponse? {
        val id = c.optString("id")
        val name = c.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        return newLiveSearchResponse(name, "${TgTvApi.SITE}/watch/$id", TvType.Live) {
            posterUrl = c.optString("logo").takeIf { it.startsWith("http") }
        }
    }

    // takes the id out of any url form that reaches load() or loadLinks(),
    // including the mainUrl-prefixed entries old history still carries
    private fun channelIdOf(input: String): String? {
        var s = input.trim()
        if (s.isBlank()) return null
        s = s.substringBefore('?').substringBefore('#')
        if (s.startsWith(TgTvApi.SITE)) s = s.removePrefix(TgTvApi.SITE)
        s = s.trim('/')
        if (s.startsWith("watch/")) s = s.removePrefix("watch/")
        if (s.contains('/')) s = s.substringAfterLast('/')
        return s.takeIf { it.isNotBlank() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val channels = TgTvApi.channels()
        val filtered = if (request.data == "all") {
            channels
        } else {
            channels.filter { it.optString("category") == request.data }
        }
        val from = (page - 1) * PAGE_SIZE
        val slice = if (from >= filtered.size) {
            emptyList()
        } else {
            filtered.subList(from, minOf(from + PAGE_SIZE, filtered.size))
        }
        val items = slice.mapNotNull(::toSearch)
        return newHomePageResponse(request.name, items, hasNext = from + PAGE_SIZE < filtered.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return TgTvApi.channels()
            .filter { it.optString("name").lowercase().contains(q) }
            .take(50)
            .mapNotNull(::toSearch)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = channelIdOf(url)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Channel not found: $url")
        val channel = TgTvApi.channelById(id)
            ?: throw com.lagradost.cloudstream3.ErrorLoadingException("Channel not found: $id")
        val name = channel.optString("name")
        val category = channel.optString("category").ifBlank { "Live TV" }
        return newLiveStreamLoadResponse(name, "${TgTvApi.SITE}/watch/$id", id) {
            posterUrl = channel.optString("logo").takeIf { it.startsWith("http") }
            plot = "$name - live $category channel from TG TV"
        }
    }

    // drm pairs on the channel entry itself, single or list form
    private fun channelKeys(channel: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val multi = channel.optJSONArray("keys")
        if (multi != null) {
            for (i in 0 until multi.length()) {
                val e = multi.optJSONObject(i) ?: continue
                val kid = e.optString("keyId").lowercase()
                val key = e.optString("key").lowercase()
                if (kid.length == 32 && key.length == 32) out[kid] = key
            }
        }
        val kid = channel.optString("keyId").lowercase()
        val key = channel.optString("key").lowercase()
        if (kid.length == 32 && key.length == 32) out[kid] = key
        return out
    }

    // drm pairs on a config or backup entry, dict or single pair
    private fun keyPairsOf(vararg sources: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun addPair(kid: Any?, key: Any?) {
            val k = kid?.toString()?.lowercase() ?: return
            val v = key?.toString()?.lowercase() ?: return
            if (k.length == 32 && v.length == 32 && k.matches(Regex("[0-9a-f]+")) && v.matches(Regex("[0-9a-f]+"))) {
                out[k] = v
            }
        }
        for (src in sources) {
            if (src is JSONObject) {
                val keys = src.optJSONObject("clearKeys")
                if (keys != null) {
                    for (k in keys.keys()) addPair(k, keys.opt(k))
                }
                addPair(src.optString("keyId"), src.optString("key"))
            }
        }
        return out
    }

    private fun hexToBase64Url(hex: String): String? {
        val clean = hex.replace("-", "")
        if (clean.length % 2 != 0 || !clean.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return try {
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenOf(url: String): String? =
        Regex("[?&]__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)

    private fun isPlLive(url: String): Boolean = url.contains("//jiotvpllive.cdn.jio.com/")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data is the bare id; older history entries can carry the prefixed form
        val id = channelIdOf(data) ?: return false
        val channel = TgTvApi.channels().firstOrNull { it.optString("id") == id } ?: return false
        val chanKeys = channelKeys(channel)

        // an interstitial answer from some networks simply means no token
        val starTok = TgTvApi.starCookie(id)
        val primTok = TgTvApi.primaryCookie()
        val config = TgTvApi.serverFn(id)

        // one link per distinct url in the site's own order, so two servers
        // handing out the same stream do not double up in the picker
        val byUrl = LinkedHashMap<String, Map<String, String>>()

        fun offer(rawUrl: String, cookie: String, extraKeys: Map<String, String>) {
            var u = rawUrl.trim()
            if (!u.startsWith("http")) return
            u = u.replace("//bpk-tv/", "/bpk-tv/").replace("bpk-tv//", "bpk-tv/")
            val path = u.substringBefore('?')
            // token class is fixed by the host the way the embedded player does
            // it: jiotvpllive takes the per-channel star token only, every
            // other cdn the global one
            val pllive = isPlLive(path)
            val token = when {
                pllive -> starTok
                cookie.isNotBlank() -> cookie
                else -> primTok
            }
            // no token of the right class - keep the query it came with, it may still work
            val finalUrl = if (token.isBlank()) u else "$path?$token"
            if (finalUrl !in byUrl) {
                byUrl[finalUrl] = if (extraKeys.isEmpty()) chanKeys else chanKeys + extraKeys
            }
        }

        // server 1: the stream config's own urls
        if (config != null) {
            val cfgKeys = keyPairsOf(config)
            val cfgCookie = config.optString("cookie")
            val urls = config.optJSONArray("urls")
            if (urls != null) {
                for (i in 0 until urls.length()) {
                    offer(urls.optString(i), cfgCookie, cfgKeys)
                }
            }
            // server 2..: every backup is its own server, with its own cookie and keys
            val backups = config.optJSONArray("backups")
            if (backups != null) {
                for (i in 0 until backups.length()) {
                    val b = backups.optJSONObject(i) ?: continue
                    val bUrls = b.optJSONArray("urls") ?: continue
                    val bKeys = keyPairsOf(b)
                    val bCookie = b.optString("cookie")
                    for (j in 0 until bUrls.length()) {
                        offer(bUrls.optString(j), bCookie, bKeys)
                    }
                }
            }
        }

        // the channel list the embedded player plays when every config server fails
        for (k in listOf("url", "url1", "url2", "url3")) {
            val u = channel.optString(k).takeIf { it.isNotBlank() } ?: continue
            offer(u, "", emptyMap())
        }

        if (byUrl.isEmpty()) return false

        var emitted = false
        for ((index, entry) in byUrl.entries.withIndex()) {
            val url = entry.key
            val keys = entry.value
            val label = "Server ${index + 1}"
            val token = tokenOf(url)
            val headers = TgTvApi.playerHeaders + (
                if (token != null) mapOf("Cookie" to "__hdnea__=$token") else emptyMap()
                )
            val encoded = keys.entries
                .mapNotNull { (kid, key) ->
                    val k = hexToBase64Url(kid)
                    val v = hexToBase64Url(key)
                    if (k != null && v != null) k to v else null
                }
            if (encoded.isEmpty()) {
                callback(
                    newExtractorLink(name, label, url, INFER_TYPE) {
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            } else {
                // first pair goes in the license, the rest ride inside the kid
                // string so one clearkey holds every pair the mpd asks for
                val kidField = StringBuilder(encoded.first().first)
                for (i in 1 until encoded.size) {
                    kidField.append("\"},{\"kty\":\"oct\",\"k\":\"").append(encoded[i].second)
                    kidField.append("\",\"kid\":\"").append(encoded[i].first)
                }
                callback(
                    newDrmExtractorLink(
                        name,
                        label,
                        url,
                        INFER_TYPE,
                        CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.kty = "oct"
                        this.key = encoded.first().second
                        this.kid = kidField.toString()
                        this.headers = headers
                    }
                )
            }
            emitted = true
        }
        return emitted
    }
}
