package com.kdesa

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KdesaPlugin : Plugin() {
    override fun load(context: Context) {
        KdesaCF.init(context)
        registerMainAPI(KdesaProvider())
    }
}
