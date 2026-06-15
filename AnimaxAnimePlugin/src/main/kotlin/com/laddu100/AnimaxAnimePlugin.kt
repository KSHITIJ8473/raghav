package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimaxAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimaxAnime())
        registerExtractorAPI(AnimaxAnimeEmbedExtractor())
    }
}
