package com.hienlh.ppm.chat

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.ChromeFace
import com.hienlh.ppm.design.ChromeStrut
import com.hienlh.ppm.design.ChromeText
import com.hienlh.ppm.design.CssColumn
import com.hienlh.ppm.design.CssRow
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssExact
import com.hienlh.ppm.design.lucideOutline
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberCssExact
import com.hienlh.ppm.design.rememberHover
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** `.markdown-content pre`: 0.75rem Monaspace with the paragraph's line-height ratio. */
internal const val CODE_SIZE = 12f
internal const val CODE_LINE_HEIGHT = 12f * 20f / 14f
private val CODE_STRUT = ChromeStrut(ChromeFace.Mono, CODE_SIZE, CODE_LINE_HEIGHT)

/** The markdown being drawn is still streaming in: its code stays plain until it is done (`MdContext.isStreaming`). */
internal val LocalMarkdownStreaming = compositionLocalOf { false }

/**
 * A fenced code block (`.markdown-content pre`): 12px Monaspace on the page background inside a
 * 1px border, scrolling sideways, with the copy button a touch screen always shows
 * (`@media (hover: none)`). Once its message is done and [highlight] holds, Shiki colours it on
 * the theme's own background (`.shiki-block`), from the first frame when it was coloured before.
 * A shell command ([isShellCodeBlock]) in a host that provides [LocalShellCodeActions] also gets
 * Run, its output shown under the block, and Run in terminal, once its message is done.
 */
@Composable
fun CodeBlockView(code: String, language: String?, modifier: Modifier = Modifier, highlight: Boolean = true) {
    val tokens = LocalPpmTokens.current
    val shellActions = LocalShellCodeActions.current
    val shape = RoundedCornerShape(6.dp)
    val streaming = LocalMarkdownStreaming.current
    val enabled = highlight && !streaming
    val canRun = shellActions != null && !streaming && isShellCodeBlock(language, code)
    val blockRun = remember { CodeBlockRun() }
    // Cancelled when the block leaves composition, which stops a run still going.
    val runScope = rememberCoroutineScope()
    val theme = LocalPpmTheme.current.shikiTheme
    val highlighter = SyntaxHighlighter.get(LocalContext.current)
    val highlighted by produceState(if (enabled) highlighter.cached(code, language, theme) else null, code, language, theme, enabled) {
        value = if (enabled) highlighter.cached(code, language, theme) else null
        if (enabled && value == null) value = highlighter.highlight(code, language, theme)
    }
    val scroll = rememberScrollState()
    // Counted exactly by the column around: 34.28 px lines at 2x and a 31.5 px padding at 2.625x,
    // where whole pixels make a one-line block 79 px against Chrome's 78.28.
    val exact = rememberCssExact()
    // The output of a Run goes under the block, in the same box the column around counts.
    Column(modifier.fillMaxWidth().cssExact(exact)) {
        Box(Modifier.fillMaxWidth()) {
            // The `pre` has the border; the copy button is placed in the box around it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .chromeScrollbar(scroll, vertical = false, tokens.border.color, border = 1.dp)
                    .clip(shape)
                    .background(highlighted?.background ?: tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, shape)
                    .horizontalScroll(scroll),
            ) {
                CssColumn(Modifier.padding(horizontal = 8.dp), top = 8.dp, bottom = 12.dp, exact = exact) {
                    val codeText = highlighted?.text ?: AnnotatedString(code)
                    val codeStyle = ppmText(CODE_SIZE, kind = FontKind.MONO, lineHeight = CODE_LINE_HEIGHT)
                        .copy(color = highlighted?.foreground ?: tokens.text.color)
                    if (LocalPlainTextLayout.current) {
                        // In SelectTextDialog: BasicText, which a SelectionContainer selects.
                        BasicText(codeText, style = codeStyle, softWrap = false)
                    } else {
                        ChromeText(codeText, codeStyle, CODE_STRUT, softWrap = false)
                    }
                }
            }
            CssRow(Modifier.align(Alignment.TopEnd).padding(4.dp), gap = 4.dp, alignEnd = true) {
                CopyIconButton(code)
                if (canRun) {
                    CodeActionButton(PLAY_ICON, "Run (output below)") {
                        blockRun.run(runScope, normalizeCodeBlockCommand(code), shellActions.run)
                    }
                    CodeActionButton(TERMINAL_ICON, "Run in terminal") {
                        // `execute: true`: typed and Enter pressed.
                        normalizeCodeBlockCommand(code).takeIf { it.isNotEmpty() }?.let { shellActions.runInTerminal(it + "\r") }
                    }
                }
            }
        }
        CodeBlockRunOutput(blockRun.state, blockRun::stop, blockRun::close)
    }
}

/**
 * The code block's 24px copy button (`ActionBtn`), `hover:bg-surface-elevated hover:text-text-primary`:
 * a check for 2 s after a copy.
 */
@Composable
internal fun CopyIconButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    CodeActionButton(if (copied) COPIED_ICON else COPY_ICON, "Copy", modifier) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", text)))
            copied = true
            delay(2_000)
            copied = false
        }
    }
}

/** `ActionBtn`: both code actions share the web's size, border and hover colours. */
@Composable
private fun CodeActionButton(icon: ImageVector, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    val hover = rememberHover()
    val lit = hover.lit.toDouble()
    Box(
        modifier
            .size(24.dp)
            .clip(shape)
            .background(tokens.panel2.mix(tokens.panel2.opacity(0.8), lit).color)
            .ppmClickable(pressedColor = tokens.panel2.color, source = hover.source, onClick = onClick)
            .cssBorder(tokens.border.opacity(0.5).color, shape)
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 12.dp, tokens.text.mix(tokens.text2, lit).color)
    }
}

/** `CopyIcon` and `CheckIcon` (markdown-code-block.tsx): lucide's outlines, which the web writes out rather than imports. */
private val COPY_ICON = lucideOutline(
    "CodeCopy",
    listOf("M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"),
)
private val COPIED_ICON = lucideOutline("CodeCopied", listOf("M20 6L9 17L4 12"))
/** `PlayIcon`, Run's triangle. */
private val PLAY_ICON = lucideOutline("CodeRun", listOf("M6 4L20 12L6 20Z"))

/** `TerminalIcon`, Run in terminal's prompt. */
private val TERMINAL_ICON = lucideOutline("CodeTerminal", listOf("M4 17L10 11L4 5", "M12 19H20"))
