package com.laddu100.netmirror

/** Netflix section of netmirror. ott = "nf", no URL prefix. */
class NetflixMirrorProvider : NetMirrorBaseProvider(
    browseOtt = "nf",
    loadLinksOtt = "nf",
    urlPrefix = "",
    displayName = "Netflix"
)
