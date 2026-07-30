package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NineAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NineAnime())
        registerExtractorAPI(NineAnimeMegaPlay())
        registerExtractorAPI(NineAnimeVidWish())
        registerExtractorAPI(NineAnimeVidmoly())
        registerExtractorAPI(NineAnimeMoon())
    }
}
