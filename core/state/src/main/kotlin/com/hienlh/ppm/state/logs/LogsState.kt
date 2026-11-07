package com.hienlh.ppm.state.logs

import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val LOGS_ISSUE_REPO = "hienlh/ppm"
const val ISSUE_URL_LIMIT = 8192
const val MAX_KEEP = 20_000
const val DEDUPE_TAIL = 4000

val LOG_SOURCE_IDS = listOf("ai", "shell", "server", "ext", "files", "auto", "tunnel", "browser")

data class LogSourceInfo(val id: String, val label: String, val group: String)

val LOG_SOURCES = listOf(
    LogSourceInfo("ai", "AI & chat", "ppm"),
    LogSourceInfo("shell", "Shell & tools", "ppm"),
    LogSourceInfo("server", "Server", "ppm"),
    LogSourceInfo("ext", "Extensions", "ppm"),
    LogSourceInfo("files", "Files", "ppm"),
    LogSourceInfo("auto", "Automation", "ppm"),
    LogSourceInfo("tunnel", "Tunnel", "elsewhere"),
    LogSourceInfo("browser", "Browser", "elsewhere"),
)

fun logSourceLabel(id: String): String =
    if (id == "all") "All sources" else LOG_SOURCES.firstOrNull { it.id == id }?.label ?: id

val LOG_RANGES = listOf("15m", "1h", "restart", "today", "all")

val LOG_RANGE_LABELS = mapOf(
    "15m" to "Last 15 minutes",
    "1h" to "Last hour",
    "restart" to "Since the restart",
    "today" to "Today",
    "all" to "Everything kept",
)

data class LogEntry(
    val id: String,
    val ts: Long,
    val lv: String,
    val src: String,
    val tag: String,
    val msg: String,
    val more: List<String> = emptyList(),
    val sid: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): LogEntry? {
            val id = json["id"].string ?: return null
            val ts = json["ts"].long ?: return null
            return LogEntry(
                id = id,
                ts = ts,
                lv = json["lv"].string ?: "info",
                src = json["src"].string ?: "server",
                tag = json["tag"].string ?: "ppm",
                msg = json["msg"].string.orEmpty(),
                more = json["more"].array.orEmpty().mapNotNull { it.string },
                sid = json["sid"].string,
            )
        }
    }
}

data class LogFilter(
    val src: String = "all",
    val levels: Map<String, Boolean> = mapOf("error" to true, "warn" to true, "info" to true, "debug" to false),
    val tagsOff: List<String> = emptyList(),
    val q: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val chat: String? = null,
)

val DEFAULT_LOG_FILTER = LogFilter()

fun levelBucket(lv: String): String = if (lv == "fatal") "error" else lv

sealed interface RegexResult {
    data object None : RegexResult
    data class Compiled(val regex: Regex) : RegexResult
    data object Invalid : RegexResult
}

private val REGEX_ESCAPE = Regex("""[.*+?^${'$'}{}()|\[\]\\]""")

fun compileLogSearch(q: String, regex: Boolean, caseSensitive: Boolean): RegexResult {
    if (q.isEmpty()) return RegexResult.None
    return try {
        val pattern = if (regex) q else q.replace(REGEX_ESCAPE) { """\${it.value}""" }
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        RegexResult.Compiled(Regex(pattern, opts))
    } catch (_: Exception) {
        RegexResult.Invalid
    }
}

fun compileLogSearch(filter: LogFilter): RegexResult =
    compileLogSearch(filter.q, filter.regex, filter.caseSensitive)

fun logSearchText(entry: LogEntry, titles: Map<String, String> = emptyMap()): String {
    val title = entry.sid?.let { titles[it] }?.let { " $it" }.orEmpty()
    val more = if (entry.more.isNotEmpty()) "\n${entry.more.joinToString("\n")}" else ""
    return "${entry.tag} ${entry.msg}$title$more"
}

fun matchesLogFilter(
    entry: LogEntry,
    filter: LogFilter,
    search: RegexResult,
    titles: Map<String, String> = emptyMap(),
): Boolean {
    if (filter.src != "all" && entry.src != filter.src) return false
    if (filter.levels[levelBucket(entry.lv)] != true) return false
    if (filter.tagsOff.isNotEmpty() && "${entry.src}:${entry.tag}" in filter.tagsOff) return false
    if (filter.chat != null && entry.sid != filter.chat) return false
    return when (search) {
        RegexResult.Invalid -> false
        RegexResult.None -> true
        is RegexResult.Compiled -> search.regex.containsMatchIn(logSearchText(entry, titles))
    }
}

data class LogSourceStats(
    val total: Int = 0,
    val err: Int = 0,
    val warn: Int = 0,
    val tags: List<Pair<String, Int>> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): LogSourceStats {
            if (json == null) return LogSourceStats()
            val tags = json["tags"].array.orEmpty().mapNotNull { arr ->
                val pair = arr.array ?: return@mapNotNull null
                val tag = pair.getOrNull(0)?.string ?: return@mapNotNull null
                val count = pair.getOrNull(1)?.int ?: 0
                tag to count
            }
            return LogSourceStats(
                total = json["total"].int ?: 0,
                err = json["err"].int ?: 0,
                warn = json["warn"].int ?: 0,
                tags = tags,
            )
        }
    }
}

fun emptySourceStats(): Map<String, LogSourceStats> =
    LOG_SOURCE_IDS.associateWith { LogSourceStats() }

fun computeSourceStats(entries: Iterable<LogEntry>): Map<String, LogSourceStats> {
    val totals = mutableMapOf<String, IntArray>()
    val tagMaps = mutableMapOf<String, MutableMap<String, Int>>()
    for (id in LOG_SOURCE_IDS) totals[id] = IntArray(3)
    for (e in entries) {
        val counts = totals.getOrPut(e.src) { IntArray(3) }
        counts[0]++
        when (levelBucket(e.lv)) {
            "error" -> counts[1]++
            "warn" -> counts[2]++
        }
        val m = tagMaps.getOrPut(e.src) { LinkedHashMap() }
        m[e.tag] = (m[e.tag] ?: 0) + 1
    }
    return totals.mapValues { (src, c) ->
        val sortedTags = tagMaps[src]?.entries?.sortedByDescending { it.value }?.map { it.key to it.value }.orEmpty()
        LogSourceStats(total = c[0], err = c[1], warn = c[2], tags = sortedTags)
    }
}

fun addToSourceStats(
    stats: Map<String, LogSourceStats>,
    entries: List<LogEntry>,
): Map<String, LogSourceStats> {
    if (entries.isEmpty()) return stats
    val out = stats.toMutableMap()
    for (e in entries) {
        val cur = out[e.src] ?: LogSourceStats()
        val b = levelBucket(e.lv)
        val nextTags = cur.tags.toMutableList()
        val idx = nextTags.indexOfFirst { it.first == e.tag }
        if (idx >= 0) nextTags[idx] = e.tag to (nextTags[idx].second + 1)
        else nextTags.add(e.tag to 1)
        out[e.src] = LogSourceStats(
            total = cur.total + 1,
            err = cur.err + if (b == "error") 1 else 0,
            warn = cur.warn + if (b == "warn") 1 else 0,
            tags = nextTags,
        )
    }
    return out
}

private val CLOUDFLARED_LEVEL = mapOf(
    "debug" to "DBG",
    "info" to "INF",
    "warn" to "WRN",
    "error" to "ERR",
    "fatal" to "FTL",
)

fun rawLogLine(entry: LogEntry, repeats: Int = 0): String {
    val iso = Instant.ofEpochMilli(entry.ts).toString()
    val head = if (entry.src == "tunnel" && entry.tag == "cloudflared") {
        "${iso.take(19)}Z ${CLOUDFLARED_LEVEL[entry.lv] ?: "INF"} ${entry.msg}"
    } else {
        "[$iso] [${entry.lv.uppercase()}] [${entry.tag}] ${entry.msg}"
    }
    val rep = if (repeats > 1) " [x$repeats]" else ""
    val more = if (entry.more.isNotEmpty()) "\n${entry.more.joinToString("\n")}" else ""
    return "$head$rep$more"
}

fun sameLogRecord(a: LogEntry, b: LogEntry): Boolean =
    a.src == b.src && a.lv == b.lv && a.tag == b.tag && a.msg == b.msg && a.more.isEmpty() && b.more.isEmpty()

data class LogRow(
    val key: String,
    val entry: LogEntry,
    val count: Int,
    val ids: List<String>,
    val lastTs: Long,
)

fun foldRepeats(entries: List<LogEntry>): List<LogRow> {
    val out = mutableListOf<LogRow>()
    for (e in entries) {
        val prev = out.lastOrNull()
        if (prev != null && sameLogRecord(prev.entry, e)) {
            out[out.lastIndex] = prev.copy(
                count = prev.count + 1,
                ids = prev.ids + e.id,
                lastTs = e.ts,
            )
        } else {
            out += LogRow(key = e.id, entry = e, count = 1, ids = listOf(e.id), lastTs = e.ts)
        }
    }
    return out
}

fun restartDividers(rows: List<LogRow>, restarts: List<Long>): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    for (at in restarts) {
        val idx = rows.indexOfFirst { it.entry.ts >= at }
        if (idx > 0) out[rows[idx].key] = at
    }
    return out
}

// ── Redaction (log-redact.ts) ──────────────────────────────────────────

data class RedactOptions(
    val home: Boolean = true,
    val email: Boolean = true,
    val chats: Boolean = true,
    val projects: List<String> = emptyList(),
)

private val TUNNEL_HOST = Regex("""\b[a-z0-9]+(?:-[a-z0-9]+)+\.trycloudflare\.com\b""", RegexOption.IGNORE_CASE)
private val SECRETS = listOf(
    Regex("""()\b(?:sk-[\w-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_\w{30,}|eyJ[\w-]{8,}\.eyJ[\w-]{8,}\.[\w-]*)"""),
    Regex("""(\d{6,}:)[\w-]{30,}"""),
    Regex("""(://[^/\s:@]*:)[^@\s/\u0001]+(?=@)"""),
    Regex("""([?&#][\w.-]*(?:token|key|secret|passw|auth|code|state|ticket|sig|credential)[\w.-]*=)[^&#\s"'<>\[\]\u0001]+""", RegexOption.IGNORE_CASE),
    Regex("""(\b(?:proxy-)?authorization["']?\s*[:=]\s*["']?)(?:(?:basic|bearer|digest|token)\s+)?[^\s"',;\u0001]+""", RegexOption.IGNORE_CASE),
    Regex("""(\b(?:set-)?cookie["']?\s*[:=]\s*["']?)(?=[^\s=;"']+=)[^\r\n"'\u0001]+""", RegexOption.IGNORE_CASE),
    Regex("""(\bbearer\s+)[^\s"',;\[\]\u0001]+""", RegexOption.IGNORE_CASE),
    Regex("""((?:token|secret|passw(?:or)?d|api[_-]?key|access[_-]?key|private[_-]?key)["']?\s*[:=]\s*["']?)[^\s"'&,;)}\[\]\u0001]+""", RegexOption.IGNORE_CASE),
)
private val HOME_RE = Regex("""(?:/home/|/Users/)[^/\s"'`]+|[A-Za-z]:(?:\\{1,2}|/)Users(?:\\{1,2}|/)[^\\/\s"'`]+""", RegexOption.IGNORE_CASE)
private val EMAIL_RE = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
private val UUID_RE = Regex("""\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)

fun redactLogText(text: String, opts: RedactOptions = RedactOptions(), mark: Boolean = false): String {
    fun m(s: String): String = if (mark) "\u0001$s\u0002" else s
    var out = TUNNEL_HOST.replace(text) { m("<tunnel>.trycloudflare.com") }
    for (re in SECRETS) {
        out = re.replace(out) { match ->
            val keep = match.groupValues.getOrElse(1) { "" }
            "$keep${m("[REDACTED]")}"
        }
    }
    if (opts.home) out = HOME_RE.replace(out) { m("~") }
    if (opts.email) out = EMAIL_RE.replace(out) { m("<email>") }
    if (opts.chats) out = UUID_RE.replace(out) { match -> m(match.groupValues[1]) }
    val names = opts.projects.map { it.trim() }.filter { it.length >= 3 }.sortedByDescending { it.length }
    if (names.isNotEmpty()) {
        val pattern = names.joinToString("|") { it.replace(REGEX_ESCAPE) { esc -> """\${esc.value}""" } }
        val projRe = Regex("""(?<![\w-])(?:$pattern)(?![\w-])""", RegexOption.IGNORE_CASE)
        out = projRe.replace(out) { m("<project>") }
    }
    return out
}

fun redactedKinds(opts: RedactOptions): List<String> = buildList {
    add("secrets")
    if (opts.home) add("home paths")
    if (opts.email) add("emails")
    if (opts.chats) add("chat ids")
    if (opts.projects.any { it.trim().length >= 3 }) add("project names")
}

// ── Time, query & report helpers ───────────────────────────────────────

fun rangeStart(range: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (range) {
    "15m" -> nowMs - 15 * 60_000L
    "1h" -> nowMs - 60 * 60_000L
    "today" -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        .toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    else -> 0L
}

fun rangeCovering(oldestMs: Long, nowMs: Long): String {
    if (oldestMs >= rangeStart("1h", nowMs)) return "1h"
    if (oldestMs >= rangeStart("today", nowMs)) return "today"
    return "all"
}

fun clockTime(ts: Long, utc: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ts), if (utc) ZoneOffset.UTC else zone)
    return String.format(
        Locale.US,
        "%02d:%02d:%02d.%03d",
        zdt.hour,
        zdt.minute,
        zdt.second,
        zdt.nano / 1_000_000,
    )
}

fun localOffsetLabel(zone: ZoneId = ZoneId.systemDefault()): String {
    val offsetSec = ZonedDateTime.now(zone).offset.totalSeconds
    val minEast = offsetSec / 60
    if (minEast == 0) return "UTC"
    val a = abs(minEast)
    val sign = if (minEast > 0) "+" else "-"
    val rem = a % 60
    return "UTC$sign${a / 60}${if (rem != 0) String.format(Locale.US, ":%02d", rem) else ""}"
}

fun plural(n: Int, one: String, many: String = "${one}s"): String =
    "${String.format(Locale.US, "%,d", n)} ${if (n == 1) one else many}"

fun shortCount(n: Int): String =
    if (n >= 10_000) {
        if (n >= 100_000) "${(n / 1000.0).roundToInt()}k"
        else String.format(Locale.US, "%.1fk", n / 1000.0).removeSuffix(".0k") + (if (String.format(Locale.US, "%.1fk", n / 1000.0).endsWith(".0k")) "k" else "")
    } else {
        String.format(Locale.US, "%,d", n)
    }

fun byteSize(n: Long): String = when {
    n >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0)).replace(".0 MB", " MB")
    n >= 1024L -> "${(n / 1024.0).roundToInt()} KB"
    else -> "$n B"
}

fun logsQueryPairs(
    filter: LogFilter,
    range: String,
    from: Long,
    before: String? = null,
    reach: String? = null,
    limit: Int = 500,
): List<Pair<String, String>> = buildList {
    add("src" to filter.src)
    add("lv" to listOf("error", "warn", "info", "debug").filter { filter.levels[it] == true }.joinToString(","))
    if (filter.tagsOff.isNotEmpty()) add("off" to filter.tagsOff.joinToString(","))
    if (filter.q.isNotEmpty()) add("q" to filter.q)
    if (filter.regex) add("re" to "1")
    if (filter.caseSensitive) add("cs" to "1")
    if (filter.chat != null) add("chat" to filter.chat)
    add("range" to range)
    add("from" to "$from")
    if (before != null) add("before" to before)
    if (reach != null) add("reach" to reach)
    add("limit" to "$limit")
}

// ── Feed state & live tail deduplication ───────────────────────────────

data class LogChatInfo(val sid: String, val title: String?, val count: Int, val last: Long) {
    companion object {
        fun from(json: JsonElement?): LogChatInfo? {
            val sid = json["sid"].string ?: return null
            return LogChatInfo(
                sid = sid,
                title = json["title"].string,
                count = json["count"].int ?: 0,
                last = json["last"].long ?: 0L,
            )
        }
    }
}

data class LogFilesInfo(
    val ppmLogPath: String = "~/.ppm/ppm.log",
    val ppmLogBytes: Long = 0L,
    val capBytes: Long = 10_485_760L,
    val generations: Int = 5,
    val rotatedFiles: Int = 0,
    val cloudflaredPath: String? = null,
    val cloudflaredBytes: Long = 0L,
    val browserDevices: Int = 0,
    val browserRetentionDays: Int = 7,
) {
    companion object {
        fun from(json: JsonElement?): LogFilesInfo {
            if (json == null) return LogFilesInfo()
            return LogFilesInfo(
                ppmLogPath = json["ppmLogPath"].string ?: "~/.ppm/ppm.log",
                ppmLogBytes = json["ppmLogBytes"].long ?: 0L,
                capBytes = json["capBytes"].long ?: 10_485_760L,
                generations = json["generations"].int ?: 5,
                rotatedFiles = json["rotatedFiles"].int ?: 0,
                cloudflaredPath = json["cloudflaredPath"].string,
                cloudflaredBytes = json["cloudflaredBytes"].long ?: 0L,
                browserDevices = json["browserDevices"].int ?: 0,
                browserRetentionDays = json["browserRetentionDays"].int ?: 7,
            )
        }
    }
}

data class LogQueryResult(
    val entries: List<LogEntry> = emptyList(),
    val matched: Int = 0,
    val inRange: Int = 0,
    val hasMore: Boolean = false,
    val stats: Map<String, LogSourceStats> = emptySourceStats(),
    val chats: List<LogChatInfo> = emptyList(),
    val titles: Map<String, String> = emptyMap(),
    val restarts: List<Long> = emptyList(),
    val fromTs: Long = 0L,
    val files: LogFilesInfo = LogFilesInfo(),
    val badRegex: Boolean = false,
    val reachMissed: String? = null,
) {
    companion object {
        fun from(json: JsonElement?): LogQueryResult {
            if (json == null) return LogQueryResult()
            val statsObj = json["stats"].obj
            val stats = LOG_SOURCE_IDS.associateWith { id -> LogSourceStats.from(statsObj?.get(id)) }
            val titles = json["titles"].obj?.mapNotNull { (k, v) -> v.string?.let { k to it } }?.toMap().orEmpty()
            return LogQueryResult(
                entries = json["entries"].array.orEmpty().mapNotNull(LogEntry::from),
                matched = json["matched"].int ?: 0,
                inRange = json["inRange"].int ?: 0,
                hasMore = json["hasMore"].bool ?: false,
                stats = stats,
                chats = json["chats"].array.orEmpty().mapNotNull(LogChatInfo::from),
                titles = titles,
                restarts = json["restarts"].array.orEmpty().mapNotNull { it.long },
                fromTs = json["fromTs"].long ?: 0L,
                files = LogFilesInfo.from(json["files"]),
                badRegex = json["badRegex"].bool ?: false,
                reachMissed = json["reachMissed"].string,
            )
        }
    }
}

/**
 * Joins live `logs:lines` entries onto [current]: filters by `fromTs`, deduplicates against the
 * last [DEDUPE_TAIL] (4,000) records, updates stats/chats/counts, and caps total entries at
 * [MAX_KEEP] (20,000).
 */
fun applyLiveEntries(
    current: LogQueryResult,
    entries: List<LogEntry>,
    titles: Map<String, String>,
    filter: LogFilter,
): LogQueryResult {
    if (entries.isEmpty()) return current
    val allTitles = current.titles + titles
    val have = current.entries.takeLast(DEDUPE_TAIL).mapTo(HashSet()) { it.id }
    val fresh = entries.filter { it.ts >= current.fromTs && it.id !in have }
    if (fresh.isEmpty()) return current
    val nextStats = addToSourceStats(current.stats, fresh)
    val search = compileLogSearch(filter)
    val matching = fresh.filter { matchesLogFilter(it, filter, search, allTitles) }
    val chats = current.chats.toMutableList()
    for (e in fresh) {
        val sid = e.sid ?: continue
        val idx = chats.indexOfFirst { it.sid == sid }
        if (idx >= 0) {
            val c = chats[idx]
            chats[idx] = c.copy(count = c.count + 1, last = e.ts)
        } else {
            chats.add(0, LogChatInfo(sid = sid, title = allTitles[sid], count = 1, last = e.ts))
        }
    }
    var nextEntries = if (matching.isNotEmpty()) current.entries + matching else current.entries
    var hasMore = current.hasMore
    if (nextEntries.size > MAX_KEEP) {
        nextEntries = nextEntries.takeLast(MAX_KEEP)
        hasMore = true
    }
    return current.copy(
        entries = nextEntries,
        stats = nextStats,
        chats = chats,
        titles = allTitles,
        matched = current.matched + matching.size,
        inRange = current.inRange + fresh.count { filter.src == "all" || it.src == filter.src },
        hasMore = hasMore,
    )
}

// ── Issues & Report models ─────────────────────────────────────────────

data class LogIssue(
    val id: String,
    val cls: String,
    val title: String,
    val area: String,
    val src: String,
    val why: String,
    val fix: String? = null,
    val count: Int = 1,
    val errors: Int = 0,
    val warnings: Int = 0,
    val last: Long = 0L,
    val lines: List<LogEntry> = emptyList(),
    val dismissed: Boolean = false,
) {
    companion object {
        fun from(json: JsonElement?): LogIssue? {
            val id = json["id"].string ?: return null
            return LogIssue(
                id = id,
                cls = json["cls"].string ?: "bug",
                title = json["title"].string.orEmpty(),
                area = json["area"].string.orEmpty(),
                src = json["src"].string ?: "server",
                why = json["why"].string.orEmpty(),
                fix = json["fix"].string,
                count = json["count"].int ?: 1,
                errors = json["errors"].int ?: 0,
                warnings = json["warnings"].int ?: 0,
                last = json["last"].long ?: 0L,
                lines = json["lines"].array.orEmpty().mapNotNull(LogEntry::from),
                dismissed = json["dismissed"].bool ?: false,
            )
        }
    }
}

data class LogIssuesResult(
    val issues: List<LogIssue> = emptyList(),
    val unsorted: Int = 0,
    val analyzedAt: Long? = null,
    val model: String = "Claude Haiku 4.5",
    val running: Boolean = false,
    val auto: Boolean = false,
    val error: String? = null,
    val windowFrom: Long = 0L,
) {
    companion object {
        fun from(json: JsonElement?): LogIssuesResult {
            if (json == null) return LogIssuesResult()
            return LogIssuesResult(
                issues = json["issues"].array.orEmpty().mapNotNull(LogIssue::from),
                unsorted = json["unsorted"].int ?: 0,
                analyzedAt = json["analyzedAt"].long,
                model = json["model"].string ?: "Claude Haiku 4.5",
                running = json["running"].bool ?: false,
                auto = json["auto"].bool ?: false,
                error = json["error"].string,
                windowFrom = json["windowFrom"].long ?: 0L,
            )
        }
    }
}

data class LogIssuesSummary(val likelyBugs: Int = 0, val running: Boolean = false) {
    companion object {
        fun from(json: JsonElement?): LogIssuesSummary = LogIssuesSummary(
            likelyBugs = json["likelyBugs"].int ?: 0,
            running = json["running"].bool ?: false,
        )
    }
}

data class ReportSnippet(
    val id: String,
    val src: String,
    val rows: List<LogRow>,
    val ctx: Int = 0,
)

data class ReportFields(
    val title: String = "",
    val labels: List<String> = emptyList(),
    val what: String = "",
    val steps: String = "",
    val expected: String = "",
)

fun snippetCount(snippet: ReportSnippet): Int = snippet.rows.sumOf { it.count }

fun snippetHeading(snippet: ReportSnippet): String {
    val first = snippet.rows.firstOrNull()
    val last = snippet.rows.lastOrNull()
    val span = if (first != null && last != null) {
        val f = Instant.ofEpochMilli(first.entry.ts).toString().substring(11, 19)
        val l = Instant.ofEpochMilli(last.lastTs).toString().substring(11, 19)
        " · $f–$l UTC"
    } else {
        ""
    }
    return "${logSourceLabel(snippet.src)}$span"
}

fun reportBody(
    fields: ReportFields,
    snippets: List<ReportSnippet>,
    environment: List<Pair<String, String>>,
    redact: RedactOptions = RedactOptions(),
    mark: Boolean = false,
): String {
    fun r(s: String): String = redactLogText(s, redact, mark)
    val out = mutableListOf(
        "### What happened",
        r(fields.what.trim()).ifEmpty { "_Not filled in_" },
        "",
        "### Steps to reproduce",
        r(fields.steps.trim()).ifEmpty { "_Not filled in_" },
        "",
    )
    if (fields.expected.isNotBlank()) {
        out += listOf("### Expected", r(fields.expected.trim()), "")
    }
    if (snippets.isNotEmpty()) {
        out += "### Log lines"
        for (s in snippets) {
            val lines = s.rows.map { r(rawLogLine(it.entry, it.count)) }
            val fence = if (lines.any { it.contains("```") }) "````" else "```"
            val n = snippetCount(s)
            out += listOf(
                "",
                "<details><summary>${snippetHeading(s)} · ${plural(n, "line")}${if (s.ctx > 0) " and ${s.ctx} around" else ""}</summary>",
                "",
                fence,
            )
            out += lines
            out += listOf(fence, "</details>")
        }
        out += ""
    }
    if (environment.isNotEmpty()) {
        out += "### Environment"
        for ((k, v) in environment) {
            out += "- $k: ${r(v)}"
        }
        out += ""
    }
    out += "<sub>Sent from PPM → Logs. Removed before sending: ${redactedKinds(redact).joinToString(", ")}.</sub>"
    return out.joinToString("\n")
}

private fun encodeUriComponent(s: String): String =
    URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")

fun issueUrl(title: String, labels: List<String>, body: String?): String {
    val parts = mutableListOf("title=${encodeUriComponent(title)}")
    if (labels.isNotEmpty()) parts += "labels=${encodeUriComponent(labels.joinToString(","))}"
    if (body != null) parts += "body=${encodeUriComponent(body)}"
    return "https://github.com/$LOGS_ISSUE_REPO/issues/new?${parts.joinToString("&")}"
}

fun reportMarkdown(title: String, body: String): String =
    "# ${title.trim().ifEmpty { "Bug report" }}\n\n$body"
