version = 16

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    language = "en"
    description = "stream movies & TV series HINDI,ENGLISH - TheMoviesFlix Provider"
    authors = listOf("raghav")

    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSj_C9WX4AwepepLyw_cF-EIJeRpqgI4wDiquYSoP9xDFFAKFtTVM-P_zo&s=10"
}
