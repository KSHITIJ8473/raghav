package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniDoorPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniDoor())
        registerExtractorAPI(AniDoorMegaPlay())
        registerExtractorAPI(AniDoorTryEmbed())
    }
}
