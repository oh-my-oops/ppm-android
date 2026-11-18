package com.hienlh.ppm.app.ui.phone.files

import com.hienlh.ppm.state.files.FileDocumentClassifier
import com.hienlh.ppm.state.files.FileDocumentViewer
import java.text.NumberFormat
import java.util.Locale

/**
 * Pure models, rules, and classifiers for `Phone 3 · Files, editor, viewers, search`
 * (`designs/ppm-mobile-tablet-redesign/phone/3-files/index.html`, Requirements 8.1, 8.2).
 *
 * The phone does what the tablet does with a project's files, laid out as the web does below `md`:
 * browse the tree and change it (New File, New Folder, Upload Files…, Cut/Copy/Paste, Rename, Delete,
 * Select for Compare), open a file in the editor or in the viewer the tablet uses for it (CSV table,
 * Word, audio, video, SQLite, HTML preview), see a file's inline diff, search and replace across the
 * project, and download any file.
 */

// ───────────────────────── Section 1 · Files (Project Tree) ─────────────────────────

/**
 * Overflow (`⋮`) menu items in the phone Files app bar: the web's Explorer header (`file-tree.tsx`: New File,
 * New Folder, Reveal Active File, Collapse All, Refresh), plus Upload Files…, which a phone cannot do by
 * dropping files on the tree, and Paste into the project folder, which the web does with Ctrl+V.
 */
enum class PhoneFilesOverflowAction(
    val id: String,
    val label: String,
) {
    NEW_FILE("new_file", "New File"),
    NEW_FOLDER("new_folder", "New Folder"),
    UPLOAD("upload", "Upload Files…"),
    PASTE("paste", "Paste"),
    REVEAL_ACTIVE_FILE("reveal_active_file", "Reveal Active File"),
    COLLAPSE_ALL("collapse_all", "Collapse All"),
    REFRESH("refresh", "Refresh"),
    ;

    companion object {
        /** [canWrite]: the tree may change the project now; [canPaste]: something was cut or copied. */
        fun phoneMenu(canWrite: Boolean = true, canPaste: Boolean = false): List<PhoneFilesOverflowAction> = buildList {
            if (canWrite) {
                add(NEW_FILE)
                add(NEW_FOLDER)
                add(UPLOAD)
                if (canPaste) add(PASTE)
            }
            add(REVEAL_ACTIVE_FILE)
            add(COLLAPSE_ALL)
            add(REFRESH)
        }
    }
}

/**
 * Long-press sheet actions for a file or folder in the phone project tree: the rows of the web's
 * `tree-node-context-menu.tsx`, which its adaptive menu shows as a bottom sheet below `md`, plus Open on a
 * file and Upload Files… on a folder.
 */
enum class PhoneTreeLongPressAction(
    val id: String,
    val label: String,
    val destructive: Boolean = false,
) {
    OPEN("open", "Open"),
    NEW_FILE("new_file", "New File"),
    NEW_FOLDER("new_folder", "New Folder"),
    UPLOAD("upload", "Upload Files…"),
    OPEN_IN_FILE_EXPLORER("open_in_file_explorer", "Open in File Explorer"),
    OPEN_IN_TERMINAL("open_in_terminal", "Open in Terminal"),
    CUT("cut", "Cut"),
    COPY("copy", "Copy"),
    PASTE("paste", "Paste"),
    RENAME("rename", "Rename"),
    DELETE("delete", "Delete", destructive = true),
    COPY_PATH("copy_path", "Copy Path"),
    COPY_FULL_PATH("copy_full_path", "Copy Full Path"),
    DOWNLOAD("download", "Download"),
    SELECT_FOR_COMPARE("select_for_compare", "Select for Compare"),
    COMPARE_WITH_SELECTED("compare_with_selected", "Compare with Selected"),
    ;

    /** The row's text: a folder downloads as a zip, and a compare names the file picked first (`label`). */
    fun labelFor(isDirectory: Boolean, compareLabel: String? = null): String = when (this) {
        DOWNLOAD -> if (isDirectory) "Download as Zip" else label
        COMPARE_WITH_SELECTED -> if (compareLabel != null) "$label ($compareLabel)" else label
        else -> label
    }

    companion object {
        /**
         * The sheet's groups, a divider between each two (the web's `ContextMenuSeparator`s). [canWrite]: the
         * tree may change files now; [canPaste]: something is cut or copied; [canOpenInExplorer]: the host's
         * File Explorer can be opened; [canCompareWithSelected]: another file of this project was selected
         * for compare.
         */
        fun groupsFor(
            isDirectory: Boolean,
            canWrite: Boolean = false,
            canPaste: Boolean = false,
            canOpenInExplorer: Boolean = false,
            canCompareWithSelected: Boolean = false,
        ): List<List<PhoneTreeLongPressAction>> = listOf(
            if (isDirectory) {
                if (canWrite) listOf(NEW_FILE, NEW_FOLDER, UPLOAD) else emptyList()
            } else {
                listOf(OPEN)
            },
            if (isDirectory) listOfNotNull(OPEN_IN_FILE_EXPLORER.takeIf { canOpenInExplorer }, OPEN_IN_TERMINAL) else emptyList(),
            if (canWrite) listOfNotNull(CUT, COPY, PASTE.takeIf { isDirectory && canPaste }) else emptyList(),
            if (canWrite) listOf(RENAME, DELETE) else emptyList(),
            listOf(COPY_PATH, COPY_FULL_PATH),
            listOf(DOWNLOAD),
            if (isDirectory) emptyList() else listOfNotNull(SELECT_FOR_COMPARE, COMPARE_WITH_SELECTED.takeIf { canCompareWithSelected }),
        ).filter { it.isNotEmpty() }

        /** [groupsFor] as one list. */
        fun actionsFor(
            isDirectory: Boolean,
            canWrite: Boolean = false,
            canPaste: Boolean = false,
            canOpenInExplorer: Boolean = false,
            canCompareWithSelected: Boolean = false,
        ): List<PhoneTreeLongPressAction> =
            groupsFor(isDirectory, canWrite, canPaste, canOpenInExplorer, canCompareWithSelected).flatten()
    }
}

/**
 * Breadcrumb segment at the top of the phone project tree (`Phone 3 · F1`, Note 1) or editor
 * (`Phone 3 · E1`, Note 11).
 */
data class PhonePathCrumb(
    val label: String,
    val relativePath: String,
    val isCurrent: Boolean,
)

/**
 * Builds the breadcrumb segments naming the folder at the top of the phone tree (`Phone 3 · F1`, Note 1).
 * The first segment is always [projectName] (relativePath = `""`), followed by each directory in [folderPath].
 */
fun buildPhoneTreeBreadcrumbs(
    projectName: String,
    folderPath: String = "",
): List<PhonePathCrumb> {
    val cleanProject = projectName.trim().ifEmpty { "project" }
    val parts = folderPath.trim().trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) {
        return listOf(PhonePathCrumb(label = cleanProject, relativePath = "", isCurrent = true))
    }
    val result = ArrayList<PhonePathCrumb>(parts.size + 1)
    result.add(PhonePathCrumb(label = cleanProject, relativePath = "", isCurrent = false))
    val accum = StringBuilder()
    parts.forEachIndexed { index, segment ->
        if (accum.isNotEmpty()) accum.append('/')
        accum.append(segment)
        result.add(
            PhonePathCrumb(
                label = segment,
                relativePath = accum.toString(),
                isCurrent = index == parts.lastIndex,
            ),
        )
    }
    return result
}

/**
 * Builds the file path breadcrumb segments for the phone editor (`Phone 3 · E1`, Note 11).
 */
fun buildPhoneEditorBreadcrumbs(filePath: String): List<PhonePathCrumb> {
    val parts = filePath.trim().trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    val result = ArrayList<PhonePathCrumb>(parts.size)
    val accum = StringBuilder()
    parts.forEachIndexed { index, segment ->
        if (accum.isNotEmpty()) accum.append('/')
        accum.append(segment)
        result.add(
            PhonePathCrumb(
                label = segment,
                relativePath = accum.toString(),
                isCurrent = index == parts.lastIndex,
            ),
        )
    }
    return result
}

/**
 * Git status priority for folder tinting (`Phone 3 · F1`, Note 2):
 * `D > A > M > R > C > U`.
 */
private val GIT_FOLDER_PRIORITY: List<String> = listOf("D", "A", "M", "R", "C", "U")

/**
 * Normalizes a raw git status code (`"??"` -> `"U"`, `"MM"` -> `"M"`, etc.) to the single-letter
 * mono badge shown at the end of a file row (`M, A, D, R, C, U`, Note 2).
 */
fun normalizePhoneGitBadgeLetter(rawStatus: String?): String? {
    val trimmed = rawStatus?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        trimmed == "??" || trimmed == "U" || trimmed == "UNTRACKED" -> "U"
        'D' in trimmed -> "D"
        'A' in trimmed -> "A"
        'M' in trimmed -> "M"
        'R' in trimmed -> "R"
        'C' in trimmed -> "C"
        trimmed.first().isLetter() -> trimmed.first().toString()
        else -> null
    }
}

/**
 * Resolves the highest-priority git status for a folder from its descendant statuses
 * (`D > A > M > R > C > U`, `Phone 3 · F1`, Note 2).
 */
fun resolveFolderGitPriority(childStatuses: Iterable<String>): String? {
    val normalized = childStatuses.mapNotNullTo(HashSet()) { normalizePhoneGitBadgeLetter(it) }
    if (normalized.isEmpty()) return null
    return GIT_FOLDER_PRIORITY.firstOrNull { it in normalized } ?: normalized.first()
}

/** Formats a folder entry count with US thousands separators (`1284` -> `"1,284"`, `Phone 3 · F6`, Note 9). */
fun formatFolderEntryCount(count: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(count.coerceAtLeast(0))

/**
 * High-level state of the phone Files screen (`Phone 3 · Section 1`, Note 10 & 65).
 */
sealed interface PhoneFilesScreenState {
    data object NoProject : PhoneFilesScreenState {
        const val TITLE: String = "Select a project to browse files."
        const val SUBTITLE: String = "☰ → the project card at the top switches project."
    }

    data object Loading : PhoneFilesScreenState {
        const val MESSAGE: String = "Loading files..."
    }

    data object Empty : PhoneFilesScreenState {
        const val TITLE: String = "Empty project."
    }

    data class Error(
        val detail: String,
        val title: String = "Could not list the project",
    ) : PhoneFilesScreenState

    data class Ready(
        val offlineSeconds: Int? = null,
    ) : PhoneFilesScreenState {
        val isOffline: Boolean get() = offlineSeconds != null
        val offlineTitle: String
            get() = "Offline for ${offlineSeconds ?: 0} s"

        companion object {
            const val OFFLINE_SUBTITLE: String =
                "The tree is as it was; actions wait for the server."
        }
    }
}

// ───────────────────────── Section 2 · Editor ─────────────────────────

/**
 * Overflow (`⋮`) menu items in the phone Editor app bar (`Phone 3 · Section 2`, Note 69).
 * Inline blame, Language server, Editor language, Format, Compare, and Save As are tablet-only.
 */
enum class PhoneEditorOverflowAction(
    val id: String,
    val label: String,
) {
    WORD_WRAP("word_wrap", "Word wrap"),
    RELOAD_FROM_DISK("reload_from_disk", "Reload from disk"),
    DOWNLOAD("download", "Download"),
    COPY_PATH("copy_path", "Copy Path"),
    ;

    companion object {
        fun phoneMenu(): List<PhoneEditorOverflowAction> = entries
    }
}

/**
 * Text selection toolbar actions on a phone (`Phone 3 · Section 2`, Note 68):
 * system actions (`Cut`, `Copy`, `Paste`, `Select all`) plus `"Add to chat"`.
 */
enum class PhoneSelectionAction(
    val id: String,
    val label: String,
    val isAiAction: Boolean = false,
) {
    CUT("cut", "Cut"),
    COPY("copy", "Copy"),
    PASTE("paste", "Paste"),
    SELECT_ALL("select_all", "Select all"),
    ADD_TO_CHAT("add_to_chat", "Add to chat", isAiAction = true),
    ;

    companion object {
        fun actionsFor(readOnly: Boolean): List<PhoneSelectionAction> =
            if (readOnly) listOf(COPY, SELECT_ALL, ADD_TO_CHAT) else entries
    }
}

/**
 * Save state displayed in the phone editor's bottom action card (`Phone 3 · Section 2`, Note 14 & 27).
 */
sealed interface PhoneEditorSaveState {
    val primaryLabel: String
    val saveButtonEnabled: Boolean

    data class Saved(
        val languageLabel: String = "Plain Text",
        val line: Int = 1,
        val column: Int = 1,
    ) : PhoneEditorSaveState {
        override val primaryLabel: String = "Saved"
        override val saveButtonEnabled: Boolean = false
        val subline: String get() = "$languageLabel · Ln $line, Col $column"
    }

    data class Edited(
        val changedOnDisk: Boolean = false,
    ) : PhoneEditorSaveState {
        override val primaryLabel: String = "Edited"
        override val saveButtonEnabled: Boolean = true
        val subline: String
            get() = if (changedOnDisk) {
                "Autosave paused until you choose"
            } else {
                "Edited · autosaves in 1 s"
            }
    }

    data object Saving : PhoneEditorSaveState {
        override val primaryLabel: String = "Saving…"
        override val saveButtonEnabled: Boolean = false
    }

    data class Failed(
        val message: String = "Failed to save file",
        val detail: String = "Edits are kept here.",
    ) : PhoneEditorSaveState {
        override val primaryLabel: String = "Failed to save file"
        override val saveButtonEnabled: Boolean = true
    }
}

/**
 * The editor status bar's language name for [path] (VS Code's names, as Monaco's language picker shows
 * them), or for the tab's own language when one was picked ([override]).
 */
fun phoneEditorLanguageLabel(path: String?, override: String? = null): String {
    val name = path.orEmpty().substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
    val ext = name.substringAfterLast('.', "")
    if (override.isNullOrBlank()) {
        when (ext) {
            "tsx" -> return "TypeScript React"
            "jsx" -> return "JavaScript React"
        }
    }
    val id = com.hienlh.ppm.app.ui.files.fileCodeLanguage(path, override).lowercase(Locale.ROOT)
    return PHONE_LANGUAGE_LABELS[id] ?: id.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

private val PHONE_LANGUAGE_LABELS = mapOf(
    "plaintext" to "Plain Text", "text" to "Plain Text", "txt" to "Plain Text",
    "typescript" to "TypeScript", "javascript" to "JavaScript", "kotlin" to "Kotlin", "python" to "Python",
    "ruby" to "Ruby", "rust" to "Rust", "go" to "Go", "java" to "Java", "swift" to "Swift", "shell" to "Shell Script",
    "yaml" to "YAML", "json" to "JSON", "html" to "HTML", "css" to "CSS", "scss" to "SCSS", "md" to "Markdown",
    "markdown" to "Markdown", "csharp" to "C#", "cpp" to "C++", "c" to "C", "sql" to "SQL", "xml" to "XML",
    "dotenv" to "Dotenv", "dockerfile" to "Dockerfile", "make" to "Makefile", "php" to "PHP", "lua" to "Lua",
    "toml" to "TOML", "dart" to "Dart", "vue" to "Vue", "svelte" to "Svelte", "csv" to "CSV",
)

/**
 * Key specification in the phone editor key row (`Phone 3 · E2`, `t-ekeys-p`, Note 16).
 *
 * Notice that `Complete` (`i-sparkles`) is intentionally excluded on a phone because the
 * language server is tablet-only (Section 9 & Note 16).
 */
data class PhoneEditorKey(
    val id: String,
    val label: String,
    val insertText: String? = null,
    val isToggle: Boolean = false,
    val isSymbol: Boolean = false,
)

val PhoneEditorKeys: List<PhoneEditorKey> = listOf(
    PhoneEditorKey(id = "paste", label = "Paste"),
    PhoneEditorKey(id = "undo", label = "Undo"),
    PhoneEditorKey(id = "redo", label = "Redo"),
    PhoneEditorKey(id = "wrap", label = "Wrap", isToggle = true),
    PhoneEditorKey(id = "tab", label = "Tab", insertText = "  "),
    PhoneEditorKey(id = "sym_lparen", label = "(", insertText = "(", isSymbol = true),
    PhoneEditorKey(id = "sym_rparen", label = ")", insertText = ")", isSymbol = true),
    PhoneEditorKey(id = "sym_lbrace", label = "{", insertText = "{", isSymbol = true),
    PhoneEditorKey(id = "sym_rbrace", label = "}", insertText = "}", isSymbol = true),
    PhoneEditorKey(id = "sym_lbracket", label = "[", insertText = "[", isSymbol = true),
    PhoneEditorKey(id = "sym_rbracket", label = "]", insertText = "]", isSymbol = true),
    PhoneEditorKey(id = "sym_lt", label = "<", insertText = "<", isSymbol = true),
    PhoneEditorKey(id = "sym_gt", label = ">", insertText = ">", isSymbol = true),
    PhoneEditorKey(id = "sym_semi", label = ";", insertText = ";", isSymbol = true),
    PhoneEditorKey(id = "sym_colon", label = ":", insertText = ":", isSymbol = true),
    PhoneEditorKey(id = "sym_eq", label = "=", insertText = "=", isSymbol = true),
    PhoneEditorKey(id = "sym_dquote", label = "\"", insertText = "\"", isSymbol = true),
    PhoneEditorKey(id = "sym_squote", label = "'", insertText = "'", isSymbol = true),
    PhoneEditorKey(id = "sym_backtick", label = "`", insertText = "`", isSymbol = true),
    PhoneEditorKey(id = "sym_slash", label = "/", insertText = "/", isSymbol = true),
    PhoneEditorKey(id = "sym_backslash", label = "\\", insertText = "\\", isSymbol = true),
    PhoneEditorKey(id = "sym_underscore", label = "_", insertText = "_", isSymbol = true),
    PhoneEditorKey(id = "sym_hash", label = "#", insertText = "#", isSymbol = true),
)

/**
 * Counts Git merge conflict blocks (`<<<<<<<` … `=======` … `>>>>>>>`) in [content]
 * (`Phone 3 · Section 2`, Note 70).
 */
fun countMergeConflicts(content: String): Int {
    if (!content.contains("<<<<<<<")) return 0
    var count = 0
    var inOurs = false
    var inTheirs = false
    for (rawLine in content.lineSequence()) {
        val line = rawLine.trimEnd('\r')
        when {
            line.startsWith("<<<<<<<") -> {
                inOurs = true
                inTheirs = false
            }
            inOurs && line.startsWith("=======") -> {
                inOurs = false
                inTheirs = true
            }
            inTheirs && line.startsWith(">>>>>>>") -> {
                count++
                inTheirs = false
            }
        }
    }
    return count
}

/** Formats the merge conflict banner title (`"2 conflicts remaining"`, Note 70). */
fun formatMergeConflictBannerTitle(conflictCount: Int): String =
    if (conflictCount == 1) "1 conflict remaining" else "$conflictCount conflicts remaining"

const val MERGE_CONFLICT_TABLET_SUBTITLE: String = "Resolve conflicts on a tablet."

// ───────────────────────── Section 3 · Diff ─────────────────────────

/**
 * Parsed unified diff item for a single file on a phone (`Phone 3 · D1`, Notes 28 & 29).
 * On a phone, diffs are always rendered inline with unchanged contexts folded into
 * 36 dp `"Show N unchanged lines"` rows.
 */
sealed interface PhoneDiffRow {
    data class HunkHeader(val header: String) : PhoneDiffRow
    data class FoldedContext(
        val foldId: Int,
        val lines: List<CodeLine>,
    ) : PhoneDiffRow {
        val label: String
            get() = "Show ${lines.size} unchanged ${if (lines.size == 1) "line" else "lines"}"
    }

    data class CodeLine(
        val oldLineNumber: Int?,
        val newLineNumber: Int?,
        val kind: Kind,
        val text: String,
    ) : PhoneDiffRow {
        enum class Kind(val marker: String) {
            CONTEXT(" "),
            ADDED("+"),
            REMOVED("−"),
        }
    }
}

/** Summary of a parsed unified diff (`+added` / `−removed` counts and folded rows). */
data class PhoneParsedDiff(
    val rows: List<PhoneDiffRow>,
    val addedCount: Int,
    val removedCount: Int,
) {
    val isEmpty: Boolean get() = rows.isEmpty() && addedCount == 0 && removedCount == 0
}

private val HUNK_HEADER_REGEX = Regex("""^@@\s*-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s*@@.*$""")

/**
 * Parses a unified diff text into inline [PhoneDiffRow] items, folding runs of more than
 * `contextLines * 2` unchanged lines into [PhoneDiffRow.FoldedContext] rows (`Phone 3 · D1`, Note 29).
 */
fun parsePhoneInlineDiff(
    diffText: String,
    contextLines: Int = 3,
    expandedFoldIds: Set<Int> = emptySet(),
): PhoneParsedDiff {
    if (diffText.isBlank()) {
        return PhoneParsedDiff(rows = emptyList(), addedCount = 0, removedCount = 0)
    }
    val rawRows = ArrayList<PhoneDiffRow>()
    val pendingContext = ArrayList<PhoneDiffRow.CodeLine>()
    var oldLine = 1
    var newLine = 1
    var added = 0
    var removed = 0
    var nextFoldId = 1
    var seenHunk = false

    fun flushContext(isTrailing: Boolean) {
        if (pendingContext.isEmpty()) return
        val threshold = if (isTrailing) contextLines else contextLines * 2 + 1
        if (pendingContext.size > threshold) {
            val keepLeading = pendingContext.take(contextLines)
            val keepTrailing = if (isTrailing) emptyList() else pendingContext.takeLast(contextLines)
            val middle = pendingContext.subList(
                keepLeading.size,
                pendingContext.size - keepTrailing.size,
            ).toList()
            rawRows.addAll(keepLeading)
            val foldId = nextFoldId++
            if (foldId in expandedFoldIds) {
                rawRows.addAll(middle)
            } else {
                rawRows.add(PhoneDiffRow.FoldedContext(foldId = foldId, lines = middle))
            }
            rawRows.addAll(keepTrailing)
        } else {
            rawRows.addAll(pendingContext)
        }
        pendingContext.clear()
    }

    for (rawLine in diffText.lineSequence()) {
        val line = rawLine.trimEnd('\r')
        if (line.startsWith("diff --git") ||
            line.startsWith("index ") ||
            line.startsWith("--- ") ||
            line.startsWith("+++ ") ||
            line.startsWith("\\ No newline")
        ) {
            continue
        }
        val hunkMatch = HUNK_HEADER_REGEX.matchEntire(line)
        if (hunkMatch != null) {
            flushContext(isTrailing = false)
            oldLine = hunkMatch.groupValues[1].toIntOrNull() ?: 1
            newLine = hunkMatch.groupValues[2].toIntOrNull() ?: 1
            seenHunk = true
            rawRows.add(PhoneDiffRow.HunkHeader(line))
            continue
        }
        if (!seenHunk && line.isEmpty()) continue
        when {
            line.startsWith("+") -> {
                flushContext(isTrailing = false)
                added++
                rawRows.add(
                    PhoneDiffRow.CodeLine(
                        oldLineNumber = null,
                        newLineNumber = newLine++,
                        kind = PhoneDiffRow.CodeLine.Kind.ADDED,
                        text = line.substring(1),
                    ),
                )
            }
            line.startsWith("-") -> {
                flushContext(isTrailing = false)
                removed++
                rawRows.add(
                    PhoneDiffRow.CodeLine(
                        oldLineNumber = oldLine++,
                        newLineNumber = null,
                        kind = PhoneDiffRow.CodeLine.Kind.REMOVED,
                        text = line.substring(1),
                    ),
                )
            }
            else -> {
                val content = if (line.startsWith(" ")) line.substring(1) else line
                pendingContext.add(
                    PhoneDiffRow.CodeLine(
                        oldLineNumber = oldLine++,
                        newLineNumber = newLine++,
                        kind = PhoneDiffRow.CodeLine.Kind.CONTEXT,
                        text = content,
                    ),
                )
            }
        }
    }
    flushContext(isTrailing = true)
    return PhoneParsedDiff(
        rows = rawRows,
        addedCount = added,
        removedCount = removed,
    )
}

// ───────────────────────── Section 4 · Viewers ─────────────────────────

/**
 * Overflow (`⋮`) menu items in a phone Viewer app bar (`Phone 3 · Section 0 & 4`, Notes 33–35).
 */
enum class PhoneViewerOverflowAction(
    val id: String,
    val label: String,
) {
    REFRESH("refresh", "Refresh"),
    DOWNLOAD("download", "Download"),
    OPEN_WITH("open_with", "Open with…"),
    ;

    companion object {
        fun actionsFor(supportsOpenWith: Boolean): List<PhoneViewerOverflowAction> =
            if (supportsOpenWith) entries else listOf(REFRESH, DOWNLOAD)
    }
}

/**
 * How a file is presented on a phone (`Phone 3 · Sections 2, 4, 8, 9`, Requirement 8.1, 8.2):
 * - [CODE]: Code editor (`E1`, `E2`, `E9`, `E11`); a file of no known kind is read as text, and the server's
 *   answer decides whether it is [BINARY_UNREADABLE] (`code-editor.tsx` does the same)
 * - [IMAGE]: Image viewer (`V1`)
 * - [PDF]: Page-by-page PDF viewer (`V2`, Requirement 8.2)
 * - [MARKDOWN]: Markdown Preview / Code viewer (`V3`)
 * - [FILE_VIEWER]: the viewer the tablet shows the file in, in its phone layout (the web's editor below `md`):
 *   a CSV table, a Word document, audio, video, a SQLite database or an HTML preview ([phoneUsesFileViewer]).
 *   `xlsx`/`xlsm` tabs are taken first by [PhoneFileTabHost] to the read-only preview, `PhoneXlsxViewer`.
 * - [BINARY_UNREADABLE]: Raw binary that no device displays — shows
 *   `"This file is a binary format and cannot be displayed."` and `Download` (`Viewers — states`).
 */
enum class PhoneFileViewKind {
    CODE,
    IMAGE,
    PDF,
    MARKDOWN,
    FILE_VIEWER,
    BINARY_UNREADABLE,
}

private val PHONE_IMAGE_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif",
)

private val PHONE_MARKDOWN_EXTENSIONS: Set<String> = setOf(
    "md", "markdown", "mdx",
)

private val RAW_BINARY_EXTENSIONS: Set<String> = setOf(
    "exe", "dll", "so", "dylib", "bin", "o", "a", "class", "jar", "war",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst",
    "woff", "woff2", "ttf", "otf", "eot",
)

/** The tablet viewers a phone file tab shows through `FileBody` (`FileDocumentClassifier`'s kinds). */
private val PHONE_FILE_VIEWERS: Set<FileDocumentViewer> = setOf(
    FileDocumentViewer.CSV,
    FileDocumentViewer.DOCX,
    FileDocumentViewer.AUDIO,
    FileDocumentViewer.VIDEO,
    FileDocumentViewer.SQLITE,
    FileDocumentViewer.HTML,
)

/**
 * Whether [path] opens in the viewer the tablet uses for it ([PhoneFileViewKind.FILE_VIEWER]): the kinds
 * `code-editor.tsx` previews rather than edits, the same below `md` as above it, and a workbook.
 */
fun phoneUsesFileViewer(path: String?): Boolean {
    val name = path.orEmpty()
    if (name.isBlank()) return false
    val ext = name.substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext == "xlsx" || ext == "xlsm" || FileDocumentClassifier.classify(name) in PHONE_FILE_VIEWERS
}

/**
 * Classifies how [path] should open on a phone according to the phone rule
 * (`Phone 3 · Section 0, 4, 9`, Requirement 8.1, 8.2).
 */
fun classifyPhoneFileView(
    path: String?,
    inlineContent: String? = null,
    forceCode: Boolean = false,
    cannotDisplayBinary: Boolean = false,
): PhoneFileViewKind {
    if (cannotDisplayBinary) return PhoneFileViewKind.BINARY_UNREADABLE
    if (inlineContent != null || forceCode) return PhoneFileViewKind.CODE
    val fileName = path.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        ext == "pdf" -> PhoneFileViewKind.PDF
        ext in PHONE_IMAGE_EXTENSIONS -> PhoneFileViewKind.IMAGE
        ext in PHONE_MARKDOWN_EXTENSIONS -> PhoneFileViewKind.MARKDOWN
        phoneUsesFileViewer(path) -> PhoneFileViewKind.FILE_VIEWER
        ext in RAW_BINARY_EXTENSIONS -> PhoneFileViewKind.BINARY_UNREADABLE
        else -> PhoneFileViewKind.CODE
    }
}

/** Formats a byte count into a compact human-readable label (`24.1 KB`, `98.4 MB`). */
fun formatPhoneFileSize(bytes: Long): String {
    if (bytes < 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return if (kb >= 100.0) "${kb.toInt()} KB" else String.format(Locale.US, "%.1f KB", kb)
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return String.format(Locale.US, "%.1f MB", mb)
    }
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.1f GB", gb)
}

const val PHONE_BINARY_FILE_TITLE: String = "This file is a binary format and cannot be displayed."
