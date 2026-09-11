package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URL
import java.net.URLDecoder

// embed hosts shared across the aggregated sources: vivibebe/bibiemb inline the
// playlist, the otaku clones pack it with jsunpacker, megaplay needs its ajax flow
object RaghavEmbeds {

    private const val TAG = "RaghavAnime"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")

    fun hostOf(url: String): String = try {
        URL(url).host
    } catch (e: Exception) {
        ""
    }

    suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        label: String,
        sourceTag: String,
        audio: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (embedUrl.isBlank()) return false
        val host = hostOf(embedUrl)
        return try {
            when {
                host.endsWith("megaplay.buzz") || host.endsWith("vidwish.live") ||
                    host.endsWith("vidtube.site") || host.contains("megaplay-") ->
                    resolveMegaPlayFamily(embedUrl, referer, label, sourceTag, subtitleCallback, callback)

                host.endsWith("vivibebe.site") || host.endsWith("bibiemb.xyz") ->
                    resolveInlineM3u8(embedUrl, referer, label, sourceTag, subtitleCallback, callback)

                host.endsWith("flixcloud.cc") ->
                    resolveFlix(embedUrl, referer, label, sourceTag, audio, subtitleCallback, callback)

                host.endsWith("otakuhg.site") || host.endsWith("otakuvid.online") || host.endsWith("kwik.cx") ->
                    resolvePackedM3u8(embedUrl, referer, label, sourceTag, subtitleCallback, callback)

                host.endsWith("playmogo.com") -> {
                    passSubtitle(embedUrl, subtitleCallback)
                    loadExtractor(embedUrl, referer, subtitleCallback, callback)
                }

                else -> resolveGeneric(embedUrl, referer, label, sourceTag, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$sourceTag] embed '$label' ($host) failed: ${e.message}")
            false
        }
    }

    private suspend fun resolveMegaPlayFamily(
        embedUrl: String,
        referer: String,
        label: String,
        sourceTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = MegaPlayHelper.resolveStream(embedUrl, referer, sourceTag)
            ?: return false
        return MegaPlayHelper.emitLinks(
            sourceTag, label, stream.m3u8, "https://${hostOf(embedUrl)}/",
            stream.subtitles, subtitleCallback, callback
        )
    }

    private suspend fun resolveInlineM3u8(
        embedUrl: String,
        referer: String,
        label: String,
        sourceTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        passSubtitle(embedUrl, subtitleCallback)
        val html = fetchEmbedHtml(embedUrl, referer) ?: return false
        val m3u8 = m3u8Regex.find(html)?.value ?: return false
        if (!streamPlayable(m3u8, "https://${hostOf(embedUrl)}/")) {
            Log.d(TAG, "[$sourceTag] stream behind '$label' is not playable, skipping")
            return false
        }
        return emitM3u8(label, m3u8, "https://${hostOf(embedUrl)}/", subtitleCallback, callback)
    }

    // flixcloud embeds wrap the playlist in a wasm derived cipher; the local
    // proxy relays the decrypted segments so the player sees a normal stream
    private suspend fun resolveFlix(
        embedUrl: String,
        referer: String,
        label: String,
        sourceTag: String,
        audio: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = FlixResolver.resolve(embedUrl, referer) ?: return false
        val proxyMaster = FlixProxy.registerMaster(res.m3u8, res.masterContent, res.pkKey)
            ?: return false
        val hasEnglishAudio = res.masterContent.contains("""LANGUAGE="en"""") ||
            res.masterContent.contains("NAME=\"English\"")
        val hasOtherAudio = Regex("""TYPE=AUDIO[^\n]*LANGUAGE="(?!en)[^"]*""")
            .containsMatchIn(res.masterContent) ||
            (res.masterContent.contains("TYPE=AUDIO") && !hasEnglishAudio)
        val lang = when {
            audio == "dub" && hasEnglishAudio -> "dub"
            audio == "sub" && (hasOtherAudio || !hasEnglishAudio) -> "sub"
            audio == "dub" && !hasEnglishAudio -> "sub"
            else -> "dub"
        }
        callback.invoke(
            newExtractorLink(sourceTag, label, "$proxyMaster?lang=$lang", type = ExtractorLinkType.M3U8) {
                this.headers = mapOf("Referer" to "https://flixcloud.cc/")
            }
        )
        for (sub in res.subtitles) {
            val ext = sub.format?.uppercase()
            val subName = if (ext != null) "${sub.language ?: "Subtitle"} ($ext)" else (sub.language ?: "Subtitle")
            subtitleCallback.invoke(newSubtitleFile(subName, sub.url) {})
        }
        return true
    }

    private suspend fun resolvePackedM3u8(
        embedUrl: String,
        referer: String,
        label: String,
        sourceTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        passSubtitle(embedUrl, subtitleCallback)
        val html = fetchEmbedHtml(embedUrl, referer) ?: return false
        var m3u8 = m3u8Regex.find(html)?.value
        if (m3u8 == null) {
            m3u8 = JsPacker.parseAndUnpack(html)?.let { m3u8Regex.find(it)?.value }
        }
        if (m3u8 == null) return false
        return emitM3u8(label, m3u8, "https://${hostOf(embedUrl)}/", subtitleCallback, callback)
    }

    private suspend fun resolveGeneric(
        embedUrl: String,
        referer: String,
        label: String,
        sourceTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loaded = try {
            loadExtractor(embedUrl, referer, subtitleCallback, callback)
        } catch (e: Exception) {
            false
        }
        if (loaded) return true

        // hosts without a registered extractor still expose the playlist in
        // their page most of the time, sometimes packed
        val html = fetchEmbedHtml(embedUrl, referer) ?: return false
        var m3u8 = m3u8Regex.find(html)?.value
        if (m3u8 == null) {
            m3u8 = JsPacker.parseAndUnpack(html)?.let { m3u8Regex.find(it)?.value }
        }
        if (m3u8 == null) return false
        return emitM3u8(label, m3u8, "https://${hostOf(embedUrl)}/", subtitleCallback, callback)
    }

    private suspend fun fetchEmbedHtml(embedUrl: String, referer: String): String? {
        return try {
            app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                ),
                timeout = 15_000L
            ).text
        } catch (e: Exception) {
            null
        }
    }

    // vivibebe/bibiemb relay through third-party storage that dies quietly and
    // 403s every segment, so probe once before offering the stream
    suspend fun streamPlayable(m3u8Url: String, referer: String): Boolean {
        return try {
            val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            val master = app.get(m3u8Url, headers = headers, timeout = 15_000L).text
            if (!master.contains("#EXTM3U")) return false
            val playlistUrl: String
            val playlist: String
            if (master.contains("#EXT-X-STREAM-INF")) {
                val variant = firstEntry(master) ?: return false
                playlistUrl = relativeTo(m3u8Url, variant)
                playlist = app.get(playlistUrl, headers = headers, timeout = 15_000L).text
                if (!playlist.contains("#EXTM3U")) return false
            } else {
                playlistUrl = m3u8Url
                playlist = master
            }
            val seg = firstEntry(playlist) ?: return true
            val probe = app.get(
                relativeTo(playlistUrl, seg),
                headers = headers + mapOf("Range" to "bytes=0-0"),
                timeout = 15_000L
            )
            probe.code == 200 || probe.code == 206
        } catch (e: Exception) {
            false
        }
    }

    private fun firstEntry(playlist: String): String? =
        playlist.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun relativeTo(baseUrl: String, entry: String): String {
        if (entry.startsWith("http://") || entry.startsWith("https://")) return entry
        return baseUrl.substringBeforeLast("/") + "/" + entry.removePrefix("./")
    }

    private suspend fun emitM3u8(
        label: String,
        m3u8: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
        val generated = try {
            M3u8Helper.generateM3u8(label, m3u8, referer, headers = headers)
        } catch (e: Exception) {
            emptyList()
        }
        if (generated.isNotEmpty()) {
            generated.forEach(callback)
            return true
        }
        callback.invoke(
            newExtractorLink(label, label, m3u8, type = ExtractorLinkType.M3U8) {
                this.headers = headers
            }
        )
        return true
    }

    // embed urls from these providers carry the subtitle file as a query param
    // (sub=, caption_1=, c1_file=) which the player injects into the iframe
    private fun passSubtitle(embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val query = URL(embedUrl).query ?: return
            val sub = Regex("""(?:sub|caption_1|c1_file)=([^&]+)""").find(query)?.groupValues?.get(1)
                ?: return
            val decoded = URLDecoder.decode(sub, "UTF-8")
            val label = Regex("""(?:sub_1|c1_label)=([^&]+)""").find(query)?.groupValues?.get(1)
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "English"
            subtitleCallback.invoke(SubtitleFile(label, decoded))
        } catch (e: Exception) {
        }
    }
}
