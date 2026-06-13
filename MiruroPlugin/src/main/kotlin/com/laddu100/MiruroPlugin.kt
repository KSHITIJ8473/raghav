package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MiruroPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Miruro())
        registerExtractor(MiruroMegaPlay())
        registerExtractor(MiruroVidWish())
    }
}
