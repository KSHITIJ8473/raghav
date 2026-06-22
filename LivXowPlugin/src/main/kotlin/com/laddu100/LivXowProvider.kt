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
 * Decryption Architecture (two separate AES keys):
 *   - HTTP Response Key (c6/f0.java): M8mkKlNL75K4nl15 / kN7m5Kl1pN5nk4xK
 *     Used for: app.txt, channels/<slug>.txt (config/channel list responses)
 *     Flow: substitution cipher -> Base64 -> AES/CBC
 *   - Stream Data Key (android/support/v4/media/session/b.java): l2K5wB8xC1wP7rK1 / n0K4nP8uB8hH1l18
 *     Used for: per-channel stream link responses
 *     Flow: Base64 -> AES/CBC
 *
 * API Flow:
 *   1. {base_url}app.txt -> HTTP key decrypt -> config JSON with sports_slug
 *   2. {base_url}{sports_slug} -> HTTP key decrypt -> channel array [{channel: "{name, logo, links, link_names}"}]
 *   3. {base_url}{channel.links} -> Stream key decrypt -> stream data
 *   4. Stream objects: { name, link, api, tokenApi, audio, scheme, secure_decoder }
 */
class LivXowProvider : MainAPI() {
    override var mainUrl = "https://sohaidoegeve2.shop/"
    override var name = "LivXow"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    // ==================== DECRYPTION: Character Substitution Cipher (rc.a.b) ====================

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

    // Decode table: shuffled char -> standard char (rc.a f11361d)
    private val decodeTable = CharArray(128) { it.toChar() }

    init {
        for (i in standardAlphabet.indices) {
            decodeTable[shuffledAlphabet[i].code] = standardAlphabet[i]
        }
    }

    /**
     * Character substitution cipher (rc.a.b equivalent).
     * Maps shuffled alphabet characters back to standard alphabet.
     */
    private fun decryptSubstitution(str: String): String {
        return String(CharArray(str.length) { i -> decodeTable[str[i].code] })
    }

    // ==================== DECRYPTION: HTTP Response AES (c6/f0.java j() method) ====================

    /**
     * AES/CBC/PKCS5Padding for HTTP response decryption (c6/f0.java).
     * Key: "M8mkKlNL75K4nl15", IV: "kN7m5Kl1pN5nk4xK"
     * Used for app.txt and channels/<slug>.txt responses.
     *
     * Full flow from f0.j():
     *   1. If response starts with { or [ -> return as-is
     *   2. Apply substitution cipher (rc.a.b)
     *   3. Base64 decode
     *   4. AES/CBC decrypt with this key/IV
     */
    private fun decryptHttpResponse(str: String): String {
        if (str.startsWith("{") || str.startsWith("[")) {
            return str
        }
        return try {
            val substituted = decryptSubstitution(str)
            val decoded = Base64.getDecoder().decode(substituted)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val key = SecretKeySpec("M8mkKlNL75K4nl15".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("kN7m5Kl1pN5nk4xK".toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    // ==================== DECRYPTION: Stream Data AES (b.k() method) ====================

    /**
     * AES/CBC/PKCS5Padding for stream data decryption (b.k() method).
     * Key: "l2K5wB8xC1wP7rK1", IV: "n0K4nP8uB8hH1l18"
     * Used for per-channel stream link responses.
     */
    private fun decryptStreamData(str: String): String? {
        if (str.startsWith("{") || str.startsWith("[")) {
            return str
        }
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

    // ==================== HEADERS ====================

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    /**
     * Parses headers from the pipe-separated format used by the app.
     * Format: "key1=value1&key2=value2&user-agent=Mozilla..."
     * Mirrors b.s() / vc.a.g() in the decompiled app.
     */
    private fun parseHeaders(headerStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in headerStr.split("&")) {
            val eqIndex = part.indexOf('=')
            if (eqIndex == -1) {
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

    /**
     * Fetches a URL and decrypts using the HTTP response key (c6/f0.java).
     * Used for app.txt and channels/<slug>.txt.
     */
    private suspend fun fetchHttpDecrypted(url: String, headers: Map<String, String> = baseHeaders): String? {
        return try {
            val response = app.get(url, headers = headers, timeout = 30L)
            if (!response.isSuccessful) return null
            val rawBody = response.text
            if (rawBody.isBlank()) return null
            decryptHttpResponse(rawBody)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches a URL and decrypts using the stream data key (b.k()).
     * Used for per-channel stream link responses.
     */
    private suspend fun fetchStreamDecrypted(url: String, headers: Map<String, String> = baseHeaders): JSONArray? {
        return try {
            val response = app.get(url, headers = headers, timeout = 30L)
            if (!response.isSuccessful) return null
            val rawBody = response.text
            if (rawBody.isBlank()) return null
            val decrypted = decryptStreamData(rawBody) ?: return null
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
        try {
            // Step 1: Fetch and decrypt app.txt config
            val configUrl = "${mainUrl}app.txt"
            val configJson = fetchHttpDecrypted(configUrl) ?: return newHomePageResponse(emptyList())

            val config = try { JSONArray(configJson).optJSONObject(0) } catch (_: Exception) { null }
            if (config == null) return newHomePageResponse(emptyList())

            // Step 2: Get sports_slug and fetch channels
            var sportsSlug = config.optString("sports_slug", "")
            if (sportsSlug.isBlank()) {
                sportsSlug = config.optString("abbasi_sports_slug", "")
            }
            if (sportsSlug.isBlank()) {
                sportsSlug = config.optString("new_sports_slug", "")
            }

            if (sportsSlug.isBlank()) {
                return newHomePageResponse(emptyList())
            }

            val channelsUrl = "$mainUrl$sportsSlug"
            val channelsJson = fetchHttpDecrypted(channelsUrl) ?: return newHomePageResponse(emptyList())

            val channelsArray = try { JSONArray(channelsJson) } catch (_: Exception) { null }
            if (channelsArray == null) return newHomePageResponse(emptyList())

            // Step 3: Parse channels
            val homeItems = mutableListOf<HomePageList>()
            val channelItems = mutableListOf<SearchResponse>()

            for (i in 0 until channelsArray.length()) {
                val item = channelsArray.optJSONObject(i) ?: continue
                // Each item has "channel" key containing a JSON string
                val channelStr = item.optString("channel", "")
                if (channelStr.isBlank()) continue

                val channelObj = try { JSONObject(channelStr) } catch (_: Exception) { continue }
                val channelName = channelObj.optString("name", "")
                val channelLogo = channelObj.optString("logo", "")
                val channelLinks = channelObj.optString("links", "")
                val isPlaylist = channelObj.optBoolean("is_playlist", false)
                val linkNames = channelObj.optJSONArray("link_names")

                if (channelName.isBlank() || channelLinks.isBlank()) continue

                // For playlist-type channels, we create sub-items for each link name
                if (isPlaylist && linkNames != null && linkNames.length() > 0) {
                    for (j in 0 until linkNames.length()) {
                        val subName = linkNames.optString(j, channelName)
                        // Store full URL in the data field as a JSON with channel info
                        val dataObj = JSONObject().apply {
                            put("links", channelLinks)
                            put("name", subName)
                            put("logo", channelLogo)
                        }
                        channelItems.add(
                            newLiveSearchResponse(subName, dataObj.toString(), TvType.Live) {
                                this.posterUrl = channelLogo.ifBlank { null }
                            }
                        )
                    }
                } else {
                    val dataObj = JSONObject().apply {
                        put("links", channelLinks)
                        put("name", channelName)
                        put("logo", channelLogo)
                    }
                    channelItems.add(
                        newLiveSearchResponse(channelName, dataObj.toString(), TvType.Live) {
                            this.posterUrl = channelLogo.ifBlank { null }
                        }
                    )
                }
            }

            homeItems.add(HomePageList("Live Channels", channelItems, isHorizontalImages = true))

            return newHomePageResponse(homeItems.ifEmpty {
                listOf(HomePageList("No Channels", emptyList(), isHorizontalImages = true))
            })
        } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }
    }

    // ==================== SEARCH ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, MainPageRequest("Search", mainUrl, false))
        val allItems = homeResponse.items.flatMap { it.list }
        return allItems.filter {
            it.name.contains(query, ignoreCase = true)
        }
    }

    // ==================== LOAD (Channel Detail) ====================

    override suspend fun load(url: String): LoadResponse {
        try {
            val dataObj = JSONObject(url)
            val name = dataObj.optString("name", "Live Channel")
            val logo = dataObj.optString("logo", "")
            return newLiveStreamLoadResponse(name, url, this.name) {
                this.posterUrl = logo.ifBlank { null }
            }
        } catch (_: Exception) {
            return newLiveStreamLoadResponse("Live Channel", url, this.name) {
                this.posterUrl = null
            }
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
            val dataObj = JSONObject(data)
            val links = dataObj.optString("links", "")
            if (links.isBlank()) return false

            val streamUrl = "$mainUrl$links"
            val jsonArray = fetchStreamDecrypted(streamUrl) ?: return false

            for (i in 0 until jsonArray.length()) {
                val streamObj = jsonArray.optJSONObject(i) ?: continue

                // Handle playlist format (channel + playlist)
                if (streamObj.has("channel") && streamObj.has("playlist")) {
                    val playlistRaw = streamObj.getString("playlist")
                    val (playlistUrl, playlistHeaders) = splitUrlAndHeaders(playlistRaw)

                    val mergedHeaders = baseHeaders.toMutableMap().apply { putAll(playlistHeaders) }
                    val playlistArray = fetchStreamDecrypted(playlistUrl, mergedHeaders)
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
     *   - name: stream name/quality
     *   - link: stream URL (may contain |headers)
     *   - api: DRM license URL (may contain |encrypted_headers)
     *   - tokenApi: token API URL
     *   - audio: audio track
     *   - scheme: DRM scheme (0=ClearKey, 1=Widevine)
     *   - secure_decoder: whether secure decoder is required
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
            actualUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.VIDEO
        }

        // Merge headers
        val mergedHeaders = baseHeaders.toMutableMap().apply { putAll(extraHeaders) }

        // Get DRM info from pc.h model
        val drmApiRaw = streamObj.optString("api", null)
        val tokenApi = streamObj.optString("tokenApi", null)

        // Add the main stream link
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $streamName",
                url = actualUrl,
                type = type
            ) {
                this.headers = mergedHeaders
                this.quality = Qualities.Unknown.value
                this.referer = mainUrl
            }
        )

        // Handle DRM/api field if present
        if (!drmApiRaw.isNullOrBlank()) {
            val (drmUrl, drmHeadersRaw) = if (drmApiRaw.contains("|")) {
                val pipeIdx = drmApiRaw.indexOf('|')
                val url = drmApiRaw.substring(0, pipeIdx)
                val headersEncrypted = drmApiRaw.substring(pipeIdx + 1)
                // Decrypt headers using substitution cipher
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
                    type = type
                ) {
                    this.headers = drmHeaders
                    this.quality = Qualities.Unknown.value
                    this.referer = mainUrl
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
                    type = type
                ) {
                    this.headers = tokenMergedHeaders
                    this.quality = Qualities.Unknown.value
                    this.referer = mainUrl
                }
            )
        }
    }
}