package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.state.database.losesDbWorkOnClose

/*
 * Pinned tabs, as VS Code has them (src/web/lib/tab-pins.ts): a pinned tab sits at the start of
 * its tab bar, and the bulk closes (Close Others, Close to the Right, Close Saved, Close All) leave
 * it open. Only its own Close, from the menu, closes it.
 */

/** Pinned tabs first, each group in its own order; the same list when there is nothing to move. */
fun pinnedFirst(tabs: List<WorkspaceTab>): List<WorkspaceTab> {
    val pinned = tabs.filter { it.pinned }
    if (pinned.isEmpty() || pinned.size == tabs.size) return tabs
    return pinned + tabs.filter { !it.pinned }
}

/**
 * Pins or unpins [tabId]. A pinned tab joins the end of the pinned group; an unpinned one becomes
 * the first unpinned tab, right after the pins, which is where VS Code puts both. The same list
 * when nothing changes.
 */
fun withPinned(tabs: List<WorkspaceTab>, tabId: String, pinned: Boolean): List<WorkspaceTab> {
    val tab = tabs.firstOrNull { it.id == tabId } ?: return tabs
    if (tab.pinned == pinned) return tabs
    val rest = tabs.filter { it.id != tabId }
    val at = rest.count { it.pinned }
    return rest.take(at) + tab.copy(pinned = pinned) + rest.drop(at)
}

/** The tab menu's bulk closes (`BulkClose`). */
enum class BulkClose { OTHERS, RIGHT, SAVED, ALL }

/**
 * The tabs a bulk close takes, in bar order (`bulkCloseIds`): never a pinned tab, never one that
 * cannot close, and for Close Saved never one holding unsaved work. [anchorId] is the tab the menu
 * was opened on.
 */
fun bulkCloseIds(
    tabs: List<WorkspaceTab>,
    anchorId: String,
    kind: BulkClose,
    hasUnsaved: (WorkspaceTab) -> Boolean,
): List<String> {
    val pool = when (kind) {
        BulkClose.OTHERS -> tabs.filter { it.id != anchorId }
        // `findIndex + 1`: an anchor not in the bar leaves every tab.
        BulkClose.RIGHT -> tabs.drop(tabs.indexOfFirst { it.id == anchorId } + 1)
        else -> tabs
    }
    return pool
        .filter { it.closable && !it.pinned && (kind != BulkClose.SAVED || !hasUnsaved(it)) }
        .map { it.id }
}

/**
 * Close Saved leaves this tab open (`tabHoldsUnsavedWork`): work a close would ask about, or an
 * untitled editor's text, which lives only in the tab (a file editor saves itself after each change).
 */
fun tabHoldsUnsavedWork(tab: WorkspaceTab): Boolean = losesDbWorkOnClose(tab) || "unsavedContent" in tab.metadata
