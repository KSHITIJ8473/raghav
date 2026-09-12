version = 1

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Anime with sub & dub, multi-language subtitles"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://senshi.to/assets/Senshi_Logo-D4bDbCzd.png"
}
