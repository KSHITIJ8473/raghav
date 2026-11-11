package com.laddu100

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class FastDlExtractor : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (s in TmfSources.resolveFastDl(url)) {
            callback.invoke(
                ExtractorLink(source = name, name = s.name, url = s.url, referer = mainUrl,
                    quality = Qualities.Unknown.value, type = s.type, headers = s.headers)
            )
        }
    }
}

class VCloudExtractor : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.beer"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (s in TmfSources.resolveVCloud(url)) {
            callback.invoke(
                ExtractorLink(source = name, name = s.name, url = s.url, referer = mainUrl,
                    quality = Qualities.Unknown.value, type = s.type, headers = s.headers)
            )
        }
    }
}

class VegaDriveExtractor : ExtractorApi() {
    override val name = "V-Drive"
    override val mainUrl = "https://one.vegadrive.me"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (s in TmfSources.resolveVegaDrive(url)) {
            callback.invoke(
                ExtractorLink(source = name, name = s.name, url = s.url, referer = mainUrl,
                    quality = Qualities.Unknown.value, type = ExtractorLinkType.VIDEO, headers = s.headers)
            )
        }
    }
}

class FilePressExtractor : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filebee.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        for (s in TmfSources.resolveFilePress(url)) {
            callback.invoke(
                ExtractorLink(source = name, name = s.name, url = s.url, referer = mainUrl,
                    quality = Qualities.Unknown.value, type = s.type, headers = s.headers)
            )
        }
    }
}
