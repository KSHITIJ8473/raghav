version = 6

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "id"
    description = "Anime sub indo from AnimeInWeb with multiple qualities"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
        "TvSeries"
    )
    iconUrl = "https://animeinweb.com/favicon.ico"
}
