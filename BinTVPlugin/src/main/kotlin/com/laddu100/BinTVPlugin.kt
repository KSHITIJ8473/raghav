package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BinTVPlugin : BasePlugin() {
    override fun load() {
        // Register our custom provider which lists live streams from bintv.net
        registerMainAPI(BinTVProvider())
    }
}
