version = 2

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
    description = "Multimovies - Movies, TV Shows & Anime"
    authors = listOf("raghav,phisher")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://multimovies.motorcycles/wp-content/uploads/2024/01/cropped-CompressJPEG.online_512x512_image.png"
}
