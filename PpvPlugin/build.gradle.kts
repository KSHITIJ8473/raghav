version = 3

cloudstream {
    description = "PPV Live Streams – lists currently live matches from ppv.to"
    authors = listOf("KSHITIJ8473")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTVgXotVMUiHc-BjE8KnZWLw256jTJcM2RywSh432dr9qWdg0iFDqRZVscp&s=10"
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
