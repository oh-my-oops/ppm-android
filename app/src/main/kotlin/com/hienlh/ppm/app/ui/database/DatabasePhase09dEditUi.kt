package com.hienlh.ppm.app.ui.database

import android.graphics.BitmapFactory
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.database.CELL_DATA_FORMATS
import com.hienlh.ppm.state.database.CellChange
import com.hienlh.ppm.state.database.CellDataCell
import com.hienlh.ppm.state.database.CellDataChoice
import com.hienlh.ppm.state.database.CellDataFormat
import com.hienlh.ppm.state.database.ChangesetFailure
import com.hienlh.ppm.state.database.ChangesetPreview
import com.hienlh.ppm.state.database.ColumnKind
import com.hienlh.ppm.state.database.CopySqlTarget
import com.hienlh.ppm.state.database.DbCellValue
import com.hienlh.ppm.state.database.DocumentColumn
import com.hienlh.ppm.state.database.FormFieldKind
import com.hienlh.ppm.state.database.FormNavigation
import com.hienlh.ppm.state.database.FormParse
import com.hienlh.ppm.state.database.GeneratedStatement
import com.hienlh.ppm.state.database.GridChangeset
import com.hienlh.ppm.state.database.GridColumnSchema
import com.hienlh.ppm.state.database.GridReference
import com.hienlh.ppm.state.database.JsonDocumentsResult
import com.hienlh.ppm.state.database.LockColumn
import com.hienlh.ppm.state.database.NO_FIELD_TEXT
import com.hienlh.ppm.state.database.NULL_TEXT
import com.hienlh.ppm.state.database.NewRow
import com.hienlh.ppm.state.database.STATEMENT_TYPES
import com.hienlh.ppm.state.database.SqlSourceRow
import com.hienlh.ppm.state.database.TableRef
import com.hienlh.ppm.state.database.TableReferences
import com.hienlh.ppm.state.database.autodetectFormat
import com.hienlh.ppm.state.database. cascadeTables
import com.hienlh.ppm.state.database.cellDataFields
import com.hienlh.ppm.state.database.cellDataMessage
import com.hienlh.ppm.state.database.cellMark
import com.hienlh.ppm.state.database.cellsText
import com.hienlh.ppm.state.database.choiceTitle
import com.hienlh.ppm.state.database.classifyColumnType
import com.hienlh.ppm.state.database.columnNameMatches
import com.hienlh.ppm.state.database.documentChanges
import com.hienlh.ppm.state.database.formDisplayText
import com.hienlh.ppm.state.database.formEditText
import com.hienlh.ppm.state.database.formFieldKindFor
import com.hienlh.ppm.state.database.formRowLabel
import com.hienlh.ppm.state.database.formatByteSize
import com.hienlh.ppm.state.database.generateSql
import com.hienlh.ppm.state.database.htmlDocument
import com.hienlh.ppm.state.database.isCellLocked
import com.hienlh.ppm.state.database.isNewRowId
import com.hienlh.ppm.state.database.navigateFormRow
import com.hienlh.ppm.state.database.newRowValues
import com.hienlh.ppm.state.database.parseFormText
import com.hienlh.ppm.state.database.pictureUrl
import com.hienlh.ppm.state.database.readJsonDocuments
import com.hienlh.ppm.state.database.refKey
import com.hienlh.ppm.state.database.refLabel
import com.hienlh.ppm.state.database.refPaths
import com.hienlh.ppm.state.database.referenceId
import com.hienlh.ppm.state.database.referenceJoin
import com.hienlh.ppm.state.database.referencesMatching
import com.hienlh.ppm.state.database.rowsJson
import com.hienlh.ppm.state.database.rowsOfCells
import com.hienlh.ppm.state.database.saveScript
import com.hienlh.ppm.state.database.takesValues
import com.hienlh.ppm.state.database.takesWhere
import java.util.Base64

// ── 9d.1 Save Changes Preview & CASCADE Dialog (`grid-save-dialog.tsx`) ──────

@Composable
fun GridSaveDialog(
    preview: ChangesetPreview,
    defaultSchema: String = "public",
    dontAskAgainInitial: Boolean = false,
    saving: Boolean = false,
    errorMessage: String? = null,
    conflictFailure: ChangesetFailure? = null,
    onConfirmSave: (cascade: List<TableRef>, dontAskAgain: Boolean) -> Unit,
    onOpenScript: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var checkedCascade by remember(preview) { mutableStateOf<Set<String>>(emptySet()) }
    var dontAskAgain by remember(dontAskAgainInitial) { mutableStateOf(dontAskAgainInitial) }

    val fullScript = remember(preview, checkedCascade) {
        saveScript(preview, checkedCascade)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-save-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Save changes (${preview.statementCount} statement${if (preview.statementCount == 1) "" else "s"})",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }

        // 409 Conflict / Error Banner
        if (errorMessage != null || conflictFailure != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.error.opacity(0.14).color)
                    .cssBorder(tokens.error.color)
                    .padding(8.dp)
                    .testTag("db-save-error-banner"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText(
                    errorMessage ?: "Save failed",
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.error.color),
                )
                if (conflictFailure != null) {
                    val stmtIdx = conflictFailure.statementIndex
                    val stmtPart = if (stmtIdx != null) {
                        "Statement ${stmtIdx + 1} of ${conflictFailure.statementCount}"
                    } else {
                        "Commit (${conflictFailure.statementCount} statement${if (conflictFailure.statementCount == 1) "" else "s"})"
                    }
                    val affectedNote = conflictFailure.affected?.let { " (affected $it rows)" }.orEmpty()
                    val sqlNote = conflictFailure.sql?.let { ": $it" }.orEmpty()
                    BasicText(
                        "$stmtPart$affectedNote$sqlNote",
                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text.color),
                        modifier = Modifier.testTag("db-save-conflict-sql"),
                    )
                }
            }
        }

        // Delete references CASCADE section when deleted rows are still referenced
        if (preview.references.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.warning.opacity(0.12).color)
                    .cssBorder(tokens.warning.color)
                    .padding(8.dp)
                    .testTag("db-save-cascade-section"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Delete references CASCADE",
                        style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text.color),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SettingsButton(
                            label = "Check all",
                            modifier = Modifier.testTag("db-save-cascade-check-all"),
                            onClick = {
                                checkedCascade = preview.references.map { refKey(it) }.toSet()
                            },
                        )
                        SettingsButton(
                            label = "Uncheck all",
                            modifier = Modifier.testTag("db-save-cascade-uncheck-all"),
                            onClick = { checkedCascade = emptySet() },
                        )
                    }
                }
                for (ref in preview.references) {
                    val key = refKey(ref)
                    val checked = key in checkedCascade
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (checked) tokens.panel2.color else Color.Transparent)
                            .clickable {
                                checkedCascade = if (checked) checkedCascade - key else checkedCascade + key
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("db-save-cascade-item-${ref.table}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            if (checked) "☑" else "☐",
                            style = ppmText(12f, lineHeight = 15f).copy(
                                color = if (checked) tokens.accent.color else tokens.text3.color,
                            ),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                refLabel(ref, defaultSchema),
                                style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f)
                                    .copy(color = tokens.text.color),
                            )
                            val helpParts = refPaths(ref) +
                                (if (ref.cascadesInDb) listOf("ON DELETE CASCADE in the database already") else emptyList())
                            if (helpParts.isNotEmpty()) {
                                BasicText(
                                    helpParts.joinToString(" · "),
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f)
                                        .copy(color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }
        }

        // SQL Preview Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 180.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
                .testTag("db-save-sql-preview"),
        ) {
            BasicText(
                fullScript.ifEmpty { "-- No statements" },
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Don't ask again" only applies when there are no FK references to confirm
            if (preview.references.isEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { dontAskAgain = !dontAskAgain }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("db-save-dont-ask-checkbox"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText(
                        if (dontAskAgain) "☑" else "☐",
                        style = ppmText(12f, lineHeight = 15f).copy(
                            color = if (dontAskAgain) tokens.accent.color else tokens.text3.color,
                        ),
                    )
                    BasicText(
                        "Don't ask again",
                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsButton(
                    label = "Open script",
                    modifier = Modifier.testTag("db-save-open-script-btn"),
                    onClick = { onOpenScript(fullScript) },
                )
                SettingsButton(
                    label = "Cancel",
                    modifier = Modifier.testTag("db-save-cancel-btn"),
                    onClick = onClose,
                )
                SettingsButton(
                    label = if (saving) "Saving…" else "Save",
                    primary = true,
                    enabled = !saving,
                    modifier = Modifier.testTag("db-save-confirm-btn"),
                    onClick = {
                        onConfirmSave(cascadeTables(preview.references, checkedCascade), dontAskAgain)
                    },
                )
            }
        }
    }
}

// ── 9d.2 Tablet Inline Cell Editor Strip (`table-tab.tsx`) ───────────────────

@Composable
fun TabletInlineCellEditorBar(
    columnName: String,
    columnType: String,
    fieldKind: FormFieldKind,
    nullable: Boolean,
    hasFk: Boolean,
    initialText: String,
    onCommitValue: (DbCellValue) -> Unit,
    onOpenFkPicker: () -> Unit,
    onJumpToFkRow: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var draft by remember(columnName, initialText) { mutableStateOf(initialText) }
    var parseError by remember(columnName, initialText) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.accent.color, sides = CssSides.Bottom)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("db-inline-cell-editor"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                "Edit $columnName",
                style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text.color),
            )
            if (columnType.isNotEmpty()) {
                BasicText(
                    columnType,
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                )
            }
            if (fieldKind == FormFieldKind.BOOLEAN) {
                SettingsButton(
                    label = "TRUE",
                    primary = draft.equals("true", ignoreCase = true),
                    modifier = Modifier.testTag("db-inline-bool-true"),
                    onClick = { onCommitValue(DbCellValue.Bool(true)) },
                )
                SettingsButton(
                    label = "FALSE",
                    primary = draft.equals("false", ignoreCase = true),
                    modifier = Modifier.testTag("db-inline-bool-false"),
                    onClick = { onCommitValue(DbCellValue.Bool(false)) },
                )
            } else {
                SettingsTextInput(
                    value = draft,
                    onValueChange = {
                        draft = it
                        parseError = null
                    },
                    placeholder = "Enter value…",
                    mono = true,
                    modifier = Modifier.weight(1f).testTag("db-inline-cell-input"),
                )
            }
            if (nullable) {
                SettingsButton(
                    label = "NULL",
                    modifier = Modifier.testTag("db-inline-set-null"),
                    onClick = { onCommitValue(DbCellValue.Null) },
                )
            }
            if (hasFk) {
                SettingsButton(
                    label = "⋯",
                    modifier = Modifier.testTag("db-inline-fk-picker-btn"),
                    onClick = onOpenFkPicker,
                )
            }
            if (onJumpToFkRow != null) {
                SettingsButton(
                    label = "⊞",
                    modifier = Modifier.testTag("db-inline-fk-jump-btn"),
                    onClick = onJumpToFkRow,
                )
            }
            SettingsButton(
                label = "Apply",
                primary = true,
                modifier = Modifier.testTag("db-inline-apply-btn"),
                onClick = {
                    when (val res = parseFormText(draft, fieldKind)) {
                        is FormParse.Valid -> onCommitValue(res.value)
                        FormParse.Invalid -> parseError = "Invalid ${fieldKind.name.lowercase()} value"
                    }
                },
            )
            SettingsButton(
                label = "Cancel",
                modifier = Modifier.testTag("db-inline-cancel-btn"),
                onClick = onCancel,
            )
        }
        if (parseError != null) {
            BasicText(
                parseError.orEmpty(),
                style = ppmText(10f, lineHeight = 13f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("db-inline-parse-error"),
            )
        }
    }
}

// ── 9d.2 Tablet Form View (`form-view.tsx`, `form-view-model.ts`) ────────────

@Composable
fun DatabaseFormView(
    columns: List<String>,
    schemaMap: Map<String, GridColumnSchema>,
    dbType: String,
    rowIndex: Int,
    rowCount: Int,
    rowId: String,
    changeset: GridChangeset,
    readonly: Boolean,
    valueAt: (colKey: String) -> DbCellValue,
    onNavigateRow: (Int) -> Unit,
    onEditCell: (colKey: String, value: DbCellValue) -> Unit,
    onOpenFkPicker: (colKey: String, schema: GridColumnSchema) -> Unit,
    onJumpToFkRow: (colKey: String, schema: GridColumnSchema, value: DbCellValue) -> Unit,
    onCloseFormView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var columnFilter by remember { mutableStateOf("") }
    val filteredCols = remember(columns, columnFilter) {
        columns.filter { columnNameMatches(columnFilter, it) }
    }
    var activeEditingCol by remember(rowIndex) { mutableStateOf<String?>(null) }
    var activeDraftText by remember(rowIndex) { mutableStateOf("") }

    fun commitActiveDraftIfAny() {
        val col = activeEditingCol ?: return
        val sch = schemaMap[col]
        val kind = formFieldKindFor(classifyColumnType(dbType, sch?.type.orEmpty()))
        val parsed = parseFormText(activeDraftText, kind)
        if (parsed is FormParse.Valid) {
            onEditCell(col, parsed.value)
        }
        activeEditingCol = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("db-form-view"),
    ) {
        // Form View Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(
                label = "⏮ First",
                enabled = rowIndex > 0,
                modifier = Modifier.testTag("db-form-nav-first"),
                onClick = {
                    commitActiveDraftIfAny()
                    onNavigateRow(navigateFormRow(rowIndex, rowCount, FormNavigation.FIRST))
                },
            )
            SettingsButton(
                label = "◀ Prev",
                enabled = rowIndex > 0,
                modifier = Modifier.testTag("db-form-nav-prev"),
                onClick = {
                    commitActiveDraftIfAny()
                    onNavigateRow(navigateFormRow(rowIndex, rowCount, FormNavigation.PREVIOUS))
                },
            )
            BasicText(
                "Row ${formRowLabel(rowIndex, rowCount)}",
                style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text.color),
                modifier = Modifier.testTag("db-form-row-label"),
            )
            SettingsButton(
                label = "Next ▶",
                enabled = rowIndex < rowCount - 1,
                modifier = Modifier.testTag("db-form-nav-next"),
                onClick = {
                    commitActiveDraftIfAny()
                    onNavigateRow(navigateFormRow(rowIndex, rowCount, FormNavigation.NEXT))
                },
            )
            SettingsButton(
                label = "Last ⏭",
                enabled = rowIndex < rowCount - 1,
                modifier = Modifier.testTag("db-form-nav-last"),
                onClick = {
                    commitActiveDraftIfAny()
                    onNavigateRow(navigateFormRow(rowIndex, rowCount, FormNavigation.LAST))
                },
            )
            SettingsTextInput(
                value = columnFilter,
                onValueChange = { columnFilter = it },
                placeholder = "Filter columns (e.g. CA, id)…",
                mono = true,
                modifier = Modifier.width(200.dp).testTag("db-form-column-filter"),
            )
            SettingsButton(
                label = "Grid view",
                modifier = Modifier.testTag("db-form-back-to-grid"),
                onClick = {
                    commitActiveDraftIfAny()
                    onCloseFormView()
                },
            )
        }

        // Fields List
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (col in filteredCols) {
                val sch = schemaMap[col]
                val v = valueAt(col)
                val locked = readonly || isCellLocked(
                    LockColumn(pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true),
                    rowId,
                    changeset,
                )
                val mark = cellMark(changeset, rowId, col)
                val colKind = classifyColumnType(dbType, sch?.type.orEmpty())
                val fieldKind = formFieldKindFor(colKind)
                val hasFk = !sch?.fkTable.isNullOrBlank()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (mark.name) {
                                "MODIFIED" -> Color(0x33F59E0B)
                                "INSERTED" -> Color(0x2610B981)
                                "DELETED" -> Color(0x26EF4444)
                                else -> tokens.panel.color
                            },
                        )
                        .cssBorder(tokens.border.color)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("db-form-field-$col"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Column Label & Badges
                    Row(
                        modifier = Modifier.width(180.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (sch?.pk == true) {
                            BasicText(
                                if (sch.autoIncrement) "#" else "🔑",
                                style = ppmText(10f, lineHeight = 13f).copy(color = tokens.accent.color),
                            )
                        } else if (hasFk) {
                            BasicText(
                                "🔗",
                                style = ppmText(10f, lineHeight = 13f).copy(color = tokens.accent.color),
                            )
                        }
                        BasicText(
                            col,
                            style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f)
                                .copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!sch?.type.isNullOrBlank()) {
                            BasicText(
                                sch?.type.orEmpty(),
                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                            )
                        }
                    }

                    // Value or Editor
                    if (!locked && activeEditingCol == col && fieldKind != FormFieldKind.BINARY) {
                        SettingsTextInput(
                            value = activeDraftText,
                            onValueChange = { next ->
                                activeDraftText = next
                                val parsed = parseFormText(next, fieldKind)
                                if (parsed is FormParse.Valid) {
                                    onEditCell(col, parsed.value)
                                }
                            },
                            placeholder = col,
                            mono = true,
                            modifier = Modifier.weight(1f).testTag("db-form-input-$col"),
                        )
                        SettingsButton(
                            label = "Done",
                            primary = true,
                            onClick = { commitActiveDraftIfAny() },
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !locked && fieldKind != FormFieldKind.BINARY) {
                                    commitActiveDraftIfAny()
                                    if (fieldKind == FormFieldKind.BOOLEAN) {
                                        val nextBool = (v as? DbCellValue.Bool)?.value != true
                                        onEditCell(col, DbCellValue.Bool(nextBool))
                                    } else {
                                        activeEditingCol = col
                                        activeDraftText = formEditText(v)
                                    }
                                }
                                .padding(vertical = 2.dp)
                                .testTag("db-form-value-$col"),
                        ) {
                            BasicText(
                                formDisplayText(v),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(
                                    color = if (v == DbCellValue.Null || v == DbCellValue.NoField) tokens.text3.color else tokens.text.color,
                                ),
                            )
                        }
                    }

                    if (!locked && sch?.nullable != false) {
                        SettingsButton(
                            label = "NULL",
                            modifier = Modifier.testTag("db-form-null-$col"),
                            onClick = { onEditCell(col, DbCellValue.Null) },
                        )
                    }
                    if (!locked && hasFk && sch != null) {
                        SettingsButton(
                            label = "⋯",
                            modifier = Modifier.testTag("db-form-fk-pick-$col"),
                            onClick = { onOpenFkPicker(col, sch) },
                        )
                    }
                    if (hasFk && sch != null && v != DbCellValue.Null && v != DbCellValue.NoField) {
                        SettingsButton(
                            label = "⊞",
                            modifier = Modifier.testTag("db-form-fk-jump-$col"),
                            onClick = { onJumpToFkRow(col, sch, v) },
                        )
                    }
                }
            }
        }
    }
}

// ── 9d.4 Upgraded Cell Data Side Panel (`cell-data-panel.tsx`, `cell-data-formats.ts`) ──

@Composable
fun DatabaseCellDataSidePanel(
    cells: List<CellDataCell>,
    columnKeys: List<String>,
    columnNames: List<String>,
    valueAt: (column: Int, row: Int) -> DbCellValue,
    canEditSingleCell: Boolean = false,
    onApplySingleCellEdit: (DbCellValue) -> Unit = {},
    onSaveCellToFile: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var choice by remember { mutableStateOf<CellDataChoice>(CellDataChoice.Autodetect) }
    val detected = remember(cells) { autodetectFormat(cells) }
    val activeFormat = when (val c = choice) {
        CellDataChoice.Autodetect -> detected
        is CellDataChoice.Explicit -> c.format
    }
    val message = remember(activeFormat, cells) { cellDataMessage(activeFormat, cells) }

    val singleCell = cells.singleOrNull()
    val displayText = remember(activeFormat, cells, columnKeys, columnNames) {
        when (activeFormat) {
            CellDataFormat.JSON_ROW -> rowsJson(rowsOfCells(cells), columnKeys, columnNames, valueAt)
            CellDataFormat.JSON, CellDataFormat.JSON_EXPANDED -> cellsText(cells, pretty = true)
            else -> cellsText(cells, pretty = false)
        }
    }
    var editDraft by remember(singleCell?.column, singleCell?.row, singleCell?.value, activeFormat) {
        mutableStateOf(
            if (singleCell != null) {
                if (activeFormat == CellDataFormat.JSON || activeFormat == CellDataFormat.JSON_EXPANDED) {
                    cellsText(cells, pretty = true)
                } else {
                    formEditText(singleCell.value)
                }
            } else {
                ""
            },
        )
    }

    Column(
        modifier = modifier
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Start)
            .padding(8.dp)
            .testTag("db-cell-data-side-panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "CELL DATA${if (singleCell != null) " · ${singleCell.columnName}" else " (${cells.size})"}",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }

        // Format Selector Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsButton(
                label = choiceTitle(CellDataChoice.Autodetect, detected),
                primary = choice == CellDataChoice.Autodetect,
                modifier = Modifier.testTag("db-cell-format-autodetect"),
                onClick = { choice = CellDataChoice.Autodetect },
            )
            for (fmt in CELL_DATA_FORMATS) {
                SettingsButton(
                    label = fmt.shortLabel,
                    primary = (choice as? CellDataChoice.Explicit)?.format == fmt,
                    modifier = Modifier.testTag("db-cell-format-${fmt.id}"),
                    onClick = { choice = CellDataChoice.Explicit(fmt) },
                )
            }
        }

        // Body Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color)
                .padding(8.dp)
                .testTag("db-cell-data-content"),
        ) {
            if (message != null) {
                BasicText(
                    message,
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                    modifier = Modifier.testTag("db-cell-data-message"),
                )
            } else {
                when (activeFormat) {
                    CellDataFormat.FORM -> {
                        val fields = remember(singleCell) {
                            if (singleCell != null) cellDataFields(singleCell) else emptyList()
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (f in fields) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    BasicText(
                                        f.key,
                                        style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f)
                                            .copy(color = tokens.text2.color),
                                        modifier = Modifier.width(100.dp),
                                    )
                                    BasicText(
                                        f.value,
                                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f)
                                            .copy(color = if (f.isNull) tokens.text3.color else tokens.text.color),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }

                    CellDataFormat.PICTURE -> {
                        val v = singleCell?.value ?: DbCellValue.Null
                        val picUrl = remember(v) { pictureUrl(v) }
                        val decodedBitmap = remember(v) {
                            val b64 = when {
                                v is DbCellValue.Binary -> v.base64
                                picUrl != null && picUrl.contains(";base64,") -> picUrl.substringAfter(";base64,")
                                else -> null
                            }
                            if (b64 != null) {
                                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                                } else null
                            } else null
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (decodedBitmap != null) {
                                Image(
                                    bitmap = decodedBitmap.asImageBitmap(),
                                    contentDescription = "Cell Picture",
                                    modifier = Modifier.fillMaxWidth().testTag("db-cell-data-picture-img"),
                                )
                            } else if (picUrl != null) {
                                BasicText(
                                    "Picture preview ready (${picUrl.substringBefore(";base64,")})",
                                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.accent.color),
                                    modifier = Modifier.testTag("db-cell-data-picture-ready"),
                                )
                            }
                            if (v is DbCellValue.Binary && v.truncated) {
                                BasicText(
                                    "Preview truncated (${formatByteSize(v.size)} total) — use Save cell to file for full bytes.",
                                    style = ppmText(10f, lineHeight = 14f).copy(color = tokens.warning.color),
                                    modifier = Modifier.testTag("db-cell-data-truncated-notice"),
                                )
                            }
                        }
                    }

                    CellDataFormat.HTML -> {
                        val rawHtml = singleCell?.let { formEditText(it.value) }.orEmpty()
                        val safeDoc = remember(rawHtml) { htmlDocument(rawHtml) }
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BasicText(
                                "Sandboxed HTML Preview (CSP: scripts & network disabled)",
                                style = ppmText(10f, lineHeight = 13f).copy(color = tokens.text3.color),
                                modifier = Modifier.testTag("db-cell-data-html-header"),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .semantics { contentDescription = safeDoc }
                                    .testTag("db-cell-data-html-webview"),
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        WebView(ctx).apply {
                                            settings.javaScriptEnabled = false
                                            settings.blockNetworkLoads = true
                                            settings.allowFileAccess = false
                                            settings.allowContentAccess = false
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView?,
                                                    request: WebResourceRequest?,
                                                ): Boolean = true
                                            }
                                        }
                                    },
                                    update = { webView ->
                                        webView.loadDataWithBaseURL(
                                            "about:blank",
                                            safeDoc,
                                            "text/html",
                                            "utf-8",
                                            null,
                                        )
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (canEditSingleCell && singleCell != null && singleCell.value !is DbCellValue.Binary) {
                                SettingsTextInput(
                                    value = editDraft,
                                    onValueChange = { editDraft = it },
                                    placeholder = singleCell.columnName,
                                    mono = true,
                                    modifier = Modifier.fillMaxWidth().testTag("db-cell-data-editor-input"),
                                )
                            }
                            BasicText(
                                displayText,
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                        }
                    }
                }
            }
        }

        // Bottom Actions Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(
                label = "Copy",
                modifier = Modifier.testTag("db-cell-data-copy-btn"),
                onClick = { onCopyText(displayText) },
            )
            if (singleCell != null && singleCell.value != DbCellValue.Null && singleCell.value != DbCellValue.NoField) {
                SettingsButton(
                    label = "Save to file",
                    modifier = Modifier.testTag("db-save-cell-file-btn"),
                    onClick = onSaveCellToFile,
                )
            }
            if (canEditSingleCell && singleCell != null && singleCell.value !is DbCellValue.Binary) {
                val targetKind = if (activeFormat == CellDataFormat.JSON || activeFormat == CellDataFormat.JSON_EXPANDED) {
                    FormFieldKind.JSON
                } else {
                    FormFieldKind.TEXT
                }
                SettingsButton(
                    label = "Apply edit",
                    primary = true,
                    modifier = Modifier.testTag("db-cell-data-apply-btn"),
                    onClick = {
                        val parsed = parseFormText(editDraft, targetKind)
                        if (parsed is FormParse.Valid) {
                            onApplySingleCellEdit(parsed.value)
                        }
                    },
                )
            }
        }
    }
}

// ── 9d.4 JSON Document Dialogs (`json-row-dialog.tsx`) ──────────────────────

@Composable
fun EditRowJsonDialog(
    rowId: String,
    columns: List<DocumentColumn>,
    currentRow: Map<String, DbCellValue>,
    storedRow: Map<String, DbCellValue>,
    key: Map<String, DbCellValue>,
    changeset: GridChangeset,
    initialJsonText: String,
    onApplyChanges: (List<CellChange>) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var text by remember(initialJsonText) { mutableStateOf(initialJsonText) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-edit-row-json-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Edit row as JSON document",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }
        SettingsTextInput(
            value = text,
            onValueChange = {
                text = it
                errorMsg = null
            },
            placeholder = "{ \"col\": \"value\" }",
            mono = true,
            modifier = Modifier.fillMaxWidth().testTag("db-edit-row-json-input"),
        )
        if (errorMsg != null) {
            BasicText(
                errorMsg.orEmpty(),
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("db-edit-row-json-error"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(label = "Cancel", onClick = onClose)
            Spacer(modifier = Modifier.width(6.dp))
            SettingsButton(
                label = "Apply",
                primary = true,
                modifier = Modifier.testTag("db-edit-row-json-apply"),
                onClick = {
                    when (val parsed = readJsonDocuments(text, single = true)) {
                        is JsonDocumentsResult.Err -> errorMsg = parsed.error
                        is JsonDocumentsResult.Ok -> {
                            val doc = parsed.docs.first()
                            documentChanges(
                                rowId = rowId,
                                columns = columns,
                                currentRow = currentRow,
                                storedRow = storedRow,
                                key = key,
                                doc = doc,
                                changeset = changeset,
                            ).fold(
                                onSuccess = { changes ->
                                    onApplyChanges(changes)
                                    onClose()
                                },
                                onFailure = { err ->
                                    errorMsg = err.message ?: "Invalid document"
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun AddJsonDocumentDialog(
    columns: List<DocumentColumn>,
    nextRowId: () -> String,
    onAddRows: (List<NewRow>) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var text by remember { mutableStateOf("{\n  \n}") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-add-json-doc-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Add JSON document(s) (Object, Array, or JSON Lines)",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }
        SettingsTextInput(
            value = text,
            onValueChange = {
                text = it
                errorMsg = null
            },
            placeholder = "{ \"col\": \"value\" }",
            mono = true,
            modifier = Modifier.fillMaxWidth().testTag("db-add-json-doc-input"),
        )
        if (errorMsg != null) {
            BasicText(
                errorMsg.orEmpty(),
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("db-add-json-doc-error"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(label = "Cancel", onClick = onClose)
            Spacer(modifier = Modifier.width(6.dp))
            SettingsButton(
                label = "Add",
                primary = true,
                modifier = Modifier.testTag("db-add-json-doc-submit"),
                onClick = {
                    when (val parsed = readJsonDocuments(text, single = false)) {
                        is JsonDocumentsResult.Err -> errorMsg = parsed.error
                        is JsonDocumentsResult.Ok -> {
                            val newRows = mutableListOf<NewRow>()
                            for (doc in parsed.docs) {
                                val res = newRowValues(columns, doc)
                                if (res.isFailure) {
                                    errorMsg = res.exceptionOrNull()?.message ?: "Invalid document"
                                    return@SettingsButton
                                }
                                newRows += NewRow(rowId = nextRowId(), values = res.getOrThrow())
                            }
                            onAddRows(newRows)
                            onClose()
                        }
                    }
                },
            )
        }
    }
}

// ── 9d.4 References Side Panel (`references-panel.tsx`) ──────────────────────

@Composable
fun ReferencesSidePanel(
    references: TableReferences,
    activeReference: GridReference?,
    defaultSchema: String = "public",
    onSelectReference: (GridReference?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    var filterText by remember { mutableStateOf("") }
    val outgoing = remember(references.outgoing, filterText, defaultSchema) {
        referencesMatching(references.outgoing, filterText, defaultSchema)
    }
    val incoming = remember(references.incoming, filterText, defaultSchema) {
        referencesMatching(references.incoming, filterText, defaultSchema)
    }

    Column(
        modifier = modifier
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Start)
            .padding(8.dp)
            .testTag("db-references-side-panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "REFERENCES",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }

        SettingsTextInput(
            value = filterText,
            onValueChange = { filterText = it },
            placeholder = "Filter references…",
            mono = true,
            modifier = Modifier.fillMaxWidth().testTag("db-references-filter-input"),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (outgoing.isNotEmpty()) {
                BasicText(
                    "References tables (${outgoing.size})",
                    style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color),
                )
                for (ref in outgoing) {
                    val isSelected = activeReference != null && referenceId(activeReference) == referenceId(ref)
                    ReferenceItemRow(
                        ref = ref,
                        selected = isSelected,
                        onClick = { onSelectReference(if (isSelected) null else ref) },
                    )
                }
            }
            if (incoming.isNotEmpty()) {
                BasicText(
                    "Dependent tables (${incoming.size})",
                    style = ppmText(10f, FontWeight.SemiBold, lineHeight = 13f).copy(color = tokens.text3.color),
                )
                for (ref in incoming) {
                    val isSelected = activeReference != null && referenceId(activeReference) == referenceId(ref)
                    ReferenceItemRow(
                        ref = ref,
                        selected = isSelected,
                        onClick = { onSelectReference(if (isSelected) null else ref) },
                    )
                }
            }
            if (outgoing.isEmpty() && incoming.isEmpty()) {
                BasicText(
                    "No foreign key references",
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }
        }
    }
}

@Composable
private fun ReferenceItemRow(
    ref: GridReference,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) tokens.accent.opacity(0.18).color else tokens.panel2.color)
            .cssBorder(if (selected) tokens.accent.color else tokens.border.color)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("db-reference-item-${ref.targetTable}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText(
            "${if (ref.direction == "out") "→" else "←"} ${ref.targetTable}",
            style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f).copy(color = tokens.text.color),
        )
        BasicText(
            referenceJoin(ref),
            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
        )
    }
}

// ── 9d.4 Generate SQL Dialog (`generate-sql-dialog.tsx`) ─────────────────────

@Composable
fun GenerateSqlDialog(
    columns: List<String>,
    keyColumns: List<String>,
    rows: List<SqlSourceRow>,
    target: CopySqlTarget,
    onCopySql: (String) -> Unit,
    onOpenInQueryTab: (String) -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var statement by remember { mutableStateOf(GeneratedStatement.INSERT) }
    var checkedValues by remember(columns) { mutableStateOf(columns.toSet()) }
    var checkedWhere by remember(columns, keyColumns) {
        mutableStateOf(if (keyColumns.isNotEmpty()) keyColumns.toSet() else columns.take(1).toSet())
    }

    val generated = remember(statement, rows, checkedValues, checkedWhere, target) {
        generateSql(
            statement = statement,
            rows = rows,
            valueColumns = columns.filter { it in checkedValues },
            whereColumns = columns.filter { it in checkedWhere },
            target = target,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(12.dp)
            .testTag("db-generate-sql-dialog"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Generate SQL (${rows.size} row${if (rows.size == 1) "" else "s"})",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            SettingsButton(label = "✕", onClick = onClose)
        }

        // Statement Type Picker
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (st in STATEMENT_TYPES) {
                SettingsButton(
                    label = st.label,
                    primary = statement == st,
                    modifier = Modifier.testTag("db-gen-sql-type-${st.id}"),
                    onClick = { statement = st },
                )
            }
        }

        // Value Columns & WHERE Columns Checklists
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (takesValues(statement)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "Value columns",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsButton(label = "All", onClick = { checkedValues = columns.toSet() })
                            SettingsButton(label = "None", onClick = { checkedValues = emptySet() })
                            SettingsButton(label = "No key", onClick = { checkedValues = (columns - keyColumns.toSet()).toSet() })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (col in columns) {
                            val on = col in checkedValues
                            SettingsButton(
                                label = "${if (on) "☑" else "☐"} $col",
                                primary = on,
                                onClick = { checkedValues = if (on) checkedValues - col else checkedValues + col },
                            )
                        }
                    }
                }
            }
            if (takesWhere(statement)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "WHERE columns",
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsButton(label = "Key", onClick = { checkedWhere = keyColumns.toSet() })
                            SettingsButton(label = "All", onClick = { checkedWhere = columns.toSet() })
                            SettingsButton(label = "None", onClick = { checkedWhere = emptySet() })
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (col in columns) {
                            val on = col in checkedWhere
                            SettingsButton(
                                label = "${if (on) "☑" else "☐"} $col",
                                primary = on,
                                onClick = { checkedWhere = if (on) checkedWhere - col else checkedWhere + col },
                            )
                        }
                    }
                }
            }
        }

        // SQL Preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp, max = 160.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.bgSolid.color)
                .cssBorder(tokens.border.color)
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
                .testTag("db-gen-sql-preview"),
        ) {
            BasicText(
                generated.notice ?: generated.preview,
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(
                    color = if (generated.notice != null) tokens.text3.color else tokens.text.color,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(
                label = "Copy",
                enabled = generated.sql.isNotEmpty(),
                modifier = Modifier.testTag("db-gen-sql-copy-btn"),
                onClick = { onCopySql(generated.sql) },
            )
            Spacer(modifier = Modifier.width(6.dp))
            SettingsButton(
                label = "Open in Query tab",
                primary = true,
                enabled = generated.sql.isNotEmpty(),
                modifier = Modifier.testTag("db-gen-sql-open-query-btn"),
                onClick = {
                    onOpenInQueryTab(generated.sql)
                    onClose()
                },
            )
        }
    }
}

// ── 9d.5 Phone Row Form Bottom Sheet (`row-form-sheet.tsx`) ──────────────────

/**
 * Phone bottom-sheet row editor (`phase-09d-db-sua.md` §9d.5):
 * - Tapping any data cell on phone opens this sheet (no inline cell editing on phone).
 * - Every field draft is flushed into [onCommitField] BEFORE `Prev`, `Next`, `Save`, or closing the sheet
 *   so typed text is never lost when navigating between rows.
 */
@Composable
fun PhoneRowFormSheet(
    rowIndex: Int,
    rowCount: Int,
    rowId: String,
    columns: List<String>,
    schemaMap: Map<String, GridColumnSchema>,
    dbType: String,
    changeset: GridChangeset,
    readonly: Boolean,
    valueAt: (colKey: String) -> DbCellValue,
    onCommitField: (rowIndex: Int, colKey: String, value: DbCellValue) -> Unit,
    onNavigateRow: (Int) -> Unit,
    onDeleteRow: () -> Unit,
    onRevertRow: () -> Unit,
    onOpenFkPicker: (colKey: String, schema: GridColumnSchema) -> Unit,
    onJumpToFkRow: (colKey: String, schema: GridColumnSchema, value: DbCellValue) -> Unit,
    onSaveChanges: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val isNew = isNewRowId(rowId)
    val isDeleted = rowId in changeset.deleted

    // Per-column text drafts for the current rowIndex so typing in any input can be flushed before Prev/Next/Save
    val drafts = remember(rowIndex, rowId) {
        mutableStateMapOf<String, String>().apply {
            for (col in columns) {
                put(col, formEditText(valueAt(col)))
            }
        }
    }
    val dirtyCols = remember(rowIndex, rowId) { mutableStateMapOf<String, Boolean>() }

    fun flushDraftsForCurrentRow() {
        if (readonly || isDeleted) return
        for ((col, isDirty) in dirtyCols.entries.toList()) {
            if (!isDirty) continue
            val text = drafts[col] ?: continue
            val sch = schemaMap[col]
            val locked = isCellLocked(
                LockColumn(pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true),
                rowId,
                changeset,
            )
            if (locked) continue
            val kind = formFieldKindFor(classifyColumnType(dbType, sch?.type.orEmpty()))
            val parsed = parseFormText(text, kind)
            if (parsed is FormParse.Valid) {
                onCommitField(rowIndex, col, parsed.value)
            }
            dirtyCols[col] = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .padding(12.dp)
            .testTag("db-phone-row-form-sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Sheet Header (`Prev` / `Row X / Y` / `Next` / `Save` / `✕`)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "◀ Prev",
                    enabled = rowIndex > 0,
                    modifier = Modifier.testTag("db-phone-form-prev-btn"),
                    onClick = {
                        flushDraftsForCurrentRow()
                        onNavigateRow(rowIndex - 1)
                    },
                )
                BasicText(
                    if (isNew) "New Row (${rowIndex + 1}/$rowCount)" else "Row ${rowIndex + 1} / $rowCount",
                    style = ppmText(12f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                        .copy(color = tokens.text.color),
                    modifier = Modifier.testTag("db-phone-form-title"),
                )
                SettingsButton(
                    label = "Next ▶",
                    enabled = rowIndex < rowCount - 1,
                    modifier = Modifier.testTag("db-phone-form-next-btn"),
                    onClick = {
                        flushDraftsForCurrentRow()
                        onNavigateRow(rowIndex + 1)
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (readonly) {
                    BasicText(
                        "Read-only",
                        style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f)
                            .copy(color = tokens.text3.color),
                        modifier = Modifier.testTag("db-phone-form-readonly-badge"),
                    )
                } else {
                    SettingsButton(
                        label = "Save",
                        primary = true,
                        modifier = Modifier.testTag("db-phone-form-save-btn"),
                        onClick = {
                            flushDraftsForCurrentRow()
                            onSaveChanges()
                        },
                    )
                }
                SettingsButton(
                    label = "✕",
                    modifier = Modifier.testTag("db-phone-form-close-btn"),
                    onClick = {
                        flushDraftsForCurrentRow()
                        onClose()
                    },
                )
            }
        }

        // Scrollable Fields List
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (col in columns) {
                val sch = schemaMap[col]
                val v = valueAt(col)
                val locked = readonly || isCellLocked(
                    LockColumn(pk = sch?.pk == true, autoIncrement = sch?.autoIncrement == true),
                    rowId,
                    changeset,
                )
                val colKind = classifyColumnType(dbType, sch?.type.orEmpty())
                val fieldKind = formFieldKindFor(colKind)
                val hasFk = !sch?.fkTable.isNullOrBlank()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color)
                        .padding(8.dp)
                        .testTag("db-phone-form-field-$col"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                col,
                                style = ppmText(11f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 14f)
                                    .copy(color = tokens.text.color),
                            )
                            if (sch?.pk == true) {
                                BasicText(
                                    if (sch.autoIncrement) "(auto PK)" else "(PK)",
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.accent.color),
                                )
                            }
                            if (!sch?.type.isNullOrBlank()) {
                                BasicText(
                                    sch?.type.orEmpty(),
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!locked && sch?.nullable != false) {
                                SettingsButton(
                                    label = "NULL",
                                    primary = v == DbCellValue.Null,
                                    modifier = Modifier.testTag("db-phone-form-null-$col"),
                                    onClick = {
                                        drafts[col] = ""
                                        dirtyCols[col] = false
                                        onCommitField(rowIndex, col, DbCellValue.Null)
                                    },
                                )
                            }
                            if (!locked && hasFk && sch != null) {
                                SettingsButton(
                                    label = "⋯",
                                    modifier = Modifier.testTag("db-phone-form-fk-pick-$col"),
                                    onClick = {
                                        flushDraftsForCurrentRow()
                                        onOpenFkPicker(col, sch)
                                    },
                                )
                            }
                            if (hasFk && sch != null && v != DbCellValue.Null && v != DbCellValue.NoField) {
                                SettingsButton(
                                    label = "⊞",
                                    modifier = Modifier.testTag("db-phone-form-fk-jump-$col"),
                                    onClick = {
                                        flushDraftsForCurrentRow()
                                        onJumpToFkRow(col, sch, v)
                                    },
                                )
                            }
                        }
                    }

                    if (locked || fieldKind == FormFieldKind.BINARY) {
                        BasicText(
                            when (v) {
                                DbCellValue.NoField -> NO_FIELD_TEXT
                                DbCellValue.Null -> NULL_TEXT
                                else -> formDisplayText(v)
                            },
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                        )
                    } else if (fieldKind == FormFieldKind.BOOLEAN) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val currentBool = (v as? DbCellValue.Bool)?.value
                            SettingsButton(
                                label = "TRUE",
                                primary = currentBool == true,
                                modifier = Modifier.testTag("db-phone-form-bool-true-$col"),
                                onClick = {
                                    drafts[col] = "true"
                                    dirtyCols[col] = false
                                    onCommitField(rowIndex, col, DbCellValue.Bool(true))
                                },
                            )
                            SettingsButton(
                                label = "FALSE",
                                primary = currentBool == false,
                                modifier = Modifier.testTag("db-phone-form-bool-false-$col"),
                                onClick = {
                                    drafts[col] = "false"
                                    dirtyCols[col] = false
                                    onCommitField(rowIndex, col, DbCellValue.Bool(false))
                                },
                            )
                        }
                    } else {
                        SettingsTextInput(
                            value = drafts[col].orEmpty(),
                            onValueChange = { nextText ->
                                drafts[col] = nextText
                                dirtyCols[col] = true
                                val parsed = parseFormText(nextText, fieldKind)
                                if (parsed is FormParse.Valid) {
                                    onCommitField(rowIndex, col, parsed.value)
                                    dirtyCols[col] = false
                                }
                            },
                            placeholder = if (v == DbCellValue.Null) NULL_TEXT else if (v == DbCellValue.NoField) NO_FIELD_TEXT else "Enter value…",
                            mono = true,
                            modifier = Modifier.fillMaxWidth().testTag("db-phone-form-input-$col"),
                        )
                    }
                }
            }
        }

        // Row Actions Footer (`Revert row` / `Delete row`)
        if (!readonly) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    label = "Revert row",
                    modifier = Modifier.testTag("db-phone-form-revert-row"),
                    onClick = {
                        dirtyCols.clear()
                        onRevertRow()
                    },
                )
                SettingsButton(
                    label = if (isDeleted) "Deleted" else "Delete row",
                    modifier = Modifier.testTag("db-phone-form-delete-row"),
                    onClick = {
                        dirtyCols.clear()
                        onDeleteRow()
                    },
                )
            }
        }
    }
}
