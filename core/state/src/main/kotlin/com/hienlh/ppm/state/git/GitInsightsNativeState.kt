package com.hienlh.ppm.state.git

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 08d: Git Graph native insights panels —
 * Blame (`git-graph.blame`), File History (`git-graph.fileHistory`),
 * Compare (`git-graph.compare`), Interactive Rebase (`git-graph.interactiveRebase`),
 * and Reflog (`git-graph.reflog`).
 *
 * Faithful 1:1 port of `packages/ext-git-graph/src/{blame-*,file-history-*,compare-*,rebase-*,reflog-*}.ts`.
 */

const val GIT_GRAPH_VIEW_BLAME = "git-graph.blame"
const val GIT_GRAPH_VIEW_FILE_HISTORY = "git-graph.fileHistory"
const val GIT_GRAPH_VIEW_COMPARE = "git-graph.compare"
const val GIT_GRAPH_VIEW_REBASE = "git-graph.interactiveRebase"
const val GIT_GRAPH_VIEW_REFLOG = "git-graph.reflog"

val GIT_INSIGHTS_VIEW_TYPES: Set<String> = setOf(
    GIT_GRAPH_VIEW_BLAME,
    GIT_GRAPH_VIEW_FILE_HISTORY,
    GIT_GRAPH_VIEW_COMPARE,
    GIT_GRAPH_VIEW_REBASE,
    GIT_GRAPH_VIEW_REFLOG,
)

// ══════════════════════════════════════════════════════════════════════════════
// 8d.1 Blame (`git-graph.blame` — `blame-parser.ts` & `blame-view.ts`)
// ══════════════════════════════════════════════════════════════════════════════

data class GitBlameTarget(
    val filePath: String,
    val line: Int? = null,
    val rev: String? = null,
)

data class GitBlameCommitInfo(
    val hash: String,
    val author: String,
    val authorMail: String,
    val authorTime: Long,
    val summary: String,
    val boundary: Boolean = false,
    val filename: String? = null,
)

data class GitBlameLine(
    val hash: String,
    val finalLine: Int,
    val origLine: Int,
    val content: String,
)

data class GitBlameResult(
    val lines: List<GitBlameLine>,
    val commits: Map<String, GitBlameCommitInfo>,
)

data class GitBlameData(
    val filePath: String,
    val rev: String? = null,
    val lines: List<GitBlameLine> = emptyList(),
    val commits: Map<String, GitBlameCommitInfo> = emptyMap(),
    val weights: Map<String, Double> = emptyMap(),
    val uncommitted: List<String> = emptyList(),
    val focusLine: Int? = null,
)

private val UNCOMMITTED_HASH_REGEX = Regex("^0+$")
private val BLAME_HEADER_REGEX = Regex("^([0-9a-f]{40})\\s+(\\d+)\\s+(\\d+)(?:\\s+(\\d+))?$")

fun isGitBlameUncommittedHash(hash: String): Boolean =
    hash.isNotEmpty() && UNCOMMITTED_HASH_REGEX.matches(hash)

/**
 * 1:1 port of `parseBlamePorcelain(stdout)` from `blame-parser.ts`.
 */
fun parseBlamePorcelain(stdout: String): GitBlameResult {
    val commits = LinkedHashMap<String, GitBlameCommitInfo>()
    val lines = ArrayList<GitBlameLine>()

    val rawLines = stdout.split("\n")
    var currentHash: String? = null
    var finalLine = 0
    var origLine = 0

    for (raw in rawLines) {
        if (raw.isEmpty()) continue

        if (raw.startsWith("\t")) {
            val hash = currentHash ?: continue
            lines.add(
                GitBlameLine(
                    hash = hash,
                    finalLine = finalLine,
                    origLine = origLine,
                    content = raw.substring(1),
                ),
            )
            currentHash = null
            continue
        }

        val headerMatch = BLAME_HEADER_REGEX.matchEntire(raw)
        if (headerMatch != null) {
            val hash = headerMatch.groupValues[1]
            origLine = headerMatch.groupValues[2].toIntOrNull() ?: 0
            finalLine = headerMatch.groupValues[3].toIntOrNull() ?: 0
            if (!commits.containsKey(hash)) {
                val uncommitted = isGitBlameUncommittedHash(hash)
                commits[hash] = GitBlameCommitInfo(
                    hash = hash,
                    author = if (uncommitted) "Not Committed Yet" else "",
                    authorMail = "",
                    authorTime = 0L,
                    summary = if (uncommitted) "Uncommitted changes" else "",
                    boundary = false,
                )
            }
            currentHash = hash
            continue
        }

        val hash = currentHash ?: continue
        val current = commits[hash] ?: continue
        val sep = raw.indexOf(' ')
        val key = if (sep == -1) raw else raw.substring(0, sep)
        val value = if (sep == -1) "" else raw.substring(sep + 1)

        commits[hash] = when (key) {
            "author" -> if (value.isNotEmpty()) current.copy(author = value) else current
            "author-mail" -> current.copy(authorMail = value.removePrefix("<").removeSuffix(">"))
            "author-time" -> current.copy(authorTime = value.toLongOrNull() ?: 0L)
            "summary" -> if (value.isNotEmpty()) current.copy(summary = value) else current
            "filename" -> current.copy(filename = value)
            "boundary" -> current.copy(boundary = true)
            else -> current
        }
    }

    return GitBlameResult(lines = lines, commits = commits)
}

/**
 * 1:1 port of `computeAgeWeights(commits)` from `blame-parser.ts`.
 */
fun computeBlameAgeWeights(commits: Map<String, GitBlameCommitInfo>): Map<String, Double> {
    val times = commits.values
        .filter { !isGitBlameUncommittedHash(it.hash) && it.authorTime > 0L }
        .map { it.authorTime }
    if (times.isEmpty()) return emptyMap()

    val minTime = times.minOrNull() ?: return emptyMap()
    val maxTime = times.maxOrNull() ?: return emptyMap()
    val span = maxTime - minTime

    val weights = LinkedHashMap<String, Double>()
    for (c in commits.values) {
        if (isGitBlameUncommittedHash(c.hash)) {
            weights[c.hash] = 1.0
        } else {
            weights[c.hash] = if (span == 0L) 1.0 else (c.authorTime - minTime).toDouble() / span.toDouble()
        }
    }
    return weights
}

private val HEAT_STOPS = arrayOf(
    intArrayOf(59, 130, 246),
    intArrayOf(20, 184, 166),
    intArrayOf(234, 179, 8),
    intArrayOf(249, 115, 22),
    intArrayOf(239, 68, 68),
)

/**
 * 1:1 port of `heatColor(w)` from `blame-view.ts` lines 251-258.
 */
fun blameHeatColorRgb(weight: Double): Triple<Int, Int, Int> {
    val clamped = weight.coerceIn(0.0, 1.0)
    val x = clamped * (HEAT_STOPS.size - 1)
    val i = floor(x).toInt().coerceIn(0, HEAT_STOPS.size - 1)
    val f = x - i
    val a = HEAT_STOPS[i]
    val b = HEAT_STOPS[min(i + 1, HEAT_STOPS.size - 1)]
    val r = (a[0] + (b[0] - a[0]) * f).roundToInt()
    val g = (a[1] + (b[1] - a[1]) * f).roundToInt()
    val bl = (a[2] + (b[2] - a[2]) * f).roundToInt()
    return Triple(r, g, bl)
}

sealed interface GitBlameExtMessage {
    data class Init(val target: GitBlameTarget?) : GitBlameExtMessage
    data class Loading(val filePath: String) : GitBlameExtMessage
    data class LoadFiles(val files: List<String>, val total: Int) : GitBlameExtMessage
    data class LoadBlame(val data: GitBlameData) : GitBlameExtMessage
    data class ErrorMessage(val message: String) : GitBlameExtMessage
}

fun parseGitBlameExtMessage(element: JsonElement?): GitBlameExtMessage? {
    val root = element?.obj ?: return null
    return when (root["command"].string) {
        "init" -> {
            val d = root["data"].obj
            val t = d?.get("target")?.obj
            val filePath = t?.get("filePath")?.string
            val target = if (!filePath.isNullOrEmpty()) {
                GitBlameTarget(
                    filePath = filePath,
                    line = t["line"].int,
                    rev = t["rev"].string,
                )
            } else {
                null
            }
            GitBlameExtMessage.Init(target)
        }
        "loading" -> {
            val d = root["data"].obj
            GitBlameExtMessage.Loading(d?.get("filePath")?.string.orEmpty())
        }
        "loadFiles" -> {
            val d = root["data"].obj
            val files = d?.get("files")?.array.orEmpty().mapNotNull { it.string }
            val total = d?.get("total")?.int ?: files.size
            GitBlameExtMessage.LoadFiles(files = files, total = total)
        }
        "loadBlame" -> {
            val d = root["data"].obj ?: return null
            val lines = d["lines"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val hash = o["hash"].string ?: return@mapNotNull null
                GitBlameLine(
                    hash = hash,
                    finalLine = o["finalLine"].int ?: 0,
                    origLine = o["origLine"].int ?: 0,
                    content = o["content"].string.orEmpty(),
                )
            }
            val commits = LinkedHashMap<String, GitBlameCommitInfo>()
            d["commits"].obj?.forEach { (hash, v) ->
                val o = v.obj ?: return@forEach
                commits[hash] = GitBlameCommitInfo(
                    hash = o["hash"].string ?: hash,
                    author = o["author"].string.orEmpty(),
                    authorMail = o["authorMail"].string.orEmpty(),
                    authorTime = o["authorTime"].long ?: 0L,
                    summary = o["summary"].string.orEmpty(),
                    boundary = o["boundary"].bool ?: false,
                    filename = o["filename"].string,
                )
            }
            val weights = LinkedHashMap<String, Double>()
            d["weights"].obj?.forEach { (hash, v) ->
                val w = (v as? JsonPrimitive)?.doubleOrNull ?: 0.0
                weights[hash] = w
            }
            val uncommitted = d["uncommitted"].array.orEmpty().mapNotNull { it.string }
            GitBlameExtMessage.LoadBlame(
                GitBlameData(
                    filePath = d["filePath"].string.orEmpty(),
                    rev = d["rev"].string,
                    lines = lines,
                    commits = commits,
                    weights = if (weights.isNotEmpty()) weights else computeBlameAgeWeights(commits),
                    uncommitted = uncommitted,
                    focusLine = d["focusLine"].int,
                ),
            )
        }
        "error" -> GitBlameExtMessage.ErrorMessage(root["message"].string ?: "Blame error")
        else -> null
    }
}

object GitBlameWebviewMessages {
    fun ready(): JsonObject = buildJsonObject { put("command", "ready") }

    fun requestFiles(query: String = ""): JsonObject = buildJsonObject {
        put("command", "requestFiles")
        put("query", query)
    }

    fun requestBlame(filePath: String, rev: String? = null, line: Int? = null): JsonObject = buildJsonObject {
        put("command", "requestBlame")
        put("filePath", filePath)
        if (!rev.isNullOrEmpty()) put("rev", rev)
        if (line != null) put("line", line)
    }

    fun blamePrevious(filePath: String, hash: String): JsonObject = buildJsonObject {
        put("command", "blamePrevious")
        put("filePath", filePath)
        put("hash", hash)
    }

    fun openDiff(filePath: String, hash: String): JsonObject = buildJsonObject {
        put("command", "openDiff")
        put("filePath", filePath)
        put("hash", hash)
    }

    fun openLineHistory(filePath: String, line: Int): JsonObject = buildJsonObject {
        put("command", "openLineHistory")
        put("filePath", filePath)
        put("line", line)
    }

    fun openHistory(filePath: String): JsonObject = buildJsonObject {
        put("command", "openHistory")
        put("filePath", filePath)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.2 File History (`git-graph.fileHistory` — `file-history-parser.ts` & `file-history-view.ts`)
// ══════════════════════════════════════════════════════════════════════════════

const val FILE_HISTORY_RECORD_SEP = "\u001e"
const val FILE_HISTORY_FIELD_SEP = "\u001f"
const val FILE_HISTORY_PAGE_SIZE = 150

data class GitFileHistoryTarget(
    val filePath: String,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
)

data class GitFileHistoryEntry(
    val hash: String,
    val parents: List<String> = emptyList(),
    val author: String,
    val authorEmail: String,
    val authorDate: Long,
    val subject: String,
    val status: String = "M",
    val path: String = "",
    val oldPath: String? = null,
)

/**
 * 1:1 port of `parseFileHistory(stdout)` from `file-history-parser.ts`.
 */
fun parseFileHistory(stdout: String): List<GitFileHistoryEntry> {
    val entries = ArrayList<GitFileHistoryEntry>()
    for (record in stdout.split(FILE_HISTORY_RECORD_SEP)) {
        if (record.isBlank()) continue
        val lines = record.split("\n")
        val fields = lines.firstOrNull().orEmpty().split(FILE_HISTORY_FIELD_SEP)
        if (fields.size < 6) continue

        val parentsRaw = fields[1]
        var status = "M"
        var path = ""
        var oldPath: String? = null

        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            val code = parts.getOrNull(0)
            val first = parts.getOrNull(1)
            if (code.isNullOrEmpty() || first.isNullOrEmpty()) continue
            val letter = code.first().toString()
            status = letter
            val second = parts.getOrNull(2)
            if ((letter == "R" || letter == "C") && !second.isNullOrEmpty()) {
                oldPath = first
                path = second
            } else {
                path = first
            }
            break
        }

        entries.add(
            GitFileHistoryEntry(
                hash = fields[0],
                parents = if (parentsRaw.isNotEmpty()) parentsRaw.split(" ").filter { it.isNotEmpty() } else emptyList(),
                author = fields[2],
                authorEmail = fields[3],
                authorDate = fields[4].toLongOrNull() ?: 0L,
                subject = fields[5],
                status = status,
                path = path,
                oldPath = oldPath,
            ),
        )
    }
    return entries
}

/**
 * 1:1 port of `buildLineRangeArg(start, end, filePath)` from `file-history-parser.ts`.
 */
fun buildLineRangeArg(start: Int, end: Int, filePath: String): String {
    val lo = min(start, end)
    val hi = max(start, end)
    return "-L$lo,$hi:$filePath"
}

sealed interface GitFileHistoryExtMessage {
    data class Init(val target: GitFileHistoryTarget?) : GitFileHistoryExtMessage
    data object Loading : GitFileHistoryExtMessage
    data class LoadFiles(val files: List<String>, val total: Int) : GitFileHistoryExtMessage
    data class LoadHistory(
        val filePath: String,
        val lineStart: Int?,
        val lineEnd: Int?,
        val entries: List<GitFileHistoryEntry>,
        val skip: Int,
        val append: Boolean,
        val hasMore: Boolean,
    ) : GitFileHistoryExtMessage
    data class ErrorMessage(val message: String) : GitFileHistoryExtMessage
}

internal fun parseFileHistoryEntriesJson(array: List<JsonElement>): List<GitFileHistoryEntry> =
    array.mapNotNull { el ->
        val o = el.obj ?: return@mapNotNull null
        val hash = o["hash"].string ?: return@mapNotNull null
        GitFileHistoryEntry(
            hash = hash,
            parents = o["parents"].array.orEmpty().mapNotNull { it.string },
            author = o["author"].string.orEmpty(),
            authorEmail = o["authorEmail"].string.orEmpty(),
            authorDate = o["authorDate"].long ?: 0L,
            subject = o["subject"].string.orEmpty(),
            status = o["status"].string ?: "M",
            path = o["path"].string.orEmpty(),
            oldPath = o["oldPath"].string,
        )
    }

fun parseGitFileHistoryExtMessage(element: JsonElement?): GitFileHistoryExtMessage? {
    val root = element?.obj ?: return null
    return when (root["command"].string) {
        "init" -> {
            val t = root["data"].obj?.get("target")?.obj
            val filePath = t?.get("filePath")?.string
            val target = if (!filePath.isNullOrEmpty()) {
                GitFileHistoryTarget(
                    filePath = filePath,
                    lineStart = t["lineStart"].int,
                    lineEnd = t["lineEnd"].int,
                )
            } else {
                null
            }
            GitFileHistoryExtMessage.Init(target)
        }
        "loading" -> GitFileHistoryExtMessage.Loading
        "loadFiles" -> {
            val d = root["data"].obj
            val files = d?.get("files")?.array.orEmpty().mapNotNull { it.string }
            val total = d?.get("total")?.int ?: files.size
            GitFileHistoryExtMessage.LoadFiles(files = files, total = total)
        }
        "loadHistory" -> {
            val d = root["data"].obj ?: return null
            val entries = parseFileHistoryEntriesJson(d["entries"].array.orEmpty())
            GitFileHistoryExtMessage.LoadHistory(
                filePath = d["filePath"].string.orEmpty(),
                lineStart = d["lineStart"].int,
                lineEnd = d["lineEnd"].int,
                entries = entries,
                skip = d["skip"].int ?: 0,
                append = d["append"].bool ?: false,
                hasMore = d["hasMore"].bool ?: false,
            )
        }
        "error" -> GitFileHistoryExtMessage.ErrorMessage(root["message"].string ?: "File history error")
        else -> null
    }
}

object GitFileHistoryWebviewMessages {
    fun ready(): JsonObject = buildJsonObject { put("command", "ready") }

    fun requestFiles(query: String = ""): JsonObject = buildJsonObject {
        put("command", "requestFiles")
        put("query", query)
    }

    fun requestHistory(
        filePath: String,
        append: Boolean = false,
        skip: Int = 0,
        lineStart: Int? = null,
        lineEnd: Int? = null,
    ): JsonObject = buildJsonObject {
        put("command", "requestHistory")
        put("filePath", filePath)
        put("append", append)
        put("skip", skip)
        if (lineStart != null) put("lineStart", lineStart)
        if (lineEnd != null) put("lineEnd", lineEnd)
    }

    fun openDiff(filePath: String, hash: String): JsonObject = buildJsonObject {
        put("command", "openDiff")
        put("filePath", filePath)
        put("hash", hash)
    }

    fun openBlame(filePath: String, hash: String? = null): JsonObject = buildJsonObject {
        put("command", "openBlame")
        put("filePath", filePath)
        if (!hash.isNullOrEmpty()) put("hash", hash)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.3 Compare (`git-graph.compare` — `compare-args.ts` & `compare-view.ts`)
// ══════════════════════════════════════════════════════════════════════════════

data class GitCompareTarget(
    val ref1: String? = null,
    val ref2: String? = null,
)

data class GitCompareRefEntry(
    val name: String,
    val hash: String,
    /** `"head"` | `"remote"` | `"tag"` */
    val kind: String,
)

data class GitCompareFileChange(
    val path: String,
    val oldPath: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
)

data class GitCompareCounts(
    val behind: Int = 0,
    val ahead: Int = 0,
)

fun buildRangeSpec(ref1: String, ref2: String, mode: String): String =
    "$ref1${if (mode == "three-dot") "..." else ".."}$ref2"

/**
 * 1:1 port of `parseNumstatZ(stdout)` from `compare-args.ts`.
 */
fun parseNumstatZ(stdout: String): List<GitCompareFileChange> {
    val parts = stdout.split("\u0000")
    val changes = ArrayList<GitCompareFileChange>()
    var i = 0
    while (i < parts.size) {
        val record = parts[i]
        if (record.isEmpty()) {
            i++
            continue
        }
        val tab1 = record.indexOf('\t')
        if (tab1 == -1) {
            i++
            continue
        }
        val tab2 = record.indexOf('\t', tab1 + 1)
        if (tab2 == -1) {
            i++
            continue
        }
        val addedRaw = record.substring(0, tab1)
        val deletedRaw = record.substring(tab1 + 1, tab2)
        val pathPart = record.substring(tab2 + 1)
        val binary = addedRaw == "-" && deletedRaw == "-"

        if (pathPart.isEmpty()) {
            val oldPath = parts.getOrNull(++i).orEmpty()
            val newPath = parts.getOrNull(++i).orEmpty()
            changes.add(
                GitCompareFileChange(
                    path = newPath,
                    oldPath = oldPath,
                    additions = if (binary) 0 else (addedRaw.toIntOrNull() ?: 0),
                    deletions = if (binary) 0 else (deletedRaw.toIntOrNull() ?: 0),
                    binary = binary,
                ),
            )
            i++
            continue
        }

        changes.add(
            GitCompareFileChange(
                path = pathPart,
                additions = if (binary) 0 else (addedRaw.toIntOrNull() ?: 0),
                deletions = if (binary) 0 else (deletedRaw.toIntOrNull() ?: 0),
                binary = binary,
            ),
        )
        i++
    }
    return changes
}

/**
 * 1:1 port of `parseAheadBehind(stdout)` from `compare-args.ts`.
 */
fun parseAheadBehind(stdout: String): GitCompareCounts {
    val tokens = stdout.trim().split(Regex("\\s+"))
    return GitCompareCounts(
        behind = tokens.getOrNull(0)?.toIntOrNull() ?: 0,
        ahead = tokens.getOrNull(1)?.toIntOrNull() ?: 0,
    )
}

/**
 * 1:1 port of `parseRefList(stdout)` from `compare-args.ts`.
 */
fun parseRefList(stdout: String): List<GitCompareRefEntry> {
    val refs = ArrayList<GitCompareRefEntry>()
    for (line in stdout.split("\n")) {
        if (line.isBlank()) continue
        val parts = line.split(FILE_HISTORY_FIELD_SEP)
        val fullName = parts.getOrNull(0).orEmpty()
        val hash = parts.getOrNull(1).orEmpty()
        val shortName = parts.getOrNull(2).orEmpty()
        if (fullName.isEmpty() || hash.isEmpty() || shortName.isEmpty()) continue
        val kind = when {
            fullName.startsWith("refs/tags/") -> "tag"
            fullName.startsWith("refs/remotes/") -> "remote"
            else -> "head"
        }
        refs.add(GitCompareRefEntry(name = shortName, hash = hash, kind = kind))
    }
    return refs
}

sealed interface GitCompareExtMessage {
    data class Init(
        val refs: List<GitCompareRefEntry>,
        val current: String?,
        val target: GitCompareTarget?,
    ) : GitCompareExtMessage
    data object Loading : GitCompareExtMessage
    data class LoadCompare(
        val ref1: String,
        val ref2: String,
        val mode: String,
        val commits: List<GitFileHistoryEntry>,
        val files: List<GitCompareFileChange>,
        val counts: GitCompareCounts,
        val truncated: Boolean,
    ) : GitCompareExtMessage
    data class ErrorMessage(val message: String) : GitCompareExtMessage
}

fun parseGitCompareExtMessage(element: JsonElement?): GitCompareExtMessage? {
    val root = element?.obj ?: return null
    return when (root["command"].string) {
        "init" -> {
            val d = root["data"].obj ?: return null
            val refs = d["refs"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val name = o["name"].string ?: return@mapNotNull null
                GitCompareRefEntry(
                    name = name,
                    hash = o["hash"].string.orEmpty(),
                    kind = o["kind"].string ?: "head",
                )
            }
            val t = d["target"].obj
            val target = if (t != null) {
                GitCompareTarget(
                    ref1 = t["ref1"].string,
                    ref2 = t["ref2"].string,
                )
            } else {
                null
            }
            GitCompareExtMessage.Init(
                refs = refs,
                current = d["current"].string,
                target = target,
            )
        }
        "loading" -> GitCompareExtMessage.Loading
        "loadCompare" -> {
            val d = root["data"].obj ?: return null
            val commits = parseFileHistoryEntriesJson(d["commits"].array.orEmpty())
            val files = d["files"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val path = o["path"].string ?: return@mapNotNull null
                GitCompareFileChange(
                    path = path,
                    oldPath = o["oldPath"].string,
                    additions = o["additions"].int ?: 0,
                    deletions = o["deletions"].int ?: 0,
                    binary = o["binary"].bool ?: false,
                )
            }
            val countsObj = d["counts"].obj
            val counts = GitCompareCounts(
                behind = countsObj?.get("behind")?.int ?: 0,
                ahead = countsObj?.get("ahead")?.int ?: 0,
            )
            GitCompareExtMessage.LoadCompare(
                ref1 = d["ref1"].string.orEmpty(),
                ref2 = d["ref2"].string.orEmpty(),
                mode = d["mode"].string ?: "three-dot",
                commits = commits,
                files = files,
                counts = counts,
                truncated = d["truncated"].bool ?: false,
            )
        }
        "error" -> GitCompareExtMessage.ErrorMessage(root["message"].string ?: "Compare error")
        else -> null
    }
}

object GitCompareWebviewMessages {
    fun ready(): JsonObject = buildJsonObject { put("command", "ready") }

    fun requestCompare(ref1: String, ref2: String, mode: String): JsonObject = buildJsonObject {
        put("command", "requestCompare")
        put("ref1", ref1)
        put("ref2", ref2)
        put("mode", mode)
    }

    fun openDiff(filePath: String, ref1: String, ref2: String, mode: String): JsonObject = buildJsonObject {
        put("command", "openDiff")
        put("filePath", filePath)
        put("ref1", ref1)
        put("ref2", ref2)
        put("mode", mode)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.4 Interactive Rebase (`git-graph.interactiveRebase` — `rebase-todo.ts` & `rebase-view.ts`)
// ══════════════════════════════════════════════════════════════════════════════

val GIT_REBASE_ACTIONS: List<String> = listOf("pick", "reword", "edit", "squash", "fixup", "drop")
const val REBASE_TODO_ENV_VAR = "PPM_REBASE_TODO"
const val REBASE_SEQUENCE_EDITOR = "printf \"%s\" \"\$$REBASE_TODO_ENV_VAR\" >"
const val REBASE_REWORD_ENV_PREFIX = "PPM_REWORD_"

data class GitRebasePlanEntry(
    val hash: String,
    val parents: List<String> = emptyList(),
    val author: String = "",
    val authorEmail: String = "",
    val authorDate: Long = 0L,
    val subject: String = "",
    val action: String = "pick",
    val message: String? = null,
)

data class GitRebaseTodoPlan(
    val todo: String,
    val env: Map<String, String>,
)

fun isGitRebaseAction(value: String?): Boolean = value != null && value in GIT_REBASE_ACTIONS

/**
 * Validates the newest-first interactive rebase plan in the UI (`rebase-view.ts` lines 402-415).
 * Returns an error string if invalid, or `null` if valid.
 */
fun validateRebasePlanNewestFirst(base: String?, plan: List<GitRebasePlanEntry>): String? {
    if (base.isNullOrBlank()) return "No base commit resolved for this rebase."
    val kept = plan.filter { it.action != "drop" }
    if (kept.isEmpty()) return "Dropping every commit would leave nothing to rebase."
    val oldestKept = kept.last()
    if (oldestKept.action == "squash" || oldestKept.action == "fixup") {
        return "The oldest kept commit cannot be ${oldestKept.action} — there is no earlier commit to fold it into."
    }
    val blankReword = kept.firstOrNull { it.action == "reword" && it.message.orEmpty().trim().isEmpty() }
    if (blankReword != null) {
        return "Reword needs a new message for ${blankReword.hash.take(7)}."
    }
    return null
}

/**
 * 1:1 port of `buildRebaseTodo(entries)` from `rebase-todo.ts` (expects oldest-first entries).
 */
fun buildRebaseTodo(entriesOldestFirst: List<GitRebasePlanEntry>): GitRebaseTodoPlan {
    val kept = entriesOldestFirst.filter { it.action != "drop" }
    val oldest = kept.firstOrNull()
        ?: throw IllegalArgumentException("Dropping every commit would leave nothing to rebase.")
    if (oldest.action == "squash" || oldest.action == "fixup") {
        throw IllegalArgumentException(
            "The oldest kept commit cannot be \"${oldest.action}\" — there is no earlier commit to fold it into.",
        )
    }

    val lines = ArrayList<String>()
    val env = LinkedHashMap<String, String>()
    var rewordCount = 0

    for (entry in entriesOldestFirst) {
        if (!Regex("^[0-9a-f]{4,40}$", RegexOption.IGNORE_CASE).matches(entry.hash)) {
            throw IllegalArgumentException("Invalid commit hash in rebase plan: \"${entry.hash}\"")
        }
        if (!isGitRebaseAction(entry.action)) {
            throw IllegalArgumentException("Invalid rebase action: \"${entry.action}\"")
        }
        if (entry.action == "drop") continue

        val subject = entry.subject.replace(Regex("[\\r\\n]+"), " ").trim()
        if (entry.action == "reword") {
            val message = entry.message.orEmpty().trim()
            if (message.isEmpty()) {
                throw IllegalArgumentException("Reword needs a new message for commit ${entry.hash.take(7)}.")
            }
            val variable = "$REBASE_REWORD_ENV_PREFIX${rewordCount++}"
            env[variable] = message
            lines.add("pick ${entry.hash}${if (subject.isNotEmpty()) " $subject" else ""}")
            lines.add("exec git commit --amend --allow-empty -m \"\$$variable\"")
            continue
        }

        lines.add("${entry.action} ${entry.hash}${if (subject.isNotEmpty()) " $subject" else ""}")
    }

    return GitRebaseTodoPlan(todo = "${lines.joinToString("\n")}\n", env = env)
}

fun moveRebasePlanItem(plan: List<GitRebasePlanEntry>, from: Int, to: Int): List<GitRebasePlanEntry> {
    if (from !in plan.indices || to !in plan.indices || from == to) return plan
    val mut = plan.toMutableList()
    val item = mut.removeAt(from)
    mut.add(to, item)
    return mut
}

sealed interface GitRebaseExtMessage {
    data object Running : GitRebaseExtMessage
    data class LoadState(
        val inProgress: String?,
        val progress: String?,
        val branch: String?,
        val upstream: String?,
        val base: String?,
        val commits: List<GitRebasePlanEntry>,
    ) : GitRebaseExtMessage
    data class RebaseResult(val ok: Boolean, val output: String) : GitRebaseExtMessage
    data class ErrorMessage(val message: String) : GitRebaseExtMessage
}

fun parseGitRebaseExtMessage(element: JsonElement?): GitRebaseExtMessage? {
    val root = element?.obj ?: return null
    return when (root["command"].string) {
        "running" -> GitRebaseExtMessage.Running
        "loadState" -> {
            val d = root["data"].obj ?: return null
            val commits = parseFileHistoryEntriesJson(d["commits"].array.orEmpty()).map { c ->
                GitRebasePlanEntry(
                    hash = c.hash,
                    parents = c.parents,
                    author = c.author,
                    authorEmail = c.authorEmail,
                    authorDate = c.authorDate,
                    subject = c.subject,
                    action = "pick",
                )
            }
            GitRebaseExtMessage.LoadState(
                inProgress = d["inProgress"].string,
                progress = d["progress"].string,
                branch = d["branch"].string,
                upstream = d["upstream"].string,
                base = d["base"].string,
                commits = commits,
            )
        }
        "rebaseResult" -> {
            val d = root["data"].obj ?: return null
            GitRebaseExtMessage.RebaseResult(
                ok = d["ok"].bool ?: false,
                output = d["output"].string.orEmpty(),
            )
        }
        "error" -> GitRebaseExtMessage.ErrorMessage(root["message"].string ?: "Rebase error")
        else -> null
    }
}

object GitRebaseWebviewMessages {
    fun ready(): JsonObject = buildJsonObject { put("command", "ready") }

    fun requestPlan(base: String? = null, count: Int? = null): JsonObject = buildJsonObject {
        put("command", "requestPlan")
        if (!base.isNullOrEmpty()) put("base", base)
        if (count != null) put("count", count)
    }

    fun runRebase(base: String, entries: List<GitRebasePlanEntry>): JsonObject = buildJsonObject {
        put("command", "runRebase")
        put("base", base)
        put(
            "entries",
            buildJsonArray {
                entries.forEach { e ->
                    add(
                        buildJsonObject {
                            put("hash", e.hash)
                            put("action", e.action)
                            put("subject", e.subject)
                            if (e.message != null) put("message", e.message) else put("message", JsonNull)
                        },
                    )
                }
            },
        )
    }

    fun rebaseAction(action: String): JsonObject = buildJsonObject {
        put("command", "rebaseAction")
        put("action", action)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 8d.5 Reflog (`git-graph.reflog` — `reflog-parser.ts` & `reflog-view.ts`)
// ══════════════════════════════════════════════════════════════════════════════

const val REFLOG_FIELD_SEP = "\u001f"
const val REFLOG_RECORD_SEP = "\u001e"
val GIT_REFLOG_ACTION_KINDS: List<String> = listOf(
    "",
    "commit",
    "checkout",
    "reset",
    "rebase",
    "merge",
    "pull",
    "clone",
)

data class GitReflogEntry(
    val hash: String,
    val selector: String,
    val action: String,
    val kind: String,
    val author: String,
    val authorEmail: String,
    val authorDate: Long,
    val subject: String,
)

/**
 * 1:1 port of `reflogKind(action)` from `reflog-parser.ts`.
 */
fun reflogKind(action: String): String {
    val head = action.split(":").firstOrNull().orEmpty()
    val word = head.trim().split(Regex("[\\s(]")).firstOrNull().orEmpty()
    return word.lowercase()
}

/**
 * 1:1 port of `parseReflog(stdout)` from `reflog-parser.ts`.
 */
fun parseReflog(stdout: String): List<GitReflogEntry> {
    val entries = ArrayList<GitReflogEntry>()
    val hashRegex = Regex("^[0-9a-f]{7,40}$", RegexOption.IGNORE_CASE)
    for (record in stdout.split(REFLOG_RECORD_SEP)) {
        val trimmed = record.trimStart('\n')
        if (trimmed.isBlank()) continue
        val fields = trimmed.split(REFLOG_FIELD_SEP)
        val hash = fields.getOrNull(0).orEmpty()
        if (!hashRegex.matches(hash)) continue
        val action = fields.getOrNull(2).orEmpty()
        entries.add(
            GitReflogEntry(
                hash = hash,
                selector = fields.getOrNull(1).orEmpty(),
                action = action,
                kind = reflogKind(action),
                author = fields.getOrNull(3).orEmpty(),
                authorEmail = fields.getOrNull(4).orEmpty(),
                authorDate = fields.getOrNull(5)?.toLongOrNull() ?: 0L,
                subject = fields.getOrNull(6).orEmpty(),
            ),
        )
    }
    return entries
}

/**
 * 1:1 port of `assertValidSelector(value)` from `reflog-parser.ts`.
 */
fun isValidReflogSelector(selector: String): Boolean {
    if (Regex("^[0-9a-f]{7,40}$", RegexOption.IGNORE_CASE).matches(selector)) return true
    if (Regex("^[A-Za-z0-9._/-]{1,255}@\\{\\d{1,9}}$").matches(selector) && !selector.contains("..")) {
        return true
    }
    return false
}

/**
 * 1:1 port of `resetHardRefusal(status)` from `reflog-view.ts` lines 36-44.
 */
fun resetHardRefusal(exitCode: Int, stdout: String): String? {
    if (exitCode != 0) {
        return "Could not check whether the working tree is clean, so nothing was run. Try again once git can read the repository."
    }
    if (stdout.trim().isNotEmpty()) {
        return "This would discard uncommitted changes, which nothing can recover. Commit or stash them first."
    }
    return null
}

/**
 * 1:1 port of `visible()` filter in `reflog-view.ts` lines 226-233.
 */
fun filterReflogEntries(
    entries: List<GitReflogEntry>,
    filterText: String,
    kind: String,
): List<GitReflogEntry> {
    val needle = filterText.trim().lowercase()
    return entries.filter { e ->
        if (kind.isNotEmpty() && e.kind != kind) return@filter false
        if (needle.isEmpty()) return@filter true
        "${e.action} ${e.subject} ${e.hash} ${e.selector}".lowercase().contains(needle)
    }
}

sealed interface GitReflogExtMessage {
    data class LoadEntries(
        val entries: List<GitReflogEntry>,
        val branch: String?,
        val truncated: Boolean,
    ) : GitReflogExtMessage
    data class ActionResult(val message: String, val output: String) : GitReflogExtMessage
    data class ErrorMessage(val message: String) : GitReflogExtMessage
}

fun parseGitReflogExtMessage(element: JsonElement?): GitReflogExtMessage? {
    val root = element?.obj ?: return null
    return when (root["command"].string) {
        "loadEntries" -> {
            val d = root["data"].obj ?: return null
            val entries = d["entries"].array.orEmpty().mapNotNull { el ->
                val o = el.obj ?: return@mapNotNull null
                val hash = o["hash"].string ?: return@mapNotNull null
                val action = o["action"].string.orEmpty()
                GitReflogEntry(
                    hash = hash,
                    selector = o["selector"].string.orEmpty(),
                    action = action,
                    kind = o["kind"].string ?: reflogKind(action),
                    author = o["author"].string.orEmpty(),
                    authorEmail = o["authorEmail"].string.orEmpty(),
                    authorDate = o["authorDate"].long ?: 0L,
                    subject = o["subject"].string.orEmpty(),
                )
            }
            GitReflogExtMessage.LoadEntries(
                entries = entries,
                branch = d["branch"].string,
                truncated = d["truncated"].bool ?: false,
            )
        }
        "actionResult" -> {
            val d = root["data"].obj ?: return null
            GitReflogExtMessage.ActionResult(
                message = d["message"].string.orEmpty(),
                output = d["output"].string.orEmpty(),
            )
        }
        "error" -> GitReflogExtMessage.ErrorMessage(root["message"].string ?: "Reflog error")
        else -> null
    }
}

object GitReflogWebviewMessages {
    fun ready(): JsonObject = buildJsonObject { put("command", "ready") }

    fun refresh(): JsonObject = buildJsonObject { put("command", "refresh") }

    fun createBranch(selector: String, name: String): JsonObject = buildJsonObject {
        put("command", "createBranch")
        put("selector", selector)
        put("name", name)
    }

    fun checkout(selector: String): JsonObject = buildJsonObject {
        put("command", "checkout")
        put("selector", selector)
    }

    fun resetHard(selector: String): JsonObject = buildJsonObject {
        put("command", "resetHard")
        put("selector", selector)
    }
}
