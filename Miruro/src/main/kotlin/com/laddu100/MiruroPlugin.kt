package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MiruroPlugin : Plugin() {
    override fun load(context: Context) {
        // Store context for WebView-based Cloudflare bypass
        Miruro.context = context
        registerMainAPI(Miruro())
        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
    }
}
