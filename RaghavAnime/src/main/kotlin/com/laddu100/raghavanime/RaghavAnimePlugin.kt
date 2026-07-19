package com.laddu100.raghavanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaghavAnimePlugin : Plugin() {
    override fun load(context: Context) {

        Miruro.context = context

        initAniDbCFBypass(context)

        initAnidapCFBypass(context)

        initSenshiCFBypass(context)
        registerMainAPI(RaghavAnime())

        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())

        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())
    }
}
