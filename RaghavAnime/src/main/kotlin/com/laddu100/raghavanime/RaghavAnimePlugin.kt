package com.laddu100.raghavanime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class RaghavAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(RaghavAnime())
        
        // Register Miruro's custom extractors
        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
        
        // Register AniWaves' custom extractors
        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())
    }
}
