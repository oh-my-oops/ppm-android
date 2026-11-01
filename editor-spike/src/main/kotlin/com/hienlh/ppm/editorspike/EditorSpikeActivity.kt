package com.hienlh.ppm.editorspike

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.LocalEditorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Evaluation harness, not the PPM editor: plaintext baseline before tokenization/IME gates. */
class EditorSpikeActivity : ComponentActivity() {
    private val frames = mutableListOf<Long>()
    private var droppedCallbacks = 0
    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        droppedCallbacks += dropped
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addOnFrameMetricsAvailableListener(frameListener, Handler(Looper.getMainLooper()))
        val rows = intent.getIntExtra("lines", 50_000).coerceIn(1, 100_000)
        setContent {
            val source by produceState<String?>(null, rows) {
                value = withContext(Dispatchers.Default) {
                    buildString(rows * 105) {
                        repeat(rows) { line ->
                            append("// line ${line + 1}: editor performance fixture ".padEnd(104, 'x'))
                            if (line < rows - 1) append('\n')
                        }
                    }
                }
            }
            val text = source
            if (text == null) BasicText("Preparing fixture…")
            else key(text.length) { Editor(text) }
        }
    }

    @Composable
    private fun Editor(text: String) {
        val scope = rememberCoroutineScope()
        val started = remember { SystemClock.elapsedRealtimeNanos() }
        val state = remember {
            CodeEditorState(text, scope = scope).also {
                log("model chars=${text.length} lines=${it.document.lineCount} ms=${msSince(started)}")
            }
        }
        val firstDraw = remember { booleanArrayOf(false) }
        var wrap by remember { mutableStateOf(false) }
        val style = TextStyle(color = Color.White, fontSize = 12.sp)
        CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark) {
            Column(Modifier.fillMaxSize().background(Color(0xFF181818)).systemBarsPadding().imePadding()) {
                BasicText("Aardink 0.6.2${if (BuildConfig.LOCAL_CANDIDATE) " local" else ""} · ${text.length} chars · plaintext", Modifier.padding(8.dp), style)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BasicText("Reset metrics", Modifier.clickable { frames.clear(); droppedCallbacks = 0 }.padding(8.dp), style)
                    BasicText("Snapshot", Modifier.clickable { snapshot(state, text) }.padding(8.dp), style)
                    BasicText("Wrap", Modifier.clickable { wrap = !wrap }.padding(8.dp), style)
                    BasicText("Undo", Modifier.clickable { state.undo() }.padding(8.dp), style)
                    BasicText("Redo", Modifier.clickable { state.redo() }.padding(8.dp), style)
                }
                CodeEditorLayout(state, Modifier.weight(1f).fillMaxWidth().drawWithContent {
                    drawContent()
                    if (!firstDraw[0]) {
                        firstDraw[0] = true
                        log("first_draw chars=${text.length} model_to_draw_ms=${msSince(started)}")
                    }
                }, options = EditorOptions(softWrap = wrap, showMinimap = false, stickyScroll = false))
            }
        }
    }

    private fun snapshot(state: CodeEditorState, original: String) {
        // Bounded text observation; never copy the 5 MB document on each keystroke.
        val ordered = frames.sorted()
        fun percentile(fraction: Double) = if (ordered.isEmpty()) 0.0 else
            ordered[((ordered.size - 1) * fraction).toInt()] / 1_000_000.0
        // Explicit Snapshot only: compare every code unit after Undo, without allocating a
        // full-document copy. Keep this verification out of rendering and keystroke paths.
        val matchesFixture = state.document.length == original.length &&
            original.indices.all { state.document[it] == original[it] }
        val report = "matches_fixture=$matchesFixture\n" + "chars=${state.document.length} lines=${state.document.lineCount} version=${state.textVersion}\n" +
            "selection=${state.selection}\n" +
            "frames=${ordered.size} p50_ms=${percentile(.5)} p95_ms=${percentile(.95)} " +
            "over_16_67_ms=${ordered.count { it > 16_666_667 }} dropped_callbacks=$droppedCallbacks\n" +
            "prefix=${state.document.subSequence(0, minOf(200, state.document.length))}\n" +
            "cursor_context=${state.document.subSequence((state.selection.start - 80).coerceAtLeast(0), (state.selection.start + 80).coerceAtMost(state.document.length))}\n"
        File(filesDir, "snapshot.txt").writeText(report)
        log(report)
    }

    override fun onDestroy() {
        window.removeOnFrameMetricsAvailableListener(frameListener)
        super.onDestroy()
    }

    private fun msSince(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
    private fun log(message: String) = Log.i("PpmEditorSpike", message)
}
