package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.WebCursor
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.files.CsvDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** CSV table before editing: native virtual rows, sticky sortable headers and resizable columns. */
@Composable
internal fun FileCsvView(content: String, wrap: Boolean) {
    var document by remember(content) { mutableStateOf<CsvDocument?>(null) }
    LaunchedEffect(content) { document = withContext(Dispatchers.Default) { CsvDocument.parse(content) } }
    val csv = document
    if (csv == null) { FileMessage("Loading CSV…"); return }
    if (csv.headers.isEmpty()) { FileMessage("Empty CSV file"); return }
    val colors = LocalPpmTokens.current
    val widths = remember(csv.headers.size) { mutableStateListOf<Float>().also { w -> repeat(csv.headers.size) { w.add(150f) } } }
    var sorting by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var ordered by remember(csv) { mutableStateOf(csv.rows.indices.toList()) }
    LaunchedEffect(csv, sorting) { ordered = withContext(Dispatchers.Default) { csv.sortedRows(sorting?.first, sorting?.second == true) } }
    val horizontal = rememberScrollState()
    val vertical = rememberLazyListState()
    val density = LocalDensity.current.density
    val style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = colors.text2.color)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val tableWidth = maxOf(maxWidth, widths.sum().dp)
        Box(Modifier.fillMaxSize().cssScrollbar(vertical).chromeScrollbar(horizontal, vertical = false, color = colors.border.color)) {
            Column(Modifier.horizontalScroll(horizontal).width(tableWidth).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().background(colors.bgSolid.color).cssBorder(colors.border.color, sides = CssSides.Bottom)) {
                    csv.headers.forEachIndexed { column, title ->
                        val handle = remember(column) { CsvResizeLocation() }
                        Box(Modifier.width(widths[column].dp).height(28.dp)
                            .then(if (column == csv.headers.lastIndex) Modifier else Modifier.cssBorder(colors.border.color, sides = CssSides.End))
                            .ppmClickable(onClickLabel = "Sort by ${title.ifEmpty { "Column ${column + 1}" }}",
                                hoverColor = colors.panel.color, onClick = {
                                    sorting = when (sorting) {
                                        column to false -> column to true
                                        column to true -> null
                                        else -> column to false
                                    }
                                })) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicText(title.ifEmpty { "Column ${column + 1}" }, Modifier.weight(1f),
                                    style = style.copy(fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                if (sorting?.first == column) PpmIcon(if (sorting?.second == true) ProductIcon.ArrowDown else ProductIcon.ArrowUp, 12.dp, colors.text2.color)
                            }
                            Box(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight()
                                .onGloballyPositioned { handle.coordinates = it }
                                .semantics { contentDescription = "Resize column ${column + 1}" }
                                .pointerHoverIcon(WebCursor.ResizeHorizontal)
                                .pointerInput(column, density) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val start = handle.coordinates?.localToRoot(down.position)?.x ?: return@awaitEachGesture
                                        val initialWidth = widths[column]
                                        do {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            val pointer = handle.coordinates?.localToRoot(change.position)?.x ?: break
                                            widths[column] = (initialWidth + (pointer - start) / density).coerceAtLeast(80f)
                                            change.consume()
                                        } while (change.pressed)
                                    }
                                })
                        }
                    }
                }
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize(), state = vertical) {
                        items(ordered.size, key = { ordered[it] }, contentType = { "csv-row" }) { position ->
                            Row(Modifier.height(IntrinsicSize.Min)) {
                                csv.rows[ordered[position]].forEachIndexed { column, value ->
                                    BasicText(value.ifEmpty { "\u00a0" }, Modifier.width(widths[column].dp).fillMaxHeight()
                                        .cssBorder(colors.border.color.copy(alpha = 0.5f), sides = CssSides.Bottom)
                                        .then(if (column == widths.lastIndex) Modifier else Modifier.cssBorder(colors.border.color.copy(alpha = 0.3f), sides = CssSides.End))
                                        .padding(horizontal = 8.dp, vertical = 4.dp), style = style,
                                        softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class CsvResizeLocation { var coordinates: LayoutCoordinates? = null }
