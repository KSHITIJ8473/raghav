version = 11

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
    language = "hi"
    description = "Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages"
    authors = listOf("raghav")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = true
    iconUrl = "https://raw.githubusercontent.com/KSHITIJ8473/raghav/master/NetMirror/logo.jpeg"
}
