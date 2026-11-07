package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ── 9a.1 Database Client Timeouts, 428 Login Coordinator & Events ───────────

/**
 * Timeout profiles for `/api/db/` routes (`phase-09a-db-ket-noi.md` §9a.1):
 * - [CATALOG]: 30 s read timeout (metadata, connections, structure, object-sql)
 * - [GRID_STREAM]: 300 s read timeout (`grid/rows`, `grid/export`, `grid/cell`)
 * - [COUNT]: `limitSec + 30` s read timeout (`grid/count`)
 * - [UNBOUNDED]: 0 s (no read timeout; only stops when cancelled — `query/script`, `changeset/apply`, `structure/apply`)
 */
enum class DbRequestTimeoutKind {
    CATALOG,
    GRID_STREAM,
    COUNT,
    UNBOUNDED,
}

fun dbReadTimeoutSeconds(kind: DbRequestTimeoutKind, countLimitSec: Int = 30): Long = when (kind) {
    DbRequestTimeoutKind.CATALOG -> PpmHttp.GET_TIMEOUT_SECONDS
    DbRequestTimeoutKind.GRID_STREAM -> 300L
    DbRequestTimeoutKind.COUNT -> (countLimitSec.coerceAtLeast(1) + 30).toLong()
    DbRequestTimeoutKind.UNBOUNDED -> 0L
}

internal fun dbClientForTimeout(kind: DbRequestTimeoutKind, countLimitSec: Int = 30): OkHttpClient {
    val readSec = dbReadTimeoutSeconds(kind, countLimitSec)
    return when (kind) {
        DbRequestTimeoutKind.CATALOG -> PpmHttp.getClient
        else -> PpmHttp.client.newBuilder()
            .readTimeout(readSec, TimeUnit.SECONDS)
            .build()
    }
}

/** Prompt payload returned in HTTP 428 (`DB_LOGIN_REQUIRED`). */
data class DbLoginPromptInfo(
    val connectionId: Int?,
    val name: String,
    val type: String,
    val user: String,
    val askUser: Boolean,
)

/**
 * Thrown when a database route answers HTTP 428 (`DB_LOGIN_REQUIRED`).
 * Distinct from HTTP 401 ([PpmError.Unauthorized]): 401 means PPM's session expired,
 * whereas 428 means the database connection needs a password in server memory.
 */
class DbLoginRequiredException(
    val prompt: DbLoginPromptInfo,
    message: String = "Database login required for ${prompt.name}",
) : Exception(message)

/**
 * Thrown when a database route answers HTTP 424 (`DB_DRIVER_MISSING`).
 */
class DbDriverMissingException(
    val driverId: String,
    val driverLabel: String,
    message: String,
) : Exception(message)

/**
 * Coalesces concurrent HTTP 428 `DB_LOGIN_REQUIRED` responses for the same connection into a
 * single login dialog prompt, and replays all waiting requests once the user logs in (`§9a.1`).
 */
class DbLoginCoordinator(
    private val onPromptLogin: suspend (DbLoginPromptInfo) -> Boolean,
) {
    private val mutex = Mutex()
    private val inflightByKey = LinkedHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun awaitLogin(prompt: DbLoginPromptInfo): Boolean {
        val key = "${prompt.connectionId ?: "unsaved"}:${prompt.name}"
        val existingOrNew: Pair<CompletableDeferred<Boolean>, Boolean> = mutex.withLock {
            val current = inflightByKey[key]
            if (current != null) {
                current to false
            } else {
                val created = CompletableDeferred<Boolean>()
                inflightByKey[key] = created
                created to true
            }
        }
        val (deferred, isLeader) = existingOrNew
        if (isLeader) {
            val ok = try {
                onPromptLogin(prompt)
            } catch (_: Throwable) {
                false
            } finally {
                mutex.withLock {
                    inflightByKey.remove(key)
                }
            }
            deferred.complete(ok)
        }
        return deferred.await()
    }
}

sealed interface DbClientEvent {
    data object ConnectionsChanged : DbClientEvent
    data class DriverInstalled(val driverId: String) : DbClientEvent
}

object DbConnectionsEvents {
    private val _events = MutableSharedFlow<DbClientEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DbClientEvent> = _events.asSharedFlow()

    fun notifyConnectionsChanged() {
        _events.tryEmit(DbClientEvent.ConnectionsChanged)
    }

    fun notifyDriverInstalled(driverId: String) {
        _events.tryEmit(DbClientEvent.DriverInstalled(driverId))
    }
}

private val DB_JSON_MEDIA = "application/json".toMediaType()
private val DB_EMPTY_BODY = ByteArray(0).toRequestBody(null)

/**
 * Executes a `/api/db/` request with per-route timeout [timeoutKind], parsing HTTP 428
 * (`DB_LOGIN_REQUIRED`) and HTTP 424 (`DB_DRIVER_MISSING`), and automatically replaying the exact
 * request once [loginCoordinator] completes a 428 login.
 */
suspend fun ApiClient.dbCall(
    method: String,
    path: String,
    query: List<Pair<String, String>> = emptyList(),
    body: JsonElement? = null,
    timeoutKind: DbRequestTimeoutKind = DbRequestTimeoutKind.CATALOG,
    countLimitSec: Int = 30,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonElement {
    val reqBody = body?.toString()?.toRequestBody(DB_JSON_MEDIA)
        ?: if (method == "GET" || method == "HEAD") null else DB_EMPTY_BODY
    val request = Request.Builder()
        .url(url(path, query))
        .method(method, reqBody)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .header("x-ppm-client", PpmHttp.CLIENT_NAME)
        .build()

    val httpClient = dbClientForTimeout(timeoutKind, countLimitSec)
    return try {
        sendDbRequest(httpClient, request)
    } catch (loginReq: DbLoginRequiredException) {
        if (loginCoordinator != null && loginCoordinator.awaitLogin(loginReq.prompt)) {
            sendDbRequest(httpClient, request)
        } else {
            throw loginReq
        }
    }
}

internal suspend fun sendDbRequest(client: OkHttpClient, request: Request): JsonElement {
    val response = try {
        client.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            val text = try {
                res.body.string()
            } catch (error: IOException) {
                throw PpmError.Unreachable(describe(error))
            }
            if (res.code == 401) throw PpmError.Unauthorized()
            val json = WireJson.parseOrNull(text)
            if (res.code == 428 && json != null && json["code"].string == "DB_LOGIN_REQUIRED") {
                val loginObj = json["login"] as? JsonObject
                val prompt = DbLoginPromptInfo(
                    connectionId = loginObj?.get("connectionId")?.int,
                    name = loginObj?.get("name")?.string.orEmpty(),
                    type = loginObj?.get("type")?.string ?: "postgres",
                    user = loginObj?.get("user")?.string.orEmpty(),
                    askUser = loginObj?.get("askUser")?.bool == true,
                )
                throw DbLoginRequiredException(prompt, json["error"].string ?: "Database login required")
            }
            if (res.code == 424 && json != null && json["code"].string == "DB_DRIVER_MISSING") {
                val driverObj = json["driver"] as? JsonObject
                val driverId = driverObj?.get("id")?.string ?: json["driverId"].string ?: "unknown"
                val driverLabel = driverObj?.get("label")?.string ?: driverId
                throw DbDriverMissingException(
                    driverId = driverId,
                    driverLabel = driverLabel,
                    message = json["error"].string ?: "Database driver $driverLabel is not installed.",
                )
            }
            ApiClient.unwrap(res.code, res.header("Content-Type"), text)
        }
    }
}

// ── 9a.1–9a.5 Database REST Endpoints ───────────────────────────────────────

private fun connQuery(database: String? = null, path: String? = null, project: String? = null): List<Pair<String, String>> =
    buildList {
        if (!database.isNullOrBlank()) add("database" to database)
        if (!path.isNullOrBlank()) add("path" to path)
        if (!project.isNullOrBlank()) add("project" to project)
    }

/** `GET /api/db/connections` — list all saved database connections. */
suspend fun ApiClient.dbConnections(): JsonArray =
    (dbCall("GET", "/api/db/connections") as? JsonArray) ?: JsonArray(emptyList())

/** `GET /api/db/connections/:id/config` — fetch editable config (without secrets) for editing. */
suspend fun ApiClient.dbConnectionConfig(id: Int): JsonObject =
    (dbCall("GET", "/api/db/connections/$id/config") as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/connections` — create a new saved connection. */
suspend fun ApiClient.dbCreateConnection(body: JsonObject): JsonObject {
    val res = (dbCall("POST", "/api/db/connections", body = body) as? JsonObject) ?: buildJsonObject {}
    DbConnectionsEvents.notifyConnectionsChanged()
    return res
}

/** `PUT /api/db/connections/:id` — update an existing saved connection. */
suspend fun ApiClient.dbUpdateConnection(id: Int, body: JsonObject): JsonObject {
    val res = (dbCall("PUT", "/api/db/connections/$id", body = body) as? JsonObject) ?: buildJsonObject {}
    DbConnectionsEvents.notifyConnectionsChanged()
    return res
}

/** `DELETE /api/db/connections/:id` — delete a saved connection from PPM. */
suspend fun ApiClient.dbDeleteConnection(id: Int) {
    dbCall("DELETE", "/api/db/connections/$id")
    DbConnectionsEvents.notifyConnectionsChanged()
}

/** `POST /api/db/connections/:id/duplicate` — duplicate a saved connection. */
suspend fun ApiClient.dbDuplicateConnection(id: Int): JsonObject {
    val res = (dbCall("POST", "/api/db/connections/$id/duplicate") as? JsonObject) ?: buildJsonObject {}
    DbConnectionsEvents.notifyConnectionsChanged()
    return res
}

/** `PATCH /api/db/connections/:id/group` — move connection to a folder (`groupName = null` for root). */
suspend fun ApiClient.dbMoveConnectionGroup(id: Int, groupName: String?) {
    val body = buildJsonObject {
        if (groupName == null) put("groupName", JsonNull) else put("groupName", groupName)
    }
    dbCall("PATCH", "/api/db/connections/$id/group", body = body)
    DbConnectionsEvents.notifyConnectionsChanged()
}

/** `POST /api/db/connections/:id/login` — store runtime DB login in server memory. */
suspend fun ApiClient.dbLoginConnection(id: Int, password: String, user: String? = null) {
    val body = buildJsonObject {
        put("password", password)
        if (!user.isNullOrBlank()) put("user", user)
    }
    dbCall("POST", "/api/db/connections/$id/login", body = body)
    DbConnectionsEvents.notifyConnectionsChanged()
}

/** `POST /api/db/connections/:id/disconnect` — close pool and optionally keep held login (`keepLogin`). */
suspend fun ApiClient.dbDisconnectConnection(id: Int, keepLogin: Boolean = false) {
    val body = buildJsonObject { put("keepLogin", keepLogin) }
    dbCall("POST", "/api/db/connections/$id/disconnect", body = body)
    if (!keepLogin) DbConnectionsEvents.notifyConnectionsChanged()
}

/** `POST /api/db/test` — test a connection config without saving. */
suspend fun ApiClient.dbTestConnection(body: JsonObject): JsonObject =
    (dbCall("POST", "/api/db/test", body = body) as? JsonObject) ?: buildJsonObject {}

/** `GET /api/db/ssh/agent` — check whether PPM host has an SSH agent and default OS user. */
suspend fun ApiClient.dbSshAgent(): JsonObject =
    (dbCall("GET", "/api/db/ssh/agent") as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/drivers/:id/install` — install a missing database driver on the PPM host and notify database views. */
suspend fun ApiClient.dbInstallMissingDriver(driverId: String): JsonObject {
    val res = (dbCall(
        method = "POST",
        path = "/api/db/drivers/${ApiClient.escape(driverId)}/install",
        timeoutKind = DbRequestTimeoutKind.UNBOUNDED,
    ) as? JsonObject) ?: buildJsonObject {}
    DbConnectionsEvents.notifyDriverInstalled(driverId)
    DbConnectionsEvents.notifyConnectionsChanged()
    return res
}

/** `GET /api/db/connections/export` — export saved connections (includes plaintext passwords). */
suspend fun ApiClient.dbExportConnections(): JsonObject =
    (dbCall("GET", "/api/db/connections/export") as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/connections/import` — import connections list. */
suspend fun ApiClient.dbImportConnections(connections: JsonArray): JsonObject {
    val body = buildJsonObject { put("connections", connections) }
    val res = (dbCall("POST", "/api/db/connections/import", body = body) as? JsonObject) ?: buildJsonObject {}
    DbConnectionsEvents.notifyConnectionsChanged()
    return res
}

/** `GET /api/db/connections/:id/databases` — list server databases for a connection. */
suspend fun ApiClient.dbListDatabases(
    connectionId: Int,
    loginCoordinator: DbLoginCoordinator? = null,
): List<String> {
    val arr = dbCall(
        method = "GET",
        path = "/api/db/connections/$connectionId/databases",
        loginCoordinator = loginCoordinator,
    ) as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.string }
}

/** `GET /api/db/connections/:id/objects` — list schemas and objects in a database. */
suspend fun ApiClient.dbListObjects(
    connectionId: String,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "GET",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/objects",
        query = connQuery(database, path, project),
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/** `GET /api/db/connections/:id/columns` — list all columns across tables/views for column search. */
suspend fun ApiClient.dbListColumns(
    connectionId: String,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonArray =
    (dbCall(
        method = "GET",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/columns",
        query = connQuery(database, path, project),
        loginCoordinator = loginCoordinator,
    ) as? JsonArray) ?: JsonArray(emptyList())

/** `GET /api/db/connections/:id/structure?table=&schema=` — full table structure (columns, PK, FK, indexes). */
suspend fun ApiClient.dbTableStructure(
    connectionId: String,
    table: String,
    schema: String? = null,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject {
    val q = connQuery(database, path, project).toMutableList()
    q += "table" to table
    if (!schema.isNullOrBlank()) q += "schema" to schema
    return (dbCall(
        method = "GET",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/structure",
        query = q,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}
}

/** `GET /api/db/connections/:id/object-sql?kind=&name=&schema=&args=&table=` — CREATE / SELECT / INSERT scripts. */
suspend fun ApiClient.dbObjectSql(
    connectionId: String,
    kind: String,
    name: String,
    schema: String? = null,
    args: String? = null,
    table: String? = null,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject {
    val q = connQuery(database, path, project).toMutableList()
    q += "kind" to kind
    q += "name" to name
    if (!schema.isNullOrBlank()) q += "schema" to schema
    if (args != null) q += "args" to args
    if (!table.isNullOrBlank()) q += "table" to table
    return (dbCall(
        method = "GET",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/object-sql",
        query = q,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}
}

// ── 9b.1–9b.5 Database Grid, Count & Quick Export Endpoints ─────────────────

/** `GET /api/db/connections/:id/schema?table=&schema=` — column schema metadata for a table. */
suspend fun ApiClient.dbTableSchema(
    connectionId: String,
    table: String,
    schema: String? = null,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonArray {
    val q = connQuery(database, path, project).toMutableList()
    q += "table" to table
    if (!schema.isNullOrBlank()) q += "schema" to schema
    return (dbCall(
        method = "GET",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/schema",
        query = q,
        loginCoordinator = loginCoordinator,
    ) as? JsonArray) ?: JsonArray(emptyList())
}

/** `POST /api/db/connections/:id/grid` — fetch one page of table rows (300 s read timeout). */
suspend fun ApiClient.dbGridPage(
    connectionId: String,
    body: JsonObject,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/grid",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.GRID_STREAM,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/connections/:id/grid/count` — background or exact (`exact = true`) row count. */
suspend fun ApiClient.dbGridCount(
    connectionId: String,
    body: JsonObject,
    exact: Boolean = false,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/grid/count",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.COUNT,
        countLimitSec = if (exact) 120 else 10,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/connections/:id/grid/values` — fetch up to 100 distinct values for a column ("Choose value" dialog). */
suspend fun ApiClient.dbGridValues(
    connectionId: String,
    body: JsonObject,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/grid/values",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.GRID_STREAM,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/connections/:id/grid/export` — start a streaming table export and receive a single-use ticket. */
suspend fun ApiClient.dbGridExportTicket(
    connectionId: String,
    body: JsonObject,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/grid/export",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.GRID_STREAM,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/** `POST /api/db/connections/:id/grid/cell` — request a single-use ticket to download a truncated binary cell whole. */
suspend fun ApiClient.dbGridCellTicket(
    connectionId: String,
    body: JsonObject,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/grid/cell",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.GRID_STREAM,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/**
 * `GET /api/db/grid-export/:ticket` — downloads the exported file directly via OkHttp in a single GET
 * without using Android's `DownloadManager` (which probes/retries and would consume the single-use 30 s ticket).
 */
suspend fun ApiClient.dbDownloadGridExportTicket(ticket: String): ByteArray {
    val request = Request.Builder()
        .url(url("/api/db/grid-export/${ApiClient.escape(ticket)}", emptyList()))
        .get()
        .header("Authorization", "Bearer $token")
        .header("x-ppm-client", PpmHttp.CLIENT_NAME)
        .build()
    val httpClient = dbClientForTimeout(DbRequestTimeoutKind.GRID_STREAM)
    val response = try {
        httpClient.newCall(request).await()
    } catch (error: IOException) {
        throw PpmError.Unreachable(describe(error))
    }
    return withContext(Dispatchers.IO) {
        response.use { res ->
            if (!res.isSuccessful) {
                val text = runCatching { res.body.string() }.getOrDefault("")
                ApiClient.unwrap(res.code, res.header("Content-Type"), text)
            }
            res.body.bytes()
        }
    }
}

// ── 9d.1 Database Changeset Preview & Apply Endpoints ───────────────────────

/** `POST /api/db/connections/:id/changeset/preview` — preview SQL script and FK references to deleted rows. */
suspend fun ApiClient.dbChangesetPreview(
    connectionId: String,
    body: JsonObject,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/changeset/preview",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.CATALOG,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}

/**
 * `POST /api/db/connections/:id/changeset/apply` — apply changeset in a single transaction
 * (`UNBOUNDED` read timeout so row locks wait until resolved or cancelled).
 */
suspend fun ApiClient.dbChangesetApply(
    connectionId: String,
    body: JsonObject,
    database: String? = null,
    path: String? = null,
    project: String? = null,
    loginCoordinator: DbLoginCoordinator? = null,
): JsonObject =
    (dbCall(
        method = "POST",
        path = "/api/db/connections/${ApiClient.escape(connectionId)}/changeset/apply",
        query = connQuery(database, path, project),
        body = body,
        timeoutKind = DbRequestTimeoutKind.UNBOUNDED,
        loginCoordinator = loginCoordinator,
    ) as? JsonObject) ?: buildJsonObject {}


