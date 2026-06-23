version = 1

cloudstream {
    description = "PPV Live Streams – lists currently live matches from ppv.to"
    authors = listOf("KSHITIJ8473")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    iconUrl = "https://i.imgur.com/6X8K9RM.png"
}

android {
    namespace = "com.laddu100"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}
