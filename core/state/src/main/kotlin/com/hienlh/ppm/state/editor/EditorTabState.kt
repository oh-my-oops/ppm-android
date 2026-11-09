package com.hienlh.ppm.state.editor

/**
 * The 13 selectable language IDs in `src/web/components/editor/code-editor.tsx` (`LANGUAGE_OPTIONS`).
 */
val EDITOR_LANGUAGE_OPTIONS: List<Pair<String, String>> = listOf(
    "plaintext" to "Plain Text",
    "javascript" to "JavaScript",
    "typescript" to "TypeScript",
    "python" to "Python",
    "html" to "HTML",
    "css" to "CSS",
    "scss" to "SCSS",
    "json" to "JSON",
    "markdown" to "Markdown",
    "yaml" to "YAML",
    "shell" to "Shell",
    "sql" to "SQL",
    "dotenv" to "Dotenv (.env)",
)

/**
 * The 18 quick-insert symbols on the mobile soft-keyboard bar (`code-editor.tsx`), rendered at 44dp height.
 */
val MOBILE_EDITOR_SYMBOLS: List<String> = listOf(
    "(", ")", "{", "}", "[", "]", "<", ">", ";", ":", "=", "\"", "'", "`", "/", "\\", "_", "#",
)

/**
 * Tracks dirty/autosave/external-change state for a single editor tab (`phase-07b`).
 */
class EditorTabDocumentState(
    initialContent: String,
    language: String = "plaintext",
    val isUntitled: Boolean = false,
    readOnly: Boolean = false,
) {
    val session = EditorSessionState(
        initialText = initialContent,
        language = language,
        readOnly = readOnly,
    )

    private var lastSavedContent: String = session.buffer.toString()
    private var lastSavedEol: String = session.buffer.eol
    private var lastSavedVersion: Long = session.buffer.version
    private var lastCheckedVersion: Long = session.buffer.version
    private var cachedDirty: Boolean = false

    var saving: Boolean = false
    var saveError: String? = null
    var externalChangeConflict: Boolean = false
        private set

    val isDirty: Boolean
        get() {
            if (isUntitled) return session.buffer.length > 0
            if (session.buffer.eol != lastSavedEol) return true
            if (session.buffer.version == lastSavedVersion) return false
            if (session.buffer.length != lastSavedContent.length) return true
            if (session.buffer.version != lastCheckedVersion) {
                lastCheckedVersion = session.buffer.version
                cachedDirty = !session.buffer.contentEquals(lastSavedContent)
                if (!cachedDirty) {
                    lastSavedVersion = session.buffer.version
                }
            }
            return cachedDirty
        }

    /** Serializes the buffer with the detected file EOL (`\r\n`, `\r`, or `\n`) for writing to disk. */
    fun textToSave(): String = session.buffer.toDiskString()

    /** Marks the current buffer content as saved on disk. */
    fun markSaved(savedText: String = textToSave()) {
        val norm = savedText.replace("\r\n", "\n").replace('\r', '\n')
        lastSavedContent = norm
        lastSavedEol = detectEol(savedText, fallback = session.buffer.eol)
        lastCheckedVersion = session.buffer.version
        cachedDirty = !session.buffer.contentEquals(lastSavedContent) || session.buffer.eol != lastSavedEol
        if (!cachedDirty) {
            lastSavedVersion = session.buffer.version
        }
        saving = false
        saveError = null
        externalChangeConflict = false
    }

    /**
     * Handles an incoming `file:changed` reload from disk:
     * - If buffer is clean (`!isDirty`), reloads the buffer with [diskContent] and clears undo history.
     * - If buffer is dirty and [diskContent] matches the current buffer, marks saved.
     * - If buffer is dirty and [diskContent] differs, preserves the user's unsaved edits and sets
     *   [externalChangeConflict] = true so the user is notified instead of losing work.
     */
    fun onExternalDiskContent(diskContent: String): Boolean {
        val diskEol = detectEol(diskContent, fallback = session.buffer.eol)
        val norm = diskContent.replace("\r\n", "\n").replace('\r', '\n')
        if (!isDirty) {
            if (norm != lastSavedContent || diskEol != lastSavedEol) {
                session.reloadExternalContent(diskContent)
                lastSavedContent = norm
                lastSavedEol = session.buffer.eol
                lastSavedVersion = session.buffer.version
                lastCheckedVersion = session.buffer.version
                cachedDirty = false
                externalChangeConflict = false
                return true
            }
            return false
        }
        if (session.buffer.contentEquals(norm) && diskEol == session.buffer.eol) {
            markSaved(diskContent)
            return false
        }
        externalChangeConflict = true
        return false
    }

    /** Formats a tab title with `" ●"` suffix when dirty, matching web `code-editor.tsx`. */
    fun formatTabTitle(baseTitle: String): String =
        if (isDirty) "$baseTitle ●" else baseTitle
}

/**
 * Formats selected lines as a chat context block (`path:start-end` + fenced code block)
 * for "Add to current chat" / "Add to new chat" (`phase-07b.4`).
 */
fun buildAiEditorContextSnippet(
    filePath: String,
    language: String,
    startLineOneBased: Int,
    endLineOneBased: Int,
    selectedText: String,
): String {
    val rangeLabel = if (startLineOneBased == endLineOneBased) {
        "$filePath:$startLineOneBased"
    } else {
        "$filePath:$startLineOneBased-$endLineOneBased"
    }
    val fenceLang = if (language == "plaintext" || language == "text") "" else language
    return "$rangeLabel\n```$fenceLang\n${selectedText.trimEnd()}\n```"
}
