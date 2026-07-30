package com.laddu100

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TwoDHivePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TwoDHiveProvider())
    }
}
