version = 10

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    description = "Movies and series from multiple sources with a Manage Sources screen that filters download and stream links"
    authors = listOf("KSHITIJ8473")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "en"
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSknDTJSN2AhrHzfxF75gr9-qG4x-JzwHRnvPDjZP2aa-93KRaXPF1_ZQdm&s=10"
}
