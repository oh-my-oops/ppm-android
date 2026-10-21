package com.hienlh.ppm.design

import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import androidx.annotation.RequiresApi
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One `<font>` of a system family: its file under `/system/fonts`, its style and its axes as `'wght' 400`. */
internal data class SystemFont(val file: String, val weight: Int, val italic: Boolean, val index: Int, val axes: String)

/** A `<family name=…>` of the system's fonts.xml. */
internal data class SystemFontFamily(val name: String, val fonts: List<SystemFont>)

/**
 * fonts.xml's named families, in the order the file lists them. The unnamed ones, which make up
 * Android's fallback list, are left out: they come after these in Chrome's search as well, and
 * the system appends them itself.
 */
internal fun namedSystemFamilies(xml: InputStream): List<SystemFontFamily> {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).documentElement
    return root.elements("family").mapNotNull { family ->
        val name = family.getAttribute("name").ifEmpty { return@mapNotNull null }
        val fonts = family.elements("font").mapNotNull { font ->
            val file = font.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
                .filter { it.nodeType == org.w3c.dom.Node.TEXT_NODE }
                .joinToString("") { it.nodeValue }
                .trim()
                .ifEmpty { return@mapNotNull null }
            SystemFont(
                file = file,
                weight = font.getAttribute("weight").toIntOrNull() ?: 400,
                italic = font.getAttribute("style") == "italic",
                index = font.getAttribute("index").toIntOrNull() ?: 0,
                axes = font.elements("axis").joinToString(", ") { "'${it.getAttribute("tag")}' ${it.getAttribute("stylevalue")}" },
            )
        }
        if (fonts.isEmpty()) null else SystemFontFamily(name, fonts)
    }
}

/** The order Chrome tries [families] in once a stack's own fonts lack a character: [generic], the stack's last word, then the rest as listed. */
internal fun <T> chromeFallbackOrder(families: List<Pair<String, T>>, generic: String): List<T> =
    families.filter { it.first == generic }.map { it.second } + families.filter { it.first != generic }.map { it.second }

private fun Element.elements(tag: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
}

/**
 * The system fonts Chrome on Android draws a character from when the page's own fonts have none:
 * the stack's generic family, then every named family of fonts.xml in its order (serif,
 * source-sans-pro, cursive…), and only then the fallback list, which is all Android's own
 * fallback searches. Measured with `CSS.getPlatformFontsForNode`: ✓ ▶ ☐ come from Source Sans
 * Pro there, arrows, box drawing and blocks from Noto Serif, ∅ from Dancing Script — the app took
 * every one of them from Noto Sans Symbols, so a ✓ was drawn twice as heavy and a tree of `├──`
 * in other strokes. Empty when the file cannot be read, which leaves Android's fallback alone.
 *
 * One difference is left. Chrome shapes all of a run's missing characters with the font it found
 * for the first of them, and searches again only for those that font lacks: in "✓ a → b" the
 * arrow is Source Sans Pro's, alone it is Noto Serif's. Android picks each character on its own,
 * so the app draws Noto Serif's there. Matching it would take a font span on every such
 * character, worked out per run in every text the app builds.
 *
 * Reading and opening the families takes ~30 ms on the emulator, so [PpmFonts.warm] does it
 * before the first text asks.
 */
@RequiresApi(29)
internal object ChromeSystemFallback {
    private val named: List<Pair<String, FontFamily>> by lazy {
        val families = runCatching { File("/system/etc/fonts.xml").inputStream().use(::namedSystemFamilies) }.getOrDefault(emptyList())
        families.mapNotNull { family -> family.build()?.let { family.name to it } }
    }

    /** The families to add after a stack's own, for one whose generic family is [generic]. */
    fun after(generic: String): List<FontFamily> = chromeFallbackOrder(named, generic)

    private fun SystemFontFamily.build(): FontFamily? {
        val built = fonts.mapNotNull { font ->
            runCatching {
                Font.Builder(File("/system/fonts", font.file))
                    .setWeight(font.weight)
                    .setSlant(if (font.italic) FontStyle.FONT_SLANT_ITALIC else FontStyle.FONT_SLANT_UPRIGHT)
                    .setTtcIndex(font.index)
                    .apply { if (font.axes.isNotEmpty()) setFontVariationSettings(font.axes) }
                    .build()
            }.getOrNull()
        }
        if (built.isEmpty()) return null
        // A family holds one font per style: a second of the same weight and slant is refused.
        val builder = FontFamily.Builder(built.first())
        for (font in built.drop(1)) runCatching { builder.addFont(font) }
        return runCatching { builder.build() }.getOrNull()
    }
}
