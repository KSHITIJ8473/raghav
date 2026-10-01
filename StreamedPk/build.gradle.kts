version = 12

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}

cloudstream {
    language = "en"
    description = "ALL LIVE SPORTS WITH MULTIPLE SERVER"
    authors = listOf("RAGHAV")

    status = 1
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://streamed.pk/favicon.png"
}
