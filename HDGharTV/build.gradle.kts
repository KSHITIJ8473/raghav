version = 4

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "hi"
    description = "Movies & Series with Multi-Audio (Hindi, English, Tamil, Telugu, etc.)"
    authors = listOf("KSHITIJ8473")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    iconUrl = "https://hdghartv.cc/favicon.png"
}
