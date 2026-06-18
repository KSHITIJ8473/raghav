// use an integer for version numbers
version = 81

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Netmirror - Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages"
    authors = listOf("KSHITIJ8473")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    requiresResources = true

    iconUrl = "https://www.zilliondesigns.com/blog/wp-content/uploads/feature-img-min-1280x720.png"
}
