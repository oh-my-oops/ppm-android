package com.hienlh.ppm.state.workspace

/** A place Back returns to: a tab shown in a project. */
data class NavEntry(val project: String, val tabId: String)

/**
 * The browser history `useUrlSync` builds, kept for Android's Back: an entry each time the tab
 * in front changes (the focused panel's, which the web's address follows), in whichever project.
 * The web also pushes an address with no tab in it, which its Back then does nothing for; those
 * are left out here, so that no press of Back is lost on one.
 */
class NavHistory(private val limit: Int = 100) {
    private val entries = ArrayList<NavEntry>()

    /** There is an entry before the current one. */
    val canGoBack: Boolean get() = entries.size > 1

    /** The tab in front changed to [entry]; the same one again is not a new entry. */
    fun record(entry: NavEntry) {
        if (entries.lastOrNull() == entry) return
        entries += entry
        if (entries.size > limit) entries.removeAt(0)
    }

    /** Steps back: the entry before the current one, which becomes the current one; null at the first. */
    fun back(): NavEntry? {
        if (entries.size < 2) return null
        entries.removeAt(entries.lastIndex)
        return entries.last()
    }

    fun clear() = entries.clear()
}
