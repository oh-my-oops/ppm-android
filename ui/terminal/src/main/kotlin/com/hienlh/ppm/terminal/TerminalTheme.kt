package com.hienlh.ppm.terminal

import androidx.compose.ui.graphics.toArgb
import com.hienlh.ppm.design.PpmTheme

/**
 * `src/web/theme/adapters/xterm-adapter.ts`, built-in branch, in ANSI order 0–15.
 * Pass to termlib's `applyColorScheme`, with tokens.text / tokens.bgSolid as its default
 * foreground / background (ARGB). Terminal's Compose colours use those same tokens;
 * selectionBackgroundColor is tokens.accentWash, retaining its alpha, and
 * selectionForegroundColor is tokens.text (otherwise termlib supplies fixed black).
 *
 * Native PpmTheme has no imported terminal palette; its resolver already falls back to Aurora.
 * Do not invent an imported palette here. Each call owns its array, so a terminal cannot
 * mutate another terminal's colours.
 *
 * The rebuilt :ui:terminal-engine adds minimumContrastRatio=4.5 and configurable cursor
 * colours (web: accent, cursor accent: bgSolid). Its renderer applies contrast per cell,
 * including truecolour and inverted cells, without changing this palette or cell backgrounds.
 */
fun terminalPalette(theme: PpmTheme): IntArray {
    val t = theme.tokens
    return intArrayOf(
        if (theme.isDark) 0xFF1B1F2A.toInt() else 0xFF2B2F38.toInt(),
        t.error.color.toArgb(),
        t.success.color.toArgb(),
        t.warning.color.toArgb(),
        t.accent.color.toArgb(),
        t.accent2.color.toArgb(),
        t.info.color.toArgb(),
        if (theme.isDark) 0xFFD7DCE8.toInt() else 0xFFE9EDF5.toInt(),
        t.text3.color.toArgb(),
        t.error.color.toArgb(),
        t.success.color.toArgb(),
        t.warning.color.toArgb(),
        t.accent.color.toArgb(),
        t.accent2.color.toArgb(),
        t.info.color.toArgb(),
        if (theme.isDark) 0xFFF7F9FF.toInt() else 0xFFFFFFFF.toInt(),
    )
}
