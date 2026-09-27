package com.mototriptracker.app.feature.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** MapLibre takes colours as `#RRGGBB` strings; this is a theme colour in that form (alpha dropped - the marker is opaque). */
internal fun Color.toMapHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())
