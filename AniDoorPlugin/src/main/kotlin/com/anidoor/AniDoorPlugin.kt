package com.anidoor

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class AniDoorPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AniDoorProvider())
        registerExtractorAPI(MegaPlayExtractor())
        registerExtractorAPI(TryEmbedExtractor())
    }
}
