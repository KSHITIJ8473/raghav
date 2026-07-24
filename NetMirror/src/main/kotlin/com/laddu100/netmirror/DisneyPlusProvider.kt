package com.laddu100.netmirror

/**
 * Disney section of netmirror.
 *
 * Browse uses ott=dp + studio=disney cookies, but the URL prefix is "/hs"
 * (same as Hotstar — Disney content is served from the Hotstar endpoint,
 * filtered by the studio cookie).
 *
 * loadLinks uses ott=hs (NOT dp) for the playlist.php request — the server
 * expects the Hotstar ott value for playlist access.
 */
class DisneyPlusProvider : NetMirrorBaseProvider(
    browseOtt = "dp",
    loadLinksOtt = "hs",
    urlPrefix = "/hs",
    displayName = "Disney",
    studio = "disney"
)
