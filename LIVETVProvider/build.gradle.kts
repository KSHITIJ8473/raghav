version = 46

android {
    namespace = "com.laddu100"
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    defaultConfig {
        buildConfigField("String", "FB_API_KEY", "\"AIzaSyDdHIwVAD3XgP5bEwZOcR1QIz7gO5q5EoM\"")
        buildConfigField("String", "FB_APP_ID", "\"1:239487160038:android:308875071ce6f0fd48f527\"")
        buildConfigField("String", "FB_PROJECT_NUMBER", "\"239487160038\"")
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "Watch LIVE TV channels & sports via LIVE TV"
    authors = listOf("raghav,cnc")
    status = 1
    tvTypes = listOf(
        "Live",
    )
    requiresResources = true

    iconUrl = "https://png.pngtree.com/png-vector/20191026/ourmid/pngtree-live-icon-design-template-vector-isolated-illustration-png-image_1874482.jpg"
}
