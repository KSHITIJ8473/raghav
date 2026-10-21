version = 5

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
    description = "hindi cartoon (unstable and many dead links)"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Anime", "Cartoon", "TvSeries", "Movie")
    iconUrl = "https://www.rareanimes.mov/wp-content/uploads/2023/11/cropped-Rare-Animes-India.png"
}
