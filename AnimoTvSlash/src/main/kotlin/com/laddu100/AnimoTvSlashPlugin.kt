package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimoTvSlashPlugin : Plugin() {
    override fun load(context: Context) {
        // Initialize Cloudflare bypass (persistent cookie storage)
        initAnimoTvSlashCFBypass(context)
        registerMainAPI(AnimoTvSlashProvider())
    }
}
