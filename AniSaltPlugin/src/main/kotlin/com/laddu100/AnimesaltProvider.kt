package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimesaltProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Animesalt())
        registerExtractorAPI(Pixdrive())
        registerExtractorAPI(Ghbrisk())
        registerExtractorAPI(AWSStream())
        registerExtractorAPI(Zephyrflick())
        registerExtractorAPI(betaAwstream())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Rapid())
        registerExtractorAPI(ascdn21())
        registerExtractorAPI(Abyass())
        registerExtractorAPI(AnimesaltMulti())
        registerExtractorAPI(Short())
    }
}
