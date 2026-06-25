version = 17


android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    description = "Live sports HIGH QAULITY"
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

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTAvvc01IqbjrBZ-AhXwvyrIblnaJBA04IUNKmJNRbqEh46fWVItaGuoRsj&s=10"
}
