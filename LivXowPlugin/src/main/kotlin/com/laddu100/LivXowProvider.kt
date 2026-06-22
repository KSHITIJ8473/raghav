package com.laddu100

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import kotlin.text.Charsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LivXow Provider - Live Sports & TV Streaming
 * Reverse-engineered from the LivXow Android APK (com.livxow.tv).
 */
open class LivXowProvider : MainAPI() {
    override var mainUrl = "https://sohaidoegeve2.shop/"
    override var name = "LivXow"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = false

    open val filterKeywords: List<String>? = null

    // ==================== DECRYPTION: Character Substitution Cipher (rc.a.b) ====================

    private val standardAlphabet = "aAbBcCdDeEfFgGhHiIjJkKlLmMnNoOpPqQrRsStTuUvVwWxXyYzZ"
    private val shuffledAlphabet = "fFgGjJkKaApPbBmMoOzZeEnNcCdDrRqQtTvVuUxXhHiIwWyYlLsS"
    private val decodeTable = CharArray(128) { it.toChar() }

    init {
        for (i in standardAlphabet.indices) {
            decodeTable[shuffledAlphabet[i].code] = standardAlphabet[i]
        }
    }

    private fun decryptSubstitution(str: String): String {
        val chars = CharArray(str.length) { i ->
            val c = str[i].code
            if (c in 0..127) decodeTable[c] else str[i]
        }
        return String(chars)
    }

    // ==================== DECRYPTION: HTTP Response AES (c6/f0.java) ====================

    fun decryptHttpResponse(str: String): String {
        if (str.startsWith("{") || str.startsWith("[")) {
            return str
        }
        return try {
            val substituted = decryptSubstitution(str)
            val padded = if (substituted.length % 4 != 0) {
                substituted + "=".repeat(4 - (substituted.length % 4))
            } else {
                substituted
            }
            val decoded = Base64.decode(padded, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val key = SecretKeySpec("M8mkKlNL75K4nl15".toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec("kN7m5Kl1pN5nk4xK".toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    // ==================== HEADERS ====================

    private val baseHeaders = mapOf(
        "User-Agent" to "okhttp/4.9.2",
        "Accept" to "*/*"
    )

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

            // Get sports_slug and fetch channels
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

            // Step 2: Fetch and decrypt ongoing live events (events.txt)
            val eventsUrl = "${mainUrl}events.txt"
            val eventsJson = fetchHttpDecrypted(eventsUrl)

            // Step 3: Fetch and decrypt channels list
            val channelsUrl = "$mainUrl$sportsSlug"
            val channelsJson = fetchHttpDecrypted(channelsUrl) ?: return newHomePageResponse(emptyList())

            val channelsArray = try { JSONArray(channelsJson) } catch (_: Exception) { null }
            if (channelsArray == null) return newHomePageResponse(emptyList())

            // Step 4: Parse channels
            val channelItems = mutableListOf<SearchResponse>()

            for (i in 0 until channelsArray.length()) {
                val item = channelsArray.optJSONObject(i) ?: continue
                val channelStr = item.optString("channel", "")
                if (channelStr.isBlank()) continue

                val channelObj = try { JSONObject(channelStr) } catch (_: Exception) { continue }
                val channelName = channelObj.optString("name", "")
                val channelLogo = channelObj.optString("logo", "")
                val channelLinks = channelObj.optString("links", "")
                val isPlaylist = channelObj.optBoolean("is_playlist", false)
                val linkNames = channelObj.optJSONArray("link_names")

                if (channelName.isBlank() || channelLinks.isBlank()) continue

                // Filter by sub-provider keywords if applicable
                if (filterKeywords != null) {
                    val matches = filterKeywords!!.any { kw ->
                        channelName.contains(kw, ignoreCase = true)
                    }
                    if (!matches) continue
                }

                // For playlist-type channels, we create sub-items for each link name
                if (isPlaylist && linkNames != null && linkNames.length() > 0) {
                    for (j in 0 until linkNames.length()) {
                        val subName = linkNames.optString(j, channelName)
                        val dataObj = JSONObject().apply {
                            put("links", channelLinks)
                            put("name", subName)
                            put("logo", channelLogo)
                            put("is_event", false)
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
                        put("is_event", false)
                    }
                    channelItems.add(
                        newLiveSearchResponse(channelName, dataObj.toString(), TvType.Live) {
                            this.posterUrl = channelLogo.ifBlank { null }
                        }
                    )
                }
            }

            // Step 5: Parse ongoing live events
            val liveEvents = parseEvents(eventsJson ?: "")

            val homeItems = mutableListOf<HomePageList>()

            if (liveEvents.isNotEmpty()) {
                homeItems.add(HomePageList("Ongoing Live Events", liveEvents, isHorizontalImages = true))
            }

            if (channelItems.isNotEmpty()) {
                val sectionName = if (filterKeywords != null) "$name Channels" else "Sports Channels"
                homeItems.add(HomePageList(sectionName, channelItems, isHorizontalImages = true))
            }

            return newHomePageResponse(homeItems)
        } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }
    }

    private fun parseEvents(eventsJson: String): List<SearchResponse> {
        val list = mutableListOf<SearchResponse>()
        try {
            val array = JSONArray(eventsJson)
            for (i in 0 until array.length()) {
                val itemObj = array.optJSONObject(i) ?: continue
                val eventStr = itemObj.optString("event", "")
                if (eventStr.isBlank()) continue
                val eventObj = JSONObject(eventStr)

                val visible = eventObj.optBoolean("visible", true)
                if (!visible) continue

                val eventName = eventObj.optString("eventName", "")
                val teamAName = eventObj.optString("teamAName", "")
                val teamBName = eventObj.optString("teamBName", "")
                val category = eventObj.optString("category", "")
                val links = eventObj.optString("links", "")
                val logo = eventObj.optString("eventLogo", "")
                val teamAFlag = eventObj.optString("teamAFlag", "")

                if (links.isBlank()) continue

                // Build display name
                val displayName = if (teamAName.isNotBlank() && teamBName.isNotBlank()) {
                    "$category: $teamAName vs $teamBName ($eventName)"
                } else if (eventName.isNotBlank()) {
                    "$category: $eventName"
                } else {
                    "$category Live Match"
                }

                // Filter by sub-provider keywords if applicable
                if (filterKeywords != null) {
                    val matches = filterKeywords!!.any { kw ->
                        displayName.contains(kw, ignoreCase = true) ||
                        category.contains(kw, ignoreCase = true) ||
                        eventName.contains(kw, ignoreCase = true)
                    }
                    if (!matches) continue
                }

                val dataObj = JSONObject().apply {
                    put("links", links)
                    put("name", displayName)
                    put("logo", logo.ifBlank { teamAFlag })
                    put("is_event", true)
                }

                list.add(
                    newLiveSearchResponse(displayName, dataObj.toString(), TvType.Live) {
                        this.posterUrl = logo.ifBlank { teamAFlag.ifBlank { null } }
                    }
                )
            }
        } catch (_: Exception) {
            // Ignore
        }
        return list
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
            val response = app.get(streamUrl, headers = baseHeaders, timeout = 30L)
            if (!response.isSuccessful) return false
            val responseBody = response.text
            if (responseBody.isBlank()) return false

            val decrypted = decryptHttpResponse(responseBody)
            if (decrypted.isBlank()) return false

            val jsonArray = JSONArray(decrypted)

            for (i in 0 until jsonArray.length()) {
                val streamObj = jsonArray.optJSONObject(i) ?: continue

                // Handle playlist format (channel + playlist)
                if (streamObj.has("channel") && streamObj.has("playlist")) {
                    val playlistRaw = streamObj.getString("playlist")
                    val (playlistUrl, playlistHeaders) = splitUrlAndHeaders(playlistRaw)

                    val mergedHeaders = baseHeaders.toMutableMap().apply { putAll(playlistHeaders) }
                    val playlistResponse = app.get(playlistUrl, headers = mergedHeaders, timeout = 30L)
                    if (playlistResponse.isSuccessful && playlistResponse.text.isNotBlank()) {
                        val decryptedPlaylist = decryptHttpResponse(playlistResponse.text)
                        if (decryptedPlaylist.isNotBlank()) {
                            val playlistArray = JSONArray(decryptedPlaylist)
                            for (j in 0 until playlistArray.length()) {
                                val pStream = playlistArray.optJSONObject(j) ?: continue
                                processStreamObject(pStream, callback)
                            }
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

    private fun hexToBase64Unpadded(hex: String): String {
        val bytes = ByteArray(hex.length / 2)
        for (i in 0 until hex.length step 2) {
            bytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun makeClearKeyJson(api: String): String {
        val parts = api.split(":")
        if (parts.size < 2) return ""
        val kid = hexToBase64Unpadded(parts[0].trim())
        val key = hexToBase64Unpadded(parts[1].trim())
        return """{"keys":[{"kty":"oct","k":"$key","kid":"$kid"}],"type":"temporary"}"""
    }

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
            actualUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }

        // Merge headers
        val mergedHeaders = baseHeaders.toMutableMap().apply { putAll(extraHeaders) }

        // Get DRM info
        val drmApiRaw = streamObj.optString("api").ifBlank { null }
        val scheme = streamObj.optInt("scheme", 0)

        // If it's a ClearKey DRM stream (api key present, not url)
        if (!drmApiRaw.isNullOrBlank() && !drmApiRaw.startsWith("http")) {
            val clearkeyJson = makeClearKeyJson(drmApiRaw)
            if (clearkeyJson.isNotBlank()) {
                callback(
                    newDrmExtractorLink(
                        source = name,
                        name = "$name - $streamName (ClearKey)",
                        url = actualUrl,
                        type = ExtractorLinkType.DASH,
                        uuid = java.util.UUID.fromString("1074bf19-7a17-4d2d-8a82-e9b1470fd211")
                    ) {
                        this.licenseUrl = "data:application/json," + clearkeyJson
                        this.headers = mergedHeaders
                    }
                )
                return
            }
        }

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

        // Handle DRM/api field if it is a license URL (starts with http)
        if (!drmApiRaw.isNullOrBlank() && drmApiRaw.startsWith("http")) {
            val (drmUrl, drmHeadersRaw) = if (drmApiRaw.contains("|")) {
                val pipeIdx = drmApiRaw.indexOf('|')
                val url = drmApiRaw.substring(0, pipeIdx)
                val headersEncrypted = drmApiRaw.substring(pipeIdx + 1)
                val headersDecrypted = decryptSubstitution(headersEncrypted)
                Pair(url, parseHeaders(headersDecrypted))
            } else {
                Pair(drmApiRaw, emptyMap<String, String>())
            }

            val drmHeaders = baseHeaders.toMutableMap().apply { putAll(drmHeadersRaw) }
            val uuid = if (scheme == 1) {
                java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") // Widevine UUID
            } else {
                java.util.UUID.fromString("1074bf19-7a17-4d2d-8a82-e9b1470fd211") // ClearKey UUID
            }

            callback(
                newDrmExtractorLink(
                    source = name,
                    name = "$name - $streamName (DRM)",
                    url = actualUrl,
                    type = ExtractorLinkType.DASH,
                    uuid = uuid
                ) {
                    this.licenseUrl = drmUrl
                    this.headers = drmHeaders
                }
            )
        }
    }
}

// ==================== SUB-PROVIDERS / SUB-PLUGINS ====================

class FancodeProvider : LivXowProvider() {
    override var name = "FanCode"
    override val filterKeywords = listOf("Fancode")
}

class StarSportsProvider : LivXowProvider() {
    override var name = "Star Sports"
    override val filterKeywords = listOf("Star Sports")
}

class SonySportsProvider : LivXowProvider() {
    override var name = "Sony Sports"
    override val filterKeywords = listOf("Sony Sports", "SonyLIV")
}

class WillowSportsProvider : LivXowProvider() {
    override var name = "Willow"
    override val filterKeywords = listOf("Willow")
}

class PTVSportsProvider : LivXowProvider() {
    override var name = "PTV Sports"
    override val filterKeywords = listOf("PTV Sports")
}

class SkySportsProvider : LivXowProvider() {
    override var name = "Sky Sports"
    override val filterKeywords = listOf("Sky Sports")
}

class FoxSportsProvider : LivXowProvider() {
    override var name = "Fox Sports"
    override val filterKeywords = listOf("Fox Sports", "Fox Cricket")
}

class ESPNProvider : LivXowProvider() {
    override var name = "ESPN"
    override val filterKeywords = listOf("ESPN")
}

class WWEProvider : LivXowProvider() {
    override var name = "WWE & Wrestling"
    override val filterKeywords = listOf("WWE", "RAW", "SmackDown", "AEW", "TNA", "Boxing", "UFC")
}