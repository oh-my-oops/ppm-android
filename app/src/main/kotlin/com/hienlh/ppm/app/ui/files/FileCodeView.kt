package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.HighlightedCode
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private class FileCodeTokens(val code: HighlightedCode, val rows: List<AnnotatedString>)

/** Native virtual rows: 14px Monaspace, shared horizontal scroll, fixed line-number gutter. */
@Composable
internal fun FileCodeView(
    content: String,
    language: String,
    wrap: Boolean,
    lineNumber: Long?,
    endLine: Long?,
    revealAt: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPpmTokens.current
    val theme = LocalPpmTheme.current.shikiTheme
    val highlighter = SyntaxHighlighter.get(LocalContext.current)
    // Retain scroll positions while a refreshed buffer is indexed off the main thread.
    val list = rememberLazyListState()
    val horizontal = rememberScrollState()
    val prepared = remember(content) { mutableStateOf<FileCodeLines?>(null) }
    LaunchedEffect(prepared) {
        prepared.value = withContext(Dispatchers.Default) { FileCodeLines(content) }
    }
    val document = prepared.value
    if (document == null) { FileNoteView(FileNote.Spinner); return }
    val tokens = remember(document, language, theme) { mutableStateOf<FileCodeTokens?>(null) }
    LaunchedEffect(tokens) {
        val code = highlighter.cached(document.text, language, theme) ?: highlighter.highlight(document.text, language, theme)
        tokens.value = code?.let { withContext(Dispatchers.Default) { document.tokenRows(it.text)?.let { rows -> FileCodeTokens(it, rows) } } }
    }
    val highlighted = tokens.value
    val density = LocalDensity.current
    val target = remember(lineNumber, endLine, document.count) { fileCodeReveal(lineNumber, endLine, document.count) }
    LaunchedEffect(document, target, revealAt) {
        if (target == null) return@LaunchedEffect
        val viewport = snapshotFlow { list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset }.first { it > 0 }
        val rowHeight = with(density) { 20.sp.toPx() }
        list.scrollToItem(target.first, -((viewport - rowHeight).coerceAtLeast(0f) / 2).toInt())
    }
    val style = ppmText(14f, kind = FontKind.MONO, lineHeight = 20f).copy(
        color = highlighted?.code?.foreground ?: colors.text.color, textDirection = TextDirection.Ltr,
    )
    val advance = with(density) { (14.sp.toPx() * PpmFonts.MONO_ADVANCE).toDp() }
    val gutter = advance * maxOf(3, document.count.toString().length) + 20.dp
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(highlighted?.code?.background ?: colors.bgSolid.color)) {
        val codeWidth = maxOf(0.dp, maxWidth - gutter)
        val scrollWidth = maxOf(codeWidth, advance * document.maxColumns + 24.dp)
        SelectionContainer {
            Box(Modifier.fillMaxSize().cssScrollbar(list)) {
                // A single scroll state drives every visible row; the gutter never moves sideways.
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(document.count, contentType = { "file-line" }) { row ->
                        val plain = remember(document, row) { document.line(row) }
                        val tokenLine = highlighted?.rows?.getOrNull(row)
                        val line = remember(plain, tokenLine) { fileCodeDisplayLine(plain, tokenLine) }
                        Row(Modifier.heightIn(min = 20.dp).background(
                            if (target?.contains(row) == true) colors.accent.color.copy(alpha = 0.12f) else Color.Transparent,
                        )) {
                            DisableSelection {
                                BasicText("${row + 1}", Modifier.width(gutter).padding(end = 12.dp),
                                    style = style.copy(color = colors.text3.color, textAlign = TextAlign.End), softWrap = false)
                            }
                            if (wrap) {
                                BasicText(line, Modifier.width(codeWidth).padding(end = 12.dp), style = style)
                            } else {
                                Box(Modifier.width(codeWidth).horizontalScroll(horizontal)) {
                                    BasicText(line, Modifier.width(scrollWidth).padding(end = 12.dp), style = style, softWrap = false)
                                }
                            }
                        }
                    }
                }
                if (!wrap) Box(Modifier.fillMaxSize().chromeScrollbar(horizontal, vertical = false, color = colors.border.color))
            }
        }
    }
}
