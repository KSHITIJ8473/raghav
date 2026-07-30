// use an integer for version numbers
version = 11

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Watch Live TV channels & sports via PlayFy TV"
    authors = listOf("raghav")

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
    requiresResources = true

    iconUrl = "https://www.google.com/s2/favicons?domain=playfy.tv&sz=%size%"
}
