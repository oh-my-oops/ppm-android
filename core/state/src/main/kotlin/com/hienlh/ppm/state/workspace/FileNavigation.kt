package com.hienlh.ppm.state.workspace

import com.hienlh.ppm.client.FsStat
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A place in a file (`SourceLine`): [end] is the inclusive last line of a range. */
data class SourceLine(val start: Long, val end: Long? = null)

/** A location as the palette reads it back (`formatSourceLocation`). */
fun formatSourceLocation(path: String, line: SourceLine?): String =
    if (line == null) path else "$path:${line.start}${line.end?.let { "-$it" } ?: ""}"

/** Where a file a chat's message names leads (`useMarkdownFileNavigation`). */
sealed interface FileNavigation {
    /** An editor tab on the file. [reveal]: lines are named, so a tab already open on it is sent to them too. */
    data class Editor(val tab: NewTab, val reveal: Boolean) : FileNavigation

    /** A folder, which opens in a file explorer. */
    data class Folder(val path: String) : FileNavigation

    /** Nothing the host can open: the command palette, searching for [query]. */
    data class Search(val query: String) : FileNavigation
}

/** What a link's target is read against: an absolute one stands alone, anything else sits beside the link. */
private val LINK_TARGET_ABSOLUTE = Regex("""^(/|[a-z]:[/\\])""", RegexOption.IGNORE_CASE)

/**
 * What [path] opens, looked up on the host by [stat]: relative to [projectRoot] unless it is
 * absolute or starts with `~`, links followed at most 8 deep. A file in the project keeps its
 * project-relative name, so it is the tab the explorer would open; one outside it is named by
 * its absolute path. Whatever cannot be read is searched for in the palette, [line] and all.
 * Two of the web's steps need what the app does not have yet: a background command's `.output`
 * goes to its output panel there, and a bare name is looked up in the file tree before searching.
 */
suspend fun resolveFileNavigation(
    path: String,
    line: SourceLine?,
    projectName: String?,
    projectRoot: String?,
    now: Long,
    stat: suspend (String) -> FsStat,
): FileNavigation {
    val search = FileNavigation.Search(formatSourceLocation(path, line))
    val root = projectRoot?.takeIf { it.isNotEmpty() }
    val candidate = if (HOST_PATH.containsMatchIn(path)) path else root?.let { absoluteProjectPath(it, path) } ?: return search
    try {
        var entry = stat(candidate)
        var depth = 0
        while (entry.kind == "symlink" && !entry.target.isNullOrEmpty() && depth < 8) {
            val target = entry.target!!
            val parent = entry.path.substring(0, entry.path.length - entry.name.length)
            entry = stat(if (LINK_TARGET_ABSOLUTE.containsMatchIn(target)) target else absoluteProjectPath(parent, target))
            depth++
        }
        if (entry.kind == "directory") return FileNavigation.Folder(entry.path)
        if (entry.kind != "file") return search
        val relative = root?.let { relativeProjectPath(it, entry.path) }
        val metadata = buildJsonObject {
            put("filePath", relative ?: entry.path)
            projectName?.takeIf { it.isNotEmpty() }?.let { put("projectName", it) }
            if (line != null) {
                put("lineNumber", line.start)
                line.end?.let { put("endLine", it) }
                put("revealAt", now)
            }
        }
        return FileNavigation.Editor(NewTab("editor", entry.name, if (relative != null) projectName else null, metadata), reveal = line != null)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return search
    }
}
