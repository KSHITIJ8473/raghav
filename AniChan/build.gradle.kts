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
    description = "Watch Anime in HD with Sub, Dub and Hardsub"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
    iconUrl = "https://www.google.com/s2/favicons?domain=anichan.net&sz=%size%"
}
