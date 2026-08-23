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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class LIVETV(
    private val customName: String = "IPTV Player",
    private val customMainUrl: String = "https://fifabd.site/OPLLX7/LIVE2.m3u"
) : MainAPI() {

    companion object {
        var context: android.content.Context? = null
        const val EXT_M3U = "#EXTM3U"
        const val EXT_INF = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }

    override var lang = "ta"
    override var mainUrl = customMainUrl
    override var name = customName
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Live,
    )

    private val headers = mapOf(
        "accept" to "*/*",
        "Cache-Control" to "no-cache, no-store",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; rv:78.0) Gecko/20100101 Firefox/78.0",
    )

    private val customHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(headers))
            .build()
    }

    private suspend fun getWithCustomHeaders(url: String): String {
        val dynamicHeaders = headers.toMutableMap()
        var hasCustomHeaders = false
        val finalUrl: String

        if (!url.contains("|")) {
            finalUrl = url
        } else {
            val parts = url.split("|", limit = 2)
            finalUrl = parts[0]
            val headersPart = parts.getOrNull(1).orEmpty()
            headersPart.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val key = kv[0].trim()
                    val value = kv[1].trim()
                    val existingKey = dynamicHeaders.keys.firstOrNull { it.equals(key, ignoreCase = true) }
                    if (existingKey != null) dynamicHeaders.remove(existingKey)
                    dynamicHeaders[key] = value
                    hasCustomHeaders = true
                }
            }
        }

        val request = Request.Builder()
            .url(finalUrl)
            .build()

        val client = if (hasCustomHeaders) {
            OkHttpClient.Builder()
                .addInterceptor(HeaderReplacementInterceptor(dynamicHeaders))
                .build()
        } else {
            customHttpClient
        }

        return client.newCall(request).execute().use { response ->
            response.body.string()
        }
    }

    private fun String.base64ToHexOrNull(): String? {
        val raw = trim()
        val normalizedHex = raw.replace("-", "")
        if (normalizedHex.isNotEmpty() && normalizedHex.length % 2 == 0 &&
            normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))
        ) {
            return normalizedHex.lowercase()
        }

        return try {
            val normalized = raw
                .replace('-', '+')
                .replace('_', '/')
                .let { value ->
                    val padding = (4 - (value.length % 4)) % 4
                    value + "=".repeat(padding)
                }
            val decoded = Base64.decode(normalized, Base64.DEFAULT)
            decoded.joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.hexToBase64UrlOrNull(): String? {
        val normalizedHex = trim().replace("-", "")
        if (normalizedHex.isEmpty() || normalizedHex.length % 2 != 0 ||
            !normalizedHex.matches(Regex("^[0-9a-fA-F]+$"))
        ) {
            return null
        }

        return try {
            val bytes = normalizedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptContent(content: String): String {
        return try {
            if (content.startsWith(EXT_M3U) || content.startsWith(EXT_INF) ||
                content.startsWith("#KODIPROP")
            ) {
                return content
            }

            val trimmedContent = content.trim()

            if (trimmedContent.length < 79) {
                return trimmedContent
            }

            val part1 = trimmedContent.substring(0, 10)
            val part2 = trimmedContent.substring(34, trimmedContent.length - 54)
            val part3 = trimmedContent.substring(trimmedContent.length - 10)
            val encryptedData = part1 + part2 + part3

            val ivBase64 = trimmedContent.substring(10, 34)
            val keyBase64 = trimmedContent.substring(trimmedContent.length - 54, trimmedContent.length - 10)

            val iv = Base64.decode(ivBase64, Base64.DEFAULT)
            val key = Base64.decode(keyBase64, Base64.DEFAULT)
            val encrypted = Base64.decode(encryptedData, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            content
        }
    }

    private fun getMpdStream(url: String, customHeaders: Map<String, String>): String {
        val client = OkHttpClient.Builder()
            .addInterceptor(HeaderReplacementInterceptor(customHeaders))
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        return client.newCall(request).execute().use { response ->
            response.body.string()
        }
    }

    private fun getDRMKeysFromLicenseServer(url: String, kid: String): String {
        val userAgent = "Dalvik/2.1.0 (Linux; U; Android)"
        val client = OkHttpClient.Builder()
            .addInterceptor(
                HeaderReplacementInterceptor(
                    mapOf(
                        "User-Agent" to userAgent,
                        "Content-Type" to "application/json;charset=UTF-8",
                    )
                )
            )
            .addInterceptor(LoggingInterceptor())
            .build()

        val json = "{\"kids\":[\"$kid\"],\"type\":\"temporary\"}"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            val responseString = response.body.string()
            val jsonResponse = parseJson<Map<String, Any>>(responseString)
            @Suppress("UNCHECKED_CAST")
            val keys = jsonResponse["keys"] as? List<Map<String, String>> ?: return ""
            val firstKey = keys.firstOrNull() ?: return ""
            firstKey["k"] ?: ""
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val rawContent = getWithCustomHeaders(mainUrl)
        val decryptedContent = decryptContent(rawContent)
        val data = IptvPlaylistParser().parseM3U(decryptedContent)
        return newHomePageResponse(
            data.items.groupBy { it.attributes["group-title"] }.map { group ->
                val title = group.key ?: ""
                val show = group.value.map { channel ->
                    val streamurl = channel.url.toString()
                    val channelname = channel.title.toString()
                    val posterurl = channel.attributes["tvg-logo"].toString()
                    val nation = channel.attributes["group-title"].toString()
                    val key = channel.key ?: ""
                    val keyid = channel.keyid ?: ""
                    val userAgent = channel.userAgent ?: ""
                    val cookie = channel.cookie ?: ""
                    val licenseUrl = channel.licenseUrl ?: ""
                    val channelHeaders = channel.headers
                    newLiveSearchResponse(
                        channelname,
                        LoadData(
                            streamurl, channelname, posterurl, nation,
                            key, keyid, userAgent, cookie, licenseUrl,
                            channel.drmKeys, channelHeaders
                        ).toJson(),
                        TvType.Live
                    ) {
                        this.posterUrl = posterurl
                        this.apiName
                        this.lang = channel.attributes["group-title"]
                    }
                }
                HomePageList(
                    title,
                    show,
                    isHorizontalImages = true
                )
            }, false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val rawContent = getWithCustomHeaders(mainUrl)
        val decryptedContent = decryptContent(rawContent)
        val data = IptvPlaylistParser().parseM3U(decryptedContent)
        return data.items.filter { it.title?.contains(query, ignoreCase = true) ?: false }
            .map { channel ->
                val streamurl = channel.url.toString()
                val channelname = channel.title.toString()
                val posterurl = channel.attributes["tvg-logo"].toString()
                val nation = channel.attributes["group-title"].toString()
                val key = channel.key ?: ""
                val keyid = channel.keyid ?: ""
                val userAgent = channel.userAgent ?: ""
                val cookie = channel.cookie ?: ""
                val licenseUrl = channel.licenseUrl ?: ""
                newLiveSearchResponse(
                    channelname,
                    LoadData(
                        streamurl, channelname, posterurl, nation,
                        key, keyid, userAgent, cookie, licenseUrl,
                        channel.drmKeys, channel.headers
                    ).toJson(),
                    TvType.Live
                ) {
                    this.posterUrl = posterurl
                    this.apiName
                    this.lang = channel.attributes["group-title"]
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LoadData>(url)
        return newLiveStreamLoadResponse(data.title, url, url) {
            this.posterUrl = data.poster
            this.plot = data.nation
        }
    }

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
        val headers: Map<String, String>,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadData>(data)

        if (loadData.url.contains("mpd")) {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
            }
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }

            val hasValidKeys = loadData.key.isNotEmpty() && loadData.keyid.isNotEmpty() &&
                loadData.key.trim() != "null" && loadData.keyid.trim() != "null"
            val hasLicenseUrl = loadData.licenseUrl.isNotEmpty() &&
                loadData.licenseUrl.trim() != "null"

            if (hasValidKeys) {
                var normalizedKey = loadData.key.base64ToHexOrNull() ?: loadData.key.trim()
                var normalizedKid = loadData.keyid.base64ToHexOrNull() ?: loadData.keyid.trim()

                if (loadData.drmKeys.isNotEmpty()) {
                    val mpdStr = getMpdStream(url = loadData.url, customHeaders = headers)
                    val regex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                    val mpdKidHex = regex.find(mpdStr)
                        ?.groups?.get(1)?.value
                        ?.replace("-", "")
                        ?.lowercase()

                    if (!mpdKidHex.isNullOrEmpty()) {
                        val mappedKey = loadData.drmKeys[mpdKidHex]
                        if (!mappedKey.isNullOrEmpty()) {
                            normalizedKid = mpdKidHex
                            normalizedKey = mappedKey
                        }
                    }
                }

                val playerKey = normalizedKey.hexToBase64UrlOrNull() ?: normalizedKey
                val playerKid = normalizedKid.hexToBase64UrlOrNull() ?: normalizedKid

                callback.invoke(
                    newDrmExtractorLink(
                        this.name, this.name, loadData.url,
                        INFER_TYPE, CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.Unknown.value
                        if (headers.isNotEmpty()) {
                            this.headers = headers
                        }
                        this.key = playerKey
                        this.kid = playerKid
                    }
                )
            } else if (hasLicenseUrl) {
                val mpdStr = getMpdStream(url = loadData.url, customHeaders = headers)
                val regex = Regex("""cenc:default_KID=["']([0-9a-fA-F\-]{36})["']""")
                val mpdKid = regex.find(mpdStr)?.groups?.get(1)?.value
                val drmKidBase64 = mpdKid?.replace("-", "")
                    ?.chunked(2)
                    ?.map { it.toInt(16).toByte() }
                    ?.toByteArray()
                    ?.let { bytes ->
                        Base64.encodeToString(
                            bytes,
                            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                        )
                    }

                if (drmKidBase64 != null) {
                    val keyBase64 = getDRMKeysFromLicenseServer(
                        url = loadData.licenseUrl,
                        kid = drmKidBase64
                    )
                    if (keyBase64.isNotEmpty()) {
                        callback.invoke(
                            newDrmExtractorLink(
                                this.name, this.name, loadData.url,
                                INFER_TYPE, CLEARKEY_UUID
                            ) {
                                this.quality = Qualities.Unknown.value
                                if (headers.isNotEmpty()) {
                                    this.headers = headers
                                }
                                this.key = keyBase64.trim()
                                this.kid = drmKidBase64.trim()
                            }
                        )
                        return true
                    }
                }

                // No KID in the MPD or no key returned by the license server:
                // pass the license URL through so the player runs the license
                // exchange itself instead of guessing a key.
                callback.invoke(
                    newDrmExtractorLink(
                        this.name, this.name, loadData.url,
                        INFER_TYPE, CLEARKEY_UUID
                    ) {
                        this.quality = Qualities.Unknown.value
                        if (headers.isNotEmpty()) {
                            this.headers = headers
                        }
                        this.licenseUrl = loadData.licenseUrl.trim()
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(
                        this.name, this.name, loadData.url,
                        ExtractorLinkType.DASH
                    ) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                        if (headers.isNotEmpty()) {
                            this.headers = headers
                        }
                    }
                )
            }
        } else if (loadData.url.contains("&e=.m3u")) {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
            }
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }
            callback.invoke(
                newExtractorLink(
                    this.name, this.name, loadData.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                }
            )
        } else if (loadData.url.contains("play.php?")) {
            val headers = mutableMapOf("User-Agent" to loadData.userAgent)
            headers.putAll(loadData.headers)
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }
            callback.invoke(
                newExtractorLink(
                    this.name, this.name, loadData.url,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } else {
            val headers = mutableMapOf<String, String>()
            headers.putAll(loadData.headers)
            if (loadData.userAgent.isNotEmpty()) {
                headers["User-Agent"] = loadData.userAgent
            }
            if (loadData.cookie.isNotEmpty()) {
                headers["Cookie"] = loadData.cookie
            }
            callback.invoke(
                newExtractorLink(
                    this.name, loadData.title, loadData.url,
                    INFER_TYPE
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    if (headers.isNotEmpty()) {
                        this.headers = headers
                    }
                }
            )
        }
        return true
    }
}
