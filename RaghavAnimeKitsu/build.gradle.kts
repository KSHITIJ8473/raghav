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
    description = "ONE STOP SOLUTION FOR ANIME(SUB,DUB) - Kitsu Catalog - Raghav Aggregated Anime Plugin. 17 sources: Miruro, AniSuge, AniWaves, Anikai, AniDb, AniKage, Anineko, 2DHive, AniKoto, Enma, Animo, Anidap, Senshi, AniNami, AniDao, AniChan, Kyren"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://www.pngall.com/wp-content/uploads/13/Anime-Logo-PNG-Images.png"
}
