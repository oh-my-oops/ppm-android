package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.PpmError
import com.hienlh.ppm.client.XlsxSheet
import com.hienlh.ppm.client.xlsxSheet
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** `.xlsx` and `.xlsm` open in the read-only preview; `.xls` stays a file the phone does not open. */
fun isPhoneXlsxPath(path: String?): Boolean {
    val ext = path.orEmpty().substringAfterLast('/').substringAfterLast('\\').substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext == "xlsx" || ext == "xlsm"
}

/**
 * Which sheet to load after loading [requested] failed with HTTP [status], or null to keep the
 * sheet on screen (`sheetAfterError`): only a 404 (the sheet is gone) moves to the first sheet.
 */
fun xlsxSheetAfterError(status: Int?, requested: Int): Int? = if (status == 404 && requested != 0) 0 else null

const val XLSX_MIN_COL_WIDTH = 64
const val XLSX_MAX_COL_WIDTH = 320
const val XLSX_WIDTH_SAMPLE_ROWS = 200

/** Each column's width (`columnWidths`): its longest value in the first 200 rows, held between 64 and 320. */
fun xlsxColumnWidths(rows: List<List<String?>>, columns: Int): List<Int> {
    val longest = IntArray(columns)
    for (r in 0 until minOf(rows.size, XLSX_WIDTH_SAMPLE_ROWS)) {
        val row = rows[r]
        for (c in 0 until minOf(row.size, columns)) {
            val v = row[c] ?: continue
            if (v.isEmpty()) continue
            val len = v.split('\n').maxOf { it.length }
            if (len > longest[c]) longest[c] = len
        }
    }
    return longest.map { len -> Math.round(minOf(XLSX_MAX_COL_WIDTH.toDouble(), maxOf(XLSX_MIN_COL_WIDTH.toDouble(), len * 7.2 + 18))).toInt() }
}

/** `A` … `Z`, `AA` …: the letters of the 0-based [index]-th column. */
fun xlsxColumnLetters(index: Int): String {
    var n = index + 1
    val out = StringBuilder()
    while (n > 0) {
        val r = (n - 1) % 26
        out.insert(0, ('A' + r))
        n = (n - 1) / 26
    }
    return out.toString()
}

data class XlsxCell(val row: Int, val col: Int)

data class PhoneXlsxState(
    val data: XlsxSheet? = null,
    /** The sheet picked and not yet loaded; its tab is lit until it loads or fails. */
    val pending: Int? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val selected: XlsxCell? = null,
) {
    val litSheet: Int get() = pending ?: data?.sheet ?: 0
}

/**
 * `xlsx-preview.tsx`'s state: one sheet at a time, only the latest request's answer applied; a
 * failed reload keeps the sheet on screen with the reason above it, and a 404 for another sheet
 * goes back to the first.
 */
class PhoneXlsxController(
    private val load: suspend (sheet: Int) -> XlsxSheet,
    scope: CoroutineScope,
) : AutoCloseable {
    private val owner = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(PhoneXlsxState())
    val state: StateFlow<PhoneXlsxState> = mutable.asStateFlow()
    private var request = 0
    private var job: Job? = null

    fun start() = fetch(0, quiet = false)

    /** A sheet tab. */
    fun pick(sheet: Int) {
        if (sheet != mutable.value.litSheet) fetch(sheet, quiet = false)
    }

    /** The file changed: reload, keeping the sheet on screen (a tab picked but not loaded is still the one wanted). */
    fun fileChanged() {
        val wanted = mutable.value.pending
        fetch(wanted ?: mutable.value.data?.sheet ?: 0, quiet = wanted == null)
    }

    fun select(cell: XlsxCell) {
        mutable.value = mutable.value.copy(selected = cell)
    }

    private fun fetch(target: Int, quiet: Boolean) {
        val id = ++request
        job?.cancel()
        mutable.value = mutable.value.copy(loading = true, pending = if (quiet) mutable.value.pending else target)
        job = owner.launch {
            try {
                val sheet = load(target)
                if (id != request) return@launch
                val current = mutable.value
                mutable.value = current.copy(
                    data = sheet, error = null, pending = null, loading = false,
                    selected = if (sheet.sheet != current.data?.sheet) null else current.selected,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (id != request) return@launch
                val instead = xlsxSheetAfterError((error as? PpmError.Server)?.status, target)
                if (instead != null) {
                    fetch(instead, quiet)
                    return@launch
                }
                mutable.value = mutable.value.copy(error = error.message ?: "Failed to read workbook", pending = null, loading = false)
            }
        }
    }

    override fun close() = owner.cancel()
}

/** The xlsx preview of a file tab: reads through `/files/xlsx-sheet` and follows the file's changes. */
@Composable
internal fun PhoneXlsxViewerHost(model: AppModel, project: String?, path: String, modifier: Modifier = Modifier) {
    val api = model.api
    val controller = remember(api, project, path) {
        api?.let { client -> PhoneXlsxController({ sheet -> client.xlsxSheet(project, path, sheet) }, model.scope) }
    }
    DisposableEffect(controller) {
        controller?.start()
        onDispose { controller?.close() }
    }
    LaunchedEffect(controller, project, path) {
        if (controller == null || project == null) return@LaunchedEffect
        model.fileChangedEvents.collect { (p, changed) -> if (p == project && changed == path) controller.fileChanged() }
    }
    if (controller == null) {
        XlsxMessage("Failed to load workbook.", "Connect to a server to read it.", modifier)
        return
    }
    PhoneXlsxViewer(controller, modifier)
}

private val ROW_HEIGHT = 32.dp
private val ROW_NUMBER_WIDTH = 52.dp

@Composable
internal fun PhoneXlsxViewer(controller: PhoneXlsxController, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    val st by controller.state.collectAsState()
    val data = st.data
    if (data == null) {
        if (st.loading) XlsxMessage(null, "Reading workbook...", modifier, loading = true)
        else XlsxMessage("Failed to load workbook.", st.error.orEmpty(), modifier)
        return
    }
    val rows = data.rows
    val columns = data.columns
    val widths = remember(rows, columns) { xlsxColumnWidths(rows, columns) }
    val totalWidth = ROW_NUMBER_WIDTH + widths.sumOf { it }.dp
    val horizontal = rememberScrollState()
    val vertical = rememberLazyListState()
    // A different sheet starts at its top-left corner.
    LaunchedEffect(data.sheet) {
        horizontal.scrollTo(0)
        vertical.scrollToItem(0)
    }
    val mono = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)
    val grouped = NumberFormat.getIntegerInstance(Locale.US)
    val notices = listOfNotNull(
        "Showing the first ${grouped.format(rows.size)} rows".takeIf { data.truncatedRows },
        "Showing the first $columns columns".takeIf { data.truncatedColumns },
    )

    Column(modifier.fillMaxSize().testTag("phone-xlsx-viewer")) {
        st.error?.let {
            BasicText(it, Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 6.dp).testTag("phone-xlsx-error"),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
        }
        if (notices.isNotEmpty()) {
            BasicText(notices.joinToString(" · "), Modifier.fillMaxWidth().background(tokens.panel2.color)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 6.dp).testTag("phone-xlsx-notice"),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
        }

        // The cell bar: the picked cell's address and its whole value, selectable, like Excel's formula bar.
        val selected = st.selected
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).cssBorder(tokens.border.color, sides = CssSides.Bottom)) {
            Box(Modifier.width(64.dp).heightIn(min = 36.dp).cssBorder(tokens.border.color, sides = CssSides.End), contentAlignment = Alignment.Center) {
                BasicText(selected?.let { "${xlsxColumnLetters(it.col)}${it.row + 1}" }.orEmpty(), Modifier.testTag("phone-xlsx-cell-ref"),
                    style = mono.copy(color = tokens.text2.color))
            }
            Box(Modifier.weight(1f).heightIn(max = 96.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
                if (selected != null) {
                    SelectionContainer {
                        BasicText(rows.getOrNull(selected.row)?.getOrNull(selected.col).orEmpty(), Modifier.testTag("phone-xlsx-cell-value"),
                            style = mono.copy(color = tokens.text.color))
                    }
                } else {
                    BasicText("Tap a cell to see its full value", style = mono.copy(color = tokens.text3.color))
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                BasicText("Empty sheet", Modifier.align(Alignment.Center), style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color))
            } else {
                Column(Modifier.fillMaxHeight().horizontalScroll(horizontal).width(totalWidth)) {
                    Row(Modifier.height(ROW_HEIGHT).background(tokens.panel.color).cssBorder(tokens.border.color, sides = CssSides.Bottom)) {
                        StickyNumber(horizontal.value, tokens.panel.color) {}
                        widths.forEachIndexed { c, w ->
                            Box(Modifier.width(w.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                BasicText(xlsxColumnLetters(c), style = mono.copy(color = tokens.text3.color, fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                    LazyColumn(state = vertical, modifier = Modifier.weight(1f).testTag("phone-xlsx-grid")) {
                        itemsIndexed(rows) { r, row ->
                            Row(Modifier.height(ROW_HEIGHT).drawBehind {
                                drawLine(tokens.border.opacity(0.5).color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
                            }) {
                                StickyNumber(horizontal.value, tokens.panel.color) {
                                    BasicText("${r + 1}", style = mono.copy(color = tokens.text3.color, textAlign = TextAlign.End))
                                }
                                widths.forEachIndexed { c, w ->
                                    val isSelected = selected?.row == r && selected.col == c
                                    Box(
                                        Modifier.width(w.dp).fillMaxHeight()
                                            .then(if (isSelected) Modifier.cssBorder(tokens.accent.color, width = 2.dp) else Modifier)
                                            .ppmClickable(onClickLabel = "${xlsxColumnLetters(c)}${r + 1}") { controller.select(XlsxCell(r, c)) }
                                            .padding(horizontal = 8.dp)
                                            .testTag("phone-xlsx-cell-${xlsxColumnLetters(c)}${r + 1}"),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        BasicText(row.getOrNull(c).orEmpty(), style = mono.copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (data.sheets.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(tokens.panel2.color).cssBorder(tokens.border.color, sides = CssSides.Top)
                    .horizontalScroll(rememberScrollState()).testTag("phone-xlsx-sheets"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                data.sheets.forEachIndexed { i, name ->
                    val lit = i == st.litSheet
                    Box(
                        Modifier.heightIn(min = KitSpec.MinTouchTarget)
                            .background(if (lit) tokens.panel.color else tokens.panel2.color)
                            .cssBorder(tokens.border.color, sides = CssSides.End)
                            .ppmClickable(onClickLabel = name.ifEmpty { "Sheet ${i + 1}" }) { controller.pick(i) }
                            .padding(horizontal = 16.dp)
                            .testTag("phone-xlsx-sheet-$i"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(name.ifEmpty { "Sheet ${i + 1}" }, style = ppmText(12.5f, if (lit) FontWeight.Medium else FontWeight.Normal, lineHeight = 16f)
                            .copy(color = if (lit) tokens.text.color else tokens.text2.color))
                    }
                }
                if (st.loading) PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text2.color, Modifier.padding(horizontal = 12.dp), spinning = true)
            }
        }
    }
}

/** The row-number cell, held at the grid's left edge while the rows scroll sideways. */
@Composable
private fun StickyNumber(scrollX: Int, background: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier.width(ROW_NUMBER_WIDTH).fillMaxHeight().zIndex(1f)
            .graphicsLayer { translationX = scrollX.toFloat() }
            .background(background).cssBorder(tokens.border.color, sides = CssSides.End).padding(end = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) { content() }
}

@Composable
private fun XlsxMessage(title: String?, detail: String, modifier: Modifier, loading: Boolean = false) {
    val tokens = LocalPpmTokens.current
    Box(modifier.fillMaxSize().padding(24.dp).testTag("phone-xlsx-message"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(if (loading) ProductIcon.Loader2 else ProductIcon.FileWarning, if (loading) 20.dp else 40.dp,
                (if (loading) tokens.text2 else tokens.text3).color, Modifier.size(if (loading) 20.dp else 40.dp), spinning = loading)
            title?.let { BasicText(it, style = ppmText(14f, lineHeight = 19f).copy(color = tokens.text2.color, textAlign = TextAlign.Center)) }
            if (detail.isNotEmpty()) BasicText(detail, style = ppmText(12.5f, lineHeight = 17f).copy(color = tokens.text3.color, textAlign = TextAlign.Center))
        }
    }
}
