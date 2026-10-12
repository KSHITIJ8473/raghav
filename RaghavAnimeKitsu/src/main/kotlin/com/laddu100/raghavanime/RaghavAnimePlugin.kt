package com.laddu100.raghavanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaghavAnimePlugin : Plugin() {
    override fun load(context: Context) {

        Miruro.context = context

        initAnidapCFBypass(context)

        initSenshiCFBypass(context)
        RaghavSenshiVhost.init(context)

        RaghavAniChanWeb.init(context)

        registerMainAPI(RaghavAnime())

        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
        registerExtractorAPI(VidTubeExtractor())

        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())

        registerExtractorAPI(KwikExtractor())

        this.openSettings = { ctx ->
            try {
                RaghavAnimeSettings.show(ctx)
            } catch (_: Exception) {}
        }
    }
}
