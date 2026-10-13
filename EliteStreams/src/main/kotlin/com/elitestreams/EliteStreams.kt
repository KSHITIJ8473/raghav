package com.elitestreams

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.raghav.donation.DonationManager

class EliteStreams : MainAPI() {

    override var mainUrl = "https://premiumplugx.com"
    override var name = "Elite Streams"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "hi"

    companion object {
        private const val KEY_PREFIX_SRC = "ES_SRC_"

        fun sourceEnabled(sourceId: String): Boolean = try {
            CloudStreamApp.getKey<Boolean>(KEY_PREFIX_SRC + sourceId) ?: true
        } catch (_: Exception) {
            true
        }

        fun setSourceEnabled(sourceId: String, on: Boolean) {
            try {
                CloudStreamApp.setKey(KEY_PREFIX_SRC + sourceId, on)
            } catch (_: Exception) {}
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        DonationManager.checkAndShow()
        if (page > 1) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        }
        val channels = EsFeeds.allChannels().filter { sourceEnabled(it.sourceId) }
        val lists = mutableListOf<HomePageList>()
        EsFeeds.sources.forEach { source ->
            val items = channels.filter { it.sourceId == source.id }.map { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                lists.add(HomePageList(source.label, items, isHorizontalImages = true))
            }
        }
        listOf(
            "Sports" to EsFeeds.CAT_SPORTS,
            "Movies" to EsFeeds.CAT_MOVIES,
            "Kids" to EsFeeds.CAT_KIDS
        ).forEach { (label, category) ->
            val items = channels.filter { it.category == category }.map { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                lists.add(HomePageList(label, items, isHorizontalImages = true))
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    private fun EsFeeds.ChannelData.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(name, toPayload().toJson(), TvType.Live) {
            posterUrl = logo
        }
    }

    private fun EsFeeds.ChannelData.toPayload(): ChannelPayload {
        return ChannelPayload(
            key = key,
            name = name,
            sourceId = sourceId,
            sourceLabel = sourceLabel,
            logo = logo,
            jioId = jioId,
            streams = streams
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return EsFeeds.allChannels()
            .filter { sourceEnabled(it.sourceId) }
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(60)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val payload = try {
            parseJson<ChannelPayload>(url)
        } catch (_: Exception) {
            return null
        }
        return newLiveStreamLoadResponse(payload.name, url, url) {
            this.posterUrl = payload.logo
            this.plot = payload.sourceLabel
        }
    }

    private fun hexToBase64Url(hex: String): String? {
        val clean = hex.replace("-", "").trim()
        if (clean.length % 2 != 0 || !clean.matches(Regex("^[0-9a-fA-F]+$"))) return null
        return try {
            val bytes = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun emitStream(
        stream: EsFeeds.StreamData,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!stream.url.startsWith("http")) return
        var kid = stream.kid
        var key = stream.key
        // multi audio channels ship several clearkey pairs, the video track
        // kid from the manifest picks the right one
        if (stream.kids != null && stream.kids.size > 1 && kid != null) {
            val mpd = try {
                app.get(stream.url, headers = stream.headers, timeout = 15L).text
            } catch (_: Exception) {
                null
            }
            val mpdKid = mpd?.let {
                Regex("""cenc:default_KID=["']([0-9a-fA-F-]{36})["']""").find(it)
                    ?.groupValues?.get(1)?.replace("-", "")?.lowercase()
            }
            if (mpdKid != null && stream.kids.containsKey(mpdKid)) {
                kid = mpdKid
                key = stream.kids[mpdKid]
            }
        }
        val kid64 = kid?.let { hexToBase64Url(it) }
        val key64 = key?.let { hexToBase64Url(it) }
        if (kid64 != null && key64 != null) {
            callback(
                newDrmExtractorLink(
                    this.name,
                    label,
                    stream.url,
                    INFER_TYPE,
                    CLEARKEY_UUID
                ) {
                    this.quality = stream.quality ?: Qualities.Unknown.value
                    if (stream.headers.isNotEmpty()) this.headers = stream.headers
                    this.key = key64
                    this.kid = kid64
                }
            )
        } else {
            callback(
                newExtractorLink(this.name, label, stream.url, INFER_TYPE) {
                    this.quality = stream.quality ?: Qualities.Unknown.value
                    if (stream.headers.isNotEmpty()) this.headers = stream.headers
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = try {
            parseJson<ChannelPayload>(data)
        } catch (_: Exception) {
            return false
        }

        if (payload.streams.isNotEmpty()) {
            payload.streams.forEach { stream ->
                val label = if (payload.streams.size > 1 && stream.quality != null) {
                    "${payload.name} ${stream.quality}p"
                } else {
                    payload.name
                }
                emitStream(stream, label, callback)
            }
            return true
        }

        // static jio channels resolve at play time so the akamai cookie and
        // clearkey pair are as fresh as the api can make them
        val jioId = payload.jioId ?: return false
        val baseName = payload.name.removeSuffix(" (${payload.sourceLabel})")
        val stream = EsFeeds.resolveJio(jioId, baseName) ?: return false
        emitStream(stream, payload.name, callback)
        return true
    }

    data class ChannelPayload(
        val key: String,
        val name: String,
        val sourceId: String,
        val sourceLabel: String,
        val logo: String?,
        val jioId: String? = null,
        val streams: List<EsFeeds.StreamData> = emptyList()
    )
}
