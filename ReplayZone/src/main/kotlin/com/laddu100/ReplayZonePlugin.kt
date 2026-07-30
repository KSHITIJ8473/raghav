package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReplayZonePlugin : Plugin() {
    override fun load() {
        registerMainAPI(ReplayZoneProvider())
    }
}
