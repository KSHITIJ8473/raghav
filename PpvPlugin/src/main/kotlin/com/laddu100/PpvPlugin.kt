package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PpvPlugin : BasePlugin() {
    override fun load() {
        // Register our custom provider which lists live streams from ppv.to
        registerMainAPI(PpvProvider())
    }
}
