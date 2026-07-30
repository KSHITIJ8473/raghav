package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AniWavesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniWaves())
        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())
    }
}
