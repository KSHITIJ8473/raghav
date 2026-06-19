package com.laddu100

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.json.JSONObject

// ============================================================
// HTTP Interceptors for PlayZTV
// ============================================================

class RequestHeaderInterceptor(
    private val overrideHeaders: Map<String, String>
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val builder = req.newBuilder()

        for ((name, _) in overrideHeaders) {
            builder.removeHeader(name)
        }
        for ((name, value) in overrideHeaders) {
            builder.addHeader(name, value)
        }
        return chain.proceed(builder.build())
    }
}

class DebugLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val outgoing = chain.request()
        val bodyCopy = outgoing.body
        val sink = okio.Buffer()
        bodyCopy?.writeTo(sink)
        sink.readUtf8() // consume for debug purposes
        return chain.proceed(outgoing)
    }
}

// ============================================================
// Main PlayZTV Provider
// ============================================================

class PlayZTV(
    private val customName: String = "IPTV Player",
    private val customMainUrl: String = "https://fifabd.site/OPLLX7/LIVE2.m3u"
) : MainAPI() {

    companion object {
        var context: android.content.Context? = null
        const val TAG_EXTM3U = "#EXTM3U"
        const val TAG_EXTINF = "#EXTINF"
        const val TAG_EXTVLCOPT = "#EXTVLCOPT"
    }

    override var lang = "ta"
    override var mainUrl = customMainUrl
    override var name = customName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val defaultHeaders = mapOf(
        "accept" to "*/*",
        "Cache-Control" to "no-cache, no-store",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:78.0) Gecko/20100101 Firefox/78.0",
    )

    private val httpEngine by lazy {
        OkHttpClient.Builder()
            .addInterceptor(RequestHeaderInterceptor(defaultHeaders))
            .build()
    }

    private suspend fun fetchUrl(target: String): String {
        val req = Request.Builder().url(target).build()
        return httpEngine.newCall(req).execute().use { resp ->
            resp.body.string()
        }
    }

    // ---- Hex / Base64 helpers ----

    private fun String.hexToB64UrlOrNull(): String? {
        val stripped = trim().replace("-", "")
        if (stripped.isEmpty() || stripped.length % 2 != 0 || !stripped.matches(Regex("^[0-9a-fA-F]+$"))) {
            return null
        }
        return runCatching {
            val raw = stripped.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun String.b64ToHexOrNull(): String? {
        val trimmed = trim()
        val normalized = trimmed.replace("-", "")
        if (normalized.isNotEmpty() && normalized.length % 2 == 0 && normalized.matches(Regex("^[0-9a-fA-F]+$"))) {
            return normalized.lowercase()
        }
        return runCatching {
            val fixed = trimmed
                .replace('-', '+')
                .replace('_', '/')
                .let { v ->
                    val pad = (4 - (v.length % 4)) % 4
                    v + "=".repeat(pad)
                }
            val decoded = Base64.decode(fixed, Base64.DEFAULT)
            decoded.joinToString(separator = "") { b -> "%02x".format(b) }
        }.getOrNull()
    }

    // ---- M3U content decryption ----

    private fun decodeM3uContent(raw: String): String {
        if (raw.startsWith("#EXTM3U") || raw.startsWith("#EXTINF") || raw.startsWith("#KODIPROP")) {
            return raw
        }
        val c = raw.trim()
        if (c.length < 79) return c

        return runCatching {
            val seg1 = c.substring(0, 10)
            val seg2 = c.substring(34, c.length - 54)
            val seg3 = c.substring(c.length - 10)
            val payload = seg1 + seg2 + seg3

            val ivB64 = c.substring(10, 34)
            val keyB64 = c.substring(c.length - 54, c.length - 10)

            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val key = Base64.decode(keyB64, Base64.DEFAULT)
            val enc = Base64.decode(payload, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(enc), StandardCharsets.UTF_8)
        }.getOrElse { raw }
    }

    // ---- MPD manifest fetching ----

    private fun fetchMpdManifest(mpdUrl: String, extraHeaders: Map<String, String>): String {
        val cl = OkHttpClient.Builder()
            .addInterceptor(RequestHeaderInterceptor(extraHeaders))
            .build()
        val req = Request.Builder().url(mpdUrl).build()
        return cl.newCall(req).execute().use { r -> r.body.string() }
    }

    // ---- DRM license server ----

    private fun resolveLicenseKeys(licenseUrl: String, kidB64: String): String {
        val ua = "Dalvik/2.1.0 (Linux; U; Android)"
        val cl = OkHttpClient.Builder()
            .addInterceptor(RequestHeaderInterceptor(mapOf(
                "User-Agent" to ua,
                "Content-Type" to "application/json;charset=UTF-8",
            )))
            .addInterceptor(DebugLogInterceptor())
            .build()

        val jsonBody = "{\"kids\":[\"$kidB64\"],\"type\":\"temporary\"}"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonBody.toRequestBody(mediaType)

        val req = Request.Builder().url(licenseUrl).post(body).build()
        return cl.newCall(req).execute().use { resp ->
            val rawResp = resp.body.string()
            val map = parseJson<Map<String, Any>>(rawResp)
            @Suppress("UNCHECKED_CAST")
            val keys = map["keys"] as? List<Map<String, String>> ?: return ""
            keys.firstOrNull()?.get("k") ?: ""
        }
    }

    // ---- Main page ----

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val raw = fetchUrl(mainUrl)
        val decoded = decodeM3uContent(raw)
        val playlist = M3uPlaylistLoader().load(decoded)

        return newHomePageResponse(
            playlist.entries.groupBy { it.attrs["group-title"] }.map { (group, channels) ->
                val title = group ?: ""
                val items = channels.map { ch ->
                    val streamUrl = ch.url.toString()
                    val chName = ch.title.toString()
                    val poster = ch.attrs["tvg-logo"].toString()
                    val nation = ch.attrs["group-title"].toString()
                    val k = ch.key ?: ""
                    val kid = ch.keyid ?: ""
                    val ua = ch.userAgent ?: ""
                    val cookie = ch.cookie ?: ""
                    val licUrl = ch.licenseUrl ?: ""
                    val hdrs = ch.headers

                    newLiveSearchResponse(
                        chName,
                        ChannelLoadData(streamUrl, chName, poster, nation, k, kid, ua, cookie, licUrl, ch.drmKeys, hdrs).toJson(),
                        TvType.Live
                    ) {
                        this.posterUrl = poster
                        this.apiName
                        this.lang = ch.attrs["group-title"]
                    }
                }
                HomePageList(title, items, isHorizontalImages = true)
            },
            false
        )
    }

    // ---- Search ----

    override suspend fun search(query: String): List<SearchResponse> {
        val raw = fetchUrl(mainUrl)
        val decoded = decodeM3uContent(raw)
        val playlist = M3uPlaylistLoader().load(decoded)

        return playlist.entries
            .filter { it.title?.contains(query, ignoreCase = true) ?: false }
            .map { ch ->
                val streamUrl = ch.url.toString()
                val chName = ch.title.toString()
                val poster = ch.attrs["tvg-logo"].toString()
                val nation = ch.attrs["group-title"].toString()
                val k = ch.key ?: ""
                val kid = ch.keyid ?: ""
                val ua = ch.userAgent ?: ""
                val cookie = ch.cookie ?: ""
                val licUrl = ch.licenseUrl ?: ""

                newLiveSearchResponse(
                    chName,
                    ChannelLoadData(streamUrl, chName, poster, nation, k, kid, ua, cookie, licUrl, ch.drmKeys, ch.headers).toJson(),
                    TvType.Live
                ) {
                    this.posterUrl = poster
                    this.apiName
                    this.lang = ch.attrs["group-title"]
                }
            }
    }

    // ---- Load ----

    override suspend fun load(url: String): LoadResponse {
        val ld = parseJson<ChannelLoadData>(url)
        return newLiveStreamLoadResponse(ld.title, url, url) {
            this.posterUrl = ld.poster
            this.plot = ld.nation
        }
    }

    data class ChannelLoadData(
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
        val headers: Map<String, String>,
    )

    // ---- Helper: build merged headers for a stream ----

    private fun buildHeaders(ld: ChannelLoadData): MutableMap<String, String> {
        val h = mutableMapOf<String, String>()
        h.putAll(ld.headers)
        if (ld.userAgent.isNotEmpty()) h["User-Agent"] = ld.userAgent
        if (ld.cookie.isNotEmpty()) h["Cookie"] = ld.cookie
        return h
    }

    // ---- Helper: emit a DASH DRM link with pre-resolved keys ----

    private fun emitDrmLink(
        ld: ChannelLoadData,
        hdrs: Map<String, String>,
        playerKey: String,
        playerKid: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newDrmExtractorLink(this.name, this.name, ld.url, INFER_TYPE, CLEARKEY_UUID) {
                this.quality = Qualities.Unknown.value
                if (hdrs.isNotEmpty()) this.headers = hdrs
                this.key = playerKey
                this.kid = playerKid
            }
        )
    }

    // ---- Helper: emit a DASH DRM link that fetches key from license server ----

    private fun emitLicenseDrmLink(
        ld: ChannelLoadData,
        hdrs: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mpd = fetchMpdManifest(ld.url, hdrs)
        val kidRegex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
        val match = kidRegex.find(mpd)
        val drmKid = match?.groups?.get(1)?.value ?: UUID.randomUUID().toString()

        val kidBytes = drmKid.replace("-", "").chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()
        val kidB64 = Base64.encodeToString(kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val keyB64 = resolveLicenseKeys(ld.licenseUrl, kidB64)
        if (keyB64.isNotEmpty()) {
            callback.invoke(
                newDrmExtractorLink(this.name, this.name, ld.url, INFER_TYPE, CLEARKEY_UUID) {
                    this.quality = Qualities.Unknown.value
                    if (hdrs.isNotEmpty()) this.headers = hdrs
                    this.key = keyB64.trim()
                    this.kid = kidB64.trim()
                }
            )
            return true
        }

        // fallback: license URL only
        callback.invoke(
            newDrmExtractorLink(this.name, this.name, ld.url, INFER_TYPE, CLEARKEY_UUID) {
                this.quality = Qualities.Unknown.value
                if (hdrs.isNotEmpty()) this.headers = hdrs
                this.licenseUrl = ld.licenseUrl.trim()
            }
        )
        return true
    }

    // ---- Helper: emit a plain extractor link ----

    private fun emitPlainLink(
        ld: ChannelLoadData,
        hdrs: Map<String, String>,
        linkType: ExtractorLinkType,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(this.name, this.name, url = ld.url, linkType) {
                this.referer = ""
                this.quality = Qualities.Unknown.value
                if (hdrs.isNotEmpty()) this.headers = hdrs
            }
        )
    }

    // ---- loadLinks ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<ChannelLoadData>(data)

        when {
            ld.url.contains("mpd") -> {
                val hdrs = buildHeaders(ld)

                val hasDirectKeys = ld.key.isNotEmpty() && ld.keyid.isNotEmpty() &&
                    ld.key.trim() != "null" && ld.keyid.trim() != "null"
                val hasLicense = ld.licenseUrl.isNotEmpty() && ld.licenseUrl.trim() != "null"

                if (hasDirectKeys) {
                    var normKey = ld.key.b64ToHexOrNull() ?: ld.key.trim()
                    var normKid = ld.keyid.b64ToHexOrNull() ?: ld.keyid.trim()

                    if (ld.drmKeys.isNotEmpty()) {
                        val mpd = fetchMpdManifest(ld.url, hdrs)
                        val kidRegex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                        val mpdKid = kidRegex.find(mpd)?.groups?.get(1)?.value
                            ?.replace("-", "")?.lowercase()

                        if (!mpdKid.isNullOrEmpty()) {
                            val mapped = ld.drmKeys[mpdKid]
                            if (!mapped.isNullOrEmpty()) {
                                normKid = mpdKid
                                normKey = mapped
                            }
                        }
                    }

                    val pKey = normKey.hexToB64UrlOrNull() ?: normKey
                    val pKid = normKid.hexToB64UrlOrNull() ?: normKid
                    emitDrmLink(ld, hdrs, pKey, pKid, callback)
                } else if (hasLicense) {
                    emitLicenseDrmLink(ld, hdrs, callback)
                } else {
                    emitPlainLink(ld, hdrs, ExtractorLinkType.DASH, callback)
                }
            }

            ld.url.contains("&e=.m3u") -> {
                emitPlainLink(ld, buildHeaders(ld), ExtractorLinkType.M3U8, callback)
            }

            ld.url.contains("play.php?") -> {
                val hdrs = mutableMapOf("User-Agent" to ld.userAgent)
                hdrs.putAll(ld.headers)
                if (ld.cookie.isNotEmpty()) hdrs["Cookie"] = ld.cookie
                callback.invoke(
                    newExtractorLink(this.name, this.name, url = ld.url, ExtractorLinkType.M3U8) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        this.headers = hdrs
                    }
                )
            }

            else -> {
                val hdrs = buildHeaders(ld)
                callback.invoke(
                    newExtractorLink(this.name, ld.title, url = ld.url, INFER_TYPE) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        if (hdrs.isNotEmpty()) this.headers = hdrs
                    }
                )
            }
        }
        return true
    }
}

// ============================================================
// Data classes for playlist entries
// ============================================================

data class ChannelList(
    val entries: List<ChannelEntry> = emptyList(),
)

data class ChannelEntry(
    val title: String? = null,
    val attrs: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val url: String? = null,
    val userAgent: String? = null,
    val key: String? = null,
    val keyid: String? = null,
    val cookie: String? = null,
    val licenseUrl: String? = null,
    val drmKeys: Map<String, String> = emptyMap(),
)

// ============================================================
// M3U Playlist Loader
// ============================================================

class M3uPlaylistLoader {

    class ParseError(message: String) : Exception(message) {
        class BadHeader : ParseError("Invalid file header. Header doesn't start with #EXTM3U")
    }

    // ---- Hex / Base64 helpers ----

    private fun String.asHexOrNull(): String? {
        val s = replace("-", "").trim()
        if (s.isBlank() || s.length % 2 != 0) return null
        return if (s.matches(Regex("^[0-9a-fA-F]+$"))) s.lowercase() else null
    }

    private fun String.b64decodeToHexOrNull(): String? {
        val fixed = trim()
            .replace('-', '+')
            .replace('_', '/')
            .let { v ->
                val pad = (4 - (v.length % 4)) % 4
                v + "=".repeat(pad)
            }
        return runCatching {
            val raw = Base64.decode(fixed, Base64.DEFAULT)
            raw.joinToString(separator = "") { b -> "%02x".format(b) }
        }.getOrNull()
    }

    private fun String.normalizeDrmOrNull(): String? {
        val t = trim()
        if (t.isEmpty() || t.equals("null", ignoreCase = true)) return null
        return t.asHexOrNull() ?: t.b64decodeToHexOrNull()
    }

    // ---- DRM key parsing ----

    private fun parseKeyMap(licenseKey: String): Map<String, String> {
        val t = licenseKey.trim()
        if (!t.startsWith("{")) return emptyMap()
        return runCatching {
            val json = JSONObject(t)
            val arr = json.optJSONArray("keys") ?: return emptyMap()
            val result = mutableMapOf<String, String>()
            for (idx in 0 until arr.length()) {
                val item = arr.optJSONObject(idx) ?: continue
                val kid = item.optString("kid").normalizeDrmOrNull()
                val k = item.optString("k").normalizeDrmOrNull()
                if (!kid.isNullOrEmpty() && !k.isNullOrEmpty()) {
                    result[kid] = k
                }
            }
            result
        }.getOrElse { emptyMap() }
    }

    private fun parseKeyPair(licenseKey: String): Pair<String?, String?>? {
        val t = licenseKey.trim()
        if (t.isEmpty()) return null

        if (t.startsWith("{")) {
            return runCatching {
                val json = JSONObject(t)
                val arr = json.optJSONArray("keys") ?: return null
                for (idx in 0 until arr.length()) {
                    val item = arr.optJSONObject(idx) ?: continue
                    val kid = item.optString("kid").normalizeDrmOrNull()
                    val k = item.optString("k").normalizeDrmOrNull()
                    if (kid != null || k != null) return k to kid
                }
                null
            }.getOrNull()
        }

        val parts = when {
            t.contains(":") -> t.split(":", limit = 2)
            t.contains(",") -> t.split(",", limit = 2)
            else -> return null
        }
        if (parts.size != 2) return null
        val keyId = parts[0].trim().normalizeDrmOrNull()
        val key = parts[1].trim().normalizeDrmOrNull()
        return key to keyId
    }

    // ---- Public API ----

    fun load(content: String): ChannelList = load(content.byteInputStream())

    @Throws(ParseError::class)
    fun load(input: InputStream): ChannelList {
        val lines = input.bufferedReader().readLines()
        val entries = mutableListOf<ChannelEntry>()

        // Accumulators
        var accCookie: String? = null
        var accUa: String? = null
        var accHeaders: Map<String, String> = emptyMap()
        var accKey: String? = null
        var accKid: String? = null
        var accLicenseUrl: String? = null
        var accDrmKeys: Map<String, String> = emptyMap()
        var accTitle: String? = null
        var accAttrs: Map<String, String> = emptyMap()

        var idx = 0
        while (idx < lines.size) {
            val ln = lines[idx].trim()

            if (ln.isNotEmpty()) {
                when {
                    ln.startsWith(PlayZTV.TAG_EXTINF) -> {
                        accTitle = ln.extractTitle()
                        accAttrs = ln.extractAttributes()

                        val attrKey = accAttrs["key"] ?: accAttrs["drm-key"]
                        val attrKid = accAttrs["keyid"] ?: accAttrs["drm-keyid"] ?: accAttrs["kid"]
                        if (accKey == null) accKey = attrKey
                        if (accKid == null) accKid = attrKid
                    }
                    ln.startsWith("#EXTHTTP:") -> {
                        val json = ln.removePrefix("#EXTHTTP:").trim()
                        runCatching {
                            val map = parseJson<Map<String, String>>(json)
                            if (map.containsKey("cookie")) accCookie = map["cookie"]
                            if (map.containsKey("user-agent")) accUa = map["user-agent"]
                        }
                    }
                    ln.startsWith(PlayZTV.TAG_EXTVLCOPT) -> {
                        val ua = ln.tagValue("http-user-agent")
                        val ref = ln.tagValue("http-referrer") ?: ln.tagValue("http-referer")
                        if (ua != null) accUa = ua
                        if (ref != null) accHeaders = accHeaders + ("Referer" to ref)
                    }
                    ln.startsWith("#KODIPROP:inputstream.adaptive.license_key=") -> {
                        val licKey = ln.removePrefix("#KODIPROP:inputstream.adaptive.license_key=").trim()
                        if (licKey.startsWith("http://") || licKey.startsWith("https://")) {
                            accLicenseUrl = licKey
                        } else {
                            if (licKey.startsWith("{")) {
                                val parsed = parseKeyMap(licKey)
                                if (parsed.isNotEmpty()) {
                                    accDrmKeys = parsed
                                    val first = parsed.entries.firstOrNull()
                                    if (first != null) {
                                        if (accKey == null) accKey = first.value
                                        if (accKid == null) accKid = first.key
                                    }
                                }
                                val pair = parseKeyPair(licKey)
                                if (pair != null) {
                                    val (k, kid) = pair
                                    if (k != null) accKey = k
                                    if (kid != null) accKid = kid
                                }
                            } else {
                                val parts = when {
                                    licKey.contains(":") -> licKey.split(":")
                                    licKey.contains(",") -> licKey.split(",")
                                    else -> listOf(licKey)
                                }

                                val kidBytes = parts.getOrNull(0)
                                    ?.replace("-", "")
                                    ?.chunked(2)
                                    ?.mapNotNull { runCatching { it.toInt(16).toByte() }.getOrNull() }
                                    ?.toByteArray()

                                val keyBytes = parts.getOrNull(1)
                                    ?.replace("-", "")
                                    ?.chunked(2)
                                    ?.mapNotNull { runCatching { it.toInt(16).toByte() }.getOrNull() }
                                    ?.toByteArray()

                                val kidB64 = if (kidBytes != null && kidBytes.isNotEmpty()) {
                                    Base64.encodeToString(kidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                                } else null

                                val keyB64 = if (keyBytes != null && keyBytes.isNotEmpty()) {
                                    Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                                } else null

                                if (keyB64 != null) accKey = keyB64
                                if (kidB64 != null) accKid = kidB64
                            }
                        }
                    }
                    !ln.startsWith("#") -> {
                        // Handle multi-line URLs
                        var combined = ln
                        var next = idx + 1
                        while (next < lines.size &&
                            !lines[next].trim().startsWith("#") &&
                            lines[next].trim().isNotEmpty()
                        ) {
                            combined += lines[next].trim()
                            next++
                        }
                        idx = next - 1

                        val entryUrl = combined.extractUrl()
                        val urlUa = combined.urlParam("user-agent")
                        val urlRef = combined.urlParam("referer")
                        val urlRefAlias = combined.urlParam("referrer")
                        val urlCookie = combined.urlParam("cookie")
                        val urlOrigin = combined.urlParam("origin")
                        val urlKey = combined.urlParam("key")
                        val urlKid = combined.urlParam("keyid")
                        val urlLic = combined.urlParam("licenseUrl")

                        var finalHeaders = accHeaders
                        val resolvedRef = urlRef ?: urlRefAlias
                        if (resolvedRef != null) finalHeaders = finalHeaders + ("Referer" to resolvedRef)
                        if (urlOrigin != null) finalHeaders = finalHeaders + ("Origin" to urlOrigin)

                        val entry = ChannelEntry(
                            title = accTitle ?: "Unknown Channel",
                            attrs = accAttrs,
                            url = entryUrl,
                            headers = finalHeaders,
                            userAgent = urlUa ?: accUa,
                            cookie = urlCookie ?: accCookie,
                            key = urlKey ?: accKey,
                            keyid = urlKid ?: accKid,
                            licenseUrl = urlLic ?: accLicenseUrl,
                            drmKeys = accDrmKeys
                        )
                        entries.add(entry)

                        // Reset accumulators
                        accCookie = null
                        accUa = null
                        accHeaders = emptyMap()
                        accKey = null
                        accKid = null
                        accLicenseUrl = null
                        accDrmKeys = emptyMap()
                        accTitle = null
                        accAttrs = emptyMap()
                    }
                }
            }
            idx++
        }
        return ChannelList(entries)
    }

    // ---- String utilities ----

    private fun String.stripQuotes(): String = replace("\"", "").trim()

    private fun String.extractTitle(): String? {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val after = replace(extInfRegex, "").trim()

        var lastComma = -1
        var inQuotes = false
        for (i in after.indices) {
            when (after[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) lastComma = i
            }
        }
        return if (lastComma != -1 && lastComma < after.length - 1) {
            after.substring(lastComma + 1).trim().stripQuotes()
        } else {
            after.split(",").lastOrNull()?.stripQuotes()
        }
    }

    private fun String.extractUrl(): String? = split("|").firstOrNull()?.stripQuotes()

    private fun String.urlParam(key: String): String? {
        val urlRegex = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val params = replace(urlRegex, "").stripQuotes()
        for (part in params.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim().equals(key, ignoreCase = true)) {
                return kv[1].trim().stripQuotes()
            }
        }
        return null
    }

    private fun String.extractAttributes(): Map<String, String> {
        val extInfRegex = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val after = replace(extInfRegex, "").trim()

        var lastComma = -1
        var inQuotes = false
        for (i in after.indices) {
            when (after[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) lastComma = i
            }
        }
        val attrPart = if (lastComma != -1) after.substring(0, lastComma).trim() else after.trim()

        val result = mutableMapOf<String, String>()
        val attrRegex = Regex("""(\w[-\w]*)\s*=\s*(?:"([^"]*)"|([^\s,]+))""", RegexOption.IGNORE_CASE)
        for (match in attrRegex.findAll(attrPart)) {
            val k = match.groups[1]?.value ?: ""
            val v = match.groups[2]?.value ?: match.groups[3]?.value ?: ""
            if (k.isNotEmpty()) result[k] = v.trim()
        }
        return result
    }

    private fun String.tagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)
        return keyRegex.find(this)?.groups?.get(1)?.value?.stripQuotes()
    }
}
