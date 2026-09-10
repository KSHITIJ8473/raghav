package com.laddu100.reanime

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReAnimePlugin : Plugin() {
    override fun load() {
        registerMainAPI(ReAnimeProvider())
    }
}
