package com.hienlh.ppm.app.ui.database

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.client.DbLoginCoordinator
import com.hienlh.ppm.client.DbLoginFailedException
import com.hienlh.ppm.client.DbLoginPromptInfo
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.dbChangesetApply
import com.hienlh.ppm.client.dbChangesetPreview
import com.hienlh.ppm.client.dbConnections
import com.hienlh.ppm.client.dbDownloadGridExportTicket
import com.hienlh.ppm.client.dbGridCount
import com.hienlh.ppm.client.dbGridPage
import com.hienlh.ppm.client.dbImpExpDeleteUpload
import com.hienlh.ppm.client.dbImpExpDownloadTicket
import com.hienlh.ppm.client.dbImpExpExport
import com.hienlh.ppm.client.dbImpExpImport
import com.hienlh.ppm.client.dbImpExpJobStatus
import com.hienlh.ppm.client.dbImpExpStopJob
import com.hienlh.ppm.client.dbImpExpUploadPreview
import com.hienlh.ppm.client.dbImpExpUploadRaw
import com.hienlh.ppm.client.dbListDatabases
import com.hienlh.ppm.client.dbListObjects
import com.hienlh.ppm.client.dbLoginConnection
import com.hienlh.ppm.client.dbStructureApply
import com.hienlh.ppm.client.dbStructurePreview
import com.hienlh.ppm.client.dbTableSchema
import com.hienlh.ppm.client.dbTableStructure
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
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.database.CSV_DELIMITERS
import com.hienlh.ppm.state.database.ColumnMapEntry
import com.hienlh.ppm.state.database.DEFAULT_SHOWN_LEVELS
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DbForeignKey
import com.hienlh.ppm.state.database.DbObjectItem
import com.hienlh.ppm.state.database.DbObjectListState
import com.hienlh.ppm.state.database.DbTableStructure
import com.hienlh.ppm.state.database.GRID_PAGE_SIZE
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.GridSortItem
import com.hienlh.ppm.state.database.IMPEXP_FILE_FORMATS
import com.hienlh.ppm.state.database.IMPORT_ACTIONS
import com.hienlh.ppm.state.database.ImpExpDatabase
import com.hienlh.ppm.state.database.ImpExpForm
import com.hienlh.ppm.state.database.ImpExpJobRef
import com.hienlh.ppm.state.database.ImpExpJobStatus
import com.hienlh.ppm.state.database.ImpExpMessage
import com.hienlh.ppm.state.database.ImpExpOutputFile
import com.hienlh.ppm.state.database.ImpExpRow
import com.hienlh.ppm.state.database.ImpExpUpload
import com.hienlh.ppm.state.database.ImportPreviewResult
import com.hienlh.ppm.state.database.MESSAGE_LEVELS
import com.hienlh.ppm.state.database.NO_FILTERS
import com.hienlh.ppm.state.database.OUTCOME_LABEL
import com.hienlh.ppm.state.database.RowCountState
import com.hienlh.ppm.state.database.StatementOutcome
import com.hienlh.ppm.state.database.StructureChange
import com.hienlh.ppm.state.database.StructureFailure
import com.hienlh.ppm.state.database.StructurePreview
import com.hienlh.ppm.state.database.TEMPLATE_SOURCE
import com.hienlh.ppm.state.database.TableModel
import com.hienlh.ppm.state.database.TableModelColumn
import com.hienlh.ppm.state.database.TableModelForeignKey
import com.hienlh.ppm.state.database.TableModelIndex
import com.hienlh.ppm.state.database.TableModelIndexColumn
import com.hienlh.ppm.state.database.TableModelPrimaryKey
import com.hienlh.ppm.state.database.TableModelUnique
import com.hienlh.ppm.state.database.TreeConnection
import com.hienlh.ppm.state.database.activeRows
import com.hienlh.ppm.state.database.addTables
import com.hienlh.ppm.state.database.addUploads
import com.hienlh.ppm.state.database. applySortAction
import com.hienlh.ppm.state.database.autoConstraintName
import com.hienlh.ppm.state.database.backupTableName
import com.hienlh.ppm.state.database.blankColumn
import com.hienlh.ppm.state.database.buildChangesetRequestJson
import com.hienlh.ppm.state.database.buildGridRequestJson
import com.hienlh.ppm.state.database.columnDefinitions
import com.hienlh.ppm.state.database.columnMapProblem
import com.hienlh.ppm.state.database.columnNames
import com.hienlh.ppm.state.database.columnProblems
import com.hienlh.ppm.state.database.columnRows
import com.hienlh.ppm.state.database.columnsLinkText
import com.hienlh.ppm.state.database.computeRowCountView
import com.hienlh.ppm.state.database.confirmedColumnMap
import com.hienlh.ppm.state.database.dataTypesFor
import com.hienlh.ppm.state.database.databaseExportForm
import com.hienlh.ppm.state.database.declaredType
import com.hienlh.ppm.state.database.defaultRowTarget
import com.hienlh.ppm.state.database.editorColumnRows
import com.hienlh.ppm.state.database.enginesFor
import com.hienlh.ppm.state.database.exportRequest
import com.hienlh.ppm.state.database.failureOf
import com.hienlh.ppm.state.database.filterMessages
import com.hienlh.ppm.state.database.fkActionChoices
import com.hienlh.ppm.state.database.foreignKeyProblems
import com.hienlh.ppm.state.database.foreignKeyRow
import com.hienlh.ppm.state.database.foreignKeyRows
import com.hienlh.ppm.state.database.formatImpExpDuration
import com.hienlh.ppm.state.database.formatRowEstimate
import com.hienlh.ppm.state.database.gridExportForm
import com.hienlh.ppm.state.database.impExpTitle
import com.hienlh.ppm.state.database.importFormatOfFileName
import com.hienlh.ppm.state.database.importIntoForm
import com.hienlh.ppm.state.database.importRequest
import com.hienlh.ppm.state.database.indexRows
import com.hienlh.ppm.state.database.isImportForm
import com.hienlh.ppm.state.database.isPrimaryKeyColumn
import com.hienlh.ppm.state.database.itemStatusText
import com.hienlh.ppm.state.database.itemsByRow
import com.hienlh.ppm.state.database.keyProblems
import com.hienlh.ppm.state.database.levelCounts
import com.hienlh.ppm.state.database.messageClock
import com.hienlh.ppm.state.database.messageLines
import com.hienlh.ppm.state.database.modelColumnDefinitions
import com.hienlh.ppm.state.database.modelColumnNames
import com.hienlh.ppm.state.database.modelFromStructure
import com.hienlh.ppm.state.database.mysqlIndexTypeChoices
import com.hienlh.ppm.state.database.newImpExpForm
import com.hienlh.ppm.state.database.newItemId
import com.hienlh.ppm.state.database.newTableModel
import com.hienlh.ppm.state.database.newerPreviewOf
import com.hienlh.ppm.state.database.nextColumnNumber
import com.hienlh.ppm.state.database.nextQueryNumber
import com.hienlh.ppm.state.database.ownIndexes
import com.hienlh.ppm.state.database.parseChangesetApplyResult
import com.hienlh.ppm.state.database.parseChangesetPreview
import com.hienlh.ppm.state.database.parseDbObjectList
import com.hienlh.ppm.state.database.parseDbTableStructure
import com.hienlh.ppm.state.database.parseGridColumnsSchema
import com.hienlh.ppm.state.database.parseGridResponse
import com.hienlh.ppm.state.database.parseImpExpJobStatus
import com.hienlh.ppm.state.database.parseImportPreview
import com.hienlh.ppm.state.database.parseStructurePreview
import com.hienlh.ppm.state.database.parseTreeConnections
import com.hienlh.ppm.state.database.patchUpload
import com.hienlh.ppm.state.database.primaryKeyHasName
import com.hienlh.ppm.state.database.primaryKeyRow
import com.hienlh.ppm.state.database.queryTabMetadata
import com.hienlh.ppm.state.database.removeColumns
import com.hienlh.ppm.state.database.removeItem
import com.hienlh.ppm.state.database.removeRow
import com.hienlh.ppm.state.database.resetColumnMap
import com.hienlh.ppm.state.database.rowSourceLabel
import com.hienlh.ppm.state.database.rowTarget
import com.hienlh.ppm.state.database.runBlocker
import com.hienlh.ppm.state.database.sameTableModel
import com.hienlh.ppm.state.database.setDatabase
import com.hienlh.ppm.state.database.setSourceType
import com.hienlh.ppm.state.database.setTables
import com.hienlh.ppm.state.database.setTargetType
import com.hienlh.ppm.state.database.startingStatus
import com.hienlh.ppm.state.database.statementReports
import com.hienlh.ppm.state.database.structureApplyRequestJson
import com.hienlh.ppm.state.database.structurePreviewRequestJson
import com.hienlh.ppm.state.database.tableModelProblems
import com.hienlh.ppm.state.database.tableReferences
import com.hienlh.ppm.state.database.uid
import com.hienlh.ppm.state.database.uniqueRows
import com.hienlh.ppm.state.database.updateRow
import com.hienlh.ppm.state.database.uploadPreviewRequest
import com.hienlh.ppm.state.database.uploadProblem
import com.hienlh.ppm.state.database.upsertColumn
import com.hienlh.ppm.state.database.upsertForeignKey
import com.hienlh.ppm.state.database.upsertIndex
import com.hienlh.ppm.state.database.upsertUnique
import com.hienlh.ppm.state.database.withMysqlIndexType
import com.hienlh.ppm.state.database.withTableName
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun copyTextPlain(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun dbDialectForType(dbType: String?): String = when (dbType?.lowercase()) {
    "mysql", "mariadb" -> "mysql"
    "sqlite" -> "sqlite"
    else -> "postgres"
}

// ══════════════════════════════════════════════════════════════════════════════
// 9f.1 & 9f.2 Database Structure Tab & Table Structure Editor (`db-structure`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun DatabaseStructureTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val meta = tab.metadata

    val connId = (meta["connectionId"] as? JsonPrimitive)?.content ?: "1"
    val connName = meta["connectionName"]?.string ?: "Postgres"
    val database = meta["database"]?.string?.takeIf { it.isNotBlank() && connId != "file" }
    val filePath = meta["filePath"]?.string
    val project = meta["project"]?.string ?: tab.projectId ?: meta["projectName"]?.string
    val dbType = meta["dbType"]?.string ?: if (connId == "file") "sqlite" else "postgres"
    val dialect = dbDialectForType(dbType)
    val initialTableName = meta["tableName"]?.string.orEmpty()
    val schemaName = meta["schema"]?.string?.takeIf { it.isNotBlank() }
    val initialIsNewTable = meta["newTable"]?.bool == true || initialTableName.isBlank()
    val readonly = meta["readonly"]?.bool == true

    var structure by remember(connId, database, filePath, schemaName, initialTableName) {
        mutableStateOf<DbTableStructure?>(null)
    }
    var isNewTable by remember(connId, database, filePath, schemaName, initialTableName, initialIsNewTable) {
        mutableStateOf(initialIsNewTable)
    }
    var loading by remember { mutableStateOf(!initialIsNewTable) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var activeLoginPrompt by remember { mutableStateOf<DbLoginPromptInfo?>(null) }
    var activeLoginDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var loginErrorDetails by remember { mutableStateOf<String?>(null) }
    val loginCoordinator = remember {
        DbLoginCoordinator { prompt ->
            val deferred = CompletableDeferred<Boolean>()
            loginError = null
            loginErrorDetails = null
            activeLoginDeferred = deferred
            activeLoginPrompt = prompt
            deferred.await()
        }
    }

    suspend fun loadStructure(targetTable: String = structure?.table ?: initialTableName) {
        val client = api ?: return
        if (targetTable.isBlank()) return
        loading = true
        errorMessage = null
        runCatching {
            client.dbTableStructure(
                connectionId = connId,
                table = targetTable,
                schema = schemaName,
                database = database,
                path = filePath,
                project = project,
                loginCoordinator = loginCoordinator,
            )
        }.onSuccess { obj ->
            structure = parseDbTableStructure(obj)
            isNewTable = false
        }.onFailure { err ->
            errorMessage = err.message ?: "Failed to load table structure"
        }
        loading = false
    }

    LaunchedEffect(api, connId, database, filePath, schemaName, initialTableName, initialIsNewTable) {
        if (api != null && !initialIsNewTable && initialTableName.isNotBlank()) {
            loadStructure(initialTableName)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                        runCatching { client.dbLoginConnection(cid, password = pass, user = user) }
                            .onSuccess {
                                activeLoginPrompt = null
                                activeLoginDeferred?.complete(true)
                            }
                            .onFailure { err ->
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

        DatabaseStructureTabContent(
            structure = structure,
            dialect = structure?.dialect ?: dialect,
            dbType = dbType,
            schema = structure?.schema ?: schemaName,
            database = database,
            connectionName = connName,
            isNewTable = isNewTable,
            readonly = readonly,
            loading = loading,
            errorMessage = errorMessage,
            onRefresh = {
                scope.launch { loadStructure() }
            },
            onStartNewTable = {
                isNewTable = true
                structure = null
            },
            onOpenDataTab = { tbl, sch ->
                model.openWorkspaceTab(
                    type = "database",
                    title = tbl,
                    metadata = buildJsonObject {
                        put("connectionId", connId)
                        put("connectionName", connName)
                        if (database != null) put("database", database)
                        if (filePath != null) put("filePath", filePath)
                        if (project != null) put("project", project)
                        put("dbType", dbType)
                        put("tableName", tbl)
                        if (sch != null) put("schema", sch)
                        put("readonly", readonly)
                    },
                )
            },
            onOpenSqlTab = { tbl, sch, kind ->
                model.openWorkspaceTab(
                    type = "db-sql",
                    title = "$tbl · SQL",
                    metadata = buildJsonObject {
                        put("connectionId", connId)
                        put("connectionName", connName)
                        if (database != null) put("database", database)
                        if (filePath != null) put("filePath", filePath)
                        if (project != null) put("project", project)
                        put("dbType", dbType)
                        put("objectKind", kind)
                        put("objectName", tbl)
                        if (sch != null) put("schema", sch)
                    },
                )
            },
            onOpenQueryTab = { sql, title ->
                val existingTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
                val qNum = nextQueryNumber(existingTabs)
                model.openWorkspaceTab(
                    type = "db-query",
                    title = title.ifBlank { "Query $qNum" },
                    metadata = queryTabMetadata(
                        sql = sql,
                        queryNumber = qNum,
                        connectionId = connId.toIntOrNull(),
                        connectionName = connName,
                        dbType = dbType,
                        database = database,
                        dbFilePath = filePath,
                        dbFileProject = project,
                    ),
                )
            },
            onPreviewChange = { change ->
                val client = api ?: error("Not connected")
                val obj = client.dbStructurePreview(
                    connectionId = connId,
                    body = structurePreviewRequestJson(change),
                    database = database,
                    path = filePath,
                    project = project,
                    loginCoordinator = loginCoordinator,
                )
                parseStructurePreview(obj, defaultDialect = dialect) ?: error("Invalid structure preview response")
            },
            onApplyChange = { change, sql, allowRecreate ->
                val client = api ?: error("Not connected")
                val body = structureApplyRequestJson(change, sql, allowRecreate)
                client.dbStructureApply(
                    connectionId = connId,
                    body = body,
                    database = database,
                    path = filePath,
                    project = project,
                    loginCoordinator = loginCoordinator,
                )
            },
            onAppliedModel = { savedModel ->
                isNewTable = false
                scope.launch { loadStructure(savedModel.name) }
            },
        )
    }
}

@Composable
internal fun DatabaseStructureTabContent(
    structure: DbTableStructure?,
    dialect: String = "postgres",
    dbType: String = "postgres",
    schema: String? = "public",
    database: String? = null,
    connectionName: String = "Postgres",
    isNewTable: Boolean = false,
    readonly: Boolean = false,
    loading: Boolean = false,
    errorMessage: String? = null,
    availableObjects: List<DbObjectItem> = emptyList(),
    onRefresh: () -> Unit = {},
    onStartNewTable: () -> Unit = {},
    onOpenDataTab: (table: String, schema: String?) -> Unit = { _, _ -> },
    onOpenSqlTab: (name: String, schema: String?, kind: String) -> Unit = { _, _, _ -> },
    onOpenQueryTab: (sql: String, title: String) -> Unit = { _, _ -> },
    onPreviewChange: suspend (StructureChange) -> StructurePreview = {
        StructurePreview(
            dialect = dialect,
            transactionalDdl = dialect != "mysql",
            recreate = false,
            statements = emptyList(),
            sql = "-- DDL preview",
        )
    },
    onApplyChange: suspend (change: StructureChange, sql: String, allowRecreate: Boolean) -> JsonObject = { _, _, _ ->
        buildJsonObject { put("applied", 1) }
    },
    onAppliedModel: (TableModel) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current

    val baseModel = remember(structure, isNewTable, dialect, schema) {
        when {
            isNewTable -> newTableModel(dialect, schema)
            structure != null -> modelFromStructure(structure)
            else -> newTableModel(dialect, schema)
        }
    }
    var draftModel by remember(baseModel) { mutableStateOf(baseModel) }
    val isDirty = remember(baseModel, draftModel) { !sameTableModel(baseModel, draftModel) }
    val problems = remember(draftModel) { tableModelProblems(draftModel) }
    val canEdit = !mobile && !readonly && (isNewTable || structure?.kind == "table" || structure == null)

    var selectedColIds by remember(baseModel) { mutableStateOf<Set<String>>(emptySet()) }
    var editingColumn by remember { mutableStateOf<Pair<TableModelColumn, Boolean>?>(null) } // (col, isNew)
    var showPkDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Pair<TableModelIndex, Boolean>?>(null) }
    var editingUnique by remember { mutableStateOf<Pair<TableModelUnique, Boolean>?>(null) }
    var editingFk by remember { mutableStateOf<Pair<TableModelForeignKey, Boolean>?>(null) }
    var activeSaveChange by remember { mutableStateOf<StructureChange?>(null) }
    var copyBanner by remember { mutableStateOf<String?>(null) }

    // Phone guard (`§9f.2`): new table or dirty structure editor prompts "Open on a larger screen"
    if (mobile && (isNewTable || isDirty)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .padding(24.dp)
                .testTag("db-structure-phone-larger-screen"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.FolderTree.vector, 28.dp, tokens.text3.color)
                BasicText(
                    "Open on a larger screen",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                )
                BasicText(
                    "Creating or editing table structure requires a tablet or desktop screen.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        }
        return
    }

    fun triggerSaveDialog() {
        if ((!isDirty && !isNewTable) || problems.isNotEmpty()) return
        activeSaveChange = if (isNewTable) {
            StructureChange.Create(database = database, target = draftModel)
        } else {
            StructureChange.Alter(database = database, base = baseModel, current = draftModel)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && (ev.isCtrlPressed || ev.isMetaPressed) && ev.key == Key.S) {
                    if (canEdit) triggerSaveDialog()
                    true
                } else false
            }
            .testTag(if (mobile) "db-structure-phone-readonly" else "db-structure-editor"),
    ) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val titleLabel = if (isNewTable) "New table (${draftModel.name})" else (structure?.table ?: draftModel.name)
            BasicText(
                "$titleLabel · Structure",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                modifier = Modifier.testTag("db-structure-title"),
            )
            if (isDirty) {
                BasicText(
                    "● Unsaved",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.warning.color),
                    modifier = Modifier.testTag("db-structure-dirty-badge"),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (!isNewTable && structure != null) {
                SettingsButton(
                    label = "Data ↗",
                    modifier = Modifier.testTag("db-structure-open-data-btn"),
                    onClick = { onOpenDataTab(structure.table, structure.schema) },
                )
                SettingsButton(
                    label = "SQL ↗",
                    modifier = Modifier.testTag("db-structure-open-sql-btn"),
                    onClick = { onOpenSqlTab(structure.table, structure.schema, structure.kind) },
                )
            }
            if (canEdit) {
                SettingsButton(
                    label = "New table",
                    modifier = Modifier.testTag("db-structure-new-table-btn"),
                    onClick = onStartNewTable,
                )
                if (isDirty) {
                    SettingsButton(
                        label = "Reset",
                        modifier = Modifier.testTag("db-structure-reset-btn"),
                        onClick = {
                            draftModel = baseModel
                            selectedColIds = emptySet()
                        },
                    )
                }
                SettingsButton(
                    label = "Save",
                    primary = (isDirty || isNewTable) && problems.isEmpty(),
                    modifier = Modifier.testTag("db-structure-save-btn"),
                    onClick = { triggerSaveDialog() },
                )
            }
            SettingsButton(
                label = "Refresh",
                modifier = Modifier.testTag("db-structure-refresh-btn"),
                onClick = onRefresh,
            )
        }

        if (copyBanner != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.accent.opacity(0.12).color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("db-structure-copy-banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    copyBanner.orEmpty(),
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text.color),
                )
                SettingsButton(label = "Dismiss", onClick = { copyBanner = null })
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("Loading table structure…", style = ppmText(12f).copy(color = tokens.text3.color))
            }
            return@Column
        }
        if (errorMessage != null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                BasicText(errorMessage, style = ppmText(12f).copy(color = tokens.error.color))
            }
            return@Column
        }

        // Validation problems banner
        if (canEdit && problems.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.12).color)
                    .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                    .padding(10.dp)
                    .testTag("db-structure-problems-banner"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (p in problems) {
                    BasicText(
                        "• ${p.message}",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Table Header Section (Editable on Tablet)
            if (canEdit) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        "TABLE PROPERTIES",
                        style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicText("Table name", style = ppmText(11f).copy(color = tokens.text2.color))
                            SettingsTextInput(
                                value = draftModel.name,
                                onValueChange = { draftModel = withTableName(draftModel, it) },
                                placeholder = "table_name",
                                modifier = Modifier.fillMaxWidth().testTag("db-structure-table-name-input"),
                            )
                        }
                        if (dialect != "sqlite") {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                BasicText("Comment", style = ppmText(11f).copy(color = tokens.text2.color))
                                SettingsTextInput(
                                    value = draftModel.comment,
                                    onValueChange = { draftModel = draftModel.copy(comment = it) },
                                    placeholder = "Table comment",
                                    modifier = Modifier.fillMaxWidth().testTag("db-structure-table-comment-input"),
                                )
                            }
                        }
                    }
                    if (dialect == "mysql") {
                        val engines = enginesFor(dbType)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText("Engine:", style = ppmText(11f).copy(color = tokens.text2.color))
                            for (eng in engines) {
                                SettingsButton(
                                    label = eng,
                                    primary = draftModel.tableEngine.equals(eng, ignoreCase = true),
                                    modifier = Modifier.testTag("db-structure-engine-btn-$eng"),
                                    onClick = { draftModel = draftModel.copy(tableEngine = eng) },
                                )
                            }
                        }
                    }
                    if (dialect == "sqlite") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsButton(
                                label = "${if (draftModel.withoutRowId) "☑" else "☐"} WITHOUT ROWID",
                                primary = draftModel.withoutRowId,
                                modifier = Modifier.testTag("db-structure-without-rowid-checkbox"),
                                onClick = { draftModel = draftModel.copy(withoutRowId = !draftModel.withoutRowId) },
                            )
                            SettingsButton(
                                label = "${if (draftModel.strict) "☑" else "☐"} STRICT",
                                primary = draftModel.strict,
                                modifier = Modifier.testTag("db-structure-strict-checkbox"),
                                onClick = { draftModel = draftModel.copy(strict = !draftModel.strict) },
                            )
                        }
                    }
                }
            }

            // 2. Columns Section
            val colRows = remember(draftModel) { editorColumnRows(draftModel) }
            if (!mobile || colRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-section-columns"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "COLUMNS (${colRows.size})",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (canEdit) {
                            SettingsButton(
                                label = "+ Add column",
                                primary = true,
                                modifier = Modifier.testTag("db-structure-add-col-btn"),
                                onClick = {
                                    val nextNum = nextColumnNumber(draftModel)
                                    editingColumn = blankColumn(dialect, nextNum) to true
                                },
                            )
                            if (selectedColIds.isNotEmpty()) {
                                SettingsButton(
                                    label = "Remove (${selectedColIds.size})",
                                    modifier = Modifier.testTag("db-structure-remove-cols-btn"),
                                    onClick = {
                                        draftModel = removeColumns(draftModel, selectedColIds)
                                        selectedColIds = emptySet()
                                    },
                                )
                            }
                        }
                        if (!mobile) {
                            SettingsButton(
                                label = "Copy names",
                                modifier = Modifier.testTag("db-structure-copy-names-btn"),
                                onClick = {
                                    val text = modelColumnNames(draftModel, selectedColIds)
                                    copyTextPlain(context, "Column names", text)
                                    copyBanner = "Copied column names: $text"
                                },
                            )
                            SettingsButton(
                                label = "Copy definitions",
                                modifier = Modifier.testTag("db-structure-copy-defs-btn"),
                                onClick = {
                                    val text = modelColumnDefinitions(draftModel, selectedColIds)
                                    copyTextPlain(context, "Column definitions", text)
                                    copyBanner = "Copied column definitions"
                                },
                            )
                        }
                    }

                    for (row in colRows) {
                        val isSelected = row.id in selectedColIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) tokens.accent.opacity(0.12).color else tokens.bgSolid.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("db-structure-col-row-${row.name}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!mobile) {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            selectedColIds = if (isSelected) selectedColIds - row.id else selectedColIds + row.id
                                        }
                                        .padding(2.dp)
                                        .testTag("db-structure-col-select-${row.name}"),
                                ) {
                                    BasicText(
                                        if (isSelected) "☑" else "☐",
                                        style = ppmText(12f).copy(color = tokens.text2.color),
                                    )
                                }
                            }
                            BasicText(
                                "${row.ordinal}.",
                                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                            )
                            if (row.isPrimaryKey) {
                                BasicText(
                                    "PK",
                                    style = ppmText(10f, FontWeight.Bold, FontKind.MONO).copy(color = tokens.warning.color),
                                    modifier = Modifier.testTag("db-structure-col-pk-badge-${row.name}"),
                                )
                            }
                            BasicText(
                                row.name,
                                style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color),
                                modifier = Modifier.weight(1f),
                            )
                            val modelCol = draftModel.columns.firstOrNull { it.id == row.id }
                            BasicText(
                                if (modelCol != null) declaredType(modelCol, dialect) else row.dataType,
                                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                            )
                            if (canEdit && modelCol != null) {
                                SettingsButton(
                                    label = if (row.notNull) "NOT NULL" else "NULL",
                                    primary = row.notNull,
                                    modifier = Modifier.testTag("db-structure-col-notnull-${row.name}"),
                                    onClick = {
                                        draftModel = upsertColumn(draftModel, modelCol.copy(notNull = !modelCol.notNull))
                                    },
                                )
                                SettingsButton(
                                    label = "Edit",
                                    modifier = Modifier.testTag("db-structure-col-edit-${row.name}"),
                                    onClick = { editingColumn = modelCol to false },
                                )
                                SettingsButton(
                                    label = "×",
                                    modifier = Modifier.testTag("db-structure-col-delete-${row.name}"),
                                    onClick = {
                                        draftModel = removeColumns(draftModel, setOf(row.id))
                                        selectedColIds = selectedColIds - row.id
                                    },
                                )
                            } else {
                                if (row.notNull) {
                                    BasicText("NOT NULL", style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text2.color))
                                }
                                if (row.defaultValue.isNotEmpty()) {
                                    BasicText("= ${row.defaultValue}", style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                                }
                            }
                        }
                    }
                }
            }

            // 3. Primary Key Section
            val pkRow = remember(draftModel) { primaryKeyRow(draftModel) }
            if (!mobile || pkRow != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-section-pk"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "PRIMARY KEY",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        if (canEdit) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SettingsButton(
                                    label = if (pkRow == null) "+ Add primary key" else "Edit",
                                    modifier = Modifier.testTag(if (pkRow == null) "db-structure-add-pk-btn" else "db-structure-edit-pk-btn"),
                                    onClick = { showPkDialog = true },
                                )
                                if (pkRow != null) {
                                    SettingsButton(
                                        label = "Remove",
                                        modifier = Modifier.testTag("db-structure-remove-pk-btn"),
                                        onClick = { draftModel = draftModel.copy(primaryKey = null) },
                                    )
                                }
                            }
                        }
                    }
                    if (pkRow != null) {
                        BasicText(
                            "${pkRow.constraintName}: (${pkRow.columns})",
                            style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
                            modifier = Modifier.testTag("db-structure-pk-summary"),
                        )
                    } else {
                        BasicText("No primary key", style = ppmText(11f).copy(color = tokens.text3.color))
                    }
                }
            }

            // 4. Indexes Section
            val idxRows = remember(draftModel) { indexRows(draftModel) }
            if (!mobile || idxRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-section-indexes"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "INDEXES (${idxRows.size})",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        if (canEdit) {
                            SettingsButton(
                                label = "+ Add index",
                                modifier = Modifier.testTag("db-structure-add-index-btn"),
                                onClick = {
                                    val firstCol = draftModel.columns.firstOrNull()
                                    val defaultName = autoConstraintName(
                                        "idx",
                                        draftModel.name,
                                        listOfNotNull(firstCol?.name),
                                    )
                                    editingIndex = TableModelIndex(
                                        id = newItemId("idx"),
                                        constraintName = defaultName,
                                        columns = listOfNotNull(firstCol?.let { TableModelIndexColumn(it.id, "ASC") }),
                                    ) to true
                                },
                            )
                        }
                    }
                    for (idxRow in idxRows) {
                        val modelIdx = draftModel.indexes.firstOrNull { it.id == idxRow.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.bgSolid.color)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("db-structure-index-row-${idxRow.constraintName}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                BasicText(
                                    "${idxRow.constraintName} (${idxRow.columns})",
                                    style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                )
                                if (idxRow.detail.isNotEmpty()) {
                                    BasicText(
                                        idxRow.detail,
                                        style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                    )
                                }
                            }
                            if (canEdit && modelIdx != null) {
                                SettingsButton(
                                    label = "Edit",
                                    modifier = Modifier.testTag("db-structure-edit-index-${idxRow.constraintName}"),
                                    onClick = { editingIndex = modelIdx to false },
                                )
                                SettingsButton(
                                    label = "×",
                                    modifier = Modifier.testTag("db-structure-remove-index-${idxRow.constraintName}"),
                                    onClick = {
                                        draftModel = draftModel.copy(
                                            indexes = removeItem(draftModel.indexes, modelIdx.id) { it.id },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 5. Uniques Section
            val uqRows = remember(draftModel) { uniqueRows(draftModel) }
            if (!mobile || uqRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-section-uniques"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "UNIQUES (${uqRows.size})",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        if (canEdit) {
                            SettingsButton(
                                label = "+ Add unique",
                                modifier = Modifier.testTag("db-structure-add-unique-btn"),
                                onClick = {
                                    val firstCol = draftModel.columns.firstOrNull()
                                    val defaultName = autoConstraintName(
                                        "uq",
                                        draftModel.name,
                                        listOfNotNull(firstCol?.name),
                                    )
                                    editingUnique = TableModelUnique(
                                        id = newItemId("uq"),
                                        constraintName = defaultName,
                                        columnIds = listOfNotNull(firstCol?.id),
                                    ) to true
                                },
                            )
                        }
                    }
                    for (uqRow in uqRows) {
                        val modelUq = draftModel.uniques.firstOrNull { it.id == uqRow.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.bgSolid.color)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("db-structure-unique-row-${uqRow.constraintName}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                "${uqRow.constraintName} (${uqRow.columns})",
                                style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                modifier = Modifier.weight(1f),
                            )
                            if (canEdit && modelUq != null) {
                                SettingsButton(
                                    label = "Edit",
                                    modifier = Modifier.testTag("db-structure-edit-unique-${uqRow.id}"),
                                    onClick = { editingUnique = modelUq to false },
                                )
                                SettingsButton(
                                    label = "×",
                                    modifier = Modifier.testTag("db-structure-remove-unique-${uqRow.id}"),
                                    onClick = {
                                        draftModel = draftModel.copy(
                                            uniques = removeItem(draftModel.uniques, modelUq.id) { it.id },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 6. Foreign Keys Section
            val fkRows = remember(draftModel) { foreignKeyRows(draftModel) }
            if (!mobile || fkRows.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-section-fks"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "FOREIGN KEYS (${fkRows.size})",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        if (canEdit) {
                            SettingsButton(
                                label = "+ Add foreign key",
                                modifier = Modifier.testTag("db-structure-add-fk-btn"),
                                onClick = {
                                    val firstCol = draftModel.columns.firstOrNull()
                                    val defaultName = autoConstraintName(
                                        "fk",
                                        draftModel.name,
                                        listOfNotNull(firstCol?.name),
                                    )
                                    editingFk = TableModelForeignKey(
                                        id = newItemId("fk"),
                                        constraintName = defaultName,
                                        columnIds = listOfNotNull(firstCol?.id),
                                        referencedSchema = draftModel.schema,
                                        referencedTable = draftModel.name,
                                        referencedColumns = listOf("id"),
                                    ) to true
                                },
                            )
                        }
                    }
                    for (fkRow in fkRows) {
                        val modelFk = draftModel.foreignKeys.firstOrNull { it.id == fkRow.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(tokens.bgSolid.color)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                                .testTag("db-structure-fk-row-${fkRow.constraintName}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                BasicText(
                                    "${fkRow.constraintName}: ${fkRow.columns}",
                                    style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BasicText(
                                        "→ ${fkRow.referencedLabel}",
                                        style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.accent.color),
                                        modifier = Modifier
                                            .clickable { onOpenDataTab(fkRow.referencedTable, fkRow.referencedSchema) }
                                            .testTag("db-structure-fk-link-${fkRow.referencedTable}"),
                                    )
                                    if (fkRow.onUpdate.isNotEmpty() || fkRow.onDelete.isNotEmpty()) {
                                        BasicText(
                                            "ON UPDATE ${fkRow.onUpdate.ifEmpty { "NO ACTION" }} · ON DELETE ${fkRow.onDelete.ifEmpty { "NO ACTION" }}",
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                        )
                                    }
                                }
                            }
                            if (canEdit && modelFk != null) {
                                SettingsButton(
                                    label = "Edit",
                                    modifier = Modifier.testTag("db-structure-edit-fk-${fkRow.id}"),
                                    onClick = { editingFk = modelFk to false },
                                )
                                SettingsButton(
                                    label = "×",
                                    modifier = Modifier.testTag("db-structure-remove-fk-${fkRow.id}"),
                                    onClick = {
                                        draftModel = draftModel.copy(
                                            foreignKeys = removeItem(draftModel.foreignKeys, modelFk.id) { it.id },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 7. Dependencies Section
            val deps = structure?.dependencies.orEmpty()
            if (deps.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-section-dependencies"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        "DEPENDENCIES (${deps.size})",
                        style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                    for (dep in deps) {
                        val obj = dep.`object`
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (obj.kind == "table" || obj.kind == "view" || obj.kind == "matview") {
                                        onOpenDataTab(obj.name, obj.schema)
                                    } else {
                                        onOpenSqlTab(obj.name, obj.schema, obj.kind)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("db-structure-dep-link-${obj.name}"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                if (dep.direction == "uses") "Uses →" else "Used by ←",
                                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                            )
                            BasicText(
                                "${obj.name} (${obj.kind})",
                                style = ppmText(12f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.accent.color),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Column Add/Edit Dialog (`db-structure-column-dialog`) ──
    val colTarget = editingColumn
    if (colTarget != null) {
        val (initialCol, isAdding) = colTarget
        var colDraft by remember(initialCol) { mutableStateOf(initialCol) }
        var pkMember by remember(initialCol, draftModel) {
            mutableStateOf(isPrimaryKeyColumn(draftModel, initialCol.id))
        }
        val colErrs = remember(draftModel, colDraft, isAdding) {
            columnProblems(draftModel, colDraft, editingId = if (isAdding) null else initialCol.id)
        }
        val typeChoices = remember(dialect) { dataTypesFor(dialect) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-structure-column-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(
                    if (isAdding) "Add column" else "Edit column ${initialCol.name}",
                    style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Column name", style = ppmText(11f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = colDraft.name,
                        onValueChange = { colDraft = colDraft.copy(name = it) },
                        placeholder = "column_name",
                        modifier = Modifier.fillMaxWidth().testTag("db-col-dialog-name-input"),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Data type", style = ppmText(11f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = colDraft.dataType,
                        onValueChange = { colDraft = colDraft.copy(dataType = it) },
                        placeholder = "varchar(255)",
                        modifier = Modifier.fillMaxWidth().testTag("db-col-dialog-type-input"),
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (tp in typeChoices.take(8)) {
                            SettingsButton(
                                label = tp,
                                primary = colDraft.dataType.equals(tp, ignoreCase = true),
                                modifier = Modifier.testTag("db-col-dialog-type-chip-$tp"),
                                onClick = { colDraft = colDraft.copy(dataType = tp) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsButton(
                        label = "${if (pkMember) "☑" else "☐"} Primary key",
                        primary = pkMember,
                        modifier = Modifier.testTag("db-col-dialog-pk-toggle"),
                        onClick = {
                            pkMember = !pkMember
                            if (pkMember) colDraft = colDraft.copy(notNull = true)
                        },
                    )
                    SettingsButton(
                        label = "${if (colDraft.notNull) "☑" else "☐"} Not null",
                        primary = colDraft.notNull,
                        modifier = Modifier.testTag("db-col-dialog-notnull-toggle"),
                        onClick = { colDraft = colDraft.copy(notNull = !colDraft.notNull) },
                    )
                    SettingsButton(
                        label = "${if (colDraft.autoIncrement) "☑" else "☐"} Autoincrement",
                        primary = colDraft.autoIncrement,
                        modifier = Modifier.testTag("db-col-dialog-autoinc-toggle"),
                        onClick = { colDraft = colDraft.copy(autoIncrement = !colDraft.autoIncrement) },
                    )
                    if (dialect == "mysql") {
                        SettingsButton(
                            label = "${if (colDraft.isUnsigned) "☑" else "☐"} Unsigned",
                            primary = colDraft.isUnsigned,
                            modifier = Modifier.testTag("db-col-dialog-unsigned-toggle"),
                            onClick = { colDraft = colDraft.copy(isUnsigned = !colDraft.isUnsigned) },
                        )
                        SettingsButton(
                            label = "${if (colDraft.isZeroFill) "☑" else "☐"} Zerofill",
                            primary = colDraft.isZeroFill,
                            modifier = Modifier.testTag("db-col-dialog-zerofill-toggle"),
                            onClick = {
                                val nextZf = !colDraft.isZeroFill
                                colDraft = colDraft.copy(
                                    isZeroFill = nextZf,
                                    isUnsigned = if (nextZf) true else colDraft.isUnsigned,
                                )
                            },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Default value (SQL expression)", style = ppmText(11f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = colDraft.defaultValue,
                        onValueChange = { colDraft = colDraft.copy(defaultValue = it) },
                        placeholder = "NULL or 'default'",
                        modifier = Modifier.fillMaxWidth().testTag("db-col-dialog-default-input"),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Computed expression", style = ppmText(11f).copy(color = tokens.text2.color))
                    SettingsTextInput(
                        value = colDraft.computedExpression,
                        onValueChange = { colDraft = colDraft.copy(computedExpression = it) },
                        placeholder = "price * quantity",
                        modifier = Modifier.fillMaxWidth().testTag("db-col-dialog-computed-input"),
                    )
                }
                if (dialect != "sqlite") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BasicText("Comment", style = ppmText(11f).copy(color = tokens.text2.color))
                        SettingsTextInput(
                            value = colDraft.comment,
                            onValueChange = { colDraft = colDraft.copy(comment = it) },
                            placeholder = "Column comment",
                            modifier = Modifier.fillMaxWidth().testTag("db-col-dialog-comment-input"),
                        )
                    }
                }
                if (colErrs.isNotEmpty()) {
                    Column(modifier = Modifier.testTag("db-col-dialog-problems")) {
                        for (e in colErrs) {
                            BasicText("• $e", style = ppmText(11f).copy(color = tokens.error.color))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SettingsButton(
                        label = "Cancel",
                        modifier = Modifier.testTag("db-col-dialog-cancel-btn"),
                        onClick = { editingColumn = null },
                    )
                    Spacer(Modifier.width(6.dp))
                    if (isAdding) {
                        SettingsButton(
                            label = "Save and next",
                            modifier = Modifier.testTag("db-col-dialog-save-next-btn"),
                            onClick = {
                                if (colErrs.isEmpty()) {
                                    val updated = upsertColumn(draftModel, colDraft, pkMember)
                                    draftModel = updated
                                    val nextNum = nextColumnNumber(updated)
                                    editingColumn = blankColumn(dialect, nextNum) to true
                                }
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    SettingsButton(
                        label = "Save",
                        primary = colErrs.isEmpty(),
                        modifier = Modifier.testTag("db-col-dialog-save-btn"),
                        onClick = {
                            if (colErrs.isEmpty()) {
                                draftModel = upsertColumn(draftModel, colDraft, pkMember)
                                editingColumn = null
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Primary Key Dialog (`db-structure-pk-dialog`) ──
    if (showPkDialog) {
        var pkName by remember(draftModel.primaryKey) {
            mutableStateOf(
                draftModel.primaryKey?.constraintName?.ifEmpty {
                    autoConstraintName("pk", draftModel.name, emptyList())
                } ?: autoConstraintName("pk", draftModel.name, emptyList()),
            )
        }
        var pkCols by remember(draftModel.primaryKey) {
            mutableStateOf(draftModel.primaryKey?.columnIds.orEmpty())
        }
        val pkErrs = remember(pkName, pkCols, draftModel) {
            keyProblems("Primary key", pkName, pkCols, requireName = false, model = draftModel, editingId = "pk")
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-structure-pk-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText("Primary key", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (primaryKeyHasName(dialect)) {
                    SettingsTextInput(
                        value = pkName,
                        onValueChange = { pkName = it },
                        placeholder = "Constraint name",
                        modifier = Modifier.fillMaxWidth().testTag("db-pk-dialog-name-input"),
                    )
                }
                for (c in draftModel.columns) {
                    val checked = c.id in pkCols
                    SettingsButton(
                        label = "${if (checked) "☑" else "☐"} ${c.name}",
                        primary = checked,
                        modifier = Modifier.testTag("db-pk-dialog-col-${c.name}"),
                        onClick = {
                            pkCols = if (checked) pkCols - c.id else pkCols + c.id
                        },
                    )
                }
                for (e in pkErrs) {
                    BasicText("• $e", style = ppmText(11f).copy(color = tokens.error.color))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", onClick = { showPkDialog = false })
                    Spacer(Modifier.width(6.dp))
                    SettingsButton(
                        label = "Save",
                        primary = pkErrs.isEmpty(),
                        modifier = Modifier.testTag("db-pk-dialog-save-btn"),
                        onClick = {
                            if (pkErrs.isEmpty()) {
                                draftModel = draftModel.copy(
                                    primaryKey = TableModelPrimaryKey(
                                        constraintName = if (primaryKeyHasName(dialect)) pkName else "",
                                        columnIds = pkCols,
                                    ),
                                )
                                showPkDialog = false
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Index Dialog (`db-structure-index-dialog`) ──
    val idxTarget = editingIndex
    if (idxTarget != null) {
        val (initialIdx, isAdding) = idxTarget
        var idxDraft by remember(initialIdx) { mutableStateOf(initialIdx) }
        val idxErrs = remember(idxDraft, draftModel, isAdding) {
            keyProblems(
                "Index",
                idxDraft.constraintName,
                idxDraft.columns.mapNotNull { it.columnId },
                requireName = true,
                model = draftModel,
                editingId = if (isAdding) null else initialIdx.id,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-structure-index-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText("Index", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                SettingsTextInput(
                    value = idxDraft.constraintName,
                    onValueChange = { idxDraft = idxDraft.copy(constraintName = it) },
                    placeholder = "index_name",
                    modifier = Modifier.fillMaxWidth().testTag("db-idx-dialog-name-input"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsButton(
                        label = "${if (idxDraft.isUnique) "☑" else "☐"} Unique",
                        primary = idxDraft.isUnique,
                        modifier = Modifier.testTag("db-idx-dialog-unique-toggle"),
                        onClick = { idxDraft = idxDraft.copy(isUnique = !idxDraft.isUnique) },
                    )
                    if (dialect == "mysql") {
                        for (tp in mysqlIndexTypeChoices(idxDraft.indexType)) {
                            val label = tp.ifEmpty { "BTREE" }
                            SettingsButton(
                                label = label,
                                primary = idxDraft.indexType.equals(tp, ignoreCase = true),
                                modifier = Modifier.testTag("db-idx-dialog-type-$label"),
                                onClick = {
                                    val (nextType, forcedUnique) = withMysqlIndexType(tp)
                                    idxDraft = idxDraft.copy(
                                        indexType = nextType,
                                        isUnique = forcedUnique ?: idxDraft.isUnique,
                                    )
                                },
                            )
                        }
                    }
                }
                if (dialect != "mysql") {
                    SettingsTextInput(
                        value = idxDraft.filter,
                        onValueChange = { idxDraft = idxDraft.copy(filter = it) },
                        placeholder = "Partial index WHERE condition (optional)",
                        modifier = Modifier.fillMaxWidth().testTag("db-idx-dialog-filter-input"),
                    )
                }
                for (c in draftModel.columns) {
                    val existing = idxDraft.columns.firstOrNull { it.columnId == c.id }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsButton(
                            label = "${if (existing != null) "☑" else "☐"} ${c.name}",
                            primary = existing != null,
                            modifier = Modifier.testTag("db-idx-dialog-col-${c.name}"),
                            onClick = {
                                val nextCols = if (existing != null) {
                                    idxDraft.columns.filter { it.columnId != c.id }
                                } else {
                                    idxDraft.columns + TableModelIndexColumn(c.id, "ASC")
                                }
                                idxDraft = idxDraft.copy(columns = nextCols)
                            },
                        )
                        if (existing != null) {
                            SettingsButton(
                                label = existing.order,
                                modifier = Modifier.testTag("db-idx-dialog-order-${c.name}"),
                                onClick = {
                                    val nextOrder = if (existing.order == "ASC") "DESC" else "ASC"
                                    idxDraft = idxDraft.copy(
                                        columns = idxDraft.columns.map {
                                            if (it.columnId == c.id) it.copy(order = nextOrder) else it
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                for (e in idxErrs) {
                    BasicText("• $e", style = ppmText(11f).copy(color = tokens.error.color))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", onClick = { editingIndex = null })
                    Spacer(Modifier.width(6.dp))
                    SettingsButton(
                        label = "Save",
                        primary = idxErrs.isEmpty(),
                        modifier = Modifier.testTag("db-idx-dialog-save-btn"),
                        onClick = {
                            if (idxErrs.isEmpty()) {
                                draftModel = upsertIndex(draftModel, idxDraft)
                                editingIndex = null
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Unique Constraint Dialog (`db-structure-unique-dialog`) ──
    val uqTarget = editingUnique
    if (uqTarget != null) {
        val (initialUq, isAdding) = uqTarget
        var uqDraft by remember(initialUq) { mutableStateOf(initialUq) }
        val uqErrs = remember(uqDraft, draftModel, isAdding) {
            keyProblems(
                "Unique constraint",
                uqDraft.constraintName,
                uqDraft.columnIds,
                requireName = false,
                model = draftModel,
                editingId = if (isAdding) null else initialUq.id,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-structure-unique-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText("Unique constraint", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                SettingsTextInput(
                    value = uqDraft.constraintName,
                    onValueChange = { uqDraft = uqDraft.copy(constraintName = it) },
                    placeholder = "Constraint name",
                    modifier = Modifier.fillMaxWidth().testTag("db-uq-dialog-name-input"),
                )
                for (c in draftModel.columns) {
                    val checked = c.id in uqDraft.columnIds
                    SettingsButton(
                        label = "${if (checked) "☑" else "☐"} ${c.name}",
                        primary = checked,
                        modifier = Modifier.testTag("db-uq-dialog-col-${c.name}"),
                        onClick = {
                            uqDraft = uqDraft.copy(
                                columnIds = if (checked) uqDraft.columnIds - c.id else uqDraft.columnIds + c.id,
                            )
                        },
                    )
                }
                for (e in uqErrs) {
                    BasicText("• $e", style = ppmText(11f).copy(color = tokens.error.color))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", onClick = { editingUnique = null })
                    Spacer(Modifier.width(6.dp))
                    SettingsButton(
                        label = "Save",
                        primary = uqErrs.isEmpty(),
                        modifier = Modifier.testTag("db-uq-dialog-save-btn"),
                        onClick = {
                            if (uqErrs.isEmpty()) {
                                draftModel = upsertUnique(draftModel, uqDraft)
                                editingUnique = null
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Foreign Key Dialog (`db-structure-fk-dialog`) ──
    val fkTarget = editingFk
    if (fkTarget != null) {
        val (initialFk, isAdding) = fkTarget
        var fkDraft by remember(initialFk) { mutableStateOf(initialFk) }
        val fkErrs = remember(fkDraft, draftModel, isAdding) {
            foreignKeyProblems(fkDraft, draftModel, editingId = if (isAdding) null else initialFk.id)
        }
        val actions = remember(fkDraft) { fkActionChoices(fkDraft) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-structure-fk-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText("Foreign key", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                SettingsTextInput(
                    value = fkDraft.constraintName,
                    onValueChange = { fkDraft = fkDraft.copy(constraintName = it) },
                    placeholder = "Constraint name",
                    modifier = Modifier.fillMaxWidth().testTag("db-fk-dialog-name-input"),
                )
                SettingsTextInput(
                    value = fkDraft.referencedTable,
                    onValueChange = { fkDraft = fkDraft.copy(referencedTable = it) },
                    placeholder = "Referenced table",
                    modifier = Modifier.fillMaxWidth().testTag("db-fk-dialog-ref-table-input"),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("ON DELETE:", style = ppmText(11f).copy(color = tokens.text2.color))
                    for (act in actions) {
                        val lbl = act.ifEmpty { "DEFAULT" }
                        SettingsButton(
                            label = lbl,
                            primary = fkDraft.onDeleteAction == act,
                            modifier = Modifier.testTag("db-fk-dialog-on-delete-$lbl"),
                            onClick = { fkDraft = fkDraft.copy(onDeleteAction = act) },
                        )
                    }
                }
                for (c in draftModel.columns) {
                    val idx = fkDraft.columnIds.indexOf(c.id)
                    val checked = idx >= 0
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsButton(
                            label = "${if (checked) "☑" else "☐"} ${c.name}",
                            primary = checked,
                            modifier = Modifier.testTag("db-fk-dialog-col-${c.name}"),
                            onClick = {
                                if (checked) {
                                    val nextCols = fkDraft.columnIds.filterIndexed { i, _ -> i != idx }
                                    val nextRefs = fkDraft.referencedColumns.filterIndexed { i, _ -> i != idx }
                                    fkDraft = fkDraft.copy(columnIds = nextCols, referencedColumns = nextRefs)
                                } else {
                                    fkDraft = fkDraft.copy(
                                        columnIds = fkDraft.columnIds + c.id,
                                        referencedColumns = fkDraft.referencedColumns + "id",
                                    )
                                }
                            },
                        )
                        if (checked) {
                            SettingsTextInput(
                                value = fkDraft.referencedColumns.getOrElse(idx) { "" },
                                onValueChange = { nextRef ->
                                    fkDraft = fkDraft.copy(
                                        referencedColumns = fkDraft.referencedColumns.mapIndexed { i, v ->
                                            if (i == idx) nextRef else v
                                        },
                                    )
                                },
                                placeholder = "Referenced column",
                                modifier = Modifier.weight(1f).testTag("db-fk-dialog-ref-col-${c.name}"),
                            )
                        }
                    }
                }
                for (e in fkErrs) {
                    BasicText("• $e", style = ppmText(11f).copy(color = tokens.error.color))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SettingsButton(label = "Cancel", onClick = { editingFk = null })
                    Spacer(Modifier.width(6.dp))
                    SettingsButton(
                        label = "Save",
                        primary = fkErrs.isEmpty(),
                        modifier = Modifier.testTag("db-fk-dialog-save-btn"),
                        onClick = {
                            if (fkErrs.isEmpty()) {
                                draftModel = upsertForeignKey(draftModel, fkDraft)
                                editingFk = null
                            }
                        },
                    )
                }
            }
        }
    }

    // ── Shared Structure Save / DDL Preview Dialog (`db-structure-save-dialog`) ──
    val saveChange = activeSaveChange
    if (saveChange != null) {
        StructureSaveDialog(
            initialChange = saveChange,
            onPreview = onPreviewChange,
            onApply = onApplyChange,
            onOpenScriptInQueryTab = { sql ->
                activeSaveChange = null
                onOpenQueryTab(sql, "${draftModel.name}_ddl.sql")
            },
            onDismiss = { activeSaveChange = null },
            onApplied = {
                activeSaveChange = null
                onAppliedModel(draftModel)
            },
        )
    }
    }
}

/**
 * Shared DDL Preview & Apply dialog (`StructureSaveDialog`), used by:
 * 1. Table Structure Editor (`Create` / `Alter`)
 * 2. Database Object Tree & Column Tree actions (`DropTable`, `TruncateTable`, `RenameTable`, `BackupTable`, `RenameColumn`, `DropColumn`)
 */
@Composable
fun StructureSaveDialog(
    initialChange: StructureChange,
    onPreview: suspend (StructureChange) -> StructurePreview,
    onApply: suspend (change: StructureChange, sql: String, allowRecreate: Boolean) -> JsonObject,
    onOpenScriptInQueryTab: (String) -> Unit,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()

    var nameInput by remember(initialChange) {
        mutableStateOf(
            when (initialChange) {
                is StructureChange.RenameTable -> initialChange.newName
                is StructureChange.BackupTable -> initialChange.backupName
                is StructureChange.RenameColumn -> initialChange.newName
                else -> ""
            },
        )
    }
    val currentChange = remember(initialChange, nameInput) {
        when (initialChange) {
            is StructureChange.RenameTable -> initialChange.copy(newName = nameInput.trim())
            is StructureChange.BackupTable -> initialChange.copy(backupName = nameInput.trim())
            is StructureChange.RenameColumn -> initialChange.copy(newName = nameInput.trim())
            else -> initialChange
        }
    }

    var preview by remember { mutableStateOf<StructurePreview?>(null) }
    var loadingPreview by remember { mutableStateOf(true) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var allowRecreate by remember { mutableStateOf(false) }
    var conflictNotice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<StructureFailure?>(null) }
    var applyError by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf(false) }

    LaunchedEffect(currentChange) {
        loadingPreview = true
        previewError = null
        runCatching { onPreview(currentChange) }
            .onSuccess { p ->
                preview = p
                allowRecreate = false
            }
            .onFailure { err ->
                previewError = err.message ?: "Failed to preview DDL"
            }
        loadingPreview = false
    }

    val title = when (initialChange) {
        is StructureChange.Create -> "Create table ${initialChange.target.name}"
        is StructureChange.Alter -> "Save changes to ${initialChange.target.name}"
        is StructureChange.DropTable -> "Drop table ${initialChange.table}"
        is StructureChange.TruncateTable -> "Truncate table ${initialChange.table}"
        is StructureChange.RenameTable -> "Rename table ${initialChange.table}"
        is StructureChange.BackupTable -> "Create table backup of ${initialChange.table}"
        is StructureChange.RenameColumn -> "Rename column ${initialChange.column}"
        is StructureChange.DropColumn -> "Drop column ${initialChange.column}"
    }

    val canExecute = !loadingPreview &&
        !applying &&
        preview != null &&
        preview!!.sql.isNotBlank() &&
        (!preview!!.recreate || allowRecreate)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(16.dp)
            .testTag("db-structure-save-dialog"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .background(tokens.panel.color, RoundedCornerShape(8.dp))
                .cssBorder(tokens.border.color)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                if (failure != null) "Error when saving" else title,
                style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
                modifier = Modifier.testTag("db-structure-save-dialog-title"),
            )

            if (
                initialChange is StructureChange.RenameTable ||
                initialChange is StructureChange.BackupTable ||
                initialChange is StructureChange.RenameColumn
            ) {
                SettingsTextInput(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = "New name",
                    modifier = Modifier.fillMaxWidth().testTag("db-structure-action-name-input"),
                )
            }

            if (conflictNotice != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.warning.opacity(0.15).color)
                        .cssBorder(tokens.warning.color)
                        .padding(10.dp)
                        .testTag("db-structure-conflict-banner"),
                ) {
                    BasicText(
                        conflictNotice.orEmpty(),
                        style = ppmText(11f, FontWeight.Medium).copy(color = tokens.warning.color),
                    )
                }
            }

            val curPreview = preview
            if (curPreview != null) {
                for (w in curPreview.warnings) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.warning.opacity(0.14).color)
                            .padding(8.dp)
                            .testTag("db-structure-preview-warnings"),
                    ) {
                        BasicText("⚠ $w", style = ppmText(11f).copy(color = tokens.warning.color))
                    }
                }
                if (!curPreview.transactionalDdl) {
                    BasicText(
                        "Note: MySQL commits each DDL statement on its own; earlier statements cannot be rolled back if a later one fails.",
                        style = ppmText(11f).copy(color = tokens.warning.color),
                        modifier = Modifier.testTag("db-structure-mysql-nontx-note"),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 220.dp)
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-structure-preview-sql"),
                ) {
                    BasicText(
                        curPreview.sql.ifBlank { "-- No SQL changes" },
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                    )
                }
                if (curPreview.recreate) {
                    SettingsButton(
                        label = "${if (allowRecreate) "☑" else "☐"} Allow recreate (rebuilds table and copies rows)",
                        primary = allowRecreate,
                        modifier = Modifier.testTag("db-structure-allow-recreate-checkbox"),
                        onClick = { allowRecreate = !allowRecreate },
                    )
                }
            } else if (loadingPreview) {
                BasicText("Generating DDL preview…", style = ppmText(12f).copy(color = tokens.text3.color))
            }

            if (previewError != null) {
                BasicText(previewError.orEmpty(), style = ppmText(11f).copy(color = tokens.error.color))
            }
            if (applyError != null) {
                BasicText(applyError.orEmpty(), style = ppmText(11f).copy(color = tokens.error.color))
            }

            val curFailure = failure
            if (curFailure != null) {
                val reports = remember(curPreview, curFailure) {
                    if (curPreview != null && curPreview.statements.isNotEmpty()) {
                        statementReports(curPreview, curFailure)
                    } else {
                        statementReports(curFailure)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.12).color)
                        .cssBorder(tokens.error.color)
                        .padding(10.dp)
                        .testTag("db-structure-failure-report"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        curFailure.error.ifBlank { applyError.orEmpty() },
                        style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.error.color),
                    )
                    for (rep in reports) {
                        val badgeLabel = OUTCOME_LABEL[rep.outcome] ?: rep.outcome.label
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("db-structure-stmt-outcome-${rep.index}"),
                        ) {
                            BasicText(
                                "[$badgeLabel]",
                                style = ppmText(10f, FontWeight.Bold, FontKind.MONO).copy(
                                    color = when (rep.outcome) {
                                        StatementOutcome.FAILED -> tokens.error.color
                                        StatementOutcome.ROLLED_BACK -> tokens.warning.color
                                        StatementOutcome.RAN -> tokens.success.color
                                        StatementOutcome.SKIPPED -> tokens.text3.color
                                    },
                                ),
                            )
                            BasicText(
                                rep.sql,
                                style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "Open script",
                    modifier = Modifier.testTag("db-structure-open-script-btn"),
                    onClick = {
                        val sql = preview?.sql.orEmpty()
                        if (sql.isNotBlank()) onOpenScriptInQueryTab(sql)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Cancel",
                        modifier = Modifier.testTag("db-structure-save-cancel-btn"),
                        onClick = onDismiss,
                    )
                    SettingsButton(
                        label = if (applying) "Executing…" else "Execute",
                        primary = canExecute,
                        modifier = Modifier.testTag("db-structure-save-execute-btn"),
                        onClick = {
                            val p = preview ?: return@SettingsButton
                            if (!canExecute) return@SettingsButton
                            applying = true
                            applyError = null
                            conflictNotice = null
                            failure = null
                            scope.launch {
                                runCatching {
                                    onApply(currentChange, p.sql, allowRecreate)
                                }.onSuccess {
                                    applying = false
                                    onApplied()
                                }.onFailure { err ->
                                    applying = false
                                    val bodyJson = (err as? PpmError.Server)?.data as? JsonObject
                                    val newer = newerPreviewOf(bodyJson)
                                    val failReport = failureOf(bodyJson)
                                    when {
                                        newer != null -> {
                                            preview = newer
                                            allowRecreate = false
                                            conflictNotice = "The table structure changed on the server. Review the updated SQL script and click Execute again."
                                        }
                                        failReport != null -> {
                                            failure = failReport.copy(
                                                error = failReport.error.ifBlank { err.message.orEmpty() },
                                            )
                                        }
                                        else -> {
                                            applyError = err.message ?: "Failed to execute DDL"
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 9f.3 Database Import / Export Tab (`db-impexp`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun DatabaseImpExpTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val meta = tab.metadata

    val initialConnId = meta["connectionId"]?.int
    val initialDb = meta["database"]?.string
    val initialSchema = meta["schema"]?.string
    val initialMode = meta["mode"]?.string // "export" | "import" | "query"
    val initialTable = meta["tableName"]?.string
    val initialSql = meta["sql"]?.string
    val initialFormat = meta["format"]?.string ?: "csv"

    val currentDbRef = remember(initialConnId, initialDb, initialSchema) {
        ImpExpDatabase(connectionId = initialConnId, database = initialDb, schema = initialSchema)
    }

    var form by remember(tab.id) {
        mutableStateOf(
            when (initialMode) {
                "import" -> importIntoForm(currentDbRef, table = initialTable)
                "query" -> gridExportForm(currentDbRef, format = initialFormat, sql = initialSql, title = initialTable)
                else -> if (initialTable != null || initialSql != null) {
                    gridExportForm(currentDbRef, format = initialFormat, table = initialTable, sql = initialSql)
                } else {
                    newImpExpForm(currentDbRef)
                }
            },
        )
    }

    var connections by remember { mutableStateOf<List<TreeConnection>>(emptyList()) }
    var sourceTablesList by remember { mutableStateOf<List<DbObjectItem>>(emptyList()) }
    var activeJob by remember { mutableStateOf<ImpExpJobRef?>(null) }
    var jobStatus by remember { mutableStateOf<ImpExpJobStatus?>(null) }
    var jobError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        if (api == null) return@LaunchedEffect
        runCatching { api.dbConnections() }.getOrNull()?.let { arr ->
            connections = parseTreeConnections(arr)
        }
    }

    LaunchedEffect(api, form.sourceDb) {
        val cid = form.sourceDb.connectionId ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        runCatching {
            client.dbListObjects(
                connectionId = cid.toString(),
                database = form.sourceDb.database,
            )
        }.getOrNull()?.let { obj ->
            val parsed = parseDbObjectList(obj)
            sourceTablesList = parsed.objects
        }
    }

    // 1-second Job Polling (`GET /api/db/impexp/jobs/:id?since=n`)
    LaunchedEffect(api, activeJob) {
        val job = activeJob ?: return@LaunchedEffect
        val client = api ?: return@LaunchedEffect
        var since = 0
        while (true) {
            val res = runCatching { client.dbImpExpJobStatus(job.id, since = since) }
            val obj = res.getOrElse { err ->
                if (err is PpmError.Server && err.status == 404) {
                    jobError = "Job is no longer on the server (PPM may have restarted)."
                } else {
                    jobError = err.message ?: "Failed to poll job status"
                }
                break
            }
            val next = parseImpExpJobStatus(obj, sinceOffset = since)
            val prevMessages = jobStatus?.messages.orEmpty()
            val mergedMessages = (prevMessages + next.messages).distinctBy { it.id }
            since = next.messageCount
            jobStatus = next.copy(messages = mergedMessages)
            if (next.state != "running" && next.state != "queued") break
            delay(1_000L)
        }
    }

    var pendingDownloadFile by remember { mutableStateOf<ImpExpOutputFile?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val file = pendingDownloadFile
        val job = jobStatus
        pendingDownloadFile = null
        if (uri != null && file != null && job != null && api != null) {
            scope.launch {
                runCatching {
                    val ticketObj = api.dbImpExpDownloadTicket(job.id, file.name)
                    val ticket = ticketObj["ticket"]?.string ?: error("Missing download ticket")
                    val bytes = api.dbDownloadGridExportTicket(ticket)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                }.onFailure { err ->
                    jobError = err.message ?: "Download failed"
                }
            }
        }
    }

    val uploadFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty() || api == null) return@rememberLauncherForActivityResult
        for (uri in uris) {
            var fileName = "import.csv"
            var fileSize = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }
            val problem = uploadProblem(fileName, fileSize)
            val localId = uid("up")
            val fmt = importFormatOfFileName(fileName)
            if (problem != null) {
                form = addUploads(
                    form,
                    listOf(
                        ImpExpUpload(
                            localId = localId,
                            fileName = fileName,
                            size = fileSize,
                            format = fmt,
                            state = "error",
                            error = problem,
                        ),
                    ),
                )
                continue
            }
            form = addUploads(
                form,
                listOf(
                    ImpExpUpload(
                        localId = localId,
                        fileName = fileName,
                        size = fileSize,
                        format = fmt,
                        state = "uploading",
                        progress = 0.0,
                    ),
                ),
            )
            scope.launch {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Could not read file")
                    }
                    api.dbImpExpUploadRaw(
                        fileName = fileName,
                        knownSize = bytes.size.toLong(),
                        openStream = { bytes.inputStream() },
                    )
                }.onSuccess { resObj ->
                    val uploadId = resObj["uploadId"]?.string ?: resObj["id"]?.string
                    val sources = (resObj["sources"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.string }
                        .orEmpty()
                    form = patchUpload(
                        form,
                        localId = localId,
                        state = "ready",
                        progress = 1.0,
                        uploadId = uploadId,
                        sources = sources,
                    )
                }.onFailure { err ->
                    form = patchUpload(
                        form,
                        localId = localId,
                        state = "error",
                        error = err.message ?: "Upload failed",
                    )
                }
            }
        }
    }

    DatabaseImpExpTabContent(
        form = form,
        connections = connections,
        currentDb = currentDbRef,
        availableObjects = sourceTablesList,
        jobStatus = jobStatus,
        jobError = jobError,
        onFormChange = { form = it },
        onPickUploadFiles = { uploadFilesLauncher.launch(arrayOf("*/*")) },
        onPreviewRow = { row ->
            val client = api ?: error("Not connected")
            if (form.sourceType != "database" && form.sourceType != "query") {
                val u = form.uploads.firstOrNull { it.localId == row.uploadLocalId }
                    ?: error("Upload missing")
                val uidStr = u.uploadId ?: error("Upload not ready")
                val body = uploadPreviewRequest(form, row)
                val obj = client.dbImpExpUploadPreview(uidStr, body)
                parseImportPreview(obj, source = row.source)
            } else {
                val cid = form.sourceDb.connectionId ?: error("No source connection")
                val reqJson = buildGridRequestJson(
                    table = row.source,
                    schema = form.sourceDb.schema,
                    sort = emptyList(),
                    filters = kotlinx.serialization.json.JsonArray(emptyList()),
                    anyColumn = kotlinx.serialization.json.JsonArray(emptyList()),
                    offset = 0,
                    limit = 100,
                )
                val gridObj = client.dbGridPage(
                    connectionId = cid.toString(),
                    body = reqJson,
                    database = form.sourceDb.database,
                )
                val page = parseGridResponse(gridObj, countKey = row.source)
                ImportPreviewResult(
                    sources = listOf(row.source),
                    source = row.source,
                    columns = page.columnKeys,
                    rows = page.rows.indices.map { page.rowAsMap(it) },
                )
            }
        },
        onRunJob = { preservedLogs ->
            val client = api ?: return@DatabaseImpExpTabContent
            val rowsOrder = activeRows(form).map { it.id }
            jobError = null
            scope.launch {
                runCatching {
                    if (isImportForm(form) && form.sourceType != "database" && form.sourceType != "query") {
                        val cid = form.targetDb.connectionId ?: error("Choose a target connection")
                        client.dbImpExpImport(cid.toString(), importRequest(form), database = form.targetDb.database)
                    } else {
                        val cid = form.sourceDb.connectionId ?: error("Choose a source connection")
                        client.dbImpExpExport(cid.toString(), exportRequest(form), database = form.sourceDb.database)
                    }
                }.onSuccess { jobIdStr ->
                    val jid = jobIdStr.ifBlank { "job-1" }
                    jobStatus = startingStatus(jid, form, preserved = preservedLogs)
                    activeJob = ImpExpJobRef(id = jid, rowOrder = rowsOrder)
                }.onFailure { err ->
                    jobError = err.message ?: "Failed to start job"
                }
            }
        },
        onStopJob = {
            val jid = jobStatus?.id ?: return@DatabaseImpExpTabContent
            val client = api ?: return@DatabaseImpExpTabContent
            scope.launch { runCatching { client.dbImpExpStopJob(jid) } }
        },
        onDownloadOutputFile = { file ->
            pendingDownloadFile = file
            downloadLauncher.launch(file.name)
        },
    )
}

@Composable
internal fun DatabaseImpExpTabContent(
    form: ImpExpForm,
    connections: List<TreeConnection> = emptyList(),
    currentDb: ImpExpDatabase = ImpExpDatabase(connectionId = 1, database = "main", schema = "public"),
    availableObjects: List<DbObjectItem> = emptyList(),
    jobStatus: ImpExpJobStatus? = null,
    jobError: String? = null,
    onFormChange: (ImpExpForm) -> Unit = {},
    onPickUploadFiles: () -> Unit = {},
    onPreviewRow: suspend (ImpExpRow) -> ImportPreviewResult = { row ->
        ImportPreviewResult(
            sources = listOf(row.source),
            source = row.source,
            columns = listOf("id", "name"),
            rows = listOf(mapOf("id" to DbCellValue.Num("1", 1.0), "name" to DbCellValue.Text("Alice"))),
        )
    },
    onRunJob: (preservedLogs: List<ImpExpMessage>) -> Unit = {},
    onStopJob: () -> Unit = {},
    onDownloadOutputFile: (ImpExpOutputFile) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Phone guard (`§9f.3`): "Needs a wider screen"
    if (mobile) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .padding(24.dp)
                .testTag("db-impexp-phone-wider-screen"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.ArrowLeftRight.vector, 28.dp, tokens.text3.color)
                BasicText(
                    "Needs a wider screen",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                )
                BasicText(
                    "Database Import / Export requires a tablet or desktop screen.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        }
        return
    }

    val targetConn = connections.firstOrNull { it.id == form.targetDb.connectionId }
    val targetReadonly = targetConn?.readonly == 1
    val blocker = remember(form, targetReadonly) { runBlocker(form, targetReadonly = targetReadonly) }
    val isRunning = jobStatus?.state == "running"

    var configuringRow by remember { mutableStateOf<ImpExpRow?>(null) }
    var configureSourceCols by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewDialogResult by remember { mutableStateOf<ImportPreviewResult?>(null) }
    var shownLevels by remember { mutableStateOf(DEFAULT_SHOWN_LEVELS) }
    var msgFilterText by remember { mutableStateOf("") }
    var preserveLogs by remember { mutableStateOf(false) }

    val rowStatusMap = remember(form, jobStatus) { itemsByRow(form, null, jobStatus) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("db-impexp-wizard"),
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    impExpTitle(form),
                    style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    modifier = Modifier.testTag("db-impexp-title"),
                )
                SettingsButton(
                    label = "⇄ Swap Source / Target",
                    modifier = Modifier.testTag("db-impexp-swap-btn"),
                    onClick = {
                        if (isImportForm(form)) {
                            onFormChange(databaseExportForm(form.targetDb))
                        } else {
                            onFormChange(importIntoForm(form.sourceDb))
                        }
                    },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (blocker != null) {
                    BasicText(
                        blocker,
                        style = ppmText(11f).copy(color = tokens.warning.color),
                        modifier = Modifier.testTag("db-impexp-blocker-text"),
                    )
                }
                if (isRunning) {
                    SettingsButton(
                        label = "Stop",
                        primary = true,
                        modifier = Modifier.testTag("db-impexp-stop-btn"),
                        onClick = onStopJob,
                    )
                } else {
                    SettingsButton(
                        label = "Run",
                        primary = blocker == null,
                        modifier = Modifier.testTag("db-impexp-run-btn"),
                        onClick = {
                            if (blocker == null) {
                                val preserved = if (preserveLogs) jobStatus?.messages.orEmpty() else emptyList()
                                onRunJob(preserved)
                            }
                        },
                    )
                }
            }
        }

        if (jobError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.15).color)
                    .cssBorder(tokens.error.color, sides = CssSides.Bottom)
                    .padding(10.dp)
                    .testTag("db-impexp-job-error"),
            ) {
                BasicText(jobError, style = ppmText(11f).copy(color = tokens.error.color))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Source & Target Columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Source Card
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText("SOURCE", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
                        SettingsButton(
                            label = "Current DB",
                            modifier = Modifier.testTag("db-impexp-source-current-db-btn"),
                            onClick = {
                                onFormChange(
                                    setDatabase(
                                        setSourceType(form, "database"),
                                        side = "source",
                                        patchConnectionId = currentDb.connectionId,
                                        patchDatabase = currentDb.database,
                                        patchSchema = currentDb.schema,
                                        updateConn = true,
                                        updateDb = true,
                                        updateSchema = true,
                                    ),
                                )
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SettingsButton(
                            label = "Database",
                            primary = form.sourceType == "database",
                            modifier = Modifier.testTag("db-impexp-source-type-database"),
                            onClick = { onFormChange(setSourceType(form, "database")) },
                        )
                        SettingsButton(
                            label = "Query",
                            primary = form.sourceType == "query",
                            modifier = Modifier.testTag("db-impexp-source-type-query"),
                            onClick = { onFormChange(setSourceType(form, "query")) },
                        )
                        for (fmt in IMPEXP_FILE_FORMATS.filter { it.importable }) {
                            SettingsButton(
                                label = fmt.id.uppercase(),
                                primary = form.sourceType == fmt.id,
                                modifier = Modifier.testTag("db-impexp-source-type-${fmt.id}"),
                                onClick = { onFormChange(setSourceType(form, fmt.id)) },
                            )
                        }
                    }

                    if (form.sourceType == "database" || form.sourceType == "query") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (c in connections) {
                                SettingsButton(
                                    label = c.name,
                                    primary = form.sourceDb.connectionId == c.id,
                                    modifier = Modifier.testTag("db-impexp-source-conn-${c.id}"),
                                    onClick = {
                                        onFormChange(
                                            setDatabase(
                                                form,
                                                side = "source",
                                                patchConnectionId = c.id,
                                                patchDatabase = c.ownDatabase,
                                                updateConn = true,
                                                updateDb = true,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }

                    if (form.sourceType == "database") {
                        val tables = availableObjects.filter { it.kind == "table" }.map { it.name }
                        val views = availableObjects.filter { it.kind == "view" }.map { it.name }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SettingsButton(
                                label = "All tables (${tables.size})",
                                modifier = Modifier.testTag("db-impexp-add-all-tables-btn"),
                                onClick = { onFormChange(addTables(form, tables)) },
                            )
                            if (views.isNotEmpty()) {
                                SettingsButton(
                                    label = "All views (${views.size})",
                                    modifier = Modifier.testTag("db-impexp-add-all-views-btn"),
                                    onClick = { onFormChange(addTables(form, views)) },
                                )
                            }
                            if (form.sourceTables.isNotEmpty()) {
                                SettingsButton(
                                    label = "Clear",
                                    modifier = Modifier.testTag("db-impexp-clear-tables-btn"),
                                    onClick = { onFormChange(setTables(form, emptyList())) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (obj in availableObjects.filter { it.kind in setOf("table", "view", "matview") }) {
                                val selected = obj.name in form.sourceTables
                                SettingsButton(
                                    label = "${if (selected) "✓ " else ""}${obj.name}",
                                    primary = selected,
                                    modifier = Modifier.testTag("db-impexp-table-chip-${obj.name}"),
                                    onClick = {
                                        val next = if (selected) form.sourceTables - obj.name else form.sourceTables + obj.name
                                        onFormChange(setTables(form, next))
                                    },
                                )
                            }
                        }
                    } else if (form.sourceType == "query") {
                        SettingsTextInput(
                            value = form.queryTitle,
                            onValueChange = { onFormChange(form.copy(queryTitle = it)) },
                            placeholder = "Query title",
                            modifier = Modifier.fillMaxWidth().testTag("db-impexp-query-title-input"),
                        )
                        SettingsTextInput(
                            value = form.querySql,
                            onValueChange = { onFormChange(form.copy(querySql = it)) },
                            placeholder = "SELECT * FROM ...",
                            modifier = Modifier.fillMaxWidth().testTag("db-impexp-query-sql-input"),
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsButton(
                                label = "+ Add file(s)",
                                primary = true,
                                modifier = Modifier.testTag("db-impexp-add-file-btn"),
                                onClick = onPickUploadFiles,
                            )
                            BasicText(
                                "Max 128 MB per file",
                                style = ppmText(10f).copy(color = tokens.text3.color),
                            )
                        }
                        for (u in form.uploads) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(tokens.bgSolid.color)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("db-impexp-upload-row-${u.fileName}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    u.fileName,
                                    style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                    modifier = Modifier.weight(1f),
                                )
                                BasicText(
                                    when (u.state) {
                                        "uploading" -> "Uploading ${(u.progress * 100).toInt()}%"
                                        "ready" -> "Ready"
                                        else -> u.error ?: "Error"
                                    },
                                    style = ppmText(10f).copy(
                                        color = if (u.state == "error") tokens.error.color else tokens.text2.color,
                                    ),
                                )
                            }
                        }
                    }
                }

                // Target Card
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText("TARGET", style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color))
                        if (form.targetType == "database") {
                            SettingsButton(
                                label = "Current DB",
                                modifier = Modifier.testTag("db-impexp-target-current-db-btn"),
                                onClick = {
                                    onFormChange(
                                        setDatabase(
                                            form,
                                            side = "target",
                                            patchConnectionId = currentDb.connectionId,
                                            patchDatabase = currentDb.database,
                                            patchSchema = currentDb.schema,
                                            updateConn = true,
                                            updateDb = true,
                                            updateSchema = true,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SettingsButton(
                            label = "Database",
                            primary = form.targetType == "database",
                            modifier = Modifier.testTag("db-impexp-target-type-database"),
                            onClick = { onFormChange(setTargetType(form, "database")) },
                        )
                        for (fmt in IMPEXP_FILE_FORMATS.filter { it.exportable }) {
                            SettingsButton(
                                label = fmt.id.uppercase(),
                                primary = form.targetType == fmt.id,
                                modifier = Modifier.testTag("db-impexp-target-type-${fmt.id}"),
                                onClick = { onFormChange(setTargetType(form, fmt.id)) },
                            )
                        }
                    }

                    if (form.targetType == "database") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (c in connections) {
                                val roSuffix = if (c.readonly == 1) " (read-only)" else ""
                                SettingsButton(
                                    label = "${c.name}$roSuffix",
                                    primary = form.targetDb.connectionId == c.id,
                                    modifier = Modifier.testTag("db-impexp-target-conn-${c.id}"),
                                    onClick = {
                                        onFormChange(
                                            setDatabase(
                                                form,
                                                side = "target",
                                                patchConnectionId = c.id,
                                                patchDatabase = c.ownDatabase,
                                                updateConn = true,
                                                updateDb = true,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        // Export format options + Archive as ZIP
                        if (form.targetType == "xlsx") {
                            SettingsButton(
                                label = "${if (form.exportOptions.xlsxSingleFile) "☑" else "☐"} All tables in one Excel file",
                                primary = form.exportOptions.xlsxSingleFile,
                                modifier = Modifier.testTag("db-impexp-xlsx-single-checkbox"),
                                onClick = {
                                    onFormChange(
                                        form.copy(
                                            exportOptions = form.exportOptions.copy(
                                                xlsxSingleFile = !form.exportOptions.xlsxSingleFile,
                                            ),
                                        ),
                                    )
                                },
                            )
                        }
                        if (form.targetType == "csv") {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText("Delimiter:", style = ppmText(11f).copy(color = tokens.text2.color))
                                for (del in CSV_DELIMITERS) {
                                    SettingsButton(
                                        label = del.label,
                                        primary = form.exportOptions.csv.delimiter == del.id,
                                        onClick = {
                                            onFormChange(
                                                form.copy(
                                                    exportOptions = form.exportOptions.copy(
                                                        csv = form.exportOptions.csv.copy(delimiter = del.id),
                                                    ),
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsButton(
                                label = "${if (form.archiveAsZip) "☑" else "☐"} Export to ZIP file",
                                primary = form.archiveAsZip,
                                modifier = Modifier.testTag("db-impexp-archive-zip-checkbox"),
                                onClick = { onFormChange(form.copy(archiveAsZip = !form.archiveAsZip)) },
                            )
                            if (form.archiveAsZip) {
                                SettingsTextInput(
                                    value = form.zipFileName,
                                    onValueChange = { onFormChange(form.copy(zipFileName = it)) },
                                    placeholder = "export.zip",
                                    modifier = Modifier.weight(1f).testTag("db-impexp-zip-name-input"),
                                )
                            }
                        }
                    }
                }
            }

            // 2. Map Table (`TABLES / FILES MAP`)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color)
                    .padding(10.dp)
                    .testTag("db-impexp-map-section"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "TABLES / FILES MAP",
                    style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color),
                )
                for (row in form.rows) {
                    val isTemplate = row.source == TEMPLATE_SOURCE
                    if (isTemplate && form.sourceType == "query") continue
                    val targetPlaceholder = defaultRowTarget(form, row)
                    val itemStatus = rowStatusMap[row.id]
                    val statusText = itemStatusText(itemStatus)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isTemplate) tokens.panel2.color else tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("db-impexp-map-row-${row.source}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isTemplate) {
                            SettingsButton(
                                label = if (row.checked) "☑" else "☐",
                                primary = row.checked,
                                modifier = Modifier.testTag("db-impexp-row-check-${row.source}"),
                                onClick = { onFormChange(updateRow(form, row.id, checked = !row.checked)) },
                            )
                        }
                        BasicText(
                            rowSourceLabel(form, row),
                            style = ppmText(
                                12f,
                                if (isTemplate) FontWeight.SemiBold else FontWeight.Normal,
                                FontKind.MONO,
                            ).copy(color = tokens.text.color),
                            modifier = Modifier.width(140.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SettingsTextInput(
                            value = row.target,
                            onValueChange = { onFormChange(updateRow(form, row.id, target = it)) },
                            placeholder = targetPlaceholder.ifEmpty { "Target name" },
                            modifier = Modifier.weight(1f).testTag("db-impexp-row-target-${row.source}"),
                        )
                        if (isImportForm(form)) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                for (act in IMPORT_ACTIONS) {
                                    SettingsButton(
                                        label = act.label,
                                        primary = row.action == act.id,
                                        modifier = Modifier.testTag("db-impexp-row-action-${row.source}-${act.id}"),
                                        onClick = { onFormChange(updateRow(form, row.id, action = act.id)) },
                                    )
                                }
                            }
                        }
                        if (!isTemplate) {
                            SettingsButton(
                                label = "Columns: ${columnsLinkText(row)}",
                                modifier = Modifier.testTag("db-impexp-row-cols-btn-${row.source}"),
                                onClick = {
                                    scope.launch {
                                        val cols = runCatching { onPreviewRow(row).columns }
                                            .getOrDefault(row.columnMap?.map { it.source }.orEmpty().ifEmpty { listOf("id", "name") })
                                        configureSourceCols = cols
                                        configuringRow = row
                                    }
                                },
                            )
                            SettingsButton(
                                label = "Preview",
                                modifier = Modifier.testTag("db-impexp-row-preview-btn-${row.source}"),
                                onClick = {
                                    scope.launch {
                                        runCatching { onPreviewRow(row) }.onSuccess { previewDialogResult = it }
                                    }
                                },
                            )
                            SettingsButton(
                                label = "×",
                                modifier = Modifier.testTag("db-impexp-row-remove-${row.source}"),
                                onClick = { onFormChange(removeRow(form, row.id)) },
                            )
                        }
                        if (statusText.isNotEmpty()) {
                            BasicText(
                                statusText,
                                style = ppmText(11f, FontWeight.Medium, FontKind.MONO).copy(
                                    color = when (itemStatus?.state) {
                                        "error" -> tokens.error.color
                                        "done" -> tokens.success.color
                                        else -> tokens.accent.color
                                    },
                                ),
                                modifier = Modifier.testTag("db-impexp-row-status-${row.source}"),
                            )
                        }
                    }
                }
            }

            // 3. Output Files & Messages Panel
            if (jobStatus != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color)
                        .padding(10.dp)
                        .testTag("db-impexp-messages-panel"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "JOB ${jobStatus.state.uppercase()} (${formatImpExpDuration(jobStatus.startedAt, jobStatus.finishedAt)})",
                            style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color),
                        )
                        val zip = jobStatus.zipFile
                        if (zip != null) {
                            SettingsButton(
                                label = "⬇ Download ${zip.name}",
                                primary = true,
                                modifier = Modifier.testTag("db-impexp-download-zip"),
                                onClick = { onDownloadOutputFile(zip) },
                            )
                        }
                        for (f in jobStatus.files) {
                            SettingsButton(
                                label = "⬇ ${f.name}",
                                modifier = Modifier.testTag("db-impexp-download-file-${f.name}"),
                                onClick = { onDownloadOutputFile(f) },
                            )
                        }
                    }

                    // Messages Filter Bar
                    val counts = remember(jobStatus.messages) { levelCounts(jobStatus.messages) }
                    val filteredMsgs = remember(jobStatus.messages, shownLevels, msgFilterText) {
                        filterMessages(jobStatus.messages, shownLevels, msgFilterText)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (lvl in MESSAGE_LEVELS) {
                            val active = lvl in shownLevels
                            SettingsButton(
                                label = "${lvl.uppercase()} (${counts[lvl] ?: 0})",
                                primary = active,
                                modifier = Modifier.testTag("db-impexp-msg-level-$lvl"),
                                onClick = {
                                    shownLevels = if (active) shownLevels - lvl else shownLevels + lvl
                                },
                            )
                        }
                        SettingsTextInput(
                            value = msgFilterText,
                            onValueChange = { msgFilterText = it },
                            placeholder = "Filter messages...",
                            modifier = Modifier.width(160.dp).testTag("db-impexp-msg-search-input"),
                        )
                        SettingsButton(
                            label = "${if (preserveLogs) "☑" else "☐"} Preserve logs",
                            primary = preserveLogs,
                            modifier = Modifier.testTag("db-impexp-preserve-logs-checkbox"),
                            onClick = { preserveLogs = !preserveLogs },
                        )
                        SettingsButton(
                            label = "Copy",
                            modifier = Modifier.testTag("db-impexp-copy-logs-btn"),
                            onClick = {
                                copyTextPlain(context, "ImpExp Logs", messageLines(filteredMsgs))
                            },
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (m in filteredMsgs) {
                            BasicText(
                                "${messageClock(m.at)} [${m.level.uppercase()}]${if (!m.item.isNullOrEmpty()) " [${m.item}]" else ""} ${m.text}",
                                style = ppmText(11f, kind = FontKind.MONO).copy(
                                    color = when (m.level) {
                                        "error" -> tokens.error.color
                                        "warn" -> tokens.warning.color
                                        else -> tokens.text2.color
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Configure Columns Dialog (`db-impexp-columns-dialog`) ──
    val cfgRow = configuringRow
    if (cfgRow != null) {
        var entries by remember(cfgRow, configureSourceCols) {
            mutableStateOf(
                cfgRow.columnMap ?: resetColumnMap(configureSourceCols),
            )
        }
        val mapProblem = remember(entries) { columnMapProblem(entries) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-impexp-columns-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(
                    "Configure columns · ${cfgRow.sourceLabel}",
                    style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
                for ((idx, entry) in entries.withIndex()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsButton(
                            label = if (!entry.skip) "☑" else "☐",
                            primary = !entry.skip,
                            modifier = Modifier.testTag("db-impexp-col-map-check-${entry.src}"),
                            onClick = {
                                entries = entries.mapIndexed { i, e ->
                                    if (i == idx) e.copy(skip = !e.skip) else e
                                }
                            },
                        )
                        BasicText(
                            entry.source,
                            style = ppmText(12f, kind = FontKind.MONO).copy(color = tokens.text.color),
                            modifier = Modifier.width(120.dp),
                        )
                        BasicText("→", style = ppmText(12f).copy(color = tokens.text3.color))
                        SettingsTextInput(
                            value = entry.dst,
                            onValueChange = { nextDst ->
                                entries = entries.mapIndexed { i, e ->
                                    if (i == idx) e.copy(dst = nextDst) else e
                                }
                            },
                            placeholder = entry.source,
                            modifier = Modifier.weight(1f).testTag("db-impexp-col-map-dst-${entry.source}"),
                        )
                    }
                }
                if (mapProblem != null) {
                    BasicText(mapProblem, style = ppmText(11f).copy(color = tokens.error.color))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SettingsButton(
                        label = "Reset",
                        modifier = Modifier.testTag("db-impexp-col-map-reset-btn"),
                        onClick = { entries = resetColumnMap(configureSourceCols) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(label = "Cancel", onClick = { configuringRow = null })
                        SettingsButton(
                            label = "Confirm",
                            primary = mapProblem == null,
                            modifier = Modifier.testTag("db-impexp-col-map-confirm-btn"),
                            onClick = {
                                if (mapProblem == null) {
                                    val confirmed = confirmedColumnMap(entries, configureSourceCols)
                                    onFormChange(
                                        updateRow(
                                            form,
                                            cfgRow.id,
                                            columnMap = confirmed,
                                            updateColumnMap = true,
                                        ),
                                    )
                                    configuringRow = null
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ── 100-Row Preview Dialog (`db-impexp-preview-dialog`) ──
    val prevRes = previewDialogResult
    if (prevRes != null) {
        val gridRows = remember(prevRes) {
            prevRes.rows.map { rMap ->
                prevRes.columns.map { col -> rMap[col] ?: DbCellValue.Null }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp)
                .testTag("db-impexp-preview-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .heightIn(max = 480.dp)
                    .fillMaxWidth()
                    .background(tokens.panel.color, RoundedCornerShape(8.dp))
                    .cssBorder(tokens.border.color)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Preview · ${prevRes.source} (${prevRes.rows.size} rows)",
                        style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    )
                    SettingsButton(
                        label = "Close",
                        modifier = Modifier.testTag("db-impexp-preview-close-btn"),
                        onClick = { previewDialogResult = null },
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    NativeDataGrid(
                        columns = prevRes.columns,
                        rows = gridRows,
                        schemaMap = emptyMap(),
                        showFilterRow = false,
                    )
                }
            }
        }
    }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 9f.4 SQLite File Viewer Tab (`sqlite` / `.db` / `.sqlite` / `.sqlite3`)
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun SqliteViewerTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val filePath = tab.metaString("filePath") ?: tab.title
    val project = tab.projectId ?: tab.metaString("projectName") ?: tab.metaString("project")

    var objects by remember(filePath, project) { mutableStateOf<List<DbObjectItem>>(emptyList()) }
    var selectedTable by remember(filePath, project) {
        mutableStateOf(tab.metaString("tableName"))
    }
    var loadingObjects by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadSqliteObjects() {
        val client = api ?: return
        loadingObjects = true
        errorMessage = null
        runCatching {
            client.dbListObjects(
                connectionId = "file",
                path = filePath,
                project = project,
                database = null, // SQLite viewer NEVER sends ?database=
            )
        }.onSuccess { obj ->
            val list = parseDbObjectList(obj).objects.filter { it.kind in setOf("table", "view") }
            objects = list
            if (selectedTable == null || list.none { it.name == selectedTable }) {
                selectedTable = list.firstOrNull()?.name
            }
        }.onFailure { err ->
            errorMessage = err.message ?: "Failed to open SQLite file"
        }
        loadingObjects = false
    }

    LaunchedEffect(api, filePath, project) {
        if (api != null) loadSqliteObjects()
    }

    SqliteViewerTabContent(
        filePath = filePath,
        project = project,
        objects = objects,
        selectedTable = selectedTable,
        loadingObjects = loadingObjects,
        errorMessage = errorMessage,
        onSelectTable = { selectedTable = it },
        onRefreshObjects = { scope.launch { loadSqliteObjects() } },
        onNewQuery = {
            val existingTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
            val qNum = nextQueryNumber(existingTabs)
            val sql = selectedTable?.let { "SELECT * FROM \"$it\" LIMIT 100;" }.orEmpty()
            model.openWorkspaceTab(
                type = "db-query",
                title = "Query $qNum",
                metadata = queryTabMetadata(
                    sql = sql,
                    queryNumber = qNum,
                    connectionId = null,
                    connectionName = filePath.substringAfterLast('/'),
                    dbType = "sqlite",
                    database = null,
                    dbFilePath = filePath,
                    dbFileProject = project,
                ),
            )
        },
        onOpenStructure = { tbl ->
            model.openWorkspaceTab(
                type = "db-structure",
                title = "$tbl · Structure",
                metadata = buildJsonObject {
                    put("connectionId", "file")
                    put("connectionName", filePath.substringAfterLast('/'))
                    put("filePath", filePath)
                    if (project != null) put("project", project)
                    put("dbType", "sqlite")
                    put("tableName", tbl)
                },
            )
        },
        onOpenSql = { tbl, kind ->
            model.openWorkspaceTab(
                type = "db-sql",
                title = "$tbl · SQL",
                metadata = buildJsonObject {
                    put("connectionId", "file")
                    put("connectionName", filePath.substringAfterLast('/'))
                    put("filePath", filePath)
                    if (project != null) put("project", project)
                    put("dbType", "sqlite")
                    put("objectKind", kind)
                    put("objectName", tbl)
                },
            )
        },
        tableContent = { tbl ->
            val syntheticTab = remember(tab.id, filePath, project, tbl) {
                WorkspaceTab(
                    id = "${tab.id}:$tbl",
                    type = "database",
                    title = tbl,
                    projectId = project,
                    metadata = buildJsonObject {
                        put("connectionId", "file")
                        put("connectionName", filePath.substringAfterLast('/'))
                        put("filePath", filePath)
                        if (project != null) put("project", project)
                        put("dbType", "sqlite")
                        put("tableName", tbl)
                        put("readonly", false)
                    },
                )
            }
            DatabaseTableTabBody(model, syntheticTab)
        },
    )
}

@Composable
internal fun SqliteViewerTabContent(
    filePath: String,
    project: String? = null,
    objects: List<DbObjectItem>,
    selectedTable: String?,
    loadingObjects: Boolean = false,
    errorMessage: String? = null,
    onSelectTable: (String) -> Unit = {},
    onRefreshObjects: () -> Unit = {},
    onNewQuery: () -> Unit = {},
    onOpenStructure: (String) -> Unit = {},
    onOpenSql: (table: String, kind: String) -> Unit = { _, _ -> },
    tableContent: @Composable (String) -> Unit = { tbl ->
        Box(Modifier.fillMaxSize().testTag("sqlite-table-content-$tbl"))
    },
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var showPhoneTableDropdown by remember { mutableStateOf(false) }

    val selectedObj = objects.firstOrNull { it.name == selectedTable }

    if (mobile) {
        // Phone layout (`§9f.4`): top table dropdown + New query + Structure ↗ above table grid
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .testTag("sqlite-viewer-phone"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("sqlite-viewer-phone-bar"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "${selectedTable ?: "Select table"} ▾",
                    primary = true,
                    modifier = Modifier.testTag("sqlite-phone-table-picker-btn"),
                    onClick = { showPhoneTableDropdown = !showPhoneTableDropdown },
                )
                SettingsButton(
                    label = "New query",
                    modifier = Modifier.testTag("sqlite-new-query-btn"),
                    onClick = onNewQuery,
                )
                if (selectedTable != null) {
                    SettingsButton(
                        label = "Structure ↗",
                        modifier = Modifier.testTag("sqlite-open-structure-btn"),
                        onClick = { onOpenStructure(selectedTable) },
                    )
                    SettingsButton(
                        label = "SQL ↗",
                        modifier = Modifier.testTag("sqlite-open-sql-btn"),
                        onClick = { onOpenSql(selectedTable, selectedObj?.kind ?: "table") },
                    )
                }
                SettingsButton(
                    label = "Refresh",
                    modifier = Modifier.testTag("sqlite-refresh-btn"),
                    onClick = onRefreshObjects,
                )
            }
            if (showPhoneTableDropdown) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(8.dp)
                        .testTag("sqlite-phone-table-dropdown"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (obj in objects) {
                        SettingsButton(
                            label = obj.name + (obj.rowEstimate?.let { " (${formatRowEstimate(it)})" } ?: ""),
                            primary = obj.name == selectedTable,
                            modifier = Modifier.testTag("sqlite-table-item-${obj.name}"),
                            onClick = {
                                showPhoneTableDropdown = false
                                onSelectTable(obj.name)
                            },
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    errorMessage != null -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        BasicText(errorMessage, style = ppmText(12f).copy(color = tokens.error.color))
                    }
                    selectedTable != null -> tableContent(selectedTable)
                    loadingObjects -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText("Loading SQLite tables…", style = ppmText(12f).copy(color = tokens.text3.color))
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BasicText("No tables in this SQLite file.", style = ppmText(12f).copy(color = tokens.text3.color))
                    }
                }
            }
        }
    } else {
        // Tablet layout (`§9f.4`): 224.dp (`w-56`) left sidebar + main table grid
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .testTag("sqlite-viewer-tablet"),
        ) {
            Column(
                modifier = Modifier
                    .width(224.dp)
                    .fillMaxHeight()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.End)
                    .testTag("sqlite-viewer-sidebar"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Tables (${objects.size})",
                        style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingsButton(
                            label = "New query",
                            modifier = Modifier.testTag("sqlite-new-query-btn"),
                            onClick = onNewQuery,
                        )
                        SettingsButton(
                            label = "↻",
                            modifier = Modifier.testTag("sqlite-refresh-btn"),
                            onClick = onRefreshObjects,
                        )
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(objects, key = { it.name }) { obj ->
                        val active = obj.name == selectedTable
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (active) tokens.accent.opacity(0.16).color else Color.Transparent)
                                .clickable { onSelectTable(obj.name) }
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                                .testTag("sqlite-table-item-${obj.name}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                obj.name,
                                style = ppmText(
                                    12f,
                                    if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    FontKind.MONO,
                                ).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val est = obj.rowEstimate
                            if (est != null) {
                                BasicText(
                                    formatRowEstimate(est),
                                    style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (selectedTable != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.panel2.color)
                            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "$filePath · $selectedTable",
                            style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SettingsButton(
                            label = "Structure ↗",
                            modifier = Modifier.testTag("sqlite-open-structure-btn"),
                            onClick = { onOpenStructure(selectedTable) },
                        )
                        SettingsButton(
                            label = "SQL ↗",
                            modifier = Modifier.testTag("sqlite-open-sql-btn"),
                            onClick = { onOpenSql(selectedTable, selectedObj?.kind ?: "table") },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        errorMessage != null -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            BasicText(errorMessage, style = ppmText(12f).copy(color = tokens.error.color))
                        }
                        selectedTable != null -> tableContent(selectedTable)
                        loadingObjects -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BasicText("Loading SQLite tables…", style = ppmText(12f).copy(color = tokens.text3.color))
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BasicText("No tables in this SQLite file.", style = ppmText(12f).copy(color = tokens.text3.color))
                        }
                    }
                }
            }
        }
    }
}
