package com.playfy

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * PlayFy TV CloudStream Provider
 *
 * Fetches live TV channels from globalmediapoint.online.
 * All API responses are AES-encrypted; decryption is handled by PlayFyCryptoUtils
 * using the PlayFy TV native library (loaded from the installed APK at runtime).
 *
 * Stream data structure (after decryption):
 *   JSON array of objects:
 *     { "id": 1, "title": "...", "link": "url|headers", "api": "KID:KEY", "type": "0|1" }
 *
 *   type=0 → plain HLS/DASH stream (no DRM)
 *   type=1 → DASH with ClearKey DRM; api field = "KID_hex:KEY_hex"
 */
class PlayFyProvider : MainAPI() {

    companion object {
        private const val TAG = "PlayFyProvider"
        private const val BASE_URL = "https://globalmediapoint.online"
        private val DEFAULT_HEADERS = mapOf(
            "User-Agent"    to "okhttp/4.9.2",
            "Accept"        to "application/json",
            "Cache-Control" to "no-cache"
        )
    }

    override var mainUrl  = BASE_URL
    override var name     = "PlayFy TV"
    override var lang     = "en"
    override val hasMainPage          = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    private val httpClient by lazy { OkHttpClient.Builder().build() }

    private fun fetchAndDecrypt(url: String, context: Context): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .apply { DEFAULT_HEADERS.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            val bodyStr = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "HTTP ${resp.code} for $url")
                    return null
                }
                resp.body.string()
            }

            // Response is { "data": "<encrypted>" } or a plain JSON
            val encryptedData = try {
                JSONObject(bodyStr).optString("data", "")
            } catch (_: Exception) {
                return bodyStr  // already plain JSON
            }

            if (encryptedData.isBlank()) {
                Log.e(TAG, "Empty data field from $url")
                return null
            }

            PlayFyCryptoUtils.decrypt(encryptedData, context)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndDecrypt($url) error: ${e.message}")
            null
        }
    }

    private data class PlayFyCat(
        val id: Int,
        val name: String,
        val image: String,
        val channelsId: Int
    )

    private data class PlayFyStream(
        val title: String,
        val link: String,    // may contain "url|Header=value&Header2=value2"
        val api: String,     // "KID_hex:KEY_hex" for DRM, or ""
        val type: String     // "0" = plain, "1" = ClearKey DRM
    )

    private fun parseCategories(json: String): List<PlayFyCat> {
        val result = mutableListOf<PlayFyCat>()
        try {
            // Try as JSON array first
            val arr = try {
                JSONArray(json)
            } catch (_: Exception) {
                // Try as JSON object containing lists
                val obj = JSONObject(json)
                val k = obj.keys()
                while (k.hasNext()) {
                    val key = k.next()
                    val v = obj.optJSONArray(key) ?: continue
                    for (i in 0 until v.length()) {
                        val cat = v.optJSONObject(i) ?: continue
                        result.add(
                            PlayFyCat(
                                id          = cat.optInt("id", result.size),
                                name        = cat.optString("name", key),
                                image       = cat.optString("image", ""),
                                channelsId  = cat.optInt("channelsId", cat.optInt("id", result.size))
                            )
                        )
                    }
                }
                return result
            }

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    PlayFyCat(
                        id          = obj.optInt("id", i),
                        name        = obj.optString("name", "Category $i"),
                        image       = obj.optString("image", ""),
                        channelsId  = obj.optInt("channelsId", obj.optInt("id", i))
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseCategories error: ${e.message}")
        }
        return result
    }

    private fun parseStreams(json: String): List<PlayFyStream> {
        val result = mutableListOf<PlayFyStream>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val link = obj.optString("link", "").trim()
                if (link.isBlank()) continue
                result.add(
                    PlayFyStream(
                        title = obj.optString("title", "Stream ${i + 1}"),
                        link  = link,
                        api   = obj.optString("api", ""),
                        type  = obj.optString("type", "0")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseStreams error: ${e.message}")
        }
        return result
    }

    /**
     * Split "https://url.com/stream.mpd|User-Agent=Mozilla&Referer=https://example.com"
     * into (url, headers).
     */
    private fun splitLinkAndHeaders(link: String): Pair<String, Map<String, String>> {
        val parts = link.split("|", limit = 2)
        val url = parts[0].trim()
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        if (parts.size > 1) {
            parts[1].split("&").forEach { param ->
                val eq = param.indexOf('=')
                if (eq > 0) {
                    val key = param.substring(0, eq).trim()
                    val value = param.substring(eq + 1).trim()
                    headers[key] = value
                }
            }
        }
        return Pair(url, headers)
    }

    /**
     * Parse "KID_hex:KEY_hex" → Pair(kid_base64url, key_base64url) for ClearKey.
     */
    private fun parseClearKey(api: String): Pair<String, String>? {
        if (api.isBlank()) return null
        val colon = api.indexOf(':')
        if (colon < 0) return null
        val kidHex = api.substring(0, colon).trim().replace("-", "").lowercase()
        val keyHex = api.substring(colon + 1).trim().replace("-", "").lowercase()
        if (kidHex.length < 32 || keyHex.length < 32) return null

        fun hexToBase64Url(hex: String): String {
            val bytes = ByteArray(hex.length / 2) {
                hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )
        }

        return Pair(hexToBase64Url(kidHex), hexToBase64Url(keyHex))
    }

    private fun getQuality(title: String): Int {
        val t = title.uppercase()
        return when {
            "4K" in t || "UHD" in t || "2160" in t -> Qualities.P2160.value
            "FHD" in t || "1080" in t               -> Qualities.P1080.value
            "HD" in t || "720" in t                 -> Qualities.P720.value
            "SD" in t || "480" in t                 -> Qualities.P480.value
            else                                    -> Qualities.Unknown.value
        }
    }

    private suspend fun addStreamLinks(stream: PlayFyStream, callback: (ExtractorLink) -> Unit) {
        val (url, headers) = splitLinkAndHeaders(stream.link)
        if (url.isBlank()) return

        val quality  = getQuality(stream.title)
        val referer  = headers["Referer"] ?: headers["referer"] ?: ""
        val isDash   = url.contains(".mpd", ignoreCase = true)

        if (stream.type == "1") {
            // ClearKey DRM stream
            val clearKey = parseClearKey(stream.api)
            if (clearKey != null) {
                val (kid, key) = clearKey
                callback.invoke(
                    newDrmExtractorLink(
                        source   = stream.title,
                        name     = stream.title,
                        url      = url,
                        type     = if (isDash) ExtractorLinkType.DASH else INFER_TYPE,
                        uuid     = CLEARKEY_UUID
                    ) {
                        this.quality  = quality
                        this.headers  = headers
                        this.referer  = referer
                        this.kid      = kid
                        this.key      = key
                    }
                )
            } else {
                // api field present but couldn't parse as KID:KEY — add as plain
                callback.invoke(
                    newExtractorLink(
                        source = stream.title,
                        name   = stream.title,
                        url    = url,
                        type   = if (isDash) ExtractorLinkType.DASH else INFER_TYPE
                    ) {
                        this.quality = quality
                        this.headers = headers
                        this.referer = referer
                    }
                )
            }
        } else {
            // Plain HLS or DASH
            callback.invoke(
                newExtractorLink(
                    source = stream.title,
                    name   = stream.title,
                    url    = url,
                    type   = when {
                        isDash                                      -> ExtractorLinkType.DASH
                        url.contains(".m3u8", ignoreCase = true)    -> ExtractorLinkType.M3U8
                        url.contains(".m3u", ignoreCase = true)     -> ExtractorLinkType.M3U8
                        else                                        -> INFER_TYPE
                    }
                ) {
                    this.quality = quality
                    this.headers = headers
                    this.referer = referer
                }
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val ctx = CloudStreamApp.context ?: run {
            Log.e(TAG, "No application context available")
            return null
        }

        if (!PlayFyCryptoUtils.isPlayFyInstalled(ctx)) {
            Log.w(TAG, "PlayFy TV app is not installed — native decryption unavailable")
        }

        val decrypted = fetchAndDecrypt("$BASE_URL/cats/live-events.json", ctx)

        if (decrypted == null) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        name = "PlayFy TV",
                        list = listOf(
                            newMovieSearchResponse(
                                name = "⚠️ Install PlayFy TV app for full access",
                                url  = "",
                                type = TvType.Live
                            )
                        )
                    )
                ),
                hasNext = false
            )
        }

        val categories = parseCategories(decrypted)
        if (categories.isEmpty()) {
        }

        val items = categories.map { cat ->
            HomePageList(
                name = cat.name,
                list = listOf(
                    newMovieSearchResponse(
                        name  = cat.name,
                        url   = "$BASE_URL/channels/${cat.channelsId}.json",
                        type  = TvType.Live
                    ) {
                        this.posterUrl     = cat.image.ifBlank { null }
                        this.posterHeaders = DEFAULT_HEADERS
                    }
                )
            )
        }

        return newHomePageResponse(items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val ctx = CloudStreamApp.context ?: return emptyList()
        val decrypted = fetchAndDecrypt("$BASE_URL/cats/live-events.json", ctx) ?: return emptyList()

        return parseCategories(decrypted)
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { cat ->
                newMovieSearchResponse(
                    name  = cat.name,
                    url   = "$BASE_URL/channels/${cat.channelsId}.json",
                    type  = TvType.Live
                ) {
                    this.posterUrl     = cat.image.ifBlank { null }
                    this.posterHeaders = DEFAULT_HEADERS
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfterLast("/").removeSuffix(".json")
        return newMovieLoadResponse(
            name = "PlayFy TV – Channel $channelId",
            url  = url,
            type = TvType.Live,
            data = url
        ) {
            this.plot    = "Live TV from PlayFy TV"
            this.apiName = this@PlayFyProvider.name
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ctx = CloudStreamApp.context ?: run {
            Log.e(TAG, "No context for loadLinks")
            return false
        }

        val decrypted = fetchAndDecrypt(data, ctx) ?: run {
            Log.e(TAG, "Failed to decrypt stream list from $data")
            return false
        }

        val streams = parseStreams(decrypted)
        if (streams.isEmpty()) {
            return false
        }

        var count = 0
        streams.forEach { stream ->
            try {
                addStreamLinks(stream, callback)
                count++
            } catch (e: Exception) {
                Log.e(TAG, "Error adding ${stream.title}: ${e.message}")
            }
        }

        Log.d(TAG, "Added $count links from ${streams.size} streams")
        return count > 0
    }
}
