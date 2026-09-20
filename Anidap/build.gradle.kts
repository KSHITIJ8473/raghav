version = 16

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
    description = "Anime with Sub, Dub & Hardsub from Anidap"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )
    iconUrl = "https://anikuro.ru/static/favicon/favicon-96x96.png"
}
