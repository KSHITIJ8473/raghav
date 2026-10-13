version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "hi"
    description = "HiCine - Bollywood and Hollywood movies, web series, anime and TV shows in dual audio."
    authors = listOf("KSHITIJ8473")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie")
    iconUrl = "https://png.pngtree.com/element_our/png/20181227/movie-icon-which-is-designed-for-all-application-purpose-new-png_287896.jpg"
}
