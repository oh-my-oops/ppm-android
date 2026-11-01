#!/usr/bin/env python3
"""Generate a patched termlib 0.3.13 Kotlin library with its untouched same-version JNI.

No downloads and no dependency installation. Gradle supplies the three pinned Maven artifacts.
All upstream sources and binaries stay under the module's build directory. Every replacement
checks its expected match count; any upstream artifact change fails before writing output.
"""

import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import tempfile
from textwrap import dedent, indent
from zipfile import ZipFile

PINS = {
    "sources": "18810e74daacca730a50fd8e1986d90ce292cab6fc4f6d13ac0d212ac7fd77c9",
    "aar": "fc8e3e1ccd69f351c23ad82974df938037a07096f14d1b53793c3dec494b54e5",
    "pom": "45d82483a6f78c6cd34411168ceee39c7041f5875469b43eee2d1f2b5d23cb88",
}
REPO = Path(__file__).resolve().parents[2]
IME_TARGETS = {"Terminal.kt", "ImeInputView.kt", "KeyboardHandler.kt", "TerminalDispatcher.kt"}


def load_ime_patch():
    # Explicit path works under Python's isolated mode without adding scripts to sys.path.
    path = Path(__file__).with_name("terminal_ime_patch.py")
    spec = importlib.util.spec_from_file_location("terminal_ime_patch", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.patch_terminal_ime


def replace(text, old, new, count=1):
    actual = text.count(old)
    if actual != count:
        raise RuntimeError(f"Patch guard: expected {count}, got {actual}: {old[:100]!r}")
    return text.replace(old, new)


def emulator(text):
    text = replace(text, "import androidx.compose.ui.graphics.Color\n", "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.toArgb\n")
    text = replace(text, "sealed interface TerminalEmulator {", "sealed interface TerminalEmulator : AutoCloseable {\n" + dedent('''
        /** Idempotent; releases JNI, images and retained history. Late queued writes are ignored. */
        override fun close()
        val isClosed: Boolean
        /** Fresh parser/screens/history, preserving dimensions, callbacks, palette and defaults. */
        fun reset()
        /** Screen-relative (row, column), zero-based. */
        fun getCursorPosition(): Pair<Int, Int>
        /** Atomic history + screen read, with an absolute cursor row and screenStartRow. */
        fun getBufferSnapshot(): TerminalBufferSnapshot
        fun getBufferLines(): List<TerminalBufferLine> = getBufferSnapshot().lines
        /** Start inclusive, end exclusive, relative to retained history. */
        fun getTextBetweenRows(start: Int, endExclusive: Int): String = getBufferSnapshot().getTextBetweenRows(start, endExclusive)
        fun getScreenText(): String {
            val buffer = getBufferSnapshot()
            return buffer.getTextBetweenRows(buffer.screenStartRow, buffer.lines.size)
        }
    '''))
    text = replace(text, "            inlineImages: InlineImages = InlineImages.Off,", "            inlineImages: InlineImages = InlineImages.Off,\n            maxScrollbackLines: Int = 50_000,")
    text = replace(text, "            inlineImages = inlineImages,", "            inlineImages = inlineImages,\n            maxScrollbackLines = maxScrollbackLines,")
    text = replace(text, "\n    inlineImages: InlineImages = InlineImages.Off,", "\n    inlineImages: InlineImages = InlineImages.Off,\n    private val maxScrollbackLines: Int = 50_000,")
    text = replace(text, "    // Handler for escaping native mutex", "    init { require(maxScrollbackLines >= 0) { \"maxScrollbackLines must be non-negative\" } }\n\n    // Handler for escaping native mutex")
    text = replace(text, "    private val maxScrollbackLines = 1000\n", "")
    text = replace(text, "    private val damageLock = Any()", "    private val damageLock = Any()\n    @Volatile override var isClosed = false\n        private set\n    private var currentAnsiPalette: IntArray? = null")
    start = text.index("    private val terminalNative by lazy {")
    end = text.index("    // Parser for OSC sequences", start)
    text = text[:start] + dedent('''
        private var nativeInstance: TerminalNative? = null
        private val terminalNative: TerminalNative
            get() {
                check(!isClosed) { "Terminal is closed" }
                return nativeInstance ?: TerminalNative(this).also { native ->
                    nativeInstance = native
                    native.resize(rows, cols)
                    native.setBoldHighbright(boldAsBright)
                    native.setDefaultColors(currentDefaultForeground.toArgb(), currentDefaultBackground.toArgb())
                    currentAnsiPalette?.let { native.setPaletteColors(it, 16) }
                }
            }

    ''') + text[end:]
    text = replace(text, "    private val oscParser = OscParser()", "    private var oscParser = OscParser()")
    marker = "    override fun clearScreen() = writeInput(\"\\u001B[2J\\u001B[H\".toByteArray())"
    text = replace(text, marker, marker + "\n\n" + dedent('''
        override fun getCursorPosition(): Pair<Int, Int> = synchronized(damageLock) {
            cursorRow to cursorCol
        }

        override fun getBufferSnapshot(): TerminalBufferSnapshot = synchronized(damageLock) {
            if (!isClosed) processPendingUpdates()
            val value = _snapshot.value
            val history = if (value.alternateScreen) emptyList() else value.scrollback
            TerminalBufferSnapshot(
                (history + value.lines).map { TerminalBufferLine(it.text.replace('\\u0000', ' '), it.softWrapped) },
                history.size + value.cursorRow, value.cursorCol, history.size, value.alternateScreen,
            )
        }

        override fun close(): Unit = commands.call {
            synchronized(damageLock) {
                if (isClosed) return@synchronized
                isClosed = true
                nativeInstance?.close()
                nativeInstance = null
                clearRetainedState()
            }
        }

        override fun reset(): Unit = commands.call {
            synchronized(damageLock) {
                if (isClosed) return@synchronized
                nativeInstance?.close()
                nativeInstance = null
                clearRetainedState()
                invalidateDisplay()
                processPendingUpdates()
            }
        }

        private fun clearRetainedState() {
            handler.removeCallbacksAndMessages(null)
            imageProtocol.consentGate?.reset()
            imageProtocol.reset()
            imageStore.clear()
            imageStore.switchScreen(false)
            oscParser = OscParser()
            scrollback.clear()
            scrollbackSnapshot = emptyList()
            scrollbackDirty = false
            pendingScrollback = null
            pendingDamageRegions.clear()
            pendingSemanticSegments.clear()
            movedSegmentRows.clear()
            semanticSegmentTexts.clear()
            damagePosted = false
            nextSnapshotNanos = System.nanoTime()
            cursorMoved = false
            propertyChanged = false
            cursorRow = 0
            cursorCol = 0
            cursorVisible = true
            cursorBlink = false
            cursorShape = CursorShape.BLOCK
            terminalTitle = ""
            isAltScreenActive = false
            currentLines = MutableList(rows) { TerminalLine.empty(it, cols, currentDefaultForeground, currentDefaultBackground) }
            _snapshot.value = TerminalSnapshot.empty(rows, cols, currentDefaultForeground, currentDefaultBackground)
                .copy(sequenceNumber = sequenceNumber++)
        }
    '''))
    # Late Compose/input/image/snapshot work must never call freed JNI or resurrect a closed engine.
    guards = [
        "    internal fun batchOutput(action: () -> Unit) = synchronized(damageLock) {",
        "    override fun writeInput(data: ByteArray, offset: Int, length: Int): Unit = synchronized(damageLock) {",
        "    override fun writeInput(buffer: ByteBuffer, length: Int): Unit = synchronized(damageLock) {",
        "    internal fun resize(newRows: Int, newCols: Int, widthPixels: Int, heightPixels: Int): Unit = synchronized(damageLock) {",
        "    override fun setInlineImages(inlineImages: InlineImages): Unit = synchronized(damageLock) {",
        "    override fun setCellPixelSize(width: Int, height: Int): Unit = synchronized(damageLock) {",
        "    fun processPendingUpdates(): Unit = synchronized(damageLock) {",
    ]
    for guard in guards:
        text = replace(text, guard, guard + "\n        if (isClosed) return@synchronized")
    for call in ("dispatchKey(modifiers, key)", "dispatchCharacter(modifiers, codepoint)"):
        text = replace(text, f"synchronized(damageLock) {{ terminalNative.{call} }}", f"synchronized(damageLock) {{ if (!isClosed) terminalNative.{call} }}")
    for signature in ("setAnsiPalette(ansiColors: IntArray)", "setDefaultColors(foreground: Int, background: Int)"):
        guard = f"    override fun {signature}: Int = synchronized(damageLock) {{"
        text = replace(text, guard, guard + "\n        if (isClosed) return@synchronized -1")
    text = replace(text, "        val result = terminalNative.setPaletteColors(ansiColors, 16)", "        currentAnsiPalette = ansiColors.copyOf(16)\n        val result = terminalNative.setPaletteColors(ansiColors, 16)")
    text = replace(text, "        if (damagePosted) return", "        if (isClosed || damagePosted) return")
    text = replace(text, "                                    synchronized(imageStore) { completion(result.getOrDefault(false)) }", "                                    if (isClosed) return@synchronized\n                                    synchronized(imageStore) { completion(result.getOrDefault(false)) }")
    return text


def selection(text):
    return replace(text, "    fun copySelection(): String", dedent('''
        /** Read without a clipboard write or any selection/scroll mutation. UI thread only. */
        fun getSelectionText(): String
        /** Read the currently displayed viewport without selecting it. UI thread only. */
        fun getVisibleText(): String
        fun copySelection(): String
    ''').rstrip())


def terminal(text):
    text = replace(text, "import androidx.compose.ui.graphics.Color\n", "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.compositeOver\n")
    text = replace(text, "import androidx.compose.ui.layout.onSizeChanged", "import androidx.compose.ui.layout.LayoutCoordinates\nimport androidx.compose.ui.layout.onGloballyPositioned\nimport androidx.compose.ui.geometry.Rect\nimport androidx.compose.ui.layout.onSizeChanged")
    # Append options to preserve existing positional Kotlin calls.
    signature = "    onPageGesture: ((Int) -> Unit)? = null,\n) {"
    text = replace(text, signature, "    onPageGesture: ((Int) -> Unit)? = null,\n    cursorColor: Color = Color.Unspecified,\n    cursorAccent: Color = Color.Unspecified,\n    minimumContrastRatio: Double = 4.5,\n    onCursorBoundsChanged: ((Rect?) -> Unit)? = null,\n    selectionActions: (@Composable (SelectionController) -> Unit)? = null,\n) {\n    require(minimumContrastRatio.isFinite() && minimumContrastRatio in 1.0..21.0)", 2)
    text = replace(text, "        onPageGesture = onPageGesture,", "        onPageGesture = onPageGesture,\n        cursorColor = cursorColor, cursorAccent = cursorAccent, minimumContrastRatio = minimumContrastRatio,\n        onCursorBoundsChanged = onCursorBoundsChanged,\n        selectionActions = selectionActions,")
    text = replace(text, "remember(typeface, calculatedFontSize, density)", "remember(typeface, calculatedFontSize, density, minimumContrastRatio)")
    text = replace(text, "TerminalTextPaint(typeface, with(density) { calculatedFontSize.toPx() })", "TerminalTextPaint(typeface, with(density) { calculatedFontSize.toPx() }, minimumContrastRatio)")
    marker = "    LaunchedEffect(terminalEmulator, baseCharWidth, baseCharHeight) {\n        terminalEmulator.commands.execute {"
    bounds = dedent('''
        // Bounds are relative to the outer terminal's content box, in physical pixels.
        // Observe layout/cursor/zoom changes outside drawing; blinking must not pan the page.
        var cursorRootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var cursorContentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var cursorLayoutRevision by remember { mutableStateOf(0) }
        val currentCursorBoundsCallback by rememberUpdatedState(onCursorBoundsChanged)
        LaunchedEffect(terminalEmulator, textPaint, baseCharWidth, baseCharHeight, onCursorBoundsChanged != null) {
            if (onCursorBoundsChanged == null) return@LaunchedEffect
            snapshotFlow {
                cursorLayoutRevision
                zoomScale; zoomOffset; zoomOrigin
                val snapshot = screenState.snapshot
                val root = cursorRootCoordinates
                val content = cursorContentCoordinates
                if (!snapshot.cursorVisible || screenState.scrollbackPosition != 0 ||
                    snapshot.cursorRow !in 0 until snapshot.rows || snapshot.cursorCol !in 0 until snapshot.cols ||
                    root == null || content == null || !root.isAttached || !content.isAttached) {
                    null
                } else {
                    val row = snapshot.cursorRow
                    val line = screenState.getVisibleLine(row)
                    val col = cursorLeadColumn(line, snapshot.cursorCol)
                    val visualCol = textPaint.visualColumn(screenState, row, col, baseCharWidth)
                    val rect = terminalCursorCellBounds(row, visualCol, line.cells.width(col), baseCharWidth, baseCharHeight)
                    Rect(root.localPositionOf(content, rect.topLeft), root.localPositionOf(content, rect.bottomRight))
                }
            }.collect { currentCursorBoundsCallback?.invoke(it) }
        }
        DisposableEffect(terminalEmulator) {
            onDispose { currentCursorBoundsCallback?.invoke(null) }
        }

    ''')
    text = replace(text, marker, bounds + marker)
    marker = "            .onSizeChanged {\n                measuredWidth = it.width"
    text = replace(text, marker, "            .onGloballyPositioned { cursorRootCoordinates = it; cursorLayoutRevision++ }\n" + marker)
    text = replace(text, "                    Modifier.fillMaxSize().drawWithContent {", "                    Modifier.fillMaxSize()\n                        .onGloballyPositioned { cursorContentCoordinates = it; cursorLayoutRevision++ }\n                        .drawWithContent {")
    marker = "            override fun copySelection(): String {"
    text = replace(text, marker, dedent('''
        override fun getSelectionText(): String =
            selectionManager.getSelectedText(screenState.snapshot, screenState.scrollbackPosition)

        override fun getVisibleText(): String =
            terminalText(List(screenState.snapshot.rows) { screenState.getVisibleLine(it) })

    ''') + marker)
    text = selection_actions(text)
    text = replace(text, "                        if (cursorLine != null) {\n                            terminalInversion.cursor(", "                        if (cursorLine != null && cursorColor == Color.Unspecified) {\n                            terminalInversion.cursor(")
    marker = "                        }\n                    },\n                ) {\n                    TerminalRows("
    text = replace(text, marker, """                        }
                        if (cursorLine != null && cursorColor != Color.Unspecified) {
                            drawTerminalCursor(cursorLine, snapshot.cursorRow, cursorCol, visualCol,
                                snapshot.cursorShape, baseCharWidth, baseCharHeight, baseCharBaseline,
                                textPaint, underlinePaint, backgroundColor, cursorColor,
                                cursorAccent.takeUnless { it == Color.Unspecified } ?: backgroundColor)
                        }
                    },
                ) {
                    TerminalRows(""")
    text = replace(text, "    selectionRow: Int = row,\n) {", "    selectionRow: Int = row,\n    foregroundOverride: Color? = null,\n) {")
    text = replace(text, "                    shapedLine, selectionRow,", "                    shapedLine, selectionRow, foregroundOverride,")
    text = replace(text, "        val fg = if (isSelected) {", "        val rawFg = if (isSelected) {")
    marker = "        val hyperlink = hyperlinkMask?.getOrNull(col)"
    addition = """        val cellBg = if (reversed) cells.foreground(col) else cells.background(col)
        val renderedBg = if (isSelected) {
            (selectionBackgroundColor.takeUnless { it == Color.Unspecified } ?: Color(0xFFB3D7FF)).compositeOver(cellBg)
        } else cellBg
        val fg = foregroundOverride ?: (textPaint as? TerminalTextPaint)?.let {
            it.contrast.foreground(rawFg, renderedBg, defaultBg, it.minimumContrastRatio, cells.charAt(col).code)
        } ?: rawFg
"""
    text = replace(text, marker, addition + marker)
    text = replace(text, "                selectionBackgroundColor.takeUnless { it == Color.Unspecified } ?: Color(0xFFB3D7FF)", "                (selectionBackgroundColor.takeUnless { it == Color.Unspecified } ?: Color(0xFFB3D7FF))\n                    .compositeOver(if (cells.flags(col) and 32 != 0) cells.foreground(col) else cells.background(col))")
    return text


def text_paint(text):
    text = replace(text, "internal class TerminalTextPaint(typeface: Typeface, size: Float) : TextPaint() {", "internal class TerminalTextPaint(typeface: Typeface, size: Float, val minimumContrastRatio: Double = 1.0) : TextPaint() {\n    val contrast = TerminalContrast()")
    return replace(text, "    fun resolvedRtl(state:", "    fun resolvedRtl(cells: PackedCells, col: Int, cellWidth: Float): Boolean = layout(cells, cellWidth)?.resolvedRtl(col) ?: false\n\n    fun resolvedRtl(state:")


def selection_actions(text):
    # Replace only the action container. Native handles, gestures and magnifier stay upstream.
    start_marker = "                val endPosition = range.getEndPosition()\n                val buttonRowWidthPx"
    end_marker = "                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {"
    if text.count(start_marker) != 1 or text.count(end_marker) != 1:
        raise RuntimeError("Selection actions patch guard: expected one popup container")
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    prefix = '''                val firstVisibleLine = screenState.visibleLineIndex(0)
                val startPosition = range.getStartPosition()
                val endPosition = range.getEndPosition()
                val context = LocalContext.current
                var overflowMenuExpanded by remember { mutableStateOf(false) }

                TerminalSelectionActions(
                    firstRow = startPosition.first - firstVisibleLine,
                    lastRow = endPosition.first - firstVisibleLine,
                    rows = screenState.snapshot.rows,
                    anchorX = endPosition.second * baseCharWidth,
                    cellHeight = baseCharHeight,
                    handleHeight = with(density) { SELECTION_HANDLE_WIDTH.toPx() },
                    gap = with(density) { (if (selectionActions != null) 4.dp else 8.dp).toPx() },
                    availableWidth = availableWidth,
                    availableHeight = availableHeight,
                ) {
                    if (selectionActions != null) {
                        selectionActions(selectionController)
                    } else {
'''
    tail_marker = """                    }
                }
            }
        }

        // Hidden AndroidView with custom InputConnection"""
    if text.count(tail_marker) != 1:
        raise RuntimeError("Selection actions patch guard: expected one popup tail")
    legacy_end = text.index(tail_marker, end) + len("                    }\n")
    legacy_row = indent(text[end:legacy_end], "    ")
    return replace(text, text[start:legacy_end], prefix + legacy_row + "                    }\n")


PATCHES = {"TerminalEmulator.kt": emulator, "SelectionManager.kt": selection, "Terminal.kt": terminal, "TerminalTextPaint.kt": text_paint}


def files(archive):
    for item in archive.infolist():
        path = PurePosixPath(item.filename)
        if path.is_absolute() or ".." in path.parts or "\\" in item.filename:
            raise RuntimeError(f"Unsafe archive member: {item.filename}")
        if not item.is_dir():
            yield item.filename, archive.read(item)


def build(sources, aar, pom, output):
    for name, path in (("sources", sources), ("aar", aar), ("pom", pom)):
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != PINS[name]:
            raise RuntimeError(f"{name}: expected termlib 0.3.13 SHA-256 {PINS[name]}, got {actual}")
    patch_ime = load_ime_patch()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="termlib-", dir=output.parent) as temp:
        stage = Path(temp)
        def write(path, data):
            target = stage / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        patched = []
        with ZipFile(sources) as archive:
            for name, data in files(archive):
                if name.endswith(".kt"):
                    filename = PurePosixPath(name).name
                    patch = PATCHES.get(filename)
                    if patch:
                        data = patch(data.decode()).encode()
                    if filename in IME_TARGETS:
                        data = patch_ime(filename, data.decode()).encode()
                    if patch or filename in IME_TARGETS:
                        if filename in patched:
                            raise RuntimeError(f"Duplicate patch target: {filename}")
                        patched.append(filename)
                    write("kotlin/" + name, data)
                elif any(word in name.upper() for word in ("LICENSE", "NOTICE", "COPYING")):
                    write("assets/terminal-engine/licenses/sources/" + name, data)
        targets = set(PATCHES) | IME_TARGETS
        if set(patched) != targets:
            raise RuntimeError(f"Missing patch targets: {targets - set(patched)}")
        with ZipFile(aar) as archive:
            for name, data in files(archive):
                if name.startswith(("jni/", "res/", "assets/")) or name == "proguard.txt":
                    write(name, data)
                elif name == "AndroidManifest.xml":
                    write("upstream/" + name, data)
                    write(name, replace(data.decode(), '    package="org.connectbot.terminal" ', '').encode())
                elif name == "classes.jar":
                    with ZipFile(io.BytesIO(data)) as classes:
                        for entry, contents in files(classes):
                            if any(word in entry.upper() for word in ("LICENSE", "NOTICE", "COPYING")):
                                write("javaResources/" + entry, contents)
                                write("assets/terminal-engine/licenses/aar/" + entry, contents)
                else:
                    write("upstream/" + name, data)
                    if any(word in name.upper() for word in ("LICENSE", "NOTICE", "COPYING")):
                        write("assets/terminal-engine/licenses/aar/" + name, data)
        write("assets/terminal-engine/licenses/Apache-2.0.txt", (REPO / "licenses/Apache-2.0.txt").read_bytes())
        for license in (REPO / "android/ui/terminal-engine/licenses").glob("*.txt"):
            write("assets/terminal-engine/licenses/" + license.name, license.read_bytes())
        write("upstream/termlib-0.3.13.pom", pom.read_bytes())
        write("assets/terminal-engine/PROVENANCE.json", (json.dumps({"coordinate": "org.connectbot:termlib:0.3.13", "sha256": PINS, "patched": sorted(patched), "native": "Unmodified JNI from original AAR"}, indent=2, sort_keys=True) + "\n").encode())
        # Guards have all passed. Sync only this task's output; preserve mtimes when unchanged.
        expected = {p.relative_to(stage) for p in stage.rglob("*") if p.is_file()}
        for relative in expected:
            target = output / relative
            data = (stage / relative).read_bytes()
            if not target.exists() or target.read_bytes() != data:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        for path in output.rglob("*"):
            if path.is_file() and path.relative_to(output) not in expected:
                path.unlink()
    print(f"termlib 0.3.13: {len(patched)} guarded Kotlin patches; original JNI/resources/rules -> {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sources", "aar", "pom", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    build(args.sources, args.aar, args.pom, args.output)
