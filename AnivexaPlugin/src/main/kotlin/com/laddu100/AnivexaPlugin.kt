package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnivexaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Anivexa())
        registerExtractorAPI(AnivexaMegaPlay())
        registerExtractorAPI(AnivexaVidWish())
        registerExtractorAPI(AnivexaVidTube())
    }
}
