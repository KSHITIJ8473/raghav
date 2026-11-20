package com.laddu100

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class LIVETVCategoryData(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val catLink: String? = null,
    val position: String? = null,
    val publish: String? = null,
)

data class LIVETVEventInfo(
    val eventName: String? = null,
    val teamA: String? = null,
    val teamAFlag: String? = null,
    val teamB: String? = null,
    val teamBFlag: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val eventBanner: String? = null,
    val isPinned: Int? = null,
)

data class LIVETVChannel(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val cat: String? = null,
    val category: String? = null,
    val publish: String? = null,
    val formats: List<String>? = null,
    val eventInfo: LIVETVEventInfo? = null,
)

data class LIVETVCatFilter(
    val id: String? = null,
    val image: String? = null,
    val position: String? = null,
    val publish: String? = null,
    val title: String? = null,
)

data class LIVETVChannelListResponse(
    val cats: List<LIVETVCatFilter>? = null,
    val channels: List<LIVETVChannel>? = null,
)

data class LIVETVStreamEntry(
    val id: Int? = null,
    val title: String? = null,
    val link: String? = null,
    val api: String? = null,
    val type: String? = null,
    val webLink: String? = null,
    val defaultLanguage: String? = null,
)

object LIVETVProviderManager {
    private const val DEFAULT_BASE_URL = "https://reliablewebcenter.online"
    private const val USER_AGENT = "PLAYFy/1.7 (Android)"

    private const val CONFIG_TTL_MS = 1_800_000L
    private const val FALLBACK_TTL_MS = 120_000L
    private const val MIN_REFETCH_MS = 60_000L

    private val baseUrl = ExpiringRemoteValue(CONFIG_TTL_MS, FALLBACK_TTL_MS, MIN_REFETCH_MS)
    private val lora = ExpiringRemoteValue(CONFIG_TTL_MS, FALLBACK_TTL_MS, MIN_REFETCH_MS)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(StaleDomainInterceptor(baseUrl))
        .build()

    private val fallbackProviders = listOf(
        mapOf("id" to 13, "title" to "TATA PLAY", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQz_qYe3Y4S5bXXVlPtXQnqtAkLw1-no57QHhPyMgWE0SQmxujzHxZKiDs&s=10", "catLink" to "https://hotstarlive.delta-cloud.workers.dev/?token=240bb9-374e2e-3c13f0-4a7xz5"),
        mapOf("id" to 14, "title" to "HOTSTAR", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRWwYjMvB58DMLsL9Ii2fhvw6NBYvD1iVCjOMU8TXBLJt0eibLGOjoRkLJP&s=10", "catLink" to "https://hotstar-live-event.alpha-circuit.workers.dev/?token=a13d9c-4b782a-6c90fd-9a1b84"),
        mapOf("id" to 15, "title" to "TATAPLAY BD", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQz_qYe3Y4S5bXXVlPtXQnqtAkLw1-no57QHhPyMgWE0SQmxujzHxZKiDs&s=10", "catLink" to "https://ranapk.short.gy/BDIX/tata.php"),
        mapOf("id" to 17, "title" to "T SPORTS ", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRJ0QvfKyjAqcCOumIXjcuYg505GnaBeVk2lQ&usqp=CAU ", "catLink" to "https://fifabangladesh2-xyz-ekkj.spidy.online/AYN/tsports.m3u  "),
        mapOf("id" to 18, "title" to "FANCODE IND", "image" to "https://play-lh.googleusercontent.com/lp1Tdhp75MQyrHqrsyRBV74HxoL3Ko8KRAjOUI1wUHREAxuuVwKR6vnamgvMEn4C4Q", "catLink" to "https://raw.githubusercontent.com/Jitendra-unatti/fancode/refs/heads/main/data/fancode.m3u"),
        mapOf("id" to 19, "title" to "SONYLIV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTzscCrHEfnHNeZdMO3haF1XSVgjskN4TNv0g&usqp=CAU ", "catLink" to "https://raw.githubusercontent.com/doctor-8trange/zyphora/refs/heads/main/data/sony.m3u"),
        mapOf("id" to 22, "title" to "JIO IND", "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png ", "catLink" to "https://jiotv.byte-vault.workers.dev/?token=42e4f5-2d873b-3c37d8-7f3f50"),
        mapOf("id" to 29, "title" to "SONY IN", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU", "catLink" to "https://sonyliv.logic-lane.workers.dev?token=a14d9c-4b782a-6c90fd-9a1b84"),
        mapOf("id" to 31, "title" to "SONY IN 2", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU", "catLink" to "https://raw.githubusercontent.com/ramnarayan01/data/refs/heads/main/s0nyind.m3u.html"),
        mapOf("id" to 48, "title" to "SUN DIRECT", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSwc4OuqPmOP-Fi9dhfiDw_q-s3rOmgCPla_IaE76VD2KRQ7c4KHeI2zJY&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/suntv.m3u"),
        mapOf("id" to 70, "title" to "VOOT BD", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfS6QZFts2FoedMGZE28H7Kh158PsrNIiabFBVJMy_jXa8Tvvb9WAlut8&s=10", "catLink" to "https://ranapk.short.gy/VOOTBD.m3u"),
        mapOf("id" to 71, "title" to "VOOT IND", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfS6QZFts2FoedMGZE28H7Kh158PsrNIiabFBVJMy_jXa8Tvvb9WAlut8&s=10", "catLink" to "https://jiocinema-live.cloud-hatchh.workers.dev/?token=42e4f5-2d414b-3c37d8-5f3f45"),
        mapOf("id" to 85, "title" to "SUN NXT", "image" to "https://upload.wikimedia.org/wikipedia/en/d/d5/Sun_NXT_logo_small.png", "catLink" to "https://raw.githubusercontent.com/alexandermail371/cricfytv/refs/heads/main/sunxt.m3u"),
        mapOf("id" to 90, "title" to "AIRTEL IND", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQf7pkggfHJKj2R8O6ttuHxgv-vQVL03xUeAg&usqp=CAU", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/artl.m3u"),
        mapOf("id" to 92, "title" to "DISTRO TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRYQjBTT5SL_kuJF7CbQtoSEA7PzyH9RYIuDO9F1sx87CtiULDyiDf7ybt&s=10", "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/DistroTV.m3u"),
        mapOf("id" to 104, "title" to "ZEE5", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU", "catLink" to "https://zee5.cloud-hatchh.workers.dev/?token=42e4f5-2d413b-3c37d8-7f3f35"),
        mapOf("id" to 106, "title" to "JIOTV+", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRh5KeAyYdOyxaCWDPbiUsJW7Oy4v_7uFqf06rIwGxaWc6nQuNVqZ2Q_Qej&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/jtv.m3u"),
        mapOf("id" to 110, "title" to "JIOLIVE IND", "image" to "https://lens-storage.storage.googleapis.com/png/bb364a303da24e5db23f01bac26949cf", "catLink" to "https://raw.githubusercontent.com/alex8875/jc_live/refs/heads/main/jevents_live.m3u"),
        mapOf("id" to 111, "title" to "ISLAMIC TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTcTOpdBeIBA52NTSANHC6Ow0v-k6hAr76vWg&usqp=CAU", "catLink" to "https://tv.noobon.top/playlist/islamic.php"),
        mapOf("id" to 114, "title" to "TAPMAD PK", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT4X-7suwtvYWwoa6m0ngFTKZt5Hg5Z2kQF1g&usqp=CAU", "catLink" to "https://tv.noobon.top/playlist/tapmad.php"),
        mapOf("id" to 117, "title" to "ZEE5 IN", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU", "catLink" to "https://ranapk.short.gy/Z5in.php"),
        mapOf("id" to 126, "title" to "WORLD TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSg8OiEwOT5UL5UttBZ5Tnhgsod8i2EQlfB97FFchdBOo8e_PfxvR8RJ68&s=10", "catLink" to "https://ranapk.short.gy/WorldTV11.m3u"),
        mapOf("id" to 129, "title" to "AYNA", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQvAant65yQ_au1e51MFs-uiE6juswXv4ZJoNCpzBBSg4q7DzJ1NeliS80c&s=10", "catLink" to "https://playlist-cricfy.noobon.top/aynaott.php"),
        mapOf("id" to 130, "title" to "JIO CINEMA IND", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQc3qZ1WgzPyFRX4cWIBJF0MSjWW3gZcLFycg&usqp=CAU", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/jcinema.m3u"),
        mapOf("id" to 131, "title" to "DISH TV", "image" to "https://m.media-amazon.com/images/S/stores-image-uploads-eu-prod/1/AmazonStores/A21TJRUUN4KGV/d5086253b614724be106c06be13f7d54.w600.h600._RO299,1,0,0,0,0,0,0,0,0,15_FMpng_.jpg", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/dishtv.m3u"),
        mapOf("id" to 132, "title" to "SHOOQ PK", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSvSWLnpgyvbzV9rHkREzbsX1Rzh2IbEZBL8yPpSv8aCPmy1nVcv7BhIWQ&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/shoq.m3u"),
        mapOf("id" to 133, "title" to "SAMSUNG TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQI9T5vcm8wU-dLuaK5vBfoHpz8KL9Ru0aU1eoVaKNcqauxGtRTfvI1rGTA&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/samsungtv.m3u"),
        mapOf("id" to 134, "title" to "JAGOBD", "image" to "https://www.jagobd.com/wp-content/uploads/2015/10/web_hi_res_512.png", "catLink" to "https://tv.noobon.top/playlist/jagobd.php"),
        mapOf("id" to 135, "title" to "JADOO", "image" to "https://bdix.net/wp-content/uploads/2019/07/Jadoo-Digital-Logo-PNG-1002x1024.png", "catLink" to "https://fifabangladesh.site/PLAYLIST/jadoo.php"),
        mapOf("id" to 136, "title" to "PISHOW", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcS1hOo397X8uamgdXoknED8klICRLPCqwuEUtB394H2cc7YIyYiD78s-B8&s=10", "catLink" to "http://playlist-storage.pages.dev/PLAYLIST/playboxtv.m3u"),
        mapOf("id" to 146, "title" to "CRICHD", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ08h1gOe7MPxwehZBrbYKAUtjv22B6rAJ1kMkN-cea64Ka49KUyGU2lpTz&s=10", "catLink" to "https://github.com/abusaeeidx/CricHd-playlists-Auto-Update-permanent/raw/main/ALL.m3u"),
        mapOf("id" to 150, "title" to "ZAP SPORTS", "image" to "https://i.ibb.co/dJfysm3V/zap-Sports.png", "catLink" to "https://tv.noobon.top/zapx/api.php?action=getIPTVPlaylist"),
        mapOf("id" to 151, "title" to "Pirates TV", "image" to "https://raw.githubusercontent.com/FunctionError/Logos/main/Pirates-Tv.png", "catLink" to "https://raw.githubusercontent.com/FunctionError/PiratesTv/refs/heads/main/combined_playlist.m3u"),
        mapOf("id" to 152, "title" to "YUPPTV", "image" to "https://d229kpbsb5jevy.cloudfront.net/bott/v2/networks/circularimages/yupptv.png", "catLink" to "https://tv.noobon.top/playlist/yapp.php"),
        mapOf("id" to 153, "title" to "DANGAL TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTMnDuHwjd3WEFHmObqo53bAjkIB6E7JvIIPjGGGxqZzbNsKT85D_sRYNc&s=10", "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/DangalPlay.m3u"),
        mapOf("id" to 157, "title" to "Movies & Series", "image" to "https://i.postimg.cc/QCVVj6D1/Movies.png", "catLink" to "http://tv.noobon.top/playlist/movies.php"),
        mapOf("id" to 158, "title" to "DEKHO 24 X 7", "image" to "https://tstatic.videoready.tv/cms-ui/images/custom-content/1739684250358.png", "catLink" to "https://dehkho24h.alpha-circuit.workers.dev/?token=1b8j9b-796c8a-36e17f-8f83a5"),
        mapOf("id" to 159, "title" to "JIOTV+ S2", "image" to "https://i.ibb.co/VY9ND7rY/image.png", "catLink" to "https://jiotvplus.byte-vault.workers.dev/?token=42e4f5-2d863b-3c38d8-7f3f51"),
        mapOf("id" to 163, "title" to "JIOHOTSTAR", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSPuz9ekmjh3vEpEc3lYL4nh6Gj7y2CQTswVG-ZCHnIS1foScuwPzuyxic&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/jstar.m3u"),
        mapOf("id" to 164, "title" to "JIOTV+ S3", "image" to "https://i.ibb.co/VY9ND7rY/image.png", "catLink" to "https://jiotv.edge-nexus.workers.dev/?token=42e4f5-2d863b-3c37d8-8f3f51"),
        mapOf("id" to 165, "title" to "ICC TV", "image" to "https://m.media-amazon.com/images/I/31F7ropt9OL.png", "catLink" to "https://icc.alpha-circuit.workers.dev/?token=42e4f5-2d863b-3c37d8-7f3f69"),
        mapOf("id" to 166, "title" to "Pluto Tv", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRI_jYjppElN7Tb6Ok3bL_J0K7QQPzfQbzPeAWzVilH9y7CYKzAy-XJbi4&s=10", "catLink" to "https://tv.noobon.top/playlist/plutotv.php"),
        mapOf("id" to 168, "title" to "FANCODE BD", "image" to "https://play-lh.googleusercontent.com/lp1Tdhp75MQyrHqrsyRBV74HxoL3Ko8KRAjOUI1wUHREAxuuVwKR6vnamgvMEn4C4Q", "catLink" to "https://ranapk.short.gy/FCBD/playlist.php"),
        mapOf("id" to 169, "title" to "EPL BD IP", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQye0cNsXab_lPM3Zv2pklCk2ZT92X3micxy7jF2n5F46-kaSgnIBjHq3KY&s=10", "catLink" to "https://bdix.short.gy/EPLxBDIX/playlist.php"),
        mapOf("id" to 170, "title" to "JIO BD", "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png", "catLink" to "https://ranapk.short.gy/JIOBD.m3u"),
        mapOf("id" to 173, "title" to "World Sports", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT7iSlqAmYv4wa-7P9aRqiLVniqbUQUtVNmgsf4BxJJqpRKNJVhlVHvFKI&s=10", "catLink" to "https://tv.xmasterbd.sbs/dhd/playlist.php"),
        mapOf("id" to 174, "title" to "Prime Channel", "image" to "https://static.vecteezy.com/system/resources/previews/046/437/251/non_2x/amazon-prime-logo-free-png.png", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/amzusa.m3u"),
        mapOf("id" to 175, "title" to "RUN TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQ7zqXTonSH_Xo--YxMlOacinf7mhLwuwSFFF1KJa8lGw&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/runn.m3u"),
        mapOf("id" to 176, "title" to "WAVES OTT", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSNyx_lxD3xXIB8jpFGnMnHZIziUo1vKW9sSS-7zP-h0vhZT4cPB6wly6o&s=10", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/waves.m3u"),
        mapOf("id" to 177, "title" to "JIO IND2", "image" to "https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/jio-logo-icon.png", "catLink" to "https://playlist-cricfy.noobon.top/noob/jiotv.php"),
        mapOf("id" to 178, "title" to "JIOTV+ S4", "image" to "https://i.ibb.co/VY9ND7rY/image.png", "catLink" to "https://jiotvplus.iron-shield.workers.dev/?token=42e4f5-2d963b-3c37d8-7f3f52"),
        mapOf("id" to 179, "title" to "LGTV IND", "image" to "https://raw.githubusercontent.com/alex8875/img/refs/heads/main/LG_tv.png", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/lgtv.m3u"),
        mapOf("id" to 180, "title" to "TOFFEE BD", "image" to "https://yt3.googleusercontent.com/q0CJuxOL2f7Duy3hiM3uKArC_Zvji24XVGNfcf2TMqXMxTW6RO3R3tqwH2XAxbcz5TG-d2p6=s900-c-k-c0x00ffffff-no-rj", "catLink" to "https://playlist-cricfy.noobon.top/toffee.php"),
        mapOf("id" to 181, "title" to "ZEE5 IN 2", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU", "catLink" to "https://raw.githubusercontent.com/alex8875/m3u/refs/heads/main/z5.m3u"),
        mapOf("id" to 182, "title" to "SONY BD", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRxsCm4WKugE7ubLr2J3AP7s-hqHl0dh69ImA&usqp=CAU", "catLink" to "https://ranapk.short.gy/sonybd/playlist.php"),
        mapOf("id" to 183, "title" to "AYNA 2", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQvAant65yQ_au1e51MFs-uiE6juswXv4ZJoNCpzBBSg4q7DzJ1NeliS80c&s=10", "catLink" to "https://bdix2.short.gy/AYNA/playlist.php"),
        mapOf("id" to 184, "title" to "DARK TV", "image" to "https://i.ibb.co/CsQCkNnb/Screenshot-2025-11-04-06-03-45-53.jpg", "catLink" to "https://ranapk.short.gy/Darktv/playlist.php"),
        mapOf("id" to 185, "title" to "AKASH", "image" to "https://image.winudf.com/v2/image1/Y29tLmFrYXNoLmdvX2ljb25fMTcyMjU3ODg2N18wNTA/icon.png?w=184&fakeurl=1", "catLink" to "https://playlist-cricfy.noobon.top/akashgo.php"),
        mapOf("id" to 186, "title" to "FREE TV", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTS4HoWswvKYjnMyenamwz-xBJq0PLSyZYpo0kp3oN6gw&s=10", "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/freetv.m3u"),
        mapOf("id" to 187, "title" to "FANCODE BD 2", "image" to "https://play-lh.googleusercontent.com/lp1Tdhp75MQyrHqrsyRBV74HxoL3Ko8KRAjOUI1wUHREAxuuVwKR6vnamgvMEn4C4Q", "catLink" to "https://ranapk.short.gy/FcOnlyBD/playlist.php"),
        mapOf("id" to 188, "title" to "DISH HOME BD", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQfc5DoDgZ5r5s1_umhMp0UUBENdkOUdWWFcSAzv-EUxA&s", "catLink" to "https://dish.data-vortex.workers.dev?token=42e4f5-2d863b-3c37d8-7f3f51"),
        mapOf("id" to 189, "title" to "ZEE5 LIVE", "image" to "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQS0OT2NFe9Jb4ofg_DrXx42EKLgyGnSGwoLg&usqp=CAU", "catLink" to "https://raw.githubusercontent.com/doctor-8trange/quarnex/refs/heads/main/data/zee5.m3u"),
        mapOf("id" to 190, "title" to "FREE SPORTS", "image" to "https://media.unreel.me/prod/freelivesports/general/6496be67-a318-46c6-a25d-93c161f86845", "catLink" to "https://playlist-storage.pages.dev/PLAYLIST/freelivesports.m3u"),
    )

    fun refreshFromFirebase() {
        baseUrl.reset()
        lora.reset()
    }

    private suspend fun getBaseUrl(): String {
        return baseUrl.get(
            fetch = {
                LIVETVFirebaseFetcher.getBaseUrl()?.trimEnd('/')
            },
            fallback = {
                DEFAULT_BASE_URL
            },
        )
    }

    private suspend fun getLora(): String {
        return lora.get(
            fetch = {
                LIVETVFirebaseFetcher.getLora()
            },
            fallback = {
                LIVETVCryptoUtils.DEFAULT_LORA
            },
        )
    }

    // a failed call invalidates the cached domain or key, one immediate
    // retry picks up the fresh value instead of failing the whole page
    private suspend fun fetchAndDecrypt(path: String): String? {
        for (i in 0 until 2) {
            try {
                val url = "${getBaseUrl()}/$path"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                val raw = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        baseUrl.invalidate()
                        null
                    } else {
                        response.body.string()
                    }
                }

                if (raw.isNullOrBlank()) {
                    continue
                }

                val encoded = LIVETVCryptoUtils.extractDataField(raw)
                if (!encoded.startsWith("[") && !encoded.startsWith("{")) {
                    val decrypted = LIVETVCryptoUtils.decryptPlayFy(
                        encoded,
                        getLora(),
                        LIVETVCryptoUtils.DEFAULT_SIG,
                    )
                    if (decrypted != null) {
                        return decrypted
                    }
                    lora.invalidate()
                    continue
                }
                return encoded
            } catch (_: Exception) {
                baseUrl.invalidate()
            }
        }
        return null
    }

    suspend fun fetchProviders(): List<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                val json = fetchAndDecrypt("cc.json") ?: return@withContext fallbackProviders
                val providers = parseJson<List<LIVETVCategoryData>>(json)
                providers
                    .filter { !it.catLink.isNullOrBlank() && it.catLink != "null" }
                    .map { provider ->
                        mapOf(
                            "id" to (provider.id ?: ""),
                            "title" to (provider.title ?: ""),
                            "image" to (provider.image ?: ""),
                            "catLink" to provider.catLink!!,
                            "type" to "m3u",
                        )
                    }
            } catch (_: Exception) {
                fallbackProviders
            }
        }
    }

    suspend fun fetchLiveEvents(): List<LIVETVChannel> {
        return fetchChannelList("cats/live-events.json")
    }

    suspend fun fetchHighlights(): List<LIVETVChannel> {
        return fetchChannelList("cats/highlights.json")
    }

    private suspend fun fetchChannelList(path: String): List<LIVETVChannel> {
        return withContext(Dispatchers.IO) {
            try {
                val json = fetchAndDecrypt(path) ?: return@withContext emptyList()
                val response = parseJson<LIVETVChannelListResponse>(json)
                response.channels?.filter { it.publish == "1" } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun fetchChannelStreams(channelId: String): List<LIVETVStreamEntry> {
        return withContext(Dispatchers.IO) {
            try {
                val json = fetchAndDecrypt("channels/$channelId.json") ?: return@withContext emptyList()
                parseJson<List<LIVETVStreamEntry>>(json)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
