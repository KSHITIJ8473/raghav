version = 23

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
    iconUrl = "https://cdn.aptoide.com/imgs/9/7/c/97c80794a7f0fc0b43ea49090343c49d_icon.png"
}
