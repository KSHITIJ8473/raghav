package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

object MMVidout {

    private const val TAG = "MM_Vidout"

    // stream hosts 403 unless referred from vidout
    const val REFERER = "https://vidout.pages.dev/"
    private const val GITHUB_RAW = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main"

    private val LANG_NAMES = mapOf(
        "eng" to "English", "hin" to "Hindi", "spa" to "Spanish", "fre" to "French",
        "ger" to "German", "ita" to "Italian", "por" to "Portuguese", "rus" to "Russian",
        "zho" to "Chinese", "ara" to "Arabic", "kor" to "Korean", "jpn" to "Japanese",
        "tam" to "Tamil", "tel" to "Telugu", "kan" to "Kannada", "mal" to "Malayalam",
    )

    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            // movie: /movie/{imdb|tmdb} ; tv: /tv/{tmdb}/s{S}/e{E}
            val tvMatch = Regex("/tv/(\\d+)/(?:s(\\d+)|(\\d+))/(?:e(\\d+)|(\\d+))", RegexOption.IGNORE_CASE)
                .find(embedUrl)
            val movieMatch = Regex("/movie/(tt\\d+|\\d+)", RegexOption.IGNORE_CASE).find(embedUrl)

            var streamUrl: String? = null
            var tmdbId: String? = null
            var season: Int? = null
            var episode: Int? = null

            if (tvMatch != null) {
                tmdbId = tvMatch.groupValues[1]
                season = (tvMatch.groupValues[2].ifEmpty { tvMatch.groupValues[3] }).toIntOrNull()
                episode = (tvMatch.groupValues[4].ifEmpty { tvMatch.groupValues[5] }).toIntOrNull()
                if (tmdbId == null || season == null || episode == null) return false
                val body = MMNet.getText("$GITHUB_RAW/hls/tv/$tmdbId/S$season.json") ?: return false
                // {"1": "https://...", "2": ...}
                streamUrl = Regex("\"$episode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?.let { MMNet.deEsc(it) }
            } else if (movieMatch != null) {
                val id = movieMatch.groupValues[1]
                tmdbId = if (id.startsWith("tt")) {
                    MMNxsha.imdbToTmdb(id, "movie")
                } else {
                    id
                } ?: return false
                streamUrl = MMNet.getText("$GITHUB_RAW/hls/movie/$tmdbId")?.trim()
                    ?.takeIf { it.startsWith("http") }
            } else {
                return false
            }

            var url = streamUrl ?: return false
            url = MMNet.deEsc(url)

            // '#' entries point at embed pages and non-hls3 .txt files fall back to
            // the videasy player - neither is playable over http
            if (url.contains("#")) return false
            val lower = url.lowercase()
            if (lower.endsWith(".txt") && !lower.contains("/hls3/")) return false
            if (!lower.contains(".m3u8") && !lower.endsWith(".txt") && !lower.contains("/stream/")) {
                return false
            }

            callback(
                newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to REFERER)
                }
            )

            loadUrlsetSubtitles(url, subtitleCallback)
            loadGithubSubtitles(tmdbId, season, episode, subtitleCallback)
            return true
        } catch (e: Exception) {
            Log.d(TAG, "resolve failed: ${e.message?.take(80)}")
            return false
        }
    }

    // vtt siblings of hls3 urlset streams live on {srv}.acek-cdn.com
    private suspend fun loadUrlsetSubtitles(streamUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val m = Regex("/([^/]+)/hls3/([^/]+)/([^/]+)/([^/]+)_(?:,|[nhl]/)").find(streamUrl)
                ?: return
            val (srv, prefix, folderId, filePrefix) = m.destructured
            for (lang in LANG_NAMES.keys) {
                val name = LANG_NAMES[lang] ?: continue
                val cdn = "https://$srv.acek-cdn.com/vtt/$prefix/$folderId/${filePrefix}_$lang.vtt"
                subtitleCallback(newSubtitleFile(name, cdn) {
                    this.headers = mapOf("Referer" to REFERER)
                })
            }
        } catch (e: Exception) {
            Log.d(TAG, "urlset subs: ${e.message?.take(60)}")
        }
    }

    // sub/movie/{tmdb}/subtitles.json or sub/tv/{tmdb}/{s}/{e}/subtitles.json
    private suspend fun loadGithubSubtitles(
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (tmdbId == null) return
        try {
            val path = if (season != null && episode != null) {
                "sub/tv/$tmdbId/$season/$episode/subtitles.json"
            } else {
                "sub/movie/$tmdbId/subtitles.json"
            }
            val body = MMNet.getText("$GITHUB_RAW/$path") ?: return
            for (m in Regex("\"([a-z]{2})\"\\s*:\\s*\"(https?[^\"]+)\"").findAll(body)) {
                val name = m.groupValues[1].uppercase()
                val url = MMNet.deEsc(m.groupValues[2])
                subtitleCallback(newSubtitleFile(name, url) {})
            }
        } catch (e: Exception) {
            Log.d(TAG, "github subs: ${e.message?.take(60)}")
        }
    }
}
