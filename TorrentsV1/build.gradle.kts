version = 16

android {
    namespace = "com.torrentsv1"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "TMDB_KEY", "\"1865f43a0549ca50d341dd9ab8b29f49\"")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    language = "en"
    description = "Torrent support for Anime, Movies and TV via Torrentio, TorrentsDB, Animetosho and custom Stremio addons"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA", "Movie", "TvSeries", "Torrent")
    iconUrl = "https://png.pngtree.com/element_our/png/20181227/movie-icon-which-is-designed-for-all-application-purpose-new-png_287896.jpg"
    requiresResources = true
}
