package com.gotaku

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.delay

object GoTakuApi {

    private const val TAG = "GoTaku"
    const val SITE = "https://gotaku.to"
    private const val API = "$SITE/api/v1"

    // the video cdn rejects anything that does not look like a real browser
    val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "sec-ch-ua" to "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\""
    )

    private fun siteHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = browserHeaders.toMutableMap()
        headers["Referer"] = "$SITE/"
        headers["Sec-Fetch-Site"] = "same-origin"
        headers["Accept"] = "application/json"
        headers.putAll(extra)
        return headers
    }

    // the edge returns 426 now and then, one retry clears it
    private suspend fun getBody(url: String, headers: Map<String, String>, attempts: Int = 3): ByteArray? {
        repeat(attempts) { attempt ->
            try {
                val response = app.get(url, headers = headers)
                if (response.isSuccessful) {
                    return response.body.bytes()
                }
                if (response.code != 426) {
                    Log.d(TAG, "request $url answered ${response.code}")
                    return null
                }
            } catch (e: Exception) {
                Log.d(TAG, "request failed: ${e.message}")
            }
            delay(700L + attempt * 500L)
        }
        return null
    }

    // k endpoints answer sealed, callers parse the text into their own type
    private suspend fun fetchText(url: String, k: Boolean = false): String? {
        val fullUrl = if (k) "$url${if (url.contains('?')) '&' else '?'}k=1" else url
        val body = getBody(fullUrl, siteHeaders()) ?: return null
        val plain = GoTakuCrypto.unseal(body) ?: body
        return String(plain, Charsets.UTF_8)
    }

    class ManifestInfo(
        val title: String?,
        val source: String,
        val token: String,
        val expiresAt: Long,
        val keySeed: ByteArray,
        val stamp: String,
        val segmentBytes: Int
    )

    suspend fun fetchManifest(base: String, stamp: String): ManifestInfo? {
        val nonce = GoTakuCrypto.newNonce()
        val path = GoTakuCrypto.buildManifestPath(nonce, stamp)
        val body = getBody("$base/$path", siteHeaders(mapOf("Accept" to "text/plain"))) ?: return null
        val plain = GoTakuCrypto.openManifestResponse(nonce, String(body, Charsets.UTF_8)) ?: return null
        return try {
            val node = parseJson<ManifestResponse>(String(plain, Charsets.UTF_8))
            val source = node.source ?: return null
            val token = node.token ?: return null
            val seedText = node.keySeed ?: return null
            val seed = android.util.Base64.decode(
                seedText.replace('-', '+').replace('_', '/')
                    .let { it + "=".repeat((4 - it.length % 4) % 4) },
                android.util.Base64.DEFAULT
            )
            ManifestInfo(
                title = node.title,
                source = source,
                token = token,
                expiresAt = node.expiresAt,
                keySeed = seed,
                stamp = node.stamp ?: stamp,
                segmentBytes = node.obf?.segmentBytes ?: 0
            )
        } catch (e: Exception) {
            Log.d(TAG, "manifest parse failed: ${e.message}")
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    class ManifestResponse(
        val title: String? = null,
        val source: String? = null,
        val token: String? = null,
        val expiresAt: Long = 0,
        val keySeed: String? = null,
        val stamp: String? = null,
        val obf: Obf? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        class Obf(val segmentBytes: Int = 0, val version: Int = 0)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    class EmbedData(
        val url: String? = null,
        val skip: Map<String, List<Int>>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    class EmbedResponse(val data: EmbedData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    class EpisodeEntry(
        val id: String? = null,
        val number: Int? = null,
        val label: String? = null,
        val name: String? = null,
        val thumbnail_url: String? = null,
        val aired_at: String? = null,
        val is_filler: Boolean? = null,
        val sub: Boolean? = null,
        val dub: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    class EpisodesResponse(val data: List<EpisodeEntry>? = null, val meta: Meta? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        class Meta(val next_airing: NextAiring? = null)
        @JsonIgnoreProperties(ignoreUnknown = true)
        class NextAiring(val number: Int? = null, val airs_at: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    class TitleEntry(
        val id: String? = null,
        val url: String? = null,
        val watch_url: String? = null,
        val name: String? = null,
        val poster_url: String? = null,
        val backdrop_url: String? = null,
        val synopsis: String? = null,
        val format: String? = null,
        val age_rating: String? = null,
        val year: Int? = null,
        val is_adult: Boolean? = null,
        val episodes: EpisodeCounts? = null,
        val status: String? = null,
        val season: String? = null,
        val duration_minutes: Int? = null,
        val genres: List<Genre>? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        class EpisodeCounts(val latest_sub: Int? = null, val latest_dub: Int? = null, val total: Int? = null)
        @JsonIgnoreProperties(ignoreUnknown = true)
        class Genre(val id: Int? = null, val name: String? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    class TitlesResponse(
        val data: List<TitleEntry>? = null,
        val meta: Meta? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        class Meta(val page: Int? = null, val limit: Int? = null, val has_more: Boolean? = null, val total: Int? = null)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    class TitleDetailResponse(val data: TitleData? = null) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        class TitleData(val title: TitleEntry? = null)
    }

    suspend fun fetchEmbed(episodeId: String, type: String): String? {
        val text = fetchText("$API/episodes/$episodeId/embed?type=$type", k = true) ?: return null
        val parsed = try {
            parseJson<EmbedResponse>(text)
        } catch (e: Exception) {
            Log.d(TAG, "embed response parse failed: ${e.message}")
            null
        }
        return parsed?.data?.url?.takeIf { it.isNotBlank() }
    }

    suspend fun fetchEpisodes(titleId: String): List<EpisodeEntry> {
        val text = fetchText("$API/titles/$titleId/episodes", k = true) ?: return emptyList()
        val parsed = try {
            parseJson<EpisodesResponse>(text)
        } catch (e: Exception) {
            Log.d(TAG, "episode list parse failed: ${e.message}")
            null
        }
        return parsed?.data.orEmpty()
    }

    suspend fun fetchTitleDetail(titleId: String): TitleEntry? {
        val text = fetchText("$API/titles/$titleId") ?: return null
        val parsed = try {
            parseJson<TitleDetailResponse>(text)
        } catch (e: Exception) {
            Log.d(TAG, "title detail parse failed: ${e.message}")
            null
        }
        return parsed?.data?.title
    }

    suspend fun fetchTitles(params: Map<String, String>): Pair<List<TitleEntry>, Boolean> {
        val query = params.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
        val text = fetchText("$API/titles?$query") ?: return Pair(emptyList(), false)
        val parsed = try {
            parseJson<TitlesResponse>(text)
        } catch (e: Exception) {
            Log.d(TAG, "titles parse failed: ${e.message}")
            null
        }
        return Pair(parsed?.data.orEmpty(), parsed?.meta?.has_more == true)
    }
}
