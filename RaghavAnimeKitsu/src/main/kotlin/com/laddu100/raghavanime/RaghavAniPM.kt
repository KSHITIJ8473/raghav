package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class RaghavAniPM : MainAPI() {
    override var mainUrl = "https://ani.pm"
    override var name = "AniPM"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private companion object {
        const val SETTLAR_EMBED = "https://embed.settlar.io"
        const val SETTLAR_REFERER = "https://embed.settlar.io/"
        const val MEGAPLAY_REFERER = "https://megaplay.buzz/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private class AniPMEntry(val anilistId: Int? = null, val seriesId: Int? = null)

    private fun headers(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Referer" to referer
    )

    private suspend fun getJson(url: String, referer: String = "$mainUrl/"): String? {
        return try {
            val res = app.get(url, headers = headers(referer), timeout = 30_000L)
            if (res.code == 200) res.text else {
                Log.e("RaghavAnimeKitsu", "[AniPM] ${url.substringBefore('?')} answered ${res.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[AniPM] ${url.substringBefore('?')} failed: ${e.message}")
            null
        }
    }

    private suspend fun bootstrap(
        entry: AniPMEntry,
        episode: Int,
        lang: String
    ): JsonNode? {
        val idPart = when {
            entry.anilistId != null -> "anilist/${entry.anilistId}"
            entry.seriesId != null -> "settlar/${entry.seriesId}"
            else -> return null
        }
        val url = "$mainUrl/api/anime/playback-bootstrap/$idPart?ep=$episode&lang=$lang&backup=1"
        val text = getJson(url) ?: return null
        return try {
            parseJson<JsonNode>(text)
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[AniPM] bootstrap parse failed: ${e.message}")
            null
        }
    }

    private suspend fun settlarSession(selection: String, episode: Int, channel: String): String? {
        val url = "$mainUrl/api/anime/settlar/session" +
            "?selection=${encode(selection)}&provider=anipm&ep=$episode&channel=$channel&telemetry=0"
        val text = getJson(url) ?: return null
        return try {
            parseJson<JsonNode>(text).path("embedUrl").asText("").ifBlank { null }
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[AniPM] settlar session parse failed: ${e.message}")
            null
        }
    }

    private suspend fun settlarResolve(embedUrl: String): JsonNode? {
        val token = Regex("t=([^&]+)").find(embedUrl)?.groupValues?.get(1) ?: return null
        val text = getJson("$SETTLAR_EMBED/api/embed/session?t=$token", "$SETTLAR_EMBED/embed/v1")
            ?: return null
        return try {
            parseJson<JsonNode>(text)
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[AniPM] settlar resolve parse failed: ${e.message}")
            null
        }
    }

    private fun cleanTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun findSeriesByTitle(title: String?, jpTitle: String?): Int? {
        val queries = listOfNotNull(title, jpTitle).filter { it.isNotBlank() }
        if (queries.isEmpty()) return null
        val targets = queries.map { cleanTitle(it) }

        for (q in queries) {
            val text = getJson("$mainUrl/api/anime/search?q=${encode(q)}") ?: continue
            val items = try {
                parseJson<JsonNode>(text).path("items")
            } catch (e: Exception) {
                Log.e("RaghavAnimeKitsu", "[AniPM] search parse failed: ${e.message}")
                null
            } ?: continue
            if (!items.isArray || items.size() == 0) continue

            val candidates = items.mapNotNull { node ->
                val id = node.path("id").asInt(0)
                val name = node.path("title").asText("")
                if (id > 0 && name.isNotBlank()) id to cleanTitle(name) else null
            }
            candidates.firstOrNull { it.second in targets }?.let { return it.first }
            candidates.firstOrNull { c ->
                targets.any { it.contains(c.second) || c.second.contains(it) }
            }?.let { return it.first }
        }
        return null
    }

    suspend fun loadLinksByAnilistId(
        anilistId: Int,
        title: String?,
        jpTitle: String?,
        episode: Int,
        isDub: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = if (isDub) "dub" else "sub"

        var entry: AniPMEntry? = if (anilistId > 0) AniPMEntry(anilistId = anilistId) else null
        var root = entry?.let { bootstrap(it, episode, channel) }
        if (root == null) {
            entry = findSeriesByTitle(title, jpTitle)?.let { AniPMEntry(seriesId = it) }
                ?: return false
            root = bootstrap(entry, episode, channel) ?: return false
        }
        val resolvedRoot = root ?: return false

        if (resolvedRoot.path("effectiveLanguage").asText("") != channel) return false

        val packageAnilist = if (anilistId > 0) anilistId
        else resolvedRoot.path("core").path("anilistId").asText("").toIntOrNull() ?: 0

        val found = AtomicBoolean(false)
        val seenLinks = ConcurrentHashMap.newKeySet<String>()
        val seenSubUrls = ConcurrentHashMap.newKeySet<String>()
        val seenSubLabels = ConcurrentHashMap.newKeySet<String>()

        coroutineScope {
            listOf(
                async {
                    try {
                        if (emitSettlar(resolvedRoot, episode, channel, seenLinks, seenSubUrls, seenSubLabels, subtitleCallback, callback)) {
                            found.set(true)
                        }
                    } catch (e: Exception) {
                        Log.e("RaghavAnimeKitsu", "[AniPM] settlar failed: ${e.message}")
                    }
                },
                async {
                    try {
                        if (emitBackup(resolvedRoot, seenLinks, subtitleCallback, callback)) {
                            found.set(true)
                        }
                    } catch (e: Exception) {
                        Log.e("RaghavAnimeKitsu", "[AniPM] backup failed: ${e.message}")
                    }
                },
                async {
                    try {
                        if (packageAnilist > 0 && emitHardsub(
                                packageAnilist, resolvedRoot, episode, channel,
                                seenLinks, seenSubUrls, seenSubLabels, subtitleCallback, callback
                            )
                        ) {
                            found.set(true)
                        }
                    } catch (e: Exception) {
                        Log.e("RaghavAnimeKitsu", "[AniPM] hardsub failed: ${e.message}")
                    }
                }
            ).awaitAll()
        }
        return found.get()
    }

    private suspend fun emitSubtitle(
        label: String,
        url: String,
        subHeaders: Map<String, String>,
        seenUrls: MutableSet<String>,
        seenLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!url.startsWith("http")) return
        if (!seenUrls.add(url)) return
        if (!seenLabels.add(label.trim().lowercase())) return
        try {
            subtitleCallback.invoke(newSubtitleFile(label, url) {
                this.headers = subHeaders
            })
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[AniPM] subtitle emit failed: ${e.message}")
        }
    }

    private suspend fun emitSettlar(
        root: JsonNode,
        episode: Int,
        channel: String,
        seenLinks: MutableSet<String>,
        seenSubUrls: MutableSet<String>,
        seenSubLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        label: String = "AniPM"
    ): Boolean {
        val selection = root.path("settlarSelection").asText("")
        if (selection.isBlank()) return false

        val embedUrl = settlarSession(selection, episode, channel) ?: return false
        val stream = settlarResolve(embedUrl) ?: return false
        val master = stream.path("source").asText("")
        if (!master.startsWith("http")) return false

        val playHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to SETTLAR_REFERER
        )
        for (sub in stream.path("subtitles")) {
            emitSubtitle(
                sub.path("label").asText("").ifBlank { "Subtitle" },
                sub.path("url").asText(""),
                playHeaders, seenSubUrls, seenSubLabels, subtitleCallback
            )
        }

        if (!seenLinks.add(master)) return true
        callback.invoke(
            newExtractorLink(name, label, master, type = ExtractorLinkType.M3U8) {
                referer = SETTLAR_REFERER
                headers = playHeaders
            }
        )
        return true
    }

    private suspend fun emitBackup(
        root: JsonNode,
        seenLinks: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = root.path("backupEmbed")
        if (!embed.path("available").asBoolean(false)) return false
        val embedUrl = embed.path("url").asText("")
        if (!embedUrl.startsWith("http")) return false

        val stream = MegaPlayHelper.resolveStream(embedUrl, "$mainUrl/", "AniPM") ?: return false
        if (!seenLinks.add(stream.m3u8)) return true

        // codec-less master variants crash the ffmpeg renderer, emitLinks sidesteps the master entirely
        return MegaPlayHelper.emitLinks(
            name, "MegaPlay", stream.m3u8, MEGAPLAY_REFERER,
            stream.subtitles, subtitleCallback, callback
        )
    }

    private suspend fun emitHardsub(
        anilistId: Int,
        root: JsonNode,
        episode: Int,
        channel: String,
        seenLinks: MutableSet<String>,
        seenSubUrls: MutableSet<String>,
        seenSubLabels: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = getJson("$mainUrl/api/anime/anipm-server/_packages?anilistId=$anilistId") ?: return false
        val packages = try {
            parseJson<JsonNode>(text)
        } catch (e: Exception) {
            Log.e("RaghavAnimeKitsu", "[AniPM] packages parse failed: ${e.message}")
            null
        } ?: return false
        val field = if (channel == "dub") "dubhard" else "subhard"
        if (!packages.path("episodes").path(episode.toString()).path(field).asBoolean(false)) return false
        return emitSettlar(
            root, episode, "${channel}hard", seenLinks, seenSubUrls, seenSubLabels,
            subtitleCallback, callback, "AniPM Hardsub"
        )
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
