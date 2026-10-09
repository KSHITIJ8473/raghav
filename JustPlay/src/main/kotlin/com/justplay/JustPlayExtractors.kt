package com.justplay

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.jsoup.nodes.Document

class PlayHubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.ist"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = try {
            app.get(url, headers = PlayNet.headers(referer), timeout = 20L)
        } catch (_: Exception) {
            return
        }
        emitHubServers(res.document, PlayNet.getBaseUrl(res.url), res.url, subtitleCallback, callback)
    }

    companion object {

        private val hubJunk = Regex(
            "tinyurl|t\\.me|telegram|/tg/|winexch|a-ads|snvhost|one\\.one\\.one\\.one|" +
                "google\\.com/search|hubcloud\\.fans|drive/admin"
        )

        private val pxlRegex = Regex("""var\s+pxl\s*=\s*[\"']([^\"']+)[\"']""")

        private fun pixelFileUrl(pageHtml: String, buttonHref: String): String? {
            val pxl = pxlRegex.find(pageHtml)?.groupValues?.get(1)
            val link = pxl?.takeIf { it.startsWith("http") } ?: buttonHref
            if (!link.startsWith("http")) return null
            if (link.contains("download", true)) return link
            val id = link.substringBefore("?").substringBefore("#").substringAfterLast("/")
            if (id.isBlank()) return null
            return "${PlayNet.getBaseUrl(link)}/api/file/$id?download"
        }

        suspend fun emitHubServers(
            doc: Document,
            base: String,
            pageUrl: String,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var emitted = false
            val header = doc.selectFirst("div.card-header")?.text().orEmpty()
            val size = doc.selectFirst("i#size")?.text().orEmpty()

            if (Regex("(?i)\\.(zip|rar|7z)\\s*$").containsMatchIn(header.trim())) return false
            val quality = PlayNet.getIndexQuality(header)
            val labelExtras = listOf(header, size).filter { it.isNotBlank() }.joinToString(" ")

            val generate = doc.select("a.btn, a[download]")
                .firstOrNull { it.text().contains("generate", true) }
                ?.attr("href")?.trim()
            if (!generate.isNullOrBlank()) {
                try {
                    val genDoc = app.get(
                        PlayNet.absolute(generate, base),
                        headers = PlayNet.headers(pageUrl),
                        timeout = 20L
                    ).document
                    if (emitGeneratedServers(genDoc, labelExtras, quality, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }

            val inner = when {
                pageUrl.contains("/video/") -> doc.selectFirst("div.vd > center > a")?.attr("href")
                else -> null
            }
            if (!inner.isNullOrBlank()) {
                try {
                    val innerDoc = app.get(
                        PlayNet.absolute(inner, base),
                        headers = PlayNet.headers(base),
                        timeout = 20L
                    ).document
                    if (emitHubServers(innerDoc, base, inner, subtitleCallback, callback)) return true
                } catch (_: Exception) {}
            }

            val found = mutableListOf<Pair<String, String>>()
            for (btn in doc.select("a.btn, a[download]")) {
                val text = btn.text()
                val link = btn.attr("href").trim()
                if (link.isBlank()) continue
                if (hubJunk.containsMatchIn(link)) continue
                val label = text.trim().lowercase()
                val abs = PlayNet.absolute(link, base)
                when {
                    label.contains("fslv2") -> found.add("FSLv2 [$labelExtras]" to abs)
                    label.contains("fsl") -> found.add("FSL Server [$labelExtras]" to abs)
                    label.contains("buzzserver") -> {
                        try {
                            val dlink = app.get(
                                "$abs/download",
                                referer = abs,
                                allowRedirects = false,
                                timeout = 15L
                            ).headers["hx-redirect"] ?: ""
                            if (dlink.isNotBlank()) {
                                found.add("BuzzServer [$labelExtras]" to PlayNet.absolute(dlink, PlayNet.getBaseUrl(abs)))
                            }
                        } catch (_: Exception) {}
                    }
                    label.contains("10gbps") -> {
                        try {
                            val target = PlayNet.resolveRedirectTarget(abs, base)
                            if (target != null) {
                                val direct = if (target.contains("link=")) target.substringAfter("link=") else target
                                if (direct.startsWith("http")) {
                                    found.add("10Gbps [Download] [$labelExtras]" to direct)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    label.contains("instant download") || label.contains("instant dl") -> {
                        try {
                            val loc = app.get(abs, headers = PlayNet.headers(base), allowRedirects = false, timeout = 10L)
                                .headers["location"]?.trim()
                            val direct = when {
                                loc == null -> null
                                loc.contains("url=") -> loc.substringAfter("url=")
                                loc.contains("link=") -> loc.substringAfter("link=")
                                else -> loc
                            }?.takeIf { it.startsWith("http") }
                            if (direct != null) {
                                found.add("Instant Download [$labelExtras]" to direct)
                            }
                        } catch (_: Exception) {}
                    }
                    label.contains("pixeldra") || label.contains("pixelserver") || label.contains("pixel server") || link.contains("pixeldra") -> {
                        pixelFileUrl(doc.toString(), link)?.let { found.add("Pixeldrain [$labelExtras]" to it) }
                    }
                    label.contains("s3 server") || label.contains("mega server") || label.contains("pdl") -> {
                        found.add("${text.trim()} [$labelExtras]" to abs)
                    }
                    label.contains("download file") -> {
                        found.add("Download File [$labelExtras]" to abs)
                    }
                    link.contains("gofile.io") -> {
                        try {
                            PlayGofile().getUrl(abs, base, subtitleCallback, callback)
                            emitted = true
                        } catch (_: Exception) {}
                    }
                    abs.startsWith("http") -> {
                        try {
                            emitted = loadExtractor(abs, base, subtitleCallback, callback) || emitted
                        } catch (_: Exception) {}
                    }
                }
            }
            if (emitVerified(found, quality, callback)) emitted = true
            return emitted
        }

        private suspend fun emitVerified(
            found: List<Pair<String, String>>,
            quality: Int,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            if (found.isEmpty()) return false
            val alive = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                coroutineScope {
                    val semaphore = Semaphore(6)
                    found.distinctBy { it.second }.map { (name, url) ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (PlayNet.fileAlive(url)) name to url else null
                            }
                        }
                    }.mapNotNull { runCatching { it.await() }.getOrNull() }
                }
            } ?: emptyList()
            for ((name, url) in alive) {
                callback(newExtractorLink("Hub-Cloud", name, url, ExtractorLinkType.VIDEO) { this.quality = quality })
            }
            return alive.isNotEmpty()
        }

        private suspend fun emitGeneratedServers(
            doc: Document,
            labelExtras: String,
            quality: Int,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            var emitted = false
            val found = mutableListOf<Pair<String, String>>()
            for (a in doc.select("center a[href], div.vd a[href], a[href]")) {
                val text = a.text().trim().lowercase()
                val href = a.attr("href").trim()
                if (!href.startsWith("http")) continue
                if (hubJunk.containsMatchIn(href)) continue
                when {
                    text.contains("fsl") || href.contains("cloudflarestorage.com") || href.contains(".r2.dev/") ||
                        href.endsWith(".r2.dev") -> {
                        found.add("FSL Server [$labelExtras]" to href)
                    }
                    text.contains("pixelserver") || text.contains("pixeldra") || href.contains("pixeldrain") -> {
                        pixelFileUrl(doc.toString(), href)?.let { found.add("Pixeldrain [$labelExtras]" to it) }
                    }
                    text.contains("10gbps") -> {
                        try {
                            val target = PlayNet.resolveRedirectTarget(href)
                            if (target != null) {
                                val direct = if (target.contains("link=")) target.substringAfter("link=") else target
                                if (direct.startsWith("http")) {
                                    found.add("10Gbps [Download] [$labelExtras]" to direct)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    text.contains("instant download") || text.contains("instant dl") -> {
                        try {
                            val loc = app.get(href, headers = PlayNet.headers(), allowRedirects = false, timeout = 10L)
                                .headers["location"]?.trim()
                            val direct = when {
                                loc == null -> null
                                loc.contains("url=") -> loc.substringAfter("url=")
                                loc.contains("link=") -> loc.substringAfter("link=")
                                else -> loc
                            }?.takeIf { it.startsWith("http") }
                            if (direct != null) {
                                found.add("Instant Download [$labelExtras]" to direct)
                            }
                        } catch (_: Exception) {}
                    }
                    text.contains("download file") || text.contains("download now") || text.contains("download] ") -> {
                        found.add("Download File [$labelExtras]" to href)
                    }
                    href.contains("gofile.io") -> {
                        try {
                            PlayGofile().getUrl(href, "", subtitleCallback, callback)
                            emitted = true
                        } catch (_: Exception) {}
                    }
                    href.contains(".workers.dev/") -> {
                        found.add("HubCloud Mirror [$labelExtras]" to href)
                    }
                }
            }
            if (emitVerified(found, quality, callback)) emitted = true
            return emitted
        }
    }
}

class PlayVCloud : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.fit"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {

            val res = PlayNet.fetchWithCf(url, referer, timeout = 30L) ?: return
            val doc = res.document
            val base = PlayNet.getBaseUrl(res.url)
            var target: org.jsoup.nodes.Document = doc
            var link: String? = null

            val hop = doc.selectFirst("div.main h4 a")?.attr("href")?.trim()
            if (!hop.isNullOrBlank()) {
                val hopUrl = PlayNet.absolute(hop, base)
                val hopRes = try {
                    PlayNet.fetchWithCf(hopUrl, base, timeout = 30L)
                } catch (_: Exception) {
                    null
                }
                hopRes?.let {
                    target = it.document
                    link = extractVCloudLink(target)
                }
            }
            if (link.isNullOrBlank()) link = extractVCloudLink(doc)

            if (link.isNullOrBlank() && res.url.contains("/video/")) {
                link = target.selectFirst("div.vd > center > a")?.attr("href")?.trim()
            }
            if (link.isNullOrBlank()) return

            val abs = if (link.startsWith("http")) link else base + link
            if (!abs.startsWith("http")) return

            val targetRes = try {
                PlayNet.fetchWithCf(abs, base, timeout = 25L)
            } catch (_: Exception) {
                null
            } ?: return
            val emitted = PlayHubCloud.emitHubServers(
                targetRes.document, PlayNet.getBaseUrl(abs), abs, subtitleCallback, callback
            )

            if (!emitted && (abs.contains("drive.google.com") || abs.contains("googleusercontent"))) {
                callback(
                    newExtractorLink(name, name, abs, ExtractorLinkType.VIDEO) {
                        this.headers = mapOf("Referer" to "$mainUrl/")
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private fun extractVCloudLink(doc: Document): String? {
        val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
        if (script.isBlank()) return null
        Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(script)?.groupValues?.get(1)
            ?.let { encoded ->
                val decoded = runCatching { base64Decode(encoded) }.getOrNull()
                    ?.let { runCatching { base64Decode(it) }.getOrNull() }
                if (decoded != null && decoded.startsWith("http")) return decoded
            }
        Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        return doc.selectFirst("div.card-body h2 a.btn[href]")?.attr("href")?.trim()
            ?.takeIf { it.startsWith("http") }
    }
}

class PlayVegaDrive : ExtractorApi() {
    override val name = "V-Drive"
    override val mainUrl = "https://one.vegadrive.app"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = app.get(
                url,
                headers = PlayNet.browserHeaders("https://nexdrive.fit/"),
                timeout = 20L
            )
            val base = PlayNet.getBaseUrl(page.url)
            val token = url.substringAfter("/s/").substringBefore("?")

            val drop = providerLink(base, token, "skydrop")
            if (drop != null && drop.contains("googleusercontent") &&
                !drop.substringAfterLast("/").contains(".zip", true)
            ) {
                callback(newExtractorLink(name, "V-Drive Vegadrop (10Gbps)", drop, ExtractorLinkType.VIDEO))
            }

            val pixel = providerLink(base, token, "pixeldrain")
            if (pixel != null && pixel.contains("pixeldrain.com/u/")) {
                val id = pixel.substringBefore("?").substringBefore("#").substringAfterLast("/")
                if (id.isNotBlank()) {
                    callback(
                        newExtractorLink(name, "V-Drive Pixeldrain", "https://pixeldrain.com/api/file/$id", ExtractorLinkType.VIDEO)
                    )
                }
            }

            val buzz = providerLink(base, token, "buzzheavier")
            if (buzz != null && buzz.contains("bzzhr.co")) {
                callback(newExtractorLink(name, "V-Drive Buzzheavier", buzz, ExtractorLinkType.VIDEO))
            }

            val telegram = providerLink(base, token, "telegram")
            if (telegram != null && telegram.contains("tgfiles")) {
                callback(newExtractorLink(name, "V-Drive Telegram", telegram, ExtractorLinkType.VIDEO))
            }
        } catch (_: Exception) {}
    }

    private suspend fun providerLink(base: String, token: String, provider: String): String? {
        val start = if (provider == "skydrop") {
            "$base/go/$token/skydrop"
        } else {
            "$base/d/$token/$provider"
        }
        return try {
            PlayNet.resolveRedirectTarget(start, "$base/")
        } catch (_: Exception) {
            null
        }
    }
}

class PlayFilePress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filebee.xyz"
    override val requiresReferer = false

    private val fileId = Regex("""/file/([a-f0-9]{16,40})""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = fileId.find(url)?.groupValues?.get(1) ?: return
        try {
            val infoRes = app.get(
                "$mainUrl/api/file/get/$id",
                headers = PlayNet.browserHeaders("$mainUrl/"),
                timeout = 15L
            )
            if (!infoRes.isSuccessful) return
            val info = try {
                JSONObject(infoRes.text).optJSONObject("data") ?: return
            } catch (_: Exception) {
                return
            }
            if (isArchiveName(info.optString("name"))) return

            val dotflix = download(id, "dotFlixDownlaod")
            if (dotflix != null && dotflix.startsWith("http")) {
                val direct = resolveDotFlix(dotflix)
                if (direct != null && direct.startsWith("http")) {
                    callback(newExtractorLink(name, "FilePress Instant", direct, ExtractorLinkType.VIDEO))
                }
            }

            val telegram = download(id, "telegramDownload")
            if (telegram != null && telegram.startsWith("http")) {
                callback(newExtractorLink(name, "FilePress Telegram", telegram, ExtractorLinkType.VIDEO))
            }

            val task = download(id, "indexDownlaod")
            if (task != null && task.matches(Regex("[a-f0-9]{16,40}"))) {
                val link = final(task, "indexDownlaod")
                if (link != null) {
                    val probe = PlayNet.probe(link, "$mainUrl/")
                    if (probe != null && probe in 200..299) {
                        callback(newExtractorLink(name, "FilePress Direct", link, ExtractorLinkType.VIDEO))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun isArchiveName(name: String): Boolean =
        Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(name.trim())

    private suspend fun download(id: String, method: String): String? {
        return try {
            val res = app.post(
                "$mainUrl/api/file/downlaod/",
                headers = PlayNet.browserHeaders("$mainUrl/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", mainUrl)
                },
                json = JSONObject()
                    .put("captchaValue", "")
                    .put("id", id)
                    .put("method", method)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = try {
                JSONObject(res.text)
            } catch (_: Exception) {
                return null
            }
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun final(taskId: String, method: String): String? {
        return try {
            val res = app.post(
                "$mainUrl/api/file/downlaod2/",
                headers = PlayNet.browserHeaders("$mainUrl/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("Origin", mainUrl)
                },
                json = JSONObject()
                    .put("captchaValue", "")
                    .put("id", taskId)
                    .put("method", method)
                    .toString(),
                timeout = 30L
            )
            if (!res.isSuccessful) return null
            val parsed = try {
                JSONObject(res.text)
            } catch (_: Exception) {
                return null
            }
            if (!parsed.optBoolean("status")) return null
            when (val data = parsed.opt("data")) {
                is String -> data.takeIf { it.startsWith("http") }
                is org.json.JSONArray -> (0 until data.length())
                    .firstNotNullOfOrNull { i ->
                        data.optString(i).takeIf { it.startsWith("http") }
                    }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveDotFlix(shareUrl: String): String? {
        return try {
            val text = app.get(
                shareUrl,
                headers = PlayNet.browserHeaders("https://new2.dotflix.shop/"),
                timeout = 20L
            ).text
            val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1)
                ?: return null
            val obfuscated = java.util.Base64.getEncoder()
                .encodeToString(code.toByteArray())
                .reversed()
            val requestId = List(13) {
                "abcdefghijklmnopqrstuvwxyz0123456789".random()
            }.joinToString("")
            val timestamp = System.currentTimeMillis().toString()
            val res = app.post(
                "https://dotflix.store/api/extract-download",
                headers = PlayNet.browserHeaders("https://new2.dotflix.shop/").toMutableMap().apply {
                    put("Content-Type", "application/json")
                    put("Accept", "application/json")
                    put("X-Request-ID", requestId)
                    put("X-Timestamp", timestamp)
                    put("Origin", "https://new2.dotflix.shop")
                },
                json = JSONObject()
                    .put("requestId", requestId)
                    .put("timestamp", timestamp)
                    .put("data", obfuscated)
                    .toString(),
                timeout = 25L
            )
            if (!res.isSuccessful) return null
            val parsed = try {
                JSONObject(res.text)
            } catch (_: Exception) {
                return null
            }
            if (!parsed.optBoolean("success")) return null
            parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }
}

internal object PlayDirectStub {
    suspend fun resolve(
        url: String,
        referer: String?
    ): String? {
        try {
            val res = app.get(
                url,
                headers = PlayNet.headers(referer ?: "https://nexdrive.fit/"),
                timeout = 20L
            )
            val text = res.text
            val embedded = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
                ?: return null
            val wrapped = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(embedded)?.groupValues?.get(1)
            val carrier = if (wrapped != null) {
                val padded = if (wrapped.length % 4 > 0) {
                    wrapped + "=".repeat(4 - wrapped.length % 4)
                } else wrapped
                runCatching { base64Decode(padded) }.getOrNull() ?: return null
            } else {
                embedded
            }
            val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1) ?: return null
            val direct = URLDecoder.decode(encoded, "UTF-8")
            return direct.takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            return null
        }
    }
}

class PlayFastDl : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val direct = PlayDirectStub.resolve(url, referer) ?: return
        callback(
            newExtractorLink(
                "G-Direct",
                "G-Direct",
                direct,
                ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf("Referer" to "https://fastdl.zip/")
            }
        )
    }
}

class PlayHubCdn : ExtractorApi() {
    override val name = "HubCdn"
    override val mainUrl = "https://hubcdn.wiki"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val direct = PlayDirectStub.resolve(url, referer) ?: return
        callback(
            newExtractorLink(
                "HubCdn",
                "G-Direct",
                direct,
                ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf("Referer" to "https://hubcdn.wiki/")
            }
        )
    }
}

class PlayHblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = PlayNet.headers(referer),
                interceptor = PlayNet.cfKiller,
                timeout = 20L
            ).document
            val seen = mutableSetOf<String>()
            for (a in doc.select("div.entry-content a[href]")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || !seen.add(href)) continue
                loadExtractor(href, url, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }
}

class PlayHubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.pics"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(
                url,
                headers = PlayNet.headers(referer),
                interceptor = PlayNet.cfKiller,
                timeout = 20L
            ).document
            val seen = mutableSetOf<String>()
            for (a in doc.select("div.entry-content a[href], main a[href], a[href*='hubcloud']")) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || href.contains("/tg/") || !seen.add(href)) continue
                if (href.contains("hubcloud")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }
}

class PlayHdStream4u : VidHidePro() {
    override val name = "HdStream4u"
    override val mainUrl = "https://hdstream4u.com"
}

class PlayGofile : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val code = url.substringAfterLast("/").substringBefore("#")
            if (code.isBlank()) return
            val api = "https://api.gofile.io"
            val token = try {
                JSONObject(
                    app.post("$api/accounts", timeout = 15L).text
                ).getJSONObject("data").getString("token")
            } catch (_: Exception) {
                return
            }
            val wt = try {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(
                    app.get("$api/dist/js/global.js", timeout = 15L).text
                )?.groupValues?.get(1)
            } catch (_: Exception) {
                null
            }
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token"
            else "$api/contents/$code?wt=$wt"
            val contentRes = app.get(
                contentUrl,
                headers = mapOf("Authorization" to "Bearer $token"),
                timeout = 20L
            ).text
            val data = JSONObject(contentRes).getJSONObject("data")
            val children = data.optJSONObject("children") ?: return
            for (key in children.keys()) {
                val child = children.optJSONObject(key) ?: continue
                val link = child.optString("link").takeIf { it.startsWith("http") } ?: continue
                val name = child.optString("name")
                val size = child.optLong("size", 0L)
                val sizeText = if (size > 0) "${size / 1024 / 1024} MB" else ""
                val inner = listOf(name.take(60), sizeText).filter { it.isNotBlank() }.joinToString(" | ")
                val quality = PlayNet.getIndexQuality(name)
                callback(
                    newExtractorLink(
                        "GoFile",
                        if (inner.isBlank()) "GoFile" else "GoFile [$inner]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.headers = mapOf("Authorization" to "Bearer $token")
                    }
                )
            }
        } catch (_: Exception) {}
    }
}

internal object PlayCrypto {

    private fun evpBytesToKey(
        password: ByteArray,
        salt: ByteArray,
        keyLen: Int = 32,
        ivLen: Int = 16
    ): Pair<ByteArray, ByteArray> {
        val md = java.security.MessageDigest.getInstance("MD5")
        val out = ArrayList<Byte>(keyLen + ivLen)
        var prev = ByteArray(0)
        while (out.size < keyLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(password)
            md.update(salt)
            prev = md.digest()
            out.addAll(prev.toList())
        }
        return out.subList(0, keyLen).toByteArray() to
            out.subList(keyLen, keyLen + ivLen).toByteArray()
    }

    fun aesEncrypt(plain: String, passphrase: String): String? = try {
        val salt = java.security.SecureRandom().generateSeed(8)
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        val out = "Salted__".toByteArray(Charsets.UTF_8) + salt + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        java.util.Base64.getEncoder().encodeToString(out)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    } catch (_: Exception) {
        null
    }

    fun aesDecrypt(data: String, passphrase: String): String? = try {
        val b64 = data.trim()
            .replace("-", "+")
            .replace("_", "/")
            .let { if (it.length % 4 != 0) it + "=".repeat(4 - it.length % 4) else it }
        val raw = java.util.Base64.getDecoder().decode(b64)
        if (raw.size < 17 || String(raw, 0, 8, Charsets.UTF_8) != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt)
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}

open class PlayGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://*.gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, headers = PlayNet.headers(referer), timeout = 20L)
            val doc = res.document
            val base = PlayNet.getBaseUrl(res.url)
            val fileName = doc.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name :").trim()
                .ifBlank { doc.title().substringAfter("GDFlix |").trim() }
            val sizeText = doc.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size :").substringBefore("|").trim()
            val quality = PlayNet.getIndexQuality(fileName)

            suspend fun emit(link: String, server: String) {
                if (!link.startsWith("http")) return
                val extras = listOf(fileName, sizeText).filter { it.isNotBlank() }.joinToString(" ")
                callback(
                    newExtractorLink(
                        "GDFlix",
                        "GDFlix $server [$extras]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = base
                    }
                )
            }

            for (a in doc.select("div.text-center a[href], div.mb-4 > a, a.btn")) {
                val text = a.text()
                val link = a.attr("href").trim()
                if (link.isBlank() || link.startsWith("#")) continue
                val abs = PlayNet.absolute(link, base)
                when {
                    text.contains("Login To DL", true) || link.startsWith("/login") -> {}
                    text.contains("Instant DL", true) || text.contains("Instant Download", true) -> {
                        try {
                            val loc = app.get(abs, headers = PlayNet.headers(res.url), allowRedirects = false, timeout = 10L)
                                .headers["location"]?.trim()
                            val direct = when {
                                loc == null -> null
                                loc.contains("url=") -> loc.substringAfter("url=")
                                loc.contains("link=") -> loc.substringAfter("link=")
                                else -> loc
                            }?.takeIf { it.startsWith("http") }
                            if (direct != null) emit(direct, "Instant Download")
                        } catch (_: Exception) {}
                    }
                    text.contains("10GBPS", true) -> {
                        try {
                            val target = PlayNet.resolveRedirectTarget(abs, res.url)
                            if (target != null) {
                                val direct = if (target.contains("link=")) target.substringAfter("link=") else target
                                emit(direct, "10Gbps")
                            }
                        } catch (_: Exception) {}
                    }
                    text.contains("DRIVEBOT", true) || link.contains("drivebot") -> {
                        driveBot(abs, fileName, sizeText, quality, callback)
                    }
                    text.contains("FAST CLOUD", true) || text.contains("CLOUD DOWNLOAD", true) ||
                        text.contains("ZIPDISK", true) -> {
                        try {
                            val cloudDoc = app.get(
                                abs,
                                headers = PlayNet.headers(res.url),
                                timeout = 20L
                            ).document
                            cloudDoc.selectFirst("div.card-body a")?.attr("href")?.trim()
                                ?.takeIf { it.startsWith("http") }?.let { emit(it, "Cloud") }
                        } catch (_: Exception) {}
                    }
                    text.contains("DIRECT DL", true) || text.contains("DIRECT SERVER", true) ->
                        emit(abs, "Direct")
                    text.contains("FSL", true) -> emit(abs, "FSL")
                    link.contains("pixeldrain") -> {
                        val pixelBase = PlayNet.getBaseUrl(link)
                        val final = if (link.contains("download", true)) link
                        else "$pixelBase/api/file/${link.substringAfterLast("/")}?download"
                        emit(final, "Pixeldrain")
                    }
                    link.contains("gofile") ->
                        PlayGofile().getUrl(abs, res.url, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun driveBot(
        url: String,
        fileName: String,
        sizeText: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = app.get(url, headers = PlayNet.headers(), timeout = 20L)
            val body = page.text
            val token = Regex("""formData\.append\('token', '([a-f0-9]+)'""")
                .find(body)?.groupValues?.get(1)
            val downloadId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                .find(body)?.groupValues?.get(1)
            if (token == null || downloadId == null) return
            val answer = app.post(
                PlayNet.absolute("/download?id=$downloadId", PlayNet.getBaseUrl(page.url)),
                headers = mapOf(
                    "User-Agent" to PLAY_UA,
                    "Referer" to page.url,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("token" to token),
                timeout = 20L
            ).text
            val link = Regex(""""url"\s*:\s*"(.*?)"""").find(answer)?.groupValues?.get(1)
                ?.replace("\\", "")
            if (!link.isNullOrBlank() && link.startsWith("http")) {
                val extras = listOf(fileName, sizeText).filter { it.isNotBlank() }.joinToString(" ")
                callback(
                    newExtractorLink(
                        "GDFlix",
                        "GDFlix DriveBot [$extras]",
                        link,
                        ExtractorLinkType.VIDEO
                    ) { this.quality = quality }
                )
            }
        } catch (_: Exception) {}
    }
}

class PlayGDLink : PlayGDFlix() {
    override val mainUrl = "https://gdlink.*"
}

internal object PlayNxsha {
    private const val PASSPHRASE = "S8x!Jk4ZP1uG8\$my"
    private const val BASE = "https://nxsha.space"
    private const val TMDB_PROXY = "https://db.speedracelight.com/3"

    private suspend fun apiGet(path: String, payload: Map<String, String>, referer: String): String? {
        val obj = payload.toMutableMap()
        obj["_req_ts"] = System.currentTimeMillis().toString()
        obj["_req_salt"] = (1..10).map { ('a' + (0..35).random()) }.joinToString("")
        val q = PlayCrypto.aesEncrypt(org.json.JSONObject(obj).toString(), PASSPHRASE) ?: return null
        return try {
            app.get(
                "$BASE$path?q=${java.net.URLEncoder.encode(q, "UTF-8")}",
                headers = PlayNet.headers(referer),
                timeout = 20L
            ).text
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> decodeHash(body: String?, clazz: Class<T>): T? {
        if (body.isNullOrBlank()) return null
        val hash = Regex(""""_hash"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return null
        val plain = PlayCrypto.aesDecrypt(hash, PASSPHRASE) ?: return null
        return try {
            ObjectMapper().readValue(plain, clazz)
        } catch (_: Exception) {
            null
        }
    }

    private class NxServers(@JsonProperty("servers") val servers: List<NxServer>? = null)
    private class NxServer(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("scraper") val scraper: String? = null,
        @JsonProperty("webSupport") val webSupport: Boolean? = null
    )

    private class NxSources(@JsonProperty("sources") val sources: List<NxSource>? = null)
    private class NxSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("isEmbed") val isEmbed: Boolean? = null,
        @JsonProperty("type") val type: String? = null
    )

    private class NxSubs(@JsonProperty("subtitles") val subtitles: List<NxSub>? = null)
    private class NxSub(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("uri") val uri: String? = null
    )

    private suspend fun imdbToTmdb(imdbId: String): String? {
        val body = try {
            app.get("$TMDB_PROXY/find/$imdbId?external_source=imdb_id", timeout = 15L).text
        } catch (_: Exception) {
            return null
        }
        return Regex(""""movie_results"\s*:\s*\[\s*\{[^}]*?"id"\s*:\s*(\d+)""")
            .find(body)?.groupValues?.get(1)
    }

    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val tvMatch = Regex("/embed/tv/(\\d+)/(\\d+)/(\\d+)").find(embedUrl)
            val movieMatch = Regex("/embed/movie/(tt\\d+)").find(embedUrl)
            var tmdbId: String? = null
            var imdbId = ""
            var type = "movie"
            var season = ""
            var episode = ""
            if (tvMatch != null) {
                tmdbId = tvMatch.groupValues[1]
                season = tvMatch.groupValues[2]
                episode = tvMatch.groupValues[3]
                type = "tv"
            } else if (movieMatch != null) {
                imdbId = movieMatch.groupValues[1]
                tmdbId = imdbToTmdb(imdbId)
                    ?: Regex("/embed/movie/(\\d+)").find(embedUrl)?.groupValues?.get(1)
            } else {
                return false
            }
            val tmdb = tmdbId ?: return false

            val base = mapOf(
                "tmdbId" to tmdb, "imdb_id" to imdbId, "type" to type,
                "season" to season, "episode" to episode
            )
            val serversBody = apiGet("/api/servers", base, embedUrl) ?: return false
            val servers = decodeHash(serversBody, NxServers::class.java)?.servers ?: return false

            var any = false
            coroutineScope {
                servers.map { server ->
                    async(Dispatchers.IO) {
                        if (server.webSupport == false) return@async
                        val scraper = server.scraper ?: return@async
                        val sourcesBody = try {
                            apiGet(
                                "/api/sources",
                                base + mapOf("ex_lang" to "false", "provider" to scraper),
                                embedUrl
                            )
                        } catch (_: Exception) {
                            null
                        } ?: return@async
                        val sources = decodeHash(sourcesBody, NxSources::class.java)?.sources ?: return@async
                        for (src in sources) {
                            val url = src.url?.trim()?.takeIf { it.startsWith("http") } ?: continue
                            if (src.isEmbed == true) continue
                            val name = PlayLabels.buildLabel("multimovies", "", "$label ${server.name ?: ""}")
                            val linkType = when (src.type?.lowercase()) {
                                "m3u8", "hls" -> ExtractorLinkType.M3U8
                                "mpd", "dash" -> ExtractorLinkType.DASH
                                "mp4", "video" -> ExtractorLinkType.VIDEO
                                else -> ExtractorLinkType.M3U8
                            }
                            val qualityNum = Regex("(2160|1080|720|480|360)")
                                .find(src.label ?: src.quality ?: "")?.groupValues?.get(1)?.toIntOrNull()
                            callback(
                                newExtractorLink("JustPlay", name, url, linkType) {

                                    this.headers = mapOf("Referer" to "$BASE/")
                                    qualityNum?.let { this.quality = it }
                                }
                            )
                            any = true
                        }
                    }
                }.forEach { it.join() }
            }

            try {
                val subsBody = apiGet("/api/subtitles", base, embedUrl)
                decodeHash(subsBody, NxSubs::class.java)?.subtitles?.forEach { sub ->
                    val uri = sub.uri?.trim()?.takeIf { it.startsWith("http") } ?: return@forEach
                    val name = sub.title?.takeIf { it.isNotBlank() }
                        ?: sub.language?.takeIf { it.isNotBlank() } ?: "English"
                    subtitleCallback(newSubtitleFile(name, uri) {})
                }
            } catch (_: Exception) {}
            any
        } catch (_: Exception) {
            false
        }
    }
}

internal object PlayVidout {
    private const val REFERER = "https://vidout.pages.dev/"
    private const val GITHUB_RAW = "https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main"

    private val langNames = mapOf(
        "eng" to "English", "hin" to "Hindi", "spa" to "Spanish", "fre" to "French",
        "ger" to "German", "ita" to "Italian", "por" to "Portuguese", "rus" to "Russian",
        "zho" to "Chinese", "ara" to "Arabic", "kor" to "Korean", "jpn" to "Japanese",
        "tam" to "Tamil", "tel" to "Telugu", "kan" to "Kannada", "mal" to "Malayalam"
    )

    private suspend fun getText(url: String): String? = try {
        val res = app.get(url, headers = PlayNet.headers(), timeout = 15L)
        if (res.isSuccessful) res.text else null
    } catch (_: Exception) {
        null
    }

    suspend fun resolve(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
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
                val body = getText("$GITHUB_RAW/hls/tv/$tmdbId/S$season.json") ?: return false
                streamUrl = Regex("\"$episode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            } else if (movieMatch != null) {
                val id = movieMatch.groupValues[1]
                tmdbId = if (id.startsWith("tt")) {
                    val body = try {
                        app.get(
                            "https://db.speedracelight.com/3/find/$id?external_source=imdb_id",
                            timeout = 15L
                        ).text
                    } catch (_: Exception) {
                        return false
                    }
                    Regex("\"movie_results\"\\s*:\\s*\\[\\s*\\{[^}]*?\"id\"\\s*:\\s*(\\d+)")
                        .find(body)?.groupValues?.get(1)
                } else id
                streamUrl = tmdbId?.let { getText("$GITHUB_RAW/hls/movie/$it")?.trim()?.takeIf { s -> s.startsWith("http") } }
            } else {
                return false
            }

            var url = streamUrl ?: return false
            url = PlayNet.deEsc(url)
            val lower = url.lowercase()

            if (url.contains("#")) return false
            if (lower.endsWith(".txt") && !lower.contains("/hls3/")) return false
            if (!lower.contains(".m3u8") && !lower.endsWith(".txt") && !lower.contains("/stream/")) {
                return false
            }

            val name = PlayLabels.buildLabel("multimovies", "", label)
            callback(
                newExtractorLink("JustPlay", name, url, ExtractorLinkType.M3U8) {
                    this.headers = mapOf("Referer" to REFERER)
                }
            )
            loadCdnSubtitles(url, subtitleCallback)
            loadGithubSubtitles(tmdbId, season, episode, subtitleCallback)
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun loadCdnSubtitles(streamUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val m = Regex("/([^/]+)/hls3/([^/]+)/([^/]+)/([^/]+)_(?:,|[nhl]/)").find(streamUrl) ?: return
            val (srv, prefix, folderId, filePrefix) = m.destructured
            for ((code, name) in langNames) {
                val cdn = "https://$srv.acek-cdn.com/vtt/$prefix/$folderId/${filePrefix}_$code.vtt"
                subtitleCallback(newSubtitleFile(name, cdn) {
                    this.headers = mapOf("Referer" to REFERER)
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun loadGithubSubtitles(
        tmdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (tmdbId == null) return
        try {
            val path = if (season != null && episode != null) {
                "sub/tv/$tmdbId/$season/$episode/subtitles.json"
            } else {
                "sub/movie/$tmdbId/subtitles.json"
            }
            val body = getText("$GITHUB_RAW/$path") ?: return
            for (m in Regex(""""([a-z]{2})"\s*:\s*"(https?[^"]+)"""").findAll(body)) {
                subtitleCallback(newSubtitleFile(m.groupValues[1].uppercase(), PlayNet.deEsc(m.groupValues[2])) {})
            }
        } catch (_: Exception) {}
    }
}
