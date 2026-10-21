package com.hienlh.ppm.state

/**
 * Shell-style recall over a chat's past user messages (`message-history-recall.ts`). [index]
 * counts back from the newest message (0 = newest); -1 means the composer holds a live draft.
 */
data class HistoryStep(val index: Int, val text: String)

/**
 * [delta] steps into the past (+1) or back toward the draft (-1); null when that falls outside
 * [history], which comes oldest first, as the transcript does.
 */
fun stepHistory(history: List<String>, index: Int, delta: Int): HistoryStep? {
    if (history.isEmpty()) return null
    val next = index + delta
    if (next < -1 || next >= history.size) return null
    return HistoryStep(next, if (next == -1) "" else history[history.size - 1 - next])
}
