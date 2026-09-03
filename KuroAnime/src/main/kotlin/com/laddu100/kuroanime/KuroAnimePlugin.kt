package com.laddu100.kuroanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuroAnimePlugin : Plugin() {
    override fun load() {
        registerMainAPI(KuroAnimeProvider())
    }
}
