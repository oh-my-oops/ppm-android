package com.hienlh.ppm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.client.ShellRunResult
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.TruncatedText
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.isJsSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a chat's shell code block can do with its command (`canRun` in markdown-code-block.tsx):
 * type it into a terminal and press Enter ([runInTerminal] gets the command with its `\r`), or
 * run it in the project's folder and answer what it printed ([run], cancelled with its coroutine).
 * A host provides it only where the web offers both buttons: a project's chat, not the PPM
 * Assistant's, not in minimal mode.
 */
class ShellCodeActions(val runInTerminal: (String) -> Unit, val run: suspend (String) -> ShellRunResult)

/** Absent outside a chat host that can run a command. */
val LocalShellCodeActions = compositionLocalOf<ShellCodeActions?> { null }

/** `CodeBlockRunState`. */
internal sealed interface CodeBlockRunState {
    data object Idle : CodeBlockRunState
    data class Running(val startedAt: Long) : CodeBlockRunState
    data class Done(val result: ShellRunResult) : CodeBlockRunState
    data class Failed(val message: String) : CodeBlockRunState
}

/**
 * `useCodeBlockRun`: the Run button's state for one code block. Stop cancels the call, and the
 * server kills the run when its request closes; the block leaving composition cancels the scope
 * the run was started in, which stops it the same way.
 */
@Stable
internal class CodeBlockRun {
    var state by mutableStateOf<CodeBlockRunState>(CodeBlockRunState.Idle)
        private set
    private var job: Job? = null

    fun run(scope: CoroutineScope, command: String, runner: suspend (String) -> ShellRunResult) {
        if (command.isEmpty()) return
        job?.cancel()
        state = CodeBlockRunState.Running(System.currentTimeMillis())
        val started = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val result = runner(command)
                if (job === self) state = CodeBlockRunState.Done(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (job === self) state = CodeBlockRunState.Failed(error.message ?: error.javaClass.simpleName)
            } finally {
                if (job === self) job = null
            }
        }
        job = started
        started.start()
    }

    fun stop() {
        val running = job ?: return
        job = null
        running.cancel()
        state = CodeBlockRunState.Done(ShellRunResult("", null, timedOut = false, stopped = true, durationMs = 0))
    }

    fun close() {
        job?.cancel()
        job = null
        state = CodeBlockRunState.Idle
    }
}

/** `formatElapsed`: tenths under ten seconds, whole seconds under a minute, then minutes. */
internal fun formatElapsed(ms: Long): String {
    val s = maxOf(0L, Math.floorDiv(ms, 1000L))
    if (s < 60) {
        // `toFixed(1)` rounds the double's exact value, half up.
        return if (ms in 1 until 10_000) "${BigDecimal(ms / 1000.0).setScale(1, RoundingMode.HALF_UP).toPlainString()}s" else "${s}s"
    }
    return "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
}

/** `runStatusLine`: one line saying how the run ended. */
internal fun runStatusLine(r: ShellRunResult): String = when {
    r.stopped -> "Stopped"
    r.timedOut -> "Stopped at the time limit · ${formatElapsed(r.durationMs)}"
    r.exitCode == null -> "Could not start"
    else -> "Exit ${r.exitCode} · ${formatElapsed(r.durationMs)}"
}

/** `text-xs`. */
private val statusText = ppmText(12f, lineHeight = 16f)

/** `font-mono text-[12px] leading-snug`. */
private val outputText = ppmText(12f, kind = FontKind.MONO, lineHeight = 12f * 1.375f)

/**
 * `CodeBlockRunOutput`: what a Run printed, under its code block — `mt-1 rounded-md border
 * border-border/60 bg-surface`, a status line with Stop while it runs and Close after, red when it
 * failed, then the output in a mono `pre` that scrolls inside `max-h-72`.
 */
@Composable
internal fun CodeBlockRunOutput(state: CodeBlockRunState, onStop: () -> Unit, onClose: () -> Unit) {
    if (state is CodeBlockRunState.Idle) return
    val tokens = LocalPpmTokens.current
    val failed = state is CodeBlockRunState.Failed || (state is CodeBlockRunState.Done && state.result.exitCode != 0)
    val shape = RoundedCornerShape(6.dp)
    val border = tokens.border.opacity(0.6).color
    Column(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .background(tokens.panel.color, shape)
            .cssBorder(border, shape)
            .testTag("code-block-run"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state is CodeBlockRunState.Running) PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text2.color, spinning = true)
            val line = when (state) {
                is CodeBlockRunState.Running -> "Running · ${formatElapsed(rememberNow(ticking = true) - state.startedAt)}"
                is CodeBlockRunState.Done -> runStatusLine(state.result)
                is CodeBlockRunState.Failed -> "Could not run: ${state.message}"
                CodeBlockRunState.Idle -> ""
            }
            TruncatedText(
                AnnotatedString(line),
                statusText.copy(color = if (failed) tokens.error.color else tokens.text2.color),
                Modifier.weight(1f).testTag("code-block-run-status"),
            )
            if (state is CodeBlockRunState.Running) {
                RunOutputButton("Stop", onStop) { tint -> PpmIcon(ProductIcon.Square, 12.dp, tint) }
            } else {
                RunOutputButton("Close output", onClose) { tint -> PpmIcon(ProductIcon.X, 14.dp, tint) }
            }
        }
        if (state is CodeBlockRunState.Done) {
            val output = state.result.output.trimEnd(::isJsSpace)
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .cssBorder(border, sides = CssSides.Top)
                    .heightIn(max = 288.dp)
                    .chromeScrollbar(scroll, vertical = true, tokens.border.color)
                    .verticalScroll(scroll)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("code-block-run-output"),
            ) {
                if (output.isEmpty()) {
                    BasicText("(no output)", style = outputText.copy(color = tokens.text2.color))
                } else {
                    BasicText(output, style = outputText.copy(color = tokens.text.color))
                }
            }
        }
    }
}

/** `size-6 rounded text-text-secondary hover:text-text-primary hover:bg-surface-elevated`. */
@Composable
private fun RunOutputButton(label: String, onClick: () -> Unit, icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit) {
    val tokens = LocalPpmTokens.current
    val hover = rememberHover()
    Box(
        Modifier
            .size(24.dp)
            .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = RoundedCornerShape(4.dp), source = hover.source, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        icon(tokens.text2.mix(tokens.text, hover.lit.toDouble()).color)
    }
}
