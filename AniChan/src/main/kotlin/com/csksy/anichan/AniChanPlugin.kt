package com.csksy.anichan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniChanPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniChanProvider())
    }
}
