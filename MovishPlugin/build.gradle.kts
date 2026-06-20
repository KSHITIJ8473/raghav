version = 1

cloudstream {
    language = "en"
    description = "Movish Provider - Movies and TV Shows"
    authors = listOf("KSHITIJ8473")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live"
    )
    iconUrl = "https://movish.net/favicon.ico"
}

android {
    namespace = "com.laddu100"
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}