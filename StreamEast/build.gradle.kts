// use an integer for version numbers
version = 2

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    description = "StreamEast Sports Provider"
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

    iconUrl = "https://d3e6ckxkrs5ntg.cloudfront.net/artists/images/8639152/original/crop:x0y0w500h500/hash:1698153106/a.jpg?1698153106"
}
