package com.hienlh.ppm.design

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The design data and rules, without a device (`PPMSelfTest/DesignTests.swift`). */
class DesignTest {
    @Test
    fun `every product icon parses and starts inside its 20x20 box`() {
        assertTrue("only ${ProductIcon.entries.size} icons", ProductIcon.entries.size > 200)
        for (icon in ProductIcon.entries) {
            for (d in icon.pathData) {
                val nodes = addPathNodes(d)
                assertTrue("$icon draws nothing", nodes.size > 1)
                // A first `m` is relative to the origin, so it is absolute too.
                val (x, y) = when (val start = nodes.first()) {
                    is PathNode.MoveTo -> start.x to start.y
                    is PathNode.RelativeMoveTo -> start.dx to start.dy
                    else -> error("$icon does not start with a move: $start")
                }
                assertTrue("$icon starts at $x,$y", x in -0.5f..20.5f && y in -0.5f..20.5f)
            }
        }
    }

    @Test
    fun `every lucide icon parses and starts inside its 24x24 box`() {
        assertEquals(15, LucideIcon.entries.size)
        for (icon in LucideIcon.entries) {
            for (d in icon.pathData) {
                val nodes = addPathNodes(d)
                assertTrue("$icon draws nothing", nodes.size > 1)
                val (x, y) = when (val start = nodes.first()) {
                    is PathNode.MoveTo -> start.x to start.y
                    is PathNode.RelativeMoveTo -> start.dx to start.dy
                    else -> error("$icon does not start with a move: $start")
                }
                assertTrue("$icon starts at $x,$y", x in 0f..24f && y in 0f..24f)
            }
        }
        // Circles and rects are converted to closed paths, one per element.
        assertEquals(5, LucideIcon.Network.pathData.size)
        assertTrue(LucideIcon.Network.pathData.count { addPathNodes(it).last() == PathNode.Close } >= 3)
        assertEquals(6, LucideIcon.ServerOff.pathData.size)
    }

    @Test
    fun `compact SVG numbers and arc flags`() {
        // `.5.5` is two numbers; `016 6` is flags 0 and 1, then x = 6.
        val nodes = addPathNodes("M0 0a6 6 0 016 6l-.5.5")
        val arc = nodes[1] as PathNode.RelativeArcTo
        assertEquals(listOf(6f, 6f, 0f, false, true, 6f, 6f), listOf(arc.horizontalEllipseRadius, arc.verticalEllipseRadius, arc.theta, arc.isMoreThanHalf, arc.isPositiveArc, arc.arcStartDx, arc.arcStartDy))
        assertEquals(PathNode.RelativeLineTo(-0.5f, 0.5f), nodes[2])
        assertEquals(PathNode.LineTo(3f, 4f), addPathNodes("M1e1 2E-1L3e+0 4")[1])
    }

    @Test
    fun `arc flags that run into the next number are set apart before Compose reads them`() {
        // Codex's own arcs: without the flags set apart, Compose reads `00.04` as a number.
        val nodes = addPathNodes(separateArcFlags("M0 0a.167.167 0 00.04.155 5.982 5.982 0 011.578 2.891l1 1"))
        assertEquals(PathNode.RelativeArcTo(0.167f, 0.167f, 0f, false, false, 0.04f, 0.155f), nodes[1])
        assertEquals(PathNode.RelativeArcTo(5.982f, 5.982f, 0f, false, true, 1.578f, 2.891f), nodes[2])
        assertEquals(PathNode.RelativeLineTo(1f, 1f), nodes[3])
        assertEquals("M 1 2e-1a 1 1 0 0 1 -.5 .5", separateArcFlags("M1,2e-1a1 1 0 01-.5.5"))
    }

    @Test
    fun `every provider logo arc is read as the circle its artwork draws`() {
        // A flag read as a number shifts every argument after it: radii, rotation and count go wrong.
        val logos = mapOf("claude" to ProviderLogoPaths.CLAUDE, "codex" to ProviderLogoPaths.CODEX, "cursor" to ProviderLogoPaths.CURSOR, "gemini" to ProviderLogoPaths.GEMINI)
        val counts = logos.mapValues { (id, d) ->
            val arcs = addPathNodes(separateArcFlags(d)).mapNotNull {
                when (it) {
                    is PathNode.RelativeArcTo -> Triple(it.horizontalEllipseRadius, it.verticalEllipseRadius, it.theta)
                    is PathNode.ArcTo -> Triple(it.horizontalEllipseRadius, it.verticalEllipseRadius, it.theta)
                    else -> null
                }
            }
            for ((rx, ry, theta) in arcs) assertTrue("$id: $rx $ry $theta", rx > 0f && rx == ry && theta == 0f)
            arcs.size
        }
        assertEquals(mapOf("claude" to 1, "codex" to 24, "cursor" to 6, "gemini" to 13), counts)
    }

    @Test
    fun `provider logos are there`() {
        for (id in listOf("claude", "codex", "cursor", "gemini")) {
            assertTrue(id, ProviderLogos.has(id))
            assertTrue(id, addPathNodes(
                when (id) {
                    "claude" -> ProviderLogoPaths.CLAUDE
                    "codex" -> ProviderLogoPaths.CODEX
                    "cursor" -> ProviderLogoPaths.CURSOR
                    else -> ProviderLogoPaths.GEMINI
                },
            ).size > 1)
        }
        assertFalse(ProviderLogos.has("mock"))
    }

    @Test
    fun `themes follow the web's selection rules`() {
        assertEquals(
            listOf("aurora-dark", "aurora-light", "slate-dark", "slate-light", "precision-dark", "precision-light"),
            BuiltInThemes.all.map { it.id },
        )
        assertEquals("slate-light", PpmTheme.resolve("slate", "light", systemIsDark = true).id)
        assertEquals("precision-dark", PpmTheme.resolve("precision", "system", systemIsDark = true).id)
        assertEquals("aurora-light", PpmTheme.resolve("aurora", "system", systemIsDark = false).id)
        assertEquals("aurora-dark", PpmTheme.resolve("custom", "dark", systemIsDark = false).id)
        assertEquals("one-dark-pro", BuiltInThemes.precisionDark.shikiTheme)
        assertEquals(Rgba.hex("#5b8cff"), BuiltInThemes.auroraDark.tokens.accent)
        assertEquals(18.0, BuiltInThemes.auroraDark.tokens.rad, 0.0)
        // Only the phone layout gets Aurora's mobile overrides.
        assertEquals(Rgba.hex("#141c2e"), BuiltInThemes.auroraDark.mobileTokens.panel)
        assertEquals(BuiltInThemes.auroraDark.tokens.text, BuiltInThemes.auroraDark.mobileTokens.text)
        assertEquals(BuiltInThemes.slateDark.tokens, BuiltInThemes.slateDark.mobileTokens)
    }

    @Test
    fun `colour arithmetic matches CSS`() {
        val white = Rgba(1.0, 1.0, 1.0, 1.0)
        val black = Rgba(0.0, 0.0, 0.0, 1.0)
        assertEquals(Rgba(0.5, 0.5, 0.5, 1.0), white.mix(black, 0.5))
        assertEquals(0.12, white.opacity(0.12).a, 0.0)
        assertEquals(white, Rgba.hex("#fff"))
        assertEquals(128.0 / 255, Rgba.hex("#00000080")!!.a, 0.0)
        assertEquals(Rgba(0.5, 0.0, 0.0, 1.0), Rgba(1.0, 0.0, 0.0, 0.5).over(black))
    }

    @Test
    fun `relative dates read like the web`() {
        val now = Instant.ofEpochSecond(2_000_000_000)
        assertEquals("just now", PpmFormat.relative(now.minusSeconds(30), now))
        assertEquals("5m ago", PpmFormat.relative(now.minusSeconds(5 * 60), now))
        assertEquals("3h ago", PpmFormat.relative(now.minusSeconds(3 * 3600), now))
        assertEquals("2d ago", PpmFormat.relative(now.minusSeconds(2 * 86400), now))
    }

    @Test
    fun `whitespace collapses as HTML shows text`() {
        assertEquals("<ctx> PPM sharing on", PpmFormat.collapsedWhitespace("  <ctx>\n\nPPM\tsharing \r\n on  "))
        assertEquals("a  b", PpmFormat.collapsedWhitespace("a  b"))
        assertEquals("", PpmFormat.collapsedWhitespace(" \n "))
    }

    @Test
    fun `project initials resolve collisions like the web`() {
        assertEquals("D", PpmFormat.projectInitials("demo", listOf("demo")))
        assertEquals("PA", PpmFormat.projectInitials("ppm-apple", listOf("ppm-apple", "payroll")))
        assertEquals("1", PpmFormat.projectInitials("ppm", listOf("ppm", "payroll")))
        assertEquals("2", PpmFormat.projectInitials("payroll", listOf("ppm", "payroll")))
    }

    @Test
    fun `project colours`() {
        val (a, b) = PpmFormat.projectColors("linear-gradient(135deg, #f5576c, #f093fb)", 0)
        assertEquals(Rgba.hex("#f5576c"), a)
        assertEquals(Rgba.hex("#f093fb"), b)
        assertEquals(Rgba.hex("#f5576c"), PpmFormat.projectColors(null, 13).first)
        assertEquals(Rgba.hex("#123456"), PpmFormat.projectColors("#123456", 0).second)
    }

    @Test
    fun `turn stop text`() {
        assertEquals("Stopped after 40 steps (Max Turns)", PpmFormat.turnStop("Reached maximum number of turns (40)", "error_max_turns").first)
        val other = PpmFormat.turnStop("API error.\nretry later\nplease", null)
        assertEquals("Stopped: API error", other.first)
        assertEquals("retry later please", other.second)
    }

    @Test
    fun `a CSS border is as many device pixels as Chrome draws`() {
        // Measured on the phone (2.625): `getComputedStyle` gives 0.761905px for 1px, 1.90476px for 2px.
        assertEquals(2, Density(2.625f).cssBorderPx(1.dp))
        assertEquals(5, Density(2.625f).cssBorderPx(2.dp))
        assertEquals(2, Density(2f).cssBorderPx(1.dp))
        // Never thinner than a pixel, and none at all for none.
        assertEquals(1, Density(0.75f).cssBorderPx(1.dp))
        assertEquals(0, Density(2.625f).cssBorderPx(0.dp))
    }

    private data class Line(val mono: Boolean, val size: Float, val height: Float, val density: Float, val baseline: Int)

    @Test
    fun `text sits on the baseline Chrome gives it`() {
        // Chrome's, in device px from the top of the line box, measured on the emulators by CDP.
        val chrome = listOf(
            Line(false, 10.5f, 15.75f, 2f, 22),
            Line(false, 11f, 16.5f, 2f, 23),
            Line(false, 11f, 17.875f, 2f, 24),
            Line(false, 12f, 18f, 2f, 25),
            Line(false, 13f, 19.5f, 2f, 28),
            Line(false, 14f, 20f, 2f, 29),
            Line(false, 14f, 21f, 2f, 30),
            Line(false, 16f, 24f, 2f, 34),
            Line(false, 20f, 28f, 2f, 42),
            Line(true, 10f, 15f, 2f, 20),
            Line(true, 11f, 16.5f, 2f, 22),
            Line(true, 12f, 16f, 2f, 22),
            Line(true, 14f, 20f, 2f, 27),
            Line(false, 10.5f, 15.75f, 2.625f, 29),
            Line(false, 11f, 16.5f, 2.625f, 31),
            Line(false, 11f, 17.875f, 2.625f, 33),
            Line(false, 12f, 18f, 2.625f, 34),
            Line(false, 13f, 19.5f, 2.625f, 36),
            Line(false, 14f, 20f, 2.625f, 39),
            Line(false, 14f, 21f, 2.625f, 40),
            Line(false, 16f, 24f, 2.625f, 45),
            Line(false, 20f, 28f, 2.625f, 54),
            Line(false, 11f, 44f / 3, 2.625f, 29),
            Line(false, 9f, 12f, 2.625f, 24),
            Line(true, 10f, 15f, 2.625f, 27),
            Line(true, 11f, 16.5f, 2.625f, 29),
            Line(true, 12f, 16f, 2.625f, 30),
            Line(true, 14f, 20f, 2.625f, 36),
            // A pixel above where Geist's own ascent of 1005 let Compose put them.
            Line(false, 12f, 16f, 2f, 23),
            Line(false, 11f, 44f / 3, 2f, 21),
            Line(false, 9f, 12f, 2f, 17),
            Line(false, 12f, 16f, 2.625f, 31),
        )
        for (line in chrome) {
            val ascent = if (line.mono) PpmFonts.MONO_ASCENT else PpmFonts.SANS_ASCENT
            val descent = if (line.mono) PpmFonts.MONO_DESCENT else PpmFonts.SANS_DESCENT
            val android = if (line.mono) PpmFonts.MONO_ASCENT else PpmFonts.SANS_ANDROID_ASCENT
            val px = line.size * line.density
            val ratio = PpmFonts.chromeTopRatio(px, line.height * line.density, ascent, descent, android)!!
            // Where Compose's LineHeightStyleSpan then puts it, on the metrics Android rounds.
            val height = ceil(line.height * line.density).toInt()
            val below = height - Math.round(px * android) - Math.round(px * descent)
            assertEquals("$line", line.baseline, height - Math.round(px * descent) - ceil(below * (1 - ratio)).toInt())
        }
        // 12 px in 16: Chrome's text is one pixel above where Geist's own ascent let the span lift it.
        assertEquals(0f, PpmFonts.chromeTopRatio(24f, 32f, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT))
        // A line no taller than the font is left to the span.
        assertNull(PpmFonts.chromeTopRatio(24f, 24f, PpmFonts.SANS_ASCENT, PpmFonts.SANS_DESCENT))
    }

    @Test
    fun `a line holding Monaspace in Geist is as tall as Chrome makes it`() {
        val sans = PpmFonts.SANS_ASCENT to PpmFonts.SANS_DESCENT
        val mono = PpmFonts.MONO_ASCENT to PpmFonts.MONO_DESCENT
        // 14 px on 21 at 2.625x, measured by CDP: the revert summary's sentence with `font-mono`
        // counts in its second line is 42.76 px tall, its first line 21.
        assertEquals(55.125f, PpmFonts.chromeLineBox(36.75f, 55.125f, listOf(sans)), 0.001f)
        assertEquals(57.125f, PpmFonts.chromeLineBox(36.75f, 55.125f, listOf(sans, mono)), 0.001f)
        assertEquals(42.76f * 2.625f, PpmFonts.chromeLineBox(36.75f, 55.125f, listOf(sans)) + PpmFonts.chromeLineBox(36.75f, 55.125f, listOf(sans, mono)), 0.02f)
    }

    @Test
    fun `line-clamp keeps the last line whole when the ellipsis fits after it`() {
        // Ten characters 10 px wide from x 0; "…" 15 px.
        val right = { k: Int -> 10f * k }
        val previous = { k: Int -> k - 1 }
        val next = { k: Int -> k + 1 }
        assertEquals(10, LineClamp.keep(0, 10, 120f, 15f, right, previous, next))
        // Cut back only as far as the "…" needs, a character at a time: mid-word, as Chrome does.
        assertEquals(9, LineClamp.keep(0, 10, 105f, 15f, right, previous, next))
        assertEquals(8, LineClamp.keep(0, 10, 104.9f, 15f, right, previous, next))
        // One character stays however narrow; an empty line keeps nothing.
        assertEquals(1, LineClamp.keep(0, 10, 10f, 15f, right, previous, next))
        assertEquals(4, LineClamp.keep(4, 4, 100f, 15f, right, previous, next))
        // A character of two code units goes whole.
        val pairs = { k: Int -> k - 2 }
        assertEquals(6, LineClamp.keep(0, 10, 85f, 15f, right, pairs, { k: Int -> k + 2 }))
    }

    @Test
    fun `a CSS row puts each child on the pixel Chrome snaps it to`() {
        // A user message's action bar on the tablet (2x), measured by CDP: time, Copy, Reply, Edit and
        // Fork 2 CSS px apart, at widths Chrome keeps in 64ths of a device pixel.
        val bar = floatArrayOf(104.703125f, 116.765625f, 121.953125f, 102.109375f, 109.5f)
        val user = cssRowPlan(bar, 4f, 0f, 0f, 1000f, 0, 1000, wrap = true, centre = false, alignEnd = true)
        assertEquals(571.03125f, user.exactWidth)
        // Flush right, the bar starts 0.03 px before the pixel Chrome rounds its edge to: Fork's 461.5 is 462.
        assertEquals(571, user.width)
        assertEquals(listOf(0, 109, 229, 355, 462), user.x.toList())
        // An assistant's starts on a whole pixel, so nothing moves.
        val assistant = cssRowPlan(bar.copyOf(3), 4f, 0f, 0f, 1000f, 0, 1000, wrap = true, centre = false, alignEnd = false)
        assertEquals(351, assistant.width)
        assertEquals(listOf(0, 109, 229), assistant.x.toList())
        // At the whole pixels Compose measures its labels to, everything from Reply on was a pixel right.
        val ceiled = cssRowPlan(FloatArray(5) { ceil(bar[it]) }, 4f, 0f, 0f, 1000f, 0, 1000, wrap = true, centre = false, alignEnd = false)
        assertEquals(listOf(0, 109, 230, 356, 463), ceiled.x.toList())
    }

    @Test
    fun `a wrapping CSS row starts each line at its start and is as wide as it was offered`() {
        val bar = floatArrayOf(104.703125f, 116.765625f, 121.953125f, 102.109375f, 109.5f)
        val plan = cssRowPlan(bar, 4f, 0f, 0f, 300f, 0, 300, wrap = true, centre = false, alignEnd = true)
        assertEquals(3, plan.lines)
        assertEquals(listOf(0, 0, 1, 1, 2), plan.lineOf.toList())
        assertEquals(listOf(0, 109, 0, 126, 0), plan.x.toList())
        assertEquals(300, plan.width)
        // Without wrap it stays one line, however narrow.
        assertEquals(1, cssRowPlan(bar, 4f, 0f, 0f, 300f, 0, 300, wrap = false, centre = false, alignEnd = false).lines)
    }

    @Test
    fun `a CSS row's padding stays exact and a centred child sits in the middle of a wider minimum`() {
        // The Copy button: px-1.5 gap-1 around a 14 px icon and its label, at 2x.
        val copy = cssRowPlan(floatArrayOf(28f, 56.765625f), 8f, 12f, 12f, 976f, 0, 1000, wrap = false, centre = true, alignEnd = false)
        assertEquals(116.765625f, copy.exactWidth)
        assertEquals(117, copy.width)
        assertEquals(listOf(12, 48), copy.x.toList())
        // An icon alone in a 44 px touch target.
        val icon = cssRowPlan(floatArrayOf(28f), 8f, 12f, 12f, 976f, 88, 1000, wrap = false, centre = true, alignEnd = false)
        assertEquals(88, icon.width)
        assertEquals(listOf(30), icon.x.toList())
        assertEquals(listOf(12), cssRowPlan(floatArrayOf(28f), 8f, 12f, 12f, 976f, 88, 1000, wrap = false, centre = false, alignEnd = false).x.toList())
    }

    @Test
    fun `a CSS row stands its children where Chrome does up and down`() {
        // The phone composer's input row (2.625x), measured by CDP: attach and mic 84 px, the field
        // 91.5 (15.75 + 60 + 15.75), send 94.5, `items-end py-2`, the row starting on 1847.5.
        val input = cssRowAcross(IntArray(4), floatArrayOf(84f, 91.5f, 84f, 94.5f), 1, 10.5f, 21f, 21f, 0f, -0.5f, CssAlign.End)
        assertEquals(136.5f, input.exactHeight)
        // From the row's first pixel, 1848: the buttons on 1879, the field on 1871.5, send on 1868.5.
        assertEquals(listOf(31, 24, 31, 21), input.y.toList())
        assertEquals(listOf(0f, -0.5f, 0f, -0.5f), input.phases.toList())
        // 1847.5 to 1984 rounds to 136 pixels, and send's 94.5 from 1868.5 to 94.
        assertEquals(136, input.height)
        assertEquals(94, (input.phases[3] + 94.5f).roundToInt())
        // The chips above it, from 1754.984: the mode chip (56.5) centred on the model chip's line (71.52).
        val chips = cssRowAcross(IntArray(2), floatArrayOf(56.5f, 71.515625f), 1, 10.5f, 21f, 0f, 0f, -0.015625f, CssAlign.Center)
        assertEquals(92.515625f, chips.exactHeight)
        assertEquals(listOf(28, 21), chips.y.toList())
        assertEquals(93, chips.height)
    }

    @Test
    fun `a wrapping CSS row stacks its lines and a minimum height centres one`() {
        val wrapped = cssRowAcross(intArrayOf(0, 0, 1), floatArrayOf(10f, 20f, 15f), 2, 4f, 2f, 3f, 0f, 0.25f, CssAlign.Center)
        assertEquals(44f, wrapped.exactHeight)
        assertEquals(listOf(7, 2, 26), wrapped.y.toList())
        assertEquals(44, wrapped.height)
        // `min-h-11` around one 10 px child: the line is the whole row.
        val tall = cssRowAcross(IntArray(1), floatArrayOf(10f), 1, 0f, 0f, 0f, 115.5f, 0.25f, CssAlign.Center)
        assertEquals(115.5f, tall.exactHeight)
        assertEquals(listOf(53), tall.y.toList())
        assertEquals(116, tall.height)
        assertEquals(listOf(0), cssRowAcross(IntArray(1), floatArrayOf(10f), 1, 0f, 0f, 0f, 115.5f, 0.25f, CssAlign.Start).y.toList())
    }

    @Test
    fun `a lazy stack opened at its end stacks upwards from its last item`() {
        // The chat's last answer (215.25 px at 2.625x) is measured first, on a whole pixel.
        assertEquals(0f to 215, cssLazyPlace(null, null, null, 215.25f, 0f, last = true))
        // The message above it (371.5) ends where that one starts: 371 pixels, from -371.
        assertEquals(-371.5f to 371, cssLazyPlace(null, 0f, null, 371.5f, 0f, last = false))
        // Measured again with the one above it known, an item goes under that one, whatever it held.
        assertEquals(0f to 215, cssLazyPlace(0f, 9f, 4f, 215.25f, 0f, last = true))
        // Cards 10.5 px apart: each takes the pixels to the next one's top, the last none under it.
        assertEquals(52.5f to 53, cssLazyPlace(52.5f, null, null, 43.3f, 10.5f, last = false))
        assertEquals(52.5f to 43, cssLazyPlace(52.5f, null, null, 43.3f, 10.5f, last = true))
        // Above one known: its own gap is between them.
        assertEquals(-53.8f to 54, cssLazyPlace(null, 0f, null, 43.3f, 10.5f, last = false))
        // Nothing beside it known: where it was.
        assertEquals(7.25f to 44, cssLazyPlace(null, null, 7.25f, 43.3f, 0f, last = true))
    }
}
