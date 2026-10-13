package com.hicine

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HiCinePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HiCine())
    }
}
