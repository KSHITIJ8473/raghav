package com.example

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnikotoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnikotoProvider())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidwish())
    }
}
