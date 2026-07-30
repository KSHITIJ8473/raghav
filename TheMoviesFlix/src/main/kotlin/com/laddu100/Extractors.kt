package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.api.Log
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLDecoder

private const val TAG = "TMF"

class FastDlExtractor : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Referer" to "https://nexdrive.fit/"
            )).text

            if (html.contains("File is Deleted") || html.contains("Something went wrong")) return

            val reurl = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl\.[^']+/dl\.php\?link=[^']+)'""").find(html)?.groupValues?.get(1)
                ?: return

            val googleUrl = Regex("""link=(https?://[^&"']+)""").find(reurl)?.groupValues?.get(1) ?: return
            val directUrl = URLDecoder.decode(googleUrl, "UTF-8")

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    referer = "https://fastdl.zip/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Referer" to "https://fastdl.zip/")
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "FastDl error: ${e.message}")
        }
    }
}

class VCloudExtractor : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.zip"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "https://nexdrive.fit/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = headers)
            val doc = response.document

            val downloadLink = doc.selectFirst("div.main h4 a")?.attr("href")
            if (downloadLink != null && downloadLink.isNotBlank()) {
                val fullUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                val doc2 = app.get(fullUrl, headers = headers).document

                val scriptData = doc2.selectFirst("script:containsData(url)")?.data()
                if (scriptData != null) {
                    val encoded = Regex("""atob\(atob\('([^']+)'\)\)""").find(scriptData)?.groupValues?.get(1)
                    if (encoded != null) {
                        val decoded = try {
                            base64Decode(base64Decode(encoded))
                        } catch (_: Exception) { null }
                        if (decoded != null && decoded.startsWith("http")) {
                            emitLink(decoded, callback)
                            return
                        }
                    }

                    val varUrl = Regex("""var\s+url\s*=\s*'([^']*)'""").find(scriptData)?.groupValues?.get(1)
                    if (varUrl != null && varUrl.startsWith("http")) {
                        emitLink(varUrl, callback)
                        return
                    }
                }

                doc2.selectFirst("div.card-body")?.select("h2 a.btn")?.forEach { btn ->
                    val href = btn.attr("href")
                    if (href.isNotBlank() && href.startsWith("http")) {
                        emitLink(href, callback)
                    }
                }
            }

            for (a in doc.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) continue
                if (href.contains("vcloud.zip") || href.contains("cloudflare") ||
                    href.contains("googleapi") || href.contains("googletagmanager") ||
                    href.contains("w.org") || href.contains("gmpg")) continue
                emitLink(href, callback)
            }

            for (script in doc.select("script")) {
                val data = script.data()
                if (!data.contains("http")) continue
                Regex("""(https?://[^"'\s<>]+)""").findAll(data).forEach { match ->
                    val u = match.groupValues[1]
                    if (!u.contains("vcloud") && !u.contains("cloudflare") &&
                        !u.contains("google") && !u.contains("w.org")) {
                        emitLink(u, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "VCloud error: ${e.message}")
        }
    }

    private fun emitLink(url: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO,
                headers = mapOf("Referer" to "$mainUrl/")
            )
        )
    }
}

class GoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    private val api = "https://api.gofile.io"
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = Regex("""/(?:\?c=|d/)([\da-zA-Z-]+)""").find(url)?.groupValues?.get(1) ?: return

            val token = JSONObject(app.post("$api/accounts", headers = headers).text)
                .getJSONObject("data").getString("token")

            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                app.get("$mainUrl/dist/js/global.js", headers = headers).text
            )?.groupValues?.get(1)

            val contentUrl = "$api/contents/$code" + (if (wt != null) "?wt=$wt" else "")
            val contentResp = app.get(contentUrl, headers = headers.toMutableMap().apply {
                put("Authorization", "Bearer $token")
            }).text

            val children = JSONObject(contentResp).getJSONObject("data").getJSONObject("children")
            val fileObj = children.getJSONObject(children.keys().next())
            val link = fileObj.getString("link")
            val fileName = fileObj.optString("name", "")
            val size = fileObj.optLong("size", 0L)

            val quality = Regex("""(\d{3,4})[pP]""").find(fileName)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value

            val sizeStr = when {
                size >= 1_073_741_824L -> "%.2f GB".format(size.toDouble() / 1_073_741_824L)
                size >= 1_048_576L -> "%.2f MB".format(size.toDouble() / 1_048_576L)
                else -> "$size B"
            }

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name [$sizeStr]",
                    url = link,
                    referer = mainUrl,
                    quality = quality,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf("Authorization" to "Bearer $token")
                )
            )
        } catch (e: Exception) {
            Log.d(TAG, "GoFile error: ${e.message}")
        }
    }
}

class FileBeeExtractor : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filebee.xyz"
    override val requiresReferer = true

    private val cfKiller = CloudflareKiller()
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "https://nexdrive.fit/"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(url, headers = headers, interceptor = cfKiller, timeout = 10L).text
            val doc = Jsoup.parse(html)

            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                if (href.contains("drive.google") || href.contains("googleusercontent") ||
                    href.contains("download") || href.contains(".mp4") || href.contains(".mkv")) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = href,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }

            for (script in doc.select("script")) {
                val data = script.data()
                if (!data.contains("drive.google") && !data.contains("googleusercontent") &&
                    !data.contains("download")) continue
                Regex("""(https?://[^"'\s]+(?:drive\.google|googleusercontent|download)[^"'\s]*)""")
                    .find(data)?.groupValues?.get(1)?.let { urlMatch ->
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = urlMatch,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
            }
        } catch (e: Exception) {
            Log.d(TAG, "FileBee error: ${e.message}")
        }
    }
}
