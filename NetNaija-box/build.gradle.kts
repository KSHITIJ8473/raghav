version = 4

android {
    namespace = "com.netnaija.app"
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "NetNaija-box - Multi Language Movies, Series and Live Sports. HD streaming with multiple dubs and subtitles."
    authors = listOf("raghav")
    status = 1
    requiresResources = true
    tvTypes = listOf("Movie", "TvSeries", "Live")
    iconUrl = "https://netnaija.film/favicon.ico"
}
