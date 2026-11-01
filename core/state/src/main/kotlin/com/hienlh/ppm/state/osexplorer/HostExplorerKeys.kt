package com.hienlh.ppm.state.osexplorer

import com.hienlh.ppm.protocol.HostFileEntry

/** How long a type-ahead buffer stays alive between keystrokes (`TYPE_AHEAD_RESET_MS`, use-entry-selection.ts). */
const val HOST_TYPE_AHEAD_RESET_MS = 800L

/** The selection and the cursor row (`SelectionState`): a range extends from [anchor], and the arrows move it. */
data class HostSelection(val selection: Set<String> = emptySet(), val anchor: String? = null)

sealed interface HostSelectionEvent {
    /**
     * A click on [path]. [ctrl] toggles the row and moves the anchor to it, and wins over [shift]; [shift] selects the range
     * from the anchor and leaves the anchor where it was; neither selects the row alone.
     */
    data class Click(val path: String, val shift: Boolean = false, val ctrl: Boolean = false) : HostSelectionEvent
    data object SelectAll : HostSelectionEvent
    data object Clear : HostSelectionEvent
}

/** `applySelection`: the selection after [event], over the visible rows [order]. */
fun applyHostSelection(state: HostSelection, order: List<String>, event: HostSelectionEvent): HostSelection = when (event) {
    HostSelectionEvent.Clear -> HostSelection()
    HostSelectionEvent.SelectAll -> HostSelection(order.toSet(), state.anchor ?: order.firstOrNull())
    is HostSelectionEvent.Click -> when {
        event.ctrl -> HostSelection(
            if (event.path in state.selection) state.selection - event.path else state.selection + event.path,
            event.path,
        )
        event.shift -> HostSelection(hostRangeBetween(order, state.anchor, event.path), state.anchor ?: event.path)
        else -> HostSelection(setOf(event.path), event.path)
    }
}

private fun hostRangeBetween(order: List<String>, from: String?, to: String): Set<String> {
    val start = from?.let(order::indexOf) ?: -1
    val end = order.indexOf(to)
    if (end < 0) return emptySet()
    if (start < 0) return setOf(to)
    return order.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
}

/**
 * The row an arrow key moves the cursor to (`moveCursor`). It stops at both ends instead of wrapping, and with no cursor
 * (or one the listing no longer holds) it enters from the end the key points away from: forwards at the first row,
 * backwards at the last.
 */
fun hostMoveCursor(order: List<String>, anchor: String?, delta: Int): String? {
    if (order.isEmpty()) return null
    val current = anchor?.let(order::indexOf) ?: -1
    if (current < 0) return if (delta > 0) order.first() else order.last()
    return order[(current + delta).coerceIn(0, order.lastIndex)]
}

/**
 * The first name starting with [buffer], looked for after [currentIndex] and wrapping round to it (`typeAheadIndex`); -1 for
 * none. With no cursor ([currentIndex] below 0) the search starts at the top. A repeated letter therefore walks the matches.
 */
fun hostTypeAheadIndex(names: List<String>, buffer: String, currentIndex: Int): Int {
    if (buffer.isEmpty()) return -1
    val needle = buffer.lowercase()
    val start = if (currentIndex < 0) 0 else currentIndex + 1
    for (offset in names.indices) {
        val index = (start + offset) % names.size
        if (names[index].lowercase().startsWith(needle)) return index
    }
    return -1
}

/**
 * What has been typed to jump to a name: a letter within [HOST_TYPE_AHEAD_RESET_MS] of the one before joins it, so `ab`
 * looks for `ab`, and one after that starts again.
 */
class HostTypeAhead {
    private var text = ""
    private var at = 0L

    /**
     * [char] typed at [now] (milliseconds): the index in [names] of the row it lands on, or -1. [cursor] is the index of the
     * cursor row, -1 for none. Only a buffer of one letter searches from the cursor; a longer one starts at the top.
     */
    fun feed(char: Char, now: Long, names: List<String>, cursor: Int): Int {
        text = if (now - at > HOST_TYPE_AHEAD_RESET_MS) char.toString() else text + char
        at = now
        return hostTypeAheadIndex(names, text, if (text.length == 1) cursor else -1)
    }
}

/** The keys the explorer reads (`use-explorer-keyboard.ts`), named apart from the UI toolkit that delivers them. */
enum class HostKey { UP, DOWN, LEFT, RIGHT, HOME, END, BACKSPACE, ENTER, F2, DELETE, ESCAPE, A, C, X, V, OTHER }

/** One key going down. [char] is what it types, when it types one (a letter, a digit, a space), whatever its modifiers. */
data class HostKeyPress(
    val key: HostKey,
    val char: Char? = null,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** What a key does. */
sealed interface HostKeyCommand {
    /** The key is the explorer's but does nothing now: two rows selected under Enter, or nothing under Delete. */
    data object Handled : HostKeyCommand
    data object Back : HostKeyCommand
    data object Forward : HostKeyCommand
    data object Up : HostKeyCommand
    data object Paste : HostKeyCommand
    data object SelectAll : HostKeyCommand
    data object ClearSelection : HostKeyCommand
    /** Move the selection to [path], or with [range] extend it from the anchor to there. */
    data class Select(val path: String, val range: Boolean = false) : HostKeyCommand
    data class Open(val entry: HostFileEntry) : HostKeyCommand
    data class Rename(val entry: HostFileEntry) : HostKeyCommand
    data class Copy(val entries: List<HostFileEntry>) : HostKeyCommand
    data class Cut(val entries: List<HostFileEntry>) : HostKeyCommand
    data class Trash(val entries: List<HostFileEntry>) : HostKeyCommand
    data class DeletePermanently(val entries: List<HostFileEntry>) : HostKeyCommand
}

/**
 * What [press] does over the rows [entries], the selection and the cursor row [anchor]; null when the key is not the
 * explorer's, so that it goes on to whoever else wants it (`useExplorerKeyboard` and the grid's own handler in `icons-view.tsx`).
 *
 * Enter and F2 act on exactly one active entry (the selection, else the cursor row); Delete, Copy and Cut on all of them.
 * [columns] is the Icons view's column count, null in the other views: there the arrows step a whole row up and down, and one
 * entry left and right. Kept from this app's own habits, not the web's: Alt+Up goes up a folder, and Shift with Home or End
 * extends the selection to the end.
 */
fun hostKeyCommand(
    press: HostKeyPress,
    entries: List<HostFileEntry>,
    selection: Set<String>,
    anchor: String?,
    columns: Int?,
    typeAhead: HostTypeAhead,
    now: Long,
): HostKeyCommand? {
    val targets = hostActiveEntries(entries, selection, anchor)
    val order = entries.map { it.path }
    if (press.ctrl) return when (press.key) {
        HostKey.A -> HostKeyCommand.SelectAll
        HostKey.C -> HostKeyCommand.Copy(targets)
        HostKey.X -> HostKeyCommand.Cut(targets)
        HostKey.V -> HostKeyCommand.Paste
        else -> null
    }
    fun step(delta: Int) = hostMoveCursor(order, anchor, delta)?.let { HostKeyCommand.Select(it, press.shift) } ?: HostKeyCommand.Handled
    return when (press.key) {
        HostKey.DOWN -> step(columns ?: 1)
        HostKey.UP -> if (press.alt) HostKeyCommand.Up else step(-(columns ?: 1))
        HostKey.LEFT -> if (press.alt) HostKeyCommand.Back else if (columns != null) step(-1) else null
        HostKey.RIGHT -> if (press.alt) HostKeyCommand.Forward else if (columns != null) step(1) else null
        HostKey.HOME -> order.firstOrNull()?.let { HostKeyCommand.Select(it, press.shift) } ?: HostKeyCommand.Handled
        HostKey.END -> order.lastOrNull()?.let { HostKeyCommand.Select(it, press.shift) } ?: HostKeyCommand.Handled
        HostKey.BACKSPACE -> HostKeyCommand.Back
        HostKey.ENTER -> targets.singleOrNull()?.let(HostKeyCommand::Open) ?: HostKeyCommand.Handled
        HostKey.F2 -> targets.singleOrNull()?.let(HostKeyCommand::Rename) ?: HostKeyCommand.Handled
        HostKey.DELETE -> when {
            targets.isEmpty() -> HostKeyCommand.Handled
            press.shift -> HostKeyCommand.DeletePermanently(targets)
            else -> HostKeyCommand.Trash(targets)
        }
        HostKey.ESCAPE -> HostKeyCommand.ClearSelection
        else -> {
            val char = press.char
            if (char == null || press.alt) null
            else {
                val index = typeAhead.feed(char, now, entries.map { it.name }, order.indexOf(anchor))
                entries.getOrNull(index)?.let { HostKeyCommand.Select(it.path) } ?: HostKeyCommand.Handled
            }
        }
    }
}
