package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MiruroPlugin : Plugin() {
    override fun load(context: Context) {
        Miruro.context = context
        registerMainAPI(Miruro())
        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
    }
}
