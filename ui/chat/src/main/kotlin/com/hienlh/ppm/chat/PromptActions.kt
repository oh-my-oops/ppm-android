package com.hienlh.ppm.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFonts
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionBookmark
import com.hienlh.ppm.state.RewindCode
import com.hienlh.ppm.state.SessionBookmarks
import com.hienlh.ppm.state.SessionTurn
import com.hienlh.ppm.state.SessionTurns
import com.hienlh.ppm.state.TurnIdResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Every turn of the chat on screen, in order (the turns the open chat publishes): what Rewind code reads. */
internal val LocalSessionTurnList = staticCompositionLocalOf<List<SessionTurn>> { emptyList() }

/**
 * Bookmark this turn, in a user message's action bar (`bookmark-turn-button.tsx`). A turn whose id
 * is not known yet waits for it the way Copy turn link does; the list is in the chat's ··· menu →
 * Bookmarks. Left out until the chat's list loads, so a server without bookmarks shows none.
 */
@Composable
internal fun BookmarkTurnButton(text: String) {
    val context = LocalTurnLinkContext.current ?: return
    val item = LocalTurnLinkItem.current ?: return
    val store = context.tools.bookmarks ?: return
    if (context.providerId != "claude" && context.providerId != "codex") return
    val project = context.tools.projectName
    val sessionId = context.sessionId
    LaunchedEffect(store, project, sessionId) { store.load(project, sessionId) }
    val lists by store.lists.collectAsState()
    val list = lists[SessionBookmarks.key(project, sessionId)] ?: return
    val scope = rememberCoroutineScope()
    val currentItem by rememberUpdatedState(item)
    val currentContext by rememberUpdatedState(context)
    var waiting by remember { mutableStateOf<Job?>(null) }
    val turnId = TurnIdResolver.known(item, context.bindings)
    val marked = turnId != null && list.any { it.turnId == turnId }
    fun save(id: String) = store.toggle(project, sessionId, id, currentItem.turnN, SessionBookmark.labelOf(text))
    val onClick = click@{
        if (turnId != null) return@click save(turnId)
        if (waiting != null) return@click
        waiting = scope.launch {
            val ctx = currentContext
            val id = TurnIdResolver.resolve(
                current = { currentItem },
                bindings = { currentContext.bindings },
                byUuid = { uuid -> ctx.tools.resolveTurnId(ctx.sessionId, ctx.providerId, uuid) },
            )
            waiting = null
            if (id != null) save(id) else ctx.tools.toast("Couldn't get this turn's id yet")
        }
    }
    val tokens = LocalPpmTokens.current
    ActionButton(
        if (waiting != null) ProductIcon.Loader2 else ProductIcon.Bookmark,
        if (marked) "Bookmarked" else "Bookmark",
        if (marked) "Remove this turn's bookmark" else "Bookmark this turn (··· → Bookmarks)",
        standardSize = true,
        iconTint = if (marked) tokens.accent.color else null,
        spinning = waiting != null,
        iconOnlyOnMobile = true,
        onClick = onClick,
    )
}

/**
 * "Rewind code" on a prompt the person sent (`rewind-code-button.tsx`): the files put back as they
 * were before this message, previewed in a dialog (a sheet on a phone), undoable, and with
 * "Rewind and fork" also forking the conversation from here ([onFork]). Shown only where the
 * chat's changes can be answered and the turns from here on made a call.
 */
@Composable
internal fun RewindCodeButton(messageId: String, onFork: (() -> Unit)?) {
    val host = LocalTurnChanges.current ?: return
    val changes = host.changes ?: return
    val mobile = LocalPpmMobile.current
    if (!mobile && host.dialog == null) return
    val turns = LocalSessionTurnList.current
    val rewind = remember(turns, messageId) { SessionTurns.rewindFrom(turns, messageId) }
    if (rewind == null || rewind.calls.isEmpty()) return
    val flow = remember(changes, rewind) { RewindCode(changes.backend, rewind.calls, settle = changes::refresh) }
    val stage by flow.stage.collectAsState()
    val scope = rememberCoroutineScope()
    val byCall = LocalTurnsByCall.current
    ActionButton(ProductIcon.History, "Rewind code", "Put the files back as they were before this message") {
        scope.launch { flow.start() }
    }
    val shown = stage ?: return
    val title = "Rewind code to here?"
    val intro = RewindCode.intro(rewind.turns)
    val body: @Composable () -> Unit = {
        RewindBody(shown, mobile, byCall)
        RewindButtons(
            shown,
            onFork,
            onClose = flow::close,
            onConfirm = { andFork -> scope.launch { if (flow.confirm() && andFork) onFork?.invoke() } },
            onUndo = { scope.launch { flow.undo() } },
        )
    }
    if (mobile) {
        host.sheet(flow::close) { top -> RewindSheet(top, title, intro, body) }
    } else {
        host.dialog?.invoke(title, intro, flow::close) {
            Column(Modifier.fillMaxWidth().testTag("rewind-code-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
        }
    }
}

/** The phone's sheet: `flex max-h-[90%] flex-col gap-3 overflow-y-auto px-4 pt-4`, the title `text-base font-semibold`. */
@Composable
private fun ColumnScope.RewindSheet(top: androidx.compose.ui.unit.Dp, title: String, intro: String, body: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = 16.dp)
            .testTag("rewind-code-dialog"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(title, style = ppmText(16f, FontWeight.SemiBold, lineHeight = 24f).copy(color = tokens.text.color))
        BasicText(intro, style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text2.color))
        body()
    }
}

@Composable
private fun RewindBody(stage: RewindCode.Stage, mobile: Boolean, byCall: Map<String, SessionTurn>) {
    val tokens = LocalPpmTokens.current
    // `text-[12.5px] leading-normal`.
    val text = ppmText(12.5f, lineHeight = 18.75f).copy(color = tokens.text2.color)
    Column(Modifier.fillMaxWidth()) {
        when (stage) {
            RewindCode.Stage.Loading -> BasicText("Working out what changed since…", style = text)
            is RewindCode.Stage.Preview -> {
                if (stage.moved) {
                    BasicText("A file changed since — this is what rewinding does now.", style = text.copy(color = tokens.warning.color, fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(8.dp))
                }
                if (stage.nothing) {
                    BasicText("Nothing written since this message is left to put back.", style = text)
                } else {
                    val files = stage.files
                    if (files.isNotEmpty()) {
                        val mono = SpanStyle(fontFamily = PpmFonts.mono)
                        BasicText(
                            buildAnnotatedString {
                                append("${RewindCode.plural(files.size, "file")}, ")
                                withStyle(mono.copy(color = tokens.success.color)) { append("+${files.sumOf { it.added }}") }
                                append(" ")
                                withStyle(mono.copy(color = tokens.error.color)) { append("−${files.sumOf { it.removed }}") }
                                append(" undone.")
                            },
                            style = text,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    RevertTurnSummary(stage.preview, mobile, byCall)
                    Spacer(Modifier.height(12.dp))
                    BasicText("You can undo it right after.", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
                }
            }
            is RewindCode.Stage.Done -> BasicText(stage.text, Modifier.testTag("rewind-code-status"), style = text)
        }
    }
}

/** `flex flex-wrap justify-end gap-1.5`: Cancel, Rewind and fork, Rewind code — or Undo and Close once done. */
@Composable
private fun RewindButtons(
    stage: RewindCode.Stage,
    onFork: (() -> Unit)?,
    onClose: () -> Unit,
    onConfirm: (andFork: Boolean) -> Unit,
    onUndo: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stage is RewindCode.Stage.Done) {
            if (stage.undoId != null) WebButton("Undo", ButtonKind.GHOST, enabled = !stage.busy, onClick = onUndo)
            WebButton("Close", ButtonKind.PRIMARY, onClick = onClose)
        } else {
            val ready = stage is RewindCode.Stage.Preview && !stage.busy && !stage.nothing
            WebButton("Cancel", ButtonKind.GHOST, onClick = onClose)
            if (onFork != null) WebButton("Rewind and fork", ButtonKind.OUTLINE, enabled = ready) { onConfirm(true) }
            WebButton("Rewind code", ButtonKind.DESTRUCTIVE, icon = icon(ProductIcon.History, 16.dp), enabled = ready) { onConfirm(false) }
        }
    }
}
