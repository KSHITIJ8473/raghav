version = 3

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
    description = "Movies, shows, cartoons and anime in HD"
    authors = listOf("KSHITIJ8473")

    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie", "Cartoon")
    iconUrl = "https://play-lh.googleusercontent.com/RVADHx9LB0v5T0icvbhDrnVN32jrNMGG6MpDKWnHK6uPIQESx0yIIbQNQBCj_GcX42HqnQ6Bf1sfkTKDMSrnQQ=w240-h480-rw"
}
