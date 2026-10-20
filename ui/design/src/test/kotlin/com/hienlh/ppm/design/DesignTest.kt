package com.hienlh.ppm.design

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
}
