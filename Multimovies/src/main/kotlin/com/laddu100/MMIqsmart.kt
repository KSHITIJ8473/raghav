package com.laddu100

import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// the gd mirror lists the same film on several file hosts and exposes one
// file id per host, each id then resolves through that host own pipeline
object MMIqsmart {

    private const val TAG = "MM_Iqsmart"
    private const val API = "https://streams.iqsmartgames.com"
    private const val PLAYER = "https://pro.iqsmartgames.com"

    private data class FileEntry(val slug: String, val name: String, val size: String)

    private data class MirrorLink(
        val siteUrl: String,
        val friendlyName: String,
        val code: String,
    )

    private fun shortLabel(filename: String): String {
        val cleaned = filename
            .replace(Regex("(?i)\\b(multi|ddp[\\d.]*|atmos|h[ .]?265|hevc|x265|x264|avc|esub|hindi|web[ .-]?dl|webrip|hdrip|hdtc|hc)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length > 26) cleaned.take(26).trim() + "..." else cleaned
    }

    private suspend fun fetchFiles(embedUrl: String): List<FileEntry> {
        val html = MMNet.get(embedUrl, referer = "https://multimovies.garden/") ?: return emptyList()

        val varValue = { name: String ->
            Regex("let\\s+$name\\s*=\\s*\"([^\"]*)\"").find(html)?.groupValues?.get(1).orEmpty()
        }
        val finalId = varValue("FinalID")
        val idType = varValue("idType")
        val key = varValue("myKey")
        if (finalId.isBlank() || key.isBlank()) return emptyList()

        val season = varValue("season")
        val episode = varValue("epname")
        val apiPath = if (season.isNotBlank() && episode.isNotBlank()) {
            "$API/myseriesapi?$idType=${MMNet.urlEncode(finalId)}" +
                "&season=$season&epname=${MMNet.urlEncode(episode)}&key=$key"
        } else {
            "$API/mymovieapi?$idType=${MMNet.urlEncode(finalId)}&key=$key"
        }

        val body = MMNet.get(apiPath, referer = "$API/") ?: return emptyList()
        val data = try {
            JSONObject(body).optJSONArray("data")
        } catch (e: Exception) {
            Log.d(TAG, "files json: ${e.message}")
            null
        } ?: return emptyList()

        val out = mutableListOf<FileEntry>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val slug = item.optString("fileslug")
            if (slug.isBlank()) continue
            out.add(FileEntry(slug, item.optString("filename"), item.optString("fsize")))
        }
        return out
    }

    private suspend fun fetchMirrors(slug: String): List<MirrorLink> {
        val body = MMNet.postForm(
            "$PLAYER/embedhelper2.php",
            mapOf(
                "sid" to slug,
                "UserFavSite" to "",
                "currentDomain" to """["streams.iqsmartgames.com","pro.iqsmartgames.com"]""",
            ),
            referer = "$PLAYER/",
        ) ?: return emptyList()

        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.d(TAG, "mirrors json: ${e.message}")
            return emptyList()
        }
        val sources = root.optJSONObject("sources") ?: return emptyList()
        val codes = try {
            JSONObject(String(MMCrypto.b64urlDecode(root.optString("mresult")), Charsets.UTF_8))
        } catch (e: Exception) {
            Log.d(TAG, "mirror codes: ${e.message}")
            return emptyList()
        }

        val out = mutableListOf<MirrorLink>()
        for (key in codes.keys()) {
            val src = sources.optJSONObject(key) ?: continue
            val siteUrl = src.optString("siteUrl")
            val code = codes.optString(key)
            if (siteUrl.isBlank() || code.isBlank()) continue
            out.add(MirrorLink(siteUrl, src.optString("friendlyName"), code))
        }
        return out
    }

    private suspend fun dispatchMirror(
        mirror: MirrorLink,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = MMNet.abs(mirror.siteUrl, mirror.code)
        val host = MMNet.hostOf(pageUrl)
        return when {
            host in setOf(
                "multimovies.rpmhub.site",
                "multimovies.embedseek.xyz",
                "multimovies.p2pplay.pro",
                "server1.uns.bio",
            ) -> MMCineverse.resolveApiV1(host, mirror.code, label, callback)

            host == "bysetayico.com" -> MMFilemoonBridge.resolveByse(label, mirror.code, callback)

            MMXvidStyle.isXvidStyle(host) -> MMXvidStyle.resolve(pageUrl, label, callback)

            else -> MMXvidStyle.resolveGeneric(pageUrl, label, callback)
        }
    }

    suspend fun resolve(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val files = fetchFiles(embedUrl)
        if (files.isEmpty()) return false
        var any = false
        for ((index, file) in files.withIndex()) {
            val mirrors = try {
                fetchMirrors(file.slug)
            } catch (e: Exception) {
                Log.e(TAG, "mirrors ${file.slug}: ${e.message}")
                emptyList()
            }
            val fileLabel = shortLabel(file.name).ifBlank { "File ${index + 1}" }
            for (mirror in mirrors) {
                try {
                    any = dispatchMirror(mirror, "$labelPrefix $fileLabel", callback) || any
                } catch (e: Exception) {
                    // single dead mirror is expected, keep the rest
                    Log.e(TAG, "mirror ${mirror.friendlyName}: ${e.message}")
                }
            }
        }
        return any
    }

    // filesforever links are iqsmart file ids in disguise, the landing page only
    // confirms the id before the shared mirror helper takes over
    suspend fun resolveFilesforever(
        embedUrl: String,
        labelPrefix: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val code = embedUrl.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return false

        val page = MMNet.get(embedUrl, referer = "https://multimovies.garden/")
        val sid = if (page != null) {
            Regex("id=\"gdmrfid\"\\s+value=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
                ?: Regex("let\\s+FinalID\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)
                ?: code
        } else code

        val mirrors = try {
            fetchMirrors(sid)
        } catch (e: Exception) {
            Log.e(TAG, "mirrors $sid: ${e.message}")
            emptyList()
        }
        var any = false
        for (mirror in mirrors) {
            try {
                any = dispatchMirror(mirror, "$labelPrefix $sid", callback) || any
            } catch (e: Exception) {
                // keep going on a single dead mirror
                Log.e(TAG, "mirror ${mirror.friendlyName}: ${e.message}")
            }
        }
        return any
    }
}

// xvidstyle frontends expose their playlist through a dean edwards packed jw setup
object MMXvidStyle {

    private const val TAG = "MM_XvidStyle"

    private val knownHosts = setOf(
        "hanerix.com",
        "morencius.com",
        "vibuxer.com",
        "n1mwq.org",
    )

    fun isXvidStyle(host: String): Boolean = host in knownHosts

    private fun extractLinks(unpacked: String, base: String): List<String> {
        val absolute = LinkedHashSet<String>()
        val relative = LinkedHashSet<String>()
        for (m in Regex("\"(?:hls\\d|file|mp4)\"\\s*:\\s*\"([^\"]+)\"").findAll(unpacked)) {
            val url = MMNet.deEsc(m.groupValues[1])
            if (url.isBlank()) continue
            if (url.startsWith("http")) absolute.add(url) else relative.add(MMNet.abs(base, url))
        }
        for (m in Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*").findAll(unpacked)) {
            absolute.add(MMNet.deEsc(m.groupValues.first()))
        }
        // the cdn copies outlive the same origin stream tokens, list them first
        return (absolute + relative).filter { it.startsWith("http") }
    }

    suspend fun resolve(
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val base = try {
            val uri = URI(pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            Log.d(TAG, "bad mirror url: ${e.message}")
            return false
        }
        val html = MMNet.get(pageUrl, referer = "https://pro.iqsmartgames.com/") ?: return false
        val unpacked = JsPacker.parseAndUnpack(html) ?: return false
        val links = extractLinks(unpacked, base)
        for (url in links) {
            callback(
                newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to "$base/")
                }
            )
        }
        return links.isNotEmpty()
    }

    // unknown hosts still get a plain m3u8 sweep of the raw page
    suspend fun resolveGeneric(
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = MMNet.get(pageUrl) ?: return false
        val urls = Regex("https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*")
            .findAll(html)
            .map { MMNet.deEsc(it.groupValues.first()) }
            .toSet()
        for (url in urls) {
            callback(newExtractorLink(label, label, url, type = ExtractorLinkType.M3U8))
        }
        return urls.isNotEmpty()
    }
}
