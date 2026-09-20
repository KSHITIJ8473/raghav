version = 1

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
    description = "PRMovies - Watch Free Movies, Web Series, Bollywood & Hollywood Online"
    authors = listOf("OshekharO")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime"
    )
    iconUrl = "https://prmovies.recipes/wp-content/uploads/2019/07/prmovies-logo.png"
}
