package com.hienlh.ppm.app.ui.database

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.DbConnectionsEvents
import com.hienlh.ppm.client.DbDriverMissingException
import com.hienlh.ppm.client.DbLoginCoordinator
import com.hienlh.ppm.client.DbLoginFailedException
import com.hienlh.ppm.client.DbLoginPromptInfo
import com.hienlh.ppm.client.dbConnectionConfig
import com.hienlh.ppm.client.dbConnections
import com.hienlh.ppm.client.dbCreateConnection
import com.hienlh.ppm.client.dbDeleteConnection
import com.hienlh.ppm.client.dbDisconnectConnection
import com.hienlh.ppm.client.dbDuplicateConnection
import com.hienlh.ppm.client.dbExportConnections
import com.hienlh.ppm.client.dbInstallMissingDriver
import com.hienlh.ppm.client.dbListDatabases
import com.hienlh.ppm.client.dbListObjects
import com.hienlh.ppm.client.dbLoginConnection
import com.hienlh.ppm.client.dbObjectSql
import com.hienlh.ppm.client.dbTableStructure
import com.hienlh.ppm.client.dbTestConnection
import com.hienlh.ppm.client.dbUpdateConnection
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.database.ConnStatusState
import com.hienlh.ppm.state.database.ConnectionFormValues
import com.hienlh.ppm.state.database.ConnectionTreeRow
import com.hienlh.ppm.state.database.DB_DEFAULT_PORT
import com.hienlh.ppm.state.database.DB_DEFAULT_SOCKET
import com.hienlh.ppm.state.database.DB_DEFAULT_USER
import com.hienlh.ppm.state.database.DB_TYPE_LABELS
import com.hienlh.ppm.state.database.DbColumnRefItem
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbObjectListState
import com.hienlh.ppm.state.database.DbRef
import com.hienlh.ppm.state.database.DbUrlParseResult
import com.hienlh.ppm.state.database.EditingConnectionInfo
import com.hienlh.ppm.state.database.GROUP_PAGE
import com.hienlh.ppm.state.database.GROUP_PAGE_MORE
import com.hienlh.ppm.state.database.ObjectTreeRow
import com.hienlh.ppm.state.database.TreeConnection
import com.hienlh.ppm.state.database.applyUrlPartsToForm
import com.hienlh.ppm.state.database.buildConnectionTreeRows
import com.hienlh.ppm.state.database.buildObjectTreeRows
import com.hienlh.ppm.state.database.buildSaveConnectionRequestJson
import com.hienlh.ppm.state.database.buildTestConnectionRequestJson
import com.hienlh.ppm.state.database.connectionWhere
import com.hienlh.ppm.state.database.defaultConnectionName
import com.hienlh.ppm.state.database.describeDbUrl
import com.hienlh.ppm.state.database.focusDiffers
import com.hienlh.ppm.state.database.formatRowEstimate
import com.hienlh.ppm.state.database.initialSchema
import com.hienlh.ppm.state.database.parseDbObjectList
import com.hienlh.ppm.state.database.parseDbUrl
import com.hienlh.ppm.state.database.parseStructureColumns
import com.hienlh.ppm.state.database.parseTreeConnections
import com.hienlh.ppm.state.database.sslFlags
import com.hienlh.ppm.state.database.switchFormEntryMode
import com.hienlh.ppm.state.database.tabDatabaseParam
import com.hienlh.ppm.state.database.validateConnectionForm
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// ── 9a.2 & 9a.3 Database Sidebar Pane (`database-sidebar.tsx`) ──────────────

@Composable
fun DatabaseSidebarPane(
    model: AppModel,
    onNavigate: () -> Unit = {},
    readOnly: Boolean = LocalPpmMobile.current,
) {
    val api = model.api
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf<List<TreeConnection>>(emptyList()) }
    val statusMap = remember { mutableStateMapOf<Int, ConnStatusState>() }
    val databasesMap = remember { mutableStateMapOf<Int, List<String>>() }
    var collapsedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var emptyFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var expandedConns by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var currentDb by remember { mutableStateOf<DbRef?>(null) }
    var focusedDb by remember { mutableStateOf<DbRef?>(null) }

    var objectList by remember { mutableStateOf(DbObjectListState(emptyList(), emptyList())) }
    var selectedSchema by remember { mutableStateOf<String?>(null) }
    var openGroups by remember { mutableStateOf(setOf("table")) }
    var expandedObjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    val structuresMap = remember { mutableStateMapOf<String, List<DbColumnRefItem>>() }
    val shownPerGroup = remember { mutableStateMapOf<String, Int>() }

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

    suspend fun reloadConnections() {
        val client = api ?: return
        runCatching { client.dbConnections() }.getOrNull()?.let { arr ->
            val parsed = parseTreeConnections(arr)
            connections = parsed
            // Connections that lost their runtime login are marked disconnected
            for (c in parsed) {
                if (c.requiresInteractiveLogin && statusMap[c.id] is ConnStatusState.Open) {
                    statusMap.remove(c.id)
                }
            }
        }
    }

    suspend fun connectAndLoadDatabase(ref: DbRef) {
        val client = api ?: return
        val conn = connections.firstOrNull { it.id == ref.conn } ?: return
        statusMap[conn.id] = ConnStatusState.Loading
        try {
            if (!conn.isSingleDatabase && ref.database == null) {
                val dbs = client.dbListDatabases(conn.id, loginCoordinator)
                databasesMap[conn.id] = dbs
                expandedConns = expandedConns + conn.id
                statusMap[conn.id] = ConnStatusState.Open
                return
            }
            val dbParam = tabDatabaseParam(ref, conn)
            val objsJson = client.dbListObjects(
                connectionId = conn.id.toString(),
                database = dbParam,
                loginCoordinator = loginCoordinator,
            )
            val parsed = parseDbObjectList(objsJson)
            objectList = parsed
            selectedSchema = if (conn.type == "postgres") {
                initialSchema(parsed.schemas, parsed.objects, selectedSchema)
            } else {
                null
            }
            currentDb = ref
            focusedDb = ref
            statusMap[conn.id] = ConnStatusState.Open
        } catch (driverMissing: DbDriverMissingException) {
            statusMap[conn.id] = ConnStatusState.Error(driverMissing.message ?: "Driver missing", driverMissing.driverId)
        } catch (err: Throwable) {
            statusMap[conn.id] = ConnStatusState.Error(err.message ?: "Connection failed")
        }
    }

    LaunchedEffect(api) {
        reloadConnections()
        DbConnectionsEvents.events.collect {
            reloadConnections()
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

        DatabaseSidebarContent(
            connections = connections,
            statusMap = statusMap,
            databasesMap = databasesMap,
            collapsedFolders = collapsedFolders,
            emptyFolders = emptyFolders,
            expandedConns = expandedConns,
            currentDb = currentDb,
            focusedDb = focusedDb,
            objectList = objectList,
            selectedSchema = selectedSchema,
            openGroups = openGroups,
            expandedObjects = expandedObjects,
            structuresMap = structuresMap,
            shownPerGroup = shownPerGroup,
            onNewConnection = {
                model.openWorkspaceTab(
                    type = "db-connection",
                    title = "New Connection",
                    metadata = buildJsonObject {},
                )
                onNavigate()
            },
            onNewQuery = {
                val cur = currentDb ?: focusedDb
                val conn = connections.firstOrNull { it.id == cur?.conn } ?: connections.firstOrNull()
                val existingTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
                val qNum = com.hienlh.ppm.state.database.nextQueryNumber(existingTabs)
                val dbParam = if (cur != null && conn != null) tabDatabaseParam(cur, conn) else null
                model.openWorkspaceTab(
                    type = "db-query",
                    title = "Query $qNum",
                    metadata = com.hienlh.ppm.state.database.queryTabMetadata(
                        sql = "",
                        queryNumber = qNum,
                        connectionId = conn?.id,
                        connectionName = conn?.name,
                        dbType = conn?.type,
                        database = dbParam,
                    ),
                )
                onNavigate()
            },
            onEditConnection = { conn ->
                model.openWorkspaceTab(
                    type = "db-connection",
                    title = "Edit ${conn.name}",
                    metadata = buildJsonObject {
                        put("connectionId", conn.id)
                        put("connectionName", conn.name)
                    },
                )
                onNavigate()
            },
            onCreateEmptyFolder = { folderName ->
                if (folderName.isNotBlank() && folderName !in emptyFolders) {
                    emptyFolders = emptyFolders + folderName.trim().take(100)
                }
            },
            onToggleFolder = { folder ->
                collapsedFolders = if (folder in collapsedFolders) collapsedFolders - folder else collapsedFolders + folder
            },
            onSelectDbRef = { ref, switchImmediately ->
                focusedDb = ref
                if (switchImmediately) {
                    scope.launch { connectAndLoadDatabase(ref) }
                }
            },
            onSwitchToFocusedDb = {
                val f = focusedDb ?: return@DatabaseSidebarContent
                scope.launch { connectAndLoadDatabase(f) }
            },
            onInstallDriver = { driverId ->
                val client = api ?: return@DatabaseSidebarContent
                scope.launch {
                    runCatching { client.dbInstallMissingDriver(driverId) }
                }
            },
            onDisconnectConnection = { connId ->
                val client = api ?: return@DatabaseSidebarContent
                scope.launch {
                    runCatching { client.dbDisconnectConnection(connId, keepLogin = false) }
                    statusMap.remove(connId)
                }
            },
            onDuplicateConnection = { connId ->
                val client = api ?: return@DatabaseSidebarContent
                scope.launch { runCatching { client.dbDuplicateConnection(connId) } }
            },
            onDeleteConnection = { connId ->
                val client = api ?: return@DatabaseSidebarContent
                scope.launch { runCatching { client.dbDeleteConnection(connId) } }
            },
            onExportConnectionsToClipboard = { context ->
                val client = api ?: return@DatabaseSidebarContent
                scope.launch {
                    runCatching { client.dbExportConnections() }.getOrNull()?.let { exported ->
                        copySensitiveTextToClipboard(context, "PPM Connections", exported.toString())
                    }
                }
            },
            onPickSchema = { selectedSchema = it },
            onToggleObjectGroup = { kind ->
                openGroups = if (kind in openGroups) openGroups - kind else openGroups + kind
            },
            onToggleExpandObject = { nodeKey, item ->
                if (nodeKey in expandedObjects) {
                    expandedObjects = expandedObjects - nodeKey
                } else {
                    expandedObjects = expandedObjects + nodeKey
                    val cur = currentDb
                    val client = api
                    val conn = connections.firstOrNull { it.id == cur?.conn }
                    if (cur != null && client != null && conn != null && !structuresMap.containsKey(nodeKey)) {
                        scope.launch {
                            runCatching {
                                client.dbTableStructure(
                                    connectionId = conn.id.toString(),
                                    table = item.name,
                                    schema = item.schema,
                                    database = tabDatabaseParam(cur, conn),
                                    loginCoordinator = loginCoordinator,
                                )
                            }.getOrNull()?.let { structObj ->
                                structuresMap[nodeKey] = parseStructureColumns(structObj)
                            }
                        }
                    }
                }
            },
            onShowMoreInGroup = { kind ->
                val currentLimit = shownPerGroup[kind] ?: GROUP_PAGE
                shownPerGroup[kind] = currentLimit + GROUP_PAGE_MORE
            },
            onOpenObjectData = { item ->
                val cur = currentDb ?: return@DatabaseSidebarContent
                val conn = connections.firstOrNull { it.id == cur.conn } ?: return@DatabaseSidebarContent
                val dbParam = tabDatabaseParam(cur, conn)
                model.openWorkspaceTab(
                    type = "database",
                    title = item.name,
                    metadata = buildJsonObject {
                        put("connectionId", conn.id)
                        put("connectionName", conn.name)
                        if (dbParam != null) put("database", dbParam)
                        put("tableName", item.name)
                        if (item.schema != null) put("schema", item.schema)
                        if (readOnly || conn.readonly == 1) put("readonly", true)
                    },
                )
                onNavigate()
            },
            onOpenObjectSql = { item ->
                val cur = currentDb ?: return@DatabaseSidebarContent
                val conn = connections.firstOrNull { it.id == cur.conn } ?: return@DatabaseSidebarContent
                val dbParam = tabDatabaseParam(cur, conn)
                model.openWorkspaceTab(
                    type = "db-sql",
                    title = "${item.name} · SQL",
                    metadata = buildJsonObject {
                        put("connectionId", conn.id)
                        put("connectionName", conn.name)
                        if (dbParam != null) put("database", dbParam)
                        put("objectKind", item.kind)
                        put("objectName", item.name)
                        if (item.schema != null) put("schema", item.schema)
                        if (item.args != null) put("args", item.args)
                        if (item.table != null) put("table", item.table)
                    },
                )
                onNavigate()
            },
            onOpenObjectStructure = { item ->
                val cur = currentDb ?: return@DatabaseSidebarContent
                val conn = connections.firstOrNull { it.id == cur.conn } ?: return@DatabaseSidebarContent
                val dbParam = tabDatabaseParam(cur, conn)
                model.openWorkspaceTab(
                    type = "db-structure",
                    title = "${item.name} · Structure",
                    metadata = buildJsonObject {
                        put("connectionId", conn.id)
                        put("connectionName", conn.name)
                        if (dbParam != null) put("database", dbParam)
                        put("dbType", conn.type)
                        put("tableName", item.name)
                        if (item.schema != null) put("schema", item.schema)
                        put("readonly", readOnly || conn.readonly == 1)
                    },
                )
                onNavigate()
            },
            onNewTable = {
                val cur = currentDb ?: focusedDb ?: return@DatabaseSidebarContent
                val conn = connections.firstOrNull { it.id == cur.conn } ?: return@DatabaseSidebarContent
                val dbParam = tabDatabaseParam(cur, conn)
                model.openWorkspaceTab(
                    type = "db-structure",
                    title = "New table · Structure",
                    metadata = buildJsonObject {
                        put("connectionId", conn.id)
                        put("connectionName", conn.name)
                        if (dbParam != null) put("database", dbParam)
                        put("dbType", conn.type)
                        put("newTable", true)
                        if (selectedSchema != null) put("schema", selectedSchema)
                    },
                )
                onNavigate()
            },
            onOpenImpExp = { mode, tableName ->
                val cur = currentDb ?: focusedDb
                val conn = connections.firstOrNull { it.id == cur?.conn } ?: connections.firstOrNull()
                val dbParam = if (cur != null && conn != null) tabDatabaseParam(cur, conn) else null
                model.openWorkspaceTab(
                    type = "db-impexp",
                    title = if (mode == "import") "Import" else "Export",
                    metadata = buildJsonObject {
                        put("impexpId", "default")
                        if (conn != null) put("connectionId", conn.id)
                        if (dbParam != null) put("database", dbParam)
                        if (selectedSchema != null) put("schema", selectedSchema)
                        if (mode != null) put("mode", mode)
                        if (tableName != null) put("tableName", tableName)
                    },
                )
                onNavigate()
            },
            readOnly = readOnly,
        )
    }
}

private fun copySensitiveTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
}

@Composable
internal fun DatabaseSidebarContent(
    connections: List<TreeConnection>,
    statusMap: Map<Int, ConnStatusState>,
    databasesMap: Map<Int, List<String>>,
    collapsedFolders: Set<String>,
    emptyFolders: List<String>,
    expandedConns: Set<Int>,
    currentDb: DbRef?,
    focusedDb: DbRef?,
    objectList: DbObjectListState,
    selectedSchema: String?,
    openGroups: Set<String>,
    expandedObjects: Set<String>,
    structuresMap: Map<String, List<DbColumnRefItem>>,
    shownPerGroup: Map<String, Int>,
    onNewConnection: () -> Unit = {},
    onNewQuery: () -> Unit = {},
    onEditConnection: (TreeConnection) -> Unit = {},
    onCreateEmptyFolder: (String) -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onSelectDbRef: (DbRef, Boolean) -> Unit = { _, _ -> },
    onSwitchToFocusedDb: () -> Unit = {},
    onInstallDriver: (String) -> Unit = {},
    onDisconnectConnection: (Int) -> Unit = {},
    onDuplicateConnection: (Int) -> Unit = {},
    onDeleteConnection: (Int) -> Unit = {},
    onExportConnectionsToClipboard: (Context) -> Unit = {},
    onPickSchema: (String?) -> Unit = {},
    onToggleObjectGroup: (String) -> Unit = {},
    onToggleExpandObject: (String, DbObjectItem) -> Unit = { _, _ -> },
    onShowMoreInGroup: (String) -> Unit = {},
    onOpenObjectData: (DbObjectItem) -> Unit = {},
    onOpenObjectSql: (DbObjectItem) -> Unit = {},
    onOpenObjectStructure: (DbObjectItem) -> Unit = {},
    onNewTable: () -> Unit = {},
    onOpenImpExp: (mode: String?, tableName: String?) -> Unit = { _, _ -> },
    readOnly: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current

    var connectionsCollapsed by remember { mutableStateOf(false) }
    var objectsCollapsed by remember { mutableStateOf(false) }
    var splitRatio by remember { mutableFloatStateOf(0.42f) }

    var connQuery by remember { mutableStateOf("") }
    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showExportWarning by remember { mutableStateOf(false) }
    var menuConn by remember { mutableStateOf<TreeConnection?>(null) }

    var objQuery by remember { mutableStateOf("") }
    var objSort by remember { mutableStateOf("name") }
    var onlyWithRows by remember { mutableStateOf(false) }

    val connRows = remember(
        connections,
        statusMap.toMap(),
        databasesMap.toMap(),
        collapsedFolders,
        emptyFolders,
        expandedConns,
        currentDb,
        focusedDb,
        connQuery,
        creatingFolder,
    ) {
        buildConnectionTreeRows(
            connections = connections,
            status = statusMap,
            databases = databasesMap,
            collapsedFolders = collapsedFolders,
            emptyFolders = emptyFolders,
            expandedConns = expandedConns,
            current = currentDb,
            focused = focusedDb,
            query = connQuery,
            creatingFolder = creatingFolder,
        )
    }

    val showFocusPrompt = remember(focusedDb, currentDb, connections) {
        focusDiffers(focusedDb, currentDb) { id -> connections.any { it.id == id } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("database-sidebar"),
    ) {
        // Top Header: Database + Export/Import + New Connection
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(ProductIcon.Database.vector, 15.dp, tokens.text.color)
                BasicText(
                    "Database",
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (readOnly) {
                    BasicText(
                        "Read-only",
                        style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f)
                            .copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("db-sidebar-readonly-badge"),
                    )
                }
                SettingsButton(
                    label = "+ Query",
                    modifier = Modifier.testTag("db-new-query-btn"),
                    onClick = onNewQuery,
                )
                if (!readOnly) {
                    SettingsButton(
                        label = "+ Table",
                        modifier = Modifier.testTag("db-new-table-btn"),
                        onClick = onNewTable,
                    )
                    SettingsButton(
                        label = "Imp/Exp",
                        modifier = Modifier.testTag("db-open-impexp-btn"),
                        onClick = { onOpenImpExp(null, null) },
                    )
                    SettingsButton(
                        label = "+ Folder",
                        modifier = Modifier.testTag("db-new-folder-btn"),
                        onClick = {
                            creatingFolder = !creatingFolder
                            newFolderName = ""
                        },
                    )
                    SettingsButton(
                        label = "Export",
                        modifier = Modifier.testTag("db-export-btn"),
                        onClick = { showExportWarning = true },
                    )
                    SettingsButton(
                        label = "+ Connection",
                        primary = true,
                        modifier = Modifier.testTag("db-new-connection-btn"),
                        onClick = onNewConnection,
                    )
                }
            }
        }

        // Plaintext Password Warning Banner on Export (`§9a.2`)
        if (showExportWarning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.warning.opacity(0.15).color)
                    .cssBorder(tokens.warning.color, sides = CssSides.Bottom)
                    .padding(10.dp)
                    .testTag("db-export-plaintext-warning"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "Exported connections include saved passwords in plaintext. Keep the exported JSON safe.",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.warning.color),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Copy Sensitive JSON",
                        primary = true,
                        modifier = Modifier.testTag("db-export-confirm-copy"),
                        onClick = {
                            onExportConnectionsToClipboard(context)
                            showExportWarning = false
                        },
                    )
                    SettingsButton(
                        label = "Cancel",
                        onClick = { showExportWarning = false },
                    )
                }
            }
        }

        // Section 1 Header: CONNECTIONS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .clickable { connectionsCollapsed = !connectionsCollapsed }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("db-connections-section-header"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "${if (connectionsCollapsed) "▸" else "▾"} CONNECTIONS (${connections.size})",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
        }

        if (!connectionsCollapsed) {
            val connModifier = if (!objectsCollapsed) {
                Modifier.weight(if (mobile) 0.42f else splitRatio).fillMaxWidth()
            } else {
                Modifier.weight(1f).fillMaxWidth()
            }
            Column(modifier = connModifier) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    SettingsTextInput(
                        value = connQuery,
                        onValueChange = { connQuery = it },
                        placeholder = "Search connections or databases...",
                        modifier = Modifier.fillMaxWidth().testTag("db-connection-search-input"),
                    )
                }

                if (creatingFolder) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsTextInput(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = "Folder name",
                            modifier = Modifier.weight(1f).testTag("db-new-folder-input"),
                        )
                        SettingsButton(
                            label = "Add",
                            primary = true,
                            modifier = Modifier.testTag("db-new-folder-submit"),
                            onClick = {
                                if (newFolderName.isNotBlank()) {
                                    onCreateEmptyFolder(newFolderName.trim())
                                    creatingFolder = false
                                    newFolderName = ""
                                }
                            },
                        )
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("db-connections-tree")) {
                    items(connRows, key = { it.key }) { row ->
                        when (row) {
                            is ConnectionTreeRow.Folder -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleFolder(row.name) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("db-folder-${row.name}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PpmIcon(
                                        if (row.open) ProductIcon.FolderOpen.vector else ProductIcon.FolderTree.vector,
                                        14.dp,
                                        tokens.text2.color,
                                    )
                                    BasicText(
                                        row.name,
                                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                                        modifier = Modifier.weight(1f),
                                    )
                                    BasicText(
                                        row.count.toString(),
                                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                                    )
                                }
                            }
                            is ConnectionTreeRow.Separator -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .height(1.dp)
                                        .background(tokens.border.color),
                                )
                            }
                            is ConnectionTreeRow.ConnectionItem -> {
                                val c = row.conn
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (row.selected) tokens.accent.opacity(0.14).color else Color.Transparent)
                                        .clickable {
                                            val targetRef = c.singleDbRef ?: DbRef(c.id, null)
                                            onSelectDbRef(targetRef, currentDb == null || !c.isSingleDatabase)
                                        }
                                        .padding(start = (10 + row.depth * 14).dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                                        .testTag("db-conn-row-${c.id}"),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        // Color dot
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(tokens.accent.color),
                                        )
                                        BasicText(
                                            c.name,
                                            style = ppmText(
                                                12f,
                                                if (row.current) FontWeight.Bold else FontWeight.Normal,
                                                lineHeight = 16f,
                                            ).copy(color = tokens.text.color),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (c.readonly == 1) {
                                            BasicText(
                                                "RO",
                                                style = ppmText(9f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 12f)
                                                    .copy(color = tokens.text3.color),
                                            )
                                        }
                                        when (val st = row.status) {
                                            is ConnStatusState.Open -> {
                                                BasicText(
                                                    "✓",
                                                    style = ppmText(11f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.success.color),
                                                )
                                            }
                                            is ConnStatusState.Loading -> {
                                                BasicText(
                                                    "…",
                                                    style = ppmText(11f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.accent.color),
                                                )
                                            }
                                            is ConnStatusState.Error -> {
                                                BasicText(
                                                    "!",
                                                    style = ppmText(11f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.error.color),
                                                )
                                            }
                                            null -> {}
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable { menuConn = if (menuConn?.id == c.id) null else c }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                .testTag("db-conn-menu-btn-${c.id}"),
                                        ) {
                                            BasicText("⋮", style = ppmText(12f, FontWeight.Bold, lineHeight = 14f).copy(color = tokens.text3.color))
                                        }
                                    }
                                    val st = row.status
                                    if (st is ConnStatusState.Error) {
                                        Row(
                                            modifier = Modifier.padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            BasicText(
                                                st.message,
                                                style = ppmText(10f, lineHeight = 14f).copy(color = tokens.error.color),
                                                modifier = Modifier.weight(1f),
                                            )
                                            val missingDriver = st.missingDriverId
                                            if (missingDriver != null) {
                                                if (readOnly) {
                                                    BasicText(
                                                        "Install the driver on a tablet.",
                                                        style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text3.color),
                                                        modifier = Modifier.testTag("db-readonly-driver-tablet-note"),
                                                    )
                                                } else {
                                                    SettingsButton(
                                                        label = "Install Driver",
                                                        primary = true,
                                                        modifier = Modifier.testTag("db-install-driver-$missingDriver"),
                                                        onClick = { onInstallDriver(missingDriver) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (menuConn?.id == c.id) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp)
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            SettingsButton(
                                                label = "Connect",
                                                onClick = {
                                                    menuConn = null
                                                    onSelectDbRef(c.singleDbRef ?: DbRef(c.id, null), true)
                                                },
                                            )
                                            if (readOnly) {
                                                SettingsButton(
                                                    label = "New query",
                                                    onClick = {
                                                        menuConn = null
                                                        onNewQuery()
                                                    },
                                                )
                                            }
                                            if (!readOnly) {
                                                SettingsButton(
                                                    label = "Edit",
                                                    onClick = {
                                                        menuConn = null
                                                        onEditConnection(c)
                                                    },
                                                )
                                                SettingsButton(
                                                    label = "Duplicate",
                                                    onClick = {
                                                        menuConn = null
                                                        onDuplicateConnection(c.id)
                                                    },
                                                )
                                            }
                                            SettingsButton(
                                                label = "Disconnect",
                                                onClick = {
                                                    menuConn = null
                                                    onDisconnectConnection(c.id)
                                                },
                                            )
                                            if (!readOnly) {
                                                SettingsButton(
                                                    label = "Delete",
                                                    onClick = {
                                                        menuConn = null
                                                        onDeleteConnection(c.id)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            is ConnectionTreeRow.DatabaseItem -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (row.selected) tokens.accent.opacity(0.14).color else Color.Transparent)
                                        .clickable {
                                            onSelectDbRef(DbRef(row.conn.id, row.database), currentDb == null)
                                        }
                                        .padding(start = (14 + row.depth * 14).dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
                                        .testTag("db-database-row-${row.conn.id}-${row.database}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    PpmIcon(ProductIcon.Database.vector, 12.dp, tokens.text2.color)
                                    BasicText(
                                        row.database,
                                        style = ppmText(
                                            12f,
                                            if (row.current) FontWeight.Bold else FontWeight.Normal,
                                            lineHeight = 16f,
                                        ).copy(color = tokens.text.color),
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
                if (readOnly) {
                    BasicText(
                        "Add or edit connections on a tablet.",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("db-readonly-connections-tablet-note"),
                    )
                }
            }
        }

        // Section 2 Header: TABLES, VIEWS, FUNCTIONS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .clickable { objectsCollapsed = !objectsCollapsed }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("db-objects-section-header"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "${if (objectsCollapsed) "▸" else "▾"} TABLES, VIEWS, FUNCTIONS",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
            if (objectList.schemas.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (sch in objectList.schemas.take(4)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedSchema == sch) tokens.accent.opacity(0.2).color else Color.Transparent)
                                .clickable { onPickSchema(sch) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            BasicText(
                                sch,
                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(
                                    color = if (selectedSchema == sch) tokens.accent.color else tokens.text3.color,
                                ),
                            )
                        }
                    }
                }
            }
        }

        if (!objectsCollapsed) {
            Column(modifier = Modifier.weight(if (connectionsCollapsed) 1f else (if (mobile) 0.58f else 1f - splitRatio)).fillMaxWidth()) {
                if (showFocusPrompt && focusedDb != null) {
                    val targetConn = connections.firstOrNull { it.id == focusedDb.conn }
                    val targetName = focusedDb.database ?: targetConn?.name ?: "database"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .padding(10.dp)
                            .testTag("db-focused-switch-prompt"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "Switch to $targetName?",
                            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                            modifier = Modifier.weight(1f),
                        )
                        SettingsButton(
                            label = "Switch",
                            primary = true,
                            modifier = Modifier.testTag("db-focused-switch-btn"),
                            onClick = onSwitchToFocusedDb,
                        )
                    }
                }

                // Filter & Sort bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsTextInput(
                        value = objQuery,
                        onValueChange = { objQuery = it },
                        placeholder = "Filter objects...",
                        modifier = Modifier.weight(1f).testTag("db-object-search-input"),
                    )
                    SettingsButton(
                        label = if (objSort == "name") "A–Z" else "Rows",
                        modifier = Modifier.testTag("db-object-sort-btn"),
                        onClick = { objSort = if (objSort == "name") "rows" else "name" },
                    )
                    SettingsButton(
                        label = ">0",
                        primary = onlyWithRows,
                        modifier = Modifier.testTag("db-object-nonempty-btn"),
                        onClick = { onlyWithRows = !onlyWithRows },
                    )
                }

                val cur = currentDb
                if (cur == null) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        BasicText(
                            "Select a database connection above to browse tables, views, and routines.",
                            style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text3.color),
                        )
                    }
                } else {
                    val objRows = remember(
                        cur,
                        objectList,
                        selectedSchema,
                        objQuery,
                        objSort,
                        onlyWithRows,
                        openGroups,
                        expandedObjects,
                        structuresMap.toMap(),
                        shownPerGroup.toMap(),
                    ) {
                        buildObjectTreeRows(
                            ref = cur,
                            list = objectList,
                            schema = selectedSchema,
                            query = objQuery,
                            sort = objSort,
                            onlyWithRows = onlyWithRows,
                            openGroups = openGroups,
                            expandedObjects = expandedObjects,
                            structures = structuresMap,
                            shownPerGroup = shownPerGroup,
                        )
                    }

                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("db-objects-tree")) {
                        items(objRows, key = { it.key }) { row ->
                            when (row) {
                                is ObjectTreeRow.Group -> {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onToggleObjectGroup(row.groupKind) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                            .testTag("db-obj-group-${row.groupKind}"),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicText(
                                            "${if (row.open) "▾" else "▸"} ${row.label}",
                                            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
                                        )
                                        BasicText(
                                            row.count.toString(),
                                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                                        )
                                    }
                                }
                                is ObjectTreeRow.ObjectEntry -> {
                                    val item = row.item
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenObjectData(item) }
                                            .padding(start = 20.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)
                                            .testTag("db-obj-row-${item.name}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (row.expandable) {
                                            Box(
                                                modifier = Modifier
                                                    .clickable { onToggleExpandObject(row.nodeKey, item) }
                                                    .padding(2.dp)
                                                    .testTag("db-obj-expand-${item.name}"),
                                            ) {
                                                BasicText(
                                                    if (row.expanded) "▾" else "▸",
                                                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                                                )
                                            }
                                        }
                                        PpmIcon(ProductIcon.Table.vector, 13.dp, tokens.text2.color)
                                        BasicText(
                                            item.name + (if (row.showArgs) "(${item.args})" else ""),
                                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        val est = item.rowEstimate
                                        if (est != null) {
                                            BasicText(
                                                formatRowEstimate(est),
                                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                                            )
                                        }
                                        if (row.expandable && !readOnly) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(tokens.panel.color)
                                                    .clickable { onOpenObjectStructure(item) }
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    .testTag("db-obj-structure-${item.name}"),
                                            ) {
                                                BasicText(
                                                    "Struct",
                                                    style = ppmText(9f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 12f)
                                                        .copy(color = tokens.text2.color),
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(tokens.panel.color)
                                                .clickable { onOpenObjectSql(item) }
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                                .testTag("db-obj-sql-${item.name}"),
                                        ) {
                                            BasicText(
                                                "SQL",
                                                style = ppmText(9f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 12f)
                                                    .copy(color = tokens.accent.color),
                                            )
                                        }
                                    }
                                }
                                is ObjectTreeRow.ColumnEntry -> {
                                    val c = row.column
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 42.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (c.pk) {
                                            BasicText(
                                                "PK",
                                                style = ppmText(9f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 12f)
                                                    .copy(color = tokens.warning.color),
                                            )
                                        }
                                        BasicText(
                                            c.name,
                                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                                            modifier = Modifier.weight(1f),
                                        )
                                        BasicText(
                                            c.type,
                                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                                        )
                                    }
                                }
                                is ObjectTreeRow.MoreEntries -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onShowMoreInGroup(row.groupKind) }
                                            .padding(horizontal = 24.dp, vertical = 6.dp)
                                            .testTag("db-obj-more-${row.groupKind}"),
                                    ) {
                                        BasicText(
                                            "Show ${row.hiddenCount} more…",
                                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accent.color),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 9a.4 Connection Form Tab (`tab.type == "db-connection"`) ────────────────

private val CONNECTION_COLORS = listOf(
    "#ef4444", "#f97316", "#eab308", "#22c55e", "#10b981",
    "#06b6d4", "#3b82f6", "#6366f1", "#a855f7", "#ec4899",
)

@Composable
fun DbConnectionFormTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val editingId = (tab.metadata["connectionId"] as? JsonPrimitive)?.intOrNull

    var initialValues by remember(editingId) { mutableStateOf(ConnectionFormValues()) }
    var editingInfo by remember(editingId) {
        mutableStateOf(editingId?.let { EditingConnectionInfo(id = it) })
    }
    var testSummary by remember { mutableStateOf<String?>(null) }
    var testDetails by remember { mutableStateOf<String?>(null) }
    var testIsError by remember { mutableStateOf(false) }
    var missingDriverId by remember { mutableStateOf<String?>(null) }
    var firstSeenSshNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, editingId) {
        if (api == null || editingId == null) return@LaunchedEffect
        runCatching { api.dbConnectionConfig(editingId) }.getOrNull()?.let { cfg ->
            val type = cfg["type"].string ?: "postgres"
            if (type == "sqlite") {
                initialValues = ConnectionFormValues(
                    type = "sqlite",
                    name = tab.metaString("connectionName").orEmpty(),
                    path = cfg["path"].string.orEmpty(),
                )
            } else {
                val connStr = cfg["connectionString"].string.orEmpty()
                val base = ConnectionFormValues(
                    type = type,
                    name = tab.metaString("connectionName").orEmpty(),
                    entry = cfg["entry"].string ?: "url",
                    url = connStr,
                    passwordMode = cfg["passwordMode"].string ?: "save",
                    singleDatabase = cfg["singleDatabase"]?.bool ?: true,
                    allowedDatabases = (cfg["allowedDatabases"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.string }
                        ?.joinToString("\n")
                        .orEmpty(),
                    allowedDatabasesRegex = cfg["allowedDatabasesRegex"].string.orEmpty(),
                )
                val parsed = parseDbUrl(connStr)
                initialValues = if (parsed is DbUrlParseResult.Url) applyUrlPartsToForm(base, parsed.parts) else base
                editingInfo = EditingConnectionInfo(
                    id = editingId,
                    passwordSaved = cfg["hasPassword"]?.bool == true,
                )
            }
        }
    }

    DbConnectionFormContent(
        initialValues = initialValues,
        editingInfo = editingInfo,
        testSummary = testSummary,
        testDetails = testDetails,
        testIsError = testIsError,
        missingDriverId = missingDriverId,
        firstSeenSshNotice = firstSeenSshNotice,
        onTestConnection = { values ->
            val client = api ?: return@DbConnectionFormContent
            scope.launch {
                missingDriverId = null
                firstSeenSshNotice = null
                runCatching {
                    client.dbTestConnection(buildTestConnectionRequestJson(values, editingInfo))
                }.onSuccess { res ->
                    val ok = res["ok"]?.bool == true
                    if (ok) {
                        val ver = res["version"].string ?: "Connected"
                        val target = res["target"].string.orEmpty()
                        val ms = res["elapsedMs"].int ?: 0
                        testIsError = false
                        testSummary = "Connected: $ver · $target · ${ms}ms"
                        testDetails = res["tls"]?.string?.let { "TLS: $it" }
                        val hops = (res["ssh"] as? JsonArray).orEmpty()
                        val firstSeen = hops.mapNotNull { it as? JsonObject }.firstOrNull { it["firstSeen"]?.bool == true }
                        if (firstSeen != null) {
                            firstSeenSshNotice = "First connection to ${firstSeen["host"].string}: trusted host key ${firstSeen["fingerprint"].string}"
                        }
                    } else {
                        testIsError = true
                        testSummary = res["error"].string ?: "Connection test failed"
                        testDetails = res["details"].string
                    }
                }.onFailure { err ->
                    testIsError = true
                    if (err is DbDriverMissingException) {
                        missingDriverId = err.driverId
                    }
                    testSummary = err.message ?: "Connection test failed"
                }
            }
        },
        onSaveConnection = { values, connectAfterSave ->
            val client = api ?: return@DbConnectionFormContent
            scope.launch {
                val body = buildSaveConnectionRequestJson(values, editingInfo)
                runCatching {
                    if (editingInfo != null) {
                        client.dbUpdateConnection(editingInfo!!.id, body)
                    } else {
                        client.dbCreateConnection(body)
                    }
                }.onSuccess {
                    testIsError = false
                    testSummary = if (connectAfterSave) "Saved & connected." else "Connection saved."
                }.onFailure { err ->
                    testIsError = true
                    testSummary = err.message ?: "Failed to save connection"
                }
            }
        },
        onInstallMissingDriver = { driverId ->
            val client = api ?: return@DbConnectionFormContent
            scope.launch {
                runCatching { client.dbInstallMissingDriver(driverId) }.onSuccess {
                    missingDriverId = null
                    testIsError = false
                    testSummary = "Driver $driverId installed. Ready to test."
                }
            }
        },
    )
}

@Composable
internal fun DbConnectionFormContent(
    initialValues: ConnectionFormValues,
    editingInfo: EditingConnectionInfo? = null,
    testSummary: String? = null,
    testDetails: String? = null,
    testIsError: Boolean = false,
    missingDriverId: String? = null,
    firstSeenSshNotice: String? = null,
    onTestConnection: (ConnectionFormValues) -> Unit = {},
    onSaveConnection: (ConnectionFormValues, Boolean) -> Unit = { _, _ -> },
    onInstallMissingDriver: (String) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var values by remember(initialValues) { mutableStateOf(initialValues) }
    var activeSubTab by remember { mutableStateOf("general") }
    var validationProblem by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    val availableTabs = if (values.type == "sqlite") {
        listOf("general" to "General")
    } else {
        listOf(
            "general" to "General",
            "ssh" to "SSH Tunnel",
            "ssl" to "SSL",
            "advanced" to "Advanced",
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("db-connection-form"),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Engine Tiles (locked when editing)
            BasicText(
                "Database Engine",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((engId, engLabel) in DB_TYPE_LABELS) {
                    val selected = values.type == engId
                    val locked = editingInfo != null && !selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) tokens.accent.opacity(0.18).color else tokens.panel.color)
                            .cssBorder(if (selected) tokens.accent.color else tokens.border.color)
                            .clickable(enabled = editingInfo == null) {
                                values = values.copy(type = engId)
                                if (engId == "sqlite") activeSubTab = "general"
                            }
                            .padding(vertical = 10.dp)
                            .testTag("db-engine-tile-$engId"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            engLabel,
                            style = ppmText(12f, if (selected) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 16f).copy(
                                color = when {
                                    selected -> tokens.accent.color
                                    locked -> tokens.text3.opacity(0.5).color
                                    else -> tokens.text.color
                                },
                            ),
                        )
                    }
                }
            }

            // 2. Sub-tab bar
            if (availableTabs.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((tabId, tabLabel) in availableTabs) {
                        SettingsButton(
                            label = tabLabel,
                            primary = activeSubTab == tabId,
                            modifier = Modifier.testTag("db-form-tab-$tabId"),
                            onClick = { activeSubTab = tabId },
                        )
                    }
                }
            }

            // 3. Sub-tab body
            when (activeSubTab) {
                "general" -> {
                    if (values.type == "sqlite") {
                        BasicText("Database File Path (on PPM host)", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
                        SettingsTextInput(
                            value = values.path,
                            onValueChange = { values = values.copy(path = it) },
                            placeholder = "/path/to/database.db",
                            mono = true,
                            modifier = Modifier.fillMaxWidth().testTag("db-form-sqlite-path"),
                        )
                    } else {
                        // Fields <-> URL switcher
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText("Connection Entry", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SettingsButton(
                                    label = "Fields",
                                    primary = values.entry == "fields",
                                    modifier = Modifier.testTag("db-entry-fields"),
                                    onClick = {
                                        val (next, prob) = switchFormEntryMode(values, "fields")
                                        if (next != null) {
                                            values = next
                                            validationProblem = null
                                        } else if (prob != null) {
                                            validationProblem = prob.message
                                        }
                                    },
                                )
                                SettingsButton(
                                    label = "URL",
                                    primary = values.entry == "url",
                                    modifier = Modifier.testTag("db-entry-url"),
                                    onClick = {
                                        val (next, _) = switchFormEntryMode(values, "url")
                                        if (next != null) values = next
                                    },
                                )
                            }
                        }

                        if (values.entry == "url") {
                            SettingsTextInput(
                                value = values.url,
                                onValueChange = { values = values.copy(url = it) },
                                placeholder = "${values.type}://user@localhost:${DB_DEFAULT_PORT[values.type] ?: 5432}/dbname",
                                mono = true,
                                modifier = Modifier.fillMaxWidth().testTag("db-form-url-input"),
                            )
                            val parsed = parseDbUrl(values.url)
                            if (parsed is DbUrlParseResult.Url) {
                                BasicText(
                                    describeDbUrl(parsed.parts),
                                    modifier = Modifier.testTag("db-form-url-description"),
                                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.success.color),
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsTextInput(
                                    value = values.host,
                                    onValueChange = { values = values.copy(host = it) },
                                    placeholder = "Server (localhost)",
                                    mono = true,
                                    modifier = Modifier.weight(1f).testTag("db-form-host-input"),
                                )
                                SettingsTextInput(
                                    value = values.port,
                                    onValueChange = { values = values.copy(port = it) },
                                    placeholder = (DB_DEFAULT_PORT[values.type] ?: 5432).toString(),
                                    mono = true,
                                    modifier = Modifier.width(96.dp).testTag("db-form-port-input"),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SettingsTextInput(
                                    value = values.user,
                                    onValueChange = { values = values.copy(user = it) },
                                    placeholder = "User (${DB_DEFAULT_USER[values.type] ?: "postgres"})",
                                    mono = true,
                                    modifier = Modifier.weight(1f).testTag("db-form-user-input"),
                                )
                                SettingsTextInput(
                                    value = values.password,
                                    onValueChange = { values = values.copy(password = it) },
                                    placeholder = if (editingInfo?.passwordSaved == true) "Saved (leave empty to keep)" else "Password",
                                    modifier = Modifier.weight(1f).testTag("db-form-password-input"),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicText("Password Mode:", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color))
                                for ((modeId, modeLabel) in listOf("save" to "Save", "askPassword" to "Ask Password", "askUser" to "Ask User & Password")) {
                                    SettingsButton(
                                        label = modeLabel,
                                        primary = values.passwordMode == modeId,
                                        modifier = Modifier.testTag("db-password-mode-$modeId"),
                                        onClick = { values = values.copy(passwordMode = modeId) },
                                    )
                                }
                            }
                            SettingsTextInput(
                                value = values.database,
                                onValueChange = { values = values.copy(database = it) },
                                placeholder = "Default database",
                                mono = true,
                                modifier = Modifier.fillMaxWidth().testTag("db-form-database-input"),
                            )
                        }
                    }

                    // Display Name, Folder, Color, Read-only, AI access
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsTextInput(
                            value = values.name,
                            onValueChange = { values = values.copy(name = it) },
                            placeholder = "Display name (${defaultConnectionName(values)})",
                            modifier = Modifier.weight(1f).testTag("db-form-name-input"),
                        )
                        SettingsTextInput(
                            value = values.folder,
                            onValueChange = { values = values.copy(folder = it.take(100)) },
                            placeholder = "Folder (optional)",
                            modifier = Modifier.weight(1f).testTag("db-form-folder-input"),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsButton(
                            label = if (values.readonly) "Read-only ✓" else "Read-only",
                            primary = values.readonly,
                            modifier = Modifier.testTag("db-form-readonly-toggle"),
                            onClick = { values = values.copy(readonly = !values.readonly) },
                        )
                        SettingsButton(
                            label = if (values.aiAccess) "AI Access ✓" else "AI Access",
                            primary = values.aiAccess,
                            modifier = Modifier.testTag("db-form-ai-toggle"),
                            onClick = { values = values.copy(aiAccess = !values.aiAccess) },
                        )
                    }

                    // Color dots
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("Color:", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color))
                        for (hex in CONNECTION_COLORS) {
                            val selected = values.color == hex
                            val c = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .cssBorder(if (selected) tokens.text.color else Color.Transparent, CircleShape)
                                    .clickable { values = values.copy(color = if (selected) null else hex) },
                            )
                        }
                    }
                }
                "ssh" -> {
                    SettingsButton(
                        label = if (values.sshEnabled) "Use SSH Tunnel ✓" else "Use SSH Tunnel",
                        primary = values.sshEnabled,
                        modifier = Modifier.testTag("db-form-ssh-toggle"),
                        onClick = { values = values.copy(sshEnabled = !values.sshEnabled) },
                    )
                    if (values.sshEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsTextInput(
                                value = values.sshHost,
                                onValueChange = { values = values.copy(sshHost = it) },
                                placeholder = "SSH Host",
                                mono = true,
                                modifier = Modifier.weight(1f).testTag("db-form-ssh-host"),
                            )
                            SettingsTextInput(
                                value = values.sshPort,
                                onValueChange = { values = values.copy(sshPort = it) },
                                placeholder = "22",
                                mono = true,
                                modifier = Modifier.width(84.dp).testTag("db-form-ssh-port"),
                            )
                        }
                        SettingsTextInput(
                            value = values.sshBastionHost,
                            onValueChange = { values = values.copy(sshBastionHost = it) },
                            placeholder = "Bastion [user@]host[:port] (optional)",
                            mono = true,
                            modifier = Modifier.fillMaxWidth().testTag("db-form-ssh-bastion"),
                        )
                        SettingsTextInput(
                            value = values.sshUser,
                            onValueChange = { values = values.copy(sshUser = it) },
                            placeholder = "SSH User (empty uses PPM host OS user)",
                            mono = true,
                            modifier = Modifier.fillMaxWidth().testTag("db-form-ssh-user"),
                        )
                    }
                }
                "ssl" -> {
                    SettingsTextInput(
                        value = values.sslCa,
                        onValueChange = { values = values.copy(sslCa = it) },
                        placeholder = "CA Certificate path on PPM host",
                        mono = true,
                        modifier = Modifier.fillMaxWidth().testTag("db-form-ssl-ca"),
                    )
                    SettingsTextInput(
                        value = values.sslCert,
                        onValueChange = { values = values.copy(sslCert = it) },
                        placeholder = "Client Certificate path on PPM host",
                        mono = true,
                        modifier = Modifier.fillMaxWidth().testTag("db-form-ssl-cert"),
                    )
                    SettingsTextInput(
                        value = values.sslKey,
                        onValueChange = { values = values.copy(sslKey = it) },
                        placeholder = "Private Key path on PPM host",
                        mono = true,
                        modifier = Modifier.fillMaxWidth().testTag("db-form-ssl-key"),
                    )
                }
                "advanced" -> {
                    BasicText("Allowed Databases (one per line)", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = values.allowedDatabases,
                        onValueChange = { values = values.copy(allowedDatabases = it) },
                        placeholder = "prod_db",
                        mono = true,
                        modifier = Modifier.fillMaxWidth().testTag("db-form-allowed-dbs"),
                    )
                    BasicText("Allowed Databases Regex (JS case-insensitive)", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = values.allowedDatabasesRegex,
                        onValueChange = { values = values.copy(allowedDatabasesRegex = it) },
                        placeholder = "^prod_.*",
                        mono = true,
                        modifier = Modifier.fillMaxWidth().testTag("db-form-allowed-regex"),
                    )
                    BasicText("Query Timeout (1–86400 seconds)", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = values.queryTimeoutSec,
                        onValueChange = { values = values.copy(queryTimeoutSec = it) },
                        placeholder = "No limit",
                        mono = true,
                        modifier = Modifier.fillMaxWidth().testTag("db-form-query-timeout"),
                    )
                }
            }
        }

        // Bottom Pinned Result + Action Bar (44dp height on mobile per §9a.4)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (validationProblem != null) {
                BasicText(
                    validationProblem!!,
                    modifier = Modifier.testTag("db-form-validation-error"),
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.error.color),
                )
            }
            if (firstSeenSshNotice != null) {
                BasicText(
                    firstSeenSshNotice,
                    modifier = Modifier.testTag("db-form-ssh-first-seen"),
                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.warning.color),
                )
            }
            if (testSummary != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        testSummary,
                        modifier = Modifier.weight(1f).testTag("db-form-test-summary"),
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(
                            color = if (testIsError) tokens.error.color else tokens.success.color,
                        ),
                    )
                    if (missingDriverId != null) {
                        SettingsButton(
                            label = "Install Driver",
                            primary = true,
                            modifier = Modifier.testTag("db-form-install-driver"),
                            onClick = { onInstallMissingDriver(missingDriverId) },
                        )
                    } else if (!testDetails.isNullOrBlank()) {
                        SettingsButton(
                            label = if (showDetails) "Hide Details" else "Details",
                            onClick = { showDetails = !showDetails },
                        )
                    }
                }
                if (showDetails && !testDetails.isNullOrBlank()) {
                    BasicText(
                        testDetails,
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (mobile) 44.dp else 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "Connect",
                    primary = true,
                    modifier = Modifier.testTag("db-form-connect-btn"),
                    onClick = {
                        val prob = validateConnectionForm(values, forSave = true)
                        if (prob != null) {
                            validationProblem = prob.message
                        } else {
                            validationProblem = null
                            onSaveConnection(values, true)
                        }
                    },
                )
                SettingsButton(
                    label = "Test",
                    modifier = Modifier.testTag("db-form-test-btn"),
                    onClick = {
                        val prob = validateConnectionForm(values, forSave = false)
                        if (prob != null) {
                            validationProblem = prob.message
                        } else {
                            validationProblem = null
                            onTestConnection(values)
                        }
                    },
                )
                SettingsButton(
                    label = "Save",
                    modifier = Modifier.testTag("db-form-save-btn"),
                    onClick = {
                        val prob = validateConnectionForm(values, forSave = true)
                        if (prob != null) {
                            validationProblem = prob.message
                        } else {
                            validationProblem = null
                            onSaveConnection(values, false)
                        }
                    },
                )
            }
        }
    }
}

// ── 9a.3 SQL Object Tab (`tab.type == "db-sql"`) ────────────────────────────

@Composable
fun DbSqlObjectTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val connId = (tab.metadata["connectionId"] as? JsonPrimitive)?.intOrNull?.toString()
        ?: tab.metaString("connectionId").orEmpty()
    val kind = tab.metaString("objectKind") ?: "table"
    val name = tab.metaString("objectName") ?: tab.title
    val schema = tab.metaString("schema")
    val args = tab.metaString("args")
    val table = tab.metaString("table")
    val database = tab.metaString("database")

    var createSql by remember(connId, kind, name, schema) { mutableStateOf("") }
    var selectSql by remember(connId, kind, name, schema) { mutableStateOf<String?>(null) }
    var insertSql by remember(connId, kind, name, schema) { mutableStateOf<String?>(null) }
    var loading by remember(connId, kind, name, schema) { mutableStateOf(true) }

    LaunchedEffect(api, connId, kind, name, schema, database) {
        if (api == null || connId.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            api.dbObjectSql(
                connectionId = connId,
                kind = kind,
                name = name,
                schema = schema,
                args = args,
                table = table,
                database = database,
            )
        }.onSuccess { res ->
            createSql = res["create"].string.orEmpty()
            selectSql = res["select"].string
            insertSql = res["insert"].string
            loading = false
        }.onFailure {
            loading = false
        }
    }

    if (loading) {
        val tokens = LocalPpmTokens.current
        Box(Modifier.fillMaxSize().background(tokens.bgSolid.color), contentAlignment = Alignment.Center) {
            BasicText("Loading object SQL…", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        return
    }

    DbSqlObjectContent(
        objectName = name,
        createSql = createSql,
        selectSql = selectSql,
        insertSql = insertSql,
        onOpenInQuery = { sqlText ->
            model.openWorkspaceTab(
                type = "db-query",
                title = "Query · $name",
                metadata = buildJsonObject {
                    put("connectionId", connId)
                    if (!database.isNullOrBlank()) put("database", database)
                    put("initialSql", sqlText)
                },
            )
        },
    )
}

@Composable
internal fun DbSqlObjectContent(
    objectName: String,
    createSql: String,
    selectSql: String? = null,
    insertSql: String? = null,
    onOpenInQuery: (String) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var activeKind by remember { mutableStateOf("CREATE") }
    val activeScript = when (activeKind) {
        "SELECT" -> selectSql ?: createSql
        "INSERT" -> insertSql ?: createSql
        else -> createSql
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("db-sql-object-tab"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    objectName,
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                SettingsButton(
                    label = "CREATE",
                    primary = activeKind == "CREATE",
                    modifier = Modifier.testTag("db-sql-tab-create"),
                    onClick = { activeKind = "CREATE" },
                )
                if (!selectSql.isNullOrBlank()) {
                    SettingsButton(
                        label = "SELECT",
                        primary = activeKind == "SELECT",
                        modifier = Modifier.testTag("db-sql-tab-select"),
                        onClick = { activeKind = "SELECT" },
                    )
                }
                if (!insertSql.isNullOrBlank()) {
                    SettingsButton(
                        label = "INSERT",
                        primary = activeKind == "INSERT",
                        modifier = Modifier.testTag("db-sql-tab-insert"),
                        onClick = { activeKind = "INSERT" },
                    )
                }
            }
            SettingsButton(
                label = "Open in Query",
                primary = true,
                modifier = Modifier.testTag("db-sql-open-query"),
                onClick = { onOpenInQuery(activeScript) },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            BasicText(
                activeScript,
                modifier = Modifier.testTag("db-sql-script-text"),
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 18f).copy(color = tokens.text.color),
                softWrap = mobile,
            )
        }
    }
}

// ── 9a.5 Database Login Dialog (`db-login-dialog.tsx`) ──────────────────────

@Composable
internal fun DbLoginDialogContent(
    prompt: DbLoginPromptInfo,
    errorMessage: String? = null,
    errorDetails: String? = null,
    onSubmit: (user: String?, password: String) -> Unit,
    onCancel: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    var user by remember(prompt) { mutableStateOf(prompt.user) }
    var password by remember(prompt) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var localError by remember(prompt, errorMessage) { mutableStateOf<String?>(null) }
    val displayedError = localError ?: errorMessage

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(14.dp)
            .testTag("db-login-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            "Database Log In — ${prompt.name}",
            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
        )
        if (prompt.askUser) {
            SettingsTextInput(
                value = user,
                onValueChange = { user = it },
                placeholder = "User",
                mono = true,
                modifier = Modifier.fillMaxWidth().testTag("db-login-user-input"),
            )
        } else if (prompt.user.isNotEmpty()) {
            BasicText(
                "User: ${prompt.user}",
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsTextInput(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                mono = showPassword,
                modifier = Modifier.weight(1f).testTag("db-login-password-input"),
            )
            SettingsButton(
                label = if (showPassword) "Hide" else "Show",
                modifier = Modifier.testTag("db-login-toggle-show"),
                onClick = { showPassword = !showPassword },
            )
        }

        if (!displayedError.isNullOrBlank()) {
            BasicText(
                displayedError,
                modifier = Modifier.testTag("db-login-error"),
                style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.error.color),
            )
            if (localError == null && !errorDetails.isNullOrBlank()) {
                SettingsButton(
                    label = if (showDetails) "Hide Details" else "Details",
                    modifier = Modifier.testTag("db-login-details-toggle"),
                    onClick = { showDetails = !showDetails },
                )
                if (showDetails) {
                    BasicText(
                        errorDetails,
                        modifier = Modifier.testTag("db-login-error-details"),
                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(
                label = "Log In",
                primary = true,
                modifier = Modifier.testTag("db-login-submit-btn"),
                onClick = {
                    if (prompt.askUser && user.trim().isEmpty()) {
                        localError = "Enter the user name."
                    } else {
                        localError = null
                        onSubmit(if (prompt.askUser) user.trim() else null, password)
                    }
                },
            )
            SettingsButton(
                label = "Cancel",
                modifier = Modifier.testTag("db-login-cancel-btn"),
                onClick = onCancel,
            )
        }
    }
}
