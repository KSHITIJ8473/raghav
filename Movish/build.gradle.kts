version = 2

cloudstream {
    language = "en"
    description = "Movish Provider - Movies and TV Shows"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Live"
    )
    iconUrl = "https://media.istockphoto.com/id/1944783914/vector/cinema-film.jpg?s=612x612&w=0&k=20&c=FEAMMl1hur-NKlA3OieFEsVB0D5kI1Xg9is2bCmFg9o="
}

android {
    namespace = "com.laddu100"
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
    defaultConfig {
        buildConfigField("String", "TMDB_KEY", "\"1865f43a0549ca50d341dd9ab8b29f49\"")
    }
}
