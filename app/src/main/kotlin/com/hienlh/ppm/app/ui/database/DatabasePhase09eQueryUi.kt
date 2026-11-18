package com.hienlh.ppm.app.ui.database

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.DbDriverMissingException
import com.hienlh.ppm.client.DbLoginCoordinator
import com.hienlh.ppm.client.DbLoginFailedException
import com.hienlh.ppm.client.DbLoginPromptInfo
import com.hienlh.ppm.client.dbChangesetApply
import com.hienlh.ppm.client.dbChangesetPreview
import com.hienlh.ppm.client.dbConnections
import com.hienlh.ppm.client.dbInstallMissingDriver
import com.hienlh.ppm.client.dbListDatabases
import com.hienlh.ppm.client.dbListObjects
import com.hienlh.ppm.client.dbLoginConnection
import com.hienlh.ppm.client.dbQueryCancel
import com.hienlh.ppm.client.dbQueryHistory
import com.hienlh.ppm.client.dbQueryScriptStream
import com.hienlh.ppm.client.dbTableSchema
import com.hienlh.ppm.client.fsList
import com.hienlh.ppm.client.writeFile
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.database.ChangesetApplyResult
import com.hienlh.ppm.state.database.ChangesetPreview
import com.hienlh.ppm.state.database.DEFAULT_QUERY_ROW_LIMIT
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbObjectListState
import com.hienlh.ppm.state.database.ExtractedQueryTable
import com.hienlh.ppm.state.database.GridChangesPayload
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.HISTORY_STATUS_LABEL
import com.hienlh.ppm.state.database.MESSAGES_TAB
import com.hienlh.ppm.state.database.QUERY_ROW_LIMITS
import com.hienlh.ppm.state.database.QueryHistoryItem
import com.hienlh.ppm.state.database.QueryHistoryResponse
import com.hienlh.ppm.state.database.QueryResultSet
import com.hienlh.ppm.state.database.QueryRun
import com.hienlh.ppm.state.database.QueryScriptEvent
import com.hienlh.ppm.state.database.QueryStatementResult
import com.hienlh.ppm.state.database.ResultEditability
import com.hienlh.ppm.state.database.RowCountView
import com.hienlh.ppm.state.database.SavedQueryFile
import com.hienlh.ppm.state.database.SqlToRun
import com.hienlh.ppm.state.database.TableRef
import com.hienlh.ppm.state.database.TimedQueryEvent
import com.hienlh.ppm.state.database.TreeConnection
import com.hienlh.ppm.state.database.applyQueryEvents
import com.hienlh.ppm.state.database.buildChangesetRequestJson
import com.hienlh.ppm.state.database.computeSqlCompletions
import com.hienlh.ppm.state.database.cursorAfterReformat
import com.hienlh.ppm.state.database.deduplicateResultColumns
import com.hienlh.ppm.state.database.defaultQueryFileName
import com.hienlh.ppm.state.database.extractQueryTable
import com.hienlh.ppm.state.database.failQueryRun
import com.hienlh.ppm.state.database.formatSqlScript
import com.hienlh.ppm.state.database.hasFileNamed
import com.hienlh.ppm.state.database.historyHasMore
import com.hienlh.ppm.state.database.historyMeta
import com.hienlh.ppm.state.database.initialSchema
import com.hienlh.ppm.state.database.lineOfPosition
import com.hienlh.ppm.state.database.mergeHistoryPage
import com.hienlh.ppm.state.database.nextQueryNumber
import com.hienlh.ppm.state.database.parseChangesetApplyResult
import com.hienlh.ppm.state.database.parseChangesetPreview
import com.hienlh.ppm.state.database.parseDbObjectList
import com.hienlh.ppm.state.database.parseGridColumnsSchema
import com.hienlh.ppm.state.database.parseQueryHistoryResponse
import com.hienlh.ppm.state.database.parseQueryScriptEventJson
import com.hienlh.ppm.state.database.parseTreeConnections
import com.hienlh.ppm.state.database.queryRowLimit
import com.hienlh.ppm.state.database.queryTabMetadata
import com.hienlh.ppm.state.database.refusedAsReadonly
import com.hienlh.ppm.state.database.replaceResultSet
import com.hienlh.ppm.state.database.resultEditability
import com.hienlh.ppm.state.database.resultOfTab
import com.hienlh.ppm.state.database.retentionNote
import com.hienlh.ppm.state.database.savedFileOf
import com.hienlh.ppm.state.database.savedQueryTab
import com.hienlh.ppm.state.database.scriptRun
import com.hienlh.ppm.state.database.selectionOrStatementRun
import com.hienlh.ppm.state.database.setUnsavedGridRows
import com.hienlh.ppm.state.database.shownResultTab
import com.hienlh.ppm.state.database.splitSqlStatementsWithLines
import com.hienlh.ppm.state.database.startQueryRun
import com.hienlh.ppm.state.database.statementAt
import com.hienlh.ppm.state.database.stopTitle
import com.hienlh.ppm.state.database.withSqlExtension
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.webRandomId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call

private const val QUERY_PREFS_FILE = "ppm_db_query_prefs"
private const val PREF_CONTINUE_ON_ERROR = "ppm:db-query-continue-on-error"
private const val PREF_ROW_LIMIT = "ppm:db-query-row-limit"
private const val STOP_GRACE_MS = 1_000L

/** Specification of a query run so `Run with write access (once)` can replay it with `writeOnce`. */
data class QueryRunSpec(
    val sql: String,
    val lineOffset: Int = 0,
    val maxRows: Int = DEFAULT_QUERY_ROW_LIMIT,
    val continueOnError: Boolean = false,
    val explain: Boolean = false,
    val writeOncePassword: String? = null,
)

// ── 9e.1–9e.6 Live Workspace Tab Body (`tab.type == "db-query"`) ─────────────

@Composable
fun DatabaseQueryTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val scope = rememberCoroutineScope()

    val dbFileObj = tab.metadata["dbFile"] as? JsonObject
    val filePath = dbFileObj?.get("path")?.string ?: tab.metaString("filePath")
    val fileProject = dbFileObj?.get("projectName")?.string ?: tab.metaString("project") ?: tab.projectId ?: model.selectedProject

    val initialConnId = (tab.metadata["connectionId"] as? JsonPrimitive)?.intOrNull
        ?: tab.metaString("connectionId")?.toIntOrNull()
    val initialConnName = tab.metaString("connectionName")
    val initialDbType = tab.metaString("dbType") ?: if (filePath != null) "sqlite" else "postgres"
    val initialDatabase = tab.metaString("database")
    val initialSql = tab.metaString("currentSql") ?: tab.metaString("initialSql") ?: tab.metaString("sql").orEmpty()
    val initialOpenedSql = tab.metaString("openedSql") ?: initialSql
    val runOnOpen = tab.metadata["runOnOpen"]?.bool == true

    var connections by remember { mutableStateOf<List<TreeConnection>>(emptyList()) }
    var selectedConnId by remember(initialConnId) { mutableStateOf(initialConnId) }
    var selectedDatabase by remember(initialDatabase) { mutableStateOf(initialDatabase) }
    val databasesMap = remember { mutableStateMapOf<Int, List<String>>() }
    var objectList by remember { mutableStateOf(DbObjectListState(emptyList(), emptyList())) }
    val tableSchemasMap = remember { mutableStateMapOf<String, List<GridColumnSchema>>() }

    // 428 Login modal state
    var activeLoginPrompt by remember { mutableStateOf<DbLoginPromptInfo?>(null) }
    var activeLoginDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginErrorDetails by remember { mutableStateOf<String?>(null) }

    val loginCoordinator = remember {
        DbLoginCoordinator { prompt ->
            val def = CompletableDeferred<Boolean>()
            loginError = null
            loginErrorDetails = null
            activeLoginPrompt = prompt
            activeLoginDeferred = def
            def.await()
        }
    }

    val selectedConn = remember(connections, selectedConnId) {
        connections.firstOrNull { it.id == selectedConnId }
    }
    val effectiveConnIdStr = if (!filePath.isNullOrBlank()) "file" else (selectedConnId?.toString() ?: "")
    val effectiveConnName = if (!filePath.isNullOrBlank()) {
        filePath.substringAfterLast('/').substringAfterLast('\\').ifEmpty { filePath }
    } else {
        selectedConn?.name ?: initialConnName ?: "Database"
    }
    val effectiveDbType = selectedConn?.type ?: initialDbType
    val effectiveReadonly = if (!filePath.isNullOrBlank()) false else (selectedConn?.readonly == 1)

    LaunchedEffect(api) {
        val client = api ?: return@LaunchedEffect
        runCatching { client.dbConnections() }.getOrNull()?.let { arr ->
            val parsed = parseTreeConnections(arr)
            connections = parsed
            if (selectedConnId == null && filePath.isNullOrBlank() && parsed.isNotEmpty()) {
                selectedConnId = parsed.first().id
            }
        }
    }

    LaunchedEffect(api, selectedConnId, selectedDatabase, filePath) {
        val client = api ?: return@LaunchedEffect
        val cid = selectedConnId
        if (filePath.isNullOrBlank() && cid != null) {
            val conn = connections.firstOrNull { it.id == cid }
            if (conn != null && !conn.isSingleDatabase && !databasesMap.containsKey(cid)) {
                runCatching { client.dbListDatabases(cid, loginCoordinator) }.getOrNull()?.let { dbs ->
                    databasesMap[cid] = dbs
                }
            }
        }
        if (effectiveConnIdStr.isNotEmpty()) {
            runCatching {
                client.dbListObjects(
                    connectionId = effectiveConnIdStr,
                    database = selectedDatabase,
                    path = filePath,
                    project = fileProject,
                    loginCoordinator = loginCoordinator,
                )
            }.getOrNull()?.let { objsJson ->
                objectList = withContext(Dispatchers.Default) { parseDbObjectList(objsJson) }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val prompt = activeLoginPrompt
        if (prompt != null) {
            DbLoginDialogContent(
                prompt = prompt,
                errorMessage = loginError,
                errorDetails = loginErrorDetails,
                onSubmit = { user, pass ->
                    val client = api ?: return@DbLoginDialogContent
                    val cid = prompt.connectionId ?: return@DbLoginDialogContent
                    scope.launch {
                        runCatching {
                            client.dbLoginConnection(cid, password = pass, user = user)
                        }.onSuccess {
                            activeLoginPrompt = null
                            activeLoginDeferred?.complete(true)
                        }.onFailure { err ->
                            loginError = err.message ?: "Login failed"
                            loginErrorDetails = (err as? DbLoginFailedException)?.details?.takeIf { it.isNotBlank() }
                        }
                    }
                },
                onCancel = {
                    activeLoginPrompt = null
                    activeLoginDeferred?.complete(false)
                },
            )
        }

        DatabaseQueryTabContent(
            tabTitle = tab.title,
            initialSql = initialSql,
            initialOpenedSql = initialOpenedSql,
            initialSavedFile = savedFileOf(tab.metadata),
            runOnOpen = runOnOpen,
            isFileTarget = !filePath.isNullOrBlank(),
            connectionId = selectedConnId,
            connectionName = effectiveConnName,
            dbType = effectiveDbType,
            database = selectedDatabase,
            readonlyConnection = effectiveReadonly,
            connections = connections,
            databases = selectedConnId?.let { databasesMap[it] }.orEmpty(),
            schemas = objectList.schemas,
            objects = objectList.objects,
            columnsByTableKey = tableSchemasMap,
            defaultProjectPath = model.projects.firstOrNull { it.name == model.selectedProject }?.path.orEmpty(),
            projectName = fileProject,
            onClearRunOnOpen = {
                model.patchTabMetadata(tab.id, "runOnOpen" to null)
            },
            onSqlChanged = { nextSql ->
                model.patchTabMetadata(tab.id, "currentSql" to JsonPrimitive(nextSql))
            },
            onSelectConnection = { nextConnId ->
                selectedConnId = nextConnId
                val nextConn = connections.firstOrNull { it.id == nextConnId }
                selectedDatabase = nextConn?.ownDatabase
                model.patchTabMetadata(
                    tab.id,
                    "connectionId" to JsonPrimitive(nextConnId),
                    "connectionName" to nextConn?.name?.let(::JsonPrimitive),
                    "dbType" to nextConn?.type?.let(::JsonPrimitive),
                    "database" to nextConn?.ownDatabase?.let(::JsonPrimitive),
                )
            },
            onSelectDatabase = { nextDb ->
                selectedDatabase = nextDb
                model.patchTabMetadata(tab.id, "database" to nextDb?.let(::JsonPrimitive))
            },
            onExecuteQueryStream = { spec, runId, onCall, onEvent ->
                val client = api ?: error("Not connected to server")
                if (effectiveConnIdStr.isEmpty()) error("Choose a database connection first.")
                val body = queryScriptRequestJson(spec, runId)
                client.dbQueryScriptStream(
                    connectionId = effectiveConnIdStr,
                    body = body,
                    database = selectedDatabase,
                    path = filePath,
                    project = fileProject,
                    loginCoordinator = loginCoordinator,
                    onCallCreated = onCall,
                    onEventLine = { line ->
                        val ev = withContext(Dispatchers.Default) { parseQueryScriptEventJson(line) }
                        if (ev != null) {
                            withContext(Dispatchers.Main.immediate) {
                                onEvent(ev)
                            }
                        }
                    },
                )
            },
            onCancelQueryRun = { runId ->
                val client = api ?: return@DatabaseQueryTabContent false
                if (effectiveConnIdStr.isEmpty()) return@DatabaseQueryTabContent false
                runCatching {
                    client.dbQueryCancel(
                        connectionId = effectiveConnIdStr,
                        runId = runId,
                        database = selectedDatabase,
                        path = filePath,
                        project = fileProject,
                        loginCoordinator = loginCoordinator,
                    )
                }.getOrDefault(false)
            },
            onLoadTableSchema = { table, schema ->
                val client = api ?: return@DatabaseQueryTabContent emptyList()
                if (effectiveConnIdStr.isEmpty()) return@DatabaseQueryTabContent emptyList()
                val key = "${schema.orEmpty()}.$table"
                tableSchemasMap[key]?.let { return@DatabaseQueryTabContent it }
                val arr = runCatching {
                    client.dbTableSchema(
                        connectionId = effectiveConnIdStr,
                        table = table,
                        schema = schema,
                        database = selectedDatabase,
                        path = filePath,
                        project = fileProject,
                        loginCoordinator = loginCoordinator,
                    )
                }.getOrNull() ?: return@DatabaseQueryTabContent emptyList()
                val parsed = withContext(Dispatchers.Default) { parseGridColumnsSchema(arr) }
                tableSchemasMap[key] = parsed
                tableSchemasMap[table] = parsed
                parsed
            },
            onPreviewResultChangeset = { table, schema, payload ->
                val client = api ?: error("Not connected")
                val body = buildChangesetRequestJson(table = table, schema = schema, payload = payload)
                val obj = client.dbChangesetPreview(
                    connectionId = effectiveConnIdStr,
                    body = body,
                    database = selectedDatabase,
                    path = filePath,
                    project = fileProject,
                    loginCoordinator = loginCoordinator,
                )
                withContext(Dispatchers.Default) { parseChangesetPreview(obj) }
            },
            onApplyResultChangeset = { table, schema, payload, cascade ->
                val client = api ?: error("Not connected")
                val body = buildChangesetRequestJson(table = table, schema = schema, payload = payload, cascade = cascade)
                val obj = client.dbChangesetApply(
                    connectionId = effectiveConnIdStr,
                    body = body,
                    database = selectedDatabase,
                    path = filePath,
                    project = fileProject,
                    loginCoordinator = loginCoordinator,
                )
                withContext(Dispatchers.Default) { parseChangesetApplyResult(obj) }
            },
            onLoadHistory = { search, offset ->
                val client = api ?: return@DatabaseQueryTabContent QueryHistoryResponse(emptyList())
                val cid = selectedConnId ?: return@DatabaseQueryTabContent QueryHistoryResponse(emptyList())
                val json = runCatching {
                    client.dbQueryHistory(
                        connectionId = cid.toString(),
                        search = search,
                        offset = offset,
                        database = selectedDatabase,
                        loginCoordinator = loginCoordinator,
                    )
                }.getOrNull() ?: return@DatabaseQueryTabContent QueryHistoryResponse(emptyList())
                withContext(Dispatchers.Default) { parseQueryHistoryResponse(json) }
            },
            onListDirectoryEntries = { dirPath ->
                val client = api ?: return@DatabaseQueryTabContent emptyList()
                runCatching {
                    client.fsList(dirPath).map { p ->
                        p.substringAfterLast('/').substringAfterLast('\\') to "file"
                    }
                }.getOrDefault(emptyList())
            },
            onSaveSqlFile = { targetFile, sqlContent ->
                val client = api ?: error("Not connected")
                client.writeFile(targetFile.projectName, targetFile.path, sqlContent)
                val (newTitle, metaPatch) = savedQueryTab(targetFile, sqlContent)
                model.updateTabTitleAndMetadata(
                    tabId = tab.id,
                    title = newTitle,
                    *metaPatch.entries.map { it.key to it.value }.toTypedArray(),
                )
            },
            onOpenNewQueryTab = { sqlText ->
                val allTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
                val nextNum = nextQueryNumber(allTabs)
                model.openWorkspaceTab(
                    type = "db-query",
                    title = "Query $nextNum",
                    metadata = queryTabMetadata(
                        sql = sqlText,
                        queryNumber = nextNum,
                        connectionId = selectedConnId,
                        connectionName = effectiveConnName,
                        dbType = effectiveDbType,
                        database = selectedDatabase,
                        dbFilePath = filePath,
                        dbFileProject = fileProject,
                    ),
                )
            },
            onInstallMissingDriver = { driverId ->
                val client = api ?: return@DatabaseQueryTabContent
                scope.launch {
                    runCatching { client.dbInstallMissingDriver(driverId) }
                }
            },
            onUnsavedResultRowsChanged = { count ->
                setUnsavedGridRows(tab.id, count, slot = "query-results")
                model.patchTabMetadata(tab.id, "unsavedGridRows" to if (count > 0) JsonPrimitive(count) else null)
            },
            // PPM signs in with a password; with none set, the server asks for nothing (`!!getAuthToken()`).
            writeOncePasswordRequired = !api?.token.isNullOrEmpty(),
        )
    }
}

// ── 9e.1–9e.6 Reusable & Testable Query Tab Content (`query-tab.tsx`) ────────

@Composable
internal fun DatabaseQueryTabContent(
    tabTitle: String = "Query 1",
    initialSql: String = "",
    initialOpenedSql: String = initialSql,
    initialSavedFile: SavedQueryFile? = null,
    runOnOpen: Boolean = false,
    isFileTarget: Boolean = false,
    connectionId: Int? = 1,
    connectionName: String = "Postgres",
    dbType: String = "postgres",
    database: String? = null,
    readonlyConnection: Boolean = false,
    writeOncePasswordRequired: Boolean = true,
    connections: List<TreeConnection> = emptyList(),
    databases: List<String> = emptyList(),
    schemas: List<String> = emptyList(),
    objects: List<DbObjectItem> = emptyList(),
    columnsByTableKey: Map<String, List<GridColumnSchema>> = emptyMap(),
    defaultProjectPath: String = "/workspace",
    projectName: String? = null,
    onClearRunOnOpen: () -> Unit = {},
    onSqlChanged: (String) -> Unit = {},
    onSelectConnection: (Int) -> Unit = {},
    onSelectDatabase: (String?) -> Unit = {},
    onExecuteQueryStream: suspend (
        spec: QueryRunSpec,
        runId: String,
        onCallCreated: (Call) -> Unit,
        onEvent: suspend (QueryScriptEvent) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
    onCancelQueryRun: suspend (runId: String) -> Boolean = { true },
    onLoadTableSchema: suspend (table: String, schema: String?) -> List<GridColumnSchema> = { _, _ -> emptyList() },
    onPreviewResultChangeset: suspend (table: String, schema: String?, payload: GridChangesPayload) -> ChangesetPreview = { _, _, payload ->
        ChangesetPreview(
            statementCount = payload.totalOperations,
            script = "-- ${payload.totalOperations} statement(s)",
            references = emptyList(),
        )
    },
    onApplyResultChangeset: suspend (table: String, schema: String?, payload: GridChangesPayload, cascade: List<TableRef>) -> ChangesetApplyResult = { _, _, payload, cascade ->
        ChangesetApplyResult(
            inserted = payload.inserts.size,
            updated = payload.updates.size,
            deleted = payload.deletes.size,
            cascaded = cascade.size,
            executionTimeMs = 1,
        )
    },
    onLoadHistory: suspend (search: String, offset: Int) -> QueryHistoryResponse = { _, _ -> QueryHistoryResponse(emptyList()) },
    onListDirectoryEntries: suspend (dirPath: String) -> List<Pair<String, String>> = { emptyList() },
    onSaveSqlFile: suspend (SavedQueryFile, String) -> Unit = { _, _ -> },
    onOpenNewQueryTab: (String) -> Unit = {},
    onInstallMissingDriver: (String) -> Unit = {},
    onUnsavedResultRowsChanged: (Int) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember(context) {
        context.getSharedPreferences(QUERY_PREFS_FILE, Context.MODE_PRIVATE)
    }
    var continueOnError by remember {
        mutableStateOf(prefs.getBoolean(PREF_CONTINUE_ON_ERROR, false))
    }
    var rowLimit by remember {
        mutableIntStateOf(queryRowLimit(prefs.getInt(PREF_ROW_LIMIT, DEFAULT_QUERY_ROW_LIMIT)))
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = initialSql, selection = TextRange(initialSql.length)))
    }
    var openedSql by remember { mutableStateOf(initialOpenedSql) }
    var savedFile by remember { mutableStateOf(initialSavedFile) }
    var currentTabTitle by remember(tabTitle) { mutableStateOf(tabTitle) }

    val sql = textFieldValue.text
    val cursorOffset = textFieldValue.selection.start.coerceIn(0, sql.length)
    val selectionText = remember(textFieldValue) {
        val sel = textFieldValue.selection
        if (!sel.collapsed) {
            val s = minOf(sel.start, sel.end).coerceIn(0, sql.length)
            val e = maxOf(sel.start, sel.end).coerceIn(0, sql.length)
            sql.substring(s, e)
        } else {
            ""
        }
    }
    val selectionStartLine = remember(textFieldValue) {
        val sel = textFieldValue.selection
        if (!sel.collapsed) {
            lineOfPosition(sql, minOf(sel.start, sel.end))
        } else {
            null
        }
    }

    val isDirty = sql != openedSql

    fun updateSqlText(nextText: String, nextCursor: Int = nextText.length) {
        val safeCursor = nextCursor.coerceIn(0, nextText.length)
        textFieldValue = TextFieldValue(text = nextText, selection = TextRange(safeCursor))
        onSqlChanged(nextText)
    }

    val statementsWithLines = remember(sql, dbType) {
        splitSqlStatementsWithLines(sql, dbType)
    }
    val activeStmt = remember(statementsWithLines, cursorOffset, sql) {
        statementAt(statementsWithLines, cursorOffset, sql)
    }
    val cursorLine = remember(sql, cursorOffset) {
        lineOfPosition(sql, cursorOffset)
    }

    val defaultSchema = remember(dbType, schemas, objects) {
        if (dbType == "postgres") initialSchema(schemas, objects, null) else null
    }

    // Schema-aware completions at cursor
    val completions = remember(sql, cursorOffset, dbType, defaultSchema, schemas, objects, columnsByTableKey.toMap()) {
        computeSqlCompletions(
            sql = sql,
            cursorOffset = cursorOffset,
            dialect = dbType,
            defaultSchema = defaultSchema,
            schemas = schemas,
            objects = objects,
            columnsByTableKey = columnsByTableKey,
        ).take(12)
    }

    // Query execution state
    var currentRun by remember { mutableStateOf<QueryRun?>(null) }
    var activeResultTab by remember { mutableStateOf(MESSAGES_TAB) }
    var lastRunSpec by remember { mutableStateOf<QueryRunSpec?>(null) }
    var activeRunJob by remember { mutableStateOf<Job?>(null) }
    var activeHttpCall by remember { mutableStateOf<Call?>(null) }
    var missingDriverId by remember { mutableStateOf<String?>(null) }
    var statusBannerMessage by remember { mutableStateOf<String?>(null) }

    val isRunning = currentRun != null && currentRun?.done == false

    // Result editability & dirty tracking per result tab
    val resultTabSchemas = remember { mutableStateMapOf<String, List<GridColumnSchema>>() }
    val dirtyResultTabs = remember { mutableStateMapOf<String, Boolean>() }
    val unsavedResultTabCount = dirtyResultTabs.values.count { it }
    val hasUnsavedResultEdits = unsavedResultTabCount > 0
    LaunchedEffect(unsavedResultTabCount) {
        onUnsavedResultRowsChanged(unsavedResultTabCount)
    }

    var pendingRunConfirmationSpec by remember { mutableStateOf<QueryRunSpec?>(null) }
    var showWriteOnceDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showConnMenu by remember { mutableStateOf(false) }
    var showDbMenu by remember { mutableStateOf(false) }
    var showLimitMenu by remember { mutableStateOf(false) }
    var showHistoryPanel by remember { mutableStateOf(false) }
    var showPhoneOptionsSheet by remember { mutableStateOf(false) }

    // Cancel active stream when tab is disposed
    DisposableEffect(Unit) {
        onDispose {
            activeHttpCall?.cancel()
            activeRunJob?.cancel()
        }
    }

    fun executeRunNow(spec: QueryRunSpec) {
        activeRunJob?.cancel()
        activeHttpCall?.cancel()
        dirtyResultTabs.clear()
        missingDriverId = null
        statusBannerMessage = null
        lastRunSpec = spec

        val runId = "run-${webRandomId()}"
        val kind = if (spec.explain) "explain" else "query"
        var runState = startQueryRun(
            runId = runId,
            lineOffset = spec.lineOffset,
            startedAt = System.currentTimeMillis(),
            kind = kind,
        )
        currentRun = runState
        activeResultTab = MESSAGES_TAB

        activeRunJob = scope.launch {
            var sawDone = false
            try {
                onExecuteQueryStream(
                    spec,
                    runId,
                    { call -> activeHttpCall = call },
                    { ev ->
                        val prevResultCount = runState.resultTabs.size
                        runState = applyQueryEvents(runState, listOf(TimedQueryEvent(ev, System.currentTimeMillis())))
                        currentRun = runState
                        if (prevResultCount == 0 && runState.resultTabs.isNotEmpty() && activeResultTab == MESSAGES_TAB) {
                            activeResultTab = runState.resultTabs.first().key
                        }
                        if (ev is QueryScriptEvent.Done) {
                            sawDone = true
                        }
                    },
                )
                if (!sawDone && !runState.done) {
                    runState = failQueryRun(runState, "The connection to the server was lost before the run ended")
                    currentRun = runState
                }
            } catch (driverErr: DbDriverMissingException) {
                missingDriverId = driverErr.driverId
                runState = failQueryRun(runState, driverErr.message ?: "Database driver is not installed.")
                currentRun = runState
            } catch (err: Throwable) {
                if (!runState.done) {
                    runState = failQueryRun(runState, err.message ?: "Query failed")
                    currentRun = runState
                }
            } finally {
                activeHttpCall = null
            }
        }
    }

    fun requestRun(spec: QueryRunSpec) {
        if (isRunning) return
        if (hasUnsavedResultEdits) {
            pendingRunConfirmationSpec = spec
            return
        }
        executeRunNow(spec)
    }

    fun stopCurrentRun() {
        val run = currentRun ?: return
        if (run.done) return
        scope.launch {
            val stopped = runCatching { onCancelQueryRun(run.runId) }.getOrDefault(false)
            if (!stopped) {
                delay(STOP_GRACE_MS)
            }
            activeHttpCall?.cancel()
            activeRunJob?.cancel()
            val cur = currentRun
            if (cur != null && !cur.done) {
                currentRun = failQueryRun(cur, "Stopped by user")
            }
        }
    }

    fun formatEditorSql() {
        runCatching {
            val formatted = formatSqlScript(sql, dbType)
            val nextCursor = cursorAfterReformat(sql, cursorOffset, formatted, dbType)
            updateSqlText(formatted, nextCursor)
            statusBannerMessage = null
        }.onFailure { err ->
            statusBannerMessage = err.message ?: "Could not format SQL"
        }
    }

    fun saveSqlDirectlyOrOpenDialog() {
        val existing = savedFile
        if (existing != null) {
            scope.launch {
                runCatching {
                    onSaveSqlFile(existing, sql)
                    openedSql = sql
                    statusBannerMessage = "Saved ${existing.path}"
                }.onFailure { err ->
                    statusBannerMessage = err.message ?: "Could not save file"
                }
            }
        } else {
            showSaveAsDialog = true
        }
    }

    // Auto-run once if opened with `runOnOpen = true`
    LaunchedEffect(runOnOpen) {
        if (runOnOpen) {
            onClearRunOnOpen()
            val toRun = scriptRun(sql)
            if (toRun != null) {
                executeRunNow(
                    QueryRunSpec(
                        sql = toRun.sql,
                        lineOffset = toRun.lineOffset,
                        maxRows = rowLimit,
                        continueOnError = continueOnError,
                    ),
                )
            }
        }
    }

    // Load table schemas for editable query results when results arrive
    LaunchedEffect(currentRun?.results, readonlyConnection, currentRun?.kind) {
        val run = currentRun ?: return@LaunchedEffect
        if (readonlyConnection || run.kind == "explain") return@LaunchedEffect
        for (res in run.results) {
            val extracted = extractQueryTable(res.sql, defaultSchema) ?: continue
            val key = "${extracted.schema.orEmpty()}.${extracted.table}"
            if (!resultTabSchemas.containsKey(key)) {
                val cols = runCatching { onLoadTableSchema(extracted.table, extracted.schema) }.getOrDefault(emptyList())
                if (cols.isNotEmpty()) {
                    resultTabSchemas[key] = cols
                }
            }
        }
    }

    fun jumpToEditorLine(targetLine: Int) {
        val lines = sql.split('\n')
        val clamped = targetLine.coerceIn(1, maxOf(1, lines.size))
        var offset = 0
        for (i in 0 until (clamped - 1)) {
            offset += lines[i].length + 1
        }
        textFieldValue = TextFieldValue(text = sql, selection = TextRange(offset.coerceIn(0, sql.length)))
    }

    fun applyCompletion(insertText: String) {
        val before = sql.substring(0, cursorOffset)
        val after = sql.substring(cursorOffset)
        val tokenStart = before.indexOfLast { !it.isLetterOrDigit() && it != '_' && it != '"' && it != '`' } + 1
        val replaced = before.substring(0, tokenStart) + insertText + after
        val nextCursor = tokenStart + insertText.length
        updateSqlText(replaced, nextCursor)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = ev.isCtrlPressed || ev.isMetaPressed
                when {
                    ev.key == Key.F5 -> {
                        scriptRun(sql)?.let { r ->
                            requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError))
                        }
                        true
                    }
                    mod && ev.key == Key.Enter -> {
                        selectionOrStatementRun(sql, selectionText, selectionStartLine, cursorOffset, dbType)?.let { r ->
                            requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError))
                        }
                        true
                    }
                    ev.isShiftPressed && ev.isAltPressed && ev.key == Key.F -> {
                        formatEditorSql()
                        true
                    }
                    mod && ev.key == Key.S -> {
                        saveSqlDirectlyOrOpenDialog()
                        true
                    }
                    else -> false
                }
            }
            .testTag("db-query-tab"),
    ) {
        val widthDp = maxWidth.value
        val showContinueLabel = widthDp >= 1120f
        val showLimitLabel = widthDp >= 1020f
        val showHistoryLabel = widthDp >= 720f
        val dockHistoryPanel = !mobile && widthDp >= 860f

        Column(Modifier.fillMaxSize()) {
            // 1. Top Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dirty dot + title badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isDirty) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(tokens.warning.color)
                                .testTag("db-query-dirty-dot"),
                        )
                    }
                    BasicText(
                        currentTabTitle,
                        modifier = Modifier.testTag("db-query-tab-title"),
                        style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                    )
                }

                // Target Connection / File Picker
                if (isFileTarget) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tokens.panel2.color)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("db-query-file-badge"),
                    ) {
                        BasicText(
                            "SQLite · $connectionName",
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                    }
                } else {
                    SettingsButton(
                        label = "$connectionName ▾",
                        modifier = Modifier.testTag("db-query-target-picker"),
                        onClick = { showConnMenu = !showConnMenu },
                    )
                    if (databases.isNotEmpty() || !database.isNullOrBlank()) {
                        SettingsButton(
                            label = "${database ?: "default"} ▾",
                            modifier = Modifier.testTag("db-query-database-picker"),
                            onClick = { showDbMenu = !showDbMenu },
                        )
                    }
                }

                if (!mobile) {
                    if (isRunning) {
                        SettingsButton(
                            label = "■ Stop (${stopTitle(dbType).substringBefore(':')})",
                            primary = true,
                            modifier = Modifier.testTag("db-query-stop-btn"),
                            onClick = { stopCurrentRun() },
                        )
                    } else {
                        SettingsButton(
                            label = "▶ Run",
                            primary = true,
                            modifier = Modifier.testTag("db-query-run-btn"),
                            onClick = {
                                scriptRun(sql)?.let { r ->
                                    requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError))
                                }
                            },
                        )
                        SettingsButton(
                            label = if (selectionText.isNotBlank()) "▶ Run selection" else "▶ Run statement",
                            modifier = Modifier.testTag("db-query-run-stmt-btn"),
                            onClick = {
                                selectionOrStatementRun(sql, selectionText, selectionStartLine, cursorOffset, dbType)?.let { r ->
                                    requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError))
                                }
                            },
                        )
                    }

                    SettingsButton(
                        label = "Explain",
                        modifier = Modifier.testTag("db-query-explain-btn"),
                        onClick = {
                            selectionOrStatementRun(sql, selectionText, selectionStartLine, cursorOffset, dbType)?.let { r ->
                                requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError, explain = true))
                            }
                        },
                    )
                    SettingsButton(
                        label = "Format",
                        modifier = Modifier.testTag("db-query-format-btn"),
                        onClick = { formatEditorSql() },
                    )
                    SettingsButton(
                        label = "Save",
                        modifier = Modifier.testTag("db-query-save-btn"),
                        onClick = { saveSqlDirectlyOrOpenDialog() },
                    )
                    SettingsButton(
                        label = "Save As…",
                        modifier = Modifier.testTag("db-query-save-as-btn"),
                        onClick = { showSaveAsDialog = true },
                    )
                    SettingsButton(
                        label = if (showContinueLabel) {
                            "${if (continueOnError) "☑" else "☐"} Continue on error"
                        } else {
                            "${if (continueOnError) "☑" else "☐"} Cont."
                        },
                        primary = continueOnError,
                        modifier = Modifier.testTag("db-query-continue-on-error-btn"),
                        onClick = {
                            continueOnError = !continueOnError
                            prefs.edit().putBoolean(PREF_CONTINUE_ON_ERROR, continueOnError).apply()
                        },
                    )
                    SettingsButton(
                        label = if (showLimitLabel) "Limit: $rowLimit ▾" else "$rowLimit ▾",
                        modifier = Modifier.testTag("db-query-limit-btn"),
                        onClick = { showLimitMenu = !showLimitMenu },
                    )
                    if (!isFileTarget) {
                        SettingsButton(
                            label = if (showHistoryLabel) "History" else "Hist",
                            primary = showHistoryPanel,
                            modifier = Modifier.testTag("db-query-history-btn"),
                            onClick = { showHistoryPanel = !showHistoryPanel },
                        )
                    }
                } else {
                    // Phone compact top bar actions: Format, Save, History
                    SettingsButton(
                        label = "Format",
                        modifier = Modifier.testTag("db-query-format-btn"),
                        onClick = { formatEditorSql() },
                    )
                    SettingsButton(
                        label = "Save",
                        modifier = Modifier.testTag("db-query-save-btn"),
                        onClick = { saveSqlDirectlyOrOpenDialog() },
                    )
                    if (!isFileTarget) {
                        SettingsButton(
                            label = "History",
                            primary = showHistoryPanel,
                            modifier = Modifier.testTag("db-query-history-btn"),
                            onClick = { showHistoryPanel = !showHistoryPanel },
                        )
                    }
                }
            }

            // Connection / Database / Limit dropdown strips
            if (showConnMenu && connections.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState())
                        .testTag("db-query-conn-menu"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (c in connections) {
                        SettingsButton(
                            label = c.name + (if (c.readonly == 1) " (RO)" else ""),
                            primary = c.id == connectionId,
                            modifier = Modifier.testTag("db-query-conn-item-${c.id}"),
                            onClick = {
                                showConnMenu = false
                                onSelectConnection(c.id)
                            },
                        )
                    }
                }
            }

            if (showDbMenu && databases.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState())
                        .testTag("db-query-db-menu"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (dbName in databases) {
                        SettingsButton(
                            label = dbName,
                            primary = dbName == database,
                            modifier = Modifier.testTag("db-query-db-item-$dbName"),
                            onClick = {
                                showDbMenu = false
                                onSelectDatabase(dbName)
                            },
                        )
                    }
                }
            }

            if (showLimitMenu) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState())
                        .testTag("db-query-limit-menu"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (lim in QUERY_ROW_LIMITS) {
                        SettingsButton(
                            label = "$lim rows",
                            primary = rowLimit == lim,
                            modifier = Modifier.testTag("db-query-limit-option-$lim"),
                            onClick = {
                                rowLimit = lim
                                prefs.edit().putInt(PREF_ROW_LIMIT, lim).apply()
                                showLimitMenu = false
                            },
                        )
                    }
                }
            }

            // Missing driver or status banner
            if (missingDriverId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.15).color)
                        .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("db-query-driver-missing-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Driver $missingDriverId is not installed.",
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.error.color),
                    )
                    SettingsButton(
                        label = "Install Driver",
                        primary = true,
                        modifier = dbPhoneTouchTarget().testTag("db-query-install-driver-btn"),
                        onClick = { onInstallMissingDriver(missingDriverId!!) },
                    )
                }
            }

            if (!statusBannerMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("db-query-status-banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        statusBannerMessage!!,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                        modifier = Modifier.weight(1f),
                    )
                    SettingsButton(
                        label = "Dismiss",
                        onClick = { statusBannerMessage = null },
                    )
                }
            }

            // Dialogs (DiscardResultEdits, SaveAs); WriteOnce overlays the whole tab, below.

            val pendingSpec = pendingRunConfirmationSpec
            if (pendingSpec != null) {
                DiscardResultEditsDialog(
                    onConfirmDiscardAndRun = {
                        pendingRunConfirmationSpec = null
                        executeRunNow(pendingSpec)
                    },
                    onCancel = { pendingRunConfirmationSpec = null },
                )
            }

            if (showSaveAsDialog) {
                QuerySaveAsDialog(
                    initialFolder = savedFile?.path?.substringBeforeLast('/')?.ifEmpty { defaultProjectPath } ?: defaultProjectPath,
                    initialFileName = savedFile?.path?.substringAfterLast('/') ?: defaultQueryFileName(currentTabTitle),
                    projectName = projectName,
                    onListDirectoryEntries = onListDirectoryEntries,
                    onConfirmSave = { file ->
                        showSaveAsDialog = false
                        scope.launch {
                            runCatching {
                                onSaveSqlFile(file, sql)
                                savedFile = file
                                openedSql = sql
                                currentTabTitle = file.path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { file.path }
                                statusBannerMessage = "Saved ${file.path}"
                            }.onFailure { err ->
                                statusBannerMessage = err.message ?: "Could not save file"
                            }
                        }
                    },
                    onCancel = { showSaveAsDialog = false },
                )
            }

            // Main Body: Editor + Results + Optional Docked/Floating History Panel
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // SQL Editor Section (~38% height on phone per §9e.6, ~42% on tablet)
                    val editorWeight = if (mobile) 0.38f else 0.42f
                    val resultsWeight = if (mobile) 0.62f else 0.58f

                    Column(
                        modifier = Modifier
                            .weight(editorWeight)
                            .fillMaxWidth()
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .testTag("db-query-editor-section"),
                    ) {
                        val lines = remember(sql) { sql.split('\n').ifEmpty { listOf("") } }
                        val editorScroll = rememberScrollState()

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(editorScroll),
                        ) {
                            // Line Number Gutter + Active Statement Highlight + Gutter Play Button + Error Line Highlight
                            Column(
                                modifier = Modifier
                                    .width(46.dp)
                                    .background(tokens.panel.color)
                                    .cssBorder(tokens.border.color, sides = CssSides.End)
                                    .padding(vertical = 8.dp)
                                    .testTag("db-query-line-gutter"),
                            ) {
                                for (lineIdx in lines.indices) {
                                    val lineNum = lineIdx + 1
                                    val inActiveStmt = activeStmt != null && lineNum in activeStmt.startLine..activeStmt.endLine
                                    val isStmtStart = activeStmt != null && lineNum == activeStmt.startLine
                                    val isErrorLine = currentRun?.errorLine == lineNum

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(18.dp)
                                            .background(
                                                when {
                                                    isErrorLine -> tokens.error.opacity(0.22).color
                                                    inActiveStmt -> tokens.accent.opacity(0.14).color
                                                    else -> Color.Transparent
                                                },
                                            )
                                            .padding(horizontal = 4.dp)
                                            .let { m ->
                                                if (inActiveStmt) m.testTag("db-query-stmt-gutter-line-$lineNum") else m
                                            },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (isStmtStart) {
                                            Box(
                                                modifier = Modifier
                                                    .clickable {
                                                        val st = activeStmt
                                                        if (st != null && st.text.isNotBlank()) {
                                                            requestRun(
                                                                QueryRunSpec(
                                                                    sql = st.text,
                                                                    lineOffset = maxOf(0, st.startLine - 1),
                                                                    maxRows = rowLimit,
                                                                    continueOnError = continueOnError,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                    .testTag("db-query-gutter-run-line-$lineNum"),
                                            ) {
                                                BasicText(
                                                    "▶",
                                                    style = ppmText(10f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.accent.color),
                                                )
                                            }
                                        } else {
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        BasicText(
                                            lineNum.toString(),
                                            modifier = if (isErrorLine) Modifier.testTag("db-query-error-line-$lineNum") else Modifier,
                                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(
                                                color = when {
                                                    isErrorLine -> tokens.error.color
                                                    inActiveStmt -> tokens.accent.color
                                                    else -> tokens.text3.color
                                                },
                                            ),
                                        )
                                    }
                                }
                            }

                            // Monospace SQL TextField
                            BasicTextField(
                                value = textFieldValue,
                                onValueChange = { nextVal ->
                                    val textChanged = nextVal.text != textFieldValue.text
                                    textFieldValue = nextVal
                                    if (textChanged) {
                                        onSqlChanged(nextVal.text)
                                    }
                                },
                                textStyle = ppmText(12f, kind = FontKind.MONO, lineHeight = 18f).copy(color = tokens.text.color),
                                cursorBrush = SolidColor(tokens.accent.color),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(8.dp)
                                    .testTag("db-query-sql-input"),
                            )
                        }

                        // Schema-aware SQL Completion Strip & Cursor Line Indicator
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Top)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState())
                                    .testTag("db-query-completions-bar"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                for (sug in completions) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tokens.panel2.color)
                                            .clickable { applyCompletion(sug.insertText) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                            .testTag("db-query-completion-item-${sug.label}"),
                                    ) {
                                        BasicText(
                                            "${sug.label} · ${sug.detail}",
                                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text2.color),
                                        )
                                    }
                                }
                            }
                            BasicText(
                                "Ln $cursorLine",
                                modifier = Modifier.padding(start = 8.dp).testTag("db-query-cursor-indicator"),
                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                            )
                        }
                    }

                    // 2. Results Area (Messages + Result 1..N / Plan 1..N)
                    val shownTab = shownResultTab(activeResultTab, currentRun)
                    val readonlyRefused = refusedAsReadonly(currentRun) && !isFileTarget

                    Column(
                        modifier = Modifier
                            .weight(resultsWeight)
                            .fillMaxWidth()
                            .background(tokens.bgSolid.color)
                            .testTag("db-query-results-section"),
                    ) {
                        // Sub-Tab Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .horizontalScroll(rememberScrollState())
                                .testTag("db-query-result-tabs"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val msgCount = currentRun?.messages?.size ?: 0
                            val hasErrorMsg = currentRun?.messages?.any { it.level == "error" } == true
                            SettingsButton(
                                label = "Messages ($msgCount)${if (hasErrorMsg) " !" else ""}",
                                primary = shownTab == MESSAGES_TAB,
                                modifier = Modifier.testTag("db-query-tab-messages"),
                                onClick = { activeResultTab = MESSAGES_TAB },
                            )

                            for (rt in currentRun?.resultTabs.orEmpty()) {
                                val pair = resultOfTab(currentRun, rt.key)
                                val rowCnt = pair?.second?.rows?.size ?: 0
                                val isDirtyTab = dirtyResultTabs[rt.key] == true
                                SettingsButton(
                                    label = "${rt.label} ($rowCnt)${if (isDirtyTab) " •" else ""}",
                                    primary = shownTab == rt.key,
                                    modifier = Modifier.testTag("db-query-tab-${rt.key}"),
                                    onClick = { activeResultTab = rt.key },
                                )
                            }

                            if (isRunning) {
                                val runIdx = (currentRun?.runningIndex ?: 0) + 1
                                val totalStmts = maxOf(1, currentRun?.statements?.size ?: 1)
                                BasicText(
                                    "Running statement $runIdx of $totalStmts…",
                                    modifier = Modifier.testTag("db-query-running-indicator"),
                                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.accent.color),
                                )
                            }

                        }

                        // `offerWriteOnce`: the run a readonly connection refused, offered once more with write access.
                        if (readonlyRefused && !isRunning) {
                            WriteOnceOfferBar(
                                connectionName = connectionName,
                                onRunWithWriteAccess = { showWriteOnceDialog = true },
                            )
                        }

                        // Active Result Sub-Tab Body
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (shownTab == MESSAGES_TAB) {
                                QueryMessagesPanel(
                                    run = currentRun,
                                    mobile = mobile,
                                    onJumpToLine = ::jumpToEditorLine,
                                )
                            } else {
                                val pair = resultOfTab(currentRun, shownTab)
                                if (pair != null) {
                                    val (stmtResult, resultSet) = pair
                                    val extracted = remember(stmtResult.sql, defaultSchema) {
                                        extractQueryTable(stmtResult.sql, defaultSchema)
                                    }
                                    val tableCols = extracted?.let {
                                        resultTabSchemas["${it.schema.orEmpty()}.${it.table}"]
                                    }.orEmpty()
                                    val editability = remember(
                                        resultSet.columns,
                                        tableCols,
                                        extracted,
                                        readonlyConnection,
                                        currentRun?.kind,
                                    ) {
                                        resultEditability(
                                            resultColumns = resultSet.columns,
                                            tableColumns = tableCols,
                                            source = extracted,
                                            readonly = readonlyConnection,
                                            explain = currentRun?.kind == "explain",
                                        )
                                    }
                                    val effectiveCols = when (editability) {
                                        is ResultEditability.Editable -> editability.columns
                                        ResultEditability.Readonly -> deduplicateResultColumns(resultSet.columns)
                                    }
                                    val primaryKeys = (editability as? ResultEditability.Editable)?.primaryKeys.orEmpty()
                                    val gridPage = remember(resultSet, effectiveCols, stmtResult.sql, primaryKeys) {
                                        resultSet.copy(columns = effectiveCols).toGridPageData(stmtResult.sql, primaryKeys)
                                    }
                                    val targetTableName = (editability as? ResultEditability.Editable)?.table
                                        ?: extracted?.table
                                        ?: shownTab
                                    val targetSchemaName = (editability as? ResultEditability.Editable)?.schema
                                        ?: extracted?.schema
                                        ?: defaultSchema.orEmpty()

                                    key(shownTab, resultSet) {
                                        Box(Modifier.fillMaxSize().testTag("db-query-result-view")) {
                                            DatabaseTableTabContent(
                                                tableName = targetTableName,
                                                schemaName = targetSchemaName,
                                                connectionName = connectionName,
                                                dbType = dbType,
                                                pageData = gridPage,
                                                schemaList = effectiveCols,
                                                rowCountView = RowCountView(
                                                    text = "Rows: ${resultSet.rows.size}${if (resultSet.truncated) "+" else ""}",
                                                    counting = false,
                                                    canCountExactly = false,
                                                    total = if (resultSet.truncated) {
                                                        com.hienlh.ppm.state.database.RowTotal.AtLeast(resultSet.rows.size.toLong())
                                                    } else {
                                                        com.hienlh.ppm.state.database.RowTotal.Exact(resultSet.rows.size.toLong())
                                                    },
                                                ),
                                                readonlyEnabled = editability !is ResultEditability.Editable,
                                                onChangesetDirtyChanged = { dirty ->
                                                    dirtyResultTabs[shownTab] = dirty
                                                },
                                                onPreviewChangeset = { payload ->
                                                    onPreviewResultChangeset(targetTableName, targetSchemaName.ifBlank { null }, payload)
                                                },
                                                onApplyChangeset = { payload, cascade ->
                                                    val applyRes = onApplyResultChangeset(
                                                        targetTableName,
                                                        targetSchemaName.ifBlank { null },
                                                        payload,
                                                        cascade,
                                                    )
                                                    dirtyResultTabs[shownTab] = false
                                                    // Re-run only this statement (`stmtResult.sql`) to refresh the edited result set
                                                    val refreshRunId = "refresh-${webRandomId()}"
                                                    var refreshedSet: QueryResultSet? = null
                                                    runCatching {
                                                        onExecuteQueryStream(
                                                            QueryRunSpec(
                                                                sql = stmtResult.sql,
                                                                lineOffset = 0,
                                                                maxRows = rowLimit,
                                                                continueOnError = false,
                                                            ),
                                                            refreshRunId,
                                                            {},
                                                            { ev ->
                                                                if (ev is QueryScriptEvent.Statement && ev.result.resultSets.isNotEmpty()) {
                                                                    refreshedSet = ev.result.resultSets.first()
                                                                }
                                                            },
                                                        )
                                                    }
                                                    val nextSet = refreshedSet
                                                    val cur = currentRun
                                                    if (nextSet != null && cur != null) {
                                                        currentRun = replaceResultSet(cur, shownTab, nextSet)
                                                    }
                                                    applyRes
                                                },
                                                onOpenQueryTab = onOpenNewQueryTab,
                                            )
                                        }
                                    }
                                }
                            }

                            // Floating History Panel when width < 860dp or on mobile
                            if (showHistoryPanel && !dockHistoryPanel && !isFileTarget) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .width(if (mobile) 300.dp else 288.dp)
                                        .align(Alignment.CenterEnd)
                                        .background(tokens.panel.color)
                                        .cssBorder(tokens.border.color, sides = CssSides.Start),
                                ) {
                                    QueryHistorySidePanel(
                                        connectionId = connectionId,
                                        editorIsEmpty = sql.isBlank(),
                                        onLoadHistory = onLoadHistory,
                                        onReplaceSql = { histSql -> updateSqlText(histSql) },
                                        onAppendSql = { histSql ->
                                            val next = if (sql.isBlank()) histSql else "${sql.trimEnd()}\n\n$histSql"
                                            updateSqlText(next)
                                        },
                                        onOpenInNewTab = onOpenNewQueryTab,
                                        onClose = { showHistoryPanel = false },
                                    )
                                }
                            }
                        }
                    }

                    // 3. Phone Bottom Thumb Bar (`§9e.6`: `Whole script` / `Run current statement` / `Stop` + `⋯`)
                    if (mobile) {
                        if (showPhoneOptionsSheet) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(tokens.panel2.color)
                                    .cssBorder(tokens.border.color, sides = CssSides.Top)
                                    .padding(12.dp)
                                    .testTag("db-query-phone-options-sheet"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BasicText(
                                    "Run options",
                                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SettingsButton(
                                        label = "Explain",
                                        modifier = Modifier.testTag("db-query-explain-btn"),
                                        onClick = {
                                            showPhoneOptionsSheet = false
                                            selectionOrStatementRun(sql, selectionText, selectionStartLine, cursorOffset, dbType)?.let { r ->
                                                requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError, explain = true))
                                            }
                                        },
                                    )
                                    SettingsButton(
                                        label = "${if (continueOnError) "☑" else "☐"} Continue on error",
                                        primary = continueOnError,
                                        modifier = Modifier.testTag("db-query-continue-on-error-btn"),
                                        onClick = {
                                            continueOnError = !continueOnError
                                            prefs.edit().putBoolean(PREF_CONTINUE_ON_ERROR, continueOnError).apply()
                                        },
                                    )
                                    SettingsButton(
                                        label = "Format SQL",
                                        modifier = Modifier.testTag("db-query-sheet-format-btn"),
                                        onClick = {
                                            showPhoneOptionsSheet = false
                                            formatEditorSql()
                                        },
                                    )
                                    SettingsButton(
                                        label = "Save As…",
                                        modifier = Modifier.testTag("db-query-save-as-btn"),
                                        onClick = {
                                            showPhoneOptionsSheet = false
                                            showSaveAsDialog = true
                                        },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        "Limit:",
                                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                                    )
                                    for (lim in QUERY_ROW_LIMITS) {
                                        SettingsButton(
                                            label = lim.toString(),
                                            primary = rowLimit == lim,
                                            modifier = Modifier.testTag("db-query-limit-option-$lim"),
                                            onClick = {
                                                rowLimit = lim
                                                prefs.edit().putInt(PREF_ROW_LIMIT, lim).apply()
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Top)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("db-query-phone-bottom-bar"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isRunning) {
                                SettingsButton(
                                    label = "■ Stop",
                                    primary = true,
                                    modifier = Modifier.weight(1f).testTag("db-query-stop-btn"),
                                    onClick = { stopCurrentRun() },
                                )
                            } else {
                                SettingsButton(
                                    label = "▶ Whole script",
                                    primary = true,
                                    modifier = Modifier.weight(1f).testTag("db-query-run-btn"),
                                    onClick = {
                                        scriptRun(sql)?.let { r ->
                                            requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError))
                                        }
                                    },
                                )
                                SettingsButton(
                                    label = "▶ Run current statement",
                                    modifier = Modifier.weight(1f).testTag("db-query-run-stmt-btn"),
                                    onClick = {
                                        selectionOrStatementRun(sql, selectionText, selectionStartLine, cursorOffset, dbType)?.let { r ->
                                            requestRun(QueryRunSpec(r.sql, r.lineOffset, rowLimit, continueOnError))
                                        }
                                    },
                                )
                            }
                            SettingsButton(
                                label = "⋯",
                                primary = showPhoneOptionsSheet,
                                modifier = Modifier.testTag("db-query-phone-options-btn"),
                                onClick = { showPhoneOptionsSheet = !showPhoneOptionsSheet },
                            )
                        }
                    }
                }

                // Docked History Panel on wide screens (>= 860dp)
                if (showHistoryPanel && dockHistoryPanel && !isFileTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(288.dp)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Start),
                    ) {
                        QueryHistorySidePanel(
                            connectionId = connectionId,
                            editorIsEmpty = sql.isBlank(),
                            onLoadHistory = onLoadHistory,
                            onReplaceSql = { histSql -> updateSqlText(histSql) },
                            onAppendSql = { histSql ->
                                val next = if (sql.isBlank()) histSql else "${sql.trimEnd()}\n\n$histSql"
                                updateSqlText(next)
                            },
                            onOpenInNewTab = onOpenNewQueryTab,
                            onClose = { showHistoryPanel = false },
                        )
                    }
                }
            }
        }

        if (showWriteOnceDialog) {
            val replay = lastRunSpec ?: scriptRun(sql)?.let {
                QueryRunSpec(it.sql, it.lineOffset, rowLimit, continueOnError)
            }
            WriteOnceDialog(
                connectionName = connectionName,
                sql = replay?.sql.orEmpty(),
                passwordRequired = writeOncePasswordRequired,
                onCancel = { showWriteOnceDialog = false },
                onRun = { password ->
                    showWriteOnceDialog = false
                    if (replay != null) executeRunNow(replay.copy(writeOncePassword = password))
                },
            )
        }
    }
}

// ── 9e.3 Messages Panel (`messages-panel.tsx`) ──────────────────────────────

@Composable
private fun QueryMessagesPanel(
    run: QueryRun?,
    mobile: Boolean,
    onJumpToLine: (Int) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("db-query-messages-panel"),
    ) {
        if (run == null || run.messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    if (run == null) "Run a query (F5 or Ctrl+Enter) to see results and messages." else "Running query…",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
            return@Column
        }

        // Header Row (Phone shows only Message + Line per §9e.6; Tablet shows #, Message, Time, Delta, Duration, Line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!mobile) {
                BasicText(
                    "#",
                    modifier = Modifier.width(28.dp),
                    style = ppmText(11f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                )
            }
            BasicText(
                "Message",
                modifier = Modifier.weight(1f),
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
            if (!mobile) {
                BasicText(
                    "Time",
                    modifier = Modifier.width(64.dp),
                    style = ppmText(11f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                )
                BasicText(
                    "Delta",
                    modifier = Modifier.width(56.dp),
                    style = ppmText(11f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                )
                BasicText(
                    "Duration",
                    modifier = Modifier.width(64.dp),
                    style = ppmText(11f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                )
            }
            BasicText(
                "Line",
                modifier = Modifier.width(48.dp),
                style = ppmText(11f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(run.messages, key = { it.number }) { msg ->
                val isErr = msg.level == "error"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isErr) tokens.error.opacity(0.1).color else Color.Transparent)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .clickable(enabled = msg.line != null) {
                            msg.line?.let(onJumpToLine)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("db-query-message-row-${msg.number}"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!mobile) {
                        BasicText(
                            msg.number.toString(),
                            modifier = Modifier.width(28.dp),
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                        )
                    }
                    BasicText(
                        msg.text,
                        modifier = Modifier.weight(1f),
                        style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(
                            color = if (isErr) tokens.error.color else tokens.text.color,
                        ),
                    )
                    if (!mobile) {
                        BasicText(
                            timeFormatter.format(Date(msg.at)),
                            modifier = Modifier.width(64.dp),
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                        )
                        BasicText(
                            "+${msg.deltaMs}ms",
                            modifier = Modifier.width(56.dp),
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                        )
                        BasicText(
                            msg.durationMs?.let { "${it}ms" } ?: "—",
                            modifier = Modifier.width(64.dp),
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                        )
                    }
                    val msgLine = msg.line
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .let { m ->
                                if (msgLine != null) {
                                    m.clickable { onJumpToLine(msgLine) }.testTag("db-query-message-line-$msgLine")
                                } else {
                                    m
                                }
                            },
                    ) {
                        BasicText(
                            msgLine?.let { "Ln $it" } ?: "—",
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(
                                color = if (msgLine != null) tokens.accent.color else tokens.text3.color,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ── 9e.2 Readonly Write-Once Dialog (`write-once-dialog.tsx`) ───────────────

/** The `/query/script` body for [spec]. A PPM with no password still sends `writeOnce`, with an empty one, as the web does. */
internal fun queryScriptRequestJson(spec: QueryRunSpec, runId: String): JsonObject = buildJsonObject {
    put("sql", spec.sql)
    put("runId", runId)
    put("maxRows", spec.maxRows)
    put("continueOnError", spec.continueOnError)
    if (spec.explain) put("explain", true)
    if (spec.writeOncePassword != null) {
        put("writeOnce", buildJsonObject { put("password", spec.writeOncePassword) })
    }
}

/**
 * The bar over a run a readonly connection refused (`offerWriteOnce` in `query-tab.tsx`):
 * `border-b bg-warning/10 px-3 py-1.5 text-xs`, its button `h-7 max-md:h-11`.
 */
@Composable
private fun WriteOnceOfferBar(connectionName: String, onRunWithWriteAccess: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.10).color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("db-query-write-once-bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PpmIcon(ProductIcon.ShieldAlert, 16.dp, tokens.warning.color)
        BasicText(
            "$connectionName is readonly.",
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .testTag("db-query-write-once-btn")
                .heightIn(min = if (mobile) 44.dp else 28.dp)
                .clip(shape)
                .ppmClickable(shape = shape, hoverColor = tokens.warning.opacity(0.15).color, onClick = onRunWithWriteAccess)
                .cssBorder(tokens.warning.opacity(0.5).color, shape)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "Run with write access (once)",
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.warning.color),
                maxLines = 1,
            )
        }
    }
}

// ── 9e.4 Discard Unsaved Result Edits Dialog (`DiscardResultEditsDialog`) ───

@Composable
private fun DiscardResultEditsDialog(
    onConfirmDiscardAndRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.warning.opacity(0.15).color)
            .cssBorder(tokens.warning.color, sides = CssSides.Bottom)
            .padding(14.dp)
            .testTag("db-query-discard-edits-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "Unsaved changes in query result",
            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.warning.color),
        )
        BasicText(
            "Running the query again will discard your unsaved edits in the result grid.",
            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(
                label = "Discard and run",
                primary = true,
                modifier = Modifier.testTag("db-query-discard-edits-confirm"),
                onClick = onConfirmDiscardAndRun,
            )
            SettingsButton(
                label = "Cancel",
                modifier = Modifier.testTag("db-query-discard-edits-cancel"),
                onClick = onCancel,
            )
        }
    }
}

// ── 9e.5 Save Query `.sql` Dialog (`use-query-file-save.tsx`) ───────────────

@Composable
private fun QuerySaveAsDialog(
    initialFolder: String,
    initialFileName: String,
    projectName: String?,
    onListDirectoryEntries: suspend (String) -> List<Pair<String, String>>,
    onConfirmSave: (SavedQueryFile) -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var folder by remember(initialFolder) { mutableStateOf(initialFolder) }
    var fileName by remember(initialFileName) { mutableStateOf(initialFileName) }
    var needsOverwriteConfirm by remember { mutableStateOf(false) }

    fun attemptSave(forceOverwrite: Boolean) {
        val cleanName = withSqlExtension(fileName)
        if (cleanName.isEmpty()) return
        val dir = folder.trim().trimEnd('/', '\\')
        val fullPath = if (dir.isEmpty()) cleanName else "$dir/$cleanName"
        scope.launch {
            if (!forceOverwrite && dir.isNotEmpty()) {
                val entries = runCatching { onListDirectoryEntries(dir) }.getOrDefault(emptyList())
                if (hasFileNamed(entries, cleanName)) {
                    needsOverwriteConfirm = true
                    return@launch
                }
            }
            onConfirmSave(SavedQueryFile(path = fullPath, projectName = projectName))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(14.dp)
            .testTag("db-query-save-as-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "Save SQL Query",
            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
        )
        SettingsTextInput(
            value = folder,
            onValueChange = {
                folder = it
                needsOverwriteConfirm = false
            },
            placeholder = "Folder path",
            mono = true,
            modifier = Modifier.fillMaxWidth().testTag("db-query-save-folder-input"),
        )
        SettingsTextInput(
            value = fileName,
            onValueChange = {
                fileName = it
                needsOverwriteConfirm = false
            },
            placeholder = "query-1.sql",
            mono = true,
            modifier = Modifier.fillMaxWidth().testTag("db-query-save-filename-input"),
        )
        if (needsOverwriteConfirm) {
            BasicText(
                "${withSqlExtension(fileName)} already exists in this folder. Overwrite it?",
                modifier = Modifier.testTag("db-query-save-overwrite-warning"),
                style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.warning.color),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    label = "Overwrite",
                    primary = true,
                    modifier = Modifier.testTag("db-query-save-overwrite-confirm"),
                    onClick = { attemptSave(forceOverwrite = true) },
                )
                SettingsButton(
                    label = "Cancel",
                    onClick = { needsOverwriteConfirm = false },
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    label = "Save",
                    primary = true,
                    modifier = Modifier.testTag("db-query-save-confirm-btn"),
                    onClick = { attemptSave(forceOverwrite = false) },
                )
                SettingsButton(
                    label = "Cancel",
                    modifier = Modifier.testTag("db-query-save-cancel-btn"),
                    onClick = onCancel,
                )
            }
        }
    }
}

// ── 9e.5 Query History Side Panel (`history-panel.tsx`) ─────────────────────

@Composable
private fun QueryHistorySidePanel(
    connectionId: Int?,
    editorIsEmpty: Boolean,
    onLoadHistory: suspend (search: String, offset: Int) -> QueryHistoryResponse,
    onReplaceSql: (String) -> Unit,
    onAppendSql: (String) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<QueryHistoryItem>>(emptyList()) }
    var retentionDays by remember { mutableIntStateOf(90) }
    var maxSizeMb by remember { mutableIntStateOf(50) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    suspend fun fetchPage(offset: Int) {
        if (connectionId == null) return
        loading = true
        val res = runCatching { onLoadHistory(searchQuery, offset) }.getOrNull()
        loading = false
        if (res != null) {
            items = mergeHistoryPage(items, res.items, offset)
            retentionDays = res.retentionDays
            maxSizeMb = res.maxSizeMb
            hasMore = historyHasMore(res.items.size)
        }
    }

    LaunchedEffect(connectionId, searchQuery) {
        if (searchQuery.isNotEmpty()) {
            delay(200)
        }
        fetchPage(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.panel.color)
            .testTag("db-query-history-panel"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Query History",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(
                label = "✕",
                modifier = Modifier.testTag("db-query-history-close-btn"),
                onClick = onClose,
            )
        }

        Box(Modifier.padding(8.dp)) {
            SettingsTextInput(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "Search SQL history…",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("db-query-history-search"),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(items, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .clickable {
                            if (editorIsEmpty) onReplaceSql(item.sql) else onAppendSql(item.sql)
                        }
                        .padding(10.dp)
                        .testTag("db-query-history-item-${item.id}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusLabel = HISTORY_STATUS_LABEL[item.status] ?: item.status
                        val statusColor = when (item.status) {
                            "ok" -> tokens.success.color
                            "blocked" -> tokens.warning.color
                            else -> tokens.error.color
                        }
                        BasicText(
                            statusLabel,
                            style = ppmText(10f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 13f).copy(color = statusColor),
                        )
                        BasicText(
                            historyMeta(item),
                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                        )
                    }

                    BasicText(
                        item.sql,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val itemError = item.error
                    if (!itemError.isNullOrBlank()) {
                        BasicText(
                            itemError,
                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.error.color),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(
                            label = "Replace",
                            modifier = Modifier.testTag("db-query-history-replace-${item.id}"),
                            onClick = { onReplaceSql(item.sql) },
                        )
                        SettingsButton(
                            label = "Append",
                            modifier = Modifier.testTag("db-query-history-append-${item.id}"),
                            onClick = { onAppendSql(item.sql) },
                        )
                        SettingsButton(
                            label = "New tab",
                            modifier = Modifier.testTag("db-query-history-new-tab-${item.id}"),
                            onClick = { onOpenInNewTab(item.sql) },
                        )
                    }
                }
            }

            if (hasMore) {
                item(key = "load-more") {
                    Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                        SettingsButton(
                            label = if (loading) "Loading…" else "Load more",
                            modifier = Modifier.testTag("db-query-history-load-more"),
                            onClick = {
                                if (!loading) {
                                    scope.launch { fetchPage(items.size) }
                                }
                            },
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            BasicText(
                retentionNote(retentionDays, maxSizeMb),
                modifier = Modifier.testTag("db-query-history-retention"),
                style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text3.color),
            )
        }
    }
}
