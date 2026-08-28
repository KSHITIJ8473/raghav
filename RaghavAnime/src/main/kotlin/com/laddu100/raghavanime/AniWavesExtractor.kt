package com.laddu100.raghavanime

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName

class AniWavesWebView(private val sourceName: String, private val baseUrl: String) : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = baseUrl
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[AniWaves][AniWavesWebView] getUrl source=$sourceName url=${url.take(120)} referer=$referer")
        runCatching {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4)(?:\?|$)"""),
                additionalUrls = listOf(Regex("""(?i)\.(m3u8|mp4)(?:\?|$)""")),
                script = """document.querySelector('button,[role="button"],.jw-icon-display,.vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 30_000L
            )
            Log.d("RaghavAnime", "[AniWaves][AniWavesWebView] WebView resolution attempt for ${url.take(120)}")
            val resolved = app.get(url, referer = referer ?: mainUrl, interceptor = resolver).url
            Log.d("RaghavAnime", "[AniWaves][AniWavesWebView] resolved url=${resolved.take(120)}")
            val headers = mapOf("Referer" to url)
            when {
                resolved.contains(".m3u8", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[AniWaves][AniWavesWebView] emitting m3u8 links: ${resolved.take(120)}")
                    generateM3u8(name, resolved, mainUrl, headers = headers).forEach(callback)
                }
                resolved.contains(".mp4", ignoreCase = true) -> {
                    Log.d("RaghavAnime", "[AniWaves][AniWavesWebView] emitting mp4 link: ${resolved.take(120)}")
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
            }
        }.onFailure { error ->
            Log.e("RaghavAnime", "[AniWaves][AniWavesWebView] getUrl failed: ${error.message}")
        }
    }
}

class AniWavesEchoVideo : ExtractorApi() {
    override val name = "EchoVideo"
    override val mainUrl = "https://play.echovideo.ru"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[AniWaves][EchoVideo] getUrl url=${url.take(120)} referer=$referer")
        val response = app.get(
            url,
            referer = referer ?: "https://aniwaves.ru/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
        )

        val html = response.text
        Log.d("RaghavAnime", "[AniWaves][EchoVideo] html length=${html.length}")

        val m3u8Regex = Regex("""(?:file|src|source|url)\s*[:=]\s*['"](https?://[^'"]*\.m3u8[^'"]*)['""]""")
        val m3u8Matches = m3u8Regex.findAll(html)

        for (match in m3u8Matches) {
            val m3u8Url = match.groupValues[1]
            Log.d("RaghavAnime", "[AniWaves][EchoVideo] m3u8 link: ${m3u8Url.take(120)}")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "Referer" to (referer ?: mainUrl),
                        "Origin" to mainUrl
                    )
                }
            )
        }

        Log.d("RaghavAnime", "[AniWaves][EchoVideo] m3u8 matches=${m3u8Regex.findAll(html).count()}")
        val mp4Regex = Regex("""(?:file|src|source|url)\s*[:=]\s*['"](https?://[^'"]*\.mp4[^'"]*)['""]""")
        val mp4Matches = mp4Regex.findAll(html)

        for (match in mp4Matches) {
            val mp4Url = match.groupValues[1]
            Log.d("RaghavAnime", "[AniWaves][EchoVideo] mp4 link: ${mp4Url.take(120)}")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = mp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "Referer" to (referer ?: mainUrl)
                    )
                }
            )
        }

        Log.d("RaghavAnime", "[AniWaves][EchoVideo] mp4 matches=${mp4Regex.findAll(html).count()}")
        val jsonSourceRegex = Regex(""""sources"\s*:\s*\[([^\]]+)\]""")
        val jsonMatch = jsonSourceRegex.find(html)
        Log.d("RaghavAnime", "[AniWaves][EchoVideo] json sources block found=${jsonMatch != null}")
        if (jsonMatch != null) {
            val sourcesJson = jsonMatch.groupValues[1]
            val urlRegex = Regex(""""(?:file|url|src)"\s*:\s*"([^"]+)"""")
            for (urlMatch in urlRegex.findAll(sourcesJson)) {
                val sourceUrl = urlMatch.groupValues[1].replace("\\/", "/")
                Log.d("RaghavAnime", "[AniWaves][EchoVideo] json link: ${sourceUrl.take(120)}")
                val type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = sourceUrl,
                        type = type
                    ) {
                        this.headers = mapOf(
                            "Referer" to (referer ?: mainUrl)
                        )
                    }
                )
            }
        }
    }
}

class AniWavesFilemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://weneverbeenfree.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[AniWaves][Filemoon] getUrl url=${url.take(120)} referer=$referer")
        val response = app.get(url, referer = referer ?: "https://aniwaves.ru/")
        val html = response.text
        Log.d("RaghavAnime", "[AniWaves][Filemoon] html length=${html.length}")

        val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packed = packedRegex.find(html)?.value
        Log.d("RaghavAnime", "[AniWaves][Filemoon] packed script found=${packed != null} len=${packed?.length}")

        if (packed != null) {
            // The packed script usually contains file:"https://...m3u8"
            val unpackedUrls = Regex("""https?://[^\s"'\\]+\.m3u8[^\s"'\\]*""").findAll(packed)
            for (match in unpackedUrls) {
                Log.d("RaghavAnime", "[AniWaves][Filemoon] packed m3u8 link: ${match.value.take(120)}")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = match.value,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to url)
                    }
                )
            }
        }

        val directM3u8 = Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""").findAll(html)
        for (match in directM3u8) {
            Log.d("RaghavAnime", "[AniWaves][Filemoon] direct m3u8 link: ${match.groupValues[1].take(120)}")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to url)
                }
            )
        }
    }
}

class AniWavesMyVidPlay : ExtractorApi() {
    override val name = "MyVidPlay"
    override val mainUrl = "https://myvidplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("RaghavAnime", "[AniWaves][MyVidPlay] getUrl url=${url.take(120)} referer=$referer")
        val response = app.get(url, referer = referer ?: "https://aniwaves.ru/")
        val html = response.text
        Log.d("RaghavAnime", "[AniWaves][MyVidPlay] html length=${html.length}")

        val m3u8Regex = Regex("""(?:file|src|source)\s*[:=]\s*["'](https?://[^"']*\.m3u8[^"']*)["']""")
        for (match in m3u8Regex.findAll(html)) {
            Log.d("RaghavAnime", "[AniWaves][MyVidPlay] m3u8 link: ${match.groupValues[1].take(120)}")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = match.groupValues[1],
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf("Referer" to url)
                }
            )
        }

        val jsonSourceRegex = Regex(""""sources"\s*:\s*\[([^\]]+)\]""")
        val jsonMatch = jsonSourceRegex.find(html)
        Log.d("RaghavAnime", "[AniWaves][MyVidPlay] json sources block found=${jsonMatch != null}")
        if (jsonMatch != null) {
            val urlRegex = Regex(""""(?:file|url)"\s*:\s*"([^"]+)"""")
            for (urlMatch in urlRegex.findAll(jsonMatch.groupValues[1])) {
                val sourceUrl = urlMatch.groupValues[1].replace("\\/", "/")
                Log.d("RaghavAnime", "[AniWaves][MyVidPlay] json link: ${sourceUrl.take(120)}")
                val type = if (sourceUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = sourceUrl,
                        type = type
                    ) {
                        this.headers = mapOf("Referer" to url)
                    }
                )
            }
        }
    }
}
