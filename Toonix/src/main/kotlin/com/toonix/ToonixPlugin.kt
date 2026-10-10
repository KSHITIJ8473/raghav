package com.toonix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ToonixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Toonix())
    }
}
