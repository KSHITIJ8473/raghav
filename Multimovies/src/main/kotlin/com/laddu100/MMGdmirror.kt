package com.laddu100

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink

object MMGdmirror {

    private const val TAG = "MM_Gdmirror"

    val PLATFORMS = mapOf(
        "smwh" to ("streamhg" to "StreamHG"),
        "flls" to ("earnvids" to "EarnVids"),
        "flmn" to ("byse" to "Byse"),
        "rpmshre" to ("rpmshare" to "RPMShare"),
        "upnshr" to ("upnshare" to "UpnShare"),
        "strmp2" to ("streamp2p" to "StreamP2P"),
    )

    suspend fun resolve(
        embedUrl: String,
        label: String,
        modiplayBase: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            // follow redirects so filesforever lands on the iqsmartgames svid page used as player base
            val resp = try {
                app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to MMNet.UA,
                        "Referer" to "https://multimovies.casa/",
                    ),
                    timeout = 30_000L,
                )
            } catch (e: Exception) {
                null
            } ?: return false
            if (!resp.isSuccessful) return false
            val html = resp.text
            val playerBaseFromRedirect = MMNet.originOf(resp.url)

            val finalId = Regex("let\\s+FinalID\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: Regex("id=\"gdmrfid\"\\s+value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: return false
            val apiBase = Regex("let\\s+api_url\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
            val myKey = Regex("let\\s+myKey\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()

            val slugsAndNames: List<Pair<String, String>> =
                if (apiBase.isNotBlank() && myKey.isNotBlank()) {
                    listFileSlugs(html, finalId, apiBase, myKey) ?: return false
                } else {
                    // svid page - no filename metadata
                    listOf(finalId to "")
                }

            val playerBase = Regex("let\\s+player_base\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
                ?: playerBaseFromRedirect.takeIf { it.isNotBlank() }
                ?: return false

            var any = false
            for ((slug, namePart) in slugsAndNames) {
                try {
                    val helper = postEmbedHelper(playerBase, slug) ?: continue
                    val mresult = Regex("\"mresult\"\\s*:\\s*\"([^\"]+)\"").find(helper)?.groupValues?.get(1)
                        ?: continue
                    val decoded = try {
                        String(Base64.decode(mresult, Base64.DEFAULT))
                    } catch (e: Exception) {
                        continue
                    }
                    val shortName = shortenName(namePart)
                    val suffix = if (shortName.isBlank()) "" else " • $shortName"
                    for (m in Regex("\"([a-z0-9]+)\"\\s*:\\s*\"([a-z0-9]+)\"").findAll(decoded)) {
                        val platform = PLATFORMS[m.groupValues[1]] ?: continue
                        val code = m.groupValues[2]
                        // direct packer embeds carry fresh tokens - try them before the proxy
                        val embedSite = directEmbedSite(helper, m.groupValues[1], code)
                        var handled = false
                        if (embedSite != null) {
                            handled = MMPacker.resolvePackerEmbed(
                                embedSite, "$label • ${platform.second}$suffix",
                                subtitleCallback, callback,
                            )
                        }
                        if (!handled && modiplayBase != null) {
                            handled = MMModiplay.resolveProxyFile(
                                modiplayBase, platform.first, code,
                                "$label • ${platform.second}$suffix", callback,
                            )
                        }
                        any = any || handled
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "slug $slug failed: ${e.message?.take(60)}")
                }
            }
            return any
        } catch (e: Exception) {
            Log.d(TAG, "resolve failed: ${e.message?.take(80)}")
            return false
        }
    }

    private suspend fun listFileSlugs(
        html: String,
        finalId: String,
        apiBase: String,
        myKey: String,
    ): List<Pair<String, String>>? {
        val idType = Regex("let\\s+idType\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: "imdbid"
        val season = Regex("let\\s+season\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val epname = Regex("let\\s+epname\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val apiUrl = if (season != null) {
            "$apiBase/myseriesapi?$idType=${MMNet.urlEncode(finalId)}" +
                "&season=$season&epname=${MMNet.urlEncode(epname ?: "")}&key=$myKey"
        } else {
            "$apiBase/mymovieapi?$idType=${MMNet.urlEncode(finalId)}&key=$myKey"
        }
        val json = MMNet.getText(apiUrl, referer = apiBase) ?: return null
        if (!json.contains("\"success\"") || json.contains("\"success\":false") || json.contains("\"error\"")) {
            return null
        }
        val slugs = Regex("\"fileslug\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        val names = Regex("\"filename\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList()
        if (slugs.isEmpty()) return null
        return slugs.mapIndexed { i, s -> s to (names.getOrNull(i) ?: s) }
    }

    private fun directEmbedSite(helperJson: String, platformKey: String, code: String): String? {
        return try {
            val block = Regex("\"$platformKey\"\\s*:\\s*\\{[^}]*\\}").find(helperJson)?.value ?: return null
            val site = Regex("\"siteUrl\"\\s*:\\s*\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: return null
            val suffix = Regex("\"embed_suffix\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: ""
            MMNet.deEsc(site) + code + MMNet.deEsc(suffix)
        } catch (e: Exception) {
            Log.d(TAG, "directEmbedSite: ${e.message?.take(60)}")
            null
        }
    }

    private suspend fun postEmbedHelper(playerBase: String, slug: String): String? = try {
        val resp = app.post(
            "$playerBase/embedhelper2.php",
            data = mapOf("sid" to slug, "UserFavSite" to "", "currentDomain" to "[]"),
            headers = mapOf(
                "User-Agent" to MMNet.UA,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to "$playerBase/evid/$slug",
            ),
            timeout = 30_000L,
        )
        if (resp.isSuccessful) resp.text else null
    } catch (e: Exception) {
        Log.d(TAG, "embedhelper $slug: ${e.message?.take(60)}")
        null
    }

    private fun shortenName(name: String): String {
        // "Demon Slayer ... 1080p AMZN WEB DL Multi Audio AAC 2 0 H265 Multimovies"
        val quality = Regex("(2160p|1080[pi]?|720[pi]?|480[pi]?|4k|HDCAM|Hdtc|CAM)", RegexOption.IGNORE_CASE)
            .findAll(name).map { it.value.uppercase() }.distinct().joinToString(" ")
        val lang = Regex("(HINDI|ENGLISH|JAPANESE|TAMIL|TELUGU|KANNADA|MALAYALAM|MULTI)", RegexOption.IGNORE_CASE)
            .findAll(name).map { it.value.replaceFirstChar { it.uppercase() } }.distinct().joinToString("/")
        return listOf(quality, lang).filter { it.isNotBlank() }.joinToString(" • ")
            .ifBlank { name.take(40) }
    }
}
