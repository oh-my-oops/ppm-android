package com.hienlh.ppm.app.ui.editor

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.files.FileCodeLines
import com.hienlh.ppm.app.ui.files.fileCodeDisplayLine
import com.hienlh.ppm.app.ui.files.fileCodeLanguage
import com.hienlh.ppm.app.ui.files.fileCodeReveal
import com.hienlh.ppm.client.aiResourcesReadContent
import com.hienlh.ppm.client.aiResourcesWriteContent
import com.hienlh.ppm.client.writeFile
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.HighlightedCode
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTheme
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.SyntaxHighlighter
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollbar
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.editor.EDITOR_LANGUAGE_OPTIONS
import com.hienlh.ppm.state.editor.EditorFindMatch
import com.hienlh.ppm.state.editor.EditorSessionState
import com.hienlh.ppm.state.editor.EditorTabDocumentState
import com.hienlh.ppm.state.editor.MAX_IME_SLICE_CHARS
import com.hienlh.ppm.state.editor.MOBILE_EDITOR_SYMBOLS
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Custom Android [View] providing a bounded, surrogate-safe [InputConnection] for [EditorSessionState].
 * Never returns more than [MAX_IME_SLICE_CHARS] UTF-16 code units across Binder IPC.
 */
internal class EditorImeBridgeView(context: Context) : View(context) {
    var session: EditorSessionState? = null
    var onMutated: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = session?.readOnly == false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val currentSession = session ?: return null
        if (currentSession.readOnly) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = currentSession.selectionStart
        outAttrs.initialSelEnd = currentSession.selectionEnd

        return object : BaseInputConnection(this, true) {
            override fun beginBatchEdit(): Boolean = currentSession.beginBatchEdit()
            override fun endBatchEdit(): Boolean {
                val res = currentSession.endBatchEdit()
                onMutated?.invoke()
                syncImmSelection()
                return res
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val ok = currentSession.commitText(text ?: "", newCursorPosition)
                onMutated?.invoke()
                syncImmSelection()
                return ok
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val ok = currentSession.setComposingText(text ?: "", newCursorPosition)
                onMutated?.invoke()
                syncImmSelection()
                return ok
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                val ok = currentSession.setComposingRegion(start, end)
                onMutated?.invoke()
                syncImmSelection()
                return ok
            }

            override fun finishComposingText(): Boolean {
                val ok = currentSession.finishComposingText()
                onMutated?.invoke()
                syncImmSelection()
                return ok
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val ok = currentSession.deleteSurroundingText(beforeLength, afterLength)
                onMutated?.invoke()
                syncImmSelection()
                return ok
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                val ok = currentSession.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                onMutated?.invoke()
                syncImmSelection()
                return ok
            }

            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
                currentSession.getTextBeforeCursor(n.coerceAtMost(MAX_IME_SLICE_CHARS))

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
                currentSession.getTextAfterCursor(n.coerceAtMost(MAX_IME_SLICE_CHARS))

            override fun getSelectedText(flags: Int): CharSequence? =
                currentSession.getSelectedText()

            override fun setSelection(start: Int, end: Int): Boolean {
                currentSession.setSelection(start, end)
                onMutated?.invoke()
                syncImmSelection()
                return true
            }

            override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
                if (event.action == AndroidKeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        AndroidKeyEvent.KEYCODE_DEL -> {
                            if (currentSession.selectionMin < currentSession.selectionMax) {
                                currentSession.commitText("", 1)
                            } else {
                                currentSession.deleteSurroundingTextInCodePoints(1, 0)
                            }
                            onMutated?.invoke()
                            syncImmSelection()
                            return true
                        }
                        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            currentSession.insertNewlineWithIndent()
                            onMutated?.invoke()
                            syncImmSelection()
                            return true
                        }
                        AndroidKeyEvent.KEYCODE_TAB -> {
                            currentSession.indentSelection()
                            onMutated?.invoke()
                            syncImmSelection()
                            return true
                        }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    fun requestSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun syncImmSelection() {
        val currentSession = session ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.updateSelection(
            this,
            currentSession.selectionStart,
            currentSession.selectionEnd,
            currentSession.composingStart,
            currentSession.composingEnd,
        )
    }
}

private class HighlightedEditorCache(val code: HighlightedCode, val rows: List<AnnotatedString>)

/**
 * Editable file code view (Phase 07a + 07b) with:
 * - Piece-table document buffer + bounded `InputConnection`
 * - 1000 ms debounced autosave + immediate Ctrl+S save + flush on dispose
 * - `file:changed` clean reload and dirty conflict protection banner
 * - Find & Replace bar (`$1`..`$9` regex capture groups), Go to Line bar, language selector
 * - 44dp mobile symbol & action bar
 */
@Composable
internal fun EditableFileCodeView(
    model: AppModel,
    project: String?,
    path: String?,
    initialContent: String,
    initialLanguage: String,
    wrap: Boolean,
    onToggleWrap: () -> Unit,
    lineNumber: Long? = null,
    endLine: Long? = null,
    revealAt: String? = null,
    isUntitled: Boolean = false,
    readOnly: Boolean = false,
    onDirtyChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val docState = remember(path, project) {
        EditorTabDocumentState(
            initialContent = initialContent,
            language = initialLanguage,
            isUntitled = isUntitled,
            readOnly = readOnly,
        )
    }
    var editTick by remember { mutableLongStateOf(0L) }
    var conflictVisible by remember { mutableStateOf(false) }
    var latestDiskContent by remember { mutableStateOf(initialContent) }

    // React to incoming server content (`file:changed` or manual refresh)
    LaunchedEffect(initialContent) {
        latestDiskContent = initialContent
        val reloaded = docState.onExternalDiskContent(initialContent)
        conflictVisible = docState.externalChangeConflict
        if (reloaded) {
            editTick++
            onDirtyChange(docState.isDirty)
        }
    }

    suspend fun performSaveNow() {
        val client = api ?: return
        val targetPath = path?.takeIf { it.isNotBlank() } ?: return
        if (docState.session.readOnly || isUntitled || !docState.isDirty) return
        val textToSave = docState.session.buffer.toString()
        docState.saving = true
        runCatching {
            client.writeFile(project, targetPath, textToSave)
        }.onSuccess {
            docState.markSaved(textToSave)
            conflictVisible = false
            onDirtyChange(docState.isDirty)
            editTick++
        }.onFailure { err ->
            docState.saving = false
            docState.saveError = err.message ?: "Failed to save file"
            editTick++
        }
    }

    // 1000 ms debounced autosave after edits
    LaunchedEffect(editTick, api, path, project) {
        onDirtyChange(docState.isDirty)
        if (!docState.isDirty || docState.session.readOnly || isUntitled || path.isNullOrBlank()) return@LaunchedEffect
        delay(1_000L)
        performSaveNow()
    }

    // Flush pending unsaved changes when closing tab / disposing (fixes web data-loss bug)
    DisposableEffect(docState, api, path, project) {
        onDispose {
            if (docState.isDirty && !docState.session.readOnly && !isUntitled && !path.isNullOrBlank() && api != null) {
                val textToSave = docState.session.buffer.toString()
                model.scope.launch {
                    runCatching { api.writeFile(project, path, textToSave) }
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (conflictVisible) {
            val tokens = LocalPpmTokens.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.warning.opacity(0.15).color)
                    .cssBorder(tokens.warning.color, sides = CssSides.Bottom)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("editor-disk-conflict-banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "File changed on disk while you have unsaved edits.",
                    style = ppmText(11f, FontWeight.Medium).copy(color = tokens.warning.color),
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Discard & Reload",
                        onClick = {
                            docState.session.reloadExternalContent(latestDiskContent)
                            docState.markSaved(latestDiskContent)
                            conflictVisible = false
                            onDirtyChange(false)
                            editTick++
                        },
                    )
                    SettingsButton(
                        label = "Overwrite Disk",
                        primary = true,
                        onClick = { scope.launch { performSaveNow() } },
                    )
                }
            }
        }

        if (!docState.saveError.isNullOrBlank()) {
            val tokens = LocalPpmTokens.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.14).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("editor-save-error-banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    docState.saveError.orEmpty(),
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.weight(1f),
                )
                SettingsButton(
                    label = "Retry Save",
                    primary = true,
                    onClick = { scope.launch { performSaveNow() } },
                )
            }
        }

        NativeCodeEditorCanvas(
            session = docState.session,
            editTick = editTick,
            onMutated = { editTick++ },
            isDirty = docState.isDirty,
            wrap = wrap,
            onToggleWrap = onToggleWrap,
            onSaveNow = { scope.launch { performSaveNow() } },
            lineNumber = lineNumber,
            endLine = endLine,
            revealAt = revealAt,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
internal fun NativeCodeEditorCanvas(
    session: EditorSessionState,
    editTick: Long,
    onMutated: () -> Unit,
    isDirty: Boolean = false,
    wrap: Boolean,
    onToggleWrap: () -> Unit,
    onSaveNow: () -> Unit = {},
    lineNumber: Long? = null,
    endLine: Long? = null,
    revealAt: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val shikiTheme = LocalPpmTheme.current.shikiTheme
    val highlighter = SyntaxHighlighter.get(context)

    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    var imeViewRef by remember { mutableStateOf<EditorImeBridgeView?>(null) }

    var showFindBar by remember { mutableStateOf(false) }
    var showGoToLineBar by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var currentLanguage by remember(session.language) { mutableStateOf(session.language) }

    // Find & Replace state
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var isRegex by remember { mutableStateOf(false) }
    var activeMatchIdx by remember { mutableIntStateOf(0) }

    val matches: List<EditorFindMatch> = remember(editTick, findQuery, caseSensitive, wholeWord, isRegex) {
        if (!showFindBar || findQuery.isEmpty()) emptyList()
        else session.findAll(findQuery, caseSensitive = caseSensitive, wholeWord = wholeWord, isRegex = isRegex)
    }

    // Go to line state
    var goToLineInput by remember { mutableStateOf("") }

    // Snapshot of lines for virtualized rendering + background Shiki highlighting
    val bufferVersion = remember(editTick) { session.buffer.version }
    val lineCount = remember(editTick, bufferVersion) { session.buffer.lineCount }
    val cursorLineCol = remember(editTick) { session.buffer.offsetToLineCol(session.selectionStart) }
    val bracketMatch = remember(editTick) { session.findMatchingBracket(session.selectionStart) }

    // Off-main-thread Shiki highlighting for files up to 200 KB (keeps 5 MB files instant)
    val highlightedState = remember(bufferVersion, currentLanguage, shikiTheme) {
        mutableStateOf<HighlightedEditorCache?>(null)
    }
    LaunchedEffect(bufferVersion, currentLanguage, shikiTheme) {
        if (session.buffer.length > 200_000) {
            highlightedState.value = null
            return@LaunchedEffect
        }
        val snapshotText = session.buffer.toString()
        val code = highlighter.cached(snapshotText, currentLanguage, shikiTheme)
            ?: highlighter.highlight(snapshotText, currentLanguage, shikiTheme)
        highlightedState.value = code?.let {
            withContext(Dispatchers.Default) {
                val lines = FileCodeLines(snapshotText)
                lines.tokenRows(it.text)?.let { rows -> HighlightedEditorCache(it, rows) }
            }
        }
    }
    val highlighted = highlightedState.value

    // Reveal requested line range (`lineNumber` / `endLine` / `revealAt`)
    val targetRange = remember(lineNumber, endLine, lineCount) {
        fileCodeReveal(lineNumber, endLine, lineCount)
    }
    LaunchedEffect(targetRange, revealAt) {
        val range = targetRange ?: return@LaunchedEffect
        session.goToLine(range.first + 1, 1)
        onMutated()
        val viewport = snapshotFlow {
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        }.first { it > 0 }
        val rowHeight = with(density) { 20.sp.toPx() }
        listState.scrollToItem(range.first, -((viewport - rowHeight).coerceAtLeast(0f) / 2).toInt())
    }

    Column(
        modifier
            .fillMaxSize()
            .background(highlighted?.code?.background ?: colors.bgSolid.color)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = ev.isCtrlPressed || ev.isMetaPressed
                when {
                    mod && ev.key == Key.S -> {
                        onSaveNow()
                        true
                    }
                    mod && ev.key == Key.F -> {
                        showFindBar = !showFindBar
                        true
                    }
                    mod && ev.key == Key.G -> {
                        showGoToLineBar = !showGoToLineBar
                        true
                    }
                    mod && ev.key == Key.Slash -> {
                        if (session.toggleLineComment()) onMutated()
                        true
                    }
                    mod && !ev.isShiftPressed && ev.key == Key.Z -> {
                        if (session.undo()) onMutated()
                        true
                    }
                    (mod && ev.isShiftPressed && ev.key == Key.Z) || (mod && ev.key == Key.Y) -> {
                        if (session.redo()) onMutated()
                        true
                    }
                    mod && ev.key == Key.A -> {
                        session.selectAll()
                        onMutated()
                        true
                    }
                    ev.isAltPressed && ev.key == Key.Z -> {
                        onToggleWrap()
                        true
                    }
                    ev.key == Key.Tab -> {
                        val changed = if (ev.isShiftPressed) session.outdentSelection() else session.indentSelection()
                        if (changed) onMutated()
                        true
                    }
                    ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                        if (session.insertNewlineWithIndent()) onMutated()
                        true
                    }
                    ev.key == Key.Backspace -> {
                        if (session.selectionMin < session.selectionMax) {
                            session.commitText("", 1)
                        } else {
                            session.deleteSurroundingTextInCodePoints(1, 0)
                        }
                        onMutated()
                        true
                    }
                    ev.key == Key.Delete -> {
                        if (session.selectionMin < session.selectionMax) {
                            session.commitText("", 1)
                        } else {
                            session.deleteSurroundingTextInCodePoints(0, 1)
                        }
                        onMutated()
                        true
                    }
                    ev.key == Key.DirectionLeft -> {
                        val next = session.clampSurrogateStart((session.selectionStart - 1).coerceAtLeast(0))
                        session.setSelection(next, if (ev.isShiftPressed) session.selectionEnd else next)
                        onMutated()
                        true
                    }
                    ev.key == Key.DirectionRight -> {
                        val next = session.clampSurrogateEnd((session.selectionStart + 1).coerceAtMost(session.buffer.length))
                        session.setSelection(next, if (ev.isShiftPressed) session.selectionEnd else next)
                        onMutated()
                        true
                    }
                    ev.key == Key.DirectionUp -> {
                        val pos = session.buffer.offsetToLineCol(session.selectionStart)
                        if (pos.line > 0) {
                            val next = session.buffer.lineColToOffset(pos.line - 1, pos.column)
                            session.setSelection(next, if (ev.isShiftPressed) session.selectionEnd else next)
                            onMutated()
                        }
                        true
                    }
                    ev.key == Key.DirectionDown -> {
                        val pos = session.buffer.offsetToLineCol(session.selectionStart)
                        if (pos.line + 1 < session.buffer.lineCount) {
                            val next = session.buffer.lineColToOffset(pos.line + 1, pos.column)
                            session.setSelection(next, if (ev.isShiftPressed) session.selectionEnd else next)
                            onMutated()
                        }
                        true
                    }
                    !mod && !ev.isAltPressed && ev.utf16CodePoint >= 32 -> {
                        val ch = ev.utf16CodePoint.toChar()
                        if (session.typeChar(ch)) {
                            onMutated()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        // Hidden 1px AndroidView hosting the custom InputConnection for soft keyboards (Gboard Telex, Samsung, etc.)
        AndroidView(
            factory = { ctx ->
                EditorImeBridgeView(ctx).apply {
                    this.session = session
                    this.onMutated = onMutated
                    imeViewRef = this
                }
            },
            update = { view ->
                view.session = session
                view.onMutated = onMutated
                imeViewRef = view
            },
            modifier = Modifier.size(1.dp),
        )

        // Editor Action Strip (status, cursor Ln/Col, Find, Go to Line, Language, Save)
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.panel.color)
                .cssBorder(colors.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "Ln ${cursorLineCol.line + 1}, Col ${cursorLineCol.column + 1}" + (if (isDirty) " ●" else ""),
                    style = ppmText(11f, kind = FontKind.MONO).copy(
                        color = if (isDirty) colors.accent.color else colors.text2.color,
                    ),
                    modifier = Modifier.testTag("editor-cursor-status"),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.bgSolid.color)
                        .cssBorder(colors.border.color, RoundedCornerShape(4.dp))
                        .clickable { showLanguageMenu = !showLanguageMenu }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("editor-language-chip"),
                ) {
                    BasicText(
                        currentLanguage,
                        style = ppmText(10f, kind = FontKind.MONO).copy(color = colors.text2.color),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton(
                    label = "Find",
                    modifier = Modifier.testTag("editor-toggle-find"),
                    onClick = { showFindBar = !showFindBar },
                )
                SettingsButton(
                    label = "Go to Line",
                    modifier = Modifier.testTag("editor-toggle-goto"),
                    onClick = { showGoToLineBar = !showGoToLineBar },
                )
                if (!session.readOnly) {
                    SettingsButton(
                        label = "Undo",
                        enabled = session.canUndo,
                        modifier = Modifier.testTag("editor-undo-btn"),
                        onClick = {
                            if (session.undo()) onMutated()
                        },
                    )
                    SettingsButton(
                        label = "Redo",
                        enabled = session.canRedo,
                        modifier = Modifier.testTag("editor-redo-btn"),
                        onClick = {
                            if (session.redo()) onMutated()
                        },
                    )
                    if (isDirty) {
                        SettingsButton(
                            label = "Save",
                            primary = true,
                            modifier = Modifier.testTag("editor-save-btn"),
                            onClick = onSaveNow,
                        )
                    }
                }
            }
        }

        if (showLanguageMenu) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel2.color)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((langId, label) in EDITOR_LANGUAGE_OPTIONS) {
                    SettingsButton(
                        label = label,
                        primary = currentLanguage == langId,
                        modifier = Modifier.testTag("editor-lang-opt-$langId"),
                        onClick = {
                            currentLanguage = langId
                            session.language = langId
                            showLanguageMenu = false
                            onMutated()
                        },
                    )
                }
            }
        }

        // Find & Replace Bar
        if (showFindBar) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel.color)
                    .cssBorder(colors.border.color, sides = CssSides.Bottom)
                    .padding(8.dp)
                    .testTag("editor-find-bar"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsTextInput(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            activeMatchIdx = 0
                        },
                        placeholder = "Find",
                        mono = true,
                        modifier = Modifier.weight(1f).testTag("editor-find-input"),
                    )
                    SettingsButton(
                        label = "Aa",
                        primary = caseSensitive,
                        modifier = Modifier.testTag("editor-find-case"),
                        onClick = { caseSensitive = !caseSensitive },
                    )
                    SettingsButton(
                        label = "W",
                        primary = wholeWord,
                        modifier = Modifier.testTag("editor-find-word"),
                        onClick = { wholeWord = !wholeWord },
                    )
                    SettingsButton(
                        label = ".*",
                        primary = isRegex,
                        modifier = Modifier.testTag("editor-find-regex"),
                        onClick = { isRegex = !isRegex },
                    )
                    BasicText(
                        if (matches.isEmpty()) "0/0" else "${(activeMatchIdx % matches.size) + 1}/${matches.size}",
                        style = ppmText(11f, kind = FontKind.MONO).copy(color = colors.text3.color),
                        modifier = Modifier.testTag("editor-find-count"),
                    )
                }
                if (!session.readOnly) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsTextInput(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            placeholder = "Replace ($1..$9 supported in regex)",
                            mono = true,
                            modifier = Modifier.weight(1f).testTag("editor-replace-input"),
                        )
                        SettingsButton(
                            label = "Replace",
                            enabled = matches.isNotEmpty(),
                            modifier = Modifier.testTag("editor-replace-one"),
                            onClick = {
                                val m = matches.getOrNull(activeMatchIdx % matches.size) ?: return@SettingsButton
                                if (session.replaceMatch(m, replaceQuery, isRegex = isRegex)) onMutated()
                            },
                        )
                        SettingsButton(
                            label = "Replace All",
                            primary = true,
                            enabled = matches.isNotEmpty(),
                            modifier = Modifier.testTag("editor-replace-all"),
                            onClick = {
                                if (session.replaceAll(
                                        query = findQuery,
                                        replacement = replaceQuery,
                                        caseSensitive = caseSensitive,
                                        wholeWord = wholeWord,
                                        isRegex = isRegex,
                                    ) > 0
                                ) {
                                    onMutated()
                                }
                            },
                        )
                    }
                }
            }
        }

        // Go to Line Bar
        if (showGoToLineBar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.panel.color)
                    .cssBorder(colors.border.color, sides = CssSides.Bottom)
                    .padding(8.dp)
                    .testTag("editor-goto-bar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextInput(
                    value = goToLineInput,
                    onValueChange = { goToLineInput = it },
                    placeholder = "Line[:Column] (1..$lineCount)",
                    mono = true,
                    modifier = Modifier.weight(1f).testTag("editor-goto-input"),
                )
                SettingsButton(
                    label = "Go",
                    primary = true,
                    modifier = Modifier.testTag("editor-goto-submit"),
                    onClick = {
                        val parts = goToLineInput.trim().split(":")
                        val l = parts.getOrNull(0)?.toIntOrNull() ?: return@SettingsButton
                        val c = parts.getOrNull(1)?.toIntOrNull() ?: 1
                        session.goToLine(l, c)
                        showGoToLineBar = false
                        onMutated()
                    },
                )
            }
        }

        // Virtualized Code Canvas
        val style = ppmText(14f, kind = FontKind.MONO, lineHeight = 20f).copy(
            color = highlighted?.code?.foreground ?: colors.text.color,
            textDirection = TextDirection.Ltr,
        )
        val advance = with(density) { (14.sp.toPx() * PpmFonts.MONO_ADVANCE).toDp() }
        val gutterWidth = advance * maxOf(3, lineCount.toString().length) + 20.dp

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .clickable {
                    focusRequester.requestFocus()
                    imeViewRef?.requestSoftKeyboard()
                }
                .testTag("native-code-editor-canvas"),
        ) {
            val codeWidth = maxOf(0.dp, maxWidth - gutterWidth)
            val scrollWidth = maxOf(codeWidth, advance * 140 + 24.dp)

            Box(Modifier.fillMaxSize().cssScrollbar(listState)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(lineCount, contentType = { "editor-line" }) { row ->
                        val plainLine = remember(editTick, row) { session.buffer.line(row) }
                        val tokenLine = highlighted?.rows?.getOrNull(row)
                        val baseStyled = remember(plainLine, tokenLine) {
                            fileCodeDisplayLine(plainLine, tokenLine)
                        }
                        val rowStartOffset = remember(editTick, row) { session.buffer.lineStart(row) }
                        val rowEndOffset = rowStartOffset + plainLine.length

                        val decoratedLine = remember(
                            baseStyled,
                            rowStartOffset,
                            rowEndOffset,
                            session.selectionStart,
                            session.selectionEnd,
                            session.composingStart,
                            session.composingEnd,
                            bracketMatch,
                        ) {
                            decorateEditorLine(
                                base = baseStyled,
                                rowStart = rowStartOffset,
                                rowEnd = rowEndOffset,
                                selMin = session.selectionMin,
                                selMax = session.selectionMax,
                                compStart = session.composingStart,
                                compEnd = session.composingEnd,
                                bracketMatch = bracketMatch,
                                selectionBg = colors.accent.color.copy(alpha = 0.28f),
                                bracketBg = colors.accent.color.copy(alpha = 0.22f),
                            )
                        }

                        val isCursorRow = cursorLineCol.line == row
                        val isRevealedRow = targetRange?.contains(row) == true

                        Row(
                            Modifier
                                .heightIn(min = 20.dp)
                                .background(
                                    when {
                                        isRevealedRow -> colors.accent.color.copy(alpha = 0.14f)
                                        isCursorRow -> colors.panel2.opacity(0.45).color
                                        else -> Color.Transparent
                                    },
                                )
                                .clickable {
                                    session.setSelection(rowEndOffset, rowEndOffset)
                                    onMutated()
                                    focusRequester.requestFocus()
                                    imeViewRef?.requestSoftKeyboard()
                                },
                        ) {
                            BasicText(
                                "${row + 1}",
                                Modifier.width(gutterWidth).padding(end = 12.dp),
                                style = style.copy(
                                    color = if (isCursorRow) colors.text.color else colors.text3.color,
                                    textAlign = TextAlign.End,
                                ),
                                softWrap = false,
                            )
                            if (wrap) {
                                BasicText(
                                    decoratedLine,
                                    Modifier.width(codeWidth).padding(end = 12.dp),
                                    style = style,
                                )
                            } else {
                                Box(Modifier.width(codeWidth).horizontalScroll(horizontalScroll)) {
                                    BasicText(
                                        decoratedLine,
                                        Modifier.width(scrollWidth).padding(end = 12.dp),
                                        style = style,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!wrap) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .chromeScrollbar(horizontalScroll, vertical = false, color = colors.border.color),
                    )
                }
            }
        }

        // Mobile 44dp Soft-Keyboard Key Row (Phase 07b.3)
        if (mobile && !session.readOnly) {
            MobileEditorSymbolBar(
                session = session,
                wrap = wrap,
                onToggleWrap = onToggleWrap,
                onMutated = onMutated,
            )
        }
    }
}

private fun decorateEditorLine(
    base: AnnotatedString,
    rowStart: Int,
    rowEnd: Int,
    selMin: Int,
    selMax: Int,
    compStart: Int,
    compEnd: Int,
    bracketMatch: com.hienlh.ppm.state.editor.BracketMatch?,
    selectionBg: Color,
    bracketBg: Color,
): AnnotatedString {
    val len = base.text.length
    val hasSelOverlap = selMin < selMax && selMax > rowStart && selMin < rowEnd
    val hasCompOverlap = compStart in 0 until compEnd && compEnd > rowStart && compStart < rowEnd
    val hasBracket = bracketMatch != null &&
        (bracketMatch.openOffset in rowStart until rowEnd || bracketMatch.closeOffset in rowStart until rowEnd)
    if (!hasSelOverlap && !hasCompOverlap && !hasBracket) return base

    return buildAnnotatedString {
        append(base)
        if (hasSelOverlap) {
            val s = (selMin - rowStart).coerceIn(0, len)
            val e = (selMax - rowStart).coerceIn(s, len)
            if (s < e) addStyle(SpanStyle(background = selectionBg), s, e)
        }
        if (hasCompOverlap) {
            val s = (compStart - rowStart).coerceIn(0, len)
            val e = (compEnd - rowStart).coerceIn(s, len)
            if (s < e) addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
        }
        if (bracketMatch != null) {
            for (bOffset in listOf(bracketMatch.openOffset, bracketMatch.closeOffset)) {
                if (bOffset in rowStart until rowEnd) {
                    val idx = (bOffset - rowStart).coerceIn(0, (len - 1).coerceAtLeast(0))
                    if (idx < len) addStyle(SpanStyle(background = bracketBg, fontWeight = FontWeight.Bold), idx, idx + 1)
                }
            }
        }
    }
}

/**
 * 44dp-tall mobile soft-keyboard bar with Paste, Undo, Redo, Tab, Wrap, and the 18 code symbols (`phase-07b.3`).
 */
@Composable
internal fun MobileEditorSymbolBar(
    session: EditorSessionState,
    wrap: Boolean,
    onToggleWrap: () -> Unit,
    onMutated: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp)
            .testTag("mobile-editor-symbol-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MobileKeyButton("Paste", testTag = "mobile-key-paste") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!clip.isNullOrEmpty() && session.commitText(clip, 1)) {
                onMutated()
            }
        }
        MobileKeyButton("Undo", enabled = session.canUndo, testTag = "mobile-key-undo") {
            if (session.undo()) onMutated()
        }
        MobileKeyButton("Redo", enabled = session.canRedo, testTag = "mobile-key-redo") {
            if (session.redo()) onMutated()
        }
        MobileKeyButton("Tab", testTag = "mobile-key-tab") {
            if (session.indentSelection()) onMutated()
        }
        MobileKeyButton(if (wrap) "Unwrap" else "Wrap", testTag = "mobile-key-wrap", onClick = onToggleWrap)

        for (sym in MOBILE_EDITOR_SYMBOLS) {
            MobileKeyButton(sym, mono = true, testTag = "mobile-key-sym-$sym") {
                if (sym.length == 1 && session.typeChar(sym[0])) {
                    onMutated()
                } else if (session.commitText(sym, 1)) {
                    onMutated()
                }
            }
        }
    }
}

@Composable
private fun MobileKeyButton(
    label: String,
    mono: Boolean = false,
    enabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(12f, if (mono) FontWeight.SemiBold else FontWeight.Medium, kind = if (mono) FontKind.MONO else FontKind.SANS).copy(
                color = if (enabled) tokens.text.color else tokens.text3.color,
            ),
        )
    }
}

/**
 * AI Resource Editor tab (`tab.type == "ai-resource"`, `phase-07b.4`):
 * Loads and saves skills, agents, commands, and MCP markdown configs via `/api/ai-resources/content`.
 */
@Composable
fun AiResourceEditorBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val scope = rememberCoroutineScope()
    val tokens = LocalPpmTokens.current
    val path = tab.metaString("filePath") ?: tab.metaString("path").orEmpty()
    val projectPath = tab.metaString("projectPath").orEmpty()

    var content by remember(path) { mutableStateOf<String?>(null) }
    var editable by remember(path) { mutableStateOf(true) }
    var wrap by remember { mutableStateOf(true) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(api, path, projectPath) {
        if (api == null || path.isBlank()) return@LaunchedEffect
        runCatching { api.aiResourcesReadContent(path, projectPath) }.getOrNull()?.let { res ->
            content = res["content"].string.orEmpty()
            editable = res["editable"]?.bool ?: true
        }
    }

    val loaded = content
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Loading AI resource…", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        return
    }

    val docState = remember(path, loaded) {
        EditorTabDocumentState(
            initialContent = loaded,
            language = fileCodeLanguage(path, "markdown"),
            readOnly = !editable,
        )
    }
    var editTick by remember { mutableLongStateOf(0L) }

    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        if (!editable) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                BasicText(
                    "Read-only bundled resource — duplicate in Settings → AI Resources to customize.",
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                docState.formatTabTitle(tab.title.ifBlank { path }),
                style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editable && dirty) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Revert",
                        onClick = {
                            docState.session.reloadExternalContent(loaded)
                            docState.markSaved(loaded)
                            dirty = false
                            editTick++
                        },
                    )
                    SettingsButton(
                        label = "Save",
                        primary = true,
                        onClick = {
                            val client = api ?: return@SettingsButton
                            val textToSave = docState.session.buffer.toString()
                            scope.launch {
                                runCatching {
                                    client.aiResourcesWriteContent(path, textToSave, projectPath)
                                }.onSuccess {
                                    content = textToSave
                                    docState.markSaved(textToSave)
                                    dirty = false
                                    editTick++
                                }
                            }
                        },
                    )
                }
            }
        }
        NativeCodeEditorCanvas(
            session = docState.session,
            editTick = editTick,
            onMutated = {
                editTick++
                dirty = docState.isDirty
            },
            isDirty = dirty,
            wrap = wrap,
            onToggleWrap = { wrap = !wrap },
            onSaveNow = {
                val client = api ?: return@NativeCodeEditorCanvas
                val textToSave = docState.session.buffer.toString()
                scope.launch {
                    runCatching { client.aiResourcesWriteContent(path, textToSave, projectPath) }
                        .onSuccess {
                            content = textToSave
                            docState.markSaved(textToSave)
                            dirty = false
                            editTick++
                        }
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
