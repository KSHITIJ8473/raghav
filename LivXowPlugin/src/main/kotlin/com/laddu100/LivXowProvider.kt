package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LivXow Provider - Live Sports & TV Streaming
 *
 * Reverse-engineered from the LivXow Android APK (com.livxow.tv).
 *
 * API Flow:
 *   1. Config: {base_url}app.txt -> encrypted -> JSON array -> first element is app config
 *   2. Channel data: Each channel's "link" field -> encrypted -> JSON array -> stream objects
 *   3. Decryption: Character substitution cipher (rc.a.b equivalent) with AES fallback
 *   4. Stream objects: { name, link, api, tokenApi, audio, scheme, secure_decoder }
 *   5. Headers: URL may contain | followed by &-separated key=value pairs
 */
class LivXowProvider : MainAPI() {
    override var mainUrl = "https://hshshebegge.store/"
    override var name = "LivXow"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    // ==================== DECRYPTION: Character Substitution Cipher ====================

    /**
     * Character substitution cipher used by the LivXow app (rc.a.b).
     * Maps from the shuffled alphabet back to the standard alphabet.
     *
     * Standard:  aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ
     * Shuffled:  fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS
     * Non-alphabetic characters pass through unchanged.
     */
    private val standardAlphabet = charArrayOf(
        'a', 'A', 'b', 'B', 'c', 'C', 'd', 'D', 'e', 'E', 'f', 'F', 'g', 'G',
        'h', 'H', 'i', 'I', 'j', 'J', 'k', 'K', 'l', 'L', 'm', 'M', 'n', 'N',
        'o', 'O', 'p', 'P', 'q', 'Q', 'r', 'R', 's', 'S', 't', 'T', 'u', 'U',
        'v', 'V', 'w', 'W', 'x', 'X', 'y', 'Y', 'z', 'Z'
    )

    private val shuffledAlphabet = charArrayOf(
        'f', 'F', 'g', 'G', 'j', 'J', 'k', 'K', 'a', 'A', 'p', 'P', 'b', 'B',
        'm', 'M', 'o', 'O', 'z', 'Z', 'e', 'E', 'n', 'N', 'c', 'C', 'd', 'D',
        'r', 'R', 'q', 'Q', 't', 'T', 'v', 'V', 'u', 'U', 'x', 'X', 'h', 'H',
        'i', 'I', 'w', 'W', 'y', 'Y', 'l', 'L', 's', 'S'
    )

    // Decode table: shuffled char -> standard char
    private val decodeTable = CharArray(128) { it.toChar() }

    init {
        for (i in standardAlphabet.indices) {
            decodeTable[shuffledAlphabet[i].code] = standardAlphabet[i]
        }
    }

    /**
     * Decrypts data using the character substitution cipher (rc.a.b equivalent).
     */
    private fun decryptSubstitution(str: String): String {
        return String(CharArray(str.length) { i -> decodeTable[str[i].code] })
    }

    // ==================== DECRYPTION: AES/CBC (b.k equivalent) ====================

    /**
     * AES/CBC/PKCS5Padding decryption used by the app (b.k method).
     * Key: "l2K5wB8xC1wP7rK1", IV: "n0K4nP8uB8hH1l18"
     * Input is Base64-encoded ciphertext.
     */
    private fun decryptAes(str: String): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val key = SecretKeySpec("l2K5wB8xC1wP7rK1".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("n0K4nP8uB8hH1l18".toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(Base64.getDecoder().decode(str)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Attempts to decrypt using the most appropriate method.
     * First tries substitution cipher, then AES as fallback.
     * Returns original string if it's already plain JSON.
     */
    private fun decrypt(str: String): String {
        if (str.startsWith("[") || str.startsWith("{")) {
            return str  // Already plain JSON
        }
        // Try substitution cipher first (used by PlayerActivity.C() and most API responses)
        val subResult = decryptSubstitution(str)
        if (subResult.startsWith("[") || subResult.startsWith("{")) {
            return subResult
        }
        // Try AES as fallback
        val aesResult = decryptAes(str)
        if (aesResult != null && (aesResult.startsWith("[") || aesResult.startsWith("{"))) {
            return aesResult
        }
        // Return substitution result as last resort
        return subResult
    }

    // ==================== HEADERS ====================

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    /**
     * Parses headers from the pipe-separated format used by the app.
     * Format: "key1=value1&key2=value2&user-agent=Mozilla..."
     * Mirrors the b.s() method in the decompiled app.
     *
     * Note: "user-agent" is mapped to "User-Agent" (case-insensitive).
     */
    private fun parseHeaders(headerStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in headerStr.split("&")) {
            val eqIndex = part.indexOf('=')
            if (eqIndex == -1) {
                // No value - just a key
                val key = if (part.equals("user-agent", ignoreCase = true)) "User-Agent" else part
                map[key] = ""
            } else {
                val key = part.substring(0, eqIndex).trim()
                val value = part.substring(eqIndex + 1)
                val mappedKey = if (key.equals("user-agent", ignoreCase = true)) "User-Agent" else key
                map[mappedKey] = value
            }
        }
        return map
    }

    /**
     * Splits a URL that may contain pipe-separated headers.
     * Returns Pair(url, headersMap).
     * Format: "https://example.com/stream.m3u8|header1=val1&header2=val2"
     */
    private fun splitUrlAndHeaders(raw: String): Pair<String, Map<String, String>> {
        if (!raw.contains("|")) {
            return Pair(raw, emptyMap())
        }
        val pipeIndex = raw.indexOf('|')
        val url = raw.substring(0, pipeIndex)
        val headerStr = raw.substring(pipeIndex + 1)
        return Pair(url, parseHeaders(headerStr))
    }

    // ==================== HTTP HELPERS ====================

    private suspend fun fetchAndDecrypt(url: String, headers: Map<String, String> = baseHeaders): JSONArray? {
        return try {
            val response = app.get(url, headers = headers, timeout = 30L)
            if (!response.isSuccessful) return null
            val rawBody = response.text
            if (rawBody.isBlank()) return null
            val decrypted = decrypt(rawBody)
            JSONArray(decrypted)
        } catch (_: Exception) {
            null
        }
    }

    // ==================== HOME PAGE ====================

    override val mainPage = mainPageOf(
        Pair("$mainUrl", "Live Channels")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val configUrl = "${mainUrl}app.txt"

        try {
            val jsonArray = fetchAndDecrypt(configUrl) ?: return HomePageResponse(emptyList())

            // First element is the app config
            val configJson = jsonArray.optJSONObject(0)
            val homeItems = mutableListOf<HomePageList>()

            if (configJson != null) {
                // Config-only keys to skip (not channel categories)
                val configKeys = setOf(
                    "sports_slug", "new_app_version", "new_download_url",
                    "new_telegram_url", "download_tg", "email", "web_url", "cric_live",
                    "foot_live", "message", "message_url", "banner_ad", "banner_ad_action",
                    "support_ad", "support_tutorial", "support_wait_seconds",
                    "support_reopen_hours", "new_app_versions"
                )

                val keys = configJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in configKeys) continue

                    val value = configJson.opt(key)
                    if (value is JSONArray) {
                        val channelItems = parseChannelArray(value, key)
                        if (channelItems.isNotEmpty()) {
                            homeItems.add(
                                HomePageList(
                                    name = key.replace("_", " ")
                                        .replaceFirstChar { it.uppercase() },
                                    list = channelItems,
                                    isHorizontalImages = false
                                )
                            )
                        }
                    }
                }
            }

            return HomePageResponse(homeItems.ifEmpty {
                // Fallback: parse all JSON objects in the array as individual channels
                val fallbackItems = mutableListOf<SearchResponse>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val item = channelToSearchResponse(obj, i)
                    if (item != null) fallbackItems.add(item)
                }
                listOf(HomePageList("Live Channels", fallbackItems))
            })
        } catch (_: Exception) {
            return HomePageResponse(emptyList())
        }
    }

    /**
     * Parses a JSONArray of channel objects into SearchResponse items.
     * Channel fields: name/title, link, logo, grouptitle, referer, useragent, referrer, origin, cookie, drmlicense
     */
    private fun parseChannelArray(arr: JSONArray, categoryName: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val item = channelToSearchResponse(obj, i)
            if (item != null) items.add(item)
        }
        return items
    }

    /**
     * Converts a single channel JSONObject to a SearchResponse.
     */
    private fun channelToSearchResponse(obj: JSONObject, index: Int): SearchResponse? {
        val channelName = obj.optString("name", obj.optString("title", ""))
        val channelLink = obj.optString("link", "")
        val channelLogo = obj.optString("logo", "")

        if (channelName.isBlank() || channelLink.isBlank()) return null

        return newLiveSearchResponse(channelName, channelLink) {
            this.posterUrl = channelLogo.ifBlank { null }
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, MainPageRequest(mainUrl))
        val allItems = homeResponse.items.flatMap { it.list }
        return allItems.filter {
            it.name.contains(query, ignoreCase = true)
        }
    }

    // ==================== LOAD (Channel Detail) ====================

    override suspend fun load(url: String): LoadResponse {
        // For live TV, the URL is the channel's link URL
        // We return a simple load response; actual stream links are resolved in loadLinks()
        return newLiveLoadResponse("Live Channel", url) {
            this.plot = "Live streaming channel"
        }
    }

    // ==================== LOAD LINKS ====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val jsonArray = fetchAndDecrypt(data) ?: return false

            for (i in 0 until jsonArray.length()) {
                val streamObj = jsonArray.optJSONObject(i) ?: continue

                // Handle playlist format (channel + playlist)
                // From b8.h.u(): checks for "channel" and "playlist" keys
                if (streamObj.has("channel") && streamObj.has("playlist")) {
                    val playlistRaw = streamObj.getString("playlist")
                    val (playlistUrl, playlistHeaders) = splitUrlAndHeaders(playlistRaw)

                    // Fetch the playlist stream data
                    val mergedHeaders = baseHeaders.toMutableMap().apply { putAll(playlistHeaders) }
                    val playlistArray = fetchAndDecrypt(playlistUrl, mergedHeaders)
                    if (playlistArray != null) {
                        for (j in 0 until playlistArray.length()) {
                            val pStream = playlistArray.optJSONObject(j) ?: continue
                            processStreamObject(pStream, callback)
                        }
                    }
                    continue
                }

                processStreamObject(streamObj, callback)
            }

            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Processes a single stream JSON object and adds extractor links.
     * Stream object fields (pc.h model):
     *   - name: stream name
     *   - link: stream URL (may contain |headers)
     *   - api: DRM license URL (may contain |headers)
     *   - tokenApi: token API URL
     *   - audio: audio track
     *   - scheme: DRM scheme (0=ClearKey, 1=Widevine, 2=other)
     *   - secure_decoder: whether secure decoder is required
     *
     * Additional fields from b.java.o():
     *   - type: stream type (token, json, sp, ls, yt, html, embed, daddy)
     *   - link_key: JSON key to extract from response
     *   - request_type: GET or POST
     *   - request_body: POST body
     */
    private suspend fun processStreamObject(
        streamObj: JSONObject,
        callback: (ExtractorLink) -> Unit
    ) {
        val streamName = streamObj.optString("name", "Stream")
        val streamUrlRaw = streamObj.optString("link", "")
        if (streamUrlRaw.isBlank()) return

        val (actualUrl, extraHeaders) = splitUrlAndHeaders(streamUrlRaw)

        // Determine stream type from URL extension
        val type = when {
            actualUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            actualUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.MPD
            else -> ExtractorLinkType.VIDEO
        }

        // Merge headers
        val mergedHeaders = baseHeaders.toMutableMap().apply { putAll(extraHeaders) }

        // Get DRM info from pc.h model
        val drmApiRaw = streamObj.optString("api", null)
        val tokenApi = streamObj.optString("tokenApi", null)
        val scheme = streamObj.optInt("scheme", 0) // 0=ClearKey, 1=Widevine
        val secureDecoder = streamObj.optBoolean("secure_decoder", false)

        // Add the main stream link
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $streamName",
                url = actualUrl,
                type = type,
                referer = mainUrl
            ) {
                this.headers = mergedHeaders
                this.quality = Qualities.Unknown.value
            }
        )

        // Handle DRM/api field if present
        // The api field may contain url|encrypted_headers (where headers are substitution-cipher encrypted)
        if (!drmApiRaw.isNullOrBlank()) {
            val (drmUrl, drmHeadersRaw) = if (drmApiRaw.contains("|")) {
                val pipeIdx = drmApiRaw.indexOf('|')
                val url = drmApiRaw.substring(0, pipeIdx)
                val headersEncrypted = drmApiRaw.substring(pipeIdx + 1)
                // Decrypt headers using substitution cipher (rc.a.b is used in b.java.o() line 454)
                val headersDecrypted = decryptSubstitution(headersEncrypted)
                Pair(url, parseHeaders(headersDecrypted))
            } else {
                Pair(drmApiRaw, emptyMap<String, String>())
            }

            val drmHeaders = baseHeaders.toMutableMap().apply { putAll(drmHeadersRaw) }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $streamName (DRM)",
                    url = drmUrl,
                    type = type,
                    referer = mainUrl
                ) {
                    this.headers = drmHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // Handle tokenApi if present
        if (!tokenApi.isNullOrBlank()) {
            val (tokenUrl, tokenHeaders) = splitUrlAndHeaders(tokenApi)
            val tokenMergedHeaders = baseHeaders.toMutableMap().apply { putAll(tokenHeaders) }

            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $streamName (Token)",
                    url = tokenUrl,
                    type = type,
                    referer = mainUrl
                ) {
                    this.headers = tokenMergedHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}