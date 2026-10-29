version = 17

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    description = "Watch Anime in HD with Sub, Dub and Hardsub"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "en"
    iconUrl = "https://play-lh.googleusercontent.com/YxgDK6JP2h08FlpZs__vP2otL3HKp3IXfvb9jVUt0HgWy1V9xcWbIdkH8_o9Z6QQYnRVYPatjcPTcOIpeWtI"
}
