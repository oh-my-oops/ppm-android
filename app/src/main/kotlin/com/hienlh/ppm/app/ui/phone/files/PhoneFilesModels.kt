package com.hienlh.ppm.app.ui.phone.files

import java.text.NumberFormat
import java.util.Locale

/**
 * Pure models, rules, and classifiers for `Phone 3 · Files, editor, viewers, search`
 * (`designs/ppm-mobile-tablet-redesign/phone/3-files/index.html`, Requirements 8.1, 8.2).
 *
 * Implements the phone rule (Section 0, 1–4, 8, 9):
 * - A phone (< 600 dp) offers basic features only: browse the tree, open a file to view it
 *   (code, image, Markdown, PDF), make a small edit and Save, see a file's inline diff, and
 *   download any other file.
 * - Tablet-only actions (New File, New Folder, Upload, Rename, Delete, Cut/Copy/Paste, Multi-select,
 *   Drag to move, Compare, Find/Replace, Go to line, LSP, Blame, Merge conflict resolution, CSV/Excel/
 *   Word/Audio/Video/HTML viewers, Project Search, OS File Explorer) are omitted on a phone or show
 *   the design's single-line tablet notice.
 */

// ───────────────────────── Section 1 · Files (Project Tree) ─────────────────────────

/**
 * Overflow (`⋮`) menu items in the phone Files app bar (`Phone 3 · Section 0 & F1`, Note 3 & 65).
 * New File, New Folder, Upload, and Select are tablet-only and not offered on a phone.
 */
enum class PhoneFilesOverflowAction(
    val id: String,
    val label: String,
) {
    REVEAL_ACTIVE_FILE("reveal_active_file", "Reveal Active File"),
    COLLAPSE_ALL("collapse_all", "Collapse All"),
    REFRESH("refresh", "Refresh"),
    ;

    companion object {
        fun phoneMenu(): List<PhoneFilesOverflowAction> = entries
    }
}

/**
 * Long-press sheet actions for a file or folder in the phone project tree (`Phone 3 · Section 1`, Note 66).
 */
enum class PhoneTreeLongPressAction(
    val id: String,
    val label: String,
) {
    OPEN("open", "Open"),
    OPEN_IN_TERMINAL("open_in_terminal", "Open in Terminal"),
    COPY_PATH("copy_path", "Copy Path"),
    COPY_FULL_PATH("copy_full_path", "Copy Full Path"),
    DOWNLOAD("download", "Download"),
    NEW_FILE("new_file", "New File"),
    NEW_FOLDER("new_folder", "New Folder"),
    RENAME("rename", "Rename"),
    CUT("cut", "Cut"),
    PASTE("paste", "Paste"),
    DELETE("delete", "Delete"),
    ;

    companion object {
        const val TABLET_ONLY_FOOTER: String =
            "Rename, delete, move and compare: open on a tablet."

        /** The footer once the sheet offers the file operations itself. */
        const val COMPARE_TABLET_ONLY_FOOTER: String = "Compare: open on a tablet."

        /**
         * The old explorer's file operations (`ExplorerTreeMenu`): New File / New Folder inside a folder,
         * Rename, Cut (paste it into a folder to move it), Paste into a folder, Delete.
         */
        fun mutationActionsFor(isDirectory: Boolean, canPaste: Boolean): List<PhoneTreeLongPressAction> = buildList {
            if (isDirectory) {
                add(NEW_FILE)
                add(NEW_FOLDER)
            }
            add(RENAME)
            add(CUT)
            if (isDirectory && canPaste) add(PASTE)
            add(DELETE)
        }

        /** Actions offered when long-pressing a file or folder on a phone (Note 66). */
        fun actionsFor(isDirectory: Boolean): List<PhoneTreeLongPressAction> =
            if (isDirectory) {
                listOf(OPEN_IN_TERMINAL, COPY_PATH, COPY_FULL_PATH)
            } else {
                listOf(OPEN, COPY_PATH, COPY_FULL_PATH, DOWNLOAD)
            }
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
        const val SUBTITLE: String = "Open on a tablet to add files."
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
 * - [CODE]: Code editor (`E1`, `E2`, `E9`, `E11`)
 * - [IMAGE]: Image viewer (`V1`)
 * - [PDF]: Page-by-page PDF viewer (`V2`, Requirement 8.2)
 * - [MARKDOWN]: Markdown Preview / Code viewer (`V3`)
 * - [TABLET_ONLY_VIEWER]: Spreadsheets (`csv, tsv, xls`; `xlsx`/`xlsm` tabs are taken first by
 *   [PhoneFileTabHost] to the read-only preview, `PhoneXlsxViewer`), Word (`doc, docx`), Audio, Video,
 *   SQLite, or HTML preview — shows what the file is, `Download`, and
 *   `"Open on a tablet to view or edit it."` (`V9`, Note 41)
 * - [BINARY_UNREADABLE]: Raw binary that no device displays — shows
 *   `"This file is a binary format and cannot be displayed."` and `Download` (`Viewers — states`).
 */
enum class PhoneFileViewKind {
    CODE,
    IMAGE,
    PDF,
    MARKDOWN,
    TABLET_ONLY_VIEWER,
    BINARY_UNREADABLE,
}

private val PHONE_IMAGE_EXTENSIONS: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif",
)

private val PHONE_MARKDOWN_EXTENSIONS: Set<String> = setOf(
    "md", "markdown", "mdx",
)

private val TABLET_ONLY_VIEWER_EXTENSIONS: Map<String, String> = mapOf(
    "csv" to "CSV table",
    "tsv" to "TSV table",
    "xls" to "Excel spreadsheet",
    "xlsx" to "Excel spreadsheet",
    "doc" to "Word document",
    "docx" to "Word document",
    "mp3" to "Audio file",
    "wav" to "Audio file",
    "ogg" to "Audio file",
    "flac" to "Audio file",
    "m4a" to "Audio file",
    "aac" to "Audio file",
    "mp4" to "Video file",
    "webm" to "Video file",
    "mov" to "Video file",
    "mkv" to "Video file",
    "avi" to "Video file",
    "sqlite" to "SQLite database",
    "sqlite3" to "SQLite database",
    "db" to "SQLite database",
)

private val RAW_BINARY_EXTENSIONS: Set<String> = setOf(
    "exe", "dll", "so", "dylib", "bin", "o", "a", "class", "jar", "war",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst",
    "woff", "woff2", "ttf", "otf", "eot",
)

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
        ext in TABLET_ONLY_VIEWER_EXTENSIONS -> PhoneFileViewKind.TABLET_ONLY_VIEWER
        ext in RAW_BINARY_EXTENSIONS -> PhoneFileViewKind.BINARY_UNREADABLE
        else -> PhoneFileViewKind.CODE
    }
}

/** Human-readable file kind label for `V9 · A file the phone does not open` (Note 41). */
fun describePhoneFileKind(path: String?): String {
    val fileName = path.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return TABLET_ONLY_VIEWER_EXTENSIONS[ext]
        ?: if (ext.isNotEmpty()) "${ext.uppercase(Locale.ROOT)} file" else "Binary file"
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

const val PHONE_UNSUPPORTED_FILE_LINE: String = "Open on a tablet to view or edit it."
const val PHONE_BINARY_FILE_TITLE: String = "This file is a binary format and cannot be displayed."
