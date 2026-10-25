package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * HtmlBoxes against Chrome laying out the web's markdown, from `android/scripts/gen-markdown-dom-cases.tsx`:
 * every element's style, and where the blocks stack once their margins collapse.
 */
class MarkdownDomTest {
    private class Element(val path: String, val tag: String, val box: List<Double>, val wrap: List<Double>?, val style: Map<String, JsonElement>) {
        fun text(key: String) = style[key].string.orEmpty()
        fun number(key: String) = style[key].double
        val rendered get() = box[2] > 0 || box[3] > 0
    }

    private class Case(val index: Int, val md: String, val rawHtml: Boolean, val height: Double, val elements: List<Element>) {
        val byPath = elements.associateBy { it.path }

        /** [element] and the elements around it, innermost first. */
        fun chain(element: Element): List<Element> = generateSequence(element.path) { path -> path.substringBeforeLast('/', "").ifEmpty { null } }
            .mapNotNull(byPath::get).toList()

        fun insidePre(element: Element) = chain(element).drop(1).any { it.tag == "pre" }

        /** Inside a closed `details`, which Chrome lays out below its box and never shows. */
        fun hidden(element: Element) = chain(element).drop(1).any { it.tag == "details" && it.box[1] + it.box[3] <= element.box[1] + 0.01 }

        override fun toString() = "#$index ${quote(md)}${if (rawHtml) "" else " (raw HTML off)"}"
    }

    private val cases: List<Case> by lazy {
        val json = WireJson.parse(javaClass.getResource("/markdown-dom-cases.json")!!.readText())
        json["cases"].array!!.mapIndexed { index, case ->
            Case(
                index,
                case["md"].string!!,
                case["rawHtml"].bool == true,
                case["height"].double!!,
                case["elements"].array!!.map { e ->
                    Element(
                        e["path"].string!!,
                        e["tag"].string!!,
                        e["box"].array!!.map { it.double!! },
                        e["wrap"].array?.map { it.double!! },
                        e["style"].obj!!,
                    )
                },
            )
        }
    }

    private fun build(case: Case, trace: MutableMap<String, HtmlStyle>? = null): FlowBox? {
        val text = markdownContent(case.md)
        if (text.isEmpty()) return null
        return HtmlBoxes.build(MarkdownHtml.render(text, case.rawHtml), MarkdownStyle.chat, trace = trace)
    }

    @Test
    fun `every element is set in the style Chrome computes for it`() {
        val failures = ArrayList<String>()
        for (case in cases) {
            val trace = HashMap<String, HtmlStyle>()
            val root = runCatching { build(case, trace) }.getOrElse {
                failures += "$case threw $it"
                null
            } ?: continue
            for (element in case.elements) {
                if (case.insidePre(element)) continue
                val style = trace[element.path]
                if (style == null) {
                    if (element.rendered && !case.hidden(element)) failures += "$case ${element.path} <${element.tag}>: not built"
                    continue
                }
                val differences = differences(case, element, style)
                if (differences.isNotEmpty()) failures += "$case ${element.path} <${element.tag}>: ${differences.joinToString()}"
            }
            check(root.tag == null)
        }
        assertEquals("${failures.size} differ:\n" + failures.take(60).joinToString("\n"), 0, failures.size)
    }

    private fun differences(case: Case, element: Element, style: HtmlStyle): List<String> {
        val out = ArrayList<String>()
        fun expect(name: String, app: Any?, web: Any?) {
            if (app != web) out += "$name app $app, web $web"
        }
        val size = element.number("fontSize")!!
        if (abs(style.size - size) > 0.01) expect("size", style.size, size)
        val lineHeight = element.number("lineHeight")
        if (lineHeight != null && lineHeight != 0.0 && abs(style.size * RATIO - lineHeight) > 0.01) expect("line height", style.size * RATIO, lineHeight)
        expect("weight", style.weight, element.text("fontWeight").toInt())
        expect("italic", style.italic, element.text("fontStyle") == "italic")
        expect("font", style.font, FONTS[element.text("fontFamily")] ?: element.text("fontFamily"))
        expect("color", style.color, COLORS[element.text("color")] ?: element.text("color"))
        expect("align", style.align, ALIGNS[element.text("textAlign")] ?: element.text("textAlign"))
        expect("white space", style.whiteSpace, SPACES[element.text("whiteSpace")] ?: element.text("whiteSpace"))
        if (element.text("display") == "list-item") expect("list style", style.listStyle, LISTS[element.text("listStyle")] ?: element.text("listStyle"))
        // Decorations reach the text of everything inside; MdCode's dotted file underline is drawn from the code's own range.
        val decorations = case.chain(element).filter { it.tag != "code" }.map { it.text("decoration") }
        expect("underline", style.underline, decorations.any { it == "underline solid" })
        expect("dotted", style.dotted, decorations.any { it == "underline dotted" })
        expect("line-through", style.lineThrough, decorations.any { it.startsWith("line-through") })
        val shift = case.chain(element).filter { it.text("position") == "relative" }.sumOf { e ->
            px(e.text("top")) ?: -(px(e.text("bottom")) ?: 0.0)
        }
        if (abs(style.shift - shift) > 0.01) expect("shift", style.shift, shift)
        return out
    }

    @Test
    fun `blocks stack where Chrome puts them`() {
        val failures = ArrayList<String>()
        var compared = 0
        for (case in cases) {
            // Lines whose height Chrome takes from more than the strut: mono text, checkboxes, ruby, and
            // italic in a table's 12px, whose face rounds its ascent half a pixel past the regular one's.
            val skip = case.elements.any { e ->
                !case.insidePre(e) && e.tag != "pre" && (
                    e.text("fontFamily") != "Geist Variable" || e.tag in setOf("input", "ruby") ||
                        (e.text("fontStyle") == "italic" && case.chain(e).any { it.tag == "td" || it.tag == "th" })
                    )
            }
            if (skip) continue
            val root = runCatching { build(case) }.getOrElse {
                failures += "$case threw $it"
                null
            }
            if (root == null) {
                if (case.height != 0.0) failures += "$case: nothing built, web ${case.height}"
                continue
            }
            compared++
            val layout = Layout(case)
            val height = layout.measure(root) - root.insetTop - root.insetBottom
            val problems = ArrayList<String>()
            if (abs(height - case.height) > TOLERANCE) problems += "height app ${r3(height)}, web ${case.height}"
            layout.place(root, 0.0, -root.insetTop.toDouble())
            for ((path, placed) in layout.placed) {
                val element = case.byPath[path]
                if (element == null) {
                    // A details with no summary shows Chrome's own, which is no element.
                    if (!path.endsWith("/summary")) problems += "$path: no such element"
                    continue
                }
                val box = if (element.tag == "table") element.wrap!! else element.box
                val where = "$path <${element.tag}>"
                // Where a box with nothing in it sits shows nowhere.
                val empty = placed.height == 0.0 && box[3] == 0.0
                if (!empty && abs(placed.y - box[1]) > TOLERANCE) problems += "$where top app ${r3(placed.y)}, web ${box[1]}"
                if (abs(placed.height - box[3]) > TOLERANCE) problems += "$where height app ${r3(placed.height)}, web ${box[3]}"
                if (placed.x != null && abs(placed.x - box[0]) > TOLERANCE) problems += "$where left app ${r3(placed.x)}, web ${box[0]}"
            }
            for (element in case.elements) {
                val display = element.text("display")
                if ((display == "block" || display == "list-item" || display == "table") && element.rendered && !case.insidePre(element) &&
                    !case.hidden(element) && element.path !in layout.placed
                ) {
                    problems += "${element.path} <${element.tag}>: not laid out as a block"
                }
            }
            if (problems.isNotEmpty()) failures += "$case\n    " + problems.joinToString("\n    ")
        }
        assertEquals("${failures.size} of $compared differ:\n" + failures.take(40).joinToString("\n"), 0, failures.size)
    }

    private class Placed(val x: Double?, val y: Double, val height: Double)

    /** Block layout over the box tree, with what only drawing can tell taken from Chrome's boxes. */
    private class Layout(private val case: Case) {
        val placed = LinkedHashMap<String, Placed>()

        fun measure(box: HtmlBox): Double = when (box) {
            is FlowBox -> flow(box)
            is LinesBox -> (box.text.text.count { it == '\n' } + 1) * lineHeight(box.style)
            is CodeBox -> case.byPath[box.path]?.box?.get(3) ?: 0.0
            is ImageBox -> case.byPath[box.path]?.box?.get(3) ?: 0.0
            is RuleBox -> 1.0
            // MdTable's scroller keeps the table's margins inside it; collapsed borders add one.
            is TableBox -> box.rows.sumOf(::rowHeight) + 1 + 16
        }

        private fun flow(box: FlowBox): Double {
            val content = box.insetTop + box.children.sumOf { it.gap + measure(it) } + box.insetBottom
            // An item's marker makes a line though nothing else does.
            return if (box.marker != null && box.children.none { measure(it) > 0 }) max(content, lineHeight(box.style)) else content
        }

        private fun rowHeight(row: List<TableCell>) = (row.maxOfOrNull { flow(it.box) } ?: 0.0) + 9

        fun place(box: HtmlBox, x: Double?, y: Double) {
            when (box) {
                is FlowBox -> {
                    if (box.tag != null) placed[box.path] = Placed(x, y, measure(box))
                    children(box, x?.plus(box.borderStart + box.paddingStart), y + box.insetTop)
                }
                is CodeBox -> placed[box.path] = Placed(x, y, measure(box))
                is ImageBox -> placed[box.path] = Placed(x.takeIf { box.align == HtmlAlign.START }, y, measure(box))
                is RuleBox -> placed[box.path] = Placed(x, y, 1.0)
                is TableBox -> {
                    placed[box.path] = Placed(x, y, measure(box))
                    var top = y + 8 + 0.5
                    for (row in box.rows) {
                        val height = rowHeight(row)
                        for (cell in row) {
                            placed[cell.box.path] = Placed(null, top, height)
                            val free = height - 9 - flow(cell.box)
                            val offset = when (cell.valign) {
                                CellAlign.TOP -> 0.0
                                CellAlign.MIDDLE -> free / 2
                                CellAlign.BOTTOM -> free
                            }
                            children(cell.box, null, top + 0.5 + 4 + offset + cell.box.insetTop)
                        }
                        top += height
                    }
                }
                is LinesBox -> Unit
            }
        }

        private fun children(box: FlowBox, x: Double?, top: Double) {
            var y = top
            for (child in box.children) {
                y += child.gap
                place(child, x, y)
                y += measure(child)
            }
        }
    }

    private companion object {
        const val RATIO = 20.0 / 14.0
        const val TOLERANCE = 0.05

        /** A line's height as Chrome lays it out, in 1/64 px. */
        fun lineHeight(style: HtmlStyle) = (style.size * RATIO * 64).roundToLong() / 64.0

        fun px(value: String) = if (value.endsWith("px")) value.removeSuffix("px").toDouble() else null

        fun r3(value: Double) = (value * 1000).roundToLong() / 1000.0

        val FONTS = mapOf("Geist Variable" to HtmlFont.SANS, "Monaspace Argon" to HtmlFont.MONO, "monospace" to HtmlFont.SYSTEM_MONO)
        val COLORS = mapOf(
            "rgb(15, 26, 51)" to HtmlColor.TEXT,
            "rgb(74, 88, 120)" to HtmlColor.SECONDARY,
            "rgb(47, 107, 255)" to HtmlColor.LINK,
            "rgb(0, 0, 0)" to HtmlColor.MARK,
        )
        val ALIGNS = mapOf(
            "start" to HtmlAlign.START, "left" to HtmlAlign.LEFT, "right" to HtmlAlign.RIGHT, "center" to HtmlAlign.CENTER,
            "justify" to HtmlAlign.JUSTIFY, "-webkit-left" to HtmlAlign.WEBKIT_LEFT, "-webkit-right" to HtmlAlign.WEBKIT_RIGHT,
            "-webkit-center" to HtmlAlign.WEBKIT_CENTER,
        )
        val SPACES = mapOf("normal" to WhiteSpace.NORMAL, "nowrap" to WhiteSpace.NOWRAP, "pre-wrap" to WhiteSpace.PRE_WRAP, "pre" to WhiteSpace.PRE)
        val LISTS = mapOf(
            "disc outside" to ListStyle.DISC,
            "decimal outside" to ListStyle.DECIMAL,
            "disclosure-open inside" to ListStyle.DISCLOSURE_OPEN,
            "disclosure-closed inside" to ListStyle.DISCLOSURE_CLOSED,
        )
    }
}
