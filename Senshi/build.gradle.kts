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
    description = "Best site for hard subs and some dubs"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://senshi.live/assets/Senshi_Logo-DRQDIkgF.png"
}
