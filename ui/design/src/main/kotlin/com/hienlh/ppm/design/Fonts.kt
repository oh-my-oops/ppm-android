package com.hienlh.ppm.design

import android.content.Context
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
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
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The web's two typefaces: Geist for the interface, Monaspace Argon for code and metadata. Both
 * ship inside the app (`res/font`, OFL). A weight the family lacks is matched as CSS matches it.
 * Each carries one glyph its release lacks, an empty U+200B (`scripts/add-zero-width-space.py`,
 * `scripts/geist-web-subsets.py`), so the line breaks the app writes into a URL or a command stay
 * in the font's run: Geist keeps its kerning, Monaspace its texture healing.
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

    /** Reads the system's fonts for [ChromeSystemFallback] now, off the main thread, so the first text does not wait for them. */
    fun warm() {
        if (Build.VERSION.SDK_INT >= 29) thread(name = "ppm-fonts") { ChromeSystemFallback.after("sans-serif") }
    }

    /** Geist's ascender, 1005 of its 1000 units: what Chrome sizes a list's disc from. */
    const val SANS_ASCENT = 1.005f

    /**
     * The ascender Android reads from the app's Geist files, 970 units (`scripts/geist-android-ascent.py`):
     * low enough that Compose's line-height span can put every baseline where Chrome's metrics do.
     */
    const val SANS_ANDROID_ASCENT = 0.97f

    /** Geist's descender, 295 of its 1000 units. */
    const val SANS_DESCENT = 0.295f

    /** Geist's x-height, 530 of its 1000 units: where `vertical-align: middle` puts an icon. */
    const val SANS_X_HEIGHT = 0.53f

    /**
     * The very files the web loads (`@fontsource/monaspace-argon`: release 1.101, Latin only, at
     * 400 and 700). 1.400's vertical metrics set 11 px text nearly 2 px lower in its line than the
     * web's. A character outside Latin falls back, as it does in the browser. Its licence reserves
     * the name, so the copy with the U+200B is named PPM Mono.
     */
    val mono = if (Build.VERSION.SDK_INT >= 29) {
        FontFamily(
            MonoWebFont(R.font.monaspace_argon_regular, FontWeight.Normal, FontStyle.Normal),
            MonoWebFont(R.font.monaspace_argon_bold, FontWeight.Bold, FontStyle.Normal),
            MonoWebFont(R.font.monaspace_argon_italic, FontWeight.Normal, FontStyle.Italic),
        )
    } else {
        FontFamily(
            Font(R.font.monaspace_argon_regular, FontWeight.Normal),
            Font(R.font.monaspace_argon_bold, FontWeight.Bold),
            Font(R.font.monaspace_argon_italic, FontWeight.Normal, FontStyle.Italic),
        )
    }

    /** Monaspace's typo ascender and descender, 1565 and 435 of its 2000 units, which Chrome takes (`USE_TYPO_METRICS`). */
    const val MONO_ASCENT = 0.7825f
    const val MONO_DESCENT = 0.2175f

    /**
     * The system's sans-serif ascent and descent, as fractions of its size: what `system-ui` is set
     * in. Android and Chrome both take them from the system's own file — Roboto's 1900 and 500 of
     * 2048 units on the emulators — so they are read from it rather than written down.
     */
    val systemAscentDescent: Pair<Float, Float> by lazy {
        val metrics = Paint().apply { typeface = Typeface.SANS_SERIF; textSize = 1000f }.fontMetrics
        -metrics.ascent / 1000f to metrics.descent / 1000f
    }

    /** The ascent and descent Chrome sets a line of [kind] on, as fractions of its size. */
    fun chromeMetrics(kind: FontKind): Pair<Float, Float> = when (kind) {
        FontKind.SANS -> SANS_ASCENT to SANS_DESCENT
        FontKind.MONO -> MONO_ASCENT to MONO_DESCENT
        FontKind.SYSTEM -> systemAscentDescent
    }

    /** Monaspace's one advance, 1240 of its 2000 units. */
    const val MONO_ADVANCE = 0.62f

    /** Monaspace Argon's ligatures and texture-healing stylistic sets (`src/web/lib/editor-font.ts`). */
    const val EDITOR_FONT_LIGATURES =
        "\"calt\", \"liga\", \"ss01\", \"ss02\", \"ss03\", \"ss04\", \"ss05\", \"ss06\", \"ss07\", \"ss08\", \"ss09\""

    /**
     * What an inline code's edges are set in (`scripts/code-edge-font.py`): an empty U+E000 one em
     * wide, with no ascent or descent, so it takes room on the line and leaves the line's height be.
     */
    val codeEdge = FontFamily(Font(R.font.code_edge))

    /**
     * A font's ascent and descent at [px] as Chrome rounds them for a box (`AscentDescentWithHacks`):
     * each to a whole pixel, and a descent rounded down takes one from the ascent.
     */
    fun chromeAscentDescent(px: Float, ascent: Float, descent: Float): Pair<Float, Float> {
        val up = Math.round(px * ascent).toFloat()
        val down = Math.round(px * descent).toFloat()
        return if (down < px * descent && up >= 1) up - 1 to down + 1 else up to down
    }

    /**
     * How far a line [line] px tall, in a font of [px] px, reaches under its baseline in Chrome: the
     * descent as [chromeAscentDescent] rounds it and the half-leading below, which keeps the pixel
     * the floored half above gives up. An inline-block sitting on that line has it under its box.
     */
    fun chromeBelowBaseline(px: Float, line: Float, ascent: Float, descent: Float): Float {
        val (up, down) = chromeAscentDescent(px, ascent, descent)
        val leading = line - up - down
        return down + leading - floor(leading / 2)
    }

    /**
     * How tall Chrome makes a line [line] px tall holding text in several fonts of [px] px, each
     * given as its ascent and descent (LayoutNG `NGLineHeightMetrics`): each font's box is [line]
     * tall around the shared baseline, its half-leading above floored, and the line takes them all.
     * Monaspace numbers in a line of Geist reach 2 px further down than the Geist around them at
     * 14 px on 21 at 2.625x, so that line is 57.125 px where a line of Geist alone is 55.125.
     */
    fun chromeLineBox(px: Float, line: Float, fonts: List<Pair<Float, Float>>): Float {
        var above = 0f
        var below = 0f
        for ((ascent, descent) in fonts) {
            val (up, down) = chromeAscentDescent(px, ascent, descent)
            val top = up + floor((line - up - down) / 2)
            above = max(above, top)
            below = max(below, line - top)
        }
        return above + below
    }

    /**
     * The `topRatio` that puts the baseline of a line [line] px tall, in a font of [px] px, where
     * Chrome puts it (LayoutNG `CalculateLeadingSpace`): the half-leading above floored to a whole
     * pixel, over [chromeAscentDescent]. Compose centres the text on the metrics Android rounds,
     * each to the nearest pixel, and set most sizes 1–2 px lower than the web (14 px in 20 at 2x:
     * 30, Chrome 29). Its span puts ⌈extra × (1 − ratio)⌉ of the room under the text, and cannot
     * lift it above the ascent Android reads from the file, [androidAscent] (Geist's 1005 left 12 px
     * in 16 one short). Null for a line no taller than the font, which the span leaves alone.
     */
    fun chromeTopRatio(px: Float, line: Float, ascent: Float, descent: Float, androidAscent: Float = ascent): Float? {
        val androidDescent = Math.round(px * descent)
        val height = ceil(line).toInt()
        val extra = height - Math.round(px * androidAscent) - androidDescent
        if (extra <= 0) return null
        val (up, down) = chromeAscentDescent(px, ascent, descent)
        val below = height - androidDescent - (floor((line - up - down) / 2) + up).toInt()
        return when {
            below <= 0 -> 1f
            below >= extra -> 0f
            else -> 1f - (below - 0.5f) / extra
        }
    }
}

/**
 * Geist as the web loads it (`@fontsource-variable/geist`): one variable font per unicode-range
 * subset, chained in the order Chrome tries them — the `wght.css` and `wght-italic.css` the web
 * imports declare cyrillic-ext, cyrillic, vietnamese, latin-ext, latin, and the last one declared
 * is tried first. Each character so takes
 * the file the browser takes it from, and a run is shaped within one file as there: a Vietnamese
 * word is kerned piece by piece, which makes it wider than one complete font sets it ("một" by
 * 0.6 px at 14 px) and wraps it where the web wraps it. A character none of them has comes from
 * the system font Chrome takes it from ([ChromeSystemFallback]).
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
        return withChromeFallback(families, "sans-serif", weight, slant)
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

/** Monaspace as [PpmFonts.mono] ships it, falling back where Chrome falls back from the web's code font ([ChromeSystemFallback]). */
@RequiresApi(29)
private class MonoWebFont(val file: Int, override val weight: FontWeight, override val style: FontStyle) :
    AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {

    private object Loader : TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface = (font as MonoWebFont).typeface(context)

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)
    }

    fun typeface(context: Context): Typeface {
        val slant = if (style == FontStyle.Italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT
        val font = android.graphics.fonts.Font.Builder(context.resources, file).setWeight(weight.weight).setSlant(slant).build()
        return withChromeFallback(listOf(android.graphics.fonts.FontFamily.Builder(font).build()), "monospace", weight, slant)
    }

    override fun equals(other: Any?) = other is MonoWebFont && other.file == file && other.weight == weight && other.style == style

    override fun hashCode() = (file * 31 + weight.hashCode()) * 31 + style.hashCode()
}

/** [families] in turn, then the system fonts Chrome tries for a stack ending in [generic], then Android's fallback list. */
@RequiresApi(29)
private fun withChromeFallback(families: List<android.graphics.fonts.FontFamily>, generic: String, weight: FontWeight, slant: Int): Typeface {
    val room = Typeface.CustomFallbackBuilder.getMaxCustomFallbackCount() - families.size
    return Typeface.CustomFallbackBuilder(families.first())
        .apply { (families.drop(1) + ChromeSystemFallback.after(generic).take(room)).forEach { addCustomFallback(it) } }
        .setStyle(android.graphics.fonts.FontStyle(weight.weight, slant))
        .build()
}

/** Geist, Monaspace, or the system's sans-serif that `system-ui` names (sonner's toaster, window titlebars). */
enum class FontKind { SANS, MONO, SYSTEM }

/**
 * Text set as a CSS rule would: [kind] at [size] CSS pixels (sp, so the system font size still
 * scales it), and with [lineHeight] each line box is that tall, the text on the baseline Chrome
 * gives it there ([PpmFonts.chromeTopRatio]). Glyphs keep their unhinted advances and fractional positions, as Chrome lays
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
    fontFamily = when (kind) {
        FontKind.SANS -> PpmFonts.sans
        FontKind.MONO -> PpmFonts.mono
        FontKind.SYSTEM -> FontFamily.SansSerif
    },
    fontSize = size.sp,
    fontWeight = weight,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = lineHeight?.sp ?: TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(lineHeight?.let { chromeAlignment(kind, size, it) } ?: LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    textMotion = TextMotion.Animated,
)

private fun chromeAlignment(kind: FontKind, size: Float, lineHeight: Float): LineHeightStyle.Alignment {
    val metrics = systemMetrics ?: return LineHeightStyle.Alignment.Center
    val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, metrics)
    val line = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lineHeight, metrics)
    val (ascent, descent) = PpmFonts.chromeMetrics(kind)
    // Only the app's own Geist files differ from the web's, in their ascender.
    val ratio = PpmFonts.chromeTopRatio(px, line, ascent, descent, if (kind == FontKind.SANS) PpmFonts.SANS_ANDROID_ASCENT else ascent)
    return ratio?.let(LineHeightStyle::Alignment) ?: LineHeightStyle.Alignment.Center
}

/** The display's, where a style is made without a context at hand; none under the JVM tests. */
private val systemMetrics: DisplayMetrics? get() = runCatching { Resources.getSystem().displayMetrics }.getOrNull()
