package com.hienlh.ppm.state.editor

import com.hienlh.ppm.state.ReviewBlocks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

// ── 7d.1 Inline Blame & Hover State (Port of `src/shared/blame.ts` & `blame-hover.ts`) ──

const val UNCOMMITTED_HASH = "0000000000000000000000000000000000000000"

private val ALL_ZEROS_REGEX = Regex("^0+$")

fun isUncommittedHash(hash: String): Boolean =
    hash.isNotEmpty() && ALL_ZEROS_REGEX.matches(hash)

data class BlameCommitInfo(
    val hash: String,
    val author: String,
    val authorMail: String,
    /** Unix seconds. */
    val authorTime: Long,
    val summary: String,
    val filename: String? = null,
    val previous: String? = null,
)

data class BlameLineEntry(
    val hash: String,
    /** 1-based line number in the current file. */
    val finalLine: Int,
    /** 1-based line number in the file at that commit. */
    val origLine: Int,
)

data class BlameResultState(
    val lines: List<BlameLineEntry>,
    val commits: Map<String, BlameCommitInfo>,
) {
    /** Look up `(commit, origLine)` for 1-based [lineNumber]. */
    fun lineBlame(lineNumber: Int): Pair<BlameCommitInfo, Int>? {
        if (lineNumber <= 0 || lines.isEmpty()) return null
        val dense = lines.getOrNull(lineNumber - 1)
        val entry = if (dense?.finalLine == lineNumber) {
            dense
        } else {
            lines.firstOrNull { it.finalLine == lineNumber }
        } ?: return null
        val commit = commits[entry.hash] ?: return null
        return commit to entry.origLine
    }
}

data class BlameLineDetailState(
    val hash: String,
    val author: String,
    val authorMail: String,
    val authorTime: Long,
    val message: String,
    val removed: List<String>,
    val added: List<String>,
)

fun formatBlameAnnotation(
    commit: BlameCommitInfo?,
    nowMs: Long = System.currentTimeMillis(),
): String {
    if (commit == null) return ""
    if (isUncommittedHash(commit.hash)) return "You, uncommitted changes"
    val who = commit.author.ifBlank { "Unknown" }
    val whenText = if (commit.authorTime > 0L) formatRelativeTime(commit.authorTime * 1000L, nowMs) else ""
    val head = if (whenText.isNotEmpty()) "$who, $whenText" else who
    return if (commit.summary.isNotBlank()) "$head • ${commit.summary}" else head
}

fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val seconds = max(0L, ((nowMs - timestampMs) / 1000.0).roundToLong())
    if (seconds < 60L) return "just now"
    val units = listOf(
        Triple(3600L, 60L, "minute"),
        Triple(86400L, 3600L, "hour"),
        Triple(2592000L, 86400L, "day"),
        Triple(31536000L, 2592000L, "month"),
        Triple(Long.MAX_VALUE, 31536000L, "year"),
    )
    for ((limit, size, name) in units) {
        if (seconds < limit) {
            val n = seconds / size
            return "$n $name${if (n == 1L) "" else "s"} ago"
        }
    }
    return "just now"
}

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

fun ordinalSuffix(day: Int): String {
    val mod100 = day % 100
    if (mod100 in 11..13) return "th"
    return when (day % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
}

/**
 * Formats an epoch-ms timestamp in the given [zoneId] as `"December 2nd, 2025 4:17 PM"`,
 * matching `formatAbsoluteTime` in `src/shared/blame.ts`.
 */
fun formatAbsoluteTime(
    timestampMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), zoneId)
    val month = MONTH_NAMES[zdt.monthValue - 1]
    val day = zdt.dayOfMonth
    val year = zdt.year
    val hour24 = zdt.hour
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    val minute = zdt.minute.toString().padStart(2, '0')
    val meridiem = if (hour24 < 12) "AM" else "PM"
    return "$month $day${ordinalSuffix(day)}, $year $hour12:$minute $meridiem"
}

private val AVATAR_COLOURS = listOf(
    "4E79A7", "F28E2B", "E15759", "76B7B2", "59A14F", "AF7AA1", "9C755F", "B07AA1",
)

fun authorInitials(author: String): String {
    val words = author.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words.first().first()
    val last = if (words.size > 1) words.last().first().toString() else ""
    return "$first$last".uppercase()
}

fun authorColourHex(author: String): String {
    var hash = 0
    for (ch in author) {
        hash = (hash * 31 + ch.code)
    }
    val idx = abs(hash.toLong()).rem(AVATAR_COLOURS.size).toInt()
    return AVATAR_COLOURS[idx]
}

private val ESCAPE_MD_REGEX = Regex("""[\\`*_{}\[\]()#+\-!<>]""")

fun escapeBlameMarkdown(text: String): String =
    ESCAPE_MD_REGEX.replace(text) { "\\${it.value}" }

/**
 * Bounded 200-entry cache for `GET /git/commit-line` responses (including `null` hits so empty
 * commits are not refetched on every cursor move).
 */
class BoundedCommitLineCache(private val maxSize: Int = 200) {
    private val map = LinkedHashMap<String, BlameLineDetailState?>()

    @Synchronized
    fun contains(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun get(key: String): BlameLineDetailState? = map[key]

    @Synchronized
    fun put(key: String, value: BlameLineDetailState?) {
        if (!map.containsKey(key) && map.size >= maxSize) {
            val oldest = map.keys.firstOrNull()
            if (oldest != null) map.remove(oldest)
        }
        map[key] = value
    }

    @Synchronized
    fun size(): Int = map.size
}

fun parseBlameResult(element: JsonElement?): BlameResultState? {
    val obj = element as? JsonObject ?: return null
    val linesArr = obj["lines"] as? JsonArray ?: return null
    val commitsObj = obj["commits"] as? JsonObject ?: return null

    val lines = linesArr.mapNotNull { el ->
        val lObj = el as? JsonObject ?: return@mapNotNull null
        val hash = (lObj["hash"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val finalLine = (lObj["finalLine"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val origLine = (lObj["origLine"] as? JsonPrimitive)?.intOrNull ?: finalLine
        BlameLineEntry(hash = hash, finalLine = finalLine, origLine = origLine)
    }

    val commits = LinkedHashMap<String, BlameCommitInfo>()
    for ((hashKey, cEl) in commitsObj) {
        val cObj = cEl as? JsonObject ?: continue
        val hash = (cObj["hash"] as? JsonPrimitive)?.contentOrNull ?: hashKey
        val author = (cObj["author"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val authorMail = (cObj["authorMail"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val authorTime = (cObj["authorTime"] as? JsonPrimitive)?.longOrNull ?: 0L
        val summary = (cObj["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val filename = (cObj["filename"] as? JsonPrimitive)?.contentOrNull
        val previous = (cObj["previous"] as? JsonPrimitive)?.contentOrNull
        commits[hashKey] = BlameCommitInfo(
            hash = hash,
            author = author,
            authorMail = authorMail,
            authorTime = authorTime,
            summary = summary,
            filename = filename,
            previous = previous,
        )
    }
    return BlameResultState(lines = lines, commits = commits)
}

fun parseBlameLineDetail(element: JsonElement?): BlameLineDetailState? {
    if (element == null || element is JsonNull) return null
    val obj = element as? JsonObject ?: return null
    val hash = (obj["hash"] as? JsonPrimitive)?.contentOrNull ?: return null
    val author = (obj["author"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val authorMail = (obj["authorMail"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val authorTime = (obj["authorTime"] as? JsonPrimitive)?.longOrNull ?: 0L
    val message = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val removed = (obj["removed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val added = (obj["added"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    return BlameLineDetailState(
        hash = hash,
        author = author,
        authorMail = authorMail,
        authorTime = authorTime,
        message = message,
        removed = removed,
        added = added,
    )
}

// ── 7d.2 2-Column Side-by-Side Diff Alignment ──────────────────────────────

data class SideBySideDiffRow(
    val key: String,
    val leftLineNumber: Int?,
    val leftText: String?,
    val leftKind: ReviewBlocks.Kind?,
    val rightLineNumber: Int?,
    val rightText: String?,
    val rightKind: ReviewBlocks.Kind?,
)

sealed interface SideBySideDiffEntry {
    val key: String

    data class Gap(
        val fromNew: Int,
        val count: Int,
    ) : SideBySideDiffEntry {
        override val key: String get() = "gap:$fromNew"
    }

    data class Rows(
        override val key: String,
        val rows: List<SideBySideDiffRow>,
    ) : SideBySideDiffEntry
}

private fun stripEol(line: String): String = when {
    line.endsWith("\r\n") -> line.dropLast(2)
    line.endsWith("\n") -> line.dropLast(1)
    else -> line
}

/**
 * Aligns unified [ReviewBlocks.Row]s into side-by-side rows so original (left) and modified (right)
 * columns scroll together in lockstep on tablet (`>= 768 dp`).
 */
fun pairHunkRowsSideBySide(blockKey: String, rows: List<ReviewBlocks.Row>): List<SideBySideDiffRow> {
    val out = mutableListOf<SideBySideDiffRow>()
    var i = 0
    var rowSeq = 0
    while (i < rows.size) {
        val current = rows[i]
        if (current.kind == ReviewBlocks.Kind.CONTEXT) {
            out += SideBySideDiffRow(
                key = "$blockKey:${rowSeq++}",
                leftLineNumber = current.o,
                leftText = current.text,
                leftKind = ReviewBlocks.Kind.CONTEXT,
                rightLineNumber = current.n,
                rightText = current.text,
                rightKind = ReviewBlocks.Kind.CONTEXT,
            )
            i++
            continue
        }
        // Collect contiguous REMOVED and ADDED runs
        val removedRun = mutableListOf<ReviewBlocks.Row>()
        val addedRun = mutableListOf<ReviewBlocks.Row>()
        while (i < rows.size && rows[i].kind != ReviewBlocks.Kind.CONTEXT) {
            if (rows[i].kind == ReviewBlocks.Kind.REMOVED) removedRun += rows[i]
            else if (rows[i].kind == ReviewBlocks.Kind.ADDED) addedRun += rows[i]
            i++
        }
        val pairCount = max(removedRun.size, addedRun.size)
        for (p in 0 until pairCount) {
            val left = removedRun.getOrNull(p)
            val right = addedRun.getOrNull(p)
            out += SideBySideDiffRow(
                key = "$blockKey:${rowSeq++}",
                leftLineNumber = left?.o,
                leftText = left?.text,
                leftKind = left?.kind,
                rightLineNumber = right?.n,
                rightText = right?.text,
                rightKind = right?.kind,
            )
        }
    }
    return out
}

fun buildSideBySideDiffEntries(
    diff: ReviewBlocks.Diff?,
    original: String,
    modified: String,
    openedGaps: Map<Int, Boolean>,
): List<SideBySideDiffEntry> {
    val newLines = ReviewBlocks.splitLines(modified).map(::stripEol)
    if (diff == null) {
        val oldLines = ReviewBlocks.splitLines(original).map(::stripEol)
        val count = max(oldLines.size, newLines.size)
        val rows = (0 until count).map { idx ->
            val lText = oldLines.getOrNull(idx)
            val rText = newLines.getOrNull(idx)
            SideBySideDiffRow(
                key = "wholesale:$idx",
                leftLineNumber = if (lText != null) idx + 1 else null,
                leftText = lText,
                leftKind = if (lText != null) ReviewBlocks.Kind.REMOVED else null,
                rightLineNumber = if (rText != null) idx + 1 else null,
                rightText = rText,
                rightKind = if (rText != null) ReviewBlocks.Kind.ADDED else null,
            )
        }
        return listOf(SideBySideDiffEntry.Rows("wholesale", rows))
    }

    val out = mutableListOf<SideBySideDiffEntry>()
    var curOld = 0
    var curNew = 0
    for (block in diff.blocks) {
        val gapCount = block.newFrom - curNew
        if (gapCount > 0) {
            if (openedGaps[curNew] == true) {
                val contextRows = (0 until gapCount).map { k ->
                    val text = newLines.getOrElse(curNew + k) { "" }
                    SideBySideDiffRow(
                        key = "ctx:$curNew:$k",
                        leftLineNumber = curOld + k + 1,
                        leftText = text,
                        leftKind = ReviewBlocks.Kind.CONTEXT,
                        rightLineNumber = curNew + k + 1,
                        rightText = text,
                        rightKind = ReviewBlocks.Kind.CONTEXT,
                    )
                }
                out += SideBySideDiffEntry.Rows("ctx:$curNew", contextRows)
            } else {
                out += SideBySideDiffEntry.Gap(fromNew = curNew, count = gapCount)
            }
        }
        out += SideBySideDiffEntry.Rows(
            key = "block:${block.key}",
            rows = pairHunkRowsSideBySide("block:${block.key}", block.rows),
        )
        curOld = block.oldTo
        curNew = block.newTo
    }
    val tailCount = newLines.size - curNew
    if (tailCount > 0) {
        if (openedGaps[curNew] == true) {
            val tailRows = (0 until tailCount).map { k ->
                val text = newLines.getOrElse(curNew + k) { "" }
                SideBySideDiffRow(
                    key = "ctx-tail:$curNew:$k",
                    leftLineNumber = curOld + k + 1,
                    leftText = text,
                    leftKind = ReviewBlocks.Kind.CONTEXT,
                    rightLineNumber = curNew + k + 1,
                    rightText = text,
                    rightKind = ReviewBlocks.Kind.CONTEXT,
                )
            }
            out += SideBySideDiffEntry.Rows("ctx-tail:$curNew", tailRows)
        } else {
            out += SideBySideDiffEntry.Gap(fromNew = curNew, count = tailCount)
        }
    }
    return out
}

// ── 7d.3 Conflict Editor State (Port of `conflict-editor.tsx` + diff3 base) ─

enum class ConflictResolutionAction {
    CURRENT,
    INCOMING,
    BOTH,
}

data class ConflictRegion(
    val id: Int,
    /** 1-indexed line of `<<<<<<<` marker. */
    val startLine: Int,
    /** 1-indexed line of optional `|||||||` diff3 base marker. */
    val baseMarkerLine: Int? = null,
    /** 1-indexed line of `=======` separator. */
    val separatorLine: Int,
    /** 1-indexed line of `>>>>>>>` marker. */
    val endLine: Int,
    val currentContent: String,
    /** Separated diff3 base content (when `|||||||` is present), never lumped into [currentContent]. */
    val baseContent: String? = null,
    val incomingContent: String,
    val currentLabel: String,
    val baseLabel: String? = null,
    val incomingLabel: String,
    val currentLines: List<String> = emptyList(),
    val baseLines: List<String>? = null,
    val incomingLines: List<String> = emptyList(),
)

/**
 * Parses `<<<<<<<`, optional `|||||||` (diff3 base), `=======`, and `>>>>>>>` markers.
 * Unlike the web version (which accidentally lumps `|||||||` base lines into `currentContent`),
 * this cleanly separates [ConflictRegion.baseContent] so `Accept Current` never keeps the base marker!
 */
fun parseConflicts(content: String): List<ConflictRegion> {
    val lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val regions = mutableListOf<ConflictRegion>()
    var i = 0
    var id = 0

    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("<<<<<<<")) {
            val startLine = i
            val currentLabel = line.substring(7).trim()
            val currentLines = mutableListOf<String>()
            var baseMarkerIdx: Int? = null
            var baseLabel: String? = null
            val baseLines = mutableListOf<String>()
            i++

            var inBase = false
            while (i < lines.size && !lines[i].startsWith("=======")) {
                val cur = lines[i]
                if (!inBase && cur.startsWith("|||||||")) {
                    inBase = true
                    baseMarkerIdx = i
                    baseLabel = cur.substring(7).trim()
                } else if (inBase) {
                    baseLines.add(cur)
                } else {
                    currentLines.add(cur)
                }
                i++
            }
            if (i >= lines.size) break

            val separatorLine = i
            val incomingLines = mutableListOf<String>()
            i++

            while (i < lines.size && !lines[i].startsWith(">>>>>>>")) {
                incomingLines.add(lines[i])
                i++
            }
            if (i >= lines.size) break

            val incomingLabel = lines[i].substring(7).trim()
            regions.add(
                ConflictRegion(
                    id = id++,
                    startLine = startLine + 1,
                    baseMarkerLine = baseMarkerIdx?.plus(1),
                    separatorLine = separatorLine + 1,
                    endLine = i + 1,
                    currentContent = currentLines.joinToString("\n"),
                    baseContent = if (baseMarkerIdx != null) baseLines.joinToString("\n") else null,
                    incomingContent = incomingLines.joinToString("\n"),
                    currentLabel = currentLabel,
                    baseLabel = baseLabel,
                    incomingLabel = incomingLabel,
                    currentLines = currentLines.toList(),
                    baseLines = if (baseMarkerIdx != null) baseLines.toList() else null,
                    incomingLines = incomingLines.toList(),
                ),
            )
        }
        i++
    }
    return regions
}

/**
 * Resolves conflict [regionId] in [content] with [action] (`CURRENT`, `INCOMING`, or `BOTH`)
 * and returns the updated document text.
 */
fun resolveConflictRegion(
    content: String,
    regionId: Int,
    action: ConflictResolutionAction,
): String {
    val regions = parseConflicts(content)
    val region = regions.firstOrNull { it.id == regionId } ?: return content
    val replacementLines = when (action) {
        ConflictResolutionAction.CURRENT -> region.currentLines
        ConflictResolutionAction.INCOMING -> region.incomingLines
        ConflictResolutionAction.BOTH -> region.currentLines + region.incomingLines
    }

    val eol = detectEol(content)
    val allLines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n").toMutableList()
    val startIdx = (region.startLine - 1).coerceIn(0, allLines.size)
    val endIdxExclusive = region.endLine.coerceIn(startIdx, allLines.size)
    allLines.subList(startIdx, endIdxExclusive).clear()
    allLines.addAll(startIdx, replacementLines)
    return allLines.joinToString(eol)
}

private val CONFLICT_EXT_TO_LANG = mapOf(
    "js" to "javascript", "jsx" to "javascript",
    "ts" to "typescript", "tsx" to "typescript",
    "py" to "python", "html" to "html",
    "css" to "css", "scss" to "scss",
    "json" to "json", "md" to "markdown", "mdx" to "markdown",
    "yaml" to "yaml", "yml" to "yaml",
    "sh" to "shell", "bash" to "shell",
    "go" to "go", "rs" to "rust", "java" to "java",
    "rb" to "ruby", "php" to "php", "swift" to "swift",
    "sql" to "sql", "xml" to "xml", "toml" to "toml",
)

fun conflictEditorLanguage(filename: String): String {
    val base = filename.substringAfterLast('/').substringAfterLast('\\')
    if (base == ".env" || base.startsWith(".env.")) return "dotenv"
    val ext = base.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return CONFLICT_EXT_TO_LANG[ext] ?: "plaintext"
}

// ── 7d.4 File Compare Picker & Store State (`compare-store.ts` & `compare-picker.tsx`) ──

const val MAX_COMPARE_DIRTY_BYTES = 500 * 1024

data class CompareSelection(
    val filePath: String,
    val projectName: String,
    val dirtyContent: String? = null,
    val label: String = filePath.substringAfterLast('/').substringAfterLast('\\'),
)

data class CompareCandidate(
    val id: String,
    val path: String,
    val label: String,
    /** `"tab"` (open editor tab, first in list) or `"file"` (project file index). */
    val source: String,
    val dirtyContent: String? = null,
)

class CompareStoreState {
    var selection: CompareSelection? = null
        private set

    fun selectForCompare(sel: CompareSelection) {
        val safeDirty = sel.dirtyContent?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_COMPARE_DIRTY_BYTES }
        selection = sel.copy(dirtyContent = safeDirty)
    }

    fun clearSelection() {
        selection = null
    }

    fun onProjectChanged(newProjectName: String?) {
        if (selection != null && selection?.projectName != newProjectName) {
            selection = null
        }
    }
}

fun formatCompareTabTitle(pathA: String, pathB: String): String {
    val a = pathA.substringAfterLast('/').substringAfterLast('\\')
    val b = pathB.substringAfterLast('/').substringAfterLast('\\')
    return "$a ↔ $b"
}

fun buildCompareCandidates(
    openEditorTabs: List<CompareCandidate>,
    projectFilePaths: List<String>,
    query: String = "",
    maxResults: Int = 50,
): List<CompareCandidate> {
    val seenPaths = openEditorTabs.map { it.path }.toHashSet()
    val fileCands = projectFilePaths
        .asSequence()
        .filter { it !in seenPaths }
        .map { path ->
            CompareCandidate(
                id = "file:$path",
                path = path,
                label = path.substringAfterLast('/').substringAfterLast('\\'),
                source = "file",
            )
        }
        .toList()
    val all = openEditorTabs + fileCands
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return all.take(maxResults)
    return all
        .filter { it.label.lowercase().contains(needle) || it.path.lowercase().contains(needle) }
        .sortedWith(
            compareBy<CompareCandidate>(
                { !it.label.lowercase().startsWith(needle) },
                { !it.label.lowercase().contains(needle) },
                { it.source != "tab" },
                { it.path.length },
            ),
        )
        .take(maxResults)
}
