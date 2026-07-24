package com.laddu100.netmirror

/** Prime Video section of netmirror. ott = "pv", URL prefix = "/pv". */
class PrimeVideoMirrorProvider : NetMirrorBaseProvider(
    browseOtt = "pv",
    loadLinksOtt = "pv",
    urlPrefix = "/pv",
    displayName = "PrimeVideo"
)
