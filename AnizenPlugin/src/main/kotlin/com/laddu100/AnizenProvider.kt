package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnizenProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(Anizen())
        registerExtractorAPI(AnizenMegaPlay())
        registerExtractorAPI(AnizenVidWish())
        registerExtractorAPI(AnizenVidTube())
        registerExtractorAPI(AnizenAbyss())
        registerExtractorAPI(AnizenRyzex())
    }
}
