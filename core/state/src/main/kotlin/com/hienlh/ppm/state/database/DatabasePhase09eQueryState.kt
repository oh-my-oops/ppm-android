package com.hienlh.ppm.state.database

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.webRandomId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

// ── 9e.1 SQL Statement Splitter (`src/shared/split-sql-statements.ts`) ───────

data class SqlSplitOptions(
    val dialect: String? = null,
    /** In MySQL/MariaDB, `\` escapes characters in string literals unless `NO_BACKSLASH_ESCAPES` is active. */
    val backslashEscapes: Boolean = true,
)

data class SqlStatementPiece(
    val text: String,
    val start: Int,
    val end: Int,
)

data class SqlStatementRange(
    val text: String,
    /** 0-based character offset where the trimmed statement starts. */
    val start: Int,
    /** 0-based character offset where the trimmed statement ends. */
    val end: Int,
    /** 1-based line where the statement starts. */
    val startLine: Int,
    /** 1-based line where the statement ends. */
    val endLine: Int,
)

data class DelimiterDirective(
    /** 1-based line number of the `DELIMITER` directive. */
    val line: Int,
    /** 0-based character offset of the start of the directive line. */
    val lineStart: Int,
    /** 0-based character offset of the end of the directive line (before `\n`). */
    val lineEnd: Int,
    /** The delimiter that takes effect after this line. */
    val delimiter: String,
)

data class SqlSplitError(
    val message: String,
    val line: Int,
)

data class SplitSqlScriptResult(
    val statements: List<SqlStatementRange>,
    val error: SqlSplitError? = null,
)

private val DOLLAR_TAG_RE = Regex("^\\$([A-Za-z_\\u0080-\\uffff][A-Za-z0-9_\\u0080-\\uffff]*)?\\$")
private val DELIMITER_LINE_RE = Regex("^\\s*DELIMITER(?:\\s+(\\S+))?\\s*$", RegexOption.IGNORE_CASE)

private fun isIdentChar(ch: Char): Boolean =
    (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch >= '\u0080'

private fun isPostgresEscapeString(sql: String, quotePos: Int): Boolean {
    if (quotePos <= 0) return false
    val prev = sql[quotePos - 1]
    if (prev != 'e' && prev != 'E') return false
    val before = if (quotePos >= 2) sql[quotePos - 2] else '\u0000'
    return before == '\u0000' || !isIdentChar(before)
}

private fun isMysqlCommentAfterDashDash(next: Char): Boolean =
    next == '\u0000' || next <= ' '

/**
 * MySQL `/*! ... */` and MariaDB `/*M! ... */` executable comments.
 * The server executes the SQL inside them, so the splitter scans their body as code.
 */
private fun isMysqlExecutableComment(sql: String, pos: Int): Boolean {
    val third = if (pos + 2 < sql.length) sql[pos + 2] else '\u0000'
    if (third == '!') return true
    val fourth = if (pos + 3 < sql.length) sql[pos + 3] else '\u0000'
    return (third == 'M' || third == 'm') && fourth == '!'
}

private fun skipMysqlExecutableCommentPrefix(sql: String, pos: Int): Int {
    val third = if (pos + 2 < sql.length) sql[pos + 2] else '\u0000'
    var i = pos + (if (third == '!') 3 else 4)
    var digits = 0
    while (i < sql.length && digits < 6 && sql[i] in '0'..'9') {
        i++
        digits++
    }
    return i
}

private fun readDollarTag(sql: String, pos: Int): String? {
    if (pos >= sql.length || sql[pos] != '$') return null
    val match = DOLLAR_TAG_RE.find(sql.substring(pos)) ?: return null
    return match.value
}

private fun splitSqlPieces(
    sql: String,
    options: SqlSplitOptions = SqlSplitOptions(),
    delimiter: String = ";",
): List<SqlStatementPiece> {
    val dialect = options.dialect
    val isMysql = dialect == "mysql" || dialect == "mariadb"
    val isPostgres = dialect == null || dialect == "postgres"
    val isSqlite = dialect == "sqlite"
    val backslashEscapes = options.backslashEscapes

    val statements = mutableListOf<SqlStatementPiece>()
    var firstCodePos = -1
    var hasStatementCode = false
    var mysqlExecutableDepth = 0
    var i = 0
    val len = sql.length

    fun markCodeAt(pos: Int) {
        if (!hasStatementCode) {
            firstCodePos = pos
            hasStatementCode = true
        }
    }

    fun emitBefore(end: Int) {
        if (!hasStatementCode || firstCodePos < 0) {
            firstCodePos = -1
            hasStatementCode = false
            return
        }
        var s = firstCodePos
        var e = end
        while (s < e && sql[s].isWhitespace()) s++
        while (e > s && sql[e - 1].isWhitespace()) e--
        if (s < e) {
            statements += SqlStatementPiece(sql.substring(s, e), s, e)
        }
        firstCodePos = -1
        hasStatementCode = false
    }

    while (i < len) {
        val ch = sql[i]
        val next = if (i + 1 < len) sql[i + 1] else '\u0000'

        if (delimiter.isNotEmpty() && sql.startsWith(delimiter, i)) {
            emitBefore(i)
            i += delimiter.length
            continue
        }

        if (mysqlExecutableDepth > 0 && ch == '*' && next == '/') {
            mysqlExecutableDepth--
            i += 2
            continue
        }

        // Line comment: -- to end of line (MySQL requires whitespace/control char/EOF after --)
        if (ch == '-' && next == '-' && (!isMysql || isMysqlCommentAfterDashDash(if (i + 2 < len) sql[i + 2] else '\u0000'))) {
            i += 2
            while (i < len && sql[i] != '\n') i++
            continue
        }

        // MySQL/MariaDB # line comment
        if (isMysql && ch == '#') {
            i++
            while (i < len && sql[i] != '\n') i++
            continue
        }

        // Block comment: /* ... */
        if (ch == '/' && next == '*') {
            if (isMysql && isMysqlExecutableComment(sql, i)) {
                mysqlExecutableDepth++
                markCodeAt(i)
                i = skipMysqlExecutableCommentPrefix(sql, i)
                continue
            }
            i += 2
            var depth = 1
            while (i < len && depth > 0) {
                if (isPostgres && sql[i] == '/' && i + 1 < len && sql[i + 1] == '*') {
                    depth++
                    i += 2
                } else if (sql[i] == '*' && i + 1 < len && sql[i + 1] == '/') {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
            continue
        }

        // Single-quoted string: '...'
        if (ch == '\'') {
            markCodeAt(i)
            val allowBackslash = (isMysql && backslashEscapes) || (isPostgres && isPostgresEscapeString(sql, i))
            i++
            while (i < len) {
                if (allowBackslash && sql[i] == '\\') {
                    i += 2
                    continue
                }
                if (sql[i] == '\'') {
                    if (i + 1 < len && sql[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        // Double-quoted identifier (or string in MySQL/SQLite): "..."
        if (ch == '"') {
            markCodeAt(i)
            val allowBackslash = isMysql && backslashEscapes
            i++
            while (i < len) {
                if (allowBackslash && sql[i] == '\\') {
                    i += 2
                    continue
                }
                if (sql[i] == '"') {
                    if (i + 1 < len && sql[i + 1] == '"') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        // Backtick-quoted identifier: `...`
        if (ch == '`') {
            markCodeAt(i)
            i++
            while (i < len) {
                if (sql[i] == '`') {
                    if (i + 1 < len && sql[i + 1] == '`') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        // SQLite bracket-quoted identifier: [...]
        if (isSqlite && ch == '[') {
            markCodeAt(i)
            i++
            while (i < len) {
                if (sql[i] == ']') {
                    if (i + 1 < len && sql[i + 1] == ']') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        // Postgres dollar-quoted string: $tag$...$tag$
        if (isPostgres && ch == '$') {
            val tag = readDollarTag(sql, i)
            if (tag != null) {
                markCodeAt(i)
                val closeIdx = sql.indexOf(tag, i + tag.length)
                if (closeIdx == -1) {
                    i = len
                } else {
                    i = closeIdx + tag.length
                }
                continue
            }
        }

        if (!ch.isWhitespace()) {
            markCodeAt(i)
        }
        i++
    }

    emitBefore(len)
    return statements
}

fun splitSqlStatements(sql: String, dialect: String? = null, backslashEscapes: Boolean = true): List<String> =
    splitSqlPieces(sql, SqlSplitOptions(dialect = dialect, backslashEscapes = backslashEscapes)).map { it.text }

fun splitSqlStatements(sql: String, options: SqlSplitOptions): List<String> =
    splitSqlPieces(sql, options).map { it.text }

/**
 * Replaces comments and quoted literals with spaces so keyword checks only inspect real SQL tokens.
 * MySQL `/*! ... */` and MariaDB `/*M! ... */` executable comments keep their inner body as code.
 */
fun sqlCode(sql: String, options: SqlSplitOptions = SqlSplitOptions()): String {
    val dialect = options.dialect
    val isMysql = dialect == "mysql" || dialect == "mariadb"
    val isPostgres = dialect == null || dialect == "postgres"
    val isSqlite = dialect == "sqlite"
    val backslashEscapes = options.backslashEscapes
    val out = StringBuilder(sql.length)
    var mysqlExecutableDepth = 0
    var i = 0
    val len = sql.length

    while (i < len) {
        val ch = sql[i]
        val next = if (i + 1 < len) sql[i + 1] else '\u0000'

        if (mysqlExecutableDepth > 0 && ch == '*' && next == '/') {
            mysqlExecutableDepth--
            out.append(' ')
            i += 2
            continue
        }

        if (ch == '-' && next == '-' && (!isMysql || isMysqlCommentAfterDashDash(if (i + 2 < len) sql[i + 2] else '\u0000'))) {
            out.append(' ')
            i += 2
            while (i < len && sql[i] != '\n') i++
            continue
        }

        if (isMysql && ch == '#') {
            out.append(' ')
            i++
            while (i < len && sql[i] != '\n') i++
            continue
        }

        if (ch == '/' && next == '*') {
            if (isMysql && isMysqlExecutableComment(sql, i)) {
                mysqlExecutableDepth++
                out.append(' ')
                i = skipMysqlExecutableCommentPrefix(sql, i)
                continue
            }
            out.append(' ')
            i += 2
            var depth = 1
            while (i < len && depth > 0) {
                if (isPostgres && sql[i] == '/' && i + 1 < len && sql[i + 1] == '*') {
                    depth++
                    i += 2
                } else if (sql[i] == '*' && i + 1 < len && sql[i + 1] == '/') {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
            continue
        }

        if (ch == '\'') {
            out.append(' ')
            val allowBackslash = (isMysql && backslashEscapes) || (isPostgres && isPostgresEscapeString(sql, i))
            i++
            while (i < len) {
                if (allowBackslash && sql[i] == '\\') {
                    i += 2
                    continue
                }
                if (sql[i] == '\'') {
                    if (i + 1 < len && sql[i + 1] == '\'') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        if (ch == '"') {
            out.append(' ')
            val allowBackslash = isMysql && backslashEscapes
            i++
            while (i < len) {
                if (allowBackslash && sql[i] == '\\') {
                    i += 2
                    continue
                }
                if (sql[i] == '"') {
                    if (i + 1 < len && sql[i + 1] == '"') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        if (ch == '`') {
            out.append(' ')
            i++
            while (i < len) {
                if (sql[i] == '`') {
                    if (i + 1 < len && sql[i + 1] == '`') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        if (isSqlite && ch == '[') {
            out.append(' ')
            i++
            while (i < len) {
                if (sql[i] == ']') {
                    if (i + 1 < len && sql[i + 1] == ']') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }

        if (isPostgres && ch == '$') {
            val tag = readDollarTag(sql, i)
            if (tag != null) {
                out.append(' ')
                val closeIdx = sql.indexOf(tag, i + tag.length)
                i = if (closeIdx == -1) len else closeIdx + tag.length
                continue
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

private fun lineIndex(sql: String): IntArray {
    val starts = mutableListOf(0)
    for (i in sql.indices) {
        if (sql[i] == '\n') starts += i + 1
    }
    return starts.toIntArray()
}

private fun lineAt(starts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (starts[mid] <= offset) lo = mid else hi = mid - 1
    }
    return lo + 1
}

private fun readDelimiterLine(sql: String, lineStart: Int): Triple<String?, Int, Boolean>? {
    var end = sql.indexOf('\n', lineStart)
    if (end == -1) end = sql.length
    val line = sql.substring(lineStart, end).removeSuffix("\r")
    val match = DELIMITER_LINE_RE.matchEntire(line) ?: return null
    val delim = match.groupValues[1].takeIf { it.isNotEmpty() }
    val nextPos = if (end < sql.length) end + 1 else end
    return Triple(delim, nextPos, true)
}

private data class MysqlDelimiterScanResult(
    val pieces: List<SqlStatementPiece>,
    val directives: List<DelimiterDirective>,
    val error: SqlSplitError? = null,
)

private fun splitMysqlWithDelimiters(sql: String): MysqlDelimiterScanResult {
    val pieces = mutableListOf<SqlStatementPiece>()
    val directives = mutableListOf<DelimiterDirective>()
    val starts = lineIndex(sql)
    var delimiter = ";"
    var firstCodePos = -1
    var hasCode = false
    var atLineStart = true
    var i = 0
    val len = sql.length

    fun emit(end: Int) {
        if (!hasCode || firstCodePos < 0) {
            firstCodePos = -1
            hasCode = false
            return
        }
        var s = firstCodePos
        var e = end
        while (s < e && sql[s].isWhitespace()) s++
        while (e > s && sql[e - 1].isWhitespace()) e--
        if (s < e) {
            pieces += SqlStatementPiece(sql.substring(s, e), s, e)
        }
        firstCodePos = -1
        hasCode = false
    }

    while (i < len) {
        if (atLineStart && !hasCode) {
            val dir = readDelimiterLine(sql, i)
            if (dir != null) {
                val (newDelim, nextPos, _) = dir
                val line = lineAt(starts, i)
                if (newDelim == null) {
                    return MysqlDelimiterScanResult(
                        pieces = emptyList(),
                        directives = directives,
                        error = SqlSplitError("DELIMITER must be followed by a delimiter character or string", line),
                    )
                }
                val lineEnd = if (nextPos > i && sql[nextPos - 1] == '\n') nextPos - 1 else nextPos
                directives += DelimiterDirective(
                    line = line,
                    lineStart = i,
                    lineEnd = lineEnd,
                    delimiter = newDelim,
                )
                delimiter = newDelim
                i = nextPos
                firstCodePos = -1
                atLineStart = true
                continue
            }
        }

        if (delimiter.isNotEmpty() && sql.startsWith(delimiter, i)) {
            emit(i)
            val end = i + delimiter.length
            val crossedNewline = sql.substring(i, min(end, len)).contains('\n')
            i = end
            atLineStart = crossedNewline || (i > 0 && sql[i - 1] == '\n')
            continue
        }

        val ch = sql[i]
        val next = if (i + 1 < len) sql[i + 1] else '\u0000'

        if (ch == '\n') {
            atLineStart = true
            i++
            continue
        }
        if (ch.isWhitespace()) {
            i++
            continue
        }
        atLineStart = false

        if (ch == '-' && next == '-' && isMysqlCommentAfterDashDash(if (i + 2 < len) sql[i + 2] else '\u0000')) {
            i += 2
            while (i < len && sql[i] != '\n') i++
            continue
        }
        if (ch == '#') {
            i++
            while (i < len && sql[i] != '\n') i++
            continue
        }
        if (ch == '/' && next == '*' && !isMysqlExecutableComment(sql, i)) {
            i += 2
            while (i < len && !(sql[i] == '*' && i + 1 < len && sql[i + 1] == '/')) i++
            if (i < len) i += 2
            continue
        }

        if (!hasCode) {
            firstCodePos = i
            hasCode = true
        }
        if (ch == '\'' || ch == '"') {
            val quote = ch
            i++
            while (i < len) {
                if (sql[i] == '\\') {
                    i += 2
                    continue
                }
                if (sql[i] == quote) {
                    if (i + 1 < len && sql[i + 1] == quote) {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }
        if (ch == '`') {
            i++
            while (i < len) {
                if (sql[i] == '`') {
                    if (i + 1 < len && sql[i + 1] == '`') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            continue
        }
        i++
    }

    emit(len)
    return MysqlDelimiterScanResult(pieces = pieces, directives = directives)
}

/** Valid MySQL/MariaDB `DELIMITER` directives in [sql] in source order. */
fun delimiterDirectives(sql: String): List<DelimiterDirective> =
    splitMysqlWithDelimiters(sql).directives

private fun withLines(sql: String, pieces: List<SqlStatementPiece>): List<SqlStatementRange> {
    val starts = lineIndex(sql)
    return pieces.map { p ->
        SqlStatementRange(
            text = p.text,
            start = p.start,
            end = p.end,
            startLine = lineAt(starts, p.start),
            endLine = lineAt(starts, max(p.start, p.end - 1)),
        )
    }
}

/** Splits [sql] into statements with 0-based character offsets and 1-based line spans. */
fun splitSqlStatementsWithLines(sql: String, dialect: String? = null): List<SqlStatementRange> {
    if (dialect == "mysql" || dialect == "mariadb") {
        val res = splitMysqlWithDelimiters(sql)
        return withLines(sql, res.pieces)
    }
    return withLines(sql, splitSqlPieces(sql, SqlSplitOptions(dialect = dialect)))
}

/** Splits a script for execution, returning client/server syntax errors such as bare `DELIMITER` lines. */
fun splitSqlScript(sql: String, dialect: String? = null): SplitSqlScriptResult {
    if (dialect == "mysql" || dialect == "mariadb") {
        val res = splitMysqlWithDelimiters(sql)
        if (res.error != null) return SplitSqlScriptResult(emptyList(), res.error)
        return SplitSqlScriptResult(withLines(sql, res.pieces))
    }
    return SplitSqlScriptResult(withLines(sql, splitSqlPieces(sql, SqlSplitOptions(dialect = dialect))))
}

/** 1-based line number of a 0-based character offset in [sql]. */
fun lineOfPosition(sql: String, offset: Int): Int =
    lineAt(lineIndex(sql), offset.coerceIn(0, max(0, sql.length)))

/**
 * Finds the statement at [offset] in an already-split statement list (`statementAt`).
 * When between statements, picks the preceding statement if it ends on the same line or the cursor
 * sits right on its trailing `;`; otherwise picks the next statement.
 */
fun statementAt(statements: List<SqlStatementRange>, offset: Int, sql: String? = null): SqlStatementRange? {
    if (statements.isEmpty()) return null
    val starts = sql?.let(::lineIndex)
    val cursorLine = if (starts != null && sql != null) lineAt(starts, offset.coerceIn(0, max(0, sql.length))) else null

    for ((idx, s) in statements.withIndex()) {
        if (offset >= s.start && offset <= s.end) return s
        if (offset < s.start) {
            val prev = if (idx > 0) statements[idx - 1] else null
            if (prev != null && ((cursorLine != null && prev.endLine == cursorLine) || offset <= prev.end + 1)) {
                return prev
            }
            return s
        }
    }
    return statements.last()
}

/** Finds the statement containing or adjacent to [offset] (`statementAtCursor`). */
fun statementAtCursor(sql: String, offset: Int, dialect: String? = null): SqlStatementRange? {
    val stmts = splitSqlStatementsWithLines(sql, dialect)
    return statementAt(stmts, offset, sql)
}

/** Legacy helper returning `(sql, startLine)` for the statement at [cursorOffset]. */
fun getStatementAtCursor(sql: String, cursorOffset: Int, dialect: String? = null): Pair<String, Int>? =
    statementAtCursor(sql, cursorOffset, dialect)?.let { it.text to it.startLine }

/**
 * 0-based line offset added to 1-based line numbers reported by the server so messages and errors
 * point at their line in the full SQL editor (`runLineOffset`).
 */
fun runLineOffset(selectionStartLine: Int?, statement: SqlStatementRange?): Int {
    if (selectionStartLine != null) return max(0, selectionStartLine - 1)
    if (statement != null) return max(0, statement.startLine - 1)
    return 0
}

// ── 9e.1 SQL Run Selection & Reformat Cursor (`sql-run.ts`, `reformat-cursor.ts`, `readonly-check.ts`) ──

data class SqlToRun(
    val sql: String,
    val lineOffset: Int,
)

/** Whole-script run (`F5` / `Run`). */
fun scriptRun(text: String): SqlToRun? =
    if (text.isBlank()) null else SqlToRun(sql = text, lineOffset = 0)

/** Single-statement run (`Run current statement` button). */
fun statementRun(text: String, cursorOffset: Int, dialect: String? = null): SqlToRun? {
    val stmt = statementAtCursor(text, cursorOffset, dialect) ?: return null
    if (stmt.text.isBlank()) return null
    return SqlToRun(sql = stmt.text, lineOffset = runLineOffset(null, stmt))
}

/** Selection when non-blank, otherwise statement at cursor (`Ctrl+Enter` / `Explain`). */
fun selectionOrStatementRun(
    text: String,
    selectionText: String?,
    selectionStartLine: Int?,
    cursorOffset: Int,
    dialect: String? = null,
): SqlToRun? {
    if (!selectionText.isNullOrBlank()) {
        return SqlToRun(
            sql = selectionText,
            lineOffset = runLineOffset(selectionStartLine ?: 1, null),
        )
    }
    return statementRun(text, cursorOffset, dialect)
}

private fun nonSpaceBeforeCursor(sql: String, offset: Int, dialect: String?): Int {
    val isMysql = dialect == "mysql" || dialect == "mariadb"
    val isPostgres = dialect == null || dialect == "postgres"
    val limit = min(max(0, offset), sql.length)
    var count = 0
    var i = 0
    while (i < limit) {
        val ch = sql[i]
        val next = if (i + 1 < sql.length) sql[i + 1] else '\u0000'
        if (ch == '-' && next == '-' && (!isMysql || isMysqlCommentAfterDashDash(if (i + 2 < sql.length) sql[i + 2] else '\u0000'))) {
            i += 2
            while (i < limit && sql[i] != '\n') i++
            continue
        }
        if (isMysql && ch == '#') {
            i++
            while (i < limit && sql[i] != '\n') i++
            continue
        }
        if (ch == '/' && next == '*' && !(isMysql && isMysqlExecutableComment(sql, i))) {
            i += 2
            var depth = 1
            while (i < sql.length && depth > 0) {
                if (isPostgres && sql[i] == '/' && i + 1 < sql.length && sql[i + 1] == '*') {
                    depth++
                    i += 2
                } else if (sql[i] == '*' && i + 1 < sql.length && sql[i + 1] == '/') {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
            continue
        }
        if (!ch.isWhitespace()) count++
        i++
    }
    return count
}

/**
 * Computes the new 0-based cursor offset in [newText] after reformatting [oldText], keeping the cursor
 * anchored at the same non-whitespace token (`cursorAfterReformat` in `reformat-cursor.ts`).
 */
fun cursorAfterReformat(
    oldText: String,
    oldOffset: Int,
    newText: String,
    dialect: String? = null,
): Int {
    val target = nonSpaceBeforeCursor(oldText, oldOffset, dialect)
    if (target <= 0) return 0
    val isMysql = dialect == "mysql" || dialect == "mariadb"
    val isPostgres = dialect == null || dialect == "postgres"
    var seen = 0
    var i = 0
    val len = newText.length
    while (i < len) {
        val ch = newText[i]
        val next = if (i + 1 < len) newText[i + 1] else '\u0000'
        if (ch == '-' && next == '-' && (!isMysql || isMysqlCommentAfterDashDash(if (i + 2 < len) newText[i + 2] else '\u0000'))) {
            i += 2
            while (i < len && newText[i] != '\n') i++
            continue
        }
        if (isMysql && ch == '#') {
            i++
            while (i < len && newText[i] != '\n') i++
            continue
        }
        if (ch == '/' && next == '*' && !(isMysql && isMysqlExecutableComment(newText, i))) {
            i += 2
            var depth = 1
            while (i < len && depth > 0) {
                if (isPostgres && newText[i] == '/' && i + 1 < len && newText[i + 1] == '*') {
                    depth++
                    i += 2
                } else if (newText[i] == '*' && i + 1 < len && newText[i + 1] == '/') {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
            continue
        }
        if (!ch.isWhitespace()) {
            seen++
            if (seen == target) return i + 1
        }
        i++
    }
    return len
}

const val READONLY_REFUSAL_PREFIX = "Connection is readonly"
const val WRONG_PASSWORD = "Wrong password"

private val READONLY_START_RE = Regex("^\\s*(SELECT|WITH|VALUES|TABLE|EXPLAIN|SHOW|PRAGMA|DESCRIBE|DESC)\\b", RegexOption.IGNORE_CASE)
private val SHOW_START_RE = Regex("^\\s*SHOW\\b", RegexOption.IGNORE_CASE)
private val WRITE_KEYWORD_RE = Regex("\\b(INSERT|UPDATE|DELETE|MERGE|DROP|CREATE|ALTER|TRUNCATE|INTO)\\b", RegexOption.IGNORE_CASE)

private fun isReadOnlyForOptions(sql: String, options: SqlSplitOptions): Boolean {
    val statements = splitSqlStatements(sql, options)
    if (statements.isEmpty()) return false
    return statements.all { stmt ->
        val code = sqlCode(stmt, options)
        SHOW_START_RE.containsMatchIn(code) ||
            (READONLY_START_RE.containsMatchIn(code) && !WRITE_KEYWORD_RE.containsMatchIn(code))
    }
}

/**
 * Checks if every SQL statement in [sql] is read-only (`src/services/database/readonly-check.ts`).
 * For MySQL/MariaDB, checks under both `backslashEscapes = true` and `backslashEscapes = false`.
 */
fun isReadOnlyQuery(sql: String, dialect: String? = null): Boolean {
    if (dialect == "mysql" || dialect == "mariadb") {
        return isReadOnlyForOptions(sql, SqlSplitOptions(dialect = dialect, backslashEscapes = true)) &&
            isReadOnlyForOptions(sql, SqlSplitOptions(dialect = dialect, backslashEscapes = false))
    }
    return isReadOnlyForOptions(sql, SqlSplitOptions(dialect = dialect))
}

fun isReadonlyRefusalText(text: String): Boolean =
    text.startsWith(READONLY_REFUSAL_PREFIX) || text == WRONG_PASSWORD

// ── 9e.1 SQL Formatter (`src/web/components/database/query/format-sql.ts`) ──

const val FORMAT_MAX_CHARS = 500_000

private val MAJOR_CLAUSE_KEYWORDS = listOf(
    "SELECT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "LIMIT", "OFFSET",
    "INSERT INTO", "VALUES", "UPDATE", "SET", "DELETE FROM",
    "LEFT OUTER JOIN", "RIGHT OUTER JOIN", "FULL OUTER JOIN",
    "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "CROSS JOIN", "JOIN",
    "UNION ALL", "UNION", "INTERSECT", "EXCEPT", "RETURNING",
)

private val UPPERCASE_WORDS = setOf(
    "select", "from", "where", "and", "or", "not", "in", "is", "null", "true", "false",
    "as", "on", "using", "join", "inner", "left", "right", "full", "outer", "cross",
    "group", "by", "order", "asc", "desc", "nulls", "first", "last", "having", "limit", "offset",
    "insert", "into", "values", "update", "set", "delete", "returning", "default",
    "create", "alter", "drop", "truncate", "table", "view", "index", "unique", "primary", "key",
    "foreign", "references", "constraint", "check", "cascade", "restrict", "if", "exists",
    "distinct", "all", "union", "intersect", "except", "with", "recursive", "case", "when",
    "then", "else", "end", "between", "like", "ilike", "cast", "coalesce", "nullif",
    "count", "sum", "avg", "min", "max", "begin", "commit", "rollback", "explain", "analyze",
    "show", "pragma", "describe",
)

private fun formatSingleStandardStatement(stmt: String, dialect: String): String {
    val pieces = mutableListOf<String>()
    val isMysql = dialect == "mysql" || dialect == "mariadb"
    val isPostgres = dialect == "postgres"
    val isSqlite = dialect == "sqlite"
    var i = 0
    val len = stmt.length

    while (i < len) {
        val ch = stmt[i]
        val next = if (i + 1 < len) stmt[i + 1] else '\u0000'

        if (ch.isWhitespace()) {
            var hasNewline = false
            while (i < len && stmt[i].isWhitespace()) {
                if (stmt[i] == '\n') hasNewline = true
                i++
            }
            pieces += if (hasNewline) "\n" else " "
            continue
        }

        // Line comments stay intact with a trailing newline
        if (ch == '-' && next == '-' && (!isMysql || isMysqlCommentAfterDashDash(if (i + 2 < len) stmt[i + 2] else '\u0000'))) {
            val start = i
            i += 2
            while (i < len && stmt[i] != '\n') i++
            pieces += stmt.substring(start, i)
            continue
        }
        if (isMysql && ch == '#') {
            val start = i
            i++
            while (i < len && stmt[i] != '\n') i++
            pieces += stmt.substring(start, i)
            continue
        }
        // Block comments stay intact
        if (ch == '/' && next == '*') {
            val start = i
            i += 2
            var depth = 1
            while (i < len && depth > 0) {
                if (isPostgres && stmt[i] == '/' && i + 1 < len && stmt[i + 1] == '*') {
                    depth++
                    i += 2
                } else if (stmt[i] == '*' && i + 1 < len && stmt[i + 1] == '/') {
                    depth--
                    i += 2
                } else {
                    i++
                }
            }
            pieces += stmt.substring(start, i)
            continue
        }
        // Quoted strings / identifiers / dollar quotes stay intact
        if (ch == '\'' || ch == '"' || ch == '`') {
            val quote = ch
            val allowBackslash = (isMysql && (quote == '\'' || quote == '"')) ||
                (isPostgres && quote == '\'' && isPostgresEscapeString(stmt, i))
            val start = i
            i++
            while (i < len) {
                if (allowBackslash && stmt[i] == '\\') {
                    i += 2
                    continue
                }
                if (stmt[i] == quote) {
                    if (i + 1 < len && stmt[i + 1] == quote) {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            pieces += stmt.substring(start, i)
            continue
        }
        if (isSqlite && ch == '[') {
            val start = i
            i++
            while (i < len) {
                if (stmt[i] == ']') {
                    if (i + 1 < len && stmt[i + 1] == ']') {
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                i++
            }
            pieces += stmt.substring(start, i)
            continue
        }
        if (isPostgres && ch == '$') {
            val tag = readDollarTag(stmt, i)
            if (tag != null) {
                val start = i
                val closeIdx = stmt.indexOf(tag, i + tag.length)
                i = if (closeIdx == -1) len else closeIdx + tag.length
                pieces += stmt.substring(start, i)
                continue
            }
            // Positional placeholder $1, $2
            if (i + 1 < len && stmt[i + 1].isDigit()) {
                val start = i
                i++
                while (i < len && stmt[i].isDigit()) i++
                pieces += stmt.substring(start, i)
                continue
            }
        }
        // Named parameter :name (not ::cast)
        if (ch == ':' && next != ':' && (next in 'a'..'z' || next in 'A'..'Z' || next == '_')) {
            val start = i
            i++
            while (i < len && isIdentChar(stmt[i])) i++
            pieces += stmt.substring(start, i)
            continue
        }
        // Identifier / keyword
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_' || ch >= '\u0080') {
            val start = i
            i++
            while (i < len && isIdentChar(stmt[i])) i++
            val word = stmt.substring(start, i)
            val prevNonWs = pieces.lastOrNull { it.isNotBlank() }
            val isQualified = prevNonWs == "."
            pieces += if (!isQualified && word.lowercase() in UPPERCASE_WORDS) word.uppercase() else word
            continue
        }
        pieces += ch.toString()
        i++
    }

    val normalized = pieces.joinToString("")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *, *"), ", ")
        .trim()

    // Break major top-level clauses onto new lines (outside parentheses and quotes)
    var out = normalized
    for (clause in MAJOR_CLAUSE_KEYWORDS) {
        if (clause == "SELECT") continue
        out = out.replace(Regex("\\s+\\b${Regex.escape(clause)}\\b")) { "\n$clause" }
    }
    return out
}

private fun formatSqlChunk(sql: String, dialect: String): String {
    val stmts = splitSqlPieces(sql, SqlSplitOptions(dialect = dialect))
    if (stmts.isEmpty()) return sql.trim()
    return stmts.joinToString(";\n\n") { piece ->
        formatSingleStandardStatement(piece.text, dialect)
    } + ";"
}

/**
 * Formats a SQL script (`formatSql` in `src/web/components/database/query/format-sql.ts`).
 * - Refuses scripts over [FORMAT_MAX_CHARS] (`500,000` chars).
 * - Preserves MySQL/MariaDB `DELIMITER` directives and leaves custom-delimiter routine bodies untouched.
 */
fun formatSqlScript(sql: String, dialect: String = "postgres"): String {
    if (sql.length > FORMAT_MAX_CHARS) {
        throw IllegalArgumentException(
            "Formatting stops at 500,000 characters so the editor stays responsive.",
        )
    }
    if (sql.isBlank()) return sql
    if (dialect == "mysql" || dialect == "mariadb") {
        val directives = delimiterDirectives(sql)
        if (directives.isNotEmpty()) {
            val out = StringBuilder()
            var pos = 0
            var delimiter = ";"
            for (d in directives) {
                val region = sql.substring(pos, d.lineStart)
                if (delimiter == ";" && region.isNotBlank()) {
                    out.append(formatSqlChunk(region, dialect)).append("\n")
                } else {
                    out.append(region)
                }
                val lineWithBreak = if (d.lineEnd < sql.length && sql[d.lineEnd] == '\n') {
                    sql.substring(d.lineStart, d.lineEnd + 1)
                } else {
                    sql.substring(d.lineStart, d.lineEnd)
                }
                out.append(lineWithBreak)
                pos = if (d.lineEnd < sql.length && sql[d.lineEnd] == '\n') d.lineEnd + 1 else d.lineEnd
                delimiter = d.delimiter
            }
            val tail = sql.substring(pos)
            if (delimiter == ";" && tail.isNotBlank()) {
                out.append(formatSqlChunk(tail, dialect))
            } else {
                out.append(tail)
            }
            return out.toString()
        }
    }
    return formatSqlChunk(sql, dialect)
}

// ── 9e.1 Schema-Aware SQL Completion Provider (`sql-completion-provider.ts`) ──

val SQL_KEYWORDS: List<String> = listOf(
    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE",
    "IS", "NULL", "TRUE", "FALSE", "AS", "ON", "USING", "JOIN", "INNER", "LEFT", "RIGHT",
    "FULL", "OUTER", "CROSS", "NATURAL", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC",
    "NULLS", "FIRST", "LAST", "LIMIT", "OFFSET", "FETCH", "NEXT", "ROWS", "ONLY",
    "DISTINCT", "ALL", "UNION", "INTERSECT", "EXCEPT", "WITH", "RECURSIVE", "CASE",
    "WHEN", "THEN", "ELSE", "END", "CAST", "COALESCE", "NULLIF", "GREATEST", "LEAST",
    "COUNT", "SUM", "AVG", "MIN", "MAX", "OVER", "PARTITION", "ROW_NUMBER", "RANK",
    "DENSE_RANK", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "FILTER", "WITHIN",
    "INSERT", "INTO", "VALUES", "DEFAULT", "UPDATE", "SET", "DELETE", "MERGE",
    "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "TABLE", "TEMP", "TEMPORARY",
    "VIEW", "MATERIALIZED", "INDEX", "UNIQUE", "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
    "CONSTRAINT", "CHECK", "CASCADE", "RESTRICT", "ADD", "COLUMN", "IF",
    "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "TRANSACTION", "EXPLAIN", "ANALYZE",
)

val DIALECT_KEYWORDS: Map<String, List<String>> = mapOf(
    "postgres" to listOf("ILIKE", "RETURNING", "LATERAL", "CONFLICT", "DO", "NOTHING", "SERIAL", "BIGSERIAL", "UUID", "JSONB", " JSON", "TEXT", "TIMESTAMPTZ", "ARRAY_AGG", "STRING_AGG", "JSONB_BUILD_OBJECT", "NOW", "GENERATE_SERIES"),
    "mysql" to listOf("SHOW", "DATABASES", "TABLES", "COLUMNS", "DESCRIBE", "USE", "REPLACE", "DUPLICATE", "AUTO_INCREMENT", "ENGINE", "CHARSET", "COLLATE", "GROUP_CONCAT", "IFNULL", "NOW", "CURDATE", "LIMIT"),
    "mariadb" to listOf("SHOW", "DATABASES", "TABLES", "COLUMNS", "DESCRIBE", "USE", "REPLACE", "DUPLICATE", "AUTO_INCREMENT", "RETURNING", "SEQUENCE", "GROUP_CONCAT", "IFNULL", "NOW"),
    "sqlite" to listOf("PRAGMA", "VACUUM", "ATTACH", "DETACH", "AUTOINCREMENT", "WITHOUT", "ROWID", "STRICT", "RETURNING", "CONFLICT", "REPLACE", "GLOB", "TOTAL", "GROUP_CONCAT", "IFNULL", "STRFTIME", "DATETIME"),
)

val SQL_OPERATORS: List<Pair<String, String>> = listOf(
    "=" to "Equals",
    "<>" to "Not equal",
    "!=" to "Not equal",
    "<" to "Less than",
    "<=" to "Less than or equal",
    ">" to "Greater than",
    ">=" to "Greater than or equal",
    "LIKE" to "Pattern match",
    "NOT LIKE" to "Negated pattern match",
    "IN" to "Match any in list",
    "NOT IN" to "Match none in list",
    "BETWEEN" to "Inclusive range",
    "IS NULL" to "Value is NULL",
    "IS NOT NULL" to "Value is not NULL",
)

private val POSTGRES_OPERATORS: List<Pair<String, String>> = listOf(
    "ILIKE" to "Case-insensitive pattern match",
    "NOT ILIKE" to "Negated case-insensitive pattern match",
    "->" to "JSON field as JSON",
    "->>" to "JSON field as text",
    "@>" to "Contains",
    "<@" to "Contained by",
)

fun sqlKeywords(dialect: String): List<String> =
    (SQL_KEYWORDS + DIALECT_KEYWORDS[dialect].orEmpty().map { it.trim() }).distinct()

fun sqlOperators(dialect: String): List<Pair<String, String>> =
    if (dialect == "postgres") SQL_OPERATORS + POSTGRES_OPERATORS else SQL_OPERATORS

typealias GridColumn = GridColumnSchema

data class SqlTableRef(
    val schema: String? = null,
    val table: String,
    val alias: String? = null,
)

private val STOP_WORDS = setOf(
    "where", "join", "inner", "left", "right", "full", "cross", "outer", "natural",
    "on", "using", "set", "values", "group", "order", "having", "limit", "offset",
    "union", "intersect", "except", "returning", "window", "for", "into", "select",
)

private val TABLE_REF_RE = Regex(
    "\\b(?:from|join|update|into|table)\\s+([\\w\"`]+(?:\\s*\\.\\s*[\\w\"`]+)?)(?:\\s+(?:as\\s+)?([a-zA-Z_]\\w*))?",
    RegexOption.IGNORE_CASE,
)

private fun unquoteIdent(raw: String): String =
    raw.trim().removeSurrounding("\"").removeSurrounding("`").removeSurrounding("[", "]")

/** Finds table references (`FROM`, `JOIN`, `UPDATE`, `INTO`, `TABLE`) and their aliases in [sql]. */
fun extractTableRefs(sql: String): List<SqlTableRef> {
    val clean = sql
        .replace(Regex("--[^\\n]*"), " ")
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .replace(Regex("'(?:''|[^'])*'"), "''")
    val out = mutableListOf<SqlTableRef>()
    for (match in TABLE_REF_RE.findAll(clean)) {
        val rawTarget = match.groupValues[1]
        val rawAlias = match.groupValues[2].takeIf { it.isNotEmpty() }
        val parts = rawTarget.split('.').map(::unquoteIdent)
        val schema = if (parts.size > 1) parts[0] else null
        val table = if (parts.size > 1) parts[1] else parts[0]
        if (table.isEmpty() || table.lowercase() in STOP_WORDS) continue
        val alias = if (rawAlias != null && rawAlias.lowercase() !in STOP_WORDS) rawAlias else null
        out += SqlTableRef(schema = schema, table = table, alias = alias)
    }
    return out
}

fun resolveTable(
    name: String,
    refs: List<SqlTableRef>,
    objects: List<DbObjectItem>,
    defaultSchema: String?,
): DbObjectItem? {
    val lower = name.lowercase()
    val ref = refs.firstOrNull { it.alias?.lowercase() == lower || it.table.lowercase() == lower }
    val targetName = ref?.table?.lowercase() ?: lower
    val targetSchema = ref?.schema?.lowercase()
    return objects.firstOrNull { o ->
        (o.kind == "table" || o.kind == "view" || o.kind == "matview") &&
            o.name.lowercase() == targetName &&
            (targetSchema == null || (o.schema ?: defaultSchema ?: "").lowercase() == targetSchema)
    } ?: objects.firstOrNull { o ->
        (o.kind == "table" || o.kind == "view" || o.kind == "matview") &&
            o.name.lowercase() == targetName
    }
}

fun schemaForTable(obj: DbObjectItem, defaultSchema: String?): String? =
    obj.schema ?: defaultSchema

private val SIMPLE_IDENT_RE = Regex("^[a-z_][a-z0-9_]*$")

fun columnInsertText(name: String, dialect: String): String {
    if (SIMPLE_IDENT_RE.matches(name) && name.uppercase() !in SQL_KEYWORDS) return name
    return if (dialect == "mysql" || dialect == "mariadb") {
        "`${name.replace("`", "``")}`"
    } else {
        "\"${name.replace("\"", "\"\"")}\""
    }
}

sealed interface CompletionContext {
    data class Dot(val prefix: String, val typed: String) : CompletionContext
    data class Clause(val clause: String, val typed: String) : CompletionContext
}

private val DOT_CONTEXT_RE = Regex("([\\w\"`]+)\\.([\\w\"`]*)$")
private val TRAILING_WORD_RE = Regex("([\\w\"`]*)$")
private val CLAUSE_WORD_RE = Regex("\\b(select|from|join|update|into|table|where|and|or|on|having|set|by|order|group)\\b", RegexOption.IGNORE_CASE)

fun getCompletionContext(textBeforeCursor: String): CompletionContext {
    val stmtPart = textBeforeCursor.substringAfterLast(';')
    val dotMatch = DOT_CONTEXT_RE.find(stmtPart)
    if (dotMatch != null) {
        return CompletionContext.Dot(
            prefix = unquoteIdent(dotMatch.groupValues[1]),
            typed = unquoteIdent(dotMatch.groupValues[2]),
        )
    }
    val typed = unquoteIdent(TRAILING_WORD_RE.find(stmtPart)?.groupValues?.get(1).orEmpty())
    val beforeTyped = stmtPart.dropLast(typed.length)
    val lastClause = CLAUSE_WORD_RE.findAll(beforeTyped).lastOrNull()?.groupValues?.get(1)?.lowercase()
    val clause = when (lastClause) {
        "from", "join", "update", "into", "table" -> "table"
        "select" -> "select"
        "where", "and", "or", "on", "having", "set" -> "where"
        "by", "order", "group" -> "order"
        else -> "other"
    }
    return CompletionContext.Clause(clause = clause, typed = typed)
}

data class SqlCompletionSuggestion(
    val label: String,
    val insertText: String,
    val detail: String,
    /** `"column"`, `"table"`, `"schema"`, `"function"`, `"operator"`, `"keyword"` */
    val kind: String,
    val sortKey: String,
)

/**
 * Computes schema-aware SQL completions at [cursorOffset] (`sql-completion-provider.ts`).
 */
fun computeSqlCompletions(
    sql: String,
    cursorOffset: Int,
    dialect: String = "postgres",
    defaultSchema: String? = null,
    schemas: List<String> = emptyList(),
    objects: List<DbObjectItem> = emptyList(),
    columnsByTableKey: Map<String, List<GridColumn>> = emptyMap(),
): List<SqlCompletionSuggestion> {
    val safeCursor = cursorOffset.coerceIn(0, sql.length)
    val before = sql.substring(0, safeCursor)
    val ctx = getCompletionContext(before)
    val refs = extractTableRefs(sql)
    val suggestions = mutableListOf<SqlCompletionSuggestion>()

    when (ctx) {
        is CompletionContext.Dot -> {
            val prefixLower = ctx.prefix.lowercase()
            val typedLower = ctx.typed.lowercase()
            val matchedSchema = schemas.firstOrNull { it.lowercase() == prefixLower }
            if (matchedSchema != null) {
                for (o in objects) {
                    if ((o.schema ?: defaultSchema) == matchedSchema && o.name.lowercase().startsWith(typedLower)) {
                        suggestions += SqlCompletionSuggestion(
                            label = o.name,
                            insertText = columnInsertText(o.name, dialect),
                            detail = "${o.schema ?: defaultSchema}.${o.name}",
                            kind = if (o.kind == "function" || o.kind == "procedure") "function" else "table",
                            sortKey = "0_${o.name}",
                        )
                    }
                }
            }
            val tableObj = resolveTable(ctx.prefix, refs, objects, defaultSchema)
            if (tableObj != null) {
                val sch = schemaForTable(tableObj, defaultSchema)
                val key = "${sch.orEmpty()}.${tableObj.name}"
                val cols = columnsByTableKey[key] ?: columnsByTableKey[tableObj.name].orEmpty()
                for ((idx, col) in cols.withIndex()) {
                    if (col.name.lowercase().startsWith(typedLower)) {
                        suggestions += SqlCompletionSuggestion(
                            label = col.name,
                            insertText = columnInsertText(col.name, dialect),
                            detail = "${col.type}${if (col.pk) " · PK" else ""} — ${tableObj.name}",
                            kind = "column",
                            sortKey = "0_${idx.toString().padStart(4, '0')}_${col.name}",
                        )
                    }
                }
            }
        }
        is CompletionContext.Clause -> {
            val typedLower = ctx.typed.lowercase()
            val tablePriority = if (ctx.clause == "table") "0" else "2"
            val colPriority = if (ctx.clause in setOf("select", "where", "order")) "0" else "2"

            // Columns from referenced tables
            val multiTable = refs.size > 1
            for (ref in refs) {
                val obj = resolveTable(ref.alias ?: ref.table, refs, objects, defaultSchema) ?: continue
                val sch = schemaForTable(obj, defaultSchema)
                val key = "${sch.orEmpty()}.${obj.name}"
                val cols = columnsByTableKey[key] ?: columnsByTableKey[obj.name].orEmpty()
                val qualifier = if (multiTable) "${ref.alias ?: obj.name}." else ""
                for ((idx, col) in cols.withIndex()) {
                    val fullLabel = "$qualifier${col.name}"
                    if (typedLower.isEmpty() || col.name.lowercase().startsWith(typedLower) || fullLabel.lowercase().startsWith(typedLower)) {
                        suggestions += SqlCompletionSuggestion(
                            label = fullLabel,
                            insertText = "$qualifier${columnInsertText(col.name, dialect)}",
                            detail = "${col.type}${if (col.pk) " · PK" else ""} — ${obj.name}",
                            kind = "column",
                            sortKey = "${colPriority}_${idx.toString().padStart(4, '0')}_$fullLabel",
                        )
                    }
                }
            }

            // Tables, views, routines
            for (o in objects) {
                if (typedLower.isEmpty() || o.name.lowercase().startsWith(typedLower)) {
                    val needSchema = o.schema != null && o.schema != defaultSchema
                    val insert = if (needSchema) {
                        "${columnInsertText(o.schema!!, dialect)}.${columnInsertText(o.name, dialect)}"
                    } else {
                        columnInsertText(o.name, dialect)
                    }
                    suggestions += SqlCompletionSuggestion(
                        label = if (needSchema) "${o.schema}.${o.name}" else o.name,
                        insertText = insert,
                        detail = o.kind,
                        kind = if (o.kind == "function" || o.kind == "procedure") "function" else "table",
                        sortKey = "${tablePriority}_${o.name}",
                    )
                }
            }

            // Schemas
            for (s in schemas) {
                if (typedLower.isEmpty() || s.lowercase().startsWith(typedLower)) {
                    suggestions += SqlCompletionSuggestion(
                        label = s,
                        insertText = columnInsertText(s, dialect),
                        detail = "schema",
                        kind = "schema",
                        sortKey = "2_$s",
                    )
                }
            }

            // Operators in WHERE/ON/HAVING
            if (ctx.clause == "where") {
                for ((op, desc) in sqlOperators(dialect)) {
                    if (typedLower.isEmpty() || op.lowercase().startsWith(typedLower)) {
                        suggestions += SqlCompletionSuggestion(
                            label = op,
                            insertText = op,
                            detail = desc,
                            kind = "operator",
                            sortKey = "1_$op",
                        )
                    }
                }
            }

            // SQL Keywords
            for (kw in sqlKeywords(dialect)) {
                if (typedLower.isNotEmpty() && kw.lowercase().startsWith(typedLower)) {
                    suggestions += SqlCompletionSuggestion(
                        label = kw,
                        insertText = kw,
                        detail = "keyword",
                        kind = "keyword",
                        sortKey = "3_$kw",
                    )
                }
            }
        }
    }

    return suggestions.sortedBy { it.sortKey }.distinctBy { "${it.kind}:${it.label}" }
}

// ── 9e.2–9e.4 Query Script Events, Run State & Editable Results (`query-run-state.ts`, `extract-query-table.ts`) ──

val QUERY_ROW_LIMITS: List<Int> = listOf(100, 1_000, 10_000, 100_000)
const val DEFAULT_QUERY_ROW_LIMIT: Int = 1_000

fun queryRowLimit(saved: Any?): Int {
    val n = when (saved) {
        is Number -> saved.toInt()
        is String -> saved.trim().toIntOrNull()
        else -> null
    }
    return if (n != null && n in QUERY_ROW_LIMITS) n else DEFAULT_QUERY_ROW_LIMIT
}

data class QueryStatementSpan(
    val startLine: Int,
    val endLine: Int,
)

data class QueryResultSet(
    val columns: List<GridColumnSchema>,
    val rows: List<List<Any?>>,
    val truncated: Boolean = false,
    val cellRows: List<List<DbCellValue>> = emptyList(),
) {
    fun toGridPageData(sql: String = "", primaryKeys: List<String> = emptyList()): DbGridPageData {
        val rawCols = columns.map { ResultColumnMeta(name = it.name, type = it.type) }
        val keys = uniqueColumnKeys(rawCols.map { it.name })
        val resolvedRows = when {
            rows.isEmpty() -> emptyList()
            cellRows.size == rows.size -> cellRows
            else -> rows.map { r ->
                List(keys.size) { idx ->
                    when (val v = r.getOrNull(idx)) {
                        null -> DbCellValue.Null
                        is DbCellValue -> v
                        is Boolean -> DbCellValue.Bool(v)
                        is Number -> DbCellValue.Num(v.toString(), v.toDouble())
                        is String -> DbCellValue.Text(v)
                        else -> DbCellValue.Text(v.toString())
                    }
                }
            }
        }
        return DbGridPageData(
            rawColumns = rawCols,
            columnKeys = keys,
            rows = resolvedRows,
            hasMore = false,
            sql = sql,
            rowKey = primaryKeys,
        )
    }
}

data class QueryStatementResult(
    val index: Int,
    val startLine: Int,
    val endLine: Int,
    val sql: String,
    val command: String? = null,
    val resultSets: List<QueryResultSet>,
    val rowsAffected: Int? = null,
    val notices: List<String> = emptyList(),
    val durationMs: Long,
    val error: String? = null,
    val errorLine: Int? = null,
    /** `"user"` | `"timeout"` | `null` */
    val stopped: String? = null,
)

sealed interface QueryScriptEvent {
    data class Start(val statements: List<QueryStatementSpan>) : QueryScriptEvent
    data class Running(val index: Int) : QueryScriptEvent
    data class Statement(val result: QueryStatementResult) : QueryScriptEvent
    data class Message(val level: String, val text: String) : QueryScriptEvent
    data class Done(val durationMs: Long, val error: String? = null) : QueryScriptEvent
}

private fun parseQueryResultSet(obj: JsonObject): QueryResultSet {
    val colsArr = obj["columns"] as? JsonArray ?: JsonArray(emptyList())
    val columns = colsArr.mapNotNull { el ->
        val cObj = el as? JsonObject ?: return@mapNotNull null
        val name = cObj["name"].string ?: return@mapNotNull null
        GridColumnSchema(
            name = name,
            type = cObj["type"].string ?: "text",
            nullable = cObj["nullable"]?.bool ?: true,
            pk = cObj["pk"]?.bool == true,
            defaultValue = cObj["defaultValue"]?.string,
            autoIncrement = cObj["autoIncrement"]?.bool == true,
            fkTable = cObj["fkTargetTable"]?.string ?: cObj["fkTable"]?.string,
            fkSchema = cObj["fkTargetSchema"]?.string ?: cObj["fkSchema"]?.string,
            fkColumn = cObj["fkTargetColumn"]?.string ?: cObj["fkColumn"]?.string,
        )
    }
    val rowsArr = obj["rows"] as? JsonArray ?: JsonArray(emptyList())
    val cellRows = rowsArr.mapNotNull { rowEl ->
        val rArr = rowEl as? JsonArray ?: return@mapNotNull null
        val width = maxOf(columns.size, rArr.size)
        List(width) { idx -> parseDbCellValue(rArr.getOrNull(idx)) }
    }
    return QueryResultSet(
        columns = columns,
        rows = cellRows,
        truncated = obj["truncated"]?.bool == true,
        cellRows = cellRows,
    )
}

/** Parses one NDJSON line from `POST /api/db/connections/:id/query/script`. */
fun parseQueryScriptEventJson(line: String): QueryScriptEvent? {
    val obj = WireJson.parseOrNull(line) as? JsonObject ?: return null
    return when (obj["type"].string) {
        "start" -> {
            val stmts = (obj["statements"] as? JsonArray).orEmpty().mapNotNull { el ->
                val s = el as? JsonObject ?: return@mapNotNull null
                QueryStatementSpan(
                    startLine = s["startLine"].int ?: 1,
                    endLine = s["endLine"].int ?: 1,
                )
            }
            QueryScriptEvent.Start(stmts)
        }
        "running" -> QueryScriptEvent.Running(index = obj["index"].int ?: 0)
        "statement" -> {
            val r = obj["result"] as? JsonObject ?: return null
            val sets = (r["resultSets"] as? JsonArray).orEmpty().mapNotNull { el ->
                (el as? JsonObject)?.let(::parseQueryResultSet)
            }
            val notices = (r["notices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            QueryScriptEvent.Statement(
                QueryStatementResult(
                    index = r["index"].int ?: 0,
                    startLine = r["startLine"].int ?: 1,
                    endLine = r["endLine"].int ?: 1,
                    sql = r["sql"].string.orEmpty(),
                    command = r["command"]?.string,
                    resultSets = sets,
                    rowsAffected = r["rowsAffected"]?.int,
                    notices = notices,
                    durationMs = r["durationMs"]?.long ?: 0L,
                    error = r["error"]?.string,
                    errorLine = r["errorLine"]?.int,
                    stopped = r["stopped"]?.string,
                ),
            )
        }
        "message" -> QueryScriptEvent.Message(
            level = obj["level"].string ?: "info",
            text = obj["text"].string.orEmpty(),
        )
        "done" -> QueryScriptEvent.Done(
            durationMs = obj["durationMs"]?.long ?: 0L,
            error = obj["error"]?.string,
        )
        else -> null
    }
}

data class TimedQueryEvent(
    val event: QueryScriptEvent,
    val at: Long = System.currentTimeMillis(),
)

data class QueryMessage(
    val number: Int,
    /** `"info"` | `"error"` */
    val level: String,
    val text: String,
    /** Wall-clock ms (`Date.now()`) when the message arrived. */
    val at: Long,
    /** Elapsed ms since the previous message (or since `startedAt` for the first message). */
    val deltaMs: Long,
    /** Statement duration in ms, when this message summarizes a statement. */
    val durationMs: Long? = null,
    /** 1-based editor line number (already offset by `lineOffset`). */
    val line: Int? = null,
)

data class QueryResultTab(
    val key: String,
    val label: String,
    val statementIndex: Int,
    val setIndex: Int,
)

const val MESSAGES_TAB = "messages"

data class QueryRun(
    val runId: String,
    /** `"query"` | `"explain"` */
    val kind: String = "query",
    val lineOffset: Int = 0,
    val startedAt: Long,
    val statements: List<QueryStatementSpan> = emptyList(),
    val runningIndex: Int? = null,
    val results: List<QueryStatementResult> = emptyList(),
    val resultTabs: List<QueryResultTab> = emptyList(),
    val messages: List<QueryMessage> = emptyList(),
    val errorLine: Int? = null,
    val done: Boolean = false,
    val durationMs: Long? = null,
)

fun startQueryRun(
    runId: String,
    lineOffset: Int = 0,
    startedAt: Long = System.currentTimeMillis(),
    kind: String = "query",
): QueryRun = QueryRun(
    runId = runId,
    kind = kind,
    lineOffset = lineOffset,
    startedAt = startedAt,
)

fun stopTitle(dbType: String?): String =
    if (dbType == "sqlite") {
        "Stop before the next statement: SQLite cannot stop the one already running"
    } else {
        "Stop the running query"
    }

fun countOf(n: Int, singular: String, plural: String = "${singular}s"): String =
    "$n ${if (n == 1) singular else plural}"

private fun inEditorLines(run: QueryRun, line: Int): Int =
    line + run.lineOffset

private fun appendQueryMessage(
    messages: MutableList<QueryMessage>,
    startedAt: Long,
    level: String,
    text: String,
    at: Long,
    durationMs: Long? = null,
    line: Int? = null,
) {
    val prevAt = messages.lastOrNull()?.at ?: startedAt
    messages += QueryMessage(
        number = messages.size + 1,
        level = level,
        text = text,
        at = at,
        deltaMs = max(0L, at - prevAt),
        durationMs = durationMs,
        line = line,
    )
}

private fun summaryOfStatement(r: QueryStatementResult): String {
    if (r.resultSets.isNotEmpty()) {
        val total = r.resultSets.sumOf { it.rows.size }
        val truncated = r.resultSets.any { it.truncated }
        val rows = "${countOf(total, "row")} returned${if (truncated) " (row limit reached)" else ""}"
        return if (r.resultSets.size == 1) rows else "${r.resultSets.size} result sets, $rows"
    }
    if (r.rowsAffected != null) {
        val cmd = r.command ?: "Affected"
        return "$cmd — ${countOf(r.rowsAffected, "row")} affected"
    }
    return "${r.command ?: "Executed"} — OK"
}

/**
 * Applies a batch of streaming [TimedQueryEvent]s onto [run] (`applyQueryEvents` in `query-run-state.ts`).
 */
fun applyQueryEvents(run: QueryRun, events: List<TimedQueryEvent>): QueryRun {
    if (events.isEmpty()) return run
    var statements = run.statements
    var runningIndex = run.runningIndex
    val results = run.results.toMutableList()
    val resultTabs = run.resultTabs.toMutableList()
    val messages = run.messages.toMutableList()
    var errorLine = run.errorLine
    var done = run.done
    var durationMs = run.durationMs

    for (timed in events) {
        val at = timed.at
        when (val ev = timed.event) {
            is QueryScriptEvent.Start -> {
                statements = ev.statements.map { s ->
                    QueryStatementSpan(
                        startLine = inEditorLines(run, s.startLine),
                        endLine = inEditorLines(run, s.endLine),
                    )
                }
            }
            is QueryScriptEvent.Running -> {
                runningIndex = ev.index
            }
            is QueryScriptEvent.Statement -> {
                val raw = ev.result
                val shifted = raw.copy(
                    startLine = inEditorLines(run, raw.startLine),
                    endLine = inEditorLines(run, raw.endLine),
                    errorLine = raw.errorLine?.let { inEditorLines(run, it) },
                )
                results += shifted
                for (notice in shifted.notices) {
                    appendQueryMessage(messages, run.startedAt, "info", notice, at, line = shifted.startLine)
                }
                if (shifted.error != null) {
                    val line = shifted.errorLine ?: shifted.startLine
                    if (errorLine == null) errorLine = line
                    appendQueryMessage(
                        messages = messages,
                        startedAt = run.startedAt,
                        level = "error",
                        text = shifted.error,
                        at = at,
                        durationMs = shifted.durationMs,
                        line = line,
                    )
                } else {
                    appendQueryMessage(
                        messages = messages,
                        startedAt = run.startedAt,
                        level = "info",
                        text = summaryOfStatement(shifted),
                        at = at,
                        durationMs = shifted.durationMs,
                        line = shifted.startLine,
                    )
                }
                val tabPrefix = if (run.kind == "explain") "Plan" else "Result"
                for (sIdx in shifted.resultSets.indices) {
                    val n = resultTabs.size + 1
                    resultTabs += QueryResultTab(
                        key = "result-${shifted.index}-$sIdx",
                        label = "$tabPrefix $n",
                        statementIndex = shifted.index,
                        setIndex = sIdx,
                    )
                }
            }
            is QueryScriptEvent.Message -> {
                appendQueryMessage(messages, run.startedAt, ev.level, ev.text, at)
            }
            is QueryScriptEvent.Done -> {
                runningIndex = null
                done = true
                durationMs = ev.durationMs
                if (ev.error != null) {
                    appendQueryMessage(messages, run.startedAt, "error", ev.error, at)
                }
            }
        }
    }

    return run.copy(
        statements = statements,
        runningIndex = runningIndex,
        results = results,
        resultTabs = resultTabs,
        messages = messages,
        errorLine = errorLine,
        done = done,
        durationMs = durationMs,
    )
}

fun failQueryRun(run: QueryRun, text: String, at: Long = System.currentTimeMillis()): QueryRun {
    val messages = run.messages.toMutableList()
    appendQueryMessage(messages, run.startedAt, "error", text, at)
    return run.copy(
        runningIndex = null,
        done = true,
        durationMs = max(0L, at - run.startedAt),
        messages = messages,
    )
}

/** True when a finished run failed because the connection is readonly (or the write-once password was wrong). */
fun refusedAsReadonly(run: QueryRun?): Boolean {
    if (run == null || !run.done) return false
    return run.messages.any { m -> m.level == "error" && isReadonlyRefusalText(m.text) }
}

fun noteOnQueryRun(
    run: QueryRun,
    text: String,
    level: String = "info",
    at: Long = System.currentTimeMillis(),
): QueryRun {
    val messages = run.messages.toMutableList()
    appendQueryMessage(messages, run.startedAt, level, text, at)
    return run.copy(messages = messages)
}

fun resultOfTab(run: QueryRun?, tabKey: String): Pair<QueryStatementResult, QueryResultSet>? {
    if (run == null) return null
    val tab = run.resultTabs.firstOrNull { it.key == tabKey } ?: return null
    val result = run.results.firstOrNull { it.index == tab.statementIndex } ?: return null
    val set = result.resultSets.getOrNull(tab.setIndex) ?: return null
    return result to set
}

/** Replaces one result tab's [QueryResultSet] after an inline edit + save re-reads that statement. */
fun replaceResultSet(run: QueryRun, tabKey: String, nextSet: QueryResultSet): QueryRun {
    val tab = run.resultTabs.firstOrNull { it.key == tabKey } ?: return run
    return run.copy(
        results = run.results.map { r ->
            if (r.index != tab.statementIndex) {
                r
            } else {
                r.copy(
                    resultSets = r.resultSets.mapIndexed { idx, s -> if (idx == tab.setIndex) nextSet else s },
                )
            }
        },
    )
}

/**
 * Resolves which bottom sub-tab (`Messages` vs `Result N` / `Plan N`) is displayed (`shownResultTab`):
 * - While running before any result arrives, or when a run finishes with 0 result tabs -> `MESSAGES_TAB`.
 * - If [activeTab] is an existing result tab or explicitly `MESSAGES_TAB` -> [activeTab].
 * - Otherwise -> the first result tab (`resultTabs.first().key`).
 */
fun shownResultTab(activeTab: String, run: QueryRun?): String {
    if (run == null || run.resultTabs.isEmpty()) return MESSAGES_TAB
    if (activeTab == MESSAGES_TAB) return MESSAGES_TAB
    if (run.resultTabs.any { it.key == activeTab }) return activeTab
    return run.resultTabs.first().key
}

/**
 * Deduplicates duplicate column names in a query result (`SELECT a.id, b.id` -> `id`, `id (2)`)
 * so every column is addressable and visible in the result grid (`phase-09e-db-query.md` §9e.3).
 */
fun deduplicateResultColumns(columns: List<GridColumn>): List<GridColumn> {
    val seenCounts = HashMap<String, Int>()
    return columns.map { col ->
        val count = (seenCounts[col.name] ?: 0) + 1
        seenCounts[col.name] = count
        if (count == 1) col else col.copy(name = "${col.name} ($count)")
    }
}

// ── 9e.4 Editable Query Results (`extract-query-table.ts`, `query-run-state.ts`) ──

data class ExtractedQueryTable(
    val table: String,
    val schema: String? = null,
)

private val SELECT_START_RE = Regex("^select\\s", RegexOption.IGNORE_CASE)
private val DISALLOWED_EDITABLE_SELECT_RE = Regex("\\b(join|union|intersect|except|group\\s+by|distinct)\\b", RegexOption.IGNORE_CASE)
private val FROM_WORD_RE = Regex("\\bfrom\\b", RegexOption.IGNORE_CASE)
private val FROM_TABLE_RE = Regex("\\bfrom\\s+([\\w\"`]+(?:\\.[\\w\"`]+)?)", RegexOption.IGNORE_CASE)

/**
 * Extracts `{ table, schema }` if [sql] is a simple single-table `SELECT` (no `JOIN`, `UNION`,
 * `GROUP BY`, `DISTINCT`, or subquery `FROM`), or `null` when the result cannot be edited in-place.
 */
fun extractQueryTable(sql: String, defaultSchema: String? = null): ExtractedQueryTable? {
    val clean = sql
        .replace(Regex("--[^\\n]*"), " ")
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .trim()
        .removeSuffix(";")
        .trim()
    if (!SELECT_START_RE.containsMatchIn(clean)) return null
    if (DISALLOWED_EDITABLE_SELECT_RE.containsMatchIn(clean)) return null
    val fromCount = FROM_WORD_RE.findAll(clean).count()
    if (fromCount != 1) return null
    val match = FROM_TABLE_RE.find(clean) ?: return null
    val parts = match.groupValues[1].split('.').map { it.replace(Regex("[\"`]"), "") }
    if (parts.size > 2 || parts.any { it.isEmpty() }) return null
    return if (parts.size == 2) {
        ExtractedQueryTable(table = parts[1], schema = parts[0])
    } else {
        ExtractedQueryTable(table = parts[0], schema = defaultSchema)
    }
}

sealed interface ResultEditability {
    data object Readonly : ResultEditability
    data class Editable(
        val table: String,
        val schema: String?,
        val columns: List<GridColumn>,
        val primaryKeys: List<String>,
    ) : ResultEditability
}

/**
 * Checks whether a query result set can be edited in-place (`resultEditability` in `query-run-state.ts`).
 * Requires:
 * - `!readonly && !explain && source != null`
 * - No duplicate column names in [resultColumns]
 * - Every result column exists in [tableColumns]
 * - [tableColumns] has at least 1 primary key column, and all primary keys are present in [resultColumns].
 */
fun resultEditability(
    resultColumns: List<GridColumn>,
    tableColumns: List<GridColumn>,
    source: ExtractedQueryTable?,
    readonly: Boolean = false,
    explain: Boolean = false,
): ResultEditability {
    if (readonly || explain || source == null) return ResultEditability.Readonly
    if (resultColumns.isEmpty() || tableColumns.isEmpty()) return ResultEditability.Readonly

    val resultNames = HashSet<String>()
    for (col in resultColumns) {
        if (!resultNames.add(col.name)) return ResultEditability.Readonly
    }

    val tableByName = tableColumns.associateBy { it.name }
    val pks = tableColumns.filter { it.pk }.map { it.name }
    if (pks.isEmpty() || !pks.all { it in resultNames }) return ResultEditability.Readonly

    val merged = mutableListOf<GridColumn>()
    for (col in resultColumns) {
        val tableCol = tableByName[col.name] ?: return ResultEditability.Readonly
        merged += tableCol
    }
    return ResultEditability.Editable(
        table = source.table,
        schema = source.schema,
        columns = merged,
        primaryKeys = pks,
    )
}

// ── 9e.5 Query History & Query File Helpers (`query-history.ts`, `query-file.ts`, `db-tabs.ts`) ──

const val QUERY_HISTORY_PAGE = 50

data class QueryHistoryItem(
    val id: Int,
    val sql: String,
    /** `"ok"` | `"error"` | `"blocked"` */
    val status: String,
    val error: String?,
    val rowCount: Int?,
    val durationMs: Long,
    val ranAt: Long,
    val byAgent: Boolean,
    val database: String? = null,
)

data class QueryHistoryResponse(
    val items: List<QueryHistoryItem>,
    val retentionDays: Int = 90,
    val maxSizeMb: Int = 50,
)

fun parseQueryHistoryResponse(json: JsonObject): QueryHistoryResponse {
    val items = (json["items"] as? JsonArray).orEmpty().mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        QueryHistoryItem(
            id = obj["id"].int ?: return@mapNotNull null,
            sql = obj["sql"].string.orEmpty(),
            status = obj["status"].string ?: "ok",
            error = obj["error"]?.string,
            rowCount = obj["rowCount"]?.int,
            durationMs = obj["durationMs"]?.long ?: 0L,
            ranAt = obj["ranAt"]?.long ?: 0L,
            byAgent = obj["byAgent"]?.bool == true,
            database = obj["database"]?.string,
        )
    }
    return QueryHistoryResponse(
        items = items,
        retentionDays = json["retentionDays"]?.int ?: 90,
        maxSizeMb = json["maxSizeMb"]?.int ?: 50,
    )
}

fun historyUrl(connectionId: Int, search: String, offset: Int): String {
    val params = mutableListOf<String>()
    val trimmed = search.trim()
    if (trimmed.isNotEmpty()) {
        params += "search=${URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")}"
    }
    if (offset > 0) {
        params += "offset=$offset"
    }
    val qs = params.joinToString("&")
    return "/api/db/connections/$connectionId/history${if (qs.isNotEmpty()) "?$qs" else ""}"
}

fun mergeHistoryPage(
    existing: List<QueryHistoryItem>,
    incoming: List<QueryHistoryItem>,
    offset: Int,
): List<QueryHistoryItem> {
    if (offset == 0) return incoming
    val seen = existing.mapTo(HashSet()) { it.id }
    return existing + incoming.filter { it.id !in seen }
}

fun historyHasMore(pageLength: Int): Boolean = pageLength >= QUERY_HISTORY_PAGE

val HISTORY_STATUS_LABEL: Map<String, String> = mapOf(
    "ok" to "OK",
    "error" to "Error",
    "blocked" to "Blocked",
)

fun historyMeta(item: QueryHistoryItem): String {
    val parts = mutableListOf<String>()
    if (!item.database.isNullOrBlank()) parts += item.database
    parts += "${item.durationMs}ms"
    if (item.status == "ok" && item.rowCount != null) {
        parts += "${item.rowCount} ${if (item.rowCount == 1) "row" else "rows"}"
    }
    if (item.byAgent) parts += "AI"
    return parts.joinToString(" · ")
}

fun retentionNote(retentionDays: Int, maxSizeMb: Int): String =
    "Kept for $retentionDays days (up to $maxSizeMb MB)"

data class SavedQueryFile(
    val path: String,
    val projectName: String? = null,
)

fun savedFileOf(metadata: JsonObject?): SavedQueryFile? {
    val f = metadata?.get("savedFile") as? JsonObject ?: return null
    val path = f["path"]?.string?.takeIf { it.isNotEmpty() } ?: return null
    val projectName = f["projectName"]?.string?.takeIf { it.isNotEmpty() }
    return SavedQueryFile(path = path, projectName = projectName)
}

fun defaultQueryFileName(title: String?): String {
    val clean = title.orEmpty().trim()
    if (Regex("\\.sql$", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return clean
    val slug = clean
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    return "${slug.ifEmpty { "query" }}.sql"
}

fun withSqlExtension(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    return if (Regex("\\.sql$", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) trimmed else "$trimmed.sql"
}

fun queryFileTitle(path: String): String {
    val idx = max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return (if (idx >= 0) path.substring(idx + 1) else path).ifEmpty { path }
}

fun savedQueryTab(file: SavedQueryFile, sql: String): Pair<String, Map<String, JsonElement>> {
    val savedObj = buildJsonObject {
        put("path", file.path)
        if (!file.projectName.isNullOrBlank()) put("projectName", file.projectName)
    }
    return queryFileTitle(file.path) to mapOf(
        "savedFile" to savedObj,
        "currentSql" to JsonPrimitive(sql),
        "openedSql" to JsonPrimitive(sql),
    )
}

fun splitFilePath(fullPath: String): Pair<String, String> {
    val slash = max(fullPath.lastIndexOf('/'), fullPath.lastIndexOf('\\'))
    if (slash < 0) return "" to fullPath
    if (slash == 0) return fullPath.substring(0, 1) to fullPath.substring(1)
    if (slash == 2 && fullPath[1] == ':') return fullPath.substring(0, 3) to fullPath.substring(3)
    return fullPath.substring(0, slash) to fullPath.substring(slash + 1)
}

/** Checks whether a directory entry list (`(name, type)`) already has a file named [fileName]. */
fun hasFileNamed(entries: List<Pair<String, String>>, fileName: String): Boolean =
    entries.any { (name, type) -> type == "file" && name == fileName }

/** Builds metadata for a new `db-query` tab (`queryTabMetadata` in `src/web/lib/db-tabs.ts`). */
fun queryTabMetadata(
    sql: String,
    queryNumber: Int,
    runOnOpen: Boolean = false,
    connectionId: Int? = null,
    connectionName: String? = null,
    dbType: String? = null,
    database: String? = null,
    connectionColor: String? = null,
    dbFilePath: String? = null,
    dbFileProject: String? = null,
    randomId: () -> String = ::webRandomId,
): JsonObject = buildJsonObject {
    if (dbFilePath != null) {
        put(
            "dbFile",
            buildJsonObject {
                put("path", dbFilePath)
                if (!dbFileProject.isNullOrBlank()) put("projectName", dbFileProject)
            },
        )
    } else if (connectionId != null) {
        put("connectionId", connectionId)
    }
    if (!connectionName.isNullOrBlank()) put("connectionName", connectionName)
    if (!dbType.isNullOrBlank()) put("dbType", dbType)
    if (!database.isNullOrBlank()) put("database", database)
    if (!connectionColor.isNullOrBlank()) put("connectionColor", connectionColor)
    put("queryId", randomId())
    put("queryNumber", queryNumber)
    put("currentSql", sql)
    put("openedSql", sql)
    if (runOnOpen) put("runOnOpen", true)
}

/** True when a `db-query` tab holds SQL edited since it opened or was last saved (`isQueryTabDirty`). */
fun isQueryTabDirty(metadata: JsonObject?): Boolean {
    val cur = metadata?.get("currentSql")?.string ?: return false
    val opened = metadata["openedSql"]?.string.orEmpty()
    return cur != opened
}

/**
 * DBGate's unsaved indicator on whichever database tab holds work nothing else keeps
 * (`isDbTabDirty` in `src/web/lib/db-tabs.ts`).
 */
fun isDbTabDirty(type: String, metadata: JsonObject?): Boolean = when (type) {
    "db-query" -> isQueryTabDirty(metadata)
    "db-structure" -> isStructureTabDirty(metadata)
    else -> false
}

/**
 * True when closing [tab] would discard unsaved database work (`losesWorkOnClose` in
 * `src/web/stores/tab-close-confirm-store.ts`):
 * - dirty `db-query` SQL (`currentSql != openedSql`)
 * - dirty `db-structure` table model (`!sameTableModel(tableEdit.base, tableEdit.current)`)
 * - unsaved grid row edits in `UnsavedGridRowsStore` or `tab.metadata["unsavedGridRows"]`
 */
fun losesDbWorkOnClose(tab: WorkspaceTab): Boolean {
    if (isDbTabDirty(tab.type, tab.metadata)) return true
    if (unsavedGridRows(tab.id) > 0) return true
    val metaRows = tab.metadata["unsavedGridRows"]?.int ?: 0
    if (metaRows > 0) return true
    val restoredCs = parseGridChangesetJson(tab.metadata["pendingChangeset"] as? JsonObject)
    return restoredCs != null && !isEmptyChangeset(restoredCs)
}

/** The next `"Query N"` number across open tabs (`nextQueryNumber` in `src/web/lib/db-tabs.ts`). */
fun nextQueryNumber(tabs: Iterable<WorkspaceTab>): Int {
    var maxNum = 0
    for (tab in tabs) {
        if (tab.type == "db-query") {
            val n = tab.metadata["queryNumber"]?.int
            if (n != null && n > maxNum) maxNum = n
        }
    }
    return maxNum + 1
}
