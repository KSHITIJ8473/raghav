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
    description = "CinestreamSite - Movies & TV Series with multi-audio"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://thecinestream.pages.dev/images/favicon.jpeg"
}
