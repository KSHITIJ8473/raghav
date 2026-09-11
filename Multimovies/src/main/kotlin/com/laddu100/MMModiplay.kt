package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

object MMModiplay {

    private const val TAG = "MM_Modiplay"

    data class CineServer(
        val embed: String,
        val platform: String,
        val name: String,
        val code: String,
    )

    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): String? {
        // the returned origin feeds gdmirror's proxy fallback
        val base = MMNet.originOf(embedUrl)
        if (base.isBlank()) return null
        val html = MMNet.getText(embedUrl, referer = "https://multimovies.casa/") ?: return base

        val servers = Regex("switchServer\\('([^']+)','([^']+)','([^']+)','([^']+)','([^']*)'")
            .findAll(html).map { m ->
                CineServer(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.distinctBy { it.code }.toList()

        if (servers.isEmpty()) {
            // no server list - treat it as a bare packer page
            MMPacker.resolvePackerEmbed(embedUrl, label, subtitleCallback, callback)
            return base
        }

        loadSubs(base, embedUrl, subtitleCallback)

        for (server in servers) {
            val linkLabel = "$label • ${server.name}"
            var handled = false
            if (server.embed.startsWith("http")) {
                try {
                    handled = MMPacker.resolvePackerEmbed(
                        server.embed, linkLabel, subtitleCallback, callback,
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "server ${server.name}: ${e.message?.take(60)}")
                    handled = false
                }
            }
            if (!handled) {
                try {
                    resolveProxyFile(base, server.platform, server.code, linkLabel, callback)
                } catch (e: Exception) {
                    Log.d(TAG, "proxy ${server.name}: ${e.message?.take(60)}")
                    continue
                }
            }
        }
        return base
    }

    suspend fun resolveProxyFile(
        base: String,
        platform: String,
        fileCode: String,
        linkLabel: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val proxyUrl = "$base/proxy.php?p=$platform&c=$fileCode&title=&site_ref=&noredirect=1"
        val page = MMNet.getText(proxyUrl, referer = base) ?: return false

        val src = Regex("var\\s+src\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let { MMNet.deEsc(it) }
        val segRef = Regex("var\\s+SEG_REF\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)?.let { MMNet.deEsc(it) }
        if (src.isNullOrBlank()) {
            return false
        }
        val masterUrl = MMNet.abs(base, src)
        val master = MMNet.getText(masterUrl, referer = base) ?: return false
        if (!master.contains("#EXTM3U")) return false

        MMPacker.emitMaster(
            master, masterUrl, base, segRef ?: base, linkLabel, callback,
        )
        return true
    }

    // the subtitle api answers null more often than not - best effort only
    private suspend fun loadSubs(base: String, embedUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val imdbId = Regex("[?&]id=(tt\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val tmdbId = Regex("[?&]id=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val season = Regex("[?&]s=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            val ep = Regex("[?&]e=(\\d+)").find(embedUrl)?.groupValues?.get(1) ?: ""
            if (imdbId.isBlank() && tmdbId.isBlank()) return
            val seen = mutableSetOf<String>()
            for (lang in listOf("en", "hi", "")) {
                val resp = MMNet.getText(
                    "$base/api/subtitle_fetch.php?tmdb_id=$tmdbId&imdb_id=$imdbId&season=$season&ep=$ep&lang=$lang",
                    referer = base,
                ) ?: continue
                val m = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(resp) ?: continue
                val url = MMNet.deEsc(m.groupValues[1])
                if (!url.startsWith("http")) continue
                val langName = Regex("\"lang\"\\s*:\\s*\"([^\"]+)\"").find(resp)
                    ?.groupValues?.get(1)?.ifBlank { null } ?: "English"
                if (seen.add(url)) {
                    subtitleCallback(newSubtitleFile(langName, url) {})
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "loadSubs: ${e.message?.take(60)}")
        }
    }
}

object MMPacker {

    suspend fun resolvePackerEmbed(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MMNet.getText(embedUrl, referer = "https://multimovies.casa/") ?: return false
        val unpacked = JsPacker.parseAndUnpack(html) ?: return false
        val base = MMNet.originOf(embedUrl)

        // hls2/hls3 are direct cdn urls; hls4 is site-relative and 403s
        // outside the site's browser context, so it stays the last resort
        val hls2 = Regex("\"hls2\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
        val hls3 = Regex("\"hls3\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)
        val hls4 = Regex("\"hls4\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)

        val master = listOfNotNull(hls2, hls3, hls4)
            .map { MMNet.deEsc(it) }
            .firstOrNull { it.isNotBlank() }
            ?.let { if (it.startsWith("http")) it else MMNet.abs(base, it) }
            ?: return false

        callback(
            newExtractorLink(label, label, master, type = ExtractorLinkType.M3U8) {
                this.headers = mapOf("Referer" to base)
            }
        )

        val seen = mutableSetOf<String>()
        for (m in Regex("file\\s*:\\s*\"(https?[^\"]+\\.vtt)\"").findAll(unpacked)) {
            val url = MMNet.deEsc(m.groupValues[1])
            if (!seen.add(url)) continue
            val lang = url.substringAfterLast('_').substringBefore('.')
            val name = when (lang.lowercase()) {
                "eng", "en" -> "English"; "hin" -> "Hindi"; "spa" -> "Spanish"
                "fre" -> "French"; "ger" -> "German"; "ita" -> "Italian"
                "por" -> "Portuguese"; "rus" -> "Russian"; "ara" -> "Arabic"
                "pol" -> "Polish"; "zho" -> "Chinese"; "kor" -> "Korean"
                "jpn" -> "Japanese"; "tam" -> "Tamil"; "tel" -> "Telugu"
                "kan" -> "Kannada"; "mal" -> "Malayalam"
                else -> lang.uppercase()
            }
            subtitleCallback(newSubtitleFile(name, url) {
                this.headers = mapOf("Referer" to base)
            })
        }
        return true
    }

    suspend fun emitMaster(
        master: String,
        masterUrl: String,
        base: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val audioTracks = Regex("#EXT-X-MEDIA:TYPE=AUDIO,[^\\n]*?NAME=\"([^\"]+)\"[^\\n]*?URI=\"([^\"]+)\"")
            .findAll(master).map { m -> m.groupValues[1] to m.groupValues[2] }.toList()
        val variants = Regex("#EXT-X-STREAM-INF:[^\\n]*?RESOLUTION=(\\d+)x(\\d+)[^\\n]*\\n\\s*([^\\n]+)")
            .findAll(master).map { m ->
                Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].trim())
            }.toList()

        if (audioTracks.isNotEmpty()) {
            // urlset naming: index-f1-v1-a1, index-f2-v1-a1 ... audio N maps to -aN
            val audioSpecific = variants.isNotEmpty() &&
                audioTracks.indices.all { idx -> variants.any { it.third.contains("-a${idx + 1}") } }
            if (audioSpecific) {
                audioTracks.forEachIndexed { idx, (name, uri) ->
                    val best = variants.filter { it.third.contains("-a${idx + 1}") }.maxByOrNull { it.first }
                    if (best != null) {
                        callback(
                            newExtractorLink(label, "$label • $name", MMNet.abs(base, best.third), type = ExtractorLinkType.M3U8) {
                                this.headers = mapOf("Referer" to referer)
                                this.quality = best.second
                            }
                        )
                    } else {
                        callback(
                            newExtractorLink(label, "$label • $name", MMNet.abs(base, uri), type = ExtractorLinkType.M3U8) {
                                this.headers = mapOf("Referer" to referer)
                            }
                        )
                    }
                }
                return
            }
            // muxed master with selectable audio
            callback(
                newExtractorLink(
                    label,
                    if (audioTracks.size > 1) "$label (Multi Audio)" else label,
                    masterUrl, type = ExtractorLinkType.M3U8,
                ) { this.headers = mapOf("Referer" to referer) }
            )
            return
        }

        val best = variants.maxByOrNull { it.first }
        if (best != null) {
            callback(
                newExtractorLink(label, label, MMNet.abs(base, best.third), type = ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to referer)
                    this.quality = best.second
                }
            )
        } else {
            callback(
                newExtractorLink(label, label, masterUrl, type = ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to referer)
                }
            )
        }
    }
}
