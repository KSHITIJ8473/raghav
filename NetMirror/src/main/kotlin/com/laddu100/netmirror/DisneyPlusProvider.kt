package com.laddu100.netmirror

/**
 * Disney section of netmirror. ott = "dp", with an extra `studio=disney`
 * cookie so the backend serves Disney-only content.
 *
 * (The upstream mobile plugin also exposes Marvel / Star Wars / Pixar via the
 * same mechanism — those studio sub-plugins have intentionally been removed
 * here. Only Disney remains, as a single, top-level provider.)
 */
class DisneyPlusProvider : NetMirrorBaseProvider(
    ott = "dp",
    displayName = "Disney",
    studio = "disney"
)
