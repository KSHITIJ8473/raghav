package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

open class AnivexaMegaPlay(private val sourceName: String = "MegaPlay") : ExtractorApi() {
    override val name = sourceName
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.m3u8"""),
                additionalUrls = emptyList(),
                script = """document.querySelector('.jw-icon-display, .vds-play-button')?.click();""",
                useOkhttp = false,
                timeout = 15_000L
            )
            val resolved = app.get(url, referer = referer ?: "https://anivexa.vercel.app/", interceptor = resolver).url
            if (resolved.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, resolved, mainUrl).forEach(callback)
            }
        } catch (e: Exception) {}
    }
}

class AnivexaVidWish : AnivexaMegaPlay("VidWish") {
    override val mainUrl = "https://vidwish.live"
}

class AnivexaVidNest : AnivexaMegaPlay("VidNest") {
    override val mainUrl = "https://vidnest.fun"
}
