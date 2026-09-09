package com.laddu100.animeinweb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeInWebPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeInWebProvider())
    }
}
