package com.laddu100

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimoPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Animo())
    }
}
