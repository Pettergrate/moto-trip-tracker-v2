package com.mototriptracker.app.feature.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Text between `**` pairs is drawn in [bold]; the marks themselves are not shown. Lets a string resource carry the
 * phrases that must stand out - the prominent disclosure of background location has to say "location" and "even when
 * the app is closed or not in use" visibly (`privacy-permissions.md` §7.3) - without splitting the sentence into pieces
 * that could not be translated as one.
 */
fun boldMarked(text: String, bold: SpanStyle): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(bold) { append(part) } else append(part)
    }
}
