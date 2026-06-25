// use an integer for version numbers
version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    description = "ALL LIVE SPORTS WITH MULTIPLE SERVER"
    authors = listOf("RAGHAV")

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

    iconUrl = "https://streamed.pk/favicon.png"
}
