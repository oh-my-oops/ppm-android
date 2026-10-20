package com.hienlh.ppm.state.windows

import kotlin.math.max

/**
 * window-dock-layout.ts: how the status bar's window dock fits its chips into the width it has.
 * Pure, so the squeeze order is tested without a layout pass.
 *
 * From roomy to cramped:
 *  1. [DockMode.Full]: every chip with its title.
 *  2. [DockMode.Compact]: only the window in front keeps its title; the others are icons.
 *  3. Overflow: chips fold into `+N`, newest first, never the one in front.
 *  4. [DockMode.Tight]: left too little room for a useful slice of its title, the front chip
 *     drops its title as well.
 * Past `maxChips` the row stops being scannable, so it folds even when it would fit.
 */
enum class DockMode { Full, Compact, Tight }

data class DockLayout(
    val mode: DockMode,
    /** Indexes folded into `+N`, in the order they were folded. */
    val hidden: List<Int>,
)

/**
 * [fullWidths] is each chip's width with its title, in open order; [iconWidth] a chip's without
 * it; [frontIndex] the window in front, or -1 when every window is minimized. Below [minTitle] dp
 * of title, the front chip shows none at all.
 */
fun planDock(
    available: Double,
    fullWidths: List<Double>,
    iconWidth: Double,
    gap: Double,
    moreWidth: Double,
    frontIndex: Int,
    maxChips: Int,
    minTitle: Double = 56.0,
): DockLayout {
    val n = fullWidths.size
    if (n == 0) return DockLayout(DockMode.Full, emptyList())
    fun sum(xs: List<Double>) = xs.sum() + max(0, xs.size - 1) * gap

    if (n <= maxChips && sum(fullWidths) <= available) return DockLayout(DockMode.Full, emptyList())

    val compact = fullWidths.mapIndexed { i, w -> if (i == frontIndex) w else iconWidth }
    if (n <= maxChips && sum(compact) <= available) return DockLayout(DockMode.Compact, emptyList())

    // Fold from the newest end until the rest fits beside `+N`.
    val hidden = mutableListOf<Int>()
    fun visible() = compact.filterIndexed { i, _ -> i !in hidden }
    fun fits() = sum(visible()) + gap + moreWidth <= available
    for (i in n - 1 downTo 0) {
        if (fits() && n - hidden.size <= maxChips) break
        if (i == frontIndex) continue
        hidden += i
    }

    // Whatever is left over goes to the front chip's title; too little of it reads as noise.
    if (frontIndex >= 0 && frontIndex !in hidden) {
        val others = visible().size - 1
        val room = available - moreWidth - gap - others * (iconWidth + gap)
        val titleRoom = room - iconWidth
        if (titleRoom < minTitle) return DockLayout(DockMode.Tight, hidden.toList())
    }
    return DockLayout(DockMode.Compact, hidden.toList())
}
