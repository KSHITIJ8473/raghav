package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnidapPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize the anti-bot bypass store (loads persisted _amx_id cookies)
        initAnidapCFBypass(context)
        registerMainAPI(AnidapProvider())
    }
}
