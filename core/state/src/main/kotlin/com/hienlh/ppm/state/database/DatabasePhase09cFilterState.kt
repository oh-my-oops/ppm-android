package com.hienlh.ppm.state.database

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// ============================================================================
// Phase 09c: Database — DBGate Filter Parser, Grid Filters, Value Picker & Funnel
// Ported from:
//   - src/shared/db-grid.ts
//   - src/shared/db-filter-parser.ts
//   - src/web/components/database/grid/grid-filters.ts
//   - src/web/components/database/grid/value-filter-text.ts
//   - src/web/components/database/grid/filter-funnel-menu.ts
//   - src/web/components/database/grid/use-table-filters.ts
// ============================================================================

const val FILTER_MAX_IN_VALUES = 5_000
const val FILTER_MAX_RAW_SQL = 4_000
const val GRID_VALUES_LIMIT = 100
const val GRID_VALUES_MAX_SEARCH = 1_000
const val LOOKUP_ROWS = GRID_VALUES_LIMIT

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private const val MAX_ALTERNATIVES = 64
private const val DATE_HINT = "use 2021, 2021-02, 2021-02-15 or 2021-02-15 23:15"

sealed interface FilterValue {
    fun toJsonElement(): JsonElement

    data class Str(val value: String) : FilterValue {
        override fun toJsonElement(): JsonElement = JsonPrimitive(value)
    }

    data class Num(val value: Double) : FilterValue {
        init {
            require(value.isFinite()) { "FilterValue.Num must be finite" }
        }

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        override fun toJsonElement(): JsonElement {
            val absVal = abs(value)
            return when {
                value % 1.0 == 0.0 && absVal <= MAX_SAFE_INTEGER.toDouble() ->
                    JsonPrimitive(value.toLong())
                value % 1.0 == 0.0 && absVal < 1e21 ->
                    JsonUnquotedLiteral(java.math.BigDecimal.valueOf(value).toBigInteger().toString())
                absVal >= 1e21 || (value != 0.0 && absVal < 1e-6) -> {
                    val raw = value.toString().lowercase(Locale.US).replace(".0e", "e")
                    val jsExp = if ("e-" in raw || "e+" in raw) raw else raw.replace("e", "e+")
                    JsonUnquotedLiteral(jsExp)
                }
                else -> JsonPrimitive(value)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Num) return false
            val a = if (value == 0.0) 0.0 else value
            val b = if (other.value == 0.0) 0.0 else other.value
            return a == b
        }

        override fun hashCode(): Int {
            val normalized = if (value == 0.0) 0.0 else value
            return normalized.hashCode()
        }
    }

    data class Bool(val value: Boolean) : FilterValue {
        override fun toJsonElement(): JsonElement = JsonPrimitive(value)
    }

    data object Null : FilterValue {
        override fun toJsonElement(): JsonElement = JsonNull
    }

    companion object {
        fun of(value: String): FilterValue = Str(value)
        fun of(value: Int): FilterValue = Num(value.toDouble())
        fun of(value: Long): FilterValue = Num(value.toDouble())
        fun of(value: Double): FilterValue = Num(if (value == 0.0) 0.0 else value)
        fun of(value: Boolean): FilterValue = Bool(value)
    }
}

data class FilterCondition(
    val op: String,
    val value: FilterValue? = null,
    val values: List<FilterValue>? = null,
    val from: String? = null,
    val to: String? = null,
    val offset: String? = null,
    val sql: String? = null,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("op", op)
        if (value != null) put("value", value.toJsonElement())
        if (values != null) {
            put("values", JsonArray(values.map { it.toJsonElement() }))
        }
        if (from != null) put("from", from)
        if (to != null) put("to", to)
        if (offset != null) put("offset", offset)
        if (sql != null) put("sql", sql)
    }
}

data class FilterGroup(
    val column: String,
    val anyOf: List<List<FilterCondition>>,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("column", column)
        put(
            "anyOf",
            buildJsonArray {
                for (andGroup in anyOf) {
                    add(
                        buildJsonArray {
                            for (cond in andGroup) {
                                add(cond.toJsonObject())
                            }
                        },
                    )
                }
            },
        )
    }
}

data class FilterSyntaxError(
    val message: String,
    val start: Int,
    val end: Int,
)

sealed interface FilterParseResult {
    val ok: Boolean

    data class Ok(val anyOf: List<List<FilterCondition>>) : FilterParseResult {
        override val ok: Boolean = true
    }

    data class Err(val error: FilterSyntaxError) : FilterParseResult {
        override val ok: Boolean = false
    }
}

sealed interface AnyColumnParseResult {
    val ok: Boolean

    data class Ok(val groups: List<FilterGroup>) : AnyColumnParseResult {
        override val ok: Boolean = true
    }

    data class Err(val error: FilterSyntaxError) : AnyColumnParseResult {
        override val ok: Boolean = false
    }
}

enum class FilterSyntax(val id: String) {
    TEXT("text"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    BINARY("binary"),
}

fun filterSyntax(kind: ColumnKind): FilterSyntax = when (kind) {
    ColumnKind.NUMBER -> FilterSyntax.NUMBER
    ColumnKind.BOOLEAN -> FilterSyntax.BOOLEAN
    ColumnKind.BINARY -> FilterSyntax.BINARY
    ColumnKind.DATE, ColumnKind.DATETIME, ColumnKind.DATETIMETZ -> FilterSyntax.DATE
    ColumnKind.TEXT, ColumnKind.TIME, ColumnKind.JSON, ColumnKind.OTHER -> FilterSyntax.TEXT
}

data class FilterableColumn(
    val name: String,
    val kind: ColumnKind,
)

// ── Internal Lexer & Parser (`db-filter-parser.ts`) ──────────────────────────

private val OPERATORS = listOf("!=", "<>", "<=", ">=", "!^", "!$", "=", "<", ">", "+", "~", "^", "$")
private val TEXT_ONLY_OPS = setOf("+", "~", "^", "!^", "$", "!$")

private val NUMBER_REGEX = Regex("""^-?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$""", RegexOption.IGNORE_CASE)
private val INTEGER_REGEX = Regex("""^-?\d+$""")
private val EXP_SUFFIX_REGEX = Regex("""e.*$""", RegexOption.IGNORE_CASE)
private val DATE_LITERAL_REGEX = Regex(
    """^(\d{4})(?:-(\d{2})(?:-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,6}))?)?(Z|[+-]\d{2}(?::?\d{2})?)?)?)?)?$""",
    RegexOption.IGNORE_CASE,
)
private val DATE_ONLY_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
private val TIME_ONLY_REGEX = Regex("""^\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?(?:Z|[+-]\d{2}(?::?\d{2})?)?$""", RegexOption.IGNORE_CASE)

private class FilterTextException(
    override val message: String,
    val start: Int,
    val end: Int,
) : RuntimeException(message)

private fun fail(message: String, start: Int, end: Int): Nothing {
    throw FilterTextException(message, start, end)
}

private data class Term(
    val op: String?,
    val text: String,
    val quoted: Boolean,
    val sql: Boolean,
    val start: Int,
    val valueStart: Int,
    val end: Int,
)

private fun isAsciiOrUnicodeSpace(ch: Char): Boolean = ch.isWhitespace()

private fun skipSpace(text: String, start: Int): Int {
    var i = start
    while (i < text.length && isAsciiOrUnicodeSpace(text[i])) i++
    return i
}

private data class QuotedPiece(val value: String, val end: Int)

private fun readQuoted(text: String, open: Int): QuotedPiece {
    val quote = text[open]
    val sb = StringBuilder()
    var i = open + 1
    while (i < text.length) {
        val ch = text[i]
        if (ch != quote) {
            sb.append(ch)
        } else if (i + 1 < text.length && text[i + 1] == quote) {
            sb.append(quote)
            i++
        } else {
            return QuotedPiece(sb.toString(), i + 1)
        }
        i++
    }
    fail("Missing closing $quote", open, text.length)
}

private data class SqlPiece(val sql: String, val end: Int)

private fun readSql(text: String, open: Int): SqlPiece {
    var depth = 0
    var quote: Char? = null
    var semicolon = -1
    var i = open
    while (i < text.length) {
        val ch = text[i]
        if (quote != null) {
            if (ch == quote) quote = null
        } else if (ch == '\'' || ch == '"' || ch == '`') {
            quote = ch
        } else if (ch == ';') {
            if (semicolon < 0) semicolon = i
        } else if (ch == '{') {
            depth++
        } else if (ch == '}') {
            depth--
            if (depth == 0) {
                val sql = text.substring(open + 1, i).trim()
                if (sql.isEmpty()) fail("The SQL condition is empty", open, i + 1)
                if (sql.length > FILTER_MAX_RAW_SQL) {
                    fail("An SQL condition may hold at most $FILTER_MAX_RAW_SQL characters", open, i + 1)
                }
                if (semicolon >= 0) {
                    fail("An SQL condition may not contain \";\"", semicolon, semicolon + 1)
                }
                return SqlPiece(sql, i + 1)
            }
        }
        i++
    }
    fail("Missing closing }", open, text.length)
}

private fun readTerm(text: String, start: Int): Term {
    val op = OPERATORS.firstOrNull { text.startsWith(it, start) }
    var i = start
    if (op != null) {
        i = skipSpace(text, start + op.length)
        if (i == text.length || text[i] == ',') {
            fail("Expected a value after \"$op\"", start, start + op.length)
        }
    }
    val ch = text[i]
    if (ch == '\'' || ch == '"') {
        val q = readQuoted(text, i)
        return Term(op = op, text = q.value, quoted = true, sql = false, start = start, valueStart = i, end = q.end)
    }
    if (ch == '{' && op == null) {
        val s = readSql(text, i)
        return Term(op = null, text = s.sql, quoted = false, sql = true, start = start, valueStart = i, end = s.end)
    }
    var end = i
    while (end < text.length && !isAsciiOrUnicodeSpace(text[end]) && text[end] != ',') end++
    return Term(op = op, text = text.substring(i, end), quoted = false, sql = false, start = start, valueStart = i, end = end)
}

private fun readTerms(text: String): List<List<Term>> {
    var i = skipSpace(text, 0)
    if (i == text.length) return emptyList()
    val groups = mutableListOf(mutableListOf<Term>())
    while (true) {
        if (text[i] == ',') fail("Expected a value before \",\"", i, i + 1)
        val term = readTerm(text, i)
        groups.last().add(term)
        val next = skipSpace(text, term.end)
        if (next == text.length) return groups
        if (text[next] == ',') {
            i = skipSpace(text, next + 1)
            if (i == text.length) fail("Expected a value after \",\"", next, next + 1)
            groups.add(mutableListOf())
        } else if (next == term.end) {
            fail("Expected a space or a comma", next, next + 1)
        } else {
            i = next
        }
    }
}

private fun wordOf(t: Term?): String? =
    if (t != null && t.op == null && !t.quoted && !t.sql) t.text.uppercase(Locale.US) else null

private data class ReadStep(
    val alt: List<List<FilterCondition>>,
    val used: Int = 1,
)

private fun one(cond: FilterCondition, used: Int = 1): ReadStep =
    ReadStep(alt = listOf(listOf(cond)), used = used)

private fun opFail(t: Term, columns: String): Nothing {
    val opLen = t.op?.length ?: 0
    fail("\"${t.op}\" does not work on $columns columns", t.start, t.start + opLen)
}

private fun readText(terms: List<Term>, k: Int): ReadStep {
    val t = terms[k]
    val word = wordOf(t)
    if (word == "EMPTY") return one(FilterCondition(op = "isEmpty"))
    if (word == "NOT" && wordOf(terms.getOrNull(k + 1)) == "EMPTY") {
        return one(FilterCondition(op = "notEmpty"), used = 2)
    }
    val value = FilterValue.Str(t.text)
    val condOp = when (t.op) {
        null, "+" -> "contains"
        "~" -> "notContains"
        "^" -> "startsWith"
        "!^" -> "notStartsWith"
        "$" -> "endsWith"
        "!$" -> "notEndsWith"
        "=" -> "eq"
        "!=", "<>" -> "ne"
        "<" -> "lt"
        ">" -> "gt"
        "<=" -> "le"
        ">=" -> "ge"
        else -> "contains"
    }
    return one(FilterCondition(op = condOp, value = value))
}

private fun numberValue(text: String): FilterValue {
    val n = text.toDoubleOrNull()
    if (n == null || !n.isFinite()) return FilterValue.Str(text)
    if (INTEGER_REGEX.matches(text)) {
        val asLong = text.toLongOrNull()
        return if (asLong != null && abs(asLong) <= MAX_SAFE_INTEGER) {
            FilterValue.Num(if (n == 0.0) 0.0 else n)
        } else {
            FilterValue.Str(text)
        }
    }
    val mantissa = text.removePrefix("-").replace(EXP_SUFFIX_REGEX, "").replace(".", "")
    val significant = mantissa.trimStart('0').trimEnd('0')
    val exact = significant.length <= 15 && !(n == 0.0 && significant.isNotEmpty())
    return if (exact) FilterValue.Num(if (n == 0.0) 0.0 else n) else FilterValue.Str(text)
}

private fun readNumber(t: Term): ReadStep {
    if (t.op != null && t.op in TEXT_ONLY_OPS) opFail(t, "number")
    if (!NUMBER_REGEX.matches(t.text)) fail("\"${t.text}\" is not a number", t.valueStart, t.end)
    val value = numberValue(t.text)
    val condOp = when (t.op) {
        "!=", "<>" -> "ne"
        "<" -> "lt"
        ">" -> "gt"
        "<=" -> "le"
        ">=" -> "ge"
        else -> "eq"
    }
    return one(FilterCondition(op = condOp, value = value))
}

private fun readBoolean(t: Term): ReadStep {
    if (t.op != null && t.op != "=") opFail(t, "boolean")
    return when (t.text.uppercase(Locale.US)) {
        "TRUE", "1" -> one(FilterCondition(op = "isTrue"))
        "FALSE", "0" -> one(FilterCondition(op = "isFalse"))
        else -> fail("\"${t.text}\" is not TRUE or FALSE", t.valueStart, t.end)
    }
}

private data class Civil(
    val y: Int,
    val mo: Int = 1,
    val d: Int = 1,
    val h: Int = 0,
    val mi: Int = 0,
    val s: Int = 0,
    val frac: String = "",
)

private enum class Precision {
    YEAR,
    MONTH,
    DAY,
    MINUTE,
    SECOND,
    FRACTION,
}

private enum class ShiftUnit {
    YEAR,
    MONTH,
    DAY,
    MINUTE,
    SECOND,
}

private fun shift(c: Civil, unit: ShiftUnit, n: Int): Civil {
    val ldt = LocalDateTime.of(c.y, c.mo, c.d, c.h, c.mi, c.s)
    val shifted = when (unit) {
        ShiftUnit.YEAR -> ldt.plusYears(n.toLong())
        ShiftUnit.MONTH -> ldt.plusMonths(n.toLong())
        ShiftUnit.DAY -> ldt.plusDays(n.toLong())
        ShiftUnit.MINUTE -> ldt.plusMinutes(n.toLong())
        ShiftUnit.SECOND -> ldt.plusSeconds(n.toLong())
    }
    return Civil(
        y = shifted.year,
        mo = shifted.monthValue,
        d = shifted.dayOfMonth,
        h = shifted.hour,
        mi = shifted.minute,
        s = shifted.second,
        frac = c.frac,
    )
}

private fun spanEnd(start: Civil, precision: Precision): Civil = when (precision) {
    Precision.YEAR -> shift(start, ShiftUnit.YEAR, 1)
    Precision.MONTH -> shift(start, ShiftUnit.MONTH, 1)
    Precision.DAY -> shift(start, ShiftUnit.DAY, 1)
    Precision.MINUTE -> shift(start, ShiftUnit.MINUTE, 1)
    Precision.SECOND -> shift(start, ShiftUnit.SECOND, 1)
    Precision.FRACTION -> {
        val digits = start.frac.length
        val next = start.frac.toLong() + 1L
        val limit = 10.0.pow(digits.toDouble()).toLong()
        if (next < limit) {
            start.copy(frac = next.toString().padStart(digits, '0'))
        } else {
            shift(start, ShiftUnit.SECOND, 1).copy(frac = "0".repeat(digits))
        }
    }
}

private fun daysIn(y: Int, mo: Int): Int =
    YearMonth.of(y, mo).lengthOfMonth()

private fun pad(n: Int, width: Int = 2): String =
    n.toString().padStart(width, '0')

private fun wallClock(c: Civil): String {
    val base = "${pad(c.y, 4)}-${pad(c.mo)}-${pad(c.d)} ${pad(c.h)}:${pad(c.mi)}:${pad(c.s)}"
    return if (c.frac.isNotEmpty()) "$base.${c.frac}" else base
}

private fun formatOffset(minutes: Int): String {
    val absMin = abs(minutes)
    val sign = if (minutes < 0) "-" else "+"
    return "$sign${pad(absMin / 60)}:${pad(absMin % 60)}"
}

private fun localOffset(c: Civil, zone: ZoneId): String {
    val ldt = LocalDateTime.of(c.y, c.mo, c.d, c.h, c.mi, c.s)
    val zdt = ldt.atZone(zone)
    val roundedMinutes = (zdt.offset.totalSeconds / 60.0).roundToInt()
    return formatOffset(roundedMinutes)
}

private data class DateLiteral(
    val start: Civil,
    val precision: Precision,
    val zoneMinutes: Int?,
)

private fun readDateLiteral(text: String): DateLiteral? {
    val m = DATE_LITERAL_REGEX.matchEntire(text) ?: return null
    val y = m.groupValues[1].toIntOrNull() ?: return null
    val moStr = m.groupValues[2]
    val dStr = m.groupValues[3]
    val hStr = m.groupValues[4]
    val miStr = m.groupValues[5]
    val sStr = m.groupValues[6]
    val fracStr = m.groupValues[7]
    val zoneStr = m.groupValues[8]

    val start = Civil(
        y = y,
        mo = if (moStr.isNotEmpty()) moStr.toInt() else 1,
        d = if (dStr.isNotEmpty()) dStr.toInt() else 1,
        h = if (hStr.isNotEmpty()) hStr.toInt() else 0,
        mi = if (miStr.isNotEmpty()) miStr.toInt() else 0,
        s = if (sStr.isNotEmpty()) sStr.toInt() else 0,
        frac = fracStr,
    )
    if (start.mo !in 1..12 || start.d !in 1..daysIn(start.y, start.mo)) return null
    if (start.h > 23 || start.mi > 59 || start.s > 59) return null

    val precision = when {
        fracStr.isNotEmpty() -> Precision.FRACTION
        sStr.isNotEmpty() -> Precision.SECOND
        miStr.isNotEmpty() -> Precision.MINUTE
        dStr.isNotEmpty() -> Precision.DAY
        moStr.isNotEmpty() -> Precision.MONTH
        else -> Precision.YEAR
    }

    var zoneMinutes: Int? = null
    if (zoneStr.isNotEmpty()) {
        if (zoneStr.equals("Z", ignoreCase = true)) {
            zoneMinutes = 0
        } else {
            val hours = zoneStr.substring(1, 3).toIntOrNull() ?: return null
            val minsPart = zoneStr.substring(3).replace(":", "")
            val mins = if (minsPart.isEmpty()) 0 else (minsPart.toIntOrNull() ?: return null)
            if (hours > 23 || mins > 59) return null
            val sign = if (zoneStr[0] == '-') -1 else 1
            zoneMinutes = sign * (hours * 60 + mins)
        }
    }
    return DateLiteral(start = start, precision = precision, zoneMinutes = zoneMinutes)
}

private data class BoundPoint(val at: String, val offset: String)

private fun spanConditions(
    t: Term,
    start: Civil,
    end: Civil,
    explicitZoneOffset: String?,
    deviceZone: ZoneId,
): List<List<FilterCondition>> {
    fun bound(c: Civil): BoundPoint = BoundPoint(
        at = wallClock(c),
        offset = explicitZoneOffset ?: localOffset(c, deviceZone),
    )
    val a = bound(start)
    val b = if (end.y <= 9999) bound(end) else null
    fun from(x: BoundPoint) = FilterCondition(op = "dateRange", from = x.at, offset = x.offset)
    fun to(x: BoundPoint) = FilterCondition(op = "dateRange", to = x.at, offset = x.offset)

    return when (t.op) {
        "!=", "<>" -> if (b != null) listOf(listOf(to(a)), listOf(from(b))) else listOf(listOf(to(a)))
        ">" -> {
            if (b == null) fail("Nothing comes after the year 9999", t.start, t.end)
            listOf(listOf(from(b)))
        }
        ">=" -> listOf(listOf(from(a)))
        "<" -> listOf(listOf(to(a)))
        "<=" -> if (b != null) listOf(listOf(to(b))) else listOf(listOf(FilterCondition(op = "notNull")))
        else -> {
            if (b == null) {
                listOf(listOf(from(a)))
            } else if (a.offset == b.offset) {
                listOf(listOf(FilterCondition(op = "dateRange", from = a.at, to = b.at, offset = a.offset)))
            } else {
                listOf(listOf(from(a), to(b)))
            }
        }
    }
}

private val DAY_WORDS = mapOf("YESTERDAY" to -1, "TODAY" to 0, "TOMORROW" to 1)
private val PERIOD_STEP = mapOf("LAST" to -1, "THIS" to 0, "NEXT" to 1)

private fun periodSpan(now: Instant, zone: ZoneId, step: Int, unit: String): Pair<Civil, Civil> {
    val zdt = now.atZone(zone)
    val today = Civil(zdt.year, zdt.monthValue, zdt.dayOfMonth)
    return when (unit) {
        "WEEK" -> {
            val dayOffsetFromMonday = zdt.dayOfWeek.value - 1 // Monday=0 .. Sunday=6
            val monday = shift(today, ShiftUnit.DAY, 7 * step - dayOffsetFromMonday)
            monday to shift(monday, ShiftUnit.DAY, 7)
        }
        "MONTH" -> {
            val first = shift(Civil(today.y, today.mo), ShiftUnit.MONTH, step)
            first to shift(first, ShiftUnit.MONTH, 1)
        }
        else -> {
            val first = Civil(today.y + step)
            first to shift(first, ShiftUnit.YEAR, 1)
        }
    }
}

private fun readDate(terms: List<Term>, k: Int, now: Instant, zone: ZoneId): ReadStep {
    val t = terms[k]
    val next = terms.getOrNull(k + 1)
    if (t.op != null && t.op in TEXT_ONLY_OPS) opFail(t, "date")
    if (!t.quoted) {
        val word = t.text.uppercase(Locale.US)
        val days = DAY_WORDS[word]
        if (days != null) {
            val zdt = now.atZone(zone)
            val start = shift(Civil(zdt.year, zdt.monthValue, zdt.dayOfMonth), ShiftUnit.DAY, days)
            return ReadStep(
                alt = spanConditions(t, start, shift(start, ShiftUnit.DAY, 1), null, zone),
                used = 1,
            )
        }
        val step = PERIOD_STEP[word]
        if (step != null) {
            val unit = wordOf(next)
            if (unit != "WEEK" && unit != "MONTH" && unit != "YEAR") {
                fail("Expected WEEK, MONTH or YEAR after $word", t.valueStart, t.end)
            }
            val (start, end) = periodSpan(now, zone, step, unit)
            return ReadStep(
                alt = spanConditions(t.copy(end = next!!.end), start, end, null, zone),
                used = 2,
            )
        }
    }
    val joined = !t.quoted && DATE_ONLY_REGEX.matches(t.text) && wordOf(next) != null && next != null && TIME_ONLY_REGEX.matches(next.text)
    val term = if (joined) t.copy(text = "${t.text} ${next!!.text}", end = next.end) else t
    val literal = readDateLiteral(term.text)
        ?: fail("\"${term.text}\" is not a date — $DATE_HINT", term.valueStart, term.end)
    val used = if (joined) 2 else 1
    if (literal.zoneMinutes == null) {
        return ReadStep(
            alt = spanConditions(term, literal.start, spanEnd(literal.start, literal.precision), null, zone),
            used = used,
        )
    }
    val startUtc = shift(literal.start, ShiftUnit.MINUTE, -literal.zoneMinutes)
    return ReadStep(
        alt = spanConditions(term, startUtc, spanEnd(startUtc, literal.precision), "+00:00", zone),
        used = used,
    )
}

private fun readCondition(terms: List<Term>, k: Int, syntax: FilterSyntax, now: Instant, zone: ZoneId): ReadStep {
    val t = terms[k]
    if (t.sql) return one(FilterCondition(op = "rawSql", sql = t.text))
    val word = wordOf(t)
    if (word == "NULL") return one(FilterCondition(op = "isNull"))
    if (word == "NOT" && wordOf(terms.getOrNull(k + 1)) == "NULL") {
        return one(FilterCondition(op = "notNull"), used = 2)
    }
    return when (syntax) {
        FilterSyntax.TEXT -> readText(terms, k)
        FilterSyntax.NUMBER -> readNumber(t)
        FilterSyntax.BOOLEAN -> readBoolean(t)
        FilterSyntax.DATE -> readDate(terms, k, now, zone)
        FilterSyntax.BINARY -> fail(
            "A binary column can only be filtered with NULL, NOT NULL or an SQL condition in braces",
            t.start,
            t.end,
        )
    }
}

private fun readAndGroup(
    terms: List<Term>,
    syntax: FilterSyntax,
    now: Instant,
    zone: ZoneId,
): List<List<FilterCondition>> {
    var all: List<List<FilterCondition>> = listOf(emptyList())
    var k = 0
    while (k < terms.size) {
        val step = readCondition(terms, k, syntax, now, zone)
        if (all.size * step.alt.size > MAX_ALTERNATIVES) {
            fail("Too many conditions joined with spaces", terms[k].start, terms[k + step.used - 1].end)
        }
        all = all.flatMap { left -> step.alt.map { right -> left + right } }
        k += step.used
    }
    return all
}

private fun foldEquals(anyOf: List<List<FilterCondition>>, text: String): List<List<FilterCondition>> {
    fun isSingleEq(group: List<FilterCondition>): Boolean =
        group.size == 1 && group[0].op == "eq" && group[0].value != null

    val values = anyOf.filter(::isSingleEq).map { it[0].value!! }
    if (values.size < 2) return anyOf
    if (values.size > FILTER_MAX_IN_VALUES) {
        fail("More than $FILTER_MAX_IN_VALUES values", 0, text.length)
    }
    val at = anyOf.indexOfFirst(::isSingleEq)
    val rest = anyOf.filterNot(::isSingleEq)
    return rest.subList(0, at) + listOf(listOf(FilterCondition(op = "in", values = values))) + rest.subList(at, rest.size)
}

/**
 * Parse a single column's filter text using DBGate's syntax rules.
 * `now` and `zone` default to the device's clock and time zone (`ZoneId.systemDefault()`),
 * and can be overridden in unit tests.
 */
fun parseFilter(
    text: String,
    kind: ColumnKind,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): FilterParseResult {
    return try {
        val syntax = filterSyntax(kind)
        val anyOf = readTerms(text).flatMap { terms -> readAndGroup(terms, syntax, now, zone) }
        FilterParseResult.Ok(foldEquals(anyOf, text))
    } catch (e: FilterTextException) {
        FilterParseResult.Err(FilterSyntaxError(message = e.message, start = e.start, end = e.end))
    }
}

/**
 * Parse the Multi-column filter (`anyColumn`) across all table columns.
 */
fun parseAnyColumnFilter(
    text: String,
    columns: List<FilterableColumn>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): AnyColumnParseResult {
    try {
        val sqlTerm = readTerms(text).flatten().firstOrNull { it.sql }
        if (sqlTerm != null) {
            fail("An SQL condition only works in one column's own filter", sqlTerm.start, sqlTerm.end)
        }
    } catch (e: FilterTextException) {
        return AnyColumnParseResult.Err(FilterSyntaxError(message = e.message, start = e.start, end = e.end))
    }

    val groups = columns.mapNotNull { col ->
        when (val res = parseFilter(text, col.kind, now, zone)) {
            is FilterParseResult.Ok -> if (res.anyOf.isNotEmpty()) FilterGroup(column = col.name, anyOf = res.anyOf) else null
            is FilterParseResult.Err -> null
        }
    }
    if (groups.isEmpty() && text.trim().isNotEmpty()) {
        return AnyColumnParseResult.Err(
            FilterSyntaxError(message = "No column can read this filter", start = 0, end = text.length),
        )
    }
    return AnyColumnParseResult.Ok(groups)
}

// ── Grid Filters State & Tab Persistence (`grid-filters.ts`) ─────────────────

data class FilterText(
    val text: String,
    val off: Boolean = false,
)

data class GridFilters(
    val columns: Map<String, FilterText> = emptyMap(),
    val multi: FilterText? = null,
)

val NO_FILTERS = GridFilters()

sealed interface FilterState {
    val stateName: String

    data object Empty : FilterState {
        override val stateName: String = "empty"
    }

    data object Ok : FilterState {
        override val stateName: String = "ok"
    }

    data object Off : FilterState {
        override val stateName: String = "off"
    }

    data class Bad(val error: FilterSyntaxError) : FilterState {
        override val stateName: String = "bad"
    }
}

fun filterableColumns(schema: List<GridColumnSchema>, dbType: String?): List<FilterableColumn> =
    schema.map { col ->
        FilterableColumn(
            name = col.name,
            kind = if (!dbType.isNullOrBlank()) classifyColumnType(dbType, col.type) else ColumnKind.OTHER,
        )
    }

fun columnFilterState(
    filter: FilterText?,
    kind: ColumnKind,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): FilterState {
    if (filter == null || filter.text.trim().isEmpty()) return FilterState.Empty
    return when (val res = parseFilter(filter.text, kind, now, zone)) {
        is FilterParseResult.Err -> FilterState.Bad(res.error)
        is FilterParseResult.Ok -> if (filter.off) FilterState.Off else FilterState.Ok
    }
}

fun multiFilterState(
    filter: FilterText?,
    columns: List<FilterableColumn>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): FilterState {
    if (filter == null || filter.text.trim().isEmpty()) return FilterState.Empty
    return when (val res = parseAnyColumnFilter(filter.text, columns, now, zone)) {
        is AnyColumnParseResult.Err -> FilterState.Bad(res.error)
        is AnyColumnParseResult.Ok -> if (filter.off) FilterState.Off else FilterState.Ok
    }
}

data class FilterRequest(
    val filters: List<FilterGroup> = emptyList(),
    val anyColumn: List<FilterGroup> = emptyList(),
) {
    val isEmpty: Boolean
        get() = filters.isEmpty() && anyColumn.isEmpty()

    fun toFiltersJsonArray(): JsonArray = buildJsonArray {
        for (g in filters) add(g.toJsonObject())
    }

    fun toAnyColumnJsonArray(): JsonArray = buildJsonArray {
        for (g in anyColumn) add(g.toJsonObject())
    }
}

fun filterRequest(
    filters: GridFilters,
    columns: List<FilterableColumn>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): FilterRequest {
    val groups = columns.mapNotNull { col ->
        val f = filters.columns[col.name] ?: return@mapNotNull null
        if (f.off) return@mapNotNull null
        when (val res = parseFilter(f.text, col.kind, now, zone)) {
            is FilterParseResult.Ok -> if (res.anyOf.isNotEmpty()) FilterGroup(column = col.name, anyOf = res.anyOf) else null
            is FilterParseResult.Err -> null
        }
    }
    val multi = filters.multi
    val anyGroups = if (multi != null && !multi.off) {
        when (val res = parseAnyColumnFilter(multi.text, columns, now, zone)) {
            is AnyColumnParseResult.Ok -> res.groups
            is AnyColumnParseResult.Err -> emptyList()
        }
    } else {
        emptyList()
    }
    return FilterRequest(filters = groups, anyColumn = anyGroups)
}

fun withColumnFilter(filters: GridFilters, column: String, text: String): GridFilters {
    val nextCols = filters.columns.toMutableMap()
    if (text.trim().isNotEmpty()) {
        nextCols[column] = FilterText(text = text, off = false)
    } else {
        nextCols.remove(column)
    }
    return filters.copy(columns = nextCols)
}

fun withMultiFilter(filters: GridFilters, text: String): GridFilters =
    if (text.trim().isNotEmpty()) {
        filters.copy(multi = FilterText(text = text, off = false))
    } else {
        filters.copy(multi = null)
    }

private fun switched(f: FilterText, off: Boolean): FilterText =
    if (off) FilterText(text = f.text, off = true) else FilterText(text = f.text, off = false)

fun withFilterOff(filters: GridFilters, column: String?, off: Boolean): GridFilters {
    if (column == null) {
        val m = filters.multi ?: return filters
        return filters.copy(multi = switched(m, off))
    }
    val f = filters.columns[column] ?: return filters
    val nextCols = filters.columns.toMutableMap()
    nextCols[column] = switched(f, off)
    return filters.copy(columns = nextCols)
}

fun hasFilters(filters: GridFilters): Boolean =
    filters.columns.isNotEmpty() || (filters.multi?.text?.trim()?.isNotEmpty() == true)

fun filtersOnColumns(filters: GridFilters, columns: List<FilterableColumn>): GridFilters {
    val names = columns.mapTo(HashSet()) { it.name }
    val kept = filters.columns.filterKeys { it in names }
    return if (kept.size == filters.columns.size) filters else filters.copy(columns = kept)
}

const val TAB_FILTERS_FIELD = "filters"
private const val CAP_COLUMNS = 1_000
private const val CAP_NAME = 1_000
private const val CAP_TEXT = 10_000

private fun parseKeptFilterText(el: JsonElement?): FilterText? {
    val obj = el as? JsonObject ?: return null
    val text = obj["text"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return null
    if (text.trim().isEmpty() || text.length > CAP_TEXT) return null
    val off = obj["off"]?.jsonPrimitive?.booleanOrNull == true
    return switched(FilterText(text), off)
}

fun encodeGridFiltersJson(filters: GridFilters): String {
    val obj = buildJsonObject {
        put(
            "columns",
            buildJsonObject {
                for ((col, ft) in filters.columns) {
                    put(
                        col,
                        buildJsonObject {
                            put("text", ft.text)
                            if (ft.off) put("off", true)
                        },
                    )
                }
            },
        )
        val m = filters.multi
        if (m != null && m.text.trim().isNotEmpty()) {
            put(
                "multi",
                buildJsonObject {
                    put("text", m.text)
                    if (m.off) put("off", true)
                },
            )
        }
    }
    return obj.toString()
}

fun decodeGridFiltersJson(rawJson: String?): GridFilters {
    if (rawJson.isNullOrBlank()) return NO_FILTERS
    val root = runCatching { Json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return NO_FILTERS
    val colsObj = root["columns"] as? JsonObject
    val cols = mutableMapOf<String, FilterText>()
    if (colsObj != null) {
        for ((name, el) in colsObj.entries.take(CAP_COLUMNS)) {
            if (name.isNotEmpty() && name.length <= CAP_NAME) {
                val ft = parseKeptFilterText(el)
                if (ft != null) cols[name] = ft
            }
        }
    }
    val multi = parseKeptFilterText(root["multi"])
    return GridFilters(columns = cols, multi = multi)
}

fun readTabFilters(metadata: Map<String, String>): GridFilters =
    decodeGridFiltersJson(metadata[TAB_FILTERS_FIELD])

fun withTabFilters(metadata: Map<String, String>, filters: GridFilters): Map<String, String> {
    val next = metadata.toMutableMap()
    if (hasFilters(filters)) {
        next[TAB_FILTERS_FIELD] = encodeGridFiltersJson(filters)
    } else {
        next.remove(TAB_FILTERS_FIELD)
    }
    return next
}

enum class LinesMode(val id: String, val label: String, val operatorPrefix: String) {
    IS("is", "Is one of line", "="),
    IS_NOT("isNot", "Is not one of line", "<>"),
    CONTAINS("contains", "Contains", ""),
    BEGINS("begins", "Begins", "^"),
    ENDS("ends", "Ends", "$"),
}

fun linesFilter(mode: LinesMode, text: String): String {
    val sep = if (mode == LinesMode.IS_NOT) " " else ","
    return text
        .split(Regex("""\r?\n"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(sep) { line ->
            "${mode.operatorPrefix}'${line.replace("'", "''")}'"
        }
}

/**
 * Determine which column filter cells are to blame when the server rejects a filtered grid query
 * (e.g. 400 invalid SQL expression or 403 write attempt in `{$$ ...}` on a readonly connection).
 */
fun refusedFilters(
    request: FilterRequest,
    statusCode: Int?,
    errorMessage: String,
): Map<String, String>? {
    if (statusCode == null || statusCode == 404 || statusCode == 428) return null
    val rawSqlGroups = request.filters.filter { g ->
        g.anyOf.any { andGroup -> andGroup.any { c -> c.op == "rawSql" } }
    }
    val blamed = when {
        rawSqlGroups.isNotEmpty() -> rawSqlGroups
        statusCode == 400 || statusCode == 403 -> request.filters
        else -> emptyList()
    }
    return if (blamed.isNotEmpty()) {
        blamed.associate { it.column to errorMessage }
    } else {
        null
    }
}

// ── Value Picker & FK Lookup Helpers (`value-filter-text.ts`) ─────────────────

fun canChooseValues(kind: ColumnKind): Boolean =
    kind != ColumnKind.BINARY && kind != ColumnKind.JSON

fun valueText(cell: DbCellValue): String = when (cell) {
    DbCellValue.Null, DbCellValue.NoField -> "NULL"
    is DbCellValue.Bool -> cell.value.toString()
    is DbCellValue.Num -> cell.rawText
    is DbCellValue.Text -> cell.value
    is DbCellValue.Binary -> formatBinary(cell)
    is DbCellValue.JsonArr -> cell.rawJson
    is DbCellValue.JsonObj -> cell.rawJson
}

fun quotedFilterValue(text: String): String =
    "\"${text.replace("\"", "\"\"")}\""

private val TRUE_TEXT_SET = setOf("1", "true", "t")
private val FALSE_TEXT_SET = setOf("0", "false", "f")

fun valueTermCell(kind: ColumnKind, value: DbCellValue): String {
    if (value is DbCellValue.Null || value is DbCellValue.NoField) return "NULL"
    val raw = valueText(value)
    if (filterSyntax(kind) == FilterSyntax.BOOLEAN) {
        val lower = raw.lowercase(Locale.US)
        if (lower in TRUE_TEXT_SET) return "TRUE"
        if (lower in FALSE_TEXT_SET) return "FALSE"
    }
    return "=${quotedFilterValue(raw)}"
}

fun pickedCellValuesFilter(kind: ColumnKind, values: List<DbCellValue>): String =
    values.joinToString(",") { valueTermCell(kind, it) }

fun cellValueKey(value: DbCellValue): String = when (value) {
    DbCellValue.Null, DbCellValue.NoField -> "null"
    is DbCellValue.Bool -> "boolean:${value.value}"
    is DbCellValue.Num -> "number:${value.rawText}"
    is DbCellValue.Text -> "string:${value.value}"
    is DbCellValue.Binary -> "binary:${value.base64}"
    is DbCellValue.JsonArr -> "object:${value.rawJson}"
    is DbCellValue.JsonObj -> "object:${value.rawJson}"
}

data class TableScope(
    val table: String,
    val schema: String? = null,
)

data class GridValuesRequestData(
    val table: String,
    val schema: String? = null,
    val column: String,
    val search: String = "",
    val filters: List<FilterGroup> = emptyList(),
    val anyColumn: List<FilterGroup> = emptyList(),
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("table", table)
        if (!schema.isNullOrBlank()) put("schema", schema)
        put("column", column)
        if (search.isNotEmpty()) put("search", search)
        if (filters.isNotEmpty()) {
            put("filters", buildJsonArray { filters.forEach { add(it.toJsonObject()) } })
        }
        if (anyColumn.isNotEmpty()) {
            put("anyColumn", buildJsonArray { anyColumn.forEach { add(it.toJsonObject()) } })
        }
    }
}

fun valuesRequest(
    scope: TableScope,
    column: String,
    search: String,
    filters: GridFilters,
    columns: List<FilterableColumn>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): GridValuesRequestData {
    val others = filterRequest(withColumnFilter(filters, column, ""), columns, now, zone)
    return GridValuesRequestData(
        table = scope.table,
        schema = scope.schema,
        column = column,
        search = search.trim(),
        filters = others.filters,
        anyColumn = others.anyColumn,
    )
}

fun lookupRowsRequest(
    scope: TableScope,
    keyColumn: String,
    anyColumn: List<FilterGroup>,
): JsonObject = buildJsonObject {
    put("table", scope.table)
    if (!scope.schema.isNullOrBlank()) put("schema", scope.schema)
    if (anyColumn.isNotEmpty()) {
        put("anyColumn", buildJsonArray { anyColumn.forEach { add(it.toJsonObject()) } })
    }
    put(
        "sort",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("column", keyColumn)
                    put("dir", "ASC")
                },
            )
        },
    )
    put("offset", 0)
    put("limit", LOOKUP_ROWS)
}

fun lookupSearch(
    search: String,
    columns: List<FilterableColumn>,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<FilterGroup>? {
    val text = search.trim()
    if (text.isEmpty()) return emptyList()
    return when (val res = parseAnyColumnFilter(quotedFilterValue(text), columns, now, zone)) {
        is AnyColumnParseResult.Ok -> res.groups
        is AnyColumnParseResult.Err -> null
    }
}

fun defaultDescription(columns: List<FilterableColumn>, keyColumn: String): String? =
    columns.firstOrNull { it.kind == ColumnKind.TEXT && it.name != keyColumn }?.name

fun descriptionColumn(columns: List<FilterableColumn>, keyColumn: String, chosen: String?): String? =
    if (!chosen.isNullOrBlank() && chosen != keyColumn && columns.any { it.name == chosen }) {
        chosen
    } else {
        defaultDescription(columns, keyColumn)
    }

// ── Filter Funnel Menu & Set Filter Dialog (`filter-funnel-menu.ts`) ──────────

enum class FunnelKind(val id: String) {
    TEXT("text"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    BINARY("binary"),
    MULTI("multi"),
}

fun FilterSyntax.toFunnelKind(): FunnelKind = when (this) {
    FilterSyntax.TEXT -> FunnelKind.TEXT
    FilterSyntax.NUMBER -> FunnelKind.NUMBER
    FilterSyntax.BOOLEAN -> FunnelKind.BOOLEAN
    FilterSyntax.DATE -> FunnelKind.DATE
    FilterSyntax.BINARY -> FunnelKind.BINARY
}

data class ConditionChoice(
    val op: String,
    val label: String,
)

private val EQUALS_CHOICES = listOf(
    ConditionChoice("=", "equals"),
    ConditionChoice("<>", "does not equal"),
)
private val TEXT_CHOICES = listOf(
    ConditionChoice("+", "contains"),
    ConditionChoice("~", "does not contain"),
    ConditionChoice("^", "begins with"),
    ConditionChoice("!^", "does not begin with"),
    ConditionChoice("$", "ends with"),
    ConditionChoice("!$", "does not end with"),
)
private val ORDER_CHOICES = listOf(
    ConditionChoice("<", "is smaller"),
    ConditionChoice(">", "is greater"),
    ConditionChoice("<=", "is smaller or equal"),
    ConditionChoice(">=", "is greater or equal"),
)
private val DATES_CHOICES = listOf(
    ConditionChoice("<", "is before"),
    ConditionChoice(">", "is after"),
    ConditionChoice("<=", "is before or equal"),
    ConditionChoice(">=", "is after or equal"),
)
private val NULLS_CHOICES = listOf(
    ConditionChoice("NULL", "is NULL"),
    ConditionChoice("NOT NULL", "is not NULL"),
)
private val SQL_CHOICES = listOf(
    ConditionChoice("sql", "SQL condition"),
    ConditionChoice("sqlRight", "SQL condition - right side only"),
)

fun conditionChoices(kind: FunnelKind): List<ConditionChoice> = when (kind) {
    FunnelKind.TEXT -> EQUALS_CHOICES + TEXT_CHOICES + ORDER_CHOICES + NULLS_CHOICES + SQL_CHOICES
    FunnelKind.MULTI -> EQUALS_CHOICES + TEXT_CHOICES + ORDER_CHOICES + NULLS_CHOICES
    FunnelKind.NUMBER -> EQUALS_CHOICES + ORDER_CHOICES + NULLS_CHOICES + SQL_CHOICES
    FunnelKind.DATE -> DATES_CHOICES + NULLS_CHOICES + SQL_CHOICES
    FunnelKind.BOOLEAN, FunnelKind.BINARY -> NULLS_CHOICES + SQL_CHOICES
}

fun takesValue(op: String): Boolean = op != "NULL" && op != "NOT NULL"

sealed interface FilterDialogRequest {
    data class ConditionDialog(
        val kind: FunnelKind,
        val first: String,
        val second: String,
    ) : FilterDialogRequest

    data object LinesDialog : FilterDialogRequest
}

fun createConditionDialogRequest(
    kind: FunnelKind,
    first: String,
    second: String? = null,
): FilterDialogRequest.ConditionDialog {
    val ops = conditionChoices(kind).map { it.op }
    val resolvedSecond = second ?: if ("=" in ops) "=" else ops.first { takesValue(it) }
    return FilterDialogRequest.ConditionDialog(kind = kind, first = first, second = resolvedSecond)
}

sealed interface FunnelAction {
    data class WriteText(val text: String) : FunnelAction
    data class OpenDialog(val request: FilterDialogRequest) : FunnelAction
}

sealed interface FunnelItem {
    data class Entry(val label: String, val action: FunnelAction) : FunnelItem
    data object Separator : FunnelItem
}

private val DATE_WORDS_GROUPS = listOf(
    listOf("Tomorrow", "Today", "Yesterday"),
    listOf("Next Week", "This Week", "Last Week"),
    listOf("Next Month", "This Month", "Last Month"),
    listOf("Next Year", "This Year", "Last Year"),
)

fun funnelItems(kind: FunnelKind): List<FunnelItem> {
    fun write(label: String, text: String) = FunnelItem.Entry(label, FunnelAction.WriteText(text))
    fun open(label: String, first: String, second: String? = null) =
        FunnelItem.Entry(label, FunnelAction.OpenDialog(createConditionDialogRequest(kind, first, second)))

    val isText = kind == FunnelKind.TEXT || kind == FunnelKind.MULTI
    val isOrdered = isText || kind == FunnelKind.NUMBER
    val items = mutableListOf<FunnelItem>(write("Clear Filter", ""))
    if (kind != FunnelKind.BINARY) {
        items.add(FunnelItem.Entry("Filter multiple values", FunnelAction.OpenDialog(FilterDialogRequest.LinesDialog)))
    }
    if (isOrdered) {
        items.add(open("Equals...", "="))
        items.add(open("Does Not Equal...", "<>"))
    }
    items.add(write("Is Null", "NULL"))
    items.add(write("Is Not Null", "NOT NULL"))
    if (isText) {
        items.add(write("Is Empty Or Null", "EMPTY, NULL"))
        items.add(write("Has Not Empty Value", "NOT EMPTY NOT NULL"))
    }
    if (isOrdered) {
        items.add(FunnelItem.Separator)
        items.add(open("Greater Than...", ">"))
        items.add(open("Greater Than Or Equal To...", ">="))
        items.add(open("Less Than...", "<"))
        items.add(open("Less Than Or Equal To...", "<="))
    }
    if (isText) {
        items.add(FunnelItem.Separator)
        items.add(open("Contains...", "+"))
        items.add(open("Does Not Contain...", "~"))
        items.add(open("Begins With...", "^"))
        items.add(open("Does Not Begin With...", "!^"))
        items.add(open("Ends With...", "$"))
        items.add(open("Does Not End With...", "!$"))
    }
    if (kind == FunnelKind.BOOLEAN) {
        items.add(write("Is True", "TRUE"))
        items.add(write("Is False", "FALSE"))
        items.add(write("Is True or NULL", "TRUE, NULL"))
        items.add(write("Is False or NULL", "FALSE, NULL"))
    }
    if (kind == FunnelKind.DATE) {
        for (words in DATE_WORDS_GROUPS) {
            items.add(FunnelItem.Separator)
            for (w in words) {
                items.add(write(w, w.uppercase(Locale.US)))
            }
        }
        items.add(FunnelItem.Separator)
        items.add(open("Before...", "<="))
        items.add(open("After...", ">="))
        items.add(open("Between...", ">=", "<="))
    }
    if (kind != FunnelKind.MULTI) {
        items.add(FunnelItem.Separator)
        items.add(open("SQL condition ...", "sql"))
        items.add(open("SQL condition - right side ...", "sqlRight"))
    }
    return items
}

data class ConditionInput(
    val op: String,
    val value: String,
)

fun conditionText(kind: FunnelKind, condition: ConditionInput): String? {
    if (!takesValue(condition.op)) return condition.op
    val v = condition.value.trim()
    if (v.isEmpty()) return null
    if (condition.op == "sql") return "{$v}"
    if (condition.op == "sqlRight") return "{$$ $v}"
    return if (kind == FunnelKind.TEXT || kind == FunnelKind.MULTI) {
        "${condition.op}\"${v.replace("\"", "\"\"")}\""
    } else {
        "${condition.op}$v"
    }
}

fun setFilterText(
    kind: FunnelKind,
    first: ConditionInput,
    join: String, // "and" | "or"
    second: ConditionInput,
): String {
    val a = conditionText(kind, first)
    val b = conditionText(kind, second)
    val sep = if (join.equals("and", ignoreCase = true)) " " else ","
    return when {
        a != null && b != null -> "$a$sep$b"
        a != null -> a
        b != null -> b
        else -> ""
    }
}
