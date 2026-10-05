package com.justplay

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JustPlayPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JustPlay())
        registerExtractorAPI(PlayHubCloud())
        registerExtractorAPI(PlayVCloud())
        registerExtractorAPI(PlayVegaDrive())
        registerExtractorAPI(PlayFilePress())
        registerExtractorAPI(PlayFastDl())
        registerExtractorAPI(PlayHubCdn())
        registerExtractorAPI(PlayHblinks())
        registerExtractorAPI(PlayHubdrive())
        registerExtractorAPI(PlayHdStream4u())
        registerExtractorAPI(PlayGofile())
        registerExtractorAPI(PlayGDFlix())
        registerExtractorAPI(PlayGDLink())
        openSettings = { ctx ->
            try {
                JustPlaySettings.show(ctx)
            } catch (_: Exception) {}
        }
    }
}
