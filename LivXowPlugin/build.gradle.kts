version = 1

cloudstream {
    language = "en"
    description = "LivXow - Live Sports & TV Streaming"
    authors = listOf("KSHITIJ8473")

    status = 1
    tvTypes = listOf(
        "Live",
        "TvSeries"
    )
    iconUrl = "https://hshshebegge.store/favicon.ico"
}

android {
    namespace = "com.laddu100"
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}