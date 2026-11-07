package com.hienlh.ppm.state.editor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

// ── LSP Geometry & Severity Constants ──────────────────────────────────────

const val SEVERITY_ERROR = 1
const val SEVERITY_WARNING = 2
const val SEVERITY_INFO = 3
const val SEVERITY_HINT = 4

/**
 * 0-based line and UTF-16 character offset, per LSP `Position` specification
 * (`positionEncodings: ["utf-16"]`).
 */
data class LspPosition(
    val line: Int,
    val character: Int,
)

data class LspRange(
    val start: LspPosition,
    val end: LspPosition,
)

/** Convert 1-based editor (lineNumber, column) to 0-based LSP position. */
fun toLspPosition(lineNumber: Int, column: Int): LspPosition =
    LspPosition(line = (lineNumber - 1).coerceAtLeast(0), character = (column - 1).coerceAtLeast(0))

/** Convert 0-based LSP position to 1-based editor (lineNumber, column). */
fun fromLspPosition(position: LspPosition): Pair<Int, Int> =
    (position.line + 1) to (position.character + 1)

/**
 * Safely clamp a UTF-16 character index within [lineText] so it never lands inside a surrogate pair.
 * Since Kotlin [String] uses UTF-16 code units (`Char`), `lineText.length` is already the UTF-16 length.
 */
fun utf16ColumnToCharOffset(lineText: String, utf16Character: Int): Int {
    val clamped = utf16Character.coerceIn(0, lineText.length)
    if (clamped > 0 && clamped < lineText.length &&
        Character.isHighSurrogate(lineText[clamped - 1]) &&
        Character.isLowSurrogate(lineText[clamped])
    ) {
        return clamped - 1
    }
    return clamped
}

/** Convert a Unicode code-point index on [lineText] into a UTF-16 character offset for LSP. */
fun codePointColumnToUtf16(lineText: String, codePointIndex: Int): Int {
    if (codePointIndex <= 0 || lineText.isEmpty()) return 0
    val totalCodePoints = lineText.codePointCount(0, lineText.length)
    val clampedCp = codePointIndex.coerceIn(0, totalCodePoints)
    return lineText.offsetByCodePoints(0, clampedCp)
}

// ── Kind Tables (Mapped by Name, Never Raw Number Offset) ──────────────────

val COMPLETION_KIND_NAMES: Map<Int, String> = mapOf(
    1 to "Text", 2 to "Method", 3 to "Function", 4 to "Constructor", 5 to "Field",
    6 to "Variable", 7 to "Class", 8 to "Interface", 9 to "Module", 10 to "Property",
    11 to "Unit", 12 to "Value", 13 to "Enum", 14 to "Keyword", 15 to "Snippet",
    16 to "Color", 17 to "File", 18 to "Reference", 19 to "Folder", 20 to "EnumMember",
    21 to "Constant", 22 to "Struct", 23 to "Event", 24 to "Operator", 25 to "TypeParameter",
)

val SYMBOL_KIND_NAMES: Map<Int, String> = mapOf(
    1 to "File", 2 to "Module", 3 to "Namespace", 4 to "Package", 5 to "Class",
    6 to "Method", 7 to "Property", 8 to "Field", 9 to "Constructor", 10 to "Enum",
    11 to "Interface", 12 to "Function", 13 to "Variable", 14 to "Constant", 15 to "String",
    16 to "Number", 17 to "Boolean", 18 to "Array", 19 to "Object", 20 to "Key",
    21 to "Null", 22 to "EnumMember", 23 to "Struct", 24 to "Event", 25 to "Operator",
    26 to "TypeParameter",
)

val SEVERITY_NAMES: Map<Int, String> = mapOf(
    1 to "Error", 2 to "Warning", 3 to "Info", 4 to "Hint",
)

val HIGHLIGHT_KIND_NAMES: Map<Int, String> = mapOf(
    1 to "Text", 2 to "Read", 3 to "Write",
)

fun completionKindName(kind: Int?): String = COMPLETION_KIND_NAMES[kind ?: 0] ?: "Text"
fun symbolKindName(kind: Int?): String = SYMBOL_KIND_NAMES[kind ?: 0] ?: "Variable"
fun severityName(severity: Int?): String = SEVERITY_NAMES[severity ?: 0] ?: "Warning"
fun highlightKindName(kind: Int?): String = HIGHLIGHT_KIND_NAMES[kind ?: 0] ?: "Text"

// ── Server Status & Diagnostics Models ─────────────────────────────────────

data class LspServerInfo(
    val id: String,
    val displayName: String,
    val rootPath: String,
)

data class LspMissingServer(
    val id: String,
    val displayName: String,
    val installHint: String,
    val installable: Boolean,
    val installWith: String? = null,
)

sealed interface LspDocumentStatus {
    data object Opening : LspDocumentStatus

    data class Ready(
        val languageId: String,
        val server: LspServerInfo,
        val projectPath: String,
        val capabilities: JsonObject = JsonObject(emptyMap()),
    ) : LspDocumentStatus

    data class Unavailable(
        val reason: String, // "no-language" | "not-installed" | "failed"
        val server: LspMissingServer?,
        val message: String,
    ) : LspDocumentStatus
}

data class LspDiagnostic(
    val message: String,
    val severity: Int? = null,
    val source: String? = null,
    val code: String? = null,
    val range: LspRange,
) {
    /**
     * Phase 07c requirement: missing severity is treated consistently as Warning (2)
     * across editor squiggles, LSP status chip, and the Problems dock tab.
     */
    val effectiveSeverity: Int
        get() = severity ?: SEVERITY_WARNING

    /** Formats e.g. `ts(2322) ` or `eslint ` for the Problems row. */
    fun sourceLabel(): String {
        val src = source?.takeIf { it.isNotBlank() } ?: return ""
        return if (code.isNullOrBlank()) "$src " else "$src($code) "
    }
}

// ── Install Notes (Port of `src/web/components/editor/lsp-status.tsx`) ─────

fun lspInstallNote(installWith: String?): String = when (installWith) {
    "go" -> "PPM builds it with the Go on this machine and keeps the binary in its own folder, leaving your GOBIN alone. A first build takes a minute or two."
    "rustup" -> "PPM asks rustup to add the component to the toolchain this project uses — the same as running the command below yourself."
    "download" -> "PPM downloads the server's own release, checks it against a pinned checksum and keeps it in its own folder. Nothing on your system changes."
    "gem" -> "PPM installs the gem with the Ruby on this machine into its own folder, leaving your gems alone. It takes a minute or so."
    else -> "PPM downloads it from npm into its own folder. Nothing you installed globally changes, and this file picks it up without being reopened."
}

fun lspCannotInstallNote(installWith: String?): String = when (installWith) {
    "go" -> "Install it, then reopen this file. PPM installs language servers but never toolchains, and it cannot find a Go on this host."
    "rustup" -> "Install it, then reopen this file. PPM installs language servers but never toolchains, and it cannot find rustup on this host."
    "gem" -> "Install it, then reopen this file. PPM installs this one with the machine's Ruby, and it cannot use one on this host."
    "download" -> "Install it, then reopen this file. Its project publishes no build for this machine, or there is no unzip here to unpack one."
    "bun" -> "Install it, then reopen this file. PPM installs this one with bun, and it cannot find bun on this host."
    else -> "Install it, then reopen this file."
}

// ── Completion, Edits, Navigation & Semantic Tokens ────────────────────────

data class LspTextEdit(
    val range: LspRange,
    val newText: String,
)

data class LspCompletionEntry(
    val label: String,
    val detail: String? = null,
    val description: String? = null,
    val kindName: String = "Text",
    val insertText: String,
    val isSnippet: Boolean = false,
    val replaceRange: LspRange? = null,
    val documentationMarkdown: String? = null,
    val deprecated: Boolean = false,
    val sortText: String? = null,
    val filterText: String? = null,
    val additionalTextEdits: List<LspTextEdit> = emptyList(),
)

data class LspLocationTarget(
    val uri: String,
    val range: LspRange,
)

data class LspDocumentSymbolNode(
    val name: String,
    val detail: String,
    val kindName: String,
    val range: LspRange,
    val selectionRange: LspRange,
    val children: List<LspDocumentSymbolNode> = emptyList(),
)

data class SemanticTokensLegend(
    val tokenTypes: List<String>,
    val tokenModifiers: List<String>,
)

data class DecodedSemanticToken(
    val line: Int,
    val startChar: Int,
    val length: Int,
    val tokenType: String,
    val modifiers: List<String>,
)

data class SemanticTokensDeltaEdit(
    val start: Int,
    val deleteCount: Int,
    val data: IntArray = IntArray(0),
)

/**
 * Expands LSP snippet syntax (`${1:default}`, `${1}`, `$1`, `$0`, `\$`) into plain text
 * and returns `(expandedText, cursorOffsetWithinExpandedText)`.
 */
fun expandSnippetPlaceholders(snippet: String): Pair<String, Int> {
    val sb = StringBuilder()
    var firstCursorOffset: Int? = null
    var zeroCursorOffset: Int? = null
    var i = 0
    while (i < snippet.length) {
        val ch = snippet[i]
        if (ch == '\\' && i + 1 < snippet.length) {
            sb.append(snippet[i + 1])
            i += 2
            continue
        }
        if (ch == '$') {
            if (i + 1 < snippet.length && snippet[i + 1] == '{') {
                val closeIdx = snippet.indexOf('}', i + 2)
                if (closeIdx > i + 2) {
                    val body = snippet.substring(i + 2, closeIdx)
                    val colonIdx = body.indexOf(':')
                    val tabstopNum = (if (colonIdx >= 0) body.substring(0, colonIdx) else body).toIntOrNull()
                    val placeholder = if (colonIdx >= 0) body.substring(colonIdx + 1) else ""
                    if (tabstopNum != null) {
                        val pos = sb.length + placeholder.length
                        if (tabstopNum == 0) {
                            if (zeroCursorOffset == null) zeroCursorOffset = pos
                        } else if (firstCursorOffset == null) {
                            firstCursorOffset = pos
                        }
                        sb.append(placeholder)
                        i = closeIdx + 1
                        continue
                    }
                }
            } else {
                var j = i + 1
                while (j < snippet.length && snippet[j].isDigit()) j++
                if (j > i + 1) {
                    val tabstopNum = snippet.substring(i + 1, j).toIntOrNull()
                    if (tabstopNum != null) {
                        if (tabstopNum == 0) {
                            if (zeroCursorOffset == null) zeroCursorOffset = sb.length
                        } else if (firstCursorOffset == null) {
                            firstCursorOffset = sb.length
                        }
                        i = j
                        continue
                    }
                }
            }
        }
        sb.append(ch)
        i++
    }
    val finalCursor = firstCursorOffset ?: zeroCursorOffset ?: sb.length
    return sb.toString() to finalCursor
}

/**
 * Order edits bottom-up so applying them sequentially never shifts earlier offsets.
 */
fun sortEditsBottomUp(edits: List<LspTextEdit>): List<LspTextEdit> =
    edits.sortedWith { a, b ->
        val lineCmp = b.range.start.line.compareTo(a.range.start.line)
        if (lineCmp != 0) lineCmp else b.range.start.character.compareTo(a.range.start.character)
    }

/** Apply a batch of 0-based UTF-16 [LspTextEdit]s to [text] safely from bottom to top. */
fun applyLspTextEdits(text: String, edits: List<LspTextEdit>): String {
    if (edits.isEmpty()) return text
    val buffer = EditorBuffer(text)
    for (edit in sortEditsBottomUp(edits)) {
        val startLineText = buffer.line(edit.range.start.line)
        val endLineText = buffer.line(edit.range.end.line)
        val startCol = utf16ColumnToCharOffset(startLineText, edit.range.start.character)
        val endCol = utf16ColumnToCharOffset(endLineText, edit.range.end.character)
        val startOffset = buffer.lineColToOffset(edit.range.start.line, startCol)
        val endOffset = buffer.lineColToOffset(edit.range.end.line, endCol)
        buffer.replace(startOffset, endOffset, edit.newText)
    }
    return buffer.toString()
}

// ── Cross-file Definition Routing (Port of `lsp-navigation.ts`) ────────────

sealed interface LspNavigationAction {
    data class SameFile(val range: LspRange) : LspNavigationAction
    data class OpenProjectFile(
        val relativePath: String,
        val projectName: String,
        val lineNumber: Int,
    ) : LspNavigationAction
    data object IgnoreOutsideProject : LspNavigationAction
}

fun fileUriToPath(uri: String): String? {
    if (!uri.startsWith("file://")) return null
    return try {
        val rawPath = URI(uri).rawPath ?: return null
        val decoded = URLDecoder.decode(rawPath, "UTF-8")
        // Windows file:///C:/foo -> C:/foo
        if (decoded.length >= 3 && decoded[0] == '/' && decoded[1].isLetter() && decoded[2] == ':') {
            decoded.substring(1)
        } else {
            decoded
        }
    } catch (_: Throwable) {
        null
    }
}

fun relativeToProject(projectPath: String, absolutePath: String): String? {
    val root = projectPath.replace('\\', '/').trimEnd('/')
    val path = absolutePath.replace('\\', '/')
    // Case-insensitive Windows drive letter check
    val rootNorm = if (root.length >= 2 && root[1] == ':') root[0].lowercaseChar() + root.substring(1) else root
    val pathNorm = if (path.length >= 2 && path[1] == ':') path[0].lowercaseChar() + path.substring(1) else path
    if (!pathNorm.startsWith("$rootNorm/")) return null
    return path.substring(root.length + 1)
}

fun resolveDefinitionAction(
    target: LspLocationTarget,
    currentClientUri: String,
    projectPath: String?,
    projectName: String,
): LspNavigationAction {
    if (target.uri == currentClientUri) {
        return LspNavigationAction.SameFile(target.range)
    }
    if (projectPath.isNullOrBlank()) return LspNavigationAction.IgnoreOutsideProject
    val absolute = fileUriToPath(target.uri) ?: return LspNavigationAction.IgnoreOutsideProject
    val relative = relativeToProject(projectPath, absolute) ?: return LspNavigationAction.IgnoreOutsideProject
    return LspNavigationAction.OpenProjectFile(
        relativePath = relative,
        projectName = projectName,
        lineNumber = target.range.start.line + 1,
    )
}

// ── Semantic Tokens Decoding ───────────────────────────────────────────────

fun decodeSemanticTokens(data: IntArray, legend: SemanticTokensLegend): List<DecodedSemanticToken> {
    if (data.size < 5 || legend.tokenTypes.isEmpty()) return emptyList()
    val result = ArrayList<DecodedSemanticToken>(data.size / 5)
    var line = 0
    var startChar = 0
    var i = 0
    while (i + 4 < data.size) {
        val deltaLine = data[i]
        val deltaStart = data[i + 1]
        val length = data[i + 2]
        val tokenTypeIdx = data[i + 3]
        val tokenModsBits = data[i + 4]
        if (deltaLine > 0) {
            line += deltaLine
            startChar = deltaStart
        } else {
            startChar += deltaStart
        }
        val tokenType = legend.tokenTypes.getOrNull(tokenTypeIdx)
        if (tokenType != null && length > 0) {
            val mods = if (tokenModsBits == 0 || legend.tokenModifiers.isEmpty()) {
                emptyList()
            } else {
                legend.tokenModifiers.mapIndexedNotNull { bit, mod ->
                    if ((tokenModsBits and (1 shl bit)) != 0) mod else null
                }
            }
            result.add(
                DecodedSemanticToken(
                    line = line,
                    startChar = startChar,
                    length = length,
                    tokenType = tokenType,
                    modifiers = mods,
                ),
            )
        }
        i += 5
    }
    return result
}

fun applySemanticTokensDelta(previous: IntArray, edits: List<SemanticTokensDeltaEdit>): IntArray {
    if (edits.isEmpty()) return previous
    val list = previous.toMutableList()
    // Apply in reverse start order so indices remain valid
    for (edit in edits.sortedByDescending { it.start }) {
        val start = edit.start.coerceIn(0, list.size)
        val end = (start + edit.deleteCount.coerceAtLeast(0)).coerceIn(start, list.size)
        list.subList(start, end).clear()
        if (edit.data.isNotEmpty()) {
            list.addAll(start, edit.data.toList())
        }
    }
    return list.toIntArray()
}

// ── JSON Parsing Helpers for LSP Responses ─────────────────────────────────

private val lspJson = Json { ignoreUnknownKeys = true }

fun parseLspRange(obj: JsonObject?): LspRange? {
    if (obj == null) return null
    val startObj = obj["start"] as? JsonObject ?: return null
    val endObj = obj["end"] as? JsonObject ?: return null
    val sLine = (startObj["line"] as? JsonPrimitive)?.intOrNull ?: return null
    val sChar = (startObj["character"] as? JsonPrimitive)?.intOrNull ?: return null
    val eLine = (endObj["line"] as? JsonPrimitive)?.intOrNull ?: return null
    val eChar = (endObj["character"] as? JsonPrimitive)?.intOrNull ?: return null
    return LspRange(LspPosition(sLine, sChar), LspPosition(eLine, eChar))
}

fun parseLspMarkup(element: JsonElement?): String? {
    if (element == null || element is JsonNull) return null
    return when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { parseLspMarkup(it) }.filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
        is JsonObject -> {
            val lang = (element["language"] as? JsonPrimitive)?.contentOrNull
            val value = (element["value"] as? JsonPrimitive)?.contentOrNull ?: return null
            if (!lang.isNullOrBlank()) "```$lang\n$value\n```" else value
        }
    }
}

fun parseLspCompletions(result: JsonElement?): List<LspCompletionEntry> {
    if (result == null || result is JsonNull) return emptyList()
    val itemsArray: JsonArray = when (result) {
        is JsonArray -> result
        is JsonObject -> (result["items"] as? JsonArray) ?: return emptyList()
        else -> return emptyList()
    }
    return itemsArray.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val labelEl = obj["label"]
        var label = ""
        var labelDetail: String? = null
        var labelDesc: String? = null
        when (labelEl) {
            is JsonPrimitive -> label = labelEl.contentOrNull.orEmpty()
            is JsonObject -> {
                label = (labelEl["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                labelDetail = (labelEl["detail"] as? JsonPrimitive)?.contentOrNull
                labelDesc = (labelEl["description"] as? JsonPrimitive)?.contentOrNull
            }
            else -> {}
        }
        if (label.isEmpty()) return@mapNotNull null
        val kind = (obj["kind"] as? JsonPrimitive)?.intOrNull
        val detail = (obj["detail"] as? JsonPrimitive)?.contentOrNull ?: labelDetail
        val textEditObj = obj["textEdit"] as? JsonObject
        val textEditNewText = (textEditObj?.get("newText") as? JsonPrimitive)?.contentOrNull
        val replaceRange = parseLspRange(
            (textEditObj?.get("replace") as? JsonObject)
                ?: (textEditObj?.get("range") as? JsonObject)
                ?: (textEditObj?.get("insert") as? JsonObject),
        )
        val insertText = textEditNewText
            ?: (obj["insertText"] as? JsonPrimitive)?.contentOrNull
            ?: label
        val insertTextFormat = (obj["insertTextFormat"] as? JsonPrimitive)?.intOrNull
        val tags = (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        val additionalEdits = (obj["additionalTextEdits"] as? JsonArray)?.mapNotNull { editEl ->
            val editObj = editEl as? JsonObject ?: return@mapNotNull null
            val r = parseLspRange(editObj["range"] as? JsonObject) ?: return@mapNotNull null
            val nt = (editObj["newText"] as? JsonPrimitive)?.contentOrNull ?: ""
            LspTextEdit(r, nt)
        }.orEmpty()
        LspCompletionEntry(
            label = label,
            detail = detail,
            description = labelDesc,
            kindName = completionKindName(kind),
            insertText = insertText,
            isSnippet = insertTextFormat == 2,
            replaceRange = replaceRange,
            documentationMarkdown = parseLspMarkup(obj["documentation"]),
            deprecated = 1 in tags,
            sortText = (obj["sortText"] as? JsonPrimitive)?.contentOrNull,
            filterText = (obj["filterText"] as? JsonPrimitive)?.contentOrNull,
            additionalTextEdits = additionalEdits,
        )
    }
}

fun parseLspLocations(result: JsonElement?): List<LspLocationTarget> {
    if (result == null || result is JsonNull) return emptyList()
    val list = when (result) {
        is JsonArray -> result
        is JsonObject -> JsonArray(listOf(result))
        else -> return emptyList()
    }
    return list.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        if ("targetUri" in obj) {
            val uri = (obj["targetUri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val range = parseLspRange(
                (obj["targetSelectionRange"] as? JsonObject) ?: (obj["targetRange"] as? JsonObject),
            ) ?: return@mapNotNull null
            LspLocationTarget(uri, range)
        } else {
            val uri = (obj["uri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val range = parseLspRange(obj["range"] as? JsonObject) ?: return@mapNotNull null
            LspLocationTarget(uri, range)
        }
    }
}

fun parseLspTextEdits(result: JsonElement?): List<LspTextEdit> {
    val arr = result as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val range = parseLspRange(obj["range"] as? JsonObject) ?: return@mapNotNull null
        val newText = (obj["newText"] as? JsonPrimitive)?.contentOrNull ?: ""
        LspTextEdit(range, newText)
    }
}

fun parseLspWorkspaceEdit(result: JsonElement?): Map<String, List<LspTextEdit>> {
    val obj = result as? JsonObject ?: return emptyMap()
    val out = LinkedHashMap<String, MutableList<LspTextEdit>>()
    val docChanges = obj["documentChanges"] as? JsonArray
    if (docChanges != null) {
        for (el in docChanges) {
            val changeObj = el as? JsonObject ?: continue
            val uri = ((changeObj["textDocument"] as? JsonObject)?.get("uri") as? JsonPrimitive)?.contentOrNull ?: continue
            val edits = parseLspTextEdits(changeObj["edits"])
            if (edits.isNotEmpty()) out.getOrPut(uri) { mutableListOf() }.addAll(edits)
        }
        return out
    }
    val changesObj = obj["changes"] as? JsonObject ?: return emptyMap()
    for ((uri, editsEl) in changesObj) {
        val edits = parseLspTextEdits(editsEl)
        if (edits.isNotEmpty()) out.getOrPut(uri) { mutableListOf() }.addAll(edits)
    }
    return out
}

// ── LSP Client Session State Machine (Port of `src/web/lib/lsp/lsp-client.ts`) ─

data class RegisteredLspDocument(
    val path: String,
    val clientUri: String,
    var version: Int,
    var currentText: String,
    var status: LspDocumentStatus = LspDocumentStatus.Opening,
    var diagnostics: List<LspDiagnostic> = emptyList(),
)

sealed interface LspIncomingEvent {
    data class ReopenAll(val outboundPayloads: List<String>) : LspIncomingEvent
    data class StatusChanged(val path: String, val status: LspDocumentStatus) : LspIncomingEvent
    data class ResyncRequested(val path: String, val outboundPayload: String?) : LspIncomingEvent
    data class DiagnosticsPublished(
        val path: String,
        val clientUri: String,
        val diagnostics: List<LspDiagnostic>,
    ) : LspIncomingEvent
    data class ResponseReceived(val id: Int, val result: JsonElement?) : LspIncomingEvent
    data class ErrorReceived(val id: Int, val message: String) : LspIncomingEvent
    data class NoticeReceived(val message: String) : LspIncomingEvent
}

/**
 * Pure-JVM state machine managing open documents, monotonic document versions,
 * `hello` reopen, `resync` full-text recovery, and `retryUnavailableDocuments`.
 */
class LspClientSessionState(val projectName: String) {
    private val documents = LinkedHashMap<String, RegisteredLspDocument>()
    private var nextRequestId = 1

    fun openDocument(path: String, clientUri: String, version: Int, text: String): String {
        val safeVersion = version.coerceAtLeast(1)
        documents[path] = RegisteredLspDocument(
            path = path,
            clientUri = clientUri,
            version = safeVersion,
            currentText = text,
            status = LspDocumentStatus.Opening,
        )
        return buildOpenMessage(path, safeVersion, text, clientUri)
    }

    fun changeDocumentFull(path: String, text: String): String? {
        val doc = documents[path] ?: return null
        doc.version += 1
        doc.currentText = text
        return buildJsonObject {
            put("t", "change")
            put("path", path)
            put("version", doc.version)
            put("text", text)
        }.toString()
    }

    fun saveDocument(path: String, text: String): String? {
        val doc = documents[path] ?: return null
        doc.currentText = text
        return buildJsonObject {
            put("t", "save")
            put("path", path)
            put("text", text)
        }.toString()
    }

    fun closeDocument(path: String): String? {
        documents.remove(path) ?: return null
        return buildJsonObject {
            put("t", "close")
            put("path", path)
        }.toString()
    }

    fun createRequest(path: String, method: String, params: JsonObject): Pair<Int, String> {
        val id = nextRequestId++
        val payload = buildJsonObject {
            put("t", "request")
            put("id", id)
            put("path", path)
            put("method", method)
            put("params", params)
        }.toString()
        return id to payload
    }

    fun createCancel(id: Int): String = buildJsonObject {
        put("t", "cancel")
        put("id", id)
    }.toString()

    fun statusOf(path: String): LspDocumentStatus? = documents[path]?.status
    fun diagnosticsOf(path: String): List<LspDiagnostic> = documents[path]?.diagnostics.orEmpty()
    fun documentOf(path: String): RegisteredLspDocument? = documents[path]

    /**
     * Re-open every document currently registered as `Unavailable` (e.g. after pressing Install).
     */
    fun retryUnavailableDocuments(): List<String> {
        val payloads = mutableListOf<String>()
        for (doc in documents.values) {
            if (doc.status is LspDocumentStatus.Unavailable) {
                doc.status = LspDocumentStatus.Opening
                payloads.add(buildOpenMessage(doc.path, doc.version, doc.currentText, doc.clientUri))
            }
        }
        return payloads
    }

    /**
     * Handle an incoming JSON frame from `/ws/project/:n/lsp`.
     */
    fun onServerMessage(rawJson: String): LspIncomingEvent? {
        val root = try {
            lspJson.parseToJsonElement(rawJson).jsonObject
        } catch (_: Throwable) {
            return null
        }
        return when ((root["t"] as? JsonPrimitive)?.contentOrNull) {
            "hello" -> {
                val opens = documents.values.map { doc ->
                    doc.status = LspDocumentStatus.Opening
                    buildOpenMessage(doc.path, doc.version, doc.currentText, doc.clientUri)
                }
                LspIncomingEvent.ReopenAll(opens)
            }
            "ready" -> {
                val path = (root["path"] as? JsonPrimitive)?.contentOrNull ?: return null
                val languageId = (root["languageId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val projectPath = (root["projectPath"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val srvObj = root["server"] as? JsonObject
                val server = LspServerInfo(
                    id = (srvObj?.get("id") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    displayName = (srvObj?.get("displayName") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    rootPath = (srvObj?.get("rootPath") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
                val capabilities = (root["capabilities"] as? JsonObject) ?: JsonObject(emptyMap())
                val status = LspDocumentStatus.Ready(languageId, server, projectPath, capabilities)
                documents[path]?.status = status
                LspIncomingEvent.StatusChanged(path, status)
            }
            "unavailable" -> {
                val path = (root["path"] as? JsonPrimitive)?.contentOrNull ?: return null
                val reason = (root["reason"] as? JsonPrimitive)?.contentOrNull ?: "failed"
                val message = (root["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val srvObj = root["server"] as? JsonObject
                val missingServer = srvObj?.let {
                    LspMissingServer(
                        id = (it["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        displayName = (it["displayName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        installHint = (it["installHint"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        installable = (it["installable"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        installWith = (it["installWith"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
                val status = LspDocumentStatus.Unavailable(reason, missingServer, message)
                documents[path]?.status = status
                LspIncomingEvent.StatusChanged(path, status)
            }
            "resync" -> {
                val path = (root["path"] as? JsonPrimitive)?.contentOrNull ?: return null
                val doc = documents[path]
                val payload = doc?.let {
                    buildJsonObject {
                        put("t", "change")
                        put("path", it.path)
                        put("version", it.version)
                        put("text", it.currentText)
                    }.toString()
                }
                LspIncomingEvent.ResyncRequested(path, payload)
            }
            "response" -> {
                val id = (root["id"] as? JsonPrimitive)?.intOrNull ?: return null
                LspIncomingEvent.ResponseReceived(id, root["result"])
            }
            "error" -> {
                val id = (root["id"] as? JsonPrimitive)?.intOrNull ?: return null
                val msg = (root["message"] as? JsonPrimitive)?.contentOrNull ?: "LSP request error"
                LspIncomingEvent.ErrorReceived(id, msg)
            }
            "notice" -> {
                val msg = (root["message"] as? JsonPrimitive)?.contentOrNull ?: return null
                LspIncomingEvent.NoticeReceived(msg)
            }
            "notification" -> {
                val method = (root["method"] as? JsonPrimitive)?.contentOrNull ?: return null
                if (method != "textDocument/publishDiagnostics") return null
                val params = root["params"] as? JsonObject ?: return null
                val uri = (params["uri"] as? JsonPrimitive)?.contentOrNull ?: return null
                val doc = documents.values.firstOrNull { it.clientUri == uri } ?: return null
                val diagArr = params["diagnostics"] as? JsonArray ?: JsonArray(emptyList())
                val parsed = diagArr.mapNotNull { el ->
                    val dObj = el as? JsonObject ?: return@mapNotNull null
                    val range = parseLspRange(dObj["range"] as? JsonObject) ?: return@mapNotNull null
                    val message = (dObj["message"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val severity = (dObj["severity"] as? JsonPrimitive)?.intOrNull
                    val source = (dObj["source"] as? JsonPrimitive)?.contentOrNull
                    val code = (dObj["code"] as? JsonPrimitive)?.contentOrNull
                    LspDiagnostic(
                        message = message,
                        severity = severity,
                        source = source,
                        code = code,
                        range = range,
                    )
                }
                doc.diagnostics = parsed
                LspIncomingEvent.DiagnosticsPublished(doc.path, uri, parsed)
            }
            else -> null
        }
    }

    private fun buildOpenMessage(path: String, version: Int, text: String, clientUri: String): String =
        buildJsonObject {
            put("t", "open")
            put("path", path)
            put("version", version)
            put("text", text)
            put("clientUri", clientUri)
        }.toString()
}

// ── Problems Store State (Port of `src/web/stores/problems-store.ts`) ───────

data class FileProblems(
    val projectName: String,
    val filePath: String,
    val diagnostics: List<LspDiagnostic>,
)

data class ProblemTotals(
    val errors: Int,
    val warnings: Int,
)

fun problemKey(projectName: String, filePath: String): String = "$projectName\u0000$filePath"

class ProblemsStoreState {
    private val files = LinkedHashMap<String, FileProblems>()

    fun publish(projectName: String, filePath: String, diagnostics: List<LspDiagnostic>) {
        val key = problemKey(projectName, filePath)
        if (diagnostics.isEmpty()) {
            files.remove(key)
        } else {
            files[key] = FileProblems(projectName, filePath, diagnostics)
        }
    }

    fun clear(projectName: String, filePath: String) {
        files.remove(problemKey(projectName, filePath))
    }

    fun filesForProject(projectName: String?): Map<String, FileProblems> {
        if (projectName.isNullOrBlank()) return files.toMap()
        return files.filterValues { it.projectName == projectName }
    }

    /**
     * Totals across open files in [projectName].
     * Uses [LspDiagnostic.effectiveSeverity] so a diagnostic with missing severity
     * is counted as Warning (2) everywhere, fixing the web inconsistency noted in Phase 07c.
     */
    fun problemCounts(projectName: String?): ProblemTotals {
        var errors = 0
        var warnings = 0
        for (file in filesForProject(projectName).values) {
            for (d in file.diagnostics) {
                when (d.effectiveSeverity) {
                    SEVERITY_ERROR -> errors++
                    SEVERITY_WARNING -> warnings++
                }
            }
        }
        return ProblemTotals(errors = errors, warnings = warnings)
    }

    fun sortedProblems(projectName: String?, query: String = ""): List<FileProblems> {
        val needle = query.trim().lowercase()
        val scoped = filesForProject(projectName).values
            .map { file ->
                file.copy(
                    diagnostics = file.diagnostics.sortedWith { a, b ->
                        val l = a.range.start.line.compareTo(b.range.start.line)
                        if (l != 0) l else a.range.start.character.compareTo(b.range.start.character)
                    },
                )
            }
            .sortedBy { it.filePath }
        if (needle.isEmpty()) return scoped
        return scoped.mapNotNull { file ->
            val filtered = if (file.filePath.lowercase().contains(needle)) {
                file.diagnostics
            } else {
                file.diagnostics.filter { d ->
                    d.message.lowercase().contains(needle) ||
                        (d.source?.lowercase()?.contains(needle) == true) ||
                        (d.code?.lowercase()?.contains(needle) == true)
                }
            }
            if (filtered.isEmpty()) null else file.copy(diagnostics = filtered)
        }
    }
}
