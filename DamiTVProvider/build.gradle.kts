// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    description = "dont install under work - live sports provider"
    authors = listOf("KSHITIJ8473")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://dami-tv.pro/assets/img/logo.png"
}
