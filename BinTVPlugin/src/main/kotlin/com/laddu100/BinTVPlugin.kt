package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BinTVPlugin : Plugin() {
    override fun load(context: Context) {
        BinTVProvider.context = context
        registerMainAPI(BinTVProvider())
    }
}
