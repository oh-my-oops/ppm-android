package com.hienlh.ppm.design

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The web's two typefaces: Geist for the interface, Monaspace Argon for code and metadata. Both
 * ship inside the app (`res/font`, OFL). A weight the family lacks is matched as CSS matches it.
 * Geist carries one glyph its release lacks, an empty U+200B (`scripts/add-zero-width-space.py`,
 * `scripts/geist-web-subsets.py`), so the line breaks the app writes into a URL stay in Geist's
 * run and keep its kerning.
 */
object PpmFonts {
    /** From Android 10 the web's own files, [GeistWebFont]; before it Geist's static release, which sets Vietnamese a little narrower. */
    val sans = if (Build.VERSION.SDK_INT >= 29) {
        FontFamily((100..900 step 100).flatMap { listOf(GeistWebFont(FontWeight(it), FontStyle.Normal), GeistWebFont(FontWeight(it), FontStyle.Italic)) })
    } else {
        FontFamily(
            Font(R.font.geist_regular, FontWeight.Normal),
            Font(R.font.geist_medium, FontWeight.Medium),
            Font(R.font.geist_semibold, FontWeight.SemiBold),
            Font(R.font.geist_bold, FontWeight.Bold),
            Font(R.font.geist_extrabold, FontWeight.ExtraBold),
            Font(R.font.geist_italic, FontWeight.Normal, FontStyle.Italic),
        )
    }

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

/**
 * Geist as the web loads it (`@fontsource-variable/geist`): one variable font per unicode-range
 * subset, chained in the order Chrome tries them — the `wght.css` and `wght-italic.css` the web
 * imports declare cyrillic-ext, cyrillic, vietnamese, latin-ext, latin, and the last one declared
 * is tried first. Each character so takes
 * the file the browser takes it from, and a run is shaped within one file as there: a Vietnamese
 * word is kerned piece by piece, which makes it wider than one complete font sets it ("một" by
 * 0.6 px at 14 px) and wraps it where the web wraps it.
 */
@RequiresApi(29)
private class GeistWebFont(override val weight: FontWeight, override val style: FontStyle) :
    AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface = (font as GeistWebFont).typeface(context)

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)
    }

    fun typeface(context: Context): Typeface {
        val slant = if (style == FontStyle.Italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT
        val families = (if (style == FontStyle.Italic) ITALIC else UPRIGHT).map { file ->
            val font = android.graphics.fonts.Font.Builder(context.resources, file)
                .setWeight(weight.weight)
                .setSlant(slant)
                .setFontVariationSettings("'wght' ${weight.weight}")
                .build()
            android.graphics.fonts.FontFamily.Builder(font).build()
        }
        return Typeface.CustomFallbackBuilder(families.first())
            .apply { families.drop(1).forEach { addCustomFallback(it) } }
            .setStyle(android.graphics.fonts.FontStyle(weight.weight, slant))
            .build()
    }

    override fun equals(other: Any?) = other is GeistWebFont && other.weight == weight && other.style == style

    override fun hashCode() = weight.hashCode() * 31 + style.hashCode()

    private companion object {
        val UPRIGHT = listOf(R.font.geist_web_latin, R.font.geist_web_latin_ext, R.font.geist_web_vietnamese, R.font.geist_web_cyrillic, R.font.geist_web_cyrillic_ext)
        val ITALIC = listOf(
            R.font.geist_web_latin_italic,
            R.font.geist_web_latin_ext_italic,
            R.font.geist_web_vietnamese_italic,
            R.font.geist_web_cyrillic_italic,
            R.font.geist_web_cyrillic_ext_italic,
        )
    }
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
