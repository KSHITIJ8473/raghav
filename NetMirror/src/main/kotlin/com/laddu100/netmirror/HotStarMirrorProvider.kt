package com.laddu100.netmirror

/** Hotstar section of netmirror. ott = "hs", URL prefix = "/hs". */
class HotStarMirrorProvider : NetMirrorBaseProvider(
    browseOtt = "hs",
    loadLinksOtt = "hs",
    urlPrefix = "/hs",
    displayName = "Hotstar"
)
