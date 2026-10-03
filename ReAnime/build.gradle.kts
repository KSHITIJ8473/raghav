version = 4

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    description = "Re:ANIME - Anime with Sub & Dub"
    authors = listOf("KSHITIJ8473")

    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    language = "en"
    iconUrl = "https://reanime.to/favicon-32x32.png"
}
