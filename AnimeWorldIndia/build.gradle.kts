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
    description = "Anime World India - Hindi, Tamil, Telugu Anime & Cartoons with multi-audio"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Cartoon")
    language = "hi"
    iconUrl = "https://watchanimeworld.top/wp-content/uploads/AW_Smiley.png"
}
