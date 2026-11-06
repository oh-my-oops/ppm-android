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
        /**
         * [writeInput] for the buffer a server replays when it picks a running session up again. It is parsed
         * like any output and what it asks is dropped: a cursor position, the device attributes or a window
         * size was answered when a program first asked, and an answer now lands at whatever is running, a
         * shell prompt typically, as text that Enter then runs. An OSC 52 clipboard write in it is dropped
         * too, so history never overwrites what was copied since.
         */
        fun writeReplay(data: ByteArray)
        /** Screen-relative (row, column), zero-based. */
        fun getCursorPosition(): Pair<Int, Int>
        /** Atomic history + screen read, with an absolute cursor row and screenStartRow. */
        fun getBufferSnapshot(): TerminalBufferSnapshot
        /**
         * [getBufferSnapshot] without the history, for a read made on every key: the screen's rows, and the history rows the screen's
         * first line wraps on from (none unless that line began there). A full read copies every row the history holds, up to
         * 50,000, under the lock the parser writes through. [TerminalBufferSnapshot.firstRow] is where its first row is in the whole buffer.
         */
        fun getScreenSnapshot(): TerminalBufferSnapshot
        fun getBufferLines(): List<TerminalBufferLine> = getBufferSnapshot().lines
        /** Start inclusive, end exclusive, relative to retained history. */
        fun getTextBetweenRows(start: Int, endExclusive: Int): String = getBufferSnapshot().getTextBetweenRows(start, endExclusive)
        fun getScreenText(): String {
            val buffer = getBufferSnapshot()
            return buffer.getTextBetweenRows(buffer.screenStartRow, buffer.lines.size)
        }
    '''))
    text = replace(text, "    fun setDefaultColors(foreground: Int, background: Int): Int\n",
                   "    fun setDefaultColors(foreground: Int, background: Int): Int\n\n"
                   "    fun setDefaultColors(foreground: Int, background: Int, cursorColor: Int): Int\n")
    text = replace(text,
                   "    fun applyColorScheme(\n        ansiColors: IntArray,\n        defaultForeground: Int,\n        defaultBackground: Int,\n    )\n",
                   "    fun applyColorScheme(\n        ansiColors: IntArray,\n        defaultForeground: Int,\n        defaultBackground: Int,\n    )\n\n"
                   "    fun applyColorScheme(\n        ansiColors: IntArray,\n        defaultForeground: Int,\n        defaultBackground: Int,\n        defaultCursorColor: Int,\n    )\n")
    text = replace(text,
                   "            defaultForeground: Color = Color.White,\n            defaultBackground: Color = Color.Black,\n",
                   "            defaultForeground: Color = Color.White,\n            defaultBackground: Color = Color.Black,\n            defaultCursorColor: Color = Color.White,\n")
    text = replace(text,
                   "            defaultForeground = defaultForeground,\n            defaultBackground = defaultBackground,\n",
                   "            defaultForeground = defaultForeground,\n            defaultBackground = defaultBackground,\n            defaultCursorColor = defaultCursorColor,\n")
    text = replace(text,
                   "\n    defaultForeground: Color = Color.White,\n    defaultBackground: Color = Color.Black,\n",
                   "\n    defaultForeground: Color = Color.White,\n    defaultBackground: Color = Color.Black,\n    defaultCursorColor: Color = Color.White,\n")
    text = replace(text,
                   "    private var currentDefaultForeground: Color = defaultForeground\n    private var currentDefaultBackground: Color = defaultBackground\n",
                   "    private var currentDefaultForeground: Color = defaultForeground\n    private var currentDefaultBackground: Color = defaultBackground\n    internal var currentCursorColor: Color = defaultCursorColor\n")
    text = replace(text, "            inlineImages: InlineImages = InlineImages.Off,", "            inlineImages: InlineImages = InlineImages.Off,\n            maxScrollbackLines: Int = 50_000,")
    text = replace(text, "            inlineImages = inlineImages,", "            inlineImages = inlineImages,\n            maxScrollbackLines = maxScrollbackLines,")
    text = replace(text, "\n    inlineImages: InlineImages = InlineImages.Off,", "\n    inlineImages: InlineImages = InlineImages.Off,\n    private val maxScrollbackLines: Int = 50_000,")
    text = replace(text, "    // Handler for escaping native mutex", "    init { require(maxScrollbackLines >= 0) { \"maxScrollbackLines must be non-negative\" } }\n\n    // Handler for escaping native mutex")
    text = replace(text, "    private val maxScrollbackLines = 1000\n", "")
    # The JNI is reached through ScreenNative, so the unit tests can stand a model of libvterm in for it (the library cannot load on the JVM).
    text = replace(text, "    private val maxScrollbackLines: Int = 50_000,\n) : TerminalEmulator,",
                   "    private val maxScrollbackLines: Int = 50_000,\n    private val nativeFactory: (TerminalCallbacks) -> ScreenNative = { TerminalNativeAdapter(TerminalNative(it)) },\n) : TerminalEmulator,")
    text = replace(text, "    private val damageLock = Any()", "    private val damageLock = Any()\n    @Volatile override var isClosed = false\n        private set\n    private var currentAnsiPalette: IntArray? = null\n    /** True only while [writeReplay] parses. Set and read under [damageLock]: libvterm calls back from inside the parse, on its thread. */\n    private var replaying = false")
    start = text.index("    private val terminalNative by lazy {")
    end = text.index("    // Parser for OSC sequences", start)
    text = text[:start] + dedent('''
        private var nativeInstance: ScreenNative? = null
        private val terminalNative: ScreenNative
            get() {
                check(!isClosed) { "Terminal is closed" }
                return nativeInstance ?: nativeFactory(this).also { native ->
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
        override fun writeReplay(data: ByteArray): Unit = synchronized(damageLock) {
            if (isClosed) return@synchronized
            replaying = true
            try {
                writeInput(data, 0, data.size)
            } finally {
                replaying = false
            }
        }

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

        override fun getScreenSnapshot(): TerminalBufferSnapshot = synchronized(damageLock) {
            if (!isClosed) processPendingUpdates()
            val value = _snapshot.value
            val history = if (value.alternateScreen) emptyList() else value.scrollback
            // Each row of the history that wraps on into the next one belongs to the screen's first line.
            var above = 0
            while (above < history.size && history[history.size - 1 - above].softWrapped) above++
            TerminalBufferSnapshot(
                (history.subList(history.size - above, history.size) + value.lines).map { TerminalBufferLine(it.text.replace('\\u0000', ' '), it.softWrapped) },
                above + value.cursorRow, value.cursorCol, above, value.alternateScreen, history.size - above,
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
            scrollbackWrap.reset()
            scrollbackSnapshot = emptyList()
            scrollbackDirty = false
            pendingScrollback = null
            pendingDamageRegions.clear()
            pendingSemanticSegments.clear()
            movedSegmentRows.clear()
            semanticSegmentTexts.clear()
            segmentRowsShifted = 0
            resizePushes = -1
            writeCuts.reset(rows)
            unrecordedLinks.clear()
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
    text = replace(text,
                   "        setAnsiPalette(ansiColors)\n        setDefaultColors(defaultForeground, defaultBackground)\n    }\n",
                   "        setAnsiPalette(ansiColors)\n        setDefaultColors(defaultForeground, defaultBackground)\n    }\n\n" + indent(dedent('''\
        override fun setDefaultColors(foreground: Int, background: Int, cursorColor: Int): Int = synchronized(damageLock) {
            if (isClosed) return@synchronized -1
            currentCursorColor = Color(cursorColor)
            setDefaultColors(foreground, background)
        }

        override fun applyColorScheme(
            ansiColors: IntArray,
            defaultForeground: Int,
            defaultBackground: Int,
            defaultCursorColor: Int,
        ) {
            require(ansiColors.size >= 16) {
                "Color scheme must provide 16 ANSI colors"
            }

            setAnsiPalette(ansiColors)
            setDefaultColors(defaultForeground, defaultBackground, defaultCursorColor)
        }
    '''), "    "))
    text = replace(text, "        val result = terminalNative.setPaletteColors(ansiColors, 16)", "        currentAnsiPalette = ansiColors.copyOf(16)\n        val result = terminalNative.setPaletteColors(ansiColors, 16)")
    text = replace(text, "        if (damagePosted) return", "        if (isClosed || damagePosted) return")
    text = replace(text, "                                    synchronized(imageStore) { completion(result.getOrDefault(false)) }", "                                    if (isClosed) return@synchronized\n                                    synchronized(imageStore) { completion(result.getOrDefault(false)) }")
    # libvterm answers a query inside the parse that read it. Every answer (device attributes, cursor position,
    # window size, the image protocols') reaches Kotlin through this one callback, so a replay's are dropped here,
    # and so is libvterm's own DECRQM reply for 47, 1047 and 1049 while a piece is written (answerQuery sends xterm.js's).
    text = replace(text, "    override fun onKeyboardInput(data: ByteArray): Int {\n        outputBatch?.let { batch ->",
                   "    override fun onKeyboardInput(data: ByteArray): Int {\n        if (replaying || (inWrite && isNativeAltScreenModeReport(data))) return 0\n        outputBatch?.let { batch ->")
    # History must not overwrite what the user has copied since: a replayed OSC 52 write is not performed either.
    text = replace(text, "                        handler.post {\n                            onClipboardCopy?.invoke(action.data)\n                        }",
                   "                        if (!replaying) {\n                            handler.post {\n                                onClipboardCopy?.invoke(action.data)\n                            }\n                        }")
    # libvterm's scrollback callback sends the soft-wrap flag of the row after the one pushed (see ScrollbackWrap),
    # so the history stored every row with its neighbour's flag: URLs wrapped across rows came out glued or cut.
    text = replace(text, "    private val scrollback = ArrayDeque<TerminalLine>()\n",
                   "    private val scrollback = ArrayDeque<TerminalLine>()\n    private val scrollbackWrap = ScrollbackWrap()\n")
    text = replace(text, "softWrapped = softWrapped, semanticSegments = line0Segments)", "softWrapped = wrapped, semanticSegments = line0Segments)")
    text = replace(text, "            imageStore.trimHistory(0)\n            scrollback.clear()\n", "            imageStore.trimHistory(0)\n            scrollback.clear()\n            scrollbackWrap.reset()\n")
    text = replace(text, "            scrollback.removeLast()\n            scrollbackDirty = true",
                   "            val restored = scrollback.removeLast()\n            scrollbackWrap.pop(restored.softWrapped)\n            if (resizePushes >= 0) resizeRestored.add(restored)\n            scrollbackDirty = true")
    # libvterm merges the scrolls of one write (VTERM_DAMAGE_SCROLL): it sends each row it pushes into the history, and every OSC
    # with the cursor's row, as they happen, but the one moverect for all of them only when the write is flushed. Segments stayed
    # on the rows the screen had before the write, so every pushed row got the top row's links and a link written after a scroll
    # landed on a row that was about to move. They now move up one row with every row pushed, as the rows themselves do; the
    # moverect then moves the cells only (see SegmentScrollTest).
    text = replace(text, "    private val pendingSemanticSegments = mutableListOf<PendingSemanticSegment>()\n",
                   "    private val pendingSemanticSegments = mutableListOf<PendingSemanticSegment>()\n" + indent(dedent('''
        /** Rows by which the pushes of the write in progress already moved [currentLines]' segments; the one moverect must not move them again. */
        private var segmentRowsShifted = 0

        /** -1 outside a resize; during one, how many rows it has pushed (its rows are not scrolls, and the screen's segments stay where they are). */
        private var resizePushes = -1

        /** What the resize in progress took back from the history so far, in the order it took it: [remapScreen] puts the lines on the screen. */
        private val resizeRestored = mutableListOf<TerminalLine>()

        /**
         * Whether the row that scrolls off was soft-wrapped. Outside a resize that is the chain of [ScrollbackWrap], with the one thing the library
         * cannot tell it: the row after this one may have a wrap the library lost in a resize, and the flag that comes with the push is read from
         * line infos it cleared (see [TerminalLine.wrapKept]). Flags do not move until the moverect, so the row after is [segmentRowsShifted] + 1.
         * During a resize the library sends one flag for the whole batch, the first row's: the screen's own flag is the row's, as the mirror had it
         * when the resize began (it is caught up with the library first).
         */
        private fun pushedRowWrapped(sent: Boolean): Boolean =
            if (resizePushes >= 0) currentLines.getOrNull(resizePushes)?.softWrapped == true
            else scrollbackWrap.push(sent, currentLines.getOrNull(segmentRowsShifted + 1)?.wrapKept == true)

        /** The segments of the row that scrolls off the top, as long as the cells pushed are still what they were made for. */
        private fun scrolledOutSegments(pushed: PackedCells): List<SemanticSegment> {
            val top = if (resizePushes >= 0) resizePushes++ else 0
            return currentLines.getOrNull(top)?.semanticSegments.orEmpty().filter { segment ->
                isValidCellRange(segment.startCol, segment.endCol, pushed.size) &&
                    segmentTextStillMatches(top, segment, pushed) &&
                    (segment.semanticType != SemanticType.HYPERLINK || cellText(pushed, segment.startCol, segment.endCol).any { it != '\\u0000' && !it.isWhitespace() })
            }
        }

        /**
         * The screen as the library laid it out when it resized it (see [ResizedRows]); called once [rows] and [cols] are the new size, with the
         * mirror still the old screen. Each row goes with the line it is, with its links, their texts, its prompt and its wrap, and a row that came
         * back from the history with what that line was pushed with. The alternate screen is another case: what the library keeps of it is not told
         * (the rows it pushes are the screen shown, which is not the shell's), so nothing moves there when it grows or stays as tall, and nothing is
         * carried when it shrinks.
         */
        private fun remapScreen(oldRows: Int, oldCols: Int, pushed: Int, restored: List<TerminalLine>) {
            val alternate = isAltScreenActive
            val moved = if (alternate) ResizedRows(oldRows, rows, 0, 0) else ResizedRows(oldRows, rows, pushed, restored.size)
            val known = !alternate || rows >= oldRows
            val laidOut = moved.remap(currentLines, restored, keepFlags = !alternate && cols == oldCols)
            currentLines = if (known) laidOut.toMutableList() else laidOut.mapTo(ArrayList(rows)) { it.copy(semanticSegments = emptyList()) }
            val texts = semanticSegmentTexts.entries.mapNotNull { (key, text) ->
                val row = moved.newRowOf(key.row)
                if (known && row in 0 until rows) key.copy(row = row) to text else null
            }
            semanticSegmentTexts.clear()
            semanticSegmentTexts.putAll(texts)
            // The rows that came back have the segments they were pushed with, over the text their cells hold now.
            for (row in 0 until moved.popped) currentLines[row].let { line -> line.semanticSegments.forEach { storeSegmentText(row, it, line) } }
            if (!alternate) scrollbackWrap.top(currentLines[0].softWrapped)
        }

        /** What a push does to the rows: every row's segments move up one, and the bottom row has none. */
        private fun shiftSegmentsUp() {
            if (resizePushes >= 0) return
            segmentRowsShifted++
            if (pendingSemanticSegments.isEmpty() && unrecordedLinks.isEmpty() && semanticSegmentTexts.isEmpty() && currentLines.none { it.semanticSegments.isNotEmpty() }) return
            val before = currentLines
            currentLines = before.mapIndexedTo(ArrayList(before.size)) { row, line ->
                val below = before.getOrNull(row + 1)?.semanticSegments.orEmpty()
                if (line.semanticSegments.isEmpty() && below.isEmpty()) line else line.copy(semanticSegments = below)
            }
            val pending = pendingSemanticSegments.filter { it.row > 0 }.map { it.copy(row = it.row - 1) }
            pendingSemanticSegments.clear()
            pendingSemanticSegments.addAll(pending)
            val waiting = unrecordedLinks.filter { it.row > 0 }.map { it.copy(row = it.row - 1) }
            unrecordedLinks.clear()
            unrecordedLinks.addAll(waiting)
            val texts = semanticSegmentTexts.entries.filter { it.key.row > 0 }.map { it.key.copy(row = it.key.row - 1) to it.value }
            semanticSegmentTexts.clear()
            semanticSegmentTexts.putAll(texts)
        }

        /** The screen's segments are not guessed at when a scroll cannot be told from the pushes: they are dropped, never left on a wrong row. */
        private fun dropScreenSegments() {
            currentLines = currentLines.mapTo(ArrayList(currentLines.size)) { if (it.semanticSegments.isEmpty()) it else it.copy(semanticSegments = emptyList()) }
            pendingSemanticSegments.clear()
            semanticSegmentTexts.clear()
        }

        /**
         * Whether the pushes already moved the segments as far as this moverect moves the cells. They did when the scroll was
         * the whole screen's and as long as the pushes: a write that also scrolled the other way, or a region that stops above
         * the last row, is not.
         */
        private fun segmentsMovedByPushes(dest: TermRect, src: TermRect): Boolean {
            val shifted = segmentRowsShifted
            segmentRowsShifted = 0
            if (shifted == 0) return false
            val wholeScreen = dest.startRow == 0 && src.endRow == rows && dest.startCol == 0 && src.startCol == 0 && dest.endCol == cols && src.endCol == cols
            if (wholeScreen && src.startRow == shifted) return true
            dropScreenSegments()
            return false
        }

        /** The end of a write. A scroll of more rows than the screen has is flushed with no moverect, so the count must not outlive the write. */
        internal fun endScrollBatch() = synchronized(damageLock) {
            segmentRowsShifted = 0
            scrolledInFlush = false
        }

        /** The flush of the write in progress moved rows: the rectangle it reports as damaged covers them, which is not a redraw. */
        private var scrolledInFlush = false

        /** True while a write is handed to the library: what it reports as damaged is the output's. */
        private var inWrite = false

        /**
         * Whether a link still stands over its cells: while the text it was made over is there. libvterm reports what a flush changed as one
         * rectangle, which also covers the cells between two that changed, so damage alone says nothing about a link; what the cells hold now
         * does. (A link made again over the same text, or the same text written over it, is not told apart. A redraw of the whole
         * screen is: see [dropScreenLinks].)
         */
        private fun linkTextIntact(row: Int, segment: SemanticSegment, cells: PackedCells): Boolean {
            val text = semanticSegmentTexts[SemanticSegmentKey(row, segment)] ?: return false
            return isValidCellRange(segment.startCol, segment.endCol, cells.size) && cellText(cells, segment.startCol, segment.endCol) == text
        }

        /** The whole screen was redrawn (`clear`, a reset, the other screen): no link on it stands, the text drawn being the same as before or not. */
        private fun dropScreenLinks() {
            if (currentLines.none { line -> line.semanticSegments.any { it.semanticType == SemanticType.HYPERLINK } }) return
            currentLines = currentLines.mapTo(ArrayList(currentLines.size)) { line ->
                if (line.semanticSegments.none { it.semanticType == SemanticType.HYPERLINK }) line
                else line.copy(semanticSegments = line.semanticSegments.filter { it.semanticType != SemanticType.HYPERLINK })
            }
            semanticSegmentTexts.keys.removeAll { it.semanticType == SemanticType.HYPERLINK }
        }

        /** The cells of a row carry one link: a link made over cells that are another's replaces it. */
        private fun withoutOverlappedLinks(segments: List<SemanticSegment>, added: SemanticSegment): List<SemanticSegment> =
            if (added.semanticType != SemanticType.HYPERLINK) segments
            else segments.filterNot { it.semanticType == SemanticType.HYPERLINK && it != added && it.overlaps(added.startCol, added.endCol) }

        /** Where the OSC 8 sequences of what is written end, and what scroll region the library holds and the screen it shows has (see [WriteCuts]). */
        private val writeCuts = WriteCuts(initialRows)

        /** The links the piece just written made: they are on the screen, with their text recorded, once the piece is over. */
        private val unrecordedLinks = mutableListOf<PendingSemanticSegment>()

        /**
         * Hands the library a write in pieces that each end right after an OSC 8, and puts the links the piece made on the screen with the
         * text under them. libvterm says where a link is when it ends, but what is under it the emulator can only read from the library's
         * screen, and the rest of the write may already have replaced those cells: the link was then shown over the text that is there now
         * (a replay is one write, and live output arrives in writes the snapshot has not caught up with). [write] must hand on exactly the
         * bytes from [from] until [to].
         */
        private inline fun writeInPieces(start: Int, end: Int, next: (from: Int) -> Int, write: (from: Int, to: Int) -> Unit) {
            var from = start
            while (true) {
                val cut = next(from)
                val to = if (cut < 0) end else cut
                inWrite = true
                try {
                    write(from, to)
                } finally {
                    inWrite = false
                }
                endScrollBatch()
                if (cut >= 0) {
                    recordLinkTexts()
                    writeRegionFix()
                    answerQuery()
                }
                if (to >= end) return
                from = to
            }
        }

        /**
         * libvterm keeps one scroll region for both screens and a resize only ever shortens it, where xterm.js keeps one for each screen,
         * starts the alternate one whole, gives the shell's back when it is left and resets both when the size changes (see [WriteCuts]).
         * When what the library holds is not what the screen it shows should have, this is the region and the cursor put back where the
         * region moved it from: the bytes are two sequences that answer nothing, and none are written otherwise.
         */
        private fun writeRegionFix() {
            val fix = writeCuts.regionFix(cursorRow, cursorCol) ?: return
            terminalNative.writeInput(fix, 0, fix.size)
        }

        /**
         * Sends xterm.js's reply to the colour query (`OSC 10 / 11 / 12 ; ?`) or alternate-screen DECRQM (`CSI ? 47 / 1047 / 1049 $ p`)
         * that ended the piece just written, right after any reply libvterm itself sent from inside that piece.
         */
        private fun answerQuery() {
            val answer = writeCuts.queryAnswer(
                currentDefaultForeground.toArgb(),
                currentDefaultBackground.toArgb(),
                currentCursorColor.toArgb(),
            ) ?: return
            onKeyboardInput(answer)
        }

        /**
         * libvterm's `request_dec_mode` in `state.c` answers `0` ("not recognised") for `47` and `1049`, and `1` or `2` for `1047`.
         * [WriteCuts] cuts at all three and [answerQuery] sends xterm.js's reply after the piece, so the library's own reply during the
         * piece is dropped here.
         */
        private fun isNativeAltScreenModeReport(data: ByteArray): Boolean {
            val n = data.size
            if (n != 9 && n != 11) return false
            if (data[0] != 0x1B.toByte() || data[1] != '['.code.toByte() || data[2] != '?'.code.toByte()) return false
            if (data[n - 4] != ';'.code.toByte() || data[n - 2] != '$'.code.toByte() || data[n - 1] != 'y'.code.toByte()) return false
            return if (n == 9) {
                data[3] == '4'.code.toByte() && data[4] == '7'.code.toByte()
            } else {
                data[3] == '1'.code.toByte() && data[4] == '0'.code.toByte() && data[5] == '4'.code.toByte() &&
                    (data[6] == '7'.code.toByte() || data[6] == '9'.code.toByte())
            }
        }

        /**
         * Puts each link the piece just handed to the library made on the screen, with the text [readText] says is under it, read from
         * the library's screen while that is still what the link was written over: a link stands for as long as that text is there (see
         * [linkTextIntact]). The piece ended with the link, so the row it was made on is the row the screen has it on, and everything the
         * piece reported as damaged or scrolled came before the link: it is not the link's, which is why the link is only put on the
         * screen now.
         */
        internal fun placeLinks(readText: (row: Int, startCol: Int, endCol: Int) -> String) {
            if (unrecordedLinks.isEmpty()) return
            val links = unrecordedLinks.toList()
            unrecordedLinks.clear()
            for (link in links) {
                if (link.row !in 0 until rows || link.startCol < 0 || link.startCol >= link.endCol || link.endCol > cols) continue
                val segment = SemanticSegment(link.startCol, link.endCol, link.semanticType, link.metadata, link.promptId)
                val line = currentLines[link.row]
                currentLines = currentLines.toMutableList().apply {
                    this[link.row] = line.copy(semanticSegments = (withoutOverlappedLinks(line.semanticSegments, segment) + segment).sortedBy { it.startCol })
                }
                semanticSegmentTexts[SemanticSegmentKey(link.row, segment)] = readText(link.row, link.startCol, link.endCol)
            }
        }

        private fun recordLinkTexts() = placeLinks { row, startCol, endCol -> screenText(row, startCol, endCol) }

        /** What the library's screen holds in [startCol] until [endCol] of [row]: one small read. */
        private fun screenText(row: Int, startCol: Int, endCol: Int): String {
            for (r in 0 until rows) {
                transferRanges[r * 2] = cols
                transferRanges[r * 2 + 1] = 0
            }
            transferRanges[row * 2] = startCol
            transferRanges[row * 2 + 1] = endCol
            val text = StringBuilder()
            screenTransfer.fetch(terminalNative, transferRanges) { _, _, count, buffer, offset, _ ->
                text.append(PackedCells.Builder(count).apply { read(buffer, offset, count) }.build().text())
            }
            return text.toString()
        }
    '''), "    "))
    text = replace(text,
                   "            // FIRST: Preserve semantic segments from line 0 (the line being scrolled out)\n"
                   "            // This must happen BEFORE we shift segments, since moverect was already called\n"
                   "            val line0Segments = if (currentLines.isNotEmpty()) {\n"
                   "                currentLines[0].semanticSegments\n"
                   "            } else {\n"
                   "                emptyList()\n"
                   "            }\n",
                   "            // The row on top is the one that scrolls off: its own wrap and links, whatever the rows below are about to become.\n"
                   "            val wrapped = pushedRowWrapped(softWrapped)\n"
                   "            val line0Segments = scrolledOutSegments(cellList)\n"
                   "            shiftSegmentsUp()\n")
    text = replace(text, "        synchronized(damageLock) {\n            if (\n                dest.startCol == 0 && src.startCol == 0 &&",
                   "        synchronized(damageLock) {\n            val segmentsMoved = segmentsMovedByPushes(dest, src)\n            if (\n                dest.startCol == 0 && src.startCol == 0 &&")
    text = replace(text, "                        updated[destRow] = previous[srcRow].copy(row = destRow)\n",
                   "                        updated[destRow] = previous[srcRow].copy(row = destRow, semanticSegments = previous[if (segmentsMoved) destRow else srcRow].semanticSegments)\n")
    text = replace(text, "                    if (key.row !in src.startRow until src.endRow) return@mapNotNull null\n",
                   "                    if (segmentsMoved || key.row !in src.startRow until src.endRow) return@mapNotNull null\n")
    text = replace(text, "                semanticSegmentTexts.keys.removeAll { it.row in dest.startRow until dest.endRow }\n                semanticSegmentTexts.putAll(movedTexts)",
                   "                if (!segmentsMoved) semanticSegmentTexts.keys.removeAll { it.row in dest.startRow until dest.endRow }\n                semanticSegmentTexts.putAll(movedTexts)")
    # A write reaches the library in pieces, each ending after an OSC 8, and the text under a link is recorded as it ends (see writeInPieces).
    text = replace(text, "        terminalNative.writeInput(data, offset, length)\n", indent(dedent('''\
        val end = offset + length
        writeInPieces(offset, end, { writeCuts.next(data, it, end) }) { from, to ->
            // The library copies the whole array on every call, so a piece is an array of its own.
            if (from == offset && to == end) {
                terminalNative.writeInput(data, offset, length)
            } else {
                val piece = data.copyOfRange(from, to)
                terminalNative.writeInput(piece, 0, piece.size)
            }
        }
    '''), "        "))
    text = replace(text, "        terminalNative.writeInput(buffer, length)\n", indent(dedent('''\
        if (length !in 0..buffer.capacity()) {
            // The library refuses it, whichever way it is wrong.
            terminalNative.writeInput(buffer, length)
            return@synchronized
        }
        // The library reads a direct buffer from its start up to the length given, whatever its position and limit are.
        val bytes = buffer.duplicate().apply { clear() }
        writeInPieces(0, length, { writeCuts.next(bytes, it, length) }) { from, to ->
            if (from == 0 && to == length) {
                terminalNative.writeInput(buffer, length)
            } else {
                terminalNative.writeInput(bytes.duplicate().apply { limit(to); position(from) }.slice(), to - from)
            }
        }
    '''), "        "))
    # A link is the link of its text for as long as the text is there, whatever else the rectangle libvterm reports as damaged covers.
    text = replace(text, "                        segment.endCol <= cells.size &&\n                            damageRegions.none { region ->\n                                !region.preserveSegments && row >= region.startRow && row < region.endRow &&\n                                    segment.overlaps(region.startCol, region.endCol)\n                            }\n",
                   "                        segment.endCol <= cells.size &&\n                            (if (segment.semanticType == SemanticType.HYPERLINK) linkTextIntact(row, segment, cells) else damageRegions.none { region ->\n                                !region.preserveSegments && row >= region.startRow && row < region.endRow &&\n                                    segment.overlaps(region.startCol, region.endCol)\n                            })\n")
    # A redraw of the whole screen ends every link on it. Rows that moved are told by their text alone, so a flush that scrolled is not a redraw.
    text = replace(text, "        synchronized(damageLock) {\n            addDamageRegion(startRow, endRow, startCol, endCol)\n",
                   "        synchronized(damageLock) {\n            if (inWrite && !scrolledInFlush && startRow <= 0 && endRow >= rows && startCol <= 0 && endCol >= cols) dropScreenLinks()\n            addDamageRegion(startRow, endRow, startCol, endCol)\n")
    text = replace(text, "            val segmentsMoved = segmentsMovedByPushes(dest, src)\n            if (\n",
                   "            val segmentsMoved = segmentsMovedByPushes(dest, src)\n            scrolledInFlush = true\n            if (\n")
    text = replace(text, "        val updatedSegments = (line.semanticSegments + newSegment)\n            .sortedBy { it.startCol }\n",
                   "        val updatedSegments = (withoutOverlappedLinks(line.semanticSegments, newSegment) + newSegment)\n            .sortedBy { it.startCol }\n")
    # A link is put on the screen when the piece that made it is over, with its text (see writeInPieces), so it is not one of the pending
    # segments that are applied blindly at the next snapshot. A link no piece recorded (none, unless libvterm ends an OSC 8 where
    # WriteCuts sees no end) still is.
    text = replace(text, "            val updatedSegments = (line.semanticSegments + newSegment).sortedBy { it.startCol }\n", indent(dedent('''\
        if (semanticType == SemanticType.HYPERLINK) {
            unrecordedLinks.add(PendingSemanticSegment(row, startCol, endCol, semanticType, metadata, promptId))
            propertyChanged = true
            requestProcessPendingUpdatesLocked()
            return
        }
        val updatedSegments = (line.semanticSegments + newSegment).sortedBy { it.startCol }
    '''), "            "))
    text = replace(text, "    fun processPendingUpdates(): Unit = synchronized(damageLock) {\n        if (isClosed) return@synchronized\n        // Collect pending changes\n",
                   "    fun processPendingUpdates(): Unit = synchronized(damageLock) {\n        if (isClosed) return@synchronized\n"
                   "        recordLinkTexts()\n"
                   "        // Collect pending changes\n")
    # The library clears every row's wrap flag and moves rows to and from the history (see ResizedRows): the mirror is caught up with it first,
    # for the flags of the rows about to be pushed, and the screen is laid out as the library laid it out, with what the rows carry.
    text = replace(text, "            terminalNative.resize(newRows, newCols)\n            rows = newRows\n", indent(dedent('''\
        processPendingUpdates()
        val oldRows = rows
        val oldCols = cols
        var pushed = 0
        var restored = emptyList<TerminalLine>()
        resizePushes = 0
        try {
            terminalNative.resize(newRows, newCols)
        } finally {
            pushed = resizePushes
            resizePushes = -1
            restored = resizeRestored.toList()
            resizeRestored.clear()
        }
        rows = newRows
    '''), "            "))
    text = replace(text, "            currentLines = MutableList(newRows) { row ->\n                currentLines.getOrNull(row) ?: TerminalLine.empty(row, 0)\n            }\n"
                         "            transferRanges = IntArray(newRows * 2)\n"
                         "            semanticSegmentTexts.keys.removeAll { it.row >= newRows }\n",
                   "            remapScreen(oldRows, oldCols, pushed, restored)\n            transferRanges = IntArray(newRows * 2)\n")
    # xterm.js resets the scroll region of both screens whenever the size really changes, and libvterm only cuts it down to a shorter screen, so
    # a region an explicit bottom was set for stays when the screen grows (see WriteCuts). The size is told to the scan once the library has it.
    text = replace(text, "            // Rebuild all lines after resize\n            invalidateDisplay()\n",
                   "            // Rebuild all lines after resize\n            invalidateDisplay()\n"
                   "            writeCuts.resized(newRows)\n"
                   "            writeRegionFix()\n")
    # A wrap the library lost in a resize (TerminalLine.wrapKept) stays on its row until a write changes the row, and then the library says what is true. A
    # row the damage only covers, as a scroll's moverect or the one rectangle a flush reports for everything it touched does, keeps it while its cells are the same.
    text = replace(text, "                if (cells !== previousLine.cells || softWrapped != previousLine.softWrapped || preservedSegments != existingSegments) {\n"
                         "                    currentLines[row] = TerminalLine(row, cells, softWrapped = softWrapped, semanticSegments = preservedSegments)\n",
                   "                val kept = previousLine.wrapKept && !softWrapped && (cells === previousLine.cells ||\n"
                   "                    damageRegions.none { region -> !region.preserveSegments && row >= region.startRow && row < region.endRow })\n"
                   "                val wrapped = softWrapped || kept\n"
                   "                if (cells !== previousLine.cells || wrapped != previousLine.softWrapped || kept != previousLine.wrapKept || preservedSegments != existingSegments) {\n"
                   "                    currentLines[row] = TerminalLine(row, cells, softWrapped = wrapped, semanticSegments = preservedSegments, wrapKept = kept)\n")
    # A screen row's flag is set when text reaches the row below it and is read only with a cell of its own row, so a
    # line that wraps in a later write than its first row kept the flag of the first (see refreshRowsAbove).
    text = replace(text, "        var builder: PackedCells.Builder? = null\n        screenTransfer.fetch(",
                   "        refreshRowsAbove(transferRanges, rows, cols, { currentLines[it].cells.size }) { row, col -> currentLines[row].cells.width(col) }\n        var builder: PackedCells.Builder? = null\n        screenTransfer.fetch(")
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
    text = replace(text, signature, "    onPageGesture: ((Int) -> Unit)? = null,\n    cursorColor: Color = Color.Unspecified,\n    cursorAccent: Color = Color.Unspecified,\n    minimumContrastRatio: Double = 4.5,\n    onCursorBoundsChanged: ((Rect?) -> Unit)? = null,\n    selectionActions: (@Composable (SelectionController) -> Unit)? = null,\n    touchSelect: Boolean = false,\n) {\n    require(minimumContrastRatio.isFinite() && minimumContrastRatio in 1.0..21.0)", 2)
    text = replace(text, "        onPageGesture = onPageGesture,", "        onPageGesture = onPageGesture,\n        cursorColor = cursorColor, cursorAccent = cursorAccent, minimumContrastRatio = minimumContrastRatio,\n        onCursorBoundsChanged = onCursorBoundsChanged,\n        selectionActions = selectionActions,\n        touchSelect = touchSelect,")
    text = replace(text, "remember(typeface, calculatedFontSize, density)", "remember(typeface, calculatedFontSize, density, minimumContrastRatio)")
    text = replace(text, "TerminalTextPaint(typeface, with(density) { calculatedFontSize.toPx() })", "TerminalTextPaint(typeface, with(density) { calculatedFontSize.toPx() }, minimumContrastRatio)")
    text = replace(text, "    SideEffect {\n        keyboardHandler.onInterceptKey = currentOnInterceptKey\n    }\n",
                   "    SideEffect {\n        keyboardHandler.onInterceptKey = currentOnInterceptKey\n        if (cursorColor != Color.Unspecified) terminalEmulator.currentCursorColor = cursorColor\n    }\n")
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
    return web_links(touch_select(tap_asks_for_focus(text)))


def tap_asks_for_focus(text):
    # The web's capture-phase `touchend` (terminal-tab.tsx) asks for the focus on every tap of the terminal, whatever
    # else the tap does. Upstream asks, and calls onTerminalTap, only for a tap that cleared no selection and opened
    # no link, so a tap that did either left the terminal unfocused, and `leavesTextOnTap` blurred a focused one.
    old = """                                        currentOnHyperlinkClick(hyperlinkUrl)
                                    } else {
                                        // Request focus when terminal is tapped to show keyboard
                                        if (currentKeyboardEnabled) {
                                            focusRequester.requestFocus()
                                            if (currentShouldShowIme) imeInputView?.showIme()
                                        }
                                        currentOnTerminalTap()
                                    }
                                }
                                // Record tap for double-tap detection
"""
    new = """                                        currentOnHyperlinkClick(hyperlinkUrl)
                                    }
                                }
                                // Every tap asks for the focus: one that cleared a selection or opened a link too.
                                if (currentKeyboardEnabled) {
                                    focusRequester.requestFocus()
                                    if (currentShouldShowIme) imeInputView?.showIme()
                                }
                                currentOnTerminalTap()
                                // Record tap for double-tap detection
"""
    return replace(text, old, new)


def touch_select(text):
    # Select mode, as the web's useTerminalTouchSelection does it: a one-finger drag is a selection from the cell the
    # finger came down on, as xterm's own mouse selection makes one, and it is not a scroll, a long press, a double tap,
    # a handle grab or a link. Upstream turns a drag into a scroll, and the app's old select mode (one cell at the
    # cursor, `isSelecting` left on) therefore showed nothing at all.
    text = replace(text, "    val currentKeyboardEnabled by rememberUpdatedState(keyboardEnabled)\n", "    val currentKeyboardEnabled by rememberUpdatedState(keyboardEnabled)\n    val currentTouchSelect by rememberUpdatedState(touchSelect)\n")
    marker = "                        scrollJob?.cancel()\n\n                        // 1a. Check for double-tap to start word selection\n"
    text = replace(text, marker, """                        // Select mode: the cell the finger came down on anchors a character selection that the drag extends.
                        fun beginTouchSelection() {
                            val target = selectionPoint(down.position)
                            val col = (target.x / baseCharWidth).toInt().coerceIn(0, screenState.snapshot.cols - 1)
                            val row = (target.y / baseCharHeight).toInt().coerceIn(0, screenState.snapshot.rows - 1)
                            selectionManager.beginDragSelection(
                                row,
                                textPaint.logicalColumn(screenState, row, col, baseCharWidth),
                                screenState.snapshot,
                                screenState.scrollbackPosition,
                            )
                            selectionManager.selectionRange?.let { releaseFilter.record(it, down.uptimeMillis) }
                            onAutoScroll = { if (selectionManager.isSelecting) selectAt(autoScrollPointer, android.os.SystemClock.uptimeMillis()) }
                            startAutoScroll()
                        }
""" + marker)
    text = replace(text, "                        val isDoubleTap = (down.uptimeMillis", "                        val isDoubleTap = !currentTouchSelect && (down.uptimeMillis")
    text = replace(text, "                        val longPressJob = if (gestureType == GestureType.Undetermined) {", "                        val longPressJob = if (gestureType == GestureType.Undetermined && !currentTouchSelect) {")
    return replace(text, "                                        gestureType = if (pagingCandidate && singleTouch &&", """                                        gestureType = if (dragSelects(currentTouchSelect, singleTouch)) {
                                            beginTouchSelection()
                                            GestureType.Selection
                                        } else if (pagingCandidate && singleTouch &&""")


def web_links(text):
    # Links as the web's terminal has them (TerminalWebLinks and TerminalLinkInput hold the rules; this is where Terminal asks).
    # xterm finds a plain http(s) URL itself, and an OSC 8 one from the cells. It underlines a link only while a mouse is over it,
    # opens it only for a mouse click that went down and came up on it, and draws an OSC 8 cell dashed all the time. Upstream
    # underlines every URL it detects all the time and opens one on any tap, a finger's included, which is what scrolling through
    # output with a thumb does all day.
    text = replace(text, "import androidx.compose.ui.input.pointer.PointerType\n", "import androidx.compose.ui.input.pointer.PointerEventType\nimport androidx.compose.ui.input.pointer.PointerIcon\nimport androidx.compose.ui.input.pointer.PointerType\nimport androidx.compose.ui.input.pointer.pointerHoverIcon\n")
    text = replace(text, "import androidx.compose.ui.unit.TextUnit\n", "import androidx.compose.ui.unit.IntOffset\nimport androidx.compose.ui.unit.TextUnit\n")
    # An OSC 8 link asks before it opens, as the web's own default does (`confirm(...)`); a plain URL opens at once.
    text = replace(text, "    touchSelect: Boolean = false,\n) {\n    require(minimumContrastRatio.isFinite() && minimumContrastRatio in 1.0..21.0)", "    touchSelect: Boolean = false,\n    onOsc8HyperlinkClick: ((String) -> Unit)? = null,\n) {\n    require(minimumContrastRatio.isFinite() && minimumContrastRatio in 1.0..21.0)", 2)
    text = replace(text, "        touchSelect = touchSelect,", "        touchSelect = touchSelect,\n        onOsc8HyperlinkClick = onOsc8HyperlinkClick,")
    text = replace(text, "    val currentOnHyperlinkClick by rememberUpdatedState(onHyperlinkClick)\n", "    val currentOnHyperlinkClick by rememberUpdatedState(onHyperlinkClick)\n    val currentOnOsc8HyperlinkClick by rememberUpdatedState(onOsc8HyperlinkClick ?: onHyperlinkClick)\n")
    # The cell a mouse is over, apart from the gesture below: a hover presses nothing, and a finger never sets one.
    text = replace(text, "    // Scroll animation state\n", """    // xterm's Linkifier asks for the link under the mouse on every move, underlines it and gives it the hand pointer.
    var hoverCell by remember(terminalEmulator) { mutableStateOf<IntOffset?>(null) }
    fun cellAt(position: Offset): IntOffset? {
        val snapshot = screenState.snapshot
        if (position.x < 0f || position.y < 0f || position.x >= snapshot.cols * baseCharWidth || position.y >= snapshot.rows * baseCharHeight) return null
        return IntOffset((position.x / baseCharWidth).toInt(), (position.y / baseCharHeight).toInt())
    }
    fun linkAtCell(cell: IntOffset): TerminalLink? =
        screenState.linkAt(cell.y, textPaint.logicalColumn(screenState, cell.y, cell.x, baseCharWidth))
    val hoverLink by remember(terminalEmulator, screenState) { derivedStateOf { hoverCell?.let(::linkAtCell) } }

    // Scroll animation state
""")
    text = replace(text, "                ).pointerInput(terminalEmulator, baseCharWidth, baseCharHeight) {\n                val touchSlopSquared =\n", """                ).pointerHoverIcon(if (hoverLink != null) PointerIcon.Hand else PointerIcon.Text)
                .pointerInput(terminalEmulator, baseCharWidth, baseCharHeight) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (change.type != PointerType.Mouse) continue
                            hoverCell = if (event.type == PointerEventType.Exit) null else cellAt(change.position)
                        }
                    }
                }
                .pointerInput(terminalEmulator, baseCharWidth, baseCharHeight) {
                val touchSlopSquared =
""")
    text = replace(text, "                        val isFinger = down.type == PointerType.Touch\n", "                        val isFinger = down.type == PointerType.Touch\n                        val downLink = if (down.type == PointerType.Mouse) cellAt(down.position)?.let(::linkAtCell) else null\n")
    text = replace(text, """                                // Otherwise, check for hyperlink or forward the tap.
                                if (selectionManager.mode != SelectionMode.NONE) {
                                    selectionManager.clearSelection()
                                } else {
                                    // Check if tap is on a hyperlink
                                    val tapCol = (down.position.x / baseCharWidth).toInt()
                                        .coerceIn(0, screenState.snapshot.cols - 1)
                                    val tapRow = (down.position.y / baseCharHeight).toInt()
                                        .coerceIn(0, screenState.snapshot.rows - 1)
                                    val hyperlinkUrl = screenState.getHyperlinkUrlAt(
                                        tapRow,
                                        textPaint.logicalColumn(screenState, tapRow, tapCol, baseCharWidth),
                                        terminalEmulator.autoDetectUrls,
                                    )

                                    if (hyperlinkUrl != null) {
                                        // User tapped on a hyperlink
                                        currentOnHyperlinkClick(hyperlinkUrl)
                                    }
                                }
                                // Every tap asks for the focus: one that cleared a selection or opened a link too.
""", """                                if (selectionManager.mode != SelectionMode.NONE) {
                                    selectionManager.clearSelection()
                                }
                                // A link opens for a mouse click that went down and came up on it, and never for a finger: the page's
                                // touchend handler answers every tap itself, so the browser sends no click after it.
                                linkToOpen(down.type == PointerType.Mouse, downLink, cellAt(autoScrollPointer)?.let(::linkAtCell))?.let { link ->
                                    if (link.osc8) currentOnOsc8HyperlinkClick(link.url) else currentOnHyperlinkClick(link.url)
                                }
                                // Every tap asks for the focus, one that cleared a selection too.
""")
    # The hovered link's cells reach drawLine as its mask, as the detected ones did.
    text = replace(text, "    autoDetectUrls: Boolean,\n    selectionManager: SelectionManager,\n", "    autoDetectUrls: Boolean,\n    hoverLink: TerminalLink?,\n    selectionManager: SelectionManager,\n")
    text = replace(text, "                        autoDetectUrls = terminalEmulator.autoDetectUrls,\n                        selectionManager = selectionManager,\n", "                        autoDetectUrls = terminalEmulator.autoDetectUrls,\n                        hoverLink = hoverLink,\n                        selectionManager = selectionManager,\n")
    text = replace(text, """    // Cross-row URL detection depends on visible contents, not cursor/sequence updates.
    val hyperlinkMasks = remember(screenState, autoDetectUrls) {
        derivedStateOf {
            if (!autoDetectUrls) {
                emptyList()
            } else {
                List(screenState.snapshot.rows) { row ->
                    BooleanArray(screenState.snapshot.cols) { col -> screenState.getHyperlinkUrlAt(row, col, autoDetectUrls = true) != null }
                }
            }
        }
    }
""", """    // The cells xterm underlines while a link is hovered, row by row; none without a mouse over a link.
    val hyperlinkMasks = remember(screenState, hoverLink) {
        derivedStateOf {
            val link = hoverLink
            if (link == null) {
                emptyList<BooleanArray?>()
            } else {
                val cols = screenState.snapshot.cols
                val spans = hoverSpans(link, cols, screenState.snapshot.rows, screenState.visibleLineIndex(0)).associateBy { it.row }
                List(screenState.snapshot.rows) { row -> spans[row]?.let { span -> BooleanArray(cols) { it >= span.start && it < span.end } } }
            }
        }
    }
""")
    text = replace(text, "    var paintedStyle = -1\n    for (col in 0 until cells.size) {\n", "    var paintedStyle = -1\n    val oscRuns = line.semanticSegments.filter { it.semanticType == SemanticType.HYPERLINK && it.metadata != null && it.startCol < it.endCol }\n    for (col in 0 until cells.size) {\n")
    text = replace(text, "        val hyperlink = hyperlinkMask?.getOrNull(col) ?: (line.getHyperlinkUrlAt(col, autoDetectUrls) != null)\n", "        val hovered = hyperlinkMask?.getOrNull(col) == true\n")
    text = replace(text, """        if (underline != 0 || hyperlink || flags and 128 != 0) underlinePaint.color = fg.toArgb()
        if (underline == 1 || hyperlink) {
            canvas.drawLine(x, y + charBaseline + 2f, x + cellWidth, y + charBaseline + 2f, underlinePaint)
        }
        if (flags and 128 != 0) {
            val strikeY = y + charBaseline + textPaint.fontMetrics.ascent * 0.35f
            canvas.drawLine(x, strikeY, x + cellWidth, strikeY, underlinePaint)
        }
        if (underline == 2) {
            drawDoubleUnderline(x, y + charBaseline, cellWidth, fg, underlinePaint)
        }
        if (underline == 3) {
            drawCurlyUnderline(x, y + charBaseline, cellWidth, charWidth, fg, underlinePaint)
        }
""", """        val osc8 = if (hovered) null else oscRuns.firstOrNull { it.contains(col) }
        if (underline != 0 || hovered || osc8 != null || flags and 128 != 0) underlinePaint.color = fg.toArgb()
        if (hovered) {
            // xterm's DOM renderer gives a hovered link's cells `text-decoration: underline`, over whatever they had: as thick and as
            // far under the baseline as Chrome draws it (underlineThickness), where the cell's own underline is a hairline.
            val linkThickness = underlineThickness(textPaint.textSize)
            val linkY = underlineCenter(y + charBaseline, linkThickness)
            underlinePaint.strokeWidth = linkThickness
            canvas.drawLine(x, linkY, x + cellWidth, linkY, underlinePaint)
            underlinePaint.strokeWidth = 1f
        } else {
            if (osc8 != null) {
                // An OSC 8 cell is dashed whatever underline it asked for (`.xterm-underline-5`), and a link is one line of dashes.
                if (col == osc8.startCol) {
                    val last = minOf(osc8.endCol, cells.size) - 1
                    val from = (shaped?.visualColumn(osc8.startCol) ?: osc8.startCol) * charWidth
                    val to = ((shaped?.visualColumn(last) ?: last) + cells.width(last).coerceAtLeast(1)) * charWidth
                    val linkThickness = underlineThickness(textPaint.textSize)
                    drawDashedUnderline(canvas, underlinePaint, minOf(from, to), maxOf(from, to), underlineCenter(y + charBaseline, linkThickness), linkThickness)
                }
            } else if (underline == 1) {
                canvas.drawLine(x, y + charBaseline + 2f, x + cellWidth, y + charBaseline + 2f, underlinePaint)
            }
            if (flags and 128 != 0) {
                val strikeY = y + charBaseline + textPaint.fontMetrics.ascent * 0.35f
                canvas.drawLine(x, strikeY, x + cellWidth, strikeY, underlinePaint)
            }
            if (osc8 == null && underline == 2) {
                drawDoubleUnderline(x, y + charBaseline, cellWidth, fg, underlinePaint)
            }
            if (osc8 == null && underline == 3) {
                drawCurlyUnderline(x, y + charBaseline, cellWidth, charWidth, fg, underlinePaint)
            }
        }
""")
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


def screen_transfer(text):
    return replace(text, "    fun fetch(native: TerminalNative, ranges: IntArray,", "    fun fetch(native: ScreenNative, ranges: IntArray,")


def terminal_line(text):
    # The library clears every row's wrap flag when the screen is resized. The flag the emulator carried across is told apart from one the library reads,
    # so that the row keeps it until the row is rewritten (see ResizedRows and updateLine).
    text = replace(text, "    val softWrapped: Boolean = false,\n    val images: List<ImageSlice> = emptyList(),\n) {\n",
                   "    val softWrapped: Boolean = false,\n"
                   "    val images: List<ImageSlice> = emptyList(),\n"
                   "    /**\n"
                   "     * True if [softWrapped] was carried over a resize and is not what the library holds: libvterm clears the continuation flag of every\n"
                   "     * row when the screen is resized, so a line that wrapped would read as two. The flag stays until a write rewrites the row.\n"
                   "     */\n"
                   "    val wrapKept: Boolean = false,\n"
                   ") {\n")
    # Three `by lazy` on every row are a lazy object and a lambda each: 120 B a row, which is as much as the row's cells now cost (a row of
    # `seq` output is 310 B with them, 190 without). A read that has been made is kept in a field of the row instead; two threads that read
    # at once both compute the same value, and either's is kept.
    text = replace(text, "    val text: String by lazy {\n        cells.text()\n    }\n",
                   "    val text: String get() = textCache ?: cells.text().also { textCache = it }\n"
                   "    private var textCache: String? = null\n")
    text = replace(text, "    internal val columnText: String by lazy {\n        cells.columnText()\n    }\n",
                   "    internal val columnText: String get() = columnTextCache ?: cells.columnText().also { columnTextCache = it }\n"
                   "    private var columnTextCache: String? = null\n")
    return replace(text, "    internal val autoDetectedUrls: List<Triple<Int, Int, String>> by lazy {\n"
                   "        if (cells.isEmpty()) return@lazy emptyList()\n"
                   "        URL_REGEX.findAll(columnText).mapNotNull { match ->\n",
                   "    internal val autoDetectedUrls: List<Triple<Int, Int, String>> get() = urlsCache ?: detectUrls().also { urlsCache = it }\n"
                   "    private var urlsCache: List<Triple<Int, Int, String>>? = null\n\n"
                   "    private fun detectUrls(): List<Triple<Int, Int, String>> {\n"
                   "        if (cells.isEmpty()) return emptyList()\n"
                   "        return URL_REGEX.findAll(columnText).mapNotNull { match ->\n")


# Upstream files this repository replaces whole instead of patching: the same class, written in src/main. PackedCells keeps four arrays as long as
# the row is wide, which is 104 MB for one terminal's 50,000 rows of history; the owned one holds what the row has (see its own header).
REPLACED = {"PackedCells.kt"}
PATCHES = {"TerminalEmulator.kt": emulator, "SelectionManager.kt": selection, "Terminal.kt": terminal, "TerminalTextPaint.kt": text_paint, "ScreenTransfer.kt": screen_transfer, "TerminalLine.kt": terminal_line}


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
        replaced = []
        with ZipFile(sources) as archive:
            for name, data in files(archive):
                if name.endswith(".kt"):
                    filename = PurePosixPath(name).name
                    if filename in REPLACED:
                        replaced.append(filename)
                        continue
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
        if sorted(replaced) != sorted(REPLACED):
            raise RuntimeError(f"Missing replaced files: {REPLACED - set(replaced)}")
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
        write("assets/terminal-engine/PROVENANCE.json", (json.dumps({"coordinate": "org.connectbot:termlib:0.3.13", "sha256": PINS, "patched": sorted(patched), "replaced": sorted(replaced), "native": "Unmodified JNI from original AAR"}, indent=2, sort_keys=True) + "\n").encode())
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
