package com.vegamovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VegaMoviesPlugin : Plugin() {
    override fun load() {
        registerMainAPI(VegaMovies())
    }
}
