/*
 * Contrast algorithm ported from xterm.js src/common/Color.ts (MIT).
 * Copyright (c) 2014-2026 The xterm.js authors. See licenses/xterm-MIT.txt.
 */
package org.connectbot.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import kotlin.math.ceil
import kotlin.math.pow

/** One bounded cache per paint, keyed by resolved RGB pairs, never palette indices. */
internal class TerminalContrast {
    private var ratio = 1.0
    private val cache = object : LinkedHashMap<Long, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Int>?) = size > 1024
    }

    fun foreground(fg: Color, bg: Color, surface: Color, minimum: Double, codepoint: Int): Color {
        if (minimum <= 1.0 || codepoint in 0x2500..0x259F || codepoint in 0xE0A4..0xE0D6) return fg
        if (ratio != minimum) { cache.clear(); ratio = minimum }
        val background = bg.compositeOver(surface).toArgb()
        val foreground = fg.compositeOver(bg.compositeOver(surface)).toArgb()
        val key = (background.toLong() shl 32) or (foreground.toLong() and 0xFFFFFFFFL)
        return Color(cache.getOrPut(key) { ensureTerminalContrast(background, foreground, minimum) })
    }
}

/** xterm's 10% channel stepping, including its alternate-direction fallback. ARGB in/out. */
internal fun ensureTerminalContrast(background: Int, foreground: Int, ratio: Double): Int {
    val bg = terminalLuminance(background)
    val fg = terminalLuminance(foreground)
    if (terminalContrastRatio(bg, fg) >= ratio) return foreground
    fun adjust(lighter: Boolean): Int {
        var r = foreground ushr 16 and 255
        var g = foreground ushr 8 and 255
        var b = foreground and 255
        fun step(c: Int) = if (lighter) c + ceil((255 - c) * 0.1).toInt() else c - ceil(c * 0.1).toInt()
        var value = foreground
        while (terminalContrastRatio(bg, terminalLuminance(value)) < ratio &&
            (if (lighter) r < 255 || g < 255 || b < 255 else r > 0 || g > 0 || b > 0)) {
            r = step(r); g = step(g); b = step(b)
            value = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return value
    }
    val first = adjust(fg >= bg)
    val firstRatio = terminalContrastRatio(bg, terminalLuminance(first))
    if (firstRatio >= ratio) return first
    val second = adjust(fg < bg)
    return if (firstRatio > terminalContrastRatio(bg, terminalLuminance(second))) first else second
}

internal fun terminalLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = (argb ushr shift and 255) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
}

internal fun terminalContrastRatio(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
