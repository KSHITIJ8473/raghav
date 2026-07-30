package com.laddu100

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TheMoviesFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TheMoviesFlix())
        registerExtractorAPI(FastDlExtractor())
        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(GoFileExtractor())
        registerExtractorAPI(FileBeeExtractor())
    }
}
