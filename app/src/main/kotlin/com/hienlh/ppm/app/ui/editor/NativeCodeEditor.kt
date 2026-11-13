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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
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
import com.hienlh.ppm.app.TextInputFocus
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.files.FileCodeLines
import com.hienlh.ppm.app.ui.files.fileCodeDisplayLine
import com.hienlh.ppm.app.ui.files.fileCodeLanguage
import com.hienlh.ppm.app.ui.files.fileCodeReveal
import com.hienlh.ppm.client.aiResourcesReadContent
import com.hienlh.ppm.client.aiResourcesWriteContent
import com.hienlh.ppm.client.gitBlame
import com.hienlh.ppm.client.gitCommitLine
import com.hienlh.ppm.client.writeFile
import com.hienlh.ppm.state.editor.BlameLineDetailState
import com.hienlh.ppm.state.editor.BlameResultState
import com.hienlh.ppm.state.editor.isUncommittedHash
import com.hienlh.ppm.state.editor.parseBlameLineDetail
import com.hienlh.ppm.state.editor.parseBlameResult
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
import com.hienlh.ppm.design.monacoEditorShikiTheme
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
    var monitorCursorUpdates: Boolean = false
        private set
    var cursorAnchorUpdateCount: Int = 0
        private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Told when the bridge gains or loses the view focus: the editor registers as the focused field ([TextInputFocus.takeNative]). */
    var onFocusChange: ((Boolean) -> Unit)? = null

    override fun onCheckIsTextEditor(): Boolean = session?.readOnly == false

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onFocusChange?.invoke(gainFocus)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val currentSession = session ?: return null
        if (currentSession.readOnly) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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

            override fun getSurroundingText(
                beforeLength: Int,
                afterLength: Int,
                flags: Int,
            ): android.view.inputmethod.SurroundingText? {
                if (android.os.Build.VERSION.SDK_INT < 31) {
                    return super.getSurroundingText(beforeLength, afterLength, flags)
                }
                val slice = currentSession.getSurroundingTextSlice(beforeLength, afterLength)
                return android.view.inputmethod.SurroundingText(
                    slice.text,
                    slice.selectionStart,
                    slice.selectionEnd,
                    slice.offset,
                )
            }

            override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
                val immediate = (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0
                monitorCursorUpdates = (cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR) != 0
                if (immediate) {
                    sendCursorAnchorInfo()
                }
                return true
            }

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
        // Called from a tap (on the code, or a key of the key row): say so, or `Root`'s leavesTextOnTap clears the focus
        // as the tap ends and the keyboard, still up, types into nothing.
        TextInputFocus.asked()
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Only a view that still holds the focus can hide the keyboard: called just before a tap elsewhere clears it. */
    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun sendCursorAnchorInfo() {
        val currentSession = session ?: return
        cursorAnchorUpdateCount += 1
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val builder = android.view.inputmethod.CursorAnchorInfo.Builder()
            .setSelectionRange(currentSession.selectionStart, currentSession.selectionEnd)
        if (currentSession.composingStart >= 0 && currentSession.composingEnd >= currentSession.composingStart) {
            val compText = currentSession.buffer.substring(
                currentSession.composingStart,
                currentSession.composingEnd.coerceAtMost(currentSession.buffer.length),
            )
            builder.setComposingText(currentSession.composingStart, compText)
        }
        builder.setMatrix(android.graphics.Matrix())
        imm.updateCursorAnchorInfo(this, builder.build())
    }

    fun syncImmSelection() {
        val currentSession = session ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.updateSelection(
            this,
            currentSession.selectionStart,
            currentSession.selectionEnd,
            currentSession.composingStart,
            currentSession.composingEnd,
        )
        if (monitorCursorUpdates) {
            sendCursorAnchorInfo()
        }
    }
}

/**
 * Where the editor's caret row and its key row are, for the keyboard's pan ([TextInputFocus.takeNative]); the
 * editor's counterpart of the terminal's `TerminalCaret`. [layout] goes up with every placement, so a pan
 * reading either answer is asked again when the caret moves to another row or the rows move in the page.
 */
private class EditorCaret {
    private var layout by mutableIntStateOf(0)
    private var row: LayoutCoordinates? = null
    private var keys: LayoutCoordinates? = null

    fun rowPlaced(coordinates: LayoutCoordinates) {
        row = coordinates
        layout++
    }

    fun keysPlaced(coordinates: LayoutCoordinates) {
        keys = coordinates
        layout++
    }

    /** The caret's row in [page], null while it is scrolled out of the list or laid out in another window. */
    fun caretIn(page: LayoutCoordinates): Rect? = boundsIn(row, page)

    fun keysIn(page: LayoutCoordinates): Rect? = boundsIn(keys, page)

    private fun boundsIn(placed: LayoutCoordinates?, page: LayoutCoordinates): Rect? {
        @Suppress("UNUSED_EXPRESSION")
        layout
        val at = placed?.takeIf { it.isAttached && page.isAttached && it.findRootCoordinates() == page.findRootCoordinates() }
            ?: return null
        return page.localBoundingBoxOf(at, clipBounds = false)
    }
}

private class HighlightedEditorCache(val code: HighlightedCode, val rows: List<AnnotatedString>)

/**
 * What a screen drawn around an [EditableFileCodeView] (the phone editor) shows of it and asks of it: its
 * save state and cursor, and Save, Discard & Reload and Overwrite Disk. A view given one leaves its own
 * conflict and save-error banners to that screen.
 */
@Stable
class EditableFileHandle {
    var dirty by mutableStateOf(false)
        internal set
    var saving by mutableStateOf(false)
        internal set
    var saveError by mutableStateOf<String?>(null)
        internal set
    /** The file changed on disk while there were unsaved edits. */
    var conflict by mutableStateOf(false)
        internal set
    var cursorLine by mutableIntStateOf(1)
        internal set
    var cursorColumn by mutableIntStateOf(1)
        internal set
    internal var onSave: () -> Unit = {}
    internal var onDiscardAndReload: () -> Unit = {}
    internal var onOverwriteDisk: () -> Unit = {}

    fun save() = onSave()
    fun discardAndReload() = onDiscardAndReload()
    fun overwriteDisk() = onOverwriteDisk()
}

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
    useWebHeader: Boolean = false,
    onDirtyChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    handle: EditableFileHandle? = null,
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
    LaunchedEffect(initialLanguage) {
        if (docState.session.language != initialLanguage) {
            docState.session.language = initialLanguage
            editTick++
        }
    }
    var conflictVisible by remember { mutableStateOf(false) }
    var latestDiskContent by remember { mutableStateOf(initialContent) }
    var saveError by remember(path, project) { mutableStateOf<String?>(null) }
    var haltedOn4xx by remember(path, project) { mutableStateOf(false) }
    val currentBufferVersion = remember(editTick) { docState.session.buffer.version }

    // Shared per-project LSP bridge (Phase 07c)
    val lspEnabled by DeviceLspPreference.enabled.collectAsState()
    val isRelativeProjectFile = !project.isNullOrBlank() && !path.isNullOrBlank() &&
        !isUntitled && !path.startsWith("/") && !Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(path)
    val clientUri = remember(project, path) { allocateClientUri() }
    val lspBridge = remember(api, project, isRelativeProjectFile, lspEnabled) {
        if (api != null && lspEnabled && isRelativeProjectFile && project != null) {
            SharedProjectLspRegistry.getOrCreate(api, project)
        } else {
            null
        }
    }
    val lspStatus = if (lspBridge != null && path != null) {
        lspBridge.statusFlow(path).collectAsState().value
    } else {
        null
    }
    val lspDiagnostics = if (lspBridge != null && path != null) {
        lspBridge.diagnosticsFlow(path).collectAsState().value
    } else {
        emptyList()
    }
    var lspCompletions by remember(path, project) { mutableStateOf<List<com.hienlh.ppm.state.editor.LspCompletionEntry>>(emptyList()) }
    var lspHoverMarkdown by remember(path, project) { mutableStateOf<String?>(null) }
    var showCompareDialog by remember(path, project) { mutableStateOf(false) }

    val initialLspVersion = remember(lspBridge, path) { docState.session.buffer.version }
    DisposableEffect(lspBridge, path) {
        if (lspBridge != null && !path.isNullOrBlank()) {
            lspBridge.openDocument(path, clientUri, docState.session.buffer.toString())
        }
        onDispose {
            if (lspBridge != null && !path.isNullOrBlank()) {
                lspBridge.closeDocument(path)
            }
        }
    }

    // Debounced off-main-thread LSP change notifications only when buffer version actually changes
    LaunchedEffect(currentBufferVersion, lspBridge, path) {
        if (lspBridge != null && !path.isNullOrBlank() && currentBufferVersion != initialLspVersion) {
            delay(150L)
            val text = withContext(Dispatchers.Default) { docState.session.buffer.toString() }
            lspBridge.changeDocument(path, text)
        }
    }

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

    suspend fun performSaveNow(forceOverwrite: Boolean = false) {
        val client = api ?: return
        val targetPath = path?.takeIf { it.isNotBlank() } ?: return
        if (docState.session.readOnly || isUntitled || !docState.isDirty) return
        if (!forceOverwrite && (conflictVisible || docState.externalChangeConflict)) return
        val textToSave = docState.textToSave()
        docState.saving = true
        handle?.saving = true
        try {
            client.writeFile(project, targetPath, textToSave)
            docState.markSaved(textToSave)
            saveError = null
            haltedOn4xx = false
            lspBridge?.saveDocument(targetPath, textToSave)
            conflictVisible = false
            onDirtyChange(docState.isDirty)
            editTick++
        } catch (ce: kotlinx.coroutines.CancellationException) {
            docState.saving = false
            throw ce
        } catch (err: Throwable) {
            docState.saving = false
            val msg = err.message ?: "Failed to save file"
            docState.saveError = msg
            saveError = msg
            val is4xx = (err is com.hienlh.ppm.client.PpmError.Server && err.status in 400..499) ||
                err is com.hienlh.ppm.client.PpmError.Unauthorized ||
                err is IllegalArgumentException
            if (is4xx) {
                haltedOn4xx = true
            }
        } finally {
            handle?.saving = false
        }
    }

    fun discardAndReload() {
        docState.session.reloadExternalContent(latestDiskContent)
        docState.markSaved(latestDiskContent)
        conflictVisible = false
        saveError = null
        haltedOn4xx = false
        onDirtyChange(false)
        editTick++
    }

    if (handle != null) {
        SideEffect {
            handle.onSave = {
                haltedOn4xx = false
                scope.launch { performSaveNow() }
            }
            handle.onDiscardAndReload = ::discardAndReload
            handle.onOverwriteDisk = { scope.launch { performSaveNow(forceOverwrite = true) } }
            handle.dirty = docState.isDirty
            handle.conflict = conflictVisible
            handle.saveError = saveError ?: docState.saveError
            val cursor = docState.session.buffer.offsetToLineCol(docState.session.selectionStart)
            handle.cursorLine = cursor.line + 1
            handle.cursorColumn = cursor.column + 1
        }
    }

    // 1000 ms debounced autosave after buffer edits (never while external-change conflict is active or halted on 4xx)
    LaunchedEffect(currentBufferVersion, api, path, project, conflictVisible, haltedOn4xx) {
        onDirtyChange(docState.isDirty)
        if (
            !docState.isDirty ||
            docState.session.readOnly ||
            isUntitled ||
            path.isNullOrBlank() ||
            conflictVisible ||
            docState.externalChangeConflict ||
            haltedOn4xx
        ) {
            return@LaunchedEffect
        }
        delay(1_000L)
        if (conflictVisible || docState.externalChangeConflict || haltedOn4xx) return@LaunchedEffect
        performSaveNow(forceOverwrite = false)
    }

    // Flush pending unsaved changes when closing tab / disposing (fixes web data-loss bug)
    DisposableEffect(docState, api, path, project) {
        onDispose {
            if (
                docState.isDirty &&
                !docState.session.readOnly &&
                !isUntitled &&
                !docState.externalChangeConflict &&
                !path.isNullOrBlank() &&
                api != null
            ) {
                val textToSave = docState.textToSave()
                model.scope.launch {
                    try {
                        api.writeFile(project, path, textToSave)
                        docState.markSaved(textToSave)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (err: Throwable) {
                        val msg = err.message ?: "Failed to save $path"
                        docState.saveError = msg
                        model.toast(
                            com.hienlh.ppm.app.ErrorToastMessage(
                                title = "Failed to save $path",
                                description = msg,
                            ),
                        )
                    }
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        if (conflictVisible && handle == null) {
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
                        onClick = ::discardAndReload,
                    )
                    SettingsButton(
                        label = "Overwrite Disk",
                        primary = true,
                        onClick = { scope.launch { performSaveNow(forceOverwrite = true) } },
                    )
                }
            }
        }

        val currentSaveError = saveError ?: docState.saveError
        if (!currentSaveError.isNullOrBlank() && handle == null) {
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
                    currentSaveError,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.weight(1f),
                )
                SettingsButton(
                    label = "Retry Save",
                    primary = true,
                    onClick = {
                        haltedOn4xx = false
                        scope.launch { performSaveNow() }
                    },
                )
            }
        }

        // Per-device Inline Blame state (Phase 07d.1)
        val blameEnabled by SharedBlamePreference.enabled.collectAsState()
        var blameData by remember(project, path) { mutableStateOf<BlameResultState?>(null) }
        var activeLineDetail by remember { mutableStateOf<BlameLineDetailState?>(null) }
        val activeCursorLineOneBased = remember(editTick) {
            docState.session.buffer.offsetToLineCol(docState.session.selectionStart).line + 1
        }
        val activeLineBlame = remember(blameData, activeCursorLineOneBased, docState.isDirty, blameEnabled) {
            if (!blameEnabled || docState.isDirty) return@remember null
            blameData?.lineBlame(activeCursorLineOneBased)
        }
        val activeBlameCommit = activeLineBlame?.first
        val fileGitScope = remember(project, path, model.gitDiscovery[project]) {
            if (project != null && !path.isNullOrBlank()) {
                com.hienlh.ppm.state.git.GitRepoScope.fileGitScope(model.gitDiscovery[project], path)
            } else {
                null
            }
        }
        val repoFile = fileGitScope?.filePath ?: path
        val fileRepo = fileGitScope?.repo

        // Change bars in the gutter against the file in git's index, as VS Code draws them, on a
        // phone too (`use-gutter-changes.ts`): one git call per open, then a cheap check every few
        // seconds while the editor is up. Like the web, nothing is asked until the repository that
        // holds the file is known.
        val gutterChanges = rememberGutterChanges(
            model = model,
            project = project,
            repoFile = repoFile,
            fileRepo = fileRepo,
            enabled = model.info?.minimalMode != true && isRelativeProjectFile &&
                fileGitScope?.kind == com.hienlh.ppm.state.git.FileGitScopeKind.REPO,
            bufferVersion = currentBufferVersion,
            readText = { docState.session.buffer.toString() },
        )

        LaunchedEffect(api, project, repoFile, fileRepo?.path, blameEnabled, docState.isDirty) {
            if (!blameEnabled || docState.isDirty || api == null || !isRelativeProjectFile || project == null || repoFile.isNullOrBlank()) {
                if (docState.isDirty) blameData = null
                return@LaunchedEffect
            }
            delay(300L)
            runCatching { api.gitBlame(project, repoFile, repo = fileRepo) }
                .getOrNull()
                ?.let { blameData = parseBlameResult(it) }
        }

        LaunchedEffect(api, project, repoFile, fileRepo?.path, blameData, activeCursorLineOneBased, activeBlameCommit) {
            val commit = activeBlameCommit
            if (api == null || project == null || repoFile.isNullOrBlank() || commit == null || isUncommittedHash(commit.hash)) {
                activeLineDetail = null
                return@LaunchedEffect
            }
            val origLine = activeLineBlame?.second ?: activeCursorLineOneBased
            val detailPath = commit.filename?.takeIf { it.isNotBlank() } ?: repoFile
            val cacheKey = "${commit.hash}:${fileRepo?.path.orEmpty()}:$detailPath:$origLine"
            val cached = SharedBlamePreference.commitLineCache.get(cacheKey)
            if (cached != null) {
                activeLineDetail = cached
                return@LaunchedEffect
            }
            runCatching { api.gitCommitLine(project, commit.hash, detailPath, origLine, repo = fileRepo) }
                .getOrNull()
                ?.let {
                    val parsed = parseBlameLineDetail(it)
                    SharedBlamePreference.commitLineCache.put(cacheKey, parsed)
                    activeLineDetail = parsed
                }
        }

        if (blameEnabled && !docState.isDirty && activeBlameCommit != null && !path.isNullOrBlank()) {
            val resolvedProjectRoot = remember(project, model.projects, model.gitDiscovery[project], model.gitChosenRepoPath[project], fileRepo?.path) {
                val projInfo = model.projects.firstOrNull { it.name == project }
                if (projInfo != null && project != null) {
                    val subRepo = fileRepo?.path ?: com.hienlh.ppm.state.git.GitRepoScope.resolveRepo(
                        model.gitDiscovery[project],
                        model.gitChosenRepoPath[project],
                    )?.path
                    if (!subRepo.isNullOrBlank() && subRepo != ".") {
                        "${projInfo.path.trimEnd('/')}/$subRepo"
                    } else {
                        projInfo.path
                    }
                } else {
                    project
                }
            }
            InlineBlameAnnotationStrip(
                commit = activeBlameCommit,
                detail = activeLineDetail,
                filePath = repoFile ?: path,
                projectPath = resolvedProjectRoot,
                onActionCommand = { actionCmd, actionArgs ->
                    val root = actionArgs.getOrNull(0) ?: resolvedProjectRoot
                    val targetFile = actionArgs.getOrNull(1) ?: (repoFile ?: path)
                    when (actionCmd) {
                        "ppm.blame.fileHistory" -> if (!root.isNullOrBlank()) {
                            model.dispatchExtCommand(
                                "git-graph.fileHistory",
                                listOf(
                                    kotlinx.serialization.json.JsonPrimitive(root),
                                    kotlinx.serialization.json.JsonPrimitive(targetFile),
                                ),
                            )
                        }
                        "ppm.blame.blameFile" -> if (!root.isNullOrBlank()) {
                            model.dispatchExtCommand(
                                "git-graph.blame",
                                listOf(
                                    kotlinx.serialization.json.JsonPrimitive(root),
                                    kotlinx.serialization.json.JsonPrimitive(targetFile),
                                ),
                            )
                        }
                        "ppm.blame.showInGraph" -> if (!root.isNullOrBlank()) {
                            model.dispatchExtCommand(
                                "git-graph.view",
                                listOf(kotlinx.serialization.json.JsonPrimitive(root)),
                            )
                        }
                    }
                },
            )
        }

        fun applyCompletionItem(entry: com.hienlh.ppm.state.editor.LspCompletionEntry) {
            val buf = docState.session.buffer
            val (expanded, cursorOffsetInExpanded) = if (entry.isSnippet) {
                com.hienlh.ppm.state.editor.expandSnippetPlaceholders(entry.insertText)
            } else {
                entry.insertText to entry.insertText.length
            }
            val replaceRange = entry.replaceRange
            if (replaceRange != null) {
                val startOffset = com.hienlh.ppm.state.editor.lspPositionToOffset(buf, replaceRange.start)
                val endOffset = com.hienlh.ppm.state.editor.lspPositionToOffset(buf, replaceRange.end)
                docState.session.setSelection(startOffset, endOffset)
            } else {
                val wordRange = com.hienlh.ppm.state.editor.defaultCompletionReplaceOffsets(buf, docState.session.selectionStart)
                docState.session.setSelection(wordRange.first, wordRange.last)
            }
            docState.session.commitText(expanded, 1)
            val newCursor = (docState.session.selectionStart - expanded.length + cursorOffsetInExpanded)
                .coerceIn(0, buf.length)
            docState.session.setSelection(newCursor, newCursor)
            if (entry.additionalTextEdits.isNotEmpty()) {
                val updated = com.hienlh.ppm.state.editor.applyLspTextEdits(buf.toString(), entry.additionalTextEdits)
                docState.session.reloadExternalContent(updated)
            }
            lspCompletions = emptyList()
            editTick++
        }

        fun triggerLspCompletions() {
            val bridge = lspBridge ?: return
            val filePath = path?.takeIf { it.isNotBlank() } ?: return
            val pos = com.hienlh.ppm.state.editor.offsetToLspPosition(docState.session.buffer, docState.session.selectionStart)
            scope.launch {
                lspCompletions = bridge.requestCompletions(filePath, clientUri, pos.line, pos.character)
            }
        }

        fun triggerLspHover() {
            val bridge = lspBridge ?: return
            val filePath = path?.takeIf { it.isNotBlank() } ?: return
            val pos = com.hienlh.ppm.state.editor.offsetToLspPosition(docState.session.buffer, docState.session.selectionStart)
            scope.launch {
                lspHoverMarkdown = bridge.requestHover(filePath, clientUri, pos.line, pos.character)
            }
        }

        fun triggerLspDefinition() {
            val bridge = lspBridge ?: return
            val filePath = path?.takeIf { it.isNotBlank() } ?: return
            val projName = project ?: return
            val pos = com.hienlh.ppm.state.editor.offsetToLspPosition(docState.session.buffer, docState.session.selectionStart)
            val projRoot = (lspStatus as? com.hienlh.ppm.state.editor.LspDocumentStatus.Ready)?.projectPath
                ?: model.projects.firstOrNull { it.name == projName }?.path
            scope.launch {
                val targets = bridge.requestDefinition(filePath, clientUri, pos.line, pos.character)
                val firstTarget = targets.firstOrNull() ?: return@launch
                when (val act = com.hienlh.ppm.state.editor.resolveDefinitionAction(firstTarget, clientUri, projRoot, projName)) {
                    is com.hienlh.ppm.state.editor.LspNavigationAction.SameFile -> {
                        val targetOffset = com.hienlh.ppm.state.editor.lspPositionToOffset(docState.session.buffer, act.range.start)
                        docState.session.setSelection(targetOffset, targetOffset)
                        editTick++
                    }
                    is com.hienlh.ppm.state.editor.LspNavigationAction.OpenProjectFile -> {
                        model.openFileTab(act.projectName, act.relativePath, act.lineNumber.toLong())
                    }
                    com.hienlh.ppm.state.editor.LspNavigationAction.IgnoreOutsideProject -> {}
                }
            }
        }

        fun triggerLspFormatting() {
            val bridge = lspBridge ?: return
            val filePath = path?.takeIf { it.isNotBlank() } ?: return
            if (docState.session.readOnly) return
            val indent = docState.session.indentStyle
            scope.launch {
                val edits = bridge.requestFormatting(filePath, clientUri, indent.size, !indent.useTabs)
                if (edits.isNotEmpty()) {
                    val formatted = com.hienlh.ppm.state.editor.applyLspTextEdits(
                        docState.session.buffer.toString(),
                        edits,
                    )
                    docState.session.selectAll()
                    docState.session.commitText(formatted, 1)
                    editTick++
                }
            }
        }

        LspCompletionPopup(
            items = lspCompletions,
            onSelect = { applyCompletionItem(it) },
            onDismiss = { lspCompletions = emptyList() },
        )
        LspHoverPopup(
            markdown = lspHoverMarkdown,
            onDismiss = { lspHoverMarkdown = null },
        )

        if (showCompareDialog && project != null && !path.isNullOrBlank()) {
            val allTabs = model.layout?.panels?.values?.flatMap { it.tabs }.orEmpty()
            val openTabs = remember(allTabs, project, path) {
                allTabs.mapNotNull { t ->
                    val p = t.metaString("filePath") ?: t.metaString("path") ?: return@mapNotNull null
                    if (p == path) return@mapNotNull null
                    com.hienlh.ppm.state.editor.CompareCandidate(
                        id = "tab:${t.id}",
                        path = p,
                        label = p.substringAfterLast('/').substringAfterLast('\\'),
                        source = "tab",
                    )
                }
            }
            val initialSel = remember(project, path, editTick) {
                com.hienlh.ppm.state.editor.CompareSelection(
                    filePath = path,
                    projectName = project,
                    dirtyContent = if (docState.isDirty) docState.session.buffer.toString() else null,
                )
            }
            androidx.compose.ui.window.Dialog(onDismissRequest = { showCompareDialog = false }) {
                CompareFilesDialogContent(
                    initialSelectionA = SharedCompareStore.store.selection ?: initialSel,
                    candidates = com.hienlh.ppm.state.editor.buildCompareCandidates(openTabs, emptyList()),
                    onPickPair = { selA, candB ->
                        showCompareDialog = false
                        SharedCompareStore.store.clearSelection()
                        model.openWorkspaceTab(
                            type = "git-diff",
                            title = com.hienlh.ppm.state.editor.formatCompareTabTitle(selA.filePath, candB.path),
                            projectId = project,
                            metadata = kotlinx.serialization.json.JsonObject(
                                mapOf(
                                    "projectName" to kotlinx.serialization.json.JsonPrimitive(project),
                                    "file1" to kotlinx.serialization.json.JsonPrimitive(selA.filePath),
                                    "file2" to kotlinx.serialization.json.JsonPrimitive(candB.path),
                                ),
                            ),
                        )
                    },
                    onClose = { showCompareDialog = false },
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
            onRequestCompletions = if (lspBridge != null) ({ triggerLspCompletions() }) else null,
            onRequestHover = if (lspBridge != null) ({ triggerLspHover() }) else null,
            onRequestDefinition = if (lspBridge != null) ({ triggerLspDefinition() }) else null,
            onRequestFormatting = if (lspBridge != null && !docState.session.readOnly) ({ triggerLspFormatting() }) else null,
            diagnostics = lspDiagnostics,
            gutterChanges = gutterChanges,
            lineNumber = lineNumber,
            endLine = endLine,
            revealAt = revealAt,
            showActionStrip = !useWebHeader,
            lspSlot = {
                if (isRelativeProjectFile) {
                    InlineBlameToolbarChip(
                        enabled = blameEnabled,
                        onToggle = { SharedBlamePreference.setEnabled(it) },
                    )
                    SettingsButton(
                        label = "Compare",
                        modifier = Modifier.testTag("editor-compare-files-btn"),
                        onClick = { showCompareDialog = true },
                    )
                    LspStatusChip(
                        enabled = lspEnabled,
                        onToggle = { DeviceLspPreference.setEnabled(it) },
                        status = lspStatus,
                        diagnostics = lspDiagnostics,
                        onInstall = if (lspBridge != null) {
                            { serverId -> lspBridge.installServer(serverId) }
                        } else null,
                        onOpenProblems = {
                            model.openWorkspaceTab(type = "problems", title = "Problems")
                        },
                    )
                }
            },
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
    onRequestCompletions: (() -> Unit)? = null,
    onRequestHover: (() -> Unit)? = null,
    onRequestDefinition: (() -> Unit)? = null,
    onRequestFormatting: (() -> Unit)? = null,
    diagnostics: List<com.hienlh.ppm.state.editor.LspDiagnostic> = emptyList(),
    gutterChanges: List<com.hienlh.ppm.state.editor.GutterChanges.Change> = emptyList(),
    lineNumber: Long? = null,
    endLine: Long? = null,
    revealAt: String? = null,
    showActionStrip: Boolean = true,
    lspSlot: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val ppmTheme = LocalPpmTheme.current
    val shikiTheme = remember(ppmTheme.isDark, colors) { monacoEditorShikiTheme(ppmTheme.isDark, colors) }
    val highlighter = SyntaxHighlighter.get(context)

    val listState = rememberLazyListState()
    val horizontalScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    var imeViewRef by remember { mutableStateOf<EditorImeBridgeView?>(null) }
    val focusManager = LocalFocusManager.current
    val caret = remember(session) { EditorCaret() }
    DisposableEffect(session) {
        onDispose { TextInputFocus.releaseNative(session) }
    }
    // The editor is the focused field while its bridge holds the view focus, as the terminal is (`TerminalBody`): a tap
    // on nothing blurs it and hides its keyboard, and the keyboard's pan follows its caret row and key row.
    val onBridgeFocus: (Boolean) -> Unit = { focused ->
        if (focused) {
            TextInputFocus.takeNative(
                session,
                focusManager,
                onBlur = { imeViewRef?.hideSoftKeyboard() },
                keep = caret::keysIn,
                bounds = caret::caretIn,
            )
        } else {
            TextInputFocus.releaseNative(session)
        }
    }

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

    // Off-main-thread Shiki highlighting for files up to 200 KB (keeps previous tokens across edits so it never flickers)
    val highlightedState = remember(currentLanguage, shikiTheme) {
        mutableStateOf<HighlightedEditorCache?>(null)
    }
    LaunchedEffect(bufferVersion, currentLanguage, shikiTheme) {
        if (session.buffer.length > 200_000) {
            highlightedState.value = null
            return@LaunchedEffect
        }
        val snapshotText = withContext(Dispatchers.Default) { session.buffer.toString() }
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
        val rowHeight = with(density) { 19.sp.toPx() }
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
                    mod && ev.key == Key.Spacebar && onRequestCompletions != null -> {
                        onRequestCompletions()
                        true
                    }
                    ev.key == Key.F12 && onRequestDefinition != null -> {
                        onRequestDefinition()
                        true
                    }
                    ev.isShiftPressed && ev.isAltPressed && ev.key == Key.F && onRequestFormatting != null -> {
                        onRequestFormatting()
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
                    ev.isAltPressed && ev.key == Key.B -> {
                        SharedBlamePreference.setEnabled(!SharedBlamePreference.enabled.value)
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
                    this.onFocusChange = onBridgeFocus
                    imeViewRef = this
                }
            },
            update = { view ->
                view.session = session
                view.onMutated = onMutated
                view.onFocusChange = onBridgeFocus
                imeViewRef = view
            },
            modifier = Modifier.size(1.dp),
        )

        // Editor Action Strip (status, cursor Ln/Col, Find, Go to Line, Language, Save)
        if (showActionStrip) {
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
                    lspSlot()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onRequestCompletions != null) {
                        SettingsButton(
                            label = "Complete",
                            modifier = Modifier.testTag("editor-lsp-complete-btn"),
                            onClick = onRequestCompletions,
                        )
                    }
                    if (onRequestHover != null) {
                        SettingsButton(
                            label = "Hover",
                            modifier = Modifier.testTag("editor-lsp-hover-btn"),
                            onClick = onRequestHover,
                        )
                    }
                    if (onRequestDefinition != null) {
                        SettingsButton(
                            label = "Def",
                            modifier = Modifier.testTag("editor-lsp-def-btn"),
                            onClick = onRequestDefinition,
                        )
                    }
                    if (onRequestFormatting != null) {
                        SettingsButton(
                            label = "Format",
                            modifier = Modifier.testTag("editor-lsp-format-btn"),
                            onClick = onRequestFormatting,
                        )
                    }
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
        val style = ppmText(14f, kind = FontKind.MONO, lineHeight = 19f).copy(
            color = highlighted?.code?.foreground ?: colors.text.color,
            textDirection = TextDirection.Ltr,
            fontFeatureSettings = PpmFonts.EDITOR_FONT_LIGATURES,
        )
        val advance = with(density) { (14.sp.toPx() * PpmFonts.MONO_ADVANCE).toDp() }
        val gutterWidth = 36.dp + advance * maxOf(3, lineCount.toString().length) + 28.dp
        val gutterMarks = remember(gutterChanges, lineCount) {
            com.hienlh.ppm.state.editor.GutterChanges.rowMarks(gutterChanges, lineCount)
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(session) {
                    // A press on the code is a press on the focused field, which a tap-to-blur leaves focused.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) TextInputFocus.pressed(session)
                        }
                    }
                }
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
                    contentPadding = PaddingValues(vertical = 0.dp),
                ) {
                    items(lineCount, contentType = { "editor-line" }) { row ->
                        val plainLine = remember(editTick, row) { session.buffer.line(row) }
                        val tokenLine = highlighted?.rows?.getOrNull(row)
                        val baseStyled = remember(plainLine, tokenLine) {
                            fileCodeDisplayLine(plainLine, tokenLine)
                        }
                        val rowStartOffset = remember(editTick, row) { session.buffer.lineStart(row) }
                        val rowEndOffset = rowStartOffset + plainLine.length
                        val rowDiagnostics = remember(diagnostics, row) {
                            if (diagnostics.isEmpty()) emptyList()
                            else diagnostics.filter { d -> row in d.range.start.line..d.range.end.line }
                        }

                        val decoratedLine = remember(
                            baseStyled,
                            rowStartOffset,
                            rowEndOffset,
                            session.selectionStart,
                            session.selectionEnd,
                            session.composingStart,
                            session.composingEnd,
                            bracketMatch,
                            rowDiagnostics,
                        ) {
                            decorateEditorLine(
                                base = baseStyled,
                                rowIndex = row,
                                rowStart = rowStartOffset,
                                rowEnd = rowEndOffset,
                                selMin = session.selectionMin,
                                selMax = session.selectionMax,
                                compStart = session.composingStart,
                                compEnd = session.composingEnd,
                                bracketMatch = bracketMatch,
                                rowDiagnostics = rowDiagnostics,
                                selectionBg = colors.accent.color.copy(alpha = 0.28f),
                                bracketBg = colors.accent.color.copy(alpha = 0.22f),
                                errorBg = colors.error.color.copy(alpha = 0.18f),
                                warningBg = colors.warning.color.copy(alpha = 0.16f),
                            )
                        }

                        val isCursorRow = cursorLineCol.line == row
                        val isRevealedRow = targetRange?.contains(row) == true
                        val codeRowBg = when {
                            isRevealedRow -> colors.accent.color.copy(alpha = 0.14f)
                            isCursorRow -> colors.accent.opacity(0x12 / 255.0).color
                            else -> Color.Transparent
                        }

                        Row(
                            Modifier
                                .heightIn(min = 19.dp)
                                .then(if (isCursorRow) Modifier.onGloballyPositioned(caret::rowPlaced) else Modifier)
                                .clickable {
                                    session.setSelection(rowEndOffset, rowEndOffset)
                                    onMutated()
                                    focusRequester.requestFocus()
                                    imeViewRef?.requestSoftKeyboard()
                                },
                        ) {
                            BasicText(
                                "${row + 1}",
                                Modifier.width(gutterWidth).gutterChangeMark(gutterMarks[row], row + 1, 28.dp, colors).padding(end = 28.dp),
                                style = style.copy(
                                    color = if (isCursorRow) colors.text2.color else colors.text3.color,
                                    textAlign = TextAlign.End,
                                ),
                                softWrap = false,
                            )
                            if (wrap) {
                                BasicText(
                                    decoratedLine,
                                    Modifier.width(codeWidth).background(codeRowBg).padding(end = 12.dp),
                                    style = style,
                                )
                            } else {
                                Box(Modifier.width(codeWidth).background(codeRowBg).horizontalScroll(horizontalScroll)) {
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

        // Mobile Soft-Keyboard Key Row (`src/web/components/editor/editor-mobile-toolbar.tsx`)
        if (mobile && !session.readOnly) {
            MobileEditorSymbolBar(
                session = session,
                wrap = wrap,
                onToggleWrap = onToggleWrap,
                onMutated = onMutated,
                keepFocus = {
                    imeViewRef?.requestSoftKeyboard()
                    imeViewRef?.syncImmSelection()
                },
                modifier = Modifier.onGloballyPositioned(caret::keysPlaced),
            )
        }
    }
}

private fun decorateEditorLine(
    base: AnnotatedString,
    rowIndex: Int,
    rowStart: Int,
    rowEnd: Int,
    selMin: Int,
    selMax: Int,
    compStart: Int,
    compEnd: Int,
    bracketMatch: com.hienlh.ppm.state.editor.BracketMatch?,
    rowDiagnostics: List<com.hienlh.ppm.state.editor.LspDiagnostic>,
    selectionBg: Color,
    bracketBg: Color,
    errorBg: Color,
    warningBg: Color,
): AnnotatedString {
    val len = base.text.length
    val hasSelOverlap = selMin < selMax && selMax > rowStart && selMin < rowEnd
    val hasCompOverlap = compStart in 0 until compEnd && compEnd > rowStart && compStart < rowEnd
    val hasBracket = bracketMatch != null &&
        (bracketMatch.openOffset in rowStart until rowEnd || bracketMatch.closeOffset in rowStart until rowEnd)
    val hasDiag = rowDiagnostics.isNotEmpty()
    if (!hasSelOverlap && !hasCompOverlap && !hasBracket && !hasDiag) return base

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
        for (diag in rowDiagnostics) {
            if (len <= 0) break
            val rawStart = if (diag.range.start.line == rowIndex) diag.range.start.character else 0
            val rawEnd = if (diag.range.end.line == rowIndex) diag.range.end.character else len
            val s = com.hienlh.ppm.state.editor.utf16ColumnToCharOffset(base.text, rawStart).coerceIn(0, len)
            val e = com.hienlh.ppm.state.editor.utf16ColumnToCharOffset(base.text, rawEnd)
                .coerceIn((s + 1).coerceAtMost(len), len)
            if (s < e) {
                val bg = if (diag.effectiveSeverity == com.hienlh.ppm.state.editor.SEVERITY_ERROR) errorBg else warningBg
                addStyle(
                    SpanStyle(
                        background = bg,
                        textDecoration = TextDecoration.Underline,
                    ),
                    s,
                    e,
                )
            }
        }
    }
}

/**
 * Mobile soft-keyboard bar (`src/web/components/editor/editor-mobile-toolbar.tsx`):
 * Paste, Undo, Redo, Wrap icon buttons, Tab, and the 18 code symbols.
 */
@Composable
internal fun MobileEditorSymbolBar(
    session: EditorSessionState,
    wrap: Boolean,
    onToggleWrap: () -> Unit,
    onMutated: () -> Unit,
    keepFocus: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current

    // The web's toolbar keys keep the editor focused (and its keyboard up), as the terminal's key row does: each key
    // ends the IME's composition first, so a Telex word in progress is not replaced by the key's text, then asks
    // for the focus back before `Root`'s leavesTextOnTap would clear it.
    fun key(action: () -> Unit): () -> Unit = {
        session.finishComposingText()
        action()
        keepFocus()
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Top)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("mobile-editor-symbol-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MobileKeyIconButton(ProductIcon.ClipboardPaste, testTag = "mobile-key-paste", onClick = key {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!clip.isNullOrEmpty() && session.commitText(clip, 1)) {
                onMutated()
            }
        })
        MobileKeyIconButton(ProductIcon.Undo2, enabled = session.canUndo, testTag = "mobile-key-undo", onClick = key {
            if (session.undo()) onMutated()
        })
        MobileKeyIconButton(ProductIcon.Redo2, enabled = session.canRedo, testTag = "mobile-key-redo", onClick = key {
            if (session.redo()) onMutated()
        })
        MobileKeyIconButton(ProductIcon.WrapText, active = wrap, testTag = "mobile-key-wrap", onClick = key(onToggleWrap))

        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(tokens.border.color))

        MobileKeyButton("Tab", mono = true, testTag = "mobile-key-tab", onClick = key {
            if (session.indentSelection()) onMutated()
        })

        Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(tokens.border.color))

        for (sym in MOBILE_EDITOR_SYMBOLS) {
            MobileKeyButton(sym, mono = true, testTag = "mobile-key-sym-$sym", onClick = key {
                if (sym.length == 1 && session.typeChar(sym[0])) {
                    onMutated()
                } else if (session.commitText(sym, 1)) {
                    onMutated()
                }
            })
        }
    }
}

@Composable
private fun MobileKeyIconButton(
    icon: ProductIcon,
    enabled: Boolean = true,
    active: Boolean = false,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .width(36.dp)
            .height(32.dp)
            .clip(shape)
            .background(tokens.panel2.color)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(
            icon,
            14.dp,
            when {
                !enabled -> tokens.text3.color
                active -> tokens.accent.color
                else -> tokens.text.color
            },
        )
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
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(tokens.panel2.color)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(12f, FontWeight.Normal, kind = if (mono) FontKind.MONO else FontKind.SANS, lineHeight = 16f).copy(
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
    val projectPath = tab.metaString("project") ?: tab.metaString("projectPath").orEmpty()
    val resourceType = tab.metaString("resourceType") ?: "skill"
    val resourceScope = tab.metaString("scope") ?: "user"
    val tabReadOnly = tab.metadata["readOnly"].bool == true || resourceScope == "bundled"
    val shadowed = tab.metadata["shadowed"].bool == true
    val shadowedByObj = tab.metadata["shadowedBy"] as? kotlinx.serialization.json.JsonObject
    val shadowedByName = shadowedByObj?.get("name").string
    val shadowedBySource = shadowedByObj?.get("source").string

    var initialContent by remember(path, projectPath) { mutableStateOf<String?>(null) }
    var lastSavedContent by remember(path, projectPath) { mutableStateOf("") }
    var loadError by remember(path, projectPath) { mutableStateOf<String?>(null) }
    var saveError by remember(path, projectPath) { mutableStateOf<String?>(null) }
    var saving by remember(path, projectPath) { mutableStateOf(false) }
    var readOnly by remember(path, projectPath, tabReadOnly) { mutableStateOf(tabReadOnly) }
    var wrap by remember { mutableStateOf(true) }
    var dirty by remember(path, projectPath) { mutableStateOf(false) }

    LaunchedEffect(api, path, projectPath) {
        if (api == null || path.isBlank()) return@LaunchedEffect
        loadError = null
        try {
            val res = api.aiResourcesReadContent(path, projectPath)
            val fetched = res["content"].string.orEmpty()
            initialContent = fetched
            lastSavedContent = fetched
            readOnly = tabReadOnly || (res["editable"]?.bool == false)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (err: Throwable) {
            loadError = err.message ?: "Failed to load AI resource"
        }
    }

    if (loadError != null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            BasicText(
                loadError.orEmpty(),
                style = ppmText(12f).copy(color = tokens.error.color),
                modifier = Modifier.testTag("ai-resource-load-error"),
            )
        }
        return
    }

    val loaded = initialContent
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Loading AI resource…", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        return
    }

    val docState = remember(path, projectPath, readOnly) {
        EditorTabDocumentState(
            initialContent = loaded,
            language = fileCodeLanguage(path, "markdown"),
            readOnly = readOnly,
        )
    }
    var editTick by remember { mutableLongStateOf(0L) }

    fun saveResource() {
        val client = api ?: return
        if (readOnly || saving || !docState.isDirty) return
        val textToSave = docState.textToSave()
        saving = true
        saveError = null
        scope.launch {
            try {
                client.aiResourcesWriteContent(path, textToSave, projectPath)
                lastSavedContent = textToSave
                docState.markSaved(textToSave)
                dirty = docState.isDirty
                saving = false
                editTick++
            } catch (ce: kotlinx.coroutines.CancellationException) {
                saving = false
                throw ce
            } catch (err: Throwable) {
                saving = false
                val msg = err.message ?: "Failed to save AI resource"
                docState.saveError = msg
                saveError = msg
                model.toast(com.hienlh.ppm.app.ErrorToastMessage(title = msg))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        if (readOnly) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("ai-resource-readonly-banner"),
            ) {
                BasicText(
                    "Bundled resource — read-only. Duplicate it (from the AI Resources panel) to make an editable copy.",
                    style = ppmText(11f).copy(color = tokens.text3.color),
                )
            }
        }
        if (shadowed && !shadowedByName.isNullOrBlank() && !shadowedBySource.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.warning.opacity(0.14).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("ai-resource-shadowed-banner"),
            ) {
                BasicText(
                    "This $resourceType is overridden by another “$shadowedByName” from a higher-priority source ($shadowedBySource). It is currently inactive.",
                    style = ppmText(11f).copy(color = tokens.warning.color),
                )
            }
        }
        if (!saveError.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.14).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("ai-resource-save-error-banner"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    saveError.orEmpty(),
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.weight(1f),
                )
                SettingsButton(
                    label = "Retry Save",
                    primary = true,
                    onClick = { saveResource() },
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
                docState.formatTabTitle((tab.metaString("name") ?: tab.title).ifBlank { path }),
                style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!readOnly && dirty) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsButton(
                        label = "Revert",
                        enabled = !saving,
                        modifier = Modifier.testTag("ai-resource-revert-btn"),
                        onClick = {
                            docState.session.reloadExternalContent(lastSavedContent)
                            docState.markSaved(lastSavedContent)
                            dirty = false
                            saveError = null
                            editTick++
                        },
                    )
                    SettingsButton(
                        label = "Save",
                        primary = true,
                        enabled = !saving,
                        modifier = Modifier.testTag("ai-resource-save-btn"),
                        onClick = { saveResource() },
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
            onSaveNow = { saveResource() },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
