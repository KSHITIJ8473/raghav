version = 8

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Multimovies - Movies, TV Shows & Anime. Sources: Cineverse (multi-server), GD Mirror, Vidout, Vidsync, Bingr, Filmu, VidBolt"
    authors = listOf("raghav,phisher,csksy")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://multimovies.garden/assets/img/logo.png"
}
