version = 18

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
    description = "NetNaija - Watch Movies, TV Series, Anime, bollywood, Korean & Hollywood. HD streaming with multi-language."
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "OVA")
    iconUrl = "https://netnaija.film/favicon.ico"
}
