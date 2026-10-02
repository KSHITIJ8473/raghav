package com.justplay

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

internal object NetNaijaSite {
    private const val API = "https://h5-api.aoneroom.com"
    private const val BFF = "$API/wefeed-h5api-bff"
    private const val DEFAULT_SITE = "https://netnaija.film"
    private const val NA_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var token: String? = null
    private val tokenMutex = Mutex()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaDub(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("type") val type: Int? = null,
        @JsonProperty("original") val original: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSubject(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("dubs") val dubs: List<NaDub>? = null,
        @JsonProperty("detailPath") val detailPath: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSearchData(val items: List<NaSubject>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSearchResponse(val data: NaSearchData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaSeason(
        @JsonProperty("se") val se: Int? = null,
        @JsonProperty("maxEp") val maxEp: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaResource(val seasons: List<NaSeason>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaDetailData(
        val subject: NaSubject? = null,
        val resource: NaResource? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaDetailResponse(val data: NaDetailData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaStream(
        val format: String? = null,
        val id: String? = null,
        val url: String? = null,
        val resolutions: String? = null,
        val size: String? = null,
        val vipLocked: Boolean? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaPlayData(
        val streams: List<NaStream>? = null,
        val hls: List<NaStream>? = null,
        val dash: List<NaStream>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaPlayResponse(val data: NaPlayData? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaCaption(
        val lan: String? = null,
        val lanName: String? = null,
        val url: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaCaptionData(val captions: List<NaCaption>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NaCaptionResponse(val data: NaCaptionData? = null)

    private fun xClientToken(): String {
        val ts = System.currentTimeMillis() / 1000
        val reversed = ts.toString().reversed()
        val md5 = MessageDigest.getInstance("MD5").digest(reversed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$ts,$md5"
    }

    private fun baseHeaders(site: String): Map<String, String> = mapOf(
        "User-Agent" to NA_UA,
        "Accept" to "application/json",
        "Origin" to site,
        "Referer" to "$site/",
        "X-Request-Lang" to "en",
        "X-Client-Info" to """{"timezone":"Asia/Kolkata"}"""
    )

    // the api hands out a fresh anonymous jwt in the x-user header whenever the
    // old one is close to expiring, reading it on every answer keeps it alive
    private fun readToken(response: com.lagradost.nicehttp.NiceResponse) {
        try {
            val xUser = response.headers["x-user"] ?: return
            if (xUser.isBlank()) return
            JSONObject(xUser).optString("token").takeIf { it.isNotBlank() }?.let { token = it }
        } catch (_: Exception) {}
    }

    private suspend fun ensureToken(site: String): String? {
        token?.let { return it }
        return tokenMutex.withLock {
            token?.let { return@withLock it }
            try {
                val response = app.get(
                    "$BFF/subject/trending?page=1&perPage=1",
                    headers = baseHeaders(site) + mapOf("X-Client-Token" to xClientToken()),
                    timeout = 15L
                )
                readToken(response)
                token
            } catch (_: Exception) {
                null
            }
        }
    }

    // list endpoints want the bearer and the play endpoint wants the cookie, so
    // both ride along on every call
    private suspend fun authHeaders(site: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = baseHeaders(site).toMutableMap()
        val t = ensureToken(site)
        if (!t.isNullOrBlank()) {
            h["Cookie"] = "token=$t"
            h["Authorization"] = "Bearer $t"
        } else {
            h["X-Client-Token"] = xClientToken()
        }
        h.putAll(extra)
        return h
    }

    private fun dubLabel(dub: NaDub): String {
        if (dub.original == true) return "Original"
        val base = (dub.lanName ?: "").substringBefore(" dub").substringBefore(" sub").trim()
        val pretty = when (base.lowercase()) {
            "ptbr" -> "PT-BR"
            "esla" -> "ES-LA"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        if (pretty.isBlank()) return "Original"
        return if (dub.type == 1) "$pretty Hardsub" else pretty
    }

    // the api search is fuzzy, privileged movies show up for a prestige query,
    // so only exact normalized titles of the right type and year may resolve
    private fun pickSubject(items: List<NaSubject>, res: PlayLinkData): NaSubject? {
        val wantTv = res.season != null
        val title = res.title ?: return null
        val typeOk: (NaSubject) -> Boolean = {
            when (it.subjectType) {
                1 -> !wantTv
                2 -> wantTv
                else -> false
            }
        }
        fun yearOk(s: NaSubject): Boolean {
            val itemYear = s.releaseDate?.take(4)?.toIntOrNull() ?: return true
            val want = res.matchYear ?: return true
            return kotlin.math.abs(itemYear - want) <= 1
        }
        return items.firstOrNull { typeOk(it) && yearOk(it) && PlayNet.normalizeTitle(it.title) == PlayNet.normalizeTitle(title) }
            ?: items.firstOrNull { typeOk(it) && yearOk(it) && PlayNet.titleMatches(it.title, title) }
    }

    suspend fun invoke(
        res: PlayLinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val site = FirebaseDomainHelper.getDomain("justplay_netnaija")
                ?: FirebaseDomainHelper.getDomain("netnaija")
                ?: DEFAULT_SITE
            val title = res.title ?: return

            val searchRes = app.post(
                "$BFF/subject/search",
                headers = authHeaders(site),
                json = mapOf("keyword" to title, "page" to 1, "perPage" to 30),
                timeout = 15L
            )
            readToken(searchRes)
            val items = try {
                AppUtils.parseJson<NaSearchResponse>(searchRes.text).data?.items.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val subject = pickSubject(items, res) ?: return
            val detailPath = subject.detailPath ?: return

            val detailRes = app.get(
                "$BFF/detail",
                params = mapOf("detailPath" to detailPath),
                headers = authHeaders(site),
                timeout = 15L
            )
            readToken(detailRes)
            val detail = try {
                AppUtils.parseJson<NaDetailResponse>(detailRes.text).data
            } catch (_: Exception) {
                null
            } ?: return
            val subj = detail.subject ?: return
            if (res.season != null) {
                val seasonOk = detail.resource?.seasons.orEmpty().any { it.se == res.season }
                if (!seasonOk) return
            }

            val dubs = (subj.dubs ?: emptyList())
                .ifEmpty {
                    listOf(
                        NaDub(
                            subjectId = subj.subjectId,
                            detailPath = detailPath,
                            lanName = "Original",
                            type = 0,
                            original = true
                        )
                    )
                }
                .take(6)

            coroutineScope {
                dubs.forEach { dub ->
                    async(Dispatchers.IO) {
                        val dubSubjectId = dub.subjectId ?: return@async
                        val dubDetailPath = dub.detailPath ?: detailPath
                        val audioLabel = dubLabel(dub)
                        try {
                            val playRes = app.get(
                                "$BFF/subject/play",
                                params = mapOf(
                                    "subjectId" to dubSubjectId,
                                    "se" to "${res.season ?: 0}",
                                    "ep" to "${res.episode ?: 0}",
                                    "detailPath" to dubDetailPath
                                ),
                                headers = authHeaders(
                                    site,
                                    mapOf(
                                        "X-Source" to "webNetnaijaSite",
                                        "Referer" to "$site/videoPlayPage/$dubDetailPath"
                                    )
                                ),
                                timeout = 20L
                            )
                            readToken(playRes)
                            val play = try {
                                AppUtils.parseJson<NaPlayResponse>(playRes.text).data
                            } catch (_: Exception) {
                                null
                            } ?: return@async

                            play.streams.orEmpty().filter { it.vipLocked != true }.forEach { stream ->
                                val url = stream.url ?: return@forEach
                                val quality = stream.resolutions?.toIntOrNull() ?: Qualities.Unknown.value
                                val sizeText = stream.size?.toLongOrNull()?.let { if (it > 0) "${it / 1048576} MB" else "" } ?: ""
                                val parts = listOfNotNull(
                                    audioLabel.takeIf { it.isNotBlank() },
                                    stream.resolutions?.let { r -> "${r}p" },
                                    sizeText.takeIf { it.isNotBlank() }
                                )
                                val name = "[NetNaija] - " + parts.joinToString(" · ")
                                callback(
                                    newExtractorLink(
                                        "[NetNaija]",
                                        name,
                                        url,
                                        ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = quality
                                        this.headers = mapOf(
                                            "Referer" to "$site/",
                                            "Origin" to site,
                                            "User-Agent" to NA_UA
                                        )
                                    }
                                )
                            }

                            play.dash.orEmpty().forEach { stream ->
                                val url = stream.url ?: return@forEach
                                val dashName = if (audioLabel.isBlank()) {
                                    "[NetNaija] - DASH"
                                } else {
                                    "[NetNaija] - $audioLabel · DASH"
                                }
                                callback(
                                    newExtractorLink(
                                        "[NetNaija]",
                                        dashName,
                                        url,
                                        ExtractorLinkType.DASH
                                    ) {
                                        this.headers = mapOf(
                                            "Referer" to "$site/",
                                            "Origin" to site,
                                            "User-Agent" to NA_UA
                                        )
                                    }
                                )
                            }

                            play.hls.orEmpty().forEach { stream ->
                                val url = stream.url ?: return@forEach
                                try {
                                    M3u8Helper.generateM3u8(
                                        "[NetNaija] - $audioLabel",
                                        url,
                                        "$site/",
                                        headers = mapOf("User-Agent" to NA_UA)
                                    ).forEach { hlsLink ->
                                        callback(
                                            newExtractorLink(
                                                "[NetNaija]",
                                                hlsLink.name,
                                                hlsLink.url,
                                                hlsLink.type
                                            ) {
                                                this.quality = hlsLink.quality
                                                this.headers = mapOf("User-Agent" to NA_UA)
                                            }
                                        )
                                    }
                                } catch (e: Exception) {
                                    val hlsName = if (audioLabel.isBlank()) {
                                        "[NetNaija] - HLS"
                                    } else {
                                        "[NetNaija] - $audioLabel · HLS"
                                    }
                                    callback(
                                        newExtractorLink(
                                            "[NetNaija]",
                                            hlsName,
                                            url,
                                            ExtractorLinkType.M3U8
                                        ) {
                                            this.headers = mapOf("User-Agent" to NA_UA)
                                        }
                                    )
                                }
                            }

                            val firstStreamId = play.streams?.firstOrNull { it.vipLocked != true }?.id
                            if (firstStreamId != null) {
                                try {
                                    val capRes = app.get(
                                        "$BFF/subject/caption",
                                        params = mapOf(
                                            "format" to "MP4",
                                            "id" to firstStreamId,
                                            "subjectId" to dubSubjectId,
                                            "detailPath" to dubDetailPath
                                        ),
                                        headers = authHeaders(site),
                                        timeout = 15L
                                    ).text
                                    AppUtils.parseJson<NaCaptionResponse>(capRes).data?.captions?.forEach { cap ->
                                        if (!cap.url.isNullOrBlank()) {
                                            subtitleCallback(
                                                com.lagradost.cloudstream3.newSubtitleFile(
                                                    cap.lanName ?: cap.lan ?: "English",
                                                    cap.url
                                                ) {}
                                            )
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
