package com.hienlh.ppm.state.database

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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.roundToInt

// ── 9a.1 / 9a.4 Database URL Parser & Builder (`src/shared/db-connection-url.ts`) ──

val DB_DEFAULT_PORT = mapOf("postgres" to 5432, "mysql" to 3306, "mariadb" to 3306)
val DB_DEFAULT_USER = mapOf("postgres" to "postgres", "mysql" to "root", "mariadb" to "root")
val DB_DEFAULT_SOCKET = mapOf(
    "postgres" to "/var/run/postgresql",
    "mysql" to "/run/mysqld/mysqld.sock",
    "mariadb" to "/run/mysqld/mysqld.sock",
)
val DB_URL_SCHEME = mapOf("postgres" to "postgres", "mysql" to "mysql", "mariadb" to "mariadb")
val DB_TYPE_LABELS = mapOf(
    "postgres" to "PostgreSQL",
    "mysql" to "MySQL",
    "mariadb" to "MariaDB",
    "sqlite" to "SQLite",
)

private val DB_SCHEMES = mapOf(
    "postgres" to "postgres",
    "postgresql" to "postgres",
    "mysql" to "mysql",
    "mariadb" to "mariadb",
)

private val DB_SSL_PARAMS = mapOf(
    "postgres" to listOf("sslmode", "ssl"),
    "mysql" to listOf("ssl-mode", "sslmode", "sslMode", "ssl"),
    "mariadb" to listOf("ssl-mode", "sslmode", "sslMode", "ssl"),
)

data class DbSslParam(val name: String, val value: String)

data class DbUrlParts(
    val type: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val socket: String,
    val user: String,
    val password: String,
    val database: String,
    val ssl: DbSslParam?,
    val params: List<Pair<String, String>>,
)

sealed interface DbUrlParseResult {
    data object Empty : DbUrlParseResult
    data class File(val path: String) : DbUrlParseResult
    data class Error(val error: String) : DbUrlParseResult
    data class Url(val parts: DbUrlParts) : DbUrlParseResult
}

private val SCHEME_RE = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$", RegexOption.DOT_MATCHES_ALL)
private val FILE_PATH_PREFIX_RE = Regex("^(~|/|\\.{1,2}[/\\\\]|[A-Za-z]:[/\\\\])")
private val FILE_EXT_RE = Regex("\\.(db|sqlite3?|db3)$", RegexOption.IGNORE_CASE)
private val FILE_SCHEME_RE = Regex("^file:(//)?", RegexOption.IGNORE_CASE)
private val PORT_DIGITS_RE = Regex("^\\d{1,5}$")

private fun looksLikeFilePath(s: String): Boolean {
    if (s.startsWith("file:", ignoreCase = true)) return true
    if (s.contains("://")) return false
    return FILE_PATH_PREFIX_RE.containsMatchIn(s) || FILE_EXT_RE.containsMatchIn(s)
}

private fun urlDecodeComponent(text: String): String {
    if (!text.contains('%')) return text
    return try {
        // URLDecoder turns '+' into space; preserve literal '+' like JS decodeURIComponent
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (_: Throwable) {
        text
    }
}

private fun urlEncodeComponent(text: String): String =
    URLEncoder.encode(text, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

private fun encodeQueryComponent(text: String): String =
    urlEncodeComponent(text)
        .replace(Regex("%2F", RegexOption.IGNORE_CASE), "/")
        .replace(Regex("%3A", RegexOption.IGNORE_CASE), ":")

fun parseDbUrl(raw: String?): DbUrlParseResult {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return DbUrlParseResult.Empty
    if (looksLikeFilePath(s)) {
        return DbUrlParseResult.File(s.replace(FILE_SCHEME_RE, ""))
    }
    val m = SCHEME_RE.matchEntire(s) ?: return DbUrlParseResult.Error("PPM cannot read this as a URL.")
    val scheme = m.groupValues[1]
    val type = DB_SCHEMES[scheme.lowercase()]
        ?: return DbUrlParseResult.Error("$scheme:// is not a database PPM supports yet.")

    var rest = m.groupValues[2]
    val hashIdx = rest.indexOf('#')
    if (hashIdx >= 0) rest = rest.substring(0, hashIdx)
    var query = ""
    val qIdx = rest.indexOf('?')
    if (qIdx >= 0) {
        query = rest.substring(qIdx + 1)
        rest = rest.substring(0, qIdx)
    }
    val slashIdx = rest.indexOf('/')
    val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
    val path = if (slashIdx >= 0) rest.substring(slashIdx + 1) else ""

    val atIdx = authority.lastIndexOf('@')
    val login = if (atIdx >= 0) authority.substring(0, atIdx) else ""
    val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
    val colonIdx = login.indexOf(':')
    val user = urlDecodeComponent(if (colonIdx >= 0) login.substring(0, colonIdx) else login)
    val password = if (colonIdx >= 0) urlDecodeComponent(login.substring(colonIdx + 1)) else ""

    var host = hostPort
    var portText = ""
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        if (close < 0) return DbUrlParseResult.Error("PPM cannot read this as a URL.")
        host = hostPort.substring(1, close)
        val after = hostPort.substring(close + 1)
        if (after.isNotEmpty() && !after.startsWith(":")) return DbUrlParseResult.Error("PPM cannot read this as a URL.")
        portText = if (after.startsWith(":")) after.substring(1) else ""
    } else if (!hostPort.contains(",")) {
        val c = hostPort.lastIndexOf(':')
        if (c >= 0) {
            host = hostPort.substring(0, c)
            portText = hostPort.substring(c + 1)
        }
        if (host.contains(":")) {
            return DbUrlParseResult.Error("The server name has a colon in it. An IPv6 address goes in brackets, like [::1]:5432.")
        }
    }
    var port: Int? = null
    if (portText.isNotEmpty()) {
        val n = if (PORT_DIGITS_RE.matches(portText)) portText.toIntOrNull() else null
        if (n == null || n !in 1..65535) {
            return DbUrlParseResult.Error("The port must be a number from 1 to 65535.")
        }
        port = n
    }

    host = urlDecodeComponent(host)
    var socket = ""
    if (host.startsWith("/")) {
        socket = host
        host = ""
    }

    val sslNames = DB_SSL_PARAMS[type].orEmpty()
    var ssl: DbSslParam? = null
    val params = mutableListOf<Pair<String, String>>()
    for (piece in query.split("&")) {
        if (piece.isEmpty()) continue
        val eq = piece.indexOf('=')
        val name = urlDecodeComponent(if (eq >= 0) piece.substring(0, eq) else piece)
        val value = if (eq >= 0) urlDecodeComponent(piece.substring(eq + 1)) else ""
        val isSocket = if (type == "postgres") {
            name == "host" && value.startsWith("/")
        } else {
            name == "socket" || name == "socketPath"
        }
        if (isSocket && socket.isEmpty()) {
            socket = value
        } else if (isSocket) {
            continue
        } else if (ssl == null && name in sslNames) {
            ssl = DbSslParam(name, value)
        } else {
            params += name to value
        }
    }
    if (socket.isNotEmpty()) host = ""

    val dbName = urlDecodeComponent(path.substringBefore('/'))
    return DbUrlParseResult.Url(
        DbUrlParts(
            type = type,
            scheme = scheme,
            host = host,
            port = port,
            socket = socket,
            user = user,
            password = password,
            database = dbName,
            ssl = ssl,
            params = params,
        ),
    )
}

fun dbUrlProblem(parts: DbUrlParts): String? =
    if (parts.host.isEmpty() && parts.socket.isEmpty()) "The URL has no server name." else null

private fun formatHostText(host: String): String =
    if (host.contains(":") && !host.contains(",") && !host.startsWith("[")) "[$host]" else host

fun buildDbUrl(parts: DbUrlParts, includePassword: Boolean = true): String {
    val password = if (includePassword) parts.password else ""
    val login = if (parts.user.isNotEmpty() || password.isNotEmpty()) {
        "${urlEncodeComponent(parts.user)}${if (password.isNotEmpty()) ":${urlEncodeComponent(password)}" else ""}@"
    } else {
        ""
    }
    val where = if (parts.socket.isNotEmpty()) {
        ""
    } else {
        "${formatHostText(parts.host)}${if (parts.port == null) "" else ":${parts.port}"}"
    }
    val query = mutableListOf<Pair<String, String>>()
    if (parts.socket.isNotEmpty()) {
        query += (if (parts.type == "postgres") "host" else "socket") to parts.socket
    }
    if (parts.ssl != null) {
        query += parts.ssl.name to parts.ssl.value
    }
    query.addAll(parts.params)
    val qs = if (query.isNotEmpty()) {
        "?" + query.joinToString("&") { (k, v) -> "${encodeQueryComponent(k)}=${encodeQueryComponent(v)}" }
    } else {
        ""
    }
    return "${parts.scheme}://$login$where/${urlEncodeComponent(parts.database)}$qs"
}

private val SSL_ON_RE = Regex("^(require|required|verify-ca|verify_ca|verify-full|verify-identity|verify_identity|no-verify|true|1)$", RegexOption.IGNORE_CASE)
private val SSL_VERIFY_RE = Regex("^verify", RegexOption.IGNORE_CASE)

data class DbSslFlags(val useSsl: Boolean, val rejectUnauthorized: Boolean)

fun sslFlags(ssl: DbSslParam?): DbSslFlags {
    val value = ssl?.value.orEmpty()
    val useSsl = SSL_ON_RE.matches(value)
    return DbSslFlags(
        useSsl = useSsl,
        rejectUnauthorized = useSsl && SSL_VERIFY_RE.containsMatchIn(value),
    )
}

fun withSslFlags(parts: DbUrlParts, useSsl: Boolean, rejectUnauthorized: Boolean): DbUrlParts {
    val now = sslFlags(parts.ssl)
    if (now.useSsl == useSsl && now.rejectUnauthorized == (useSsl && rejectUnauthorized)) return parts
    if (!useSsl) return parts.copy(ssl = null)
    val pg = parts.type == "postgres"
    val value = if (pg) {
        if (rejectUnauthorized) "verify-full" else "require"
    } else {
        if (rejectUnauthorized) "VERIFY_IDENTITY" else "REQUIRED"
    }
    return parts.copy(ssl = DbSslParam(name = if (pg) "sslmode" else "ssl-mode", value = value))
}

fun dbUrlTarget(parts: DbUrlParts): String {
    if (parts.socket.isNotEmpty()) return "socket ${parts.socket}"
    val defaultPort = DB_DEFAULT_PORT[parts.type] ?: 5432
    return "${parts.host.ifEmpty { "localhost" }}:${parts.port ?: defaultPort}"
}

fun describeDbUrl(parts: DbUrlParts): String {
    val flags = sslFlags(parts.ssl)
    return listOfNotNull(
        DB_TYPE_LABELS[parts.type] ?: parts.type,
        dbUrlTarget(parts),
        parts.user.takeIf { it.isNotEmpty() }?.let { "user $it" },
        parts.password.takeIf { it.isNotEmpty() }?.let { "password set" },
        if (parts.database.isNotEmpty()) "database ${parts.database}" else "all databases",
        if (flags.useSsl) "SSL${if (flags.rejectUnauthorized) ", verified" else ""}" else null,
    ).joinToString(" · ")
}

// ── 9a.2 / 9a.4 Allowed Databases Filter (JS-Compatible Regex + ReDoS Protection) ──

/**
 * Translates JS-specific regex constructs like `[^]` (match any char including newline) into
 * JVM-compatible `[\s\S]` and compiles case-insensitively.
 */
fun compileJsCompatibleRegexOrNull(pattern: String?): Regex? {
    if (pattern.isNullOrEmpty()) return null
    val normalized = pattern.replace("[^]", "[\\s\\S]")
    return try {
        Regex(normalized, setOf(RegexOption.IGNORE_CASE))
    } catch (_: Throwable) {
        null
    }
}

/**
 * Bounds character-by-character access so catastrophic backtracking regexes like `(a+)+$`
 * on long inputs abort cleanly instead of hanging the UI thread (`phase-09a` Test Criteria #5).
 */
private class StepBoundedCharSequence(
    private val delegate: CharSequence,
    private val maxSteps: Int = 25_000,
) : CharSequence {
    private var steps = 0

    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        if (++steps > maxSteps) throw RegexStepLimitExceededException()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        StepBoundedCharSequence(delegate.subSequence(startIndex, endIndex), maxSteps - steps)

    override fun toString(): String = delegate.toString()
}

private class RegexStepLimitExceededException : RuntimeException()

fun safeRegexContainsMatch(regex: Regex, input: String, maxSteps: Int = 25_000): Boolean =
    try {
        regex.containsMatchIn(StepBoundedCharSequence(input, maxSteps))
    } catch (_: RegexStepLimitExceededException) {
        false
    } catch (_: Throwable) {
        false
    }

fun filterAllowedDatabases(
    names: List<String>,
    allowedDatabases: List<String> = emptyList(),
    allowedDatabasesRegex: String? = null,
): List<String> {
    val only = allowedDatabases.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val re = compileJsCompatibleRegexOrNull(allowedDatabasesRegex)
    return names.filter { name ->
        (only.isEmpty() || name.lowercase() in only) &&
            (re == null || safeRegexContainsMatch(re, name))
    }
}

// ── 9a.2 / 9a.3 Explorer Tree & Object Tree Models ──────────────────────────

data class TreeConnection(
    val id: Int,
    val type: String,
    val name: String,
    val groupName: String? = null,
    val color: String? = null,
    val readonly: Int = 1,
    val aiAccess: Int = 1,
    val passwordMode: String = "save",
    val loggedIn: Boolean = false,
    val allowedDatabases: List<String> = emptyList(),
    val allowedDatabasesRegex: String? = null,
    val defaultDatabase: String? = null,
    val singleDatabase: Boolean = true,
    val server: String? = null,
    val user: String? = null,
) {
    val isSingleDatabase: Boolean
        get() = type == "sqlite" || singleDatabase

    val ownDatabase: String?
        get() = if (type == "sqlite") null else defaultDatabase

    val singleDbRef: DbRef?
        get() = if (isSingleDatabase) DbRef(id, ownDatabase) else null

    val requiresInteractiveLogin: Boolean
        get() = (passwordMode == "askPassword" || passwordMode == "askUser") && !loggedIn
}

fun parseTreeConnections(array: JsonArray): List<TreeConnection> =
    array.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: "postgres"
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: "Connection $id"
        TreeConnection(
            id = id,
            type = type,
            name = name,
            groupName = (obj["group_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            color = (obj["color"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() },
            readonly = (obj["readonly"] as? JsonPrimitive)?.intOrNull ?: 1,
            aiAccess = (obj["ai_access"] as? JsonPrimitive)?.intOrNull ?: 1,
            passwordMode = (obj["password_mode"] as? JsonPrimitive)?.contentOrNull ?: "save",
            loggedIn = (obj["logged_in"] as? JsonPrimitive)?.booleanOrNull ?: true,
            allowedDatabases = (obj["allowed_databases"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            allowedDatabasesRegex = (obj["allowed_databases_regex"] as? JsonPrimitive)?.contentOrNull,
            defaultDatabase = (obj["default_database"] as? JsonPrimitive)?.contentOrNull,
            singleDatabase = (obj["single_database"] as? JsonPrimitive)?.booleanOrNull ?: (type == "sqlite"),
            server = (obj["server"] as? JsonPrimitive)?.contentOrNull,
            user = (obj["user"] as? JsonPrimitive)?.contentOrNull,
        )
    }

data class DbRef(
    val conn: Int,
    val database: String? = null,
) {
    val key: String get() = "$conn/${database.orEmpty()}"
}

fun tabDatabaseParam(ref: DbRef, conn: TreeConnection): String? =
    if (ref.database == null || ref.database == conn.ownDatabase) null else ref.database

fun focusDiffers(
    focused: DbRef?,
    current: DbRef?,
    connectionExists: (Int) -> Boolean,
): Boolean {
    if (focused == null || !connectionExists(focused.conn)) return false
    if (current == null) return true
    return focused.conn != current.conn || (focused.database != null && focused.database != current.database)
}

fun connectionWhere(c: TreeConnection): String {
    if (c.type == "sqlite") return c.server.orEmpty()
    val at = if (!c.server.isNullOrEmpty()) {
        "${if (!c.user.isNullOrEmpty()) "${c.user}@" else ""}${c.server}"
    } else {
        ""
    }
    return "$at${if (!c.defaultDatabase.isNullOrEmpty()) "/${c.defaultDatabase}" else ""}"
}

fun folderNames(conns: List<TreeConnection>, emptyFolders: List<String>): List<String> {
    val names = HashSet<String>()
    for (c in conns) if (!c.groupName.isNullOrBlank()) names += c.groupName
    for (f in emptyFolders) if (f.isNotBlank()) names += f
    return names.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

sealed interface ConnStatusState {
    data object Open : ConnStatusState
    data object Loading : ConnStatusState
    data class Error(val message: String, val missingDriverId: String? = null) : ConnStatusState
}

sealed interface ConnectionTreeRow {
    val key: String

    data object NewFolder : ConnectionTreeRow {
        override val key: String = "new-folder"
    }

    data class Folder(
        val name: String,
        val count: Int,
        val open: Boolean,
        val renaming: Boolean,
    ) : ConnectionTreeRow {
        override val key: String = "folder:$name"
    }

    data object Separator : ConnectionTreeRow {
        override val key: String = "separator"
    }

    data class ConnectionItem(
        val conn: TreeConnection,
        val depth: Int,
        val expandable: Boolean,
        val expanded: Boolean,
        val current: Boolean,
        val selected: Boolean,
        val status: ConnStatusState?,
    ) : ConnectionTreeRow {
        override val key: String = "conn:${conn.id}"
    }

    data class DatabaseItem(
        val conn: TreeConnection,
        val database: String,
        val depth: Int,
        val current: Boolean,
        val selected: Boolean,
    ) : ConnectionTreeRow {
        override val key: String = "db:${conn.id}/$database"
    }

    data class DatabasesLoading(val connId: Int, val depth: Int) : ConnectionTreeRow {
        override val key: String = "conn:$connId:loading"
    }

    data class DatabasesError(val connId: Int, val depth: Int, val message: String) : ConnectionTreeRow {
        override val key: String = "conn:$connId:error"
    }
}

fun buildConnectionTreeRows(
    connections: List<TreeConnection>,
    status: Map<Int, ConnStatusState>,
    databases: Map<Int, List<String>>,
    collapsedFolders: Set<String>,
    emptyFolders: List<String>,
    expandedConns: Set<Int>,
    current: DbRef?,
    focused: DbRef?,
    query: String = "",
    creatingFolder: Boolean = false,
    renamingFolder: String? = null,
): List<ConnectionTreeRow> {
    val needle = query.trim().lowercase()
    val searching = needle.isNotEmpty()
    val rows = mutableListOf<ConnectionTreeRow>()

    fun listedDbs(c: TreeConnection): List<String>? =
        databases[c.id]?.let { filterAllowedDatabases(it, c.allowedDatabases, c.allowedDatabasesRegex) }

    fun matchesConnection(c: TreeConnection): Pair<Boolean, List<String>?> {
        if (!searching) return true to null
        val hit = c.name.lowercase().contains(needle) ||
            (c.server?.lowercase()?.contains(needle) == true) ||
            (c.user?.lowercase()?.contains(needle) == true) ||
            (DB_TYPE_LABELS[c.type]?.lowercase()?.contains(needle) == true)
        val singleHit = c.isSingleDatabase && (c.defaultDatabase?.lowercase()?.contains(needle) == true)
        val dbs = if (!c.isSingleDatabase) {
            listedDbs(c)?.filter { it.lowercase().contains(needle) }.orEmpty()
        } else {
            emptyList()
        }
        return (hit || singleHit || dbs.isNotEmpty()) to (if (dbs.isNotEmpty() && !hit) dbs else null)
    }

    fun appendConnectionRows(c: TreeConnection, depth: Int, matchedDatabases: List<String>?) {
        val single = c.isSingleDatabase
        val open = status[c.id] is ConnStatusState.Open
        val expandable = !single && open
        val expanded = expandable && (c.id in expandedConns || matchedDatabases != null)
        val own = c.singleDbRef
        rows += ConnectionTreeRow.ConnectionItem(
            conn = c,
            depth = depth,
            expandable = expandable,
            expanded = expanded,
            current = current != null && current.conn == c.id && (!single || current == own),
            selected = focused != null && focused.conn == c.id && (if (single) focused == own else focused.database == null),
            status = status[c.id],
        )
        if (!expanded) return
        val dbList = matchedDatabases ?: listedDbs(c).orEmpty()
        for (db in dbList) {
            val ref = DbRef(c.id, db)
            rows += ConnectionTreeRow.DatabaseItem(
                conn = c,
                database = db,
                depth = depth + 1,
                current = current == ref,
                selected = focused == ref,
            )
        }
    }

    if (creatingFolder) rows += ConnectionTreeRow.NewFolder

    var shown = 0
    val folders = folderNames(connections, emptyFolders)
    for (folder in folders) {
        val kids = connections
            .filter { it.groupName == folder }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { it to matchesConnection(it) }
            .filter { it.second.first }
        if (searching && kids.isEmpty()) continue
        val open = searching || folder !in collapsedFolders
        rows += ConnectionTreeRow.Folder(
            name = folder,
            count = kids.size,
            open = open,
            renaming = renamingFolder == folder,
        )
        if (open) {
            for ((c, m) in kids) appendConnectionRows(c, 1, m.second)
        }
        shown += kids.size
    }

    val loose = connections
        .filter { it.groupName.isNullOrBlank() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        .map { it to matchesConnection(it) }
        .filter { it.second.first }
    if (loose.isNotEmpty() && (shown > 0 || (!searching && folders.isNotEmpty()))) {
        rows += ConnectionTreeRow.Separator
    }
    for ((c, m) in loose) appendConnectionRows(c, 0, m.second)
    return rows
}

// ── 9a.3 Object Tree Model (`object-tree-model.ts`) ─────────────────────────

const val GROUP_PAGE = 200
const val GROUP_PAGE_MORE = 500

val DB_OBJECT_GROUPS = listOf(
    "table" to "Tables",
    "view" to "Views",
    "matview" to "Materialized views",
    "procedure" to "Procedures",
    "function" to "Functions",
    "trigger" to "Triggers",
    "sequence" to "Sequences",
)

val KINDS_WITH_COLUMNS = setOf("table", "view", "matview")

data class DbObjectItem(
    val schema: String?,
    val name: String,
    val kind: String,
    val args: String? = null,
    val table: String? = null,
    val rowEstimate: Long? = null,
)

data class DbObjectListState(
    val schemas: List<String>,
    val objects: List<DbObjectItem>,
)

data class DbColumnRefItem(
    val schema: String?,
    val table: String,
    val name: String,
    val type: String,
    val pk: Boolean = false,
    val fk: String? = null,
)

fun parseDbObjectList(obj: JsonObject): DbObjectListState {
    val schemas = (obj["schemas"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    val objects = (obj["objects"] as? JsonArray)
        ?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: "table"
            DbObjectItem(
                schema = (o["schema"] as? JsonPrimitive)?.contentOrNull,
                name = name,
                kind = kind,
                args = (o["args"] as? JsonPrimitive)?.contentOrNull,
                table = (o["table"] as? JsonPrimitive)?.contentOrNull,
                rowEstimate = (o["rowEstimate"] as? JsonPrimitive)?.longOrNull,
            )
        }
        .orEmpty()
    return DbObjectListState(schemas = schemas, objects = objects)
}

fun parseStructureColumns(structureObj: JsonObject): List<DbColumnRefItem> {
    val pkSet = ((structureObj["primaryKey"] as? JsonObject)?.get("columns") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty()
    val fkMap = HashMap<String, String>()
    val fks = (structureObj["foreignKeys"] as? JsonArray).orEmpty()
    for (fkEl in fks) {
        val fkObj = fkEl as? JsonObject ?: continue
        val refTable = (fkObj["refTable"] as? JsonPrimitive)?.contentOrNull ?: continue
        val cols = (fkObj["columns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val refCols = (fkObj["refColumns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        cols.forEachIndexed { idx, col ->
            if (!fkMap.containsKey(col)) {
                fkMap[col] = "$refTable.${refCols.getOrNull(idx).orEmpty()}"
            }
        }
    }
    val tableName = (structureObj["name"] as? JsonPrimitive)?.contentOrNull
        ?: (structureObj["table"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val schemaName = (structureObj["schema"] as? JsonPrimitive)?.contentOrNull
    return ((structureObj["columns"] as? JsonArray).orEmpty()).mapNotNull { colEl ->
        val cObj = colEl as? JsonObject ?: return@mapNotNull null
        val name = (cObj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val type = (cObj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        DbColumnRefItem(
            schema = schemaName,
            table = tableName,
            name = name,
            type = type,
            pk = name in pkSet,
            fk = fkMap[name],
        )
    }
}

fun formatRowEstimate(n: Long): String = when {
    n >= 1_000_000L -> "~${((n / 100_000.0).roundToInt() / 10.0)}M"
    n >= 10_000L -> "~${(n / 1_000.0).roundToInt()}k"
    n >= 1_000L -> "~${((n / 100.0).roundToInt() / 10.0)}k"
    n >= 100L -> "~$n"
    else -> n.toString()
}

fun initialSchema(schemas: List<String>, objects: List<DbObjectItem>, remembered: String? = null): String? {
    val all = (schemas + objects.mapNotNull { it.schema }).distinct().sorted()
    if (all.isEmpty()) return null
    if (remembered != null && remembered in all) return remembered
    if ("public" in all) return "public"
    val nonEmpty = objects.mapNotNull { it.schema }.toSet()
    return all.firstOrNull { it in nonEmpty } ?: all.first()
}

sealed interface ObjectTreeRow {
    val key: String

    data class Group(
        val groupKind: String,
        val label: String,
        val count: Int,
        val open: Boolean,
    ) : ObjectTreeRow {
        override val key: String = "group:$groupKind"
    }

    data class ObjectEntry(
        val item: DbObjectItem,
        val nodeKey: String,
        val expandable: Boolean,
        val expanded: Boolean,
        val showArgs: Boolean,
    ) : ObjectTreeRow {
        override val key: String = "obj:${item.kind}:${item.schema.orEmpty()}.${item.name}:${item.args.orEmpty()}:${item.table.orEmpty()}"
    }

    data class ColumnEntry(
        val objectKey: String,
        val column: DbColumnRefItem,
    ) : ObjectTreeRow {
        override val key: String = "$objectKey|${column.name}"
    }

    data class MoreEntries(
        val groupKind: String,
        val hiddenCount: Int,
    ) : ObjectTreeRow {
        override val key: String = "more:$groupKind"
    }
}

fun buildObjectTreeRows(
    ref: DbRef,
    list: DbObjectListState,
    schema: String?,
    query: String = "",
    sort: String = "name",
    onlyWithRows: Boolean = false,
    openGroups: Set<String> = setOf("table"),
    expandedObjects: Set<String> = emptySet(),
    structures: Map<String, List<DbColumnRefItem>> = emptyMap(),
    shownPerGroup: Map<String, Int> = emptyMap(),
): List<ObjectTreeRow> {
    val needle = query.trim().lowercase()
    val searching = needle.isNotEmpty()
    val inSchema = if (schema == null) list.objects else list.objects.filter { it.schema == schema }
    val rows = mutableListOf<ObjectTreeRow>()

    for ((kind, label) in DB_OBJECT_GROUPS) {
        var items = inSchema.filter { o ->
            o.kind == kind && (
                !searching ||
                    o.name.lowercase().contains(needle) ||
                    (o.schema?.lowercase()?.contains(needle) == true)
                )
        }
        if (onlyWithRows && kind == "table") {
            items = items.filter { it.rowEstimate == null || it.rowEstimate > 0L }
        }
        if (items.isEmpty()) continue

        items = if (sort == "rows") {
            items.sortedWith(
                compareByDescending<DbObjectItem> { it.rowEstimate ?: -1L }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
        } else {
            items.sortedWith(
                compareBy<DbObjectItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.args.orEmpty() },
            )
        }

        val groupOpen = searching || kind in openGroups
        rows += ObjectTreeRow.Group(
            groupKind = kind,
            label = label,
            count = items.size,
            open = groupOpen,
        )
        if (!groupOpen) continue

        val limit = shownPerGroup[kind] ?: GROUP_PAGE
        val nameCounts = items.groupingBy { it.name }.eachCount()
        for (o in items.take(limit)) {
            val nodeKey = "${ref.key}|${o.schema.orEmpty()}.${o.name}"
            val expandable = o.kind in KINDS_WITH_COLUMNS
            val expanded = expandable && nodeKey in expandedObjects
            val objRow = ObjectTreeRow.ObjectEntry(
                item = o,
                nodeKey = nodeKey,
                expandable = expandable,
                expanded = expanded,
                showArgs = !o.args.isNullOrEmpty() && (nameCounts[o.name] ?: 0) > 1,
            )
            rows += objRow
            if (expanded) {
                for (col in structures[nodeKey].orEmpty()) {
                    rows += ObjectTreeRow.ColumnEntry(objectKey = objRow.key, column = col)
                }
            }
        }
        if (items.size > limit) {
            rows += ObjectTreeRow.MoreEntries(groupKind = kind, hiddenCount = items.size - limit)
        }
    }
    return rows
}

/** Deduplicates imported connection names: `"Prod"` -> `"Prod (2)"` -> `"Prod (3)"` (`§9a.2`). */
fun resolveImportedConnectionName(desiredName: String, takenNames: Set<String>): String {
    val base = desiredName.trim().ifEmpty { "Database" }
    if (base !in takenNames) return base
    var suffix = 2
    while ("$base ($suffix)" in takenNames) {
        suffix++
    }
    return "$base ($suffix)"
}

// ── 9a.4 Connection Form Model (`connection-form-state.ts`) ─────────────────

data class ConnectionFormValues(
    val type: String = "postgres",
    val entry: String = "fields",
    val url: String = "",
    val connMode: String = "host",
    val host: String = "",
    val port: String = "",
    val socket: String = "",
    val user: String = "",
    val password: String = "",
    val passwordMode: String = "save",
    val database: String = "",
    val singleDatabase: Boolean = true,
    val path: String = "",
    val readonly: Boolean = true,
    val aiAccess: Boolean = true,
    val name: String = "",
    val folder: String = "",
    val color: String? = null,
    val allowedDatabases: String = "",
    val allowedDatabasesRegex: String = "",
    val isolationLevel: String = "",
    val queryTimeoutSec: String = "",
    val urlSchemeExtra: String? = null,
    val urlSslExtra: DbSslParam? = null,
    val urlParamsExtra: List<Pair<String, String>> = emptyList(),
    val sshEnabled: Boolean = false,
    val sshHost: String = "",
    val sshPort: String = "",
    val sshBastionHost: String = "",
    val sshAuth: String = "password",
    val sshUser: String = "",
    val sshPassword: String = "",
    val sshKeyFile: String = "",
    val sshPassphrase: String = "",
    val sslCa: String = "",
    val sslCert: String = "",
    val sslKey: String = "",
    val sslKeyPassword: String = "",
)

data class EditingConnectionInfo(
    val id: Int,
    val passwordSaved: Boolean = false,
    val savedUrlUnreadable: Boolean = false,
    val sshPasswordSaved: Boolean = false,
    val sshPassphraseSaved: Boolean = false,
    val sslKeyPasswordSaved: Boolean = false,
)

data class FormProblem(val field: String, val message: String)

fun applyUrlPartsToForm(values: ConnectionFormValues, parts: DbUrlParts): ConnectionFormValues =
    values.copy(
        connMode = if (parts.socket.isNotEmpty()) "socket" else "host",
        host = parts.host,
        port = parts.port?.toString().orEmpty(),
        socket = parts.socket,
        user = parts.user,
        password = parts.password,
        database = parts.database,
        urlSchemeExtra = parts.scheme,
        urlSslExtra = parts.ssl,
        urlParamsExtra = parts.params,
    )

fun fieldPartsOfForm(values: ConnectionFormValues): DbUrlParts {
    val type = values.type
    val socketMode = values.connMode == "socket"
    val portTrimmed = values.port.trim()
    val validPort = portTrimmed.toIntOrNull()?.takeIf { it in 1..65535 }
    return DbUrlParts(
        type = type,
        scheme = values.urlSchemeExtra ?: DB_URL_SCHEME[type] ?: "postgres",
        host = if (socketMode) "" else values.host.trim().ifEmpty { "localhost" },
        port = if (socketMode) null else validPort,
        socket = if (socketMode) values.socket.trim() else "",
        user = values.user.trim().ifEmpty { DB_DEFAULT_USER[type] ?: "postgres" },
        password = values.password,
        database = values.database.trim(),
        ssl = values.urlSslExtra,
        params = values.urlParamsExtra,
    )
}

fun switchFormEntryMode(
    values: ConnectionFormValues,
    targetEntry: String,
): Pair<ConnectionFormValues?, FormProblem?> {
    if (targetEntry == values.entry || values.type == "sqlite") {
        return values.copy(entry = targetEntry) to null
    }
    if (targetEntry == "url") {
        val touched = listOf(values.host, values.port, values.socket, values.user, values.password, values.database)
            .any { it.isNotBlank() } || values.urlSslExtra != null || values.urlParamsExtra.isNotEmpty()
        val built = if (touched) buildDbUrl(fieldPartsOfForm(values)) else ""
        return values.copy(entry = "url", url = built) to null
    }
    return when (val parsed = parseDbUrl(values.url)) {
        is DbUrlParseResult.Empty -> values.copy(entry = "fields") to null
        is DbUrlParseResult.Url -> {
            val prob = dbUrlProblem(parsed.parts)
            if (prob != null) {
                null to FormProblem("url", prob)
            } else {
                applyUrlPartsToForm(values, parsed.parts).copy(entry = "fields") to null
            }
        }
        is DbUrlParseResult.File -> null to FormProblem("url", "That is a file path: pick SQLite above to open a file.")
        is DbUrlParseResult.Error -> null to FormProblem("url", parsed.error)
    }
}

fun defaultConnectionName(values: ConnectionFormValues): String {
    if (values.type == "sqlite") {
        return values.path.trim().substringAfterLast('/').substringAfterLast('\\').ifEmpty { "database.db" }
    }
    val parts = if (values.entry == "url") {
        (parseDbUrl(values.url) as? DbUrlParseResult.Url)?.parts
    } else {
        fieldPartsOfForm(values)
    }
    val host = if (parts?.socket?.isNotEmpty() == true) "localhost" else parts?.host?.ifEmpty { "localhost" } ?: "localhost"
    val db = parts?.database.orEmpty()
    val user = if (values.passwordMode == "askUser") "" else parts?.user.orEmpty()
    val prefix = db.ifEmpty { user.ifEmpty { DB_DEFAULT_USER[values.type] ?: "postgres" } }
    return "$prefix@$host"
}

fun effectiveConnectionName(values: ConnectionFormValues): String =
    values.name.trim().ifEmpty { defaultConnectionName(values) }

fun validateConnectionForm(
    values: ConnectionFormValues,
    takenNames: Set<String> = emptySet(),
    forSave: Boolean = true,
): FormProblem? {
    if (values.type == "sqlite") {
        if (values.path.isBlank()) return FormProblem("path", "Enter the path of the database file.")
    } else if (values.entry == "url") {
        when (val parsed = parseDbUrl(values.url)) {
            is DbUrlParseResult.Empty -> return FormProblem("url", "Enter the database URL.")
            is DbUrlParseResult.File -> return FormProblem("url", "That is a file path: pick SQLite above to open a file.")
            is DbUrlParseResult.Error -> return FormProblem("url", parsed.error)
            is DbUrlParseResult.Url -> {
                val prob = dbUrlProblem(parsed.parts)
                if (prob != null) return FormProblem("url", prob)
            }
        }
    } else if (values.connMode == "socket") {
        if (values.socket.isBlank()) return FormProblem("socket", "Enter the path of the socket.")
    } else if (values.port.isNotBlank()) {
        val p = values.port.trim().toIntOrNull()
        if (p == null || p !in 1..65535) return FormProblem("port", "A number from 1 to 65535.")
    }

    if (values.type != "sqlite" && values.sshEnabled) {
        if (values.sshHost.isBlank()) return FormProblem("sshHost", "Enter the SSH host.")
        if (values.sshPort.isNotBlank()) {
            val sp = values.sshPort.trim().toIntOrNull()
            if (sp == null || sp !in 1..65535) return FormProblem("sshPort", "A number from 1 to 65535.")
        }
    }

    if (values.type != "sqlite" && values.queryTimeoutSec.isNotBlank()) {
        val sec = values.queryTimeoutSec.trim().toIntOrNull()
        if (sec == null || sec !in 1..86_400) {
            return FormProblem("queryTimeoutSec", "A whole number of seconds from 1 to 86400, or empty for no limit.")
        }
    }

    if (forSave && effectiveConnectionName(values) in takenNames) {
        return FormProblem("name", "Another connection already has this name.")
    }
    return null
}

fun buildConnectionConfigJson(
    values: ConnectionFormValues,
    editing: EditingConnectionInfo? = null,
): JsonObject {
    if (values.type == "sqlite") {
        return buildJsonObject {
            put("type", "sqlite")
            put("path", values.path.trim())
        }
    }
    val effectiveMode = if (values.entry == "fields") values.passwordMode else "save"
    val sentParts = fieldPartsOfForm(values).let { p ->
        val noPass = if (effectiveMode == "askPassword" || effectiveMode == "askUser") p.copy(password = "") else p
        if (effectiveMode == "askUser") noPass.copy(user = "") else noPass
    }
    val connStr = if (values.entry == "url") values.url.trim() else buildDbUrl(sentParts)

    return buildJsonObject {
        put("type", values.type)
        put("entry", values.entry)
        put("passwordMode", effectiveMode)
        put(
            "allowedDatabases",
            buildJsonArray {
                values.allowedDatabases.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) }
            },
        )
        put("allowedDatabasesRegex", values.allowedDatabasesRegex.trim())
        if (values.isolationLevel.isNotBlank()) put("isolationLevel", values.isolationLevel)
        if (values.queryTimeoutSec.isNotBlank()) {
            values.queryTimeoutSec.trim().toIntOrNull()?.let { put("queryTimeoutSec", it) }
        }
        if (values.database.isNotBlank() || connStr.substringAfter("://").substringAfter('/').substringBefore('?').isNotBlank()) {
            put("singleDatabase", values.singleDatabase)
        }
        if (!(editing?.savedUrlUnreadable == true && values.entry == "url" && values.url.isBlank())) {
            put("connectionString", connStr)
        }
        put(
            "ssh",
            buildJsonObject {
                put("enabled", values.sshEnabled)
                put("host", values.sshHost.trim())
                put("port", values.sshPort.trim())
                put("bastionHost", values.sshBastionHost.trim())
                put("auth", values.sshAuth)
                put("user", values.sshUser.trim())
                put("keyFile", values.sshKeyFile.trim())
                if (values.sshAuth == "password" && values.sshPassword.isNotEmpty()) put("password", values.sshPassword)
                if (values.sshAuth == "keyFile" && values.sshPassphrase.isNotEmpty()) put("passphrase", values.sshPassphrase)
            },
        )
        put(
            "ssl",
            buildJsonObject {
                put("ca", values.sslCa.trim())
                put("cert", values.sslCert.trim())
                put("key", values.sslKey.trim())
                if (values.sslKeyPassword.isNotEmpty()) put("keyPassword", values.sslKeyPassword)
            },
        )
        if (editing != null && (editing.passwordSaved || editing.sshPasswordSaved || editing.sshPassphraseSaved || editing.sslKeyPasswordSaved)) {
            put("keepPassword", true)
        }
    }
}

fun buildSaveConnectionRequestJson(
    values: ConnectionFormValues,
    editing: EditingConnectionInfo? = null,
): JsonObject = buildJsonObject {
    val asks = (values.entry == "fields" && (values.passwordMode == "askPassword" || values.passwordMode == "askUser"))
    put("type", values.type)
    put("name", effectiveConnectionName(values))
    put("connectionConfig", buildConnectionConfigJson(values, editing))
    val folder = values.folder.trim()
    if (folder.isNotEmpty()) {
        put("groupName", folder.take(100))
    } else if (editing != null) {
        put("groupName", JsonNull)
    }
    if (values.color != null) {
        put("color", values.color)
    } else if (editing != null) {
        put("color", JsonNull)
    }
    put("readonly", values.readonly)
    put("aiAccess", values.aiAccess && !asks)
}

fun buildTestConnectionRequestJson(
    values: ConnectionFormValues,
    editing: EditingConnectionInfo? = null,
): JsonObject = buildJsonObject {
    put("type", values.type)
    put("connectionConfig", buildConnectionConfigJson(values, editing))
    if (editing != null) put("connectionId", editing.id)
}
