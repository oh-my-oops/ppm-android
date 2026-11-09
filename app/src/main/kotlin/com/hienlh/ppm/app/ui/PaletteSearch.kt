package com.hienlh.ppm.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.hienlh.ppm.chat.FileTargets
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.protocol.FileEntry
import com.hienlh.ppm.protocol.jsLowercase
import com.hienlh.ppm.state.FileSearchScore
import com.hienlh.ppm.state.files.FileTreeState
import com.hienlh.ppm.state.scoreFileSearchFast
import com.hienlh.ppm.state.searchFilename
import com.hienlh.ppm.state.workspace.SourceLine

/*
 * What the command palette searches and how it lays the answer out (command-palette.tsx), kept
 * apart from the screen so it can be tested: the three kinds of result, the query's path and line,
 * the ranking, the host's folders, and what the filter chips count.
 */

/** At most this many matches are drawn (`MAX_RESULTS`). */
internal const val PALETTE_MAX_RESULTS = 100

/** A folder's files are cut to this many (`.slice(0, 50)`). */
internal const val FILESYSTEM_MAX_RESULTS = 50

/** Where a result comes from (`CommandItem.group`), which is what the filter chips split the list by. The web's database tables have no place here yet. */
internal enum class PaletteGroup(val label: String, val icon: ProductIcon) {
    ACTION("Actions", ProductIcon.Zap),
    FILE("Files", ProductIcon.FileCode),
    FILESYSTEM("Filesystem", ProductIcon.FolderOpen),
}

/**
 * One result (`CommandItem`). [keywords] is what a query is matched against: for a file or a host
 * file its path, which is also what a pick opens; for an action its name and the other words that
 * find it. An action runs [action]; a file has none, the screen opens its path.
 */
internal class PaletteItem(
    val group: PaletteGroup,
    val label: String,
    val keywords: String,
    /** The dimmed text at the right: a file's path. */
    val hint: String? = null,
    val shortcut: String? = null,
    /** An action's icon; a file is drawn with its own. */
    val icon: ProductIcon? = null,
    val iconVector: ImageVector? = null,
    /** Excluded by `.gitignore`: drawn at 60% with a tooltip. */
    val ignored: Boolean = false,
    val action: (() -> Unit)? = null,
)

/** A file of the project's list, as the palette lists it: its name, with the path beside it. */
internal fun fileItem(entry: FileEntry) = PaletteItem(PaletteGroup.FILE, entry.name, entry.path, hint = entry.path, ignored = entry.isIgnored)

/** A file the host listed, by absolute path: named by the last segment, either separator. */
internal fun filesystemItem(path: String) = PaletteItem(PaletteGroup.FILESYSTEM, path.split('/', '\\').last(), path, hint = path)

/**
 * What was typed (`typed`, `searchPath`): a path, and the line or lines it names when it ends in
 * `:120`, `:120-140` or `#L120`. A suffix naming a line that cannot be leaves the whole text as
 * the path, which then finds nothing, rather than opening line 1 of a file the text says line 0.
 */
internal class PaletteQuery(val text: String) {
    private val located = FileTargets.splitSourceLocation(text)
    val path: String = located?.path ?: text
    val line: SourceLine? = located?.line
}

/** A query that names a place on the host rather than in the project (`isPathQuery`): `/`, `~/`, `~\`, `C:\`. */
internal fun isPathQuery(query: String): Boolean =
    query.startsWith("/") || query.startsWith("~/") || query.startsWith("~\\") || DRIVE.containsMatchIn(query)

/** The folder the host is asked to list for a path query (`extractDir`): the path up to its last separator, or all of it when that is the first character. */
internal fun listedDirectory(query: String): String {
    val normalized = query.replace('\\', '/')
    if (normalized.endsWith("/")) return query
    val lastSlash = maxOf(normalized.lastIndexOf('/'), query.lastIndexOf('\\'))
    return if (lastSlash > 0) query.substring(0, lastSlash + 1) else query
}

/**
 * The files the explorer's tree has read, folders it opened or prefetched alike, each folder's
 * files and subfolders in the order the tree lists them (`flattenFiles`): what the palette lists
 * until the project's own list lands. Their ignored flag is not carried, as it is not on the web.
 */
internal fun loadedTreeFiles(tree: FileTreeState?, project: String?): List<FileEntry> {
    if (tree == null || tree.project != project) return emptyList()
    val files = ArrayList<FileEntry>()
    fun walk(path: String) {
        for (node in tree.directories[path].orEmpty()) {
            if (node.isDirectory) walk(node.path) else files += FileEntry(node.path, node.name)
        }
    }
    walk("")
    return files
}

/**
 * The actions and the project's files, prepared once to be searched on every keystroke: each
 * text lowercased as JavaScript lowercases it, with the filename it ends in.
 */
internal class PaletteSearchIndex(items: List<PaletteItem>) {
    private class Row(val item: PaletteItem, val filename: String, val path: String, val labelLength: Int, val depth: Int)

    private class Hit(val item: PaletteItem, val score: FileSearchScore)

    private val rows = items.map {
        val path = jsLowercase(it.keywords)
        Row(it, searchFilename(path), path, it.label.length, it.keywords.split('/').size)
    }

    val size: Int get() = rows.size

    /**
     * The best [limit] items for [searchPath], best first (`filtered`, normal mode): by how well
     * the name and path match, then the shorter name, then the shallower path, and in the order
     * they were given when those are equal. A leading `./` or `../` is dropped, as the index holds
     * paths without one.
     */
    fun rank(searchPath: String, limit: Int = PALETTE_MAX_RESULTS): List<PaletteItem> {
        val query = jsLowercase(searchPath).replace(LEADING_DOT_SLASH, "")
        val hits = ArrayList<Hit>()
        for (row in rows) {
            val score = scoreFileSearchFast(query, row.filename, row.path, row.labelLength, row.depth) ?: continue
            hits += Hit(row.item, score)
        }
        // The stable sort is what keeps equal scores in their given order.
        return hits.sortedWith { a, b -> compare(a.score, b.score) }.take(limit).map { it.item }
    }

    private fun compare(a: FileSearchScore, b: FileSearchScore): Int = when {
        a.tier != b.tier -> a.tier.compareTo(b.tier)
        a.offset != b.offset -> a.offset.compareTo(b.offset)
        a.nameLen != b.nameLen -> a.nameLen.compareTo(b.nameLen)
        else -> a.depth.compareTo(b.depth)
    }
}

/**
 * The files of a folder the host listed that [searchPath] names (`filtered`, path mode): the
 * folder is whatever it says up to the last `/`, and what follows is only a filter on the name
 * or the path, not a ranking. Everything, cut to fifty, while nothing follows.
 */
internal fun filesystemMatches(items: List<PaletteItem>, searchPath: String): List<PaletteItem> {
    val lastSlash = searchPath.lastIndexOf('/')
    val filter = if (lastSlash >= 0) jsLowercase(searchPath.substring(lastSlash + 1)) else ""
    if (filter.isEmpty()) return items.take(FILESYSTEM_MAX_RESULTS)
    return items
        .filter { jsLowercase(it.label).contains(filter) || jsLowercase(it.keywords).contains(filter) }
        .take(FILESYSTEM_MAX_RESULTS)
}

/**
 * What the palette lists for [searchPath] before the chips narrow it (`filtered`): the host's
 * files for a path query; the actions alone while nothing is typed; else the best of actions
 * and files together.
 */
internal fun paletteResults(index: PaletteSearchIndex, actions: List<PaletteItem>, filesystem: List<PaletteItem>, searchPath: String): List<PaletteItem> = when {
    isPathQuery(searchPath) -> filesystemMatches(filesystem, searchPath)
    searchPath.isBlank() -> actions
    else -> index.rank(searchPath)
}

/**
 * A list and the text it answers. The web makes `filtered` from the text within a frame of the
 * keystroke, so the list on screen is, as far as a hand can tell, the one for it. Here it is ranked
 * off the main thread and arrives after the text, so the one on screen can still answer what was
 * typed before.
 */
internal class PaletteRanked(val query: String, val items: List<PaletteItem>) {
    /** Whether this is the list for [searchPath], the host's folder not being read meanwhile ([listing]). */
    fun answers(searchPath: String, listing: Boolean): Boolean = query == searchPath && !listing
}

/**
 * An Enter, which on the web runs on the list of the text it was pressed on. Pressed before that
 * list has landed, it waits for it (milliseconds) rather than run the first row of the list for the
 * text before: for "new" that is New AI Chat, which starts a real chat. Typing on drops it, and a
 * list that was already there runs it at once.
 */
internal class PaletteEnter {
    private var waitingOn: String? = null

    /** Enter was pressed on [text]. True when its list is there and it runs now, else it is kept for [due]. */
    fun press(text: String, answered: Boolean): Boolean {
        waitingOn = if (answered) null else text
        return answered
    }

    /** The text or the list moved on: true once the list of the text a waiting Enter was pressed on has landed. */
    fun due(text: String, answered: Boolean): Boolean {
        val pressedOn = waitingOn ?: return false
        if (pressedOn != text) {
            waitingOn = null
            return false
        }
        if (!answered) return false
        waitingOn = null
        return true
    }
}

/**
 * The chips to offer (`availableGroups`), in the order the web finds them: a group is there when
 * something of it exists, not when something of it matches, so the chips do not come and go as
 * the query changes.
 */
internal fun availableGroups(hasActions: Boolean, hasFiles: Boolean, hasFilesystem: Boolean): List<PaletteGroup> = buildList {
    if (hasActions) add(PaletteGroup.ACTION)
    if (hasFiles) add(PaletteGroup.FILE)
    if (hasFilesystem) add(PaletteGroup.FILESYSTEM)
}

/** How many of the [results] each group holds (`groupCounts`): the count a chip shows. */
internal fun groupCounts(results: List<PaletteItem>): Map<PaletteGroup, Int> = results.groupingBy { it.group }.eachCount()

/** The list as drawn (`displayItems`): only the [active] groups when any is on. They narrow what the search already cut to its best hundred. */
internal fun visibleResults(results: List<PaletteItem>, active: Set<PaletteGroup>): List<PaletteItem> =
    if (active.isEmpty()) results else results.filter { it.group in active }

private val DRIVE = Regex("^[A-Za-z]:[/\\\\]")
private val LEADING_DOT_SLASH = Regex("^\\.\\.?/")
