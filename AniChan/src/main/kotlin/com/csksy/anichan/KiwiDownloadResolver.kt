package com.csksy.anichan

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.Jsoup

// the download mirrors are pahe.nekostream pages that chain into a kwik file page
object KiwiDownloadResolver {

    private const val TAG = "AniChan"
    private val pageHeaders = mapOf("User-Agent" to AniChanApi.USER_AGENT)

    private val workersUrlRegex = Regex("""const\s+url\s*=\s*"(https?://[^"]+)"""")
    private val fallbackUrlRegex = Regex(""""(https?://[^"]*workers\.dev[^"]*)"""")
    private val sourceRegex = Regex("""source\s*=\s*["']([^"']+)["']""")
    private val formActionRegex = Regex("""action="([^"]+)"""")
    private val formTokenRegex = Regex("""value="([^"]+)"""")

    // returns the playable file and the kwik page it came from, the page is the referer
    // the cdn wants
    suspend fun resolve(paheUrl: String): Pair<String, String>? {
        val kwikUrl = kwikFileUrl(paheUrl) ?: return null
        return kwikSource(kwikUrl, paheUrl)
    }

    private suspend fun kwikFileUrl(paheUrl: String): String? {
        val page = try {
            app.get(paheUrl, headers = pageHeaders, timeout = 15L).text
        } catch (e: Exception) {
            Log.d(TAG, "kiwi page failed: ${e.message}")
            return null
        }
        val base = workersUrlRegex.find(page)?.groupValues?.get(1)
            ?: fallbackUrlRegex.find(page)?.groupValues?.get(1)
            ?: return null
        val redirector = base.trimEnd('/') + "/" + paheUrl.trimEnd('/').substringAfterLast('/')
        return try {
            app.get(redirector, headers = pageHeaders, allowRedirects = false)
                .headers["location"]?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.d(TAG, "kiwi redirect failed: ${e.message}")
            null
        }
    }

    private suspend fun kwikSource(kwikUrl: String, referer: String): Pair<String, String>? {
        val page = try {
            app.get(
                kwikUrl,
                headers = pageHeaders + ("Referer" to referer),
                timeout = 20L
            )
        } catch (e: Exception) {
            Log.d(TAG, "kwik page failed: ${e.message}")
            return null
        }
        val html = page.text

        val packed = try {
            Jsoup.parse(html).selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
        } catch (e: Exception) {
            null
        }
        val unpacked = packed?.let {
            try {
                getAndUnpack(it)
            } catch (e: Exception) {
                null
            }
        }

        if (unpacked != null) {
            sourceRegex.find(unpacked)?.groupValues?.get(1)?.let { src ->
                if (src.startsWith("http")) return src to page.url
            }
            val action = formActionRegex.find(unpacked)?.groupValues?.get(1)
            val token = formTokenRegex.find(unpacked)?.groupValues?.get(1)
            if (action != null && token != null) {
                return postForFile(action, token, page.url)
            }
        }

        sourceRegex.find(html)?.groupValues?.get(1)?.let { src ->
            if (src.startsWith("http") && (src.contains(".m3u8") || src.contains(".mp4"))) {
                return src to page.url
            }
        }
        return null
    }

    // kwik answers the token POST with a 302 to the file only after a few tries
    private suspend fun postForFile(action: String, token: String, pageUrl: String): Pair<String, String>? {
        repeat(10) {
            try {
                val res = app.post(
                    action,
                    headers = pageHeaders + ("Referer" to pageUrl),
                    data = mapOf("_token" to token),
                    allowRedirects = false
                )
                if (res.code == 302) {
                    res.headers["location"]?.takeIf { it.startsWith("http") }?.let { return it to pageUrl }
                }
            } catch (e: Exception) {
                Log.d(TAG, "kwik post failed: ${e.message}")
            }
        }
        return null
    }
}
