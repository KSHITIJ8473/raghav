package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.google.gson.JsonParser
import com.lagradost.api.Log

// ── BASE MEGAPLAY EXTRACTOR ──────────────────────────────────────────
open class MegaPlayBaseExtractor(
    override val name: String,
    override val mainUrl: String
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
        )

        val pageHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://9anime.org.lv/")
        )

        val doc = try {
            app.get(url, headers = pageHeaders).document
        } catch (e: Exception) {
            Log.e(name, "Failed to load player iframe: ${e.message}")
            return
        }

        val playerEl = doc.selectFirst("#megaplay-player")
        val streamId = playerEl?.attr("data-id")
            ?: playerEl?.attr("data-realid")
            ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1)
            ?: return

        val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"

        val ajaxHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to url,
        )

        val jsonText = try {
            app.get(
                "$mainUrl/stream/getSources?id=$streamId&type=$type",
                headers = ajaxHeaders,
                referer = url
            ).text
        } catch (e: Exception) {
            Log.e(name, "getSources failed: ${e.message}")
            return
        }

        val root = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (e: Exception) {
            Log.e(name, "Failed to parse sources JSON: ${e.message}")
            null
        } ?: return

        val m3u8 = root.getAsJsonObject("sources")?.get("file")?.asString
        if (m3u8.isNullOrBlank()) {
            Log.e(name, "No m3u8 in response for id=$streamId")
            return
        }

        val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders)
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = playbackHeaders
                }
            )
        }

        try {
            val tracks = root.getAsJsonArray("tracks")
            if (tracks != null) {
                for (element in tracks) {
                    val track = element.asJsonObject
                    val kind = track.get("kind")?.asString ?: continue
                    if (kind != "captions" && kind != "subtitles") continue
                    val file = track.get("file")?.asString ?: continue
                    val label = track.get("label")?.asString ?: "Unknown"
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = playbackHeaders
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

class NineAnimeMegaPlay : MegaPlayBaseExtractor("MegaPlay", "https://megaplay.buzz")
class NineAnimeVidWish : MegaPlayBaseExtractor("VidWish", "https://vidwish.live")

// ── CUSTOM VIDMOLY EXTRACTOR ─────────────────────────────────────────
class NineAnimeVidmoly : ExtractorApi() {
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.biz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: "https://9anime.org.lv/")
        )
        val res = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            Log.e(name, "Failed to load Vidmoly iframe: ${e.message}")
            return
        }
        val html = res.text

        // Extract stream m3u8 using a robust regex that matches master.m3u8 containing commas
        val m3u8Regex = Regex("""file\s*:\s*['"](https?://[^'"]+master\.m3u8[^'"]*)['"]""")
        val m3u8 = m3u8Regex.find(html)?.groupValues?.get(1) ?: return

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val generated = M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = playbackHeaders)
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                    this.headers = playbackHeaders
                }
            )
        }
    }
}

// ── CUSTOM MOON EXTRACTOR ────────────────────────────────────────────
class NineAnimeMoon : ExtractorApi() {
    override val name = "Moon"
    override val mainUrl = "https://bysesayeveum.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 20_000L
            )
            val resolved = app.get(url, referer = referer ?: "https://9anime.org.lv/", interceptor = resolver).url
            val headers = mapOf("Referer" to url)
            
            if (resolved.contains(".m3u8", ignoreCase = true)) {
                M3u8Helper.generateM3u8(name, resolved, url, headers = headers).forEach(callback)
            } else if (resolved.contains(".mp4", ignoreCase = true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = resolved,
                        type = INFER_TYPE
                    ) {
                        quality = getQualityFromName(resolved)
                        this.headers = headers
                    }
                )
            }
        }.onFailure { error ->
            Log.e(name, "WebView extraction failed: ${error.message}")
        }
    }
}
