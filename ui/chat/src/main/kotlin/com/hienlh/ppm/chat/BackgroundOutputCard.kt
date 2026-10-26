package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.webTitle
import com.hienlh.ppm.protocol.BackgroundShell

/** `text-sm font-mono`: 14 on 20. */
private val headerText = ppmText(14f, kind = FontKind.MONO, lineHeight = 20f)

/** `text-[11px]` under the page's `line-height: 1.5`. */
private val statusText = ppmText(11f, lineHeight = 16.5f)

/** `text-xs font-mono`: 12 on 16. */
private val outputText = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f)

/**
 * What the background output panel holds (`background-output-panel.tsx`): the command and how it
 * stands over its `.output` file, which follows the text to its end as it changes. [shell] is null
 * once no chat lists the command any more ("Background command", no status). The text's box runs
 * on under [bottomInset] (a navigation bar), its last line kept above it.
 */
@Composable
fun BackgroundOutputCard(shell: BackgroundShell?, text: String, onClose: () -> Unit, modifier: Modifier = Modifier, bottomInset: Dp = 0.dp) {
    val tokens = LocalPpmTokens.current
    Column(modifier) {
        // `flex items-center gap-2 px-4 py-3 border-b border-border`; the close button's `-mr-1`
        // takes 4dp of the padding back.
        Row(
            Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.TerminalSquare, 16.dp, tokens.text2.color)
            val command = shell?.command?.ifEmpty { null } ?: "Background command"
            val title = shell?.command?.let { Modifier.webTitle(it) } ?: Modifier
            TruncatedText(AnnotatedString(PpmFormat.collapsedWhitespace(command)), headerText.copy(color = tokens.text.color), Modifier.weight(1f).then(title))
            when (shell?.status) {
                "running" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PpmIcon(ProductIcon.Loader2, 12.dp, EMERALD_500.color, spinning = true)
                    BasicText("live", style = statusText.copy(color = EMERALD_500.color), maxLines = 1, softWrap = false)
                }
                "stopping" -> BasicText("stopping…", style = statusText.copy(color = tokens.warning.color), maxLines = 1, softWrap = false)
                "stopped" -> BasicText("stopped", style = statusText.copy(color = tokens.text3.color), maxLines = 1, softWrap = false)
            }
            // `size-9 rounded hover:bg-surface-elevated text-text-secondary`, an × of 20.
            Box(
                Modifier
                    .size(36.dp)
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = RoundedCornerShape(4.dp), onClickLabel = "Close", onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 20.dp, tokens.text2.color)
            }
        }
        OutputText(text, bottomInset, Modifier.weight(1f, fill = false))
    }
}

/**
 * `<pre class="flex-1 overflow-auto p-4 whitespace-pre-wrap break-words bg-background">`, a line
 * at a time so that half a megabyte of output lays out only what is on screen; scrolled to its end
 * whenever the text changes (`scrollTop = scrollHeight`).
 */
@Composable
private fun OutputText(text: String, bottomInset: Dp, modifier: Modifier) {
    val tokens = LocalPpmTokens.current
    val lines = remember(text) { outputLines(text) }
    val list = rememberLazyListState()
    LaunchedEffect(text) {
        // Past the end, which the list takes back to the last line's bottom.
        if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex, 1_000_000)
    }
    WithoutOnCopy("$BREAK$JOIN") {
        SelectionContainer(modifier.fillMaxWidth().background(tokens.bgSolid.color)) {
            LazyColumn(Modifier.fillMaxWidth().cssScrollbar(list), list, PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomInset)) {
                items(lines.size) { index -> BasicText(lines[index], style = outputText.copy(color = tokens.text.color)) }
            }
        }
    }
}

/**
 * [text] as `white-space: pre-wrap` lays it out, one entry a line: a final newline starts no
 * line of its own, a carriage return shows as the space CSS treats it as, a tab reaches the next
 * stop of four columns, and a line wraps where Chrome wraps it ([chromeBreaks]).
 */
internal fun outputLines(text: String): List<AnnotatedString> =
    text.replace("\r\n", "\n").replace('\r', ' ').removeSuffix("\n").split('\n').map { line ->
        AnnotatedString(chromeBreaks(if ('\t' in line) preText(line, breakAll = false).text else line))
    }
