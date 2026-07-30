package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YupFlixPlugin : Plugin() {
    override fun load() {
        registerMainAPI(YupFlixProvider())
    }
}
