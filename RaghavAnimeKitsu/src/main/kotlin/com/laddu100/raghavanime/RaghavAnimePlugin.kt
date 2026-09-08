package com.laddu100.raghavanime

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.laddu100.raghavanime.settings.SettingsFragment

@CloudstreamPlugin
class RaghavAnimePlugin : Plugin() {
    override fun load(context: Context) {

        Miruro.context = context

        initAniDbCFBypass(context)

        initAnidapCFBypass(context)

        initSenshiCFBypass(context)

        initProxCFBypass(context)

        EnmaDecryptor.setContext(context)
        EnmaDecryptor.startInit()

        registerMainAPI(RaghavAnime())

        registerExtractorAPI(MiruroMegaPlay())
        registerExtractorAPI(MiruroVidWish())
        registerExtractorAPI(VidTubeExtractor())

        registerExtractorAPI(AniWavesEchoVideo())
        registerExtractorAPI(AniWavesFilemoon())
        registerExtractorAPI(AniWavesMyVidPlay())

        this.openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                val frag = SettingsFragment()
                frag.show(activity.supportFragmentManager, "RaghavAnimeSettings")
            }
        }
    }
}
