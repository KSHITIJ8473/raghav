package com.livetgtv

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class LiveTgTvProvider(
    playlistName: String,
    private val playlistUrl: String,
    private val firebaseKey: String? = null
) : MainAPI() {

    data class LoadData(
        val url: String,
        val title: String,
        val poster: String,
        val nation: String,
        val key: String,
        val keyid: String,
        val userAgent: String,
        val cookie: String,
        val licenseUrl: String,
        val drmKeys: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap()
    )

    companion object {
        private const val DEFAULT_USER_AGENT =
            "OTT Navigator/1.7.1.4 (Linux;Android 13; en; 1fin92n)"

        // the feed regenerates server side, holding the parse keeps pages instant
        private const val PLAYLIST_TTL_MS = 10L * 60L * 1000L

        private val CENC_KID = Regex("cenc:default_KID=[\"']([0-9a-fA-F\\-]{36})[\"']")
    }

    override var name = playlistName
    override var mainUrl = playlistUrl
    override var lang = "hi"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    private val baseHeaders = mapOf("User-Agent" to DEFAULT_USER_AGENT)

    private val baseClient: OkHttpClient by lazy { clientFor(baseHeaders) }

    // generation on the feed server alone takes up to half a minute
    private fun clientFor(replacement: Map<String, String>): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .addInterceptor(HeaderReplacementInterceptor(replacement))
            .build()
    }

    private val cacheLock = Any()
    private var cachedItems: List<PlaylistItem> = emptyList()
    private var cachedAt = 0L

    private suspend fun resolvePlaylistUrl(): String {
        val override = firebaseKey
            ?.let { FirebaseDomainHelper.getDomain(it) }
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val url = override ?: playlistUrl
        mainUrl = url
        return url
    }

    suspend fun channels(): List<PlaylistItem> {
        synchronized(cacheLock) {
            if (cachedItems.isNotEmpty() && System.currentTimeMillis() - cachedAt < PLAYLIST_TTL_MS) {
                return cachedItems
            }
        }
        return try {
            val url = resolvePlaylistUrl()
            val raw = getWithCustomHeaders(url)
            val parsed = IptvPlaylistParser.parse(decryptContent(raw))
            synchronized(cacheLock) {
                cachedItems = parsed
                cachedAt = System.currentTimeMillis()
                parsed
            }
        } catch (e: Exception) {
            // keep serving the last good parse while the feed is unreachable
            val fallback = synchronized(cacheLock) { cachedItems }
            if (fallback.isEmpty()) throw e
            fallback
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val byGroup = LinkedHashMap<String?, MutableList<PlaylistItem>>()
        for (channel in channels()) {
            val group = channel.attributes["group-title"]
            byGroup.getOrPut(group) { mutableListOf() }.add(channel)
        }
        val lists = byGroup.map { (group, groupChannels) ->
            HomePageList(
                group ?: "Channels",
                groupChannels.map { channel -> toSearch(channel) },
                true
            )
        }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return channels()
            .filter { it.title?.lowercase()?.contains(q) == true }
            .map { channel -> toSearch(channel) }
    }

    private fun toSearch(channel: PlaylistItem): SearchResponse {
        val data = LoadData(
            url = channel.url ?: "",
            title = channel.title ?: "Unknown Channel",
            poster = channel.attributes["tvg-logo"] ?: "",
            nation = channel.attributes["group-title"] ?: "",
            key = channel.key ?: "",
            keyid = channel.keyid ?: "",
            userAgent = channel.userAgent ?: "",
            cookie = channel.cookie ?: "",
            licenseUrl = channel.licenseUrl ?: "",
            drmKeys = channel.drmKeys,
            headers = channel.headers
        )
        return newLiveSearchResponse(data.title, data.toJson(), TvType.Live) {
            posterUrl = data.poster.takeIf { it.startsWith("http") }
            lang = data.nation
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = try {
            parseJson<LoadData>(url)
        } catch (_: Exception) {
            throw ErrorLoadingException("Channel data is unreadable")
        }
        return newLiveStreamLoadResponse(data.title, url, url) {
            posterUrl = data.poster.takeIf { it.startsWith("http") }
            plot = data.nation
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = try {
            parseJson<LoadData>(data)
        } catch (_: Exception) {
            return false
        }
        val url = item.url.trim()
        if (url.isBlank()) return false
        val playbackHeaders = playbackHeaders(item)

        if (url.contains("mpd")) {
            return mpdLinks(item, url, playbackHeaders, callback)
        }

        if (url.contains("&e=.m3u") || url.contains("play.php?")) {
            callback(
                newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                    referer = ""
                    quality = Qualities.Unknown.value
                    this.headers = playbackHeaders
                }
            )
            return true
        }

        callback(
            newExtractorLink(name, item.title, url, INFER_TYPE) {
                referer = ""
                quality = Qualities.Unknown.value
                this.headers = playbackHeaders
            }
        )
        return true
    }

    private fun playbackHeaders(item: LoadData): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out.putAll(item.headers)
        out["User-Agent"] = item.userAgent.ifBlank { DEFAULT_USER_AGENT }
        if (item.cookie.isNotBlank()) out["Cookie"] = item.cookie
        return out
    }

    private suspend fun mpdLinks(
        item: LoadData,
        url: String,
        playbackHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val validKey = item.key.isNotBlank() && item.keyid.isNotBlank() &&
            item.key.trim().lowercase() != "null" && item.keyid.trim().lowercase() != "null"
        val validLicense = item.licenseUrl.isNotBlank() && item.licenseUrl.trim().lowercase() != "null"

        if (validKey || item.drmKeys.isNotEmpty()) {
            clearKeyLink(item, url, playbackHeaders, callback)
            return true
        }

        if (!validLicense) {
            callback(
                newExtractorLink(name, name, url, ExtractorLinkType.DASH) {
                    referer = ""
                    quality = Qualities.Unknown.value
                    this.headers = playbackHeaders
                }
            )
            return true
        }

        // no embedded pair, ask the license server the way the site player does
        val kidHex = manifestKids(url, playbackHeaders)?.firstOrNull()
            ?: UUID.randomUUID().toString().replace("-", "").lowercase()
        val kidBase64 = hexToBase64Url(kidHex) ?: kidHex
        val serverKey = licenseServerKey(item.licenseUrl.trim(), kidBase64)
        if (serverKey.isNotBlank()) {
            callback(
                newDrmExtractorLink(name, item.title, url, INFER_TYPE, CLEARKEY_UUID) {
                    quality = Qualities.Unknown.value
                    this.headers = playbackHeaders
                    key = serverKey
                    kid = kidBase64
                }
            )
            return true
        }
        callback(
            newDrmExtractorLink(name, item.title, url, INFER_TYPE, CLEARKEY_UUID) {
                quality = Qualities.Unknown.value
                this.headers = playbackHeaders
                licenseUrl = item.licenseUrl.trim()
            }
        )
        return true
    }

    // multiple clearkey pairs ride inside the kid string the way the jiotv providers pack them,
    // limited to the kids the manifest asks for when it is reachable
    private suspend fun clearKeyLink(
        item: LoadData,
        url: String,
        playbackHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val allPairs: List<Pair<String, String>> = when {
            item.drmKeys.isNotEmpty() -> {
                val fromManifest = manifestKids(url, playbackHeaders)
                    ?.mapNotNull { kid -> item.drmKeys[kid]?.let { kid to it } }
                fromManifest?.takeIf { it.isNotEmpty() }
                    ?: item.drmKeys.entries.map { it.key to it.value }
            }
            else -> listOf(
                (base64ToHexOrNull(item.keyid) ?: item.keyid.trim()) to
                    (base64ToHexOrNull(item.key) ?: item.key.trim())
            )
        }
        val encoded = allPairs.mapNotNull { (kid, key) ->
            val kidB64 = hexToBase64Url(kid) ?: kid
            val keyB64 = hexToBase64Url(key) ?: key
            kidB64 to keyB64
        }
        if (encoded.isEmpty()) {
            callback(
                newExtractorLink(name, name, url, ExtractorLinkType.DASH) {
                    referer = ""
                    quality = Qualities.Unknown.value
                    this.headers = playbackHeaders
                }
            )
            return
        }
        var kidField = encoded.first().first
        for (index in 1 until encoded.size) {
            kidField += "\"},{\"kty\":\"oct\",\"k\":\"" + encoded[index].second +
                "\",\"kid\":\"" + encoded[index].first
        }
        callback(
            newDrmExtractorLink(name, item.title, url, INFER_TYPE, CLEARKEY_UUID) {
                quality = Qualities.Unknown.value
                this.headers = playbackHeaders
                key = encoded.first().second
                kid = kidField
            }
        )
    }

    private fun manifestKids(url: String, headers: Map<String, String>): List<String>? {
        val manifest = fetchText(url, headers) ?: return null
        return CENC_KID.findAll(manifest)
            .map { it.groupValues[1].replace("-", "").lowercase(Locale.ROOT) }
            .distinct()
            .toList()
    }

    private fun licenseServerKey(licenseUrl: String, kid: String): String {
        return try {
            val payload = """{"kids":["$kid"],"type":"temporary"}"""
            val request = Request.Builder()
                .url(licenseUrl)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val text = clientFor(
                mapOf(
                    "User-Agent" to DEFAULT_USER_AGENT,
                    "Content-Type" to "application/json"
                )
            ).newCall(request).execute().use { response ->
                response.body?.string()
            } ?: return ""
            val root = parseJson<Map<String, Any?>>(text)
            val entries = (root["keys"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?: return ""
            val matched = entries.firstOrNull { it["kid"] == kid } ?: entries.firstOrNull()
            (matched?.get("k") as? String)?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        return try {
            clientFor(headers).newCall(Request.Builder().url(url).build()).execute().use { response ->
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    // some feeds put their headers straight on the playlist url behind a pipe
    private suspend fun getWithCustomHeaders(url: String): String {
        var finalUrl = url
        var replacement: Map<String, String>? = null
        if (url.contains("|")) {
            val parts = url.split("|", limit = 2)
            val dynamic = baseHeaders.toMutableMap()
            var changed = false
            for (pair in parts[1].split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size != 2) continue
                val headerName = kv[0].trim()
                val headerValue = kv[1].trim()
                dynamic.keys.firstOrNull { it.equals(headerName, ignoreCase = true) }
                    ?.let { dynamic.remove(it) }
                dynamic[headerName] = headerValue
                changed = true
            }
            finalUrl = parts[0]
            if (changed) replacement = dynamic
        }
        val client = replacement?.let { clientFor(it) } ?: baseClient
        return client.newCall(Request.Builder().url(finalUrl).build()).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    // a few feeds ship the playlist as an aes blob instead of text
    private fun decryptContent(content: String): String {
        if (content.startsWith("#EXTM3U") || content.startsWith("#EXTINF") || content.startsWith("#KODIPROP")) {
            return content
        }
        return try {
            val trimmed = content.trim()
            if (trimmed.length < 79) return trimmed
            val encrypted = trimmed.substring(0, 10) +
                trimmed.substring(34, trimmed.length - 54) +
                trimmed.substring(trimmed.length - 10)
            val iv = Base64.decode(trimmed.substring(10, 34), Base64.DEFAULT)
            val keyBytes = Base64.decode(
                trimmed.substring(trimmed.length - 54, trimmed.length - 10),
                Base64.DEFAULT
            )
            val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (_: Exception) {
            content
        }
    }

    private fun base64ToHexOrNull(value: String): String? {
        val direct = value.trim().replace("-", "")
        if (direct.isNotEmpty() && direct.length % 2 == 0 && direct.matches(Regex("^[0-9a-fA-F]+$"))) {
            return direct.lowercase(Locale.ROOT)
        }
        return try {
            val normalized = value.trim().replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            Base64.decode(padded, Base64.DEFAULT).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBase64Url(hex: String): String? {
        val clean = hex.trim().replace("-", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return try {
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return CookieRefreshInterceptor(extractorLink)
    }

    // stream hosts rotate set-cookies, the starting cookie has to survive every segment request
    private class CookieRefreshInterceptor(private val link: ExtractorLink) : Interceptor {
        private var currentCookie: String? = link.headers["Cookie"]

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val builder = request.newBuilder()
            val range = request.header("Range")
            builder.headers(Headers.Builder().build())
            range?.let { builder.addHeader("Range", it) }
            for ((name, value) in link.headers) builder.addHeader(name, value)
            currentCookie?.takeIf { it.isNotBlank() }?.let { cookie ->
                builder.removeHeader("Cookie")
                builder.addHeader("Cookie", cookie)
            }
            val response = chain.proceed(builder.build())
            val setCookies = response.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                val jar = LinkedHashMap<String, String>()
                currentCookie?.takeIf { it.isNotBlank() }?.let { cookie ->
                    for (part in cookie.split(";")) {
                        val kv = part.trim().split("=", limit = 2)
                        if (kv.size == 2) jar[kv[0]] = kv[1]
                    }
                }
                for (raw in setCookies) {
                    val kv = raw.substringBefore(";").trim().split("=", limit = 2)
                    if (kv.size == 2) jar[kv[0]] = kv[1]
                }
                currentCookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
            return response
        }
    }

    // feeds that demand a specific player user agent or origin fail without these swapped in
    private class HeaderReplacementInterceptor(
        private val customHeaders: Map<String, String>
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val builder = chain.request().newBuilder()
            for (name in customHeaders.keys) builder.removeHeader(name)
            for ((name, value) in customHeaders) builder.addHeader(name, value)
            return chain.proceed(builder.build())
        }
    }
}
