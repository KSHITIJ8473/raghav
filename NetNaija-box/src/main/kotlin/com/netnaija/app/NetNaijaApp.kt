package com.netnaija.app

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.netnaija.app.donation.DonationManager
import com.lagradost.cloudstream3.amap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class NetNaijaApp(private val sharedPref: SharedPreferences?) : MainAPI() {

    companion object {
        const val SPORT_API = "https://h5-sport-api.aoneroom.com/wefeed-h5api-bff"
        const val SPORT_URL = "https://www.sportslive.wine/"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

        // any h5 frontend can serve the play request when the primary
        // domain is down or blocked
        val WEB_DOMAINS = listOf(
            "https://123moviesfree.club",
            "https://movie-box.co",
            "https://movieboxonline.net",
            "https://sflix.film",
            "https://h5-api.aoneroom.com",
            "https://netnaija.film",
            "https://movieboxhd.net"
        )

        // the token bootstrap only works against the mobile gateway,
        // everything else can go to any regional api host
        val HOST_POOL = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com"
        )

        const val PREF_SECTION_ORDER = "netnaija_app_section_order"
        const val PREF_SECTION_HIDDEN = "netnaija_app_section_hidden"

        // "r|" rankings and "1|channel;filters" browse lists, interpreted in
        // getMainPage
        val sectionCatalog = listOf(
            "live|matches" to "\u26bd Live Football & Sports",
            "r|0|9167640870324258216" to "Trending Movies",
            "r|0|5692654647815587592" to "In Cinema",
            "r|0|414907768299210008" to "Bollywood",
            "r|0|3859721901924910512" to "South Indian",
            "r|0|8019599703232971616" to "Hollywood",
            "r|0|1488104699998914056" to "New Release",
            "r|0|6027735606879570952" to "New Punjabi",
            "r|0|6144409817256968824" to "New Bengali",
            "r|5|719331337777440448" to "Top Series",
            "r|5|4903182713986896328" to "Indian Drama",
            "r|5|1255898847918934600" to "Reality TV",
            "r|5|1976033493293449744" to "Asian Drama",
            "r|5|3910636007619709856" to "Western TV",
            "r|5|5177200225164885656" to "Turkish Drama",
            "1|1" to "Movies",
            "1|2" to "Series",
            "1|1006" to "Anime",
            "2|2;country=Japan;genre=Animation" to "Anime (Series)",
            "1|1;country=India" to "Indian (Movies)",
            "1|2;country=India" to "Indian (Series)",
            "1|1;classify=Hindi dub;country=United States" to "USA (Movies)",
            "1|2;classify=Hindi dub;country=United States" to "USA (Series)",
            "1|1;country=Japan" to "Japan (Movies)",
            "1|2;country=Japan" to "Japan (Series)",
            "1|1;country=China" to "China (Movies)",
            "1|2;country=China" to "China (Series)",
            "1|1;country=Philippines" to "Philippines (Movies)",
            "1|2;country=Philippines" to "Philippines (Series)",
            "1|1;country=Thailand" to "Thailand (Movies)",
            "1|2;country=Thailand" to "Thailand (Series)",
            "1|1;country=Nigeria" to "Nollywood (Movies)",
            "1|2;country=Nigeria" to "Nollywood (Series)",
            "1|1;country=Korea" to "South Korean (Movies)",
            "1|2;country=Korea" to "South Korean (Series)",
            "1|1;classify=Hindi dub;genre=Action" to "Action (Movies)",
            "1|1;classify=Hindi dub;genre=Crime" to "Crime (Movies)",
            "1|1;classify=Hindi dub;genre=Comedy" to "Comedy (Movies)",
            "1|2;classify=Hindi dub;genre=Crime" to "Crime (Series)",
            "1|2;classify=Hindi dub;genre=Comedy" to "Comedy (Series)"
        )

        // saved order wins, sections unknown to it are appended so updates
        // never drop a new row from the home page
        fun orderedSections(prefs: SharedPreferences?): List<Pair<String, String>> {
            val saved = prefs?.getString(PREF_SECTION_ORDER, null) ?: return sectionCatalog
            return try {
                val byKey = sectionCatalog.toMap()
                val ordered = ArrayList<Pair<String, String>>()
                val savedKeys = JSONArray(saved)
                for (i in 0 until savedKeys.length()) {
                    val key = savedKeys.getString(i)
                    val name = byKey[key] ?: continue
                    if (ordered.none { it.first == key }) ordered.add(key to name)
                }
                sectionCatalog.forEach { section ->
                    if (ordered.none { it.first == section.first }) ordered.add(section)
                }
                ordered
            } catch (e: Exception) {
                sectionCatalog
            }
        }

        fun hiddenSections(prefs: SharedPreferences?): Set<String> {
            val saved = prefs?.getString(PREF_SECTION_HIDDEN, "") ?: ""
            if (saved.isBlank()) return emptySet()
            return saved.split(",").filter { it.isNotBlank() }.toSet()
        }

        fun visibleSections(prefs: SharedPreferences?): List<Pair<String, String>> {
            val hiddenKeys = hiddenSections(prefs)
            return orderedSections(prefs).filter { it.first !in hiddenKeys }
        }

        private const val TOKEN_BOOTSTRAP_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"

        // secrets are base64 of base64, the hmac key is the twice decoded
        // string, same as the mobile client
        private const val SECRET_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        private const val SECRET_ALT_B64 = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

        var bearerToken: String? = null

        fun decodeJwtExpiry(token: String): Long {
            return try {
                val payload = token.split(".").getOrNull(1) ?: return 0L
                val std = payload.replace("-", "+").replace("_", "/")
                val padded = std + "=".repeat((4 - (std.length % 4)) % 4)
                val json = String(Base64.getUrlDecoder().decode(padded))
                JSONObject(json).getLong("exp")
            } catch (e: Exception) {
                0L
            }
        }

        fun isTokenValid(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val exp = decodeJwtExpiry(token)
            // one hour buffer so a token never expires mid session
            return exp > (System.currentTimeMillis() / 1000) + 3600
        }
    }

    override var mainUrl = sharedPref?.getString("netnaija_app_host", HOST_POOL[4]) ?: HOST_POOL[4]
    override var name = "NetNaija-box"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val random = SecureRandom()
    private val deviceId: String = generateDeviceId()

    private val modernUserAgent =
        "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
    private val modernClientInfo =
        "{\"package_name\":\"com.community.mbox.in\",\"version_name\":\"4.0.03.0920.03\",\"version_code\":50020130," +
            "\"os\":\"android\",\"os_version\":\"14\",\"device_id\":\"$deviceId\",\"install_store\":\"official\"," +
            "\"gaid\":\"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d\",\"brand\":\"Google\",\"model\":\"Pixel 8\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"IN\",\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

    private val PREF_TOKEN_KEY = "netnaija_app_bearer_token"

    private val mapper: com.fasterxml.jackson.databind.ObjectMapper =
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun md5(input: ByteArray): String {
        return MessageDigest.getInstance("MD5").digest(input)
            .joinToString("") { String.format("%02x", it) }
    }

    private fun generateXClientToken(timestamp: Long = System.currentTimeMillis()): String {
        val ts = timestamp.toString()
        return ts + "," + md5(ts.reversed().toByteArray())
    }

    // method, accept, content type, body length and hash, timestamp and the
    // sorted query all go into the signed string
    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long
    ): String {
        return try {
            val parsed = URI(url)
            val path = parsed.path ?: ""
            val rawQuery = parsed.rawQuery
            val query = if (!rawQuery.isNullOrBlank()) {
                rawQuery.split("&").mapNotNull {
                    val p = it.split("=")
                    if (p.isEmpty()) null else p[0] to (p.getOrNull(1) ?: "")
                }.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
            } else ""
            val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path
            val bodyHash: String
            val bodyLength: String
            if (body != null) {
                val bodyBytes = body.toByteArray()
                bodyHash = md5(if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes)
                bodyLength = bodyBytes.size.toString()
            } else {
                bodyHash = ""
                bodyLength = ""
            }
            method.uppercase(Locale.ROOT) + "\n" + (accept ?: "") + "\n" + (contentType ?: "") + "\n" +
                bodyLength + "\n" + timestamp + "\n" + bodyHash + "\n" + canonicalUrl
        } catch (e: Exception) {
            method.uppercase(Locale.ROOT) + "\n" + (accept ?: "") + "\n" + (contentType ?: "") + "\n\n" +
                timestamp + "\n\n"
        }
    }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        useAltKey: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)
        val secret = if (useAltKey) SECRET_ALT_B64 else SECRET_DEFAULT_B64
        val secretBytes = Base64.getDecoder().decode(
            String(Base64.getDecoder().decode(secret))
        )
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray()))
        return "$timestamp|2|$signature"
    }

    private fun saveToken(token: String) {
        if (!token.isBlank() && isTokenValid(token)) {
            bearerToken = token
            sharedPref?.edit()?.putString(PREF_TOKEN_KEY, token)?.apply()
        }
    }

    // the gateway hands the jwt back through the x-user response header,
    // not the body
    private suspend fun fetchAnonymousToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            if (isTokenValid(bearerToken)) return bearerToken!!
            val saved = sharedPref?.getString(PREF_TOKEN_KEY, null)
            if (isTokenValid(saved)) {
                bearerToken = saved
                return saved!!
            }
        }
        return try {
            val ts = System.currentTimeMillis()
            val xClientToken = generateXClientToken(ts)
            val xTrSig = generateXTrSignature("GET", "application/json", "application/json", TOKEN_BOOTSTRAP_URL, null, false, ts)
            val headers = mapOf(
                "user-agent" to modernUserAgent,
                "accept" to "application/json",
                "content-type" to "application/json",
                "connection" to "keep-alive",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSig,
                "x-client-info" to modernClientInfo,
                "x-client-status" to "0"
            )
            val resp = app.get(TOKEN_BOOTSTRAP_URL, headers = headers)
            val xUser = resp.headers["x-user"]
            val token = xUser?.let { runCatching { JSONObject(it).optString("token") }.getOrNull() }
            if (!token.isNullOrBlank()) {
                saveToken(token)
                token
            } else {
                bearerToken ?: ""
            }
        } catch (e: Exception) {
            bearerToken ?: ""
        }
    }

    private fun persistTokenFromXUser(xUserHeader: String?) {
        if (xUserHeader.isNullOrBlank()) return
        try {
            val token = JSONObject(xUserHeader).optString("token")
            if (token.isNotBlank()) saveToken(token)
        } catch (_: Exception) {}
    }

    private suspend fun buildAuthHeaders(
        method: String,
        url: String,
        contentType: String = "application/json",
        accept: String = "application/json",
        body: String? = null,
        useToken: Boolean = true
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val xClientToken = generateXClientToken(ts)
        val xTrSig = generateXTrSignature(method, accept, contentType, url, body, false, ts)
        val headers = mutableMapOf(
            "user-agent" to modernUserAgent,
            "accept" to accept,
            "content-type" to contentType,
            "connection" to "keep-alive",
            "x-client-token" to xClientToken,
            "x-tr-signature" to xTrSig,
            "x-client-info" to modernClientInfo,
            "x-client-status" to "0"
        )
        if (useToken) {
            val token = fetchAnonymousToken()
            if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    // sign cookies embed the manifest path, decoding one turns it into the
    // real stream url
    private fun extractPolicyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        val edgeCacheMatch = Regex("Edge-Cache-Cookie=urlprefix=([^:;\\s]+)").find(signCookie)
        if (edgeCacheMatch != null) {
            val urlPrefixB64 = edgeCacheMatch.groupValues[1]
            return try {
                val std = urlPrefixB64.replace('_', '/').replace('-', '+')
                val padded = std + "=".repeat((4 - (std.length % 4)) % 4)
                String(Base64.getUrlDecoder().decode(padded)).trimEnd('/') + "/index.mpd"
            } catch (e: Exception) {
                null
            }
        }
        val match = Regex("CloudFront-Policy=([^;]+)").find(signCookie) ?: return null
        val policyRaw = match.groupValues[1]
        val candidates = listOf(
            policyRaw.replace('-', '+').replace('~', '/').replace('_', '='),
            policyRaw.replace('-', '+').replace('_', '/')
        )
        for (cfB64 in candidates) {
            try {
                val rem = cfB64.length % 4
                val padded = if (rem > 0) cfB64 + "=".repeat(4 - rem) else cfB64
                val decoded = String(Base64.getDecoder().decode(padded))
                val root = runCatching { mapper.readTree(decoded) }.getOrNull()
                val resource = root?.get("Statement")?.get(0)?.get("Resource")?.asText()
                if (resource != null) {
                    val trimmed = resource.trimEnd('*', '/')
                    return if (trimmed.endsWith(".mpd", true)) trimmed else "$trimmed/index.mpd"
                }
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    // re-read on every home reload so section changes apply without a
    // restart
    override val mainPage: List<MainPageData>
        get() = mainPageOf(*visibleSections(sharedPref).toTypedArray())

    // hides explicit rows unless adult content is enabled in the app settings
    fun isNsfwItem(item: JsonNode): Boolean {
        if (MainAPI.Companion.settingsForProvider.enableAdult) return false
        val genre = item.get("genre")?.asText()?.lowercase(Locale.ROOT) ?: ""
        val genreTokens = genre.split(",").map { it.trim() }
        val title = item.get("title")?.asText()?.lowercase(Locale.ROOT) ?: ""
        val subjectType = item.get("subjectType")?.asInt() ?: 1
        val restrictKid = item.get("restrictKid")?.asInt() ?: 0
        val contentRating = item.get("contentRating")?.asText() ?: ""
        val adultGenres = listOf("adult", "erotic", "erotica", "hot")
        if (genreTokens.any { it in adultGenres }) return true
        if (Regex("\\bsex\\b").containsMatchIn(title)) return true
        val adultKeywords = listOf(
            "porn", "hentai", "xxx", "seduced", "nude", "naked", "erotica", "vivamax",
            "ullu", "charmsukh", "kooku", "primeplay", "bhabhi", "bhabhiji", "x-rated",
            "سكس", "جنس"
        )
        if (adultKeywords.any { title.contains(it) }) return true
        val isMatureRating = contentRating.equals("R", true) || contentRating.equals("TV-MA", true)
        if (restrictKid == 1 && isMatureRating) {
            if (genreTokens.any { it in listOf("romance", "erotic", "hot", "drama") }) return true
        }
        if (subjectType != 7) return false
        if (!genre.contains("romance") && !genre.contains("drama")) return false
        val provocativeWords = listOf(
            "pleasure", "seductive", "naked", "escort", "affair", "lover", "mistress", "sensual"
        )
        return provocativeWords.any { title.contains(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        val restrictKid = if (!MainAPI.Companion.settingsForProvider.enableAdult) 1 else 0

        // live sports come from a separate h5 sport api and render as one
        // scrolling "Live Now" row
        if (request.data.startsWith("live|")) {
            return try {
                val headers = mapOf(
                    "User-Agent" to WEB_USER_AGENT,
                    "Accept" to "application/json",
                    "Origin" to SPORT_URL.trimEnd('/'),
                    "Referer" to SPORT_URL,
                    "X-Device-Info" to "{}"
                )
                val resp = app.get("$SPORT_API/live/match-list-v5", headers = headers)
                if (resp.code != 200) return newHomePageResponse(emptyList())
                val list = mapper.readTree(resp.text).get("data")?.get("list")
                    ?: return newHomePageResponse(emptyList())
                val liveItems = ArrayList<SearchResponse>()
                for (match in list) {
                    val id = match.get("id")?.asText()?.takeUnless { it.isBlank() } ?: continue
                    val team1 = match.get("team1")?.get("name")?.asText() ?: "Team 1"
                    val team2 = match.get("team2")?.get("name")?.asText() ?: "Team 2"
                    val score1 = match.get("team1")?.get("score")?.asText() ?: "0"
                    val score2 = match.get("team2")?.get("score")?.asText() ?: "0"
                    val status = match.get("status")?.asText() ?: ""
                    val statusLive = match.get("statusLive")?.asText() ?: ""
                    val playType = match.get("playType")?.asText() ?: ""
                    val playPath = match.get("playPath")?.asText()?.takeUnless { it.isBlank() } ?: continue
                    if (!playPath.startsWith("http")) continue
                    val league = match.get("league")?.asText()
                    val poster = match.get("team1")?.get("avatar")?.asText()
                        ?: match.get("team2")?.get("avatar")?.asText()
                    val ended = status == "MatchEnded" || status == "2" || status == "FT" || statusLive == "3"
                    if (ended) continue
                    val isVideoType = playType == "PlayTypeVideo"
                    val isLive = status == "MatchIng" || status == "1" ||
                        statusLive.equals("Living", true) || statusLive == "2" || statusLive == "1"
                    if (!isVideoType && !isLive) continue
                    val playSources = match.get("playSource")?.mapNotNull { src ->
                        val path = src.get("path")?.asText()?.takeUnless { it.isBlank() } ?: return@mapNotNull null
                        SportPlaySource(src.get("title")?.asText(), path)
                    }
                    val title = if (score1 != "0" && score2 != "0") {
                        "\uD83D\uDD34 $team1  $score1 - $score2  $team2"
                    } else {
                        "\uD83D\uDD34 $team1 vs $team2"
                    }
                    val payload = SportMatchData(
                        id = id,
                        title = "$team1 vs $team2",
                        league = league,
                        status = "LIVE",
                        playPath = playPath,
                        playSource = playSources,
                        poster = poster
                    ).toJson()
                    liveItems.add(
                        newLiveSearchResponse(title, "sport_match:$payload", TvType.Live) {
                            this.posterUrl = poster
                        }
                    )
                }
                if (liveItems.isEmpty()) return newHomePageResponse(emptyList())
                newHomePageResponse(listOf(HomePageList("\uD83D\uDD34 Live Now", liveItems, true)), false)
            } catch (e: Exception) {
                newHomePageResponse(emptyList())
            }
        }

        val isRanking = request.data.startsWith("r|")
        val url = if (isRanking) {
            val parts = request.data.split("|")
            val tabId = parts.getOrNull(1) ?: "0"
            val categoryType = parts.getOrNull(2) ?: return newHomePageResponse(emptyList())
            "$mainUrl/wefeed-mobile-bff/tab/ranking-list?tabId=$tabId&categoryType=$categoryType&page=$page&perPage=20&restrictKid=$restrictKid"
        } else {
            "$mainUrl/wefeed-mobile-bff/subject-api/list"
        }

        // the channel drives the list, the remaining pairs become the filter
        // body of the browse request
        val data = request.data
        val channelId = data.substringBefore(";").split("|").getOrNull(1)
        val params = LinkedHashMap<String, String>()
        data.substringAfter(";", "").split(";").forEach {
            val p = it.split("=")
            val k = p.getOrNull(0)
            val v = p.getOrNull(1)
            if (!k.isNullOrBlank() && !v.isNullOrBlank()) params[k] = v
        }
        val classify = params["classify"] ?: "All"
        val country = params["country"] ?: "All"
        val year = params["year"] ?: "All"
        val genre = params["genre"] ?: "All"
        val sort = params["sort"] ?: "ForYou"
        val bodyJson = "{\"page\":$page,\"perPage\":20,\"channelId\":\"$channelId\"," +
            "\"classify\":\"$classify\",\"country\":\"$country\",\"year\":\"$year\"," +
            "\"genre\":\"$genre\",\"sort\":\"$sort\",\"restrictKid\":$restrictKid}"
        val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

        var headers = if (isRanking) {
            buildAuthHeaders("GET", url)
        } else {
            buildAuthHeaders("POST", url, "application/json; charset=utf-8", body = bodyJson)
        }
        var response = if (isRanking) {
            app.get(url, headers = headers)
        } else {
            app.post(url, headers = headers, requestBody = requestBody)
        }
        if (response.code == 401 || response.code == 441) {
            bearerToken = null
            sharedPref?.edit()?.remove(PREF_TOKEN_KEY)?.apply()
            headers = if (isRanking) {
                buildAuthHeaders("GET", url)
            } else {
                buildAuthHeaders("POST", url, "application/json; charset=utf-8", body = bodyJson)
            }
            response = if (isRanking) {
                app.get(url, headers = headers)
            } else {
                app.post(url, headers = headers, requestBody = requestBody)
            }
        }

        return try {
            val root = mapper.readTree(response.text)
            // rankings arrive under data.items, browse rows under data.subjects
            val items = root.get("data")?.get("items") ?: root.get("data")?.get("subjects")
                ?: return newHomePageResponse(emptyList())
            val responses = ArrayList<MovieSearchResponse>()
            for (item in items) {
                if (isNsfwItem(item)) continue
                val rawTitle = item.get("title")?.asText() ?: continue
                val title = rawTitle.substringBefore("[")
                val subjectId = item.get("subjectId")?.asText() ?: continue
                val coverImg = item.get("cover")?.get("url")?.asText()
                val tvType = when (item.get("subjectType")?.asInt() ?: 1) {
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                responses.add(
                    newMovieSearchResponse(title, subjectId, tvType) {
                        this.posterUrl = coverImg
                        this.score = Score.from10(item.get("imdbRatingValue")?.asText())
                    }
                )
            }
            val seen = HashSet<String>()
            val deduped = responses.filter { seen.add(it.url) }
            newHomePageResponse(listOf(HomePageList(request.name, deduped, false)), false)
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "$mainUrl/wefeed-mobile-bff/subject-api/search/v2"
        val restrictKid = if (!MainAPI.Companion.settingsForProvider.enableAdult) 1 else 0
        val bodyJson = "{\"page\": $page, \"perPage\": 20, \"keyword\": \"$query\", \"restrictKid\": $restrictKid}"
        val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

        var headers = buildAuthHeaders("POST", url, "application/json; charset=utf-8", body = bodyJson)
        var response = app.post(url, headers = headers, requestBody = requestBody)
        if (response.code == 401 || response.code == 441) {
            bearerToken = null
            sharedPref?.edit()?.remove(PREF_TOKEN_KEY)?.apply()
            headers = buildAuthHeaders("POST", url, "application/json; charset=utf-8", body = bodyJson)
            response = app.post(url, headers = headers, requestBody = requestBody)
        }
        persistTokenFromXUser(response.headers["x-user"])

        val results = ArrayList<SearchResponse>()
        try {
            val root = mapper.readTree(response.text)
            val groups = root.get("data")?.get("results")
            if (groups != null) {
                for (group in groups) {
                    val subjects = group.get("subjects") ?: continue
                    for (subject in subjects) {
                        if (isNsfwItem(subject)) continue
                        val title = subject.get("title")?.asText() ?: continue
                        val subjectId = subject.get("subjectId")?.asText() ?: continue
                        val coverImg = subject.get("cover")?.get("url")?.asText()
                        val tvType = when (subject.get("subjectType")?.asInt() ?: 1) {
                            2 -> TvType.TvSeries
                            else -> TvType.Movie
                        }
                        results.add(
                            newMovieSearchResponse(title, subjectId, tvType) {
                                this.posterUrl = coverImg
                                this.score = Score.from10(subject.get("imdbRatingValue")?.asText())
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // first page also matches running sports events so live matches
        // surface straight from search
        if (page == 1) {
            try {
                val headers2 = mapOf(
                    "User-Agent" to WEB_USER_AGENT,
                    "Accept" to "application/json",
                    "Origin" to SPORT_URL.trimEnd('/'),
                    "Referer" to SPORT_URL,
                    "X-Device-Info" to "{}"
                )
                val resp = app.get("$SPORT_API/live/match-list-v5", headers = headers2)
                if (resp.code == 200) {
                    val list = mapper.readTree(resp.text).get("data")?.get("list")
                    if (list != null) {
                        val q = query.trim().lowercase(Locale.ROOT)
                        for (match in list) {
                            val id = match.get("id")?.asText() ?: continue
                            val team1 = match.get("team1")?.get("name")?.asText() ?: ""
                            val team2 = match.get("team2")?.get("name")?.asText() ?: ""
                            val league = match.get("league")?.asText() ?: ""
                            val matchesQuery = q in listOf("football", "soccer", "live", "match", "sports") ||
                                (q.length >= 3 && (team1.contains(q, true) || team2.contains(q, true) || league.contains(q, true)))
                            if (!matchesQuery) continue
                            val score1 = match.get("team1")?.get("score")?.asText() ?: "0"
                            val score2 = match.get("team2")?.get("score")?.asText() ?: "0"
                            val status = match.get("status")?.asText() ?: ""
                            val statusLive = match.get("statusLive")?.asText() ?: ""
                            val playPath = match.get("playPath")?.asText()
                            val ended = status == "MatchEnded" || status == "2" || status == "FT" || statusLive == "3"
                            if (ended) continue
                            val live = status == "MatchIng" || status == "1" ||
                                statusLive.equals("Living", true) || statusLive == "2" || statusLive == "1"
                            if (!live) continue
                            if (playPath.isNullOrBlank() || !playPath.startsWith("http")) continue
                            val poster = match.get("team1")?.get("avatar")?.asText()
                                ?: match.get("team2")?.get("avatar")?.asText()
                            val title = if (score1 != "0" && score2 != "0") {
                                "\uD83D\uDD34 LIVE: $team1 $score1 - $score2 $team2"
                            } else {
                                "\uD83D\uDD34 $team1 vs $team2"
                            }
                            val payload = SportMatchData(
                                id = id,
                                title = "$team1 vs $team2",
                                league = league,
                                status = "LIVE",
                                playPath = playPath,
                                poster = poster
                            ).toJson()
                            results.add(
                                0,
                                newLiveSearchResponse(title, "sport_match:$payload", TvType.Live) {
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return results.toNewSearchResponseList()
    }

    private suspend fun fetchRecommendations(subjectId: String): List<SearchResponse> {
        return try {
            val recUrl = "$mainUrl/wefeed-mobile-bff/subject-api/detail-rec"
            val bodyJson = "{\"subjectId\":\"$subjectId\",\"page\":1,\"perPage\":20}"
            val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
            var headers = buildAuthHeaders("POST", recUrl, "application/json; charset=utf-8", body = bodyJson)
            var response = app.post(recUrl, headers = headers, requestBody = requestBody)
            if (response.code == 401 || response.code == 441) {
                bearerToken = null
                sharedPref?.edit()?.remove(PREF_TOKEN_KEY)?.apply()
                headers = buildAuthHeaders("POST", recUrl, "application/json; charset=utf-8", body = bodyJson)
                response = app.post(recUrl, headers = headers, requestBody = requestBody)
            }
            persistTokenFromXUser(response.headers["x-user"])
            if (response.code != 200) return emptyList()
            val items = mapper.readTree(response.text).get("data")?.get("items") ?: return emptyList()
            if (!items.isArray) return emptyList()
            val out = ArrayList<SearchResponse>()
            for (item in items) {
                if (isNsfwItem(item)) continue
                val subjectId2 = item.get("subjectId")?.asText() ?: continue
                val rawTitle = item.get("title")?.asText() ?: continue
                val title = rawTitle.substringBefore("[")
                val coverImg = item.get("cover")?.get("url")?.asText()
                val tvType = when (item.get("subjectType")?.asInt() ?: 1) {
                    2 -> TvType.TvSeries
                    else -> TvType.Movie
                }
                out.add(
                    newMovieSearchResponse(title, subjectId2, tvType) {
                        this.posterUrl = coverImg
                        this.score = Score.from10(item.get("imdbRatingValue")?.asText())
                    }
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = when {
            url.contains("/sport_match:") -> "sport_match:" + url.substringAfter("/sport_match:")
            url.contains("/sport_video:") -> "sport_video:" + url.substringAfter("/sport_video:")
            else -> url
        }

        if (normalizedUrl.startsWith("sport_match:")) {
            val json = normalizedUrl.removePrefix("sport_match:")
            val matchData = mapper.readValue(json, SportMatchData::class.java)
            val desc = buildString {
                matchData.league?.let { append("League: $it\n") }
                matchData.status?.let { append("Status: $it\n") }
                val extra = ArrayList<String>()
                if (!matchData.playPath.isNullOrBlank()) extra.add("Live Stream Available")
                if (!matchData.playSource.isNullOrEmpty()) extra.add("${matchData.playSource.size} Channels")
                if (extra.isNotEmpty()) append("Streams: " + extra.joinToString(", "))
            }
            return newMovieLoadResponse(matchData.title, normalizedUrl, TvType.Live, normalizedUrl) {
                this.posterUrl = matchData.poster
                this.plot = desc
            }
        }

        if (normalizedUrl.startsWith("sport_video:")) {
            val json = normalizedUrl.removePrefix("sport_video:")
            val videoData = mapper.readValue(json, SportVideoData::class.java)
            return newMovieLoadResponse(videoData.title, normalizedUrl, TvType.Live, normalizedUrl) {
                this.posterUrl = videoData.poster
                this.plot = "Sports Highlight / Replay Clip"
            }
        }

        // watch history carries the row position and web page glued after
        // the id behind pipes, the id itself only lives before the first one
        val urlBase = url.substringBefore("|")
        val id = when {
            urlBase.contains("get?subjectId") ->
                Regex("subjectId=([^&]+)").find(urlBase)?.groupValues?.get(1)
                    ?: urlBase.substringAfterLast('/')
            urlBase.contains("/") -> urlBase.substringAfterLast('/')
            else -> urlBase
        }
        val finalUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"

        var headers = buildAuthHeaders("GET", finalUrl)
        var response = app.get(finalUrl, headers = headers)
        if (response.code == 401 || response.code == 441) {
            bearerToken = null
            sharedPref?.edit()?.remove(PREF_TOKEN_KEY)?.apply()
            headers = buildAuthHeaders("GET", finalUrl)
            response = app.get(finalUrl, headers = headers)
        }
        if (response.code != 200) {
            throw ErrorLoadingException("Failed to load data: ${response.text}")
        }
        val root = mapper.readTree(response.text)
        val data = root.get("data") ?: throw ErrorLoadingException("No data")
        if (isNsfwItem(data)) {
            throw ErrorLoadingException(
                "Content blocked by the NSFW filter. Enable 'Adult content' in CloudStream settings to show it."
            )
        }

        val title = data.get("title")?.asText()?.substringBefore("[") ?: "No title found"
        val description = data.get("description")?.asText()
        val releaseDate = data.get("releaseDate")?.asText()
        val duration = data.get("duration")?.asText()
        val genre = data.get("genre")?.asText()
        val imdbRatingStr = data.get("imdbRatingValue")?.asText()?.takeUnless { it.isBlank() || it.equals("null", true) }
            ?: data.get("imdbRate")?.asText()?.takeUnless { it.isBlank() || it.equals("null", true) }
        val imdbRatingDouble = imdbRatingStr?.toDoubleOrNull()
        val year = releaseDate?.take(4)?.toIntOrNull()
        val coverUrl = data.get("cover")?.get("url")?.asText()
        val backgroundUrl = data.get("cover")?.get("url")?.asText()
        val detailUrl = data.get("detailUrl")?.asText()
        var detailDomain: String? = null
        var detailPath: String? = null
        if (!detailUrl.isNullOrBlank()) {
            try {
                val uri = URI(detailUrl)
                detailDomain = "${uri.scheme}://${uri.host}"
                detailPath = detailUrl.trimEnd('/').substringBefore('?').substringAfterLast('/')
            } catch (_: Exception) {}
        }
        if (detailPath.isNullOrBlank()) {
            detailPath = data.get("detailPath")?.asText()
        }
        val pathPart = detailPath ?: ""
        val domainPart = detailDomain ?: ""
        val subjectType = data.get("subjectType")?.asInt() ?: 1
        val type = when (subjectType) {
            2 -> TvType.TvSeries
            7 -> TvType.TvSeries
            else -> TvType.Movie
        }
        val isIndian = data.get("countryName")?.asText()?.contains("India", true) == true ||
            data.get("language")?.asText()?.contains("Hindi", true) == true ||
            data.get("title")?.asText()?.contains("Hindi", true) == true ||
            data.get("corner")?.asText()?.contains("Hindi", true) == true

        val cleanName = title.substringBefore("(").substringBefore("[").trim()
        val (tmdbId, imdbId) = try {
            identifyID(cleanName, year, imdbRatingDouble, isIndian, type)
        } catch (e: Exception) {
            null to null
        }

        // site cast list first, cinemeta fills the gap for entries without one
        val movieboxActors = try {
            data.get("staffList")?.mapNotNull { staff ->
                val name = staff.get("name")?.asText()?.takeUnless { it.isBlank() } ?: return@mapNotNull null
                val roleString = staff.get("character")?.asText()
                val image = staff.get("avatarUrl")?.asText()?.takeUnless { it.isBlank() }
                ActorData(Actor(name, image), null, roleString)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val meta = if (!imdbId.isNullOrBlank()) fetchMetaData(imdbId, type) else null
        val metaActors = try {
            meta?.get("cast")?.mapNotNull { castNode ->
                castNode.asText()?.takeUnless { it.isBlank() || it.equals("null", true) }
            }?.map { ActorData(Actor(it, null)) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val actors = if (movieboxActors.isEmpty()) metaActors else movieboxActors
        val tags = genre?.split(",")?.map { it.trim() } ?: emptyList()
        val durationMinutes: Int? = try {
            val runtimeSource = duration?.takeUnless { it.isBlank() }
                ?: meta?.get("runtime")?.asText()
            val hmatch = Regex("(\\d+)h\\s*(\\d+)m").find(runtimeSource ?: "")
            when {
                hmatch != null -> hmatch.groupValues[1].toInt() * 60 + hmatch.groupValues[2].toInt()
                runtimeSource != null -> runtimeSource.replace("m", "").trim().toIntOrNull()
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        val logoUrl = try {
            fetchTmdbLogoUrl(
                "https://api.themoviedb.org/3",
                "98ae14df2b8d8f8f8136499daf79f0e0",
                type,
                tmdbId,
                "en"
            )
        } catch (e: Exception) {
            null
        }

        val Poster = meta?.get("poster")?.asText()
        val Background = meta?.get("background")?.asText()
        val Description = meta?.get("overview")?.asText()?.takeUnless { it.isBlank() }
        val IMDBRating = meta?.get("imdbRating")?.asText()?.takeUnless { it.isBlank() }
        val score = Score.from10(IMDBRating ?: imdbRatingStr)
        var contentRating = data.get("contentRating")?.asText()?.takeUnless { it.isBlank() || it.equals("null", true) }
        if (contentRating == null) {
            contentRating = meta?.get("certification")?.asText()?.takeUnless { it.isBlank() || it.equals("null", true) }
        }
        if (contentRating == null) {
            contentRating = meta?.get("app_extras")?.get("certification")?.asText()
                ?.takeUnless { it.isBlank() || it.equals("null", true) }
        }

        if (type == TvType.TvSeries) {
            // merge the season maps of the main subject and every dub so a
            // track carrying more seasons still shows up
            val allSubjectIds = ArrayList<String>()
            allSubjectIds.add(id)
            data.get("dubs")?.forEach { dub ->
                val dubSubjectId = dub.get("subjectId")?.asText()
                if (!dubSubjectId.isNullOrBlank() && !allSubjectIds.contains(dubSubjectId)) {
                    allSubjectIds.add(dubSubjectId)
                }
            }
            val episodeMap = LinkedHashMap<Int, LinkedHashSet<Int>>()
            for (subjectId in allSubjectIds) {
                try {
                    val seasonUrl = "$mainUrl/wefeed-mobile-bff/subject-api/season-info?subjectId=$subjectId"
                    val seasonHeaders = buildAuthHeaders("GET", seasonUrl)
                    val seasonResp = app.get(seasonUrl, headers = seasonHeaders)
                    if (seasonResp.code != 200) continue
                    val seasonRoot = mapper.readTree(seasonResp.text)
                    val seasons = seasonRoot.get("data")?.get("seasons") ?: continue
                    if (!seasons.isArray || seasons.size() == 0) continue
                    for (season in seasons) {
                        val se = season.get("se")?.asInt() ?: 1
                        val maxEp = season.get("maxEp")?.asInt() ?: 1
                        val eps = episodeMap.getOrPut(se) { LinkedHashSet() }
                        for (i in 1..maxEp) eps.add(i)
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            // cinemeta carries per episode stills, titles and airdates,
            // anything missing falls back to the show cover and a plain label
            val metaVideos = try {
                meta?.get("videos")?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val episodes = ArrayList<Episode>()
            val apiGetUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id"
            for ((seasonNumber, episodeNumbers) in episodeMap) {
                for (episodeNumber in episodeNumbers.sorted()) {
                    val epUrl = "$apiGetUrl|$seasonNumber|$episodeNumber|$pathPart|$domainPart"
                    val info = metaVideos.firstOrNull {
                        it.get("season")?.asInt() == seasonNumber && it.get("episode")?.asInt() == episodeNumber
                    }
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = info?.get("name")?.asText()?.takeUnless { it.isBlank() }
                                ?: "S${seasonNumber}E${episodeNumber}"
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = info?.get("thumbnail")?.asText()?.takeUnless { it.isBlank() }
                                ?: coverUrl
                            this.description = info?.get("overview")?.asText()?.takeUnless { it.isBlank() }
                                ?: info?.get("description")?.asText()?.takeUnless { it.isBlank() }
                                ?: "Season $seasonNumber Episode $episodeNumber"
                            this.runTime = info?.get("runtime")?.asText()?.filter { it.isDigit() }?.toIntOrNull()
                            info?.get("released")?.asText()?.takeUnless { it.isBlank() }?.let { addDate(it) }
                        }
                    )
                }
            }
            if (episodes.isEmpty()) {
                val fallbackUrl = "$apiGetUrl|1|1|$pathPart|$domainPart"
                episodes.add(
                    newEpisode(fallbackUrl) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = coverUrl
                    }
                )
            }

            val recommendations = fetchRecommendations(id)
            return newTvSeriesLoadResponse(title, finalUrl, type, episodes) {
                this.posterUrl = coverUrl ?: Poster
                this.backgroundPosterUrl = Background ?: backgroundUrl ?: Poster
                try {
                    this.logoUrl = logoUrl
                } catch (_: Throwable) {}
                this.plot = Description ?: description
                this.year = year
                this.tags = tags
                this.actors = actors
                this.score = score
                this.contentRating = contentRating
                this.duration = durationMinutes
                this.recommendations = recommendations
                addImdbId(imdbId)
                addTMDbId(tmdbId?.toString())
            }
        }

        val recommendations = fetchRecommendations(id)
        val movieUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$id|0|0|$pathPart|$domainPart"
        // the page url stays clean so history and continue watching reopen
        // the title, only playback needs the pipe payload
        return newMovieLoadResponse(title, finalUrl, type, movieUrl) {
            this.posterUrl = coverUrl ?: Poster
            this.backgroundPosterUrl = Background ?: backgroundUrl
            try {
                this.logoUrl = logoUrl
            } catch (_: Throwable) {}
            this.plot = Description ?: description
            this.year = year
            this.tags = tags
            this.actors = actors
            this.score = score
            this.contentRating = contentRating
            this.duration = durationMinutes
            this.recommendations = recommendations
            addImdbId(imdbId)
            addTMDbId(tmdbId?.toString())
        }
    }

    private suspend fun loadCaptions(
        streamId: String,
        language: String,
        subjectId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = HashSet<String>()
        try {
            // both endpoints return their rows under data.extCaptions
            val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId"
            val subHeaders = buildAuthHeaders("GET", subLink)
            val subResponse = app.get(subLink, headers = subHeaders)
            val subRoot = mapper.readTree(subResponse.text).get("data")?.get("extCaptions")
            if (subRoot != null && subRoot.isArray) {
                for (caption in subRoot) {
                    emitCaption(seen, subtitleCallback, language, caption)
                }
            }
        } catch (_: Exception) {}
        try {
            val subLink = "$mainUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=0"
            val subHeaders = buildAuthHeaders("GET", subLink)
            val subResponse = app.get(subLink, headers = subHeaders)
            val subRoot = mapper.readTree(subResponse.text).get("data")?.get("extCaptions")
            if (subRoot != null && subRoot.isArray) {
                for (caption in subRoot) {
                    emitCaption(seen, subtitleCallback, language, caption)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun emitCaption(
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        language: String,
        caption: JsonNode
    ) {
        val url = caption.get("url")?.asText()?.takeUnless { it.isBlank() } ?: return
        if (!seen.add(url)) return
        val rawLang = caption.get("lanName")?.asText()?.takeUnless { it.isBlank() }
            ?: caption.get("language")?.asText()?.takeUnless { it.isBlank() }
            ?: caption.get("lan")?.asText()?.takeUnless { it.isBlank() }
            ?: "Unknown"
        val lang = SubtitleHelper.fromTagToEnglishLanguageName(rawLang)
            ?: SubtitleHelper.fromTagToEnglishLanguageName(rawLang.substringBefore("-"))
            ?: rawLang
        val audioTag = language.replace("dub", "Audio")
        val label = "$lang ($audioTag)"
        subtitleCallback(newSubtitleFile(label, url))
    }

    private suspend fun loadNativeMobileStreams(
        subjectId: String,
        language: String,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val playUrl = "$mainUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$season&ep=$episode"
            var playHeaders = buildAuthHeaders("GET", playUrl)
            var response = app.get(playUrl, headers = playHeaders)
            if (response.code == 401 || response.code == 441) {
                fetchAnonymousToken(forceRefresh = true)
                playHeaders = buildAuthHeaders("GET", playUrl)
                response = app.get(playUrl, headers = playHeaders)
            }
            if (response.code != 200) return
            val playData = mapper.readTree(response.text).get("data") ?: return
            val streams = playData.get("streams")
            if (streams != null && streams.isArray && streams.size() > 0) {
                // dash variants first so adaptive streams sort to the top
                val sortedStreams = streams.sortedByDescending { stream ->
                    val url = stream.get("url")?.asText() ?: ""
                    val format = stream.get("format")?.asText() ?: ""
                    if (format.equals("DASH", true) || url.contains(".mpd")) 1 else 0
                }
                for (stream in sortedStreams) {
                    try {
                        val rawStreamUrl = stream.get("url")?.asText() ?: continue
                        val format = stream.get("format")?.asText() ?: ""
                        val resolutions = stream.get("resolutions")?.asText() ?: ""
                        // plain mp4 mirrors never carry a sign cookie
                        val signCookie = stream.get("signCookie")?.asText()?.takeUnless { it.isNullOrEmpty() }
                        val streamId = stream.get("id")?.asText() ?: "$subjectId|$season|$episode"
                        val quality = getHighestQuality(resolutions)
                        val policyUrl = extractPolicyResource(signCookie)
                        val finalStreamUrl = policyUrl ?: rawStreamUrl
                        if (finalStreamUrl.contains("b164fbfb4347792950bdfbfb563d39d9")) continue
                        if (finalStreamUrl == rawStreamUrl && rawStreamUrl.contains("/other/2026/09/")) continue
                        val isDash = format.equals("DASH", true) || finalStreamUrl.contains(".mpd")
                        val type = when {
                            isDash -> ExtractorLinkType.DASH
                            rawStreamUrl.startsWith("magnet:") -> ExtractorLinkType.MAGNET
                            rawStreamUrl.substringAfterLast('.').equals("torrent", true) -> ExtractorLinkType.TORRENT
                            format.equals("HLS", true) || finalStreamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                            finalStreamUrl.contains(".mp4") || finalStreamUrl.contains(".mkv") -> ExtractorLinkType.VIDEO
                            else -> INFER_TYPE
                        }
                        val cleanLang = language.replace("dub", "").replace("Audio", "").trim()
                        val linkName = "$cleanLang Audio"
                        val formatName = if (isDash) "DASH" else "HLS"
                        val sourceName = "$name $formatName"
                        val displayName = "$name $formatName ($linkName)"
                        val baseHeaders = mutableMapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to modernUserAgent
                        )
                        if (signCookie != null) {
                            baseHeaders["Cookie"] = signCookie
                        }
                        callback(
                            com.lagradost.cloudstream3.utils.newExtractorLink(sourceName, displayName, finalStreamUrl, type) {
                                this.headers = baseHeaders
                                if (quality != null) this.quality = quality
                            }
                        )
                        loadCaptions(streamId, language, subjectId, subtitleCallback)
                        return
                    } catch (e: Exception) {
                        continue
                    }
                }
            }

            // external mirrors are per resolution links keyed by season and
            // episode, only the requested position is used
            val fallbackUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
            val fallbackHeaders = buildAuthHeaders("GET", fallbackUrl)
            val fallbackResponse = app.get(fallbackUrl, headers = fallbackHeaders)
            if (fallbackResponse.code != 200) return
            val fallbackRoot = mapper.readTree(fallbackResponse.text).get("data") ?: return
            val detectors = fallbackRoot.get("resourceDetectors") ?: return
            for (detector in detectors) {
                try {
                    val resolutionList = detector.get("resolutionList") ?: continue
                    for (video in resolutionList) {
                        val link = video.get("resourceLink")?.asText() ?: continue
                        val resolution = video.get("resolution")?.asInt() ?: continue
                        val se = video.get("se")?.asInt() ?: continue
                        val ep = video.get("ep")?.asInt() ?: continue
                        if (se != season || ep != episode) continue
                        val audioTag = language.replace("dub", "Audio")
                        val sourceName = "$name $audioTag"
                        val displayName = "$name S${se}E${ep} ${resolution}p ($audioTag)"
                        callback(
                            com.lagradost.cloudstream3.utils.newExtractorLink(sourceName, displayName, link, ExtractorLinkType.VIDEO) {
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "User-Agent" to modernUserAgent
                                )
                                this.quality = resolution
                            }
                        )
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (_: Exception) {}
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalizedData = when {
            data.contains("/sport_match:") -> "sport_match:" + data.substringAfter("/sport_match:")
            data.contains("/sport_video:") -> "sport_video:" + data.substringAfter("/sport_video:")
            else -> data
        }

        if (normalizedData.startsWith("sport_match:")) {
            try {
                val json = normalizedData.removePrefix("sport_match:")
                val matchData = mapper.readValue(json, SportMatchData::class.java)
                // the detail endpoint carries fresher channels than the list
                // response, merge both before emitting
                var freshPlayPath: String? = null
                var freshPlaySources: List<SportPlaySource>? = null
                try {
                    val sportHeaders = mapOf(
                        "User-Agent" to WEB_USER_AGENT,
                        "Accept" to "application/json",
                        "Origin" to SPORT_URL.trimEnd('/'),
                        "Referer" to SPORT_URL,
                        "X-Device-Info" to "{}"
                    )
                    val detailResp = app.get("$SPORT_API/live/match-detail?id=${matchData.id}", headers = sportHeaders)
                    if (detailResp.code == 200) {
                        val detail = mapper.readTree(detailResp.text).get("data")
                        freshPlayPath = detail?.get("playPath")?.asText()?.takeUnless { it.isBlank() }
                        freshPlaySources = detail?.get("playSource")?.mapNotNull { src ->
                            val path = src.get("path")?.asText()?.takeUnless { it.isBlank() } ?: return@mapNotNull null
                            SportPlaySource(src.get("title")?.asText(), path)
                        }
                    } else {
                        freshPlayPath = null
                        freshPlaySources = null
                    }
                } catch (e: Exception) {
                    freshPlayPath = null
                    freshPlaySources = null
                }
                val mainPath = freshPlayPath ?: matchData.playPath
                if (!mainPath.isNullOrBlank() && mainPath.startsWith("http")) {
                    callback(
                        com.lagradost.cloudstream3.utils.newExtractorLink("NetNaija-box Live", "Live Stream (Main)", mainPath, ExtractorLinkType.M3U8) {
                            this.referer = SPORT_URL
                            this.headers = mapOf(
                                "Referer" to SPORT_URL,
                                "User-Agent" to WEB_USER_AGENT
                            )
                            this.quality = com.lagradost.cloudstream3.utils.Qualities.P1080.value
                        }
                    )
                }
                val channels = freshPlaySources ?: matchData.playSource ?: emptyList()
                channels.forEachIndexed { idx, src ->
                    try {
                        val rawPath = src.path ?: return@forEachIndexed
                        if (rawPath.isBlank()) return@forEachIndexed
                        val channelTitle = src.title?.takeUnless { it.isBlank() } ?: "Channel ${idx + 1}"
                        var streamUrl = rawPath
                        if (streamUrl.contains("88player.top/m3u8.html")) {
                            streamUrl = Regex("[?&]url=([^&]+)").find(streamUrl)?.groupValues?.get(1)
                                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                                ?: return@forEachIndexed
                        }
                        if (!streamUrl.startsWith("http")) return@forEachIndexed
                        val isM3u8 = streamUrl.contains("m3u8") || streamUrl.contains("playlist")
                        val channelReferer = if (rawPath.contains("88player.top")) "https://play.88player.top/" else SPORT_URL
                        callback(
                            com.lagradost.cloudstream3.utils.newExtractorLink(
                                "NetNaija-box Live",
                                "Live Channel - $channelTitle",
                                streamUrl,
                                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = channelReferer
                                this.headers = mapOf(
                                    "Referer" to channelReferer,
                                    "User-Agent" to WEB_USER_AGENT
                                )
                                this.quality = com.lagradost.cloudstream3.utils.Qualities.P720.value
                            }
                        )
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            return true
        }

        if (normalizedData.startsWith("sport_video:")) {
            try {
                val json = normalizedData.removePrefix("sport_video:")
                val videoData = mapper.readValue(json, SportVideoData::class.java)
                val isM3u8 = videoData.url.contains("m3u8")
                callback(
                    com.lagradost.cloudstream3.utils.newExtractorLink(
                        "NetNaija-box Sports",
                        videoData.title,
                        videoData.url,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = SPORT_URL
                        this.headers = mapOf(
                            "Referer" to SPORT_URL,
                            "User-Agent" to WEB_USER_AGENT
                        )
                        this.quality = com.lagradost.cloudstream3.utils.Qualities.P1080.value
                    }
                )
            } catch (_: Exception) {}
            return true
        }

        val parts = normalizedData.split("|")
        val originalSubjectId: String = when {
            parts[0].contains("get?subjectId") -> {
                Regex("subjectId=([^&]+)").find(parts[0])?.groupValues?.get(1)
                    ?: parts[0].substringAfterLast('/')
            }
            parts[0].contains("/") -> parts[0].substringAfterLast('/')
            else -> parts[0]
        }
        val season = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val episode = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val passedDetailPath = parts.getOrNull(3)?.takeUnless { it.isBlank() }
        val passedDetailDomain = parts.getOrNull(4)?.takeUnless { it.isBlank() }

        val subjectUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$originalSubjectId"
        var subjectHeaders = buildAuthHeaders("GET", subjectUrl)
        var subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
        if (subjectResponse.code == 401 || subjectResponse.code == 441) {
            bearerToken = null
            sharedPref?.edit()?.remove(PREF_TOKEN_KEY)?.apply()
            subjectHeaders = buildAuthHeaders("GET", subjectUrl)
            subjectResponse = app.get(subjectUrl, headers = subjectHeaders)
        }
        persistTokenFromXUser(subjectResponse.headers["x-user"])

        val subjectData = mapper.readTree(subjectResponse.text).get("data")
        var mainDetailDomain: String? = passedDetailDomain
        var mainDetailPath: String? = passedDetailPath
        if (subjectData != null) {
            val detailUrl = subjectData.get("detailUrl")?.asText()?.takeUnless { it.isBlank() }
            if (detailUrl != null) {
                try {
                    val uri = URI(detailUrl)
                    mainDetailDomain = "${uri.scheme}://${uri.host}"
                    mainDetailPath = detailUrl.trimEnd('/').substringBefore('?').substringAfterLast('/')
                } catch (_: Exception) {}
            }
            if (mainDetailPath.isNullOrBlank()) {
                mainDetailPath = subjectData.get("detailPath")?.asText() ?: mainDetailPath
            }
        }

        // the original track leads the list, every dub resolves in parallel
        val dubList = ArrayList<Triple<String, String, String?>>()
        var originalLanguageName = "Original"
        subjectData?.get("dubs")?.forEach { dub ->
            val dubSubjectId = dub.get("subjectId")?.asText()
            val language = dub.get("lanName")?.asText()
            if (dubSubjectId.isNullOrBlank() || language.isNullOrBlank()) return@forEach
            if (dubSubjectId == originalSubjectId) {
                originalLanguageName = language
                return@forEach
            }
            dubList.add(Triple(dubSubjectId, language, dub.get("detailUrl")?.asText()))
        }
        dubList.add(0, Triple(originalSubjectId, originalLanguageName, null))

        NetNaijaAppSources.register(dubList.map { NetNaijaAppSources.labelOf(it.second) })

        // a track turned off in the settings is skipped entirely, its play
        // endpoint is never touched
        val activeDubs = dubList.filter { NetNaijaAppSources.isEnabled(NetNaijaAppSources.labelOf(it.second)) }

        activeDubs.amap { (subjectId, language, initialDubDetailUrl) ->
            try {
                // a dub without a detail url needs a subject record lookup, the dubs entry already knows the page
                var currentDetailUrl: String? = initialDubDetailUrl
                var dubPath: String? = mainDetailPath
                if (subjectId != originalSubjectId && currentDetailUrl.isNullOrBlank()) {
                    try {
                        val dubUrl = "$mainUrl/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"
                        val dubHeaders = buildAuthHeaders("GET", dubUrl)
                        val dubResp = app.get(dubUrl, headers = dubHeaders)
                        if (dubResp.code == 200) {
                            val dubData = mapper.readTree(dubResp.text).get("data")
                            val detailUrl = dubData?.get("detailUrl")?.asText()
                            if (!detailUrl.isNullOrBlank()) {
                                currentDetailUrl = detailUrl
                            } else {
                                dubPath = dubData?.get("detailPath")?.asText() ?: dubPath
                            }
                        }
                    } catch (_: Exception) {}
                }
                var dubDomain: String? = mainDetailDomain
                if (!currentDetailUrl.isNullOrBlank()) {
                    try {
                        val uri = URI(currentDetailUrl)
                        dubDomain = "${uri.scheme}://${uri.host}"
                        dubPath = currentDetailUrl.trimEnd('/').substringBefore('?').substringAfterLast('/')
                    } catch (_: Exception) {}
                }

                val pathParam = dubPath ?: ""
                val candidateDomains = LinkedHashSet<String>()
                dubDomain?.trimEnd('/')?.let { candidateDomains.add(it) }
                candidateDomains.addAll(WEB_DOMAINS)

                val captionsLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
                var webStreamsEmitted = false
                for (domain in candidateDomains) {
                    try {
                        val playUrl = "$domain/wefeed-h5api-bff/subject/play?subjectId=$subjectId" +
                            "&se=$season&ep=$episode&detailPath=$pathParam" +
                            "&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
                        val referer = "$domain/movies/$pathParam?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en"
                        val webHeaders = mutableMapOf(
                            "User-Agent" to WEB_USER_AGENT,
                            "Referer" to referer,
                            "Accept" to "application/json",
                            "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                            "x-request-lang" to "en",
                            "x-vip-restrict" to "0",
                            "x-no-high-risk-restrict" to "0",
                            "x-source" to ""
                        )
                        if (!bearerToken.isNullOrBlank()) {
                            webHeaders["Authorization"] = "Bearer $bearerToken"
                        }
                        val webResp = app.get(playUrl, headers = webHeaders)
                        if (webResp.code != 200) continue
                        val webData = mapper.readTree(webResp.text).get("data") ?: continue
                        val dashItems = webData.get("dash")
                        val streamItems = webData.get("streams")
                        val hlsItems = webData.get("hls")
                        val hasStreams = (dashItems != null && dashItems.isArray && dashItems.size() > 0) ||
                            (streamItems != null && streamItems.isArray && streamItems.size() > 0) ||
                            (hlsItems != null && hlsItems.isArray && hlsItems.size() > 0)
                        if (!hasStreams) continue

                        suspend fun emitWebStream(item: JsonNode, kind: String) {
                            val rawStreamUrl = item.get("url")?.asText() ?: return
                            // mp4 mirrors ship without a sign cookie, it is
                            // only required by the signed cdn hosts
                            val signCookie = item.get("signCookie")?.asText()?.takeUnless { it.isBlank() }
                            val signHeaderKey = item.get("signHeaderKey")?.asText()?.takeUnless { it.isBlank() } ?: "X-MB-Token"
                            val streamId = item.get("id")?.asText() ?: "$subjectId|$season|$episode"
                            val resolutions = item.get("resolutions")?.asText() ?: ""
                            val quality = getHighestQuality(resolutions)
                            val policyUrl = extractPolicyResource(signCookie)
                            val finalStreamUrl = policyUrl ?: rawStreamUrl
                            // known broken resource ids and a bad upload month
                            // never reach the player
                            if (finalStreamUrl.contains("b164fbfb4347792950bdfbfb563d39d9")) return
                            if (finalStreamUrl == rawStreamUrl && rawStreamUrl.contains("/other/2026/09/")) return
                            val isDash = kind.equals("DASH", true) || finalStreamUrl.contains(".mpd")
                            val type = when {
                                isDash -> ExtractorLinkType.DASH
                                kind.equals("HLS", true) || finalStreamUrl.contains(".m3u8") -> ExtractorLinkType.M3U8
                                finalStreamUrl.contains(".mp4") || finalStreamUrl.contains(".mkv") -> ExtractorLinkType.VIDEO
                                else -> INFER_TYPE
                            }
                            val streamHeaders = mutableMapOf(
                                "Origin" to domain,
                                "Referer" to referer,
                                "User-Agent" to WEB_USER_AGENT,
                                "Accept" to "*/*"
                            )
                            if (signCookie != null) {
                                streamHeaders[signHeaderKey] = signCookie
                                streamHeaders["Cookie"] = signCookie
                            }
                            val cleanLang = language.replace("dub", "").replace("Audio", "").trim()
                            val linkName = "$cleanLang Audio"
                            val formatName = if (isDash) "DASH" else "HLS"
                            val sourceName = "$name $formatName"
                            val displayName = "$name $formatName ($linkName)"
                            callback(
                                com.lagradost.cloudstream3.utils.newExtractorLink(sourceName, displayName, finalStreamUrl, type) {
                                    this.referer = referer
                                    this.headers = streamHeaders
                                    if (quality != null) this.quality = quality
                                }
                            )
                            if (captionsLoaded.compareAndSet(false, true)) {
                                loadCaptions(streamId, language, subjectId, subtitleCallback)
                            }
                        }

                        try {
                            if (dashItems != null && dashItems.isArray) {
                                for (item in dashItems) emitWebStream(item, "DASH")
                            }
                        } catch (_: Exception) {}
                        try {
                            if (hlsItems != null && hlsItems.isArray) {
                                for (item in hlsItems) emitWebStream(item, "HLS")
                            }
                        } catch (_: Exception) {}
                        try {
                            if (streamItems != null && streamItems.isArray) {
                                for (item in streamItems) emitWebStream(item, "MP4")
                            }
                        } catch (_: Exception) {}
                        // the first domain carrying streams wins, native
                        // mobile streams only run when no web domain worked
                        webStreamsEmitted = true
                        return@amap
                    } catch (e: Exception) {
                        continue
                    }
                }
                if (!webStreamsEmitted) {
                    loadNativeMobileStreams(subjectId, language, season, episode, subtitleCallback, callback)
                }
            } catch (_: Exception) {}
        }
        return true
    }
}
