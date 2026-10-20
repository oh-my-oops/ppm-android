package com.hienlh.ppm.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The web's two typefaces: Geist for the interface, Monaspace Argon for code and metadata. Both
 * ship inside the app (`res/font`, OFL). A weight the family lacks is matched as CSS matches it.
 * Geist carries one glyph its release lacks, an empty U+200B (`scripts/add-zero-width-space.py`),
 * so the line breaks the app writes into a URL stay in Geist's run and keep its kerning.
 */
object PpmFonts {
    val sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold),
        Font(R.font.geist_extrabold, FontWeight.ExtraBold),
        Font(R.font.geist_italic, FontWeight.Normal, FontStyle.Italic),
    )

    /** Geist's ascender, 1005 of its 1000 units: what Chrome sizes a list's disc from. */
    const val SANS_ASCENT = 1.005f

    /**
     * The very files the web loads (`@fontsource/monaspace-argon`: release 1.101, Latin only, at
     * 400 and 700). 1.400's vertical metrics set 11 px text nearly 2 px lower in its line than the
     * web's. A character outside Latin falls back, as it does in the browser.
     */
    val mono = FontFamily(
        Font(R.font.monaspace_argon_regular, FontWeight.Normal),
        Font(R.font.monaspace_argon_bold, FontWeight.Bold),
        Font(R.font.monaspace_argon_italic, FontWeight.Normal, FontStyle.Italic),
    )
}

enum class FontKind { SANS, MONO }

/**
 * Text set as a CSS rule would: [kind] at [size] CSS pixels (sp, so the system font size still
 * scales it), and with [lineHeight] each line box is that tall, half the extra leading above
 * and half below. Glyphs keep their unhinted advances and fractional positions, as Chrome lays
 * text out: Android's default snaps every advance to a whole pixel, which at 2.625x made a line
 * 1–2 % narrower than the web's and wrapped it somewhere else.
 */
fun ppmText(
    size: Float,
    weight: FontWeight = FontWeight.Normal,
    kind: FontKind = FontKind.SANS,
    lineHeight: Float? = null,
    italic: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = if (kind == FontKind.SANS) PpmFonts.sans else PpmFonts.mono,
    fontSize = size.sp,
    fontWeight = weight,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = lineHeight?.sp ?: TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    textMotion = TextMotion.Animated,
)
