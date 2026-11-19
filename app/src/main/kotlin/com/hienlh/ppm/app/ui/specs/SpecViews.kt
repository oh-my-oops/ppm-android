package com.hienlh.ppm.app.ui.specs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.pendingSpecStart
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.startSpec
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.chat.MarkdownStyle
import com.hienlh.ppm.chat.MarkdownView
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SpecActionResult
import com.hienlh.ppm.client.SpecPhases
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.client.SpecTaskView
import com.hienlh.ppm.client.SpecView
import com.hienlh.ppm.client.approveSpec
import com.hienlh.ppm.client.checkSpecSuite
import com.hienlh.ppm.client.requestSpecChanges
import com.hienlh.ppm.client.resetSpecTask
import com.hienlh.ppm.client.runAllSpec
import com.hienlh.ppm.client.runSpecTask
import com.hienlh.ppm.client.setSpecChecks
import com.hienlh.ppm.client.spec
import com.hienlh.ppm.client.specs
import com.hienlh.ppm.client.stopSpec
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.BottomActionCard
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.SpecDocument
import com.hienlh.ppm.state.SpecStartChoice
import com.hienlh.ppm.state.SpecWorkflowCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The test tag every spec view starts its tags with: `phone-spec` in the phone's Specs area,
 * `spec` in the tablet's spec pane and workflow dialog.
 */
internal val LocalSpecTag = staticCompositionLocalOf { "phone-spec" }

/** How often a shown spec is read again: the server's `spec:changed` does not reach the app's socket handler. */
internal const val SPEC_POLL_MS = 4_000L

/** How often the Specs list is read again while it is shown. */
internal const val SPECS_POLL_MS = 10_000L

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    BasicText(
        text,
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
    )
}

/** Reads [block] again every [periodMs] while the screen is in front, and once at once. */
@Composable
internal fun PollWhileShown(vararg keys: Any?, periodMs: Long, block: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                block()
                delay(periodMs)
            }
        }
    }
}

internal fun errorText(error: Exception): String = error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"

@Composable
internal fun SpecListRow(spec: SpecSummary, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .ppmClickable(onClickLabel = "Open ${spec.title}", onClick = onClick)
            .cssBorder(tokens.borderSoft.color, sides = com.hienlh.ppm.design.CssSides.Bottom)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("${LocalSpecTag.current}-row:${spec.slug}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(ProductIcon.ClipboardList, 18.dp, tokens.text3.color)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(
                spec.title,
                style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val meta = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)
                BasicText(SpecWorkflowCopy.stage(spec.current), style = meta)
                if (spec.current == SpecPhases.EXECUTION && spec.tasksTotal > 0) BasicText("· ${spec.tasksDone}/${spec.tasksTotal}", style = meta)
                if (spec.checks == "thorough") {
                    Box(Modifier.semantics { contentDescription = "Thorough" }) { PpmIcon(ProductIcon.FlaskConical, 12.dp, tokens.success.color) }
                }
                if (spec.red > 0) BasicText("· ${spec.red} red", style = meta.copy(color = tokens.error.color))
                spec.updatedAt?.let { BasicText("· ${PpmFormat.relative(it, Instant.now())}", style = meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (spec.waitingForYou) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(tokens.warning.color)
                    .semantics { contentDescription = "Waiting for your approval" },
            )
        }
    }
}

/**
 * One spec (`SpecPane`): where it stands (Requirements → Design → Tasks), the document of the step
 * it is on with Approve / Request changes, and once the tasks are approved the tasks by wave with
 * Run all / Stop, Start and each task's chat. Approvals are made here, never by the chat's AI.
 */
@Composable
internal fun SpecDetail(
    model: AppModel,
    project: String,
    slug: String,
    onOpenChat: (sessionId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    /** The spec's own chat is the one beside it, so its header offers no Open chat. */
    inOwnChat: Boolean = false,
    /** Hides the pane (the tablet's ×); null shows no ×. */
    onClose: (() -> Unit)? = null,
    /** Every read of the spec, its title for whoever names it. */
    onLoaded: (SpecView) -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var spec by remember(project, slug) { mutableStateOf<SpecView?>(null) }
    var loadError by remember(project, slug) { mutableStateOf<String?>(null) }
    var busy by remember(project, slug) { mutableStateOf<String?>(null) }
    // Which document is shown: the step the spec is on, unless another was picked.
    var picked by remember(project, slug) { mutableStateOf<String?>(null) }

    PollWhileShown(model.api, project, slug, periodMs = SPEC_POLL_MS) {
        val api = model.api ?: return@PollWhileShown
        if (busy != null) return@PollWhileShown
        try {
            val read = api.spec(project, slug)
            spec = read
            onLoaded(read)
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = errorText(e)
        }
    }

    /** Runs a button's call: its spinner, the spec it answers, and what it says. */
    fun act(key: String, failTitle: String, call: suspend (ApiClient) -> Any) {
        val api = model.api ?: return
        if (busy != null) return
        busy = key
        scope.launch {
            try {
                when (val answer = call(api)) {
                    is SpecActionResult -> {
                        spec = answer.spec
                        if (answer.message.isNotBlank()) model.toast(ErrorToastMessage(answer.message, kind = ErrorToastMessage.Kind.SUCCESS))
                    }
                    is SpecView -> spec = answer
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage(failTitle, errorText(e)))
            } finally {
                busy = null
            }
        }
    }

    val view = spec
    if (view == null) {
        Box(modifier.fillMaxSize().testTag("${LocalSpecTag.current}-screen"), contentAlignment = Alignment.Center) {
            if (loadError != null) {
                EmptyNote("Could not load the spec: $loadError")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                    BasicText("Loading spec…", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color))
                }
            }
        }
        return
    }

    val taskList = picked?.let { it == SpecPhases.TASKS && view.current == SpecPhases.EXECUTION } ?: (view.current == SpecPhases.EXECUTION)
    val shownPhase = if (taskList) SpecPhases.TASKS else picked ?: view.current

    Column(modifier.fillMaxSize().testTag("${LocalSpecTag.current}-screen")) {
        SpecHeader(view, busy, onOpenChat.takeUnless { inOwnChat }, onClose, onChecks = { checks ->
            if (checks != view.checks) act("checks", "Could not change the checks") { it.setSpecChecks(project, slug, checks) }
        })
        SpecStepper(view, shownPhase) { p ->
            picked = if (p == view.current || (p == SpecPhases.TASKS && view.current == SpecPhases.EXECUTION)) null else p
        }
        if (taskList) {
            SpecTasks(
                view,
                busy,
                Modifier.weight(1f),
                onStart = { t -> act("start-${t.id}", "Could not start the task") { it.runSpecTask(project, slug, t.id) } },
                onReset = { t -> act("reset-${t.id}", "Could not reset the task") { it.resetSpecTask(project, slug, t.id) } },
                onCheckSuite = { act("suite", "Could not run the suite") { it.checkSpecSuite(project, slug) } },
                onOpenChat = onOpenChat,
            )
            SpecRunBar(
                view,
                busy,
                onRunAll = { act("run", "Could not start Run all") { it.runAllSpec(project, slug) } },
                onStop = { act("stop", "Could not stop the run") { it.stopSpec(project, slug) } },
            )
        } else {
            SpecDocumentPane(
                view,
                shownPhase,
                busy,
                Modifier.weight(1f),
                onApprove = { phase -> act("approve-$phase", "Not approved") { it.approveSpec(project, slug, phase) } },
                onRequestChanges = { phase, note, done ->
                    act("changes-$phase", "Could not send") {
                        val answer = it.requestSpecChanges(project, slug, phase, note)
                        done()
                        model.toast(ErrorToastMessage("Sent to the chat", kind = ErrorToastMessage.Kind.SUCCESS))
                        answer
                    }
                },
            )
        }
    }
}

@Composable
private fun SpecHeader(view: SpecView, busy: String?, onOpenChat: ((String, String) -> Unit)?, onClose: (() -> Unit)?, onChecks: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = com.hienlh.ppm.design.CssSides.Bottom)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PpmIcon(ProductIcon.ClipboardList, 18.dp, tokens.accent.color)
            BasicText(
                view.title,
                Modifier.weight(1f),
                style = ppmText(16f, FontWeight.SemiBold, lineHeight = 21f).copy(color = tokens.text.color),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Pill(SpecWorkflowCopy.kind(view.kind), Tone.MUTE)
            if (onClose != null) {
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier
                        .size(KitSpec.MinTouchTarget)
                        .testTag("${LocalSpecTag.current}-close")
                        .clip(shape)
                        .ppmClickable(shape = shape, role = Role.Button, onClickLabel = "Hide the spec", onClick = onClose)
                        .semantics { contentDescription = "Hide the spec" },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 16.dp, tokens.text3.color)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(
                label = "Task checks",
                value = view.checks,
                options = listOf("standard" to "Standard", "thorough" to "Thorough"),
                enabled = busy == null,
                onChange = onChecks,
            )
            Spacer(Modifier.weight(1f))
            if (onOpenChat != null) view.sessionId?.let { sessionId ->
                SmallButton("Open chat", ProductIcon.MessageSquare, enabled = true, testTag = "${LocalSpecTag.current}-open-chat") {
                    onOpenChat(sessionId, view.title)
                }
            }
        }
    }
}

@Composable
private fun SpecStepper(view: SpecView, shown: String, onPick: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .cssBorder(tokens.borderSoft.color, sides = com.hienlh.ppm.design.CssSides.Bottom)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        view.order.forEachIndexed { i, p ->
            val phase = view.phases[p] ?: return@forEachIndexed
            if (i > 0) PpmIcon(ProductIcon.ChevronRight, 12.dp, tokens.text3.color)
            val on = shown == p
            val now = view.current == p
            val shape = RoundedCornerShape(999.dp)
            Row(
                Modifier
                    .heightIn(min = KitSpec.MinTouchTarget)
                    .clip(shape)
                    .background(if (on) tokens.accentWash.color else Color.Transparent, shape)
                    .ppmClickable(shape = shape, onClickLabel = SpecWorkflowCopy.stage(p)) { onPick(p) }
                    .padding(horizontal = 10.dp)
                    .testTag("${LocalSpecTag.current}-step:$p"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (phase.status == "approved") {
                    PpmIcon(ProductIcon.CheckCircle2, 16.dp, tokens.success.color)
                } else {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (on || now) tokens.accent.color else Color.Transparent)
                            .cssBorder(if (on || now) tokens.accent.color else tokens.border.color, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("${i + 1}", style = ppmText(10f, FontWeight.SemiBold, lineHeight = 12f).copy(color = if (on || now) tokens.accentFg.color else tokens.text3.color))
                    }
                }
                BasicText(
                    SpecWorkflowCopy.stage(p),
                    style = ppmText(13f, if (on || now) FontWeight.Medium else FontWeight.Normal, lineHeight = 18f)
                        .copy(color = if (on || now) tokens.text.color else tokens.text2.color),
                )
                if (phase.status == "stale") BasicText("•", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.warning.color))
                if (phase.submitted) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.warning.color).semantics { contentDescription = "waiting for you" })
                }
            }
        }
        if (view.needsDrawing) Pill("Drawing: ${view.drawing}", if (view.drawing == "approved") Tone.GREEN else Tone.INFO)
        if (view.current == SpecPhases.EXECUTION) {
            val required = view.tasks.filter { !it.optional }
            if (required.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                BasicText(
                    "${required.count { it.done }} of ${required.size} done",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        }
    }
}

@Composable
private fun SpecDocumentPane(
    view: SpecView,
    phase: String,
    busy: String?,
    modifier: Modifier,
    onApprove: (String) -> Unit,
    onRequestChanges: (phase: String, note: String, done: () -> Unit) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val ph = view.phases[phase] ?: return
    var noteOpen by remember(view.slug, phase) { mutableStateOf(false) }
    val note = remember(view.slug, phase) { TextFieldState() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp)) {
        val errors = ph.issues.filter { it.isError }
        val warnings = ph.issues.filterNot { it.isError }.take(6)
        if (errors.isNotEmpty() || warnings.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.panel2.color)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (issue in errors) BasicText("Line ${issue.line}: ${issue.message}", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
                for (issue in warnings) BasicText("Line ${issue.line}: ${issue.message}", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.warning.color))
            }
        }
        val text = ph.text
        if (text == null) {
            BasicText(
                "Not written yet. The AI writes ${ph.file} when it gets to this step.",
                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color),
            )
        } else {
            MarkdownView(remember(phase, text) { SpecDocument.shown(phase, text) }, Modifier.fillMaxWidth().testTag("${LocalSpecTag.current}-document"), style = MarkdownStyle.chat, rawHtml = false)
        }
    }
    if (noteOpen) {
        Column(
            Modifier
                .fillMaxWidth()
                .cssBorder(tokens.borderSoft.color, sides = com.hienlh.ppm.design.CssSides.Top)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val shape = RoundedCornerShape(8.dp)
            Box(Modifier.fillMaxWidth().clip(shape).background(tokens.bgSolid.color).cssBorder(tokens.border.color, shape).padding(8.dp)) {
                PpmTextField(
                    note,
                    "What should change in ${ph.file}?",
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("${LocalSpecTag.current}-note"),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 6),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SmallButton("Cancel", null, enabled = true, testTag = "${LocalSpecTag.current}-note-cancel") { noteOpen = false }
                SmallButton(
                    "Send to the chat",
                    null,
                    enabled = busy == null && note.text.isNotBlank(),
                    primary = true,
                    testTag = "${LocalSpecTag.current}-note-send",
                ) {
                    onRequestChanges(phase, note.text.toString().trim()) {
                        note.clearText()
                        noteOpen = false
                    }
                }
            }
        }
    }
    // The card lays its content out in a column, where weight(1f) would take the pane's whole height.
    BottomActionCard(applyWindowInsets = LocalPpmMobile.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                SpecWorkflowCopy.phaseState(ph),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (ph.canAct) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!noteOpen) {
                        SmallButton("Request changes", ProductIcon.Pencil, enabled = busy == null, testTag = "${LocalSpecTag.current}-request-changes", modifier = Modifier.weight(1f)) {
                            noteOpen = true
                        }
                    }
                    ActionPrimaryButton(
                        icon = if (busy == "approve-$phase") ProductIcon.Loader2 else ProductIcon.Check,
                        label = "Approve ${SpecWorkflowCopy.stage(phase).lowercase()}",
                        enabled = busy == null,
                        spinningIcon = busy == "approve-$phase",
                        onClick = { onApprove(phase) },
                        modifier = Modifier.weight(1f).testTag("${LocalSpecTag.current}-approve"),
                    )
                }
            }
        }
    }
}

/** Run all / Stop and the running wave (`SpecRunControls`). */
@Composable
private fun SpecRunBar(view: SpecView, busy: String?, onRunAll: () -> Unit, onStop: () -> Unit) {
    val tokens = LocalPpmTokens.current
    if (!view.running && !view.interrupted && view.allDone) return
    // The card lays its content out in a column, where weight(1f) would take the pane's whole height.
    BottomActionCard(applyWindowInsets = LocalPpmMobile.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val run = view.run
            if (view.running && run != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PpmIcon(ProductIcon.Loader2, 14.dp, tokens.accent.color, spinning = true)
                    BasicText("Wave ${run.wave + 1} of ${view.waves.size}", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color))
                }
            }
            if (view.interrupted) {
                BasicText(
                    "Interrupted: a restart stopped the run. Stop clears it, then Run all starts it again.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.warning.color),
                )
            }
            if (view.running || view.interrupted) {
                SmallButton("Stop", ProductIcon.Square, enabled = busy == null, testTag = "${LocalSpecTag.current}-stop", modifier = Modifier.fillMaxWidth()) { onStop() }
            } else {
                ActionPrimaryButton(
                    icon = if (busy == "run") ProductIcon.Loader2 else ProductIcon.Play,
                    label = "Run all",
                    enabled = busy == null,
                    spinningIcon = busy == "run",
                    onClick = onRunAll,
                    modifier = Modifier.fillMaxWidth().testTag("${LocalSpecTag.current}-run-all"),
                )
            }
        }
    }
}

/** The approved tasks by wave (`SpecTasks`): what runs side by side, why a task waits, and each task's chat. */
@Composable
private fun SpecTasks(
    view: SpecView,
    busy: String?,
    modifier: Modifier,
    onStart: (SpecTaskView) -> Unit,
    onReset: (SpecTaskView) -> Unit,
    onCheckSuite: () -> Unit,
    onOpenChat: (String, String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val byId = view.tasks.associateBy { it.id }
    val optional = view.tasks.filter { it.optional }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (view.suiteNewFailures.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.error.opacity(0.10).color)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText("The whole suite has new failures", style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.error.color))
                for (f in view.suiteNewFailures.take(8)) {
                    BasicText("• $f", style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText("Tasks wait until a clean run.", Modifier.weight(1f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
                    SmallButton(if (busy == "suite") "Running…" else "Check again", null, enabled = busy == null, testTag = "${LocalSpecTag.current}-check-suite") { onCheckSuite() }
                }
            }
        }
        for (issue in view.waveIssues.filter { it.isError }) {
            BasicText(
                issue.message,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(tokens.error.opacity(0.10).color).padding(8.dp),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
            )
        }
        view.waves.forEachIndexed { w, ids ->
            val tasks = ids.mapNotNull { byId[it] }
            val live = tasks.count { it.active }
            WaveHeader("Wave ${w + 1}") {
                when {
                    tasks.isNotEmpty() && tasks.all { it.done } -> Pill("Done", Tone.GREEN)
                    live > 0 -> Pill("$live running", Tone.ACCENT)
                    tasks.size > 1 -> Pill("${tasks.size} side by side", Tone.MUTE)
                }
            }
            for (t in tasks) TaskRow(view, t, view.moved[t.id], busy, onStart, onReset, onOpenChat)
        }
        if (optional.isNotEmpty()) {
            WaveHeader("Optional · not in Run all") {}
            for (t in optional) TaskRow(view, t, null, busy, onStart, onReset, onOpenChat)
        }
        if (view.tasks.isEmpty()) BasicText("tasks.md lists no tasks.", Modifier.padding(8.dp), style = ppmText(14f, lineHeight = 20f).copy(color = tokens.text3.color))
        if (view.thorough) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.ShieldCheck, 14.dp, tokens.text3.color)
                val base = view.baseline
                val line = when {
                    view.baselineRunning -> "Recording the suite's failures before the work…"
                    base != null -> "Baseline: ${base.failing.size} test${if (base.failing.size == 1) "" else "s"} already failed before this spec, so green means no new failures."
                    else -> "The whole suite is compared with how it stood before the first task."
                } + if (view.suiteRunning) " Running the whole suite…" else ""
                BasicText(line, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            }
        }
    }
}

@Composable
private fun WaveHeader(label: String, trailing: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(label.uppercase(), style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color))
        trailing()
    }
}

@Composable
private fun TaskRow(
    view: SpecView,
    t: SpecTaskView,
    why: String?,
    busy: String?,
    onStart: (SpecTaskView) -> Unit,
    onReset: (SpecTaskView) -> Unit,
    onOpenChat: (String, String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    val run = t.run
    val last = run?.lastRun ?: when {
        t.status == "red" -> run?.red
        t.done -> run?.green
        else -> null
    }
    val refs = listOfNotNull(
        t.requirements.takeIf { it.isNotEmpty() }?.let { "Req ${it.joinToString(" · ")}" },
        t.deps.takeIf { it.isNotEmpty() }?.let { "Deps: ${it.joinToString(", ")}" },
        t.files.takeIf { it.isNotEmpty() }?.let { "Files: ${it.joinToString(", ")}" },
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (t.active || t.status == "failed") tokens.panel2.color else Color.Transparent, shape)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag("${LocalSpecTag.current}-task:${t.id}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StateDot(t)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(
                "${t.id}. ${t.title}",
                style = ppmText(14f, if (t.done) FontWeight.Normal else FontWeight.Medium, lineHeight = 19f)
                    .copy(color = if (t.done) tokens.text2.color else tokens.text.color),
            )
            if (refs.isNotEmpty()) {
                BasicText(refs, style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text3.color), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            why?.let { BasicText("Later wave: $it", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.info.color)) }
            if (view.thorough && t.status == "red" && t.tests.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    PpmIcon(ProductIcon.Lock, 12.dp, tokens.text3.color)
                    BasicText("${t.tests.joinToString(", ")} · locked since red", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (t.status == "failed") run?.error?.let { BasicText(it, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color)) }
            if (last != null && !t.done) {
                val failing = last.failing.take(5).joinToString("") { "\n✗ $it" }
                BasicText(
                    "${last.outcome} · ${last.failed ?: 0} fail · ${last.passed ?: 0} pass$failing",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(tokens.bgSolid.color).padding(8.dp),
                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                )
            }
            if (!t.done && t.next == "blocked" && !t.active) {
                t.blockedReason?.let { BasicText(it, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color)) }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    t.done -> Pill(run?.green?.let { "Green · ${it.passed ?: 0} pass" } ?: "Done", Tone.GREEN)
                    t.status == "red" -> Pill("Red · writing code", Tone.RED)
                    t.status == "running" -> Pill(if (view.thorough && t.noTest == null) "Writing tests" else "Running", Tone.ACCENT)
                    t.status == "failed" -> Pill("Failed", Tone.RED)
                    t.optional -> Pill("Optional", Tone.MUTE)
                }
                val chat = run?.sessionId
                if (chat != null && (t.active || t.status == "failed" || t.done)) {
                    SmallButton("Chat", ProductIcon.ExternalLink, enabled = true, testTag = "${LocalSpecTag.current}-task-chat:${t.id}") { onOpenChat(chat, "${view.title} · ${t.id}") }
                }
                if (t.next == "start" && t.status != "failed" && view.run?.active != true) {
                    SmallButton("Start", ProductIcon.Play, enabled = busy == null, testTag = "${LocalSpecTag.current}-task-start:${t.id}") { onStart(t) }
                }
                if ((t.active || t.status == "failed") && view.run?.active != true) {
                    SmallButton("Reset", ProductIcon.X, enabled = busy == null, testTag = "${LocalSpecTag.current}-task-reset:${t.id}") { onReset(t) }
                }
            }
        }
    }
}

@Composable
private fun StateDot(t: SpecTaskView) {
    val tokens = LocalPpmTokens.current
    val base = Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape)
    when {
        t.done -> Box(base.background(tokens.success.opacity(0.15).color), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.Check, 12.dp, tokens.success.color) }
        t.status == "red" -> Box(base.background(tokens.error.opacity(0.15).color), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.FlaskConical, 12.dp, tokens.error.color) }
        t.status == "running" -> Box(base.background(tokens.accentWash.color), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.Loader2, 13.dp, tokens.accent.color, spinning = true) }
        t.status == "failed" -> Box(base.background(tokens.error.opacity(0.15).color), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.CircleX, 12.dp, tokens.error.color) }
        t.next == "blocked" -> Box(base.background(tokens.panel2.color), contentAlignment = Alignment.Center) { PpmIcon(ProductIcon.Clock, 12.dp, tokens.text3.color) }
        else -> Box(base.cssBorder(tokens.border.color, CircleShape))
    }
}

private enum class Tone { GREEN, RED, ACCENT, MUTE, INFO }

@Composable
private fun Pill(text: String, tone: Tone) {
    val tokens = LocalPpmTokens.current
    val (bg: Rgba, fg: Rgba) = when (tone) {
        Tone.GREEN -> tokens.success.opacity(0.15) to tokens.success
        Tone.RED -> tokens.error.opacity(0.15) to tokens.error
        Tone.ACCENT -> tokens.accentWash to tokens.accent
        Tone.INFO -> tokens.info.opacity(0.15) to tokens.info
        Tone.MUTE -> tokens.panel2 to tokens.text3
    }
    val shape = RoundedCornerShape(999.dp)
    BasicText(
        text,
        Modifier.clip(shape).background(bg.color, shape).padding(horizontal = 8.dp, vertical = 2.dp),
        style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = fg.color),
        maxLines = 1,
    )
}

/** A 44 dp secondary button (or the primary one with [primary]). */
@Composable
private fun SmallButton(
    label: String,
    icon: ProductIcon?,
    enabled: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val fg = when {
        !enabled -> tokens.text3
        primary -> tokens.accentFg
        else -> tokens.text
    }
    Row(
        modifier
            .heightIn(min = KitSpec.MinTouchTarget)
            .clip(shape)
            .background(if (primary && enabled) tokens.accent.color else tokens.panel2.color, shape)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(enabled = enabled, shape = shape, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { PpmIcon(it, 14.dp, fg.color) }
        BasicText(label, style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = fg.color), maxLines = 1)
    }
}

@Composable
internal fun Segmented(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onChange: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val outer = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(outer)
            .background(tokens.panel2.color, outer)
            .cssBorder(tokens.borderSoft.color, outer)
            .padding(2.dp)
            .semantics { contentDescription = label },
    ) {
        for ((id, text) in options) {
            val on = value == id
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(if (on) tokens.panel.color else Color.Transparent, shape)
                    .ppmClickable(enabled = enabled, shape = shape, role = Role.RadioButton, onClickLabel = text) { onChange(id) }
                    .padding(horizontal = 12.dp)
                    .testTag("${LocalSpecTag.current}-option:$id"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (id == "thorough") PpmIcon(ProductIcon.FlaskConical, 13.dp, tokens.success.color)
                BasicText(text, style = ppmText(12.5f, if (on) FontWeight.Medium else FontWeight.Normal, lineHeight = 17f).copy(color = if (on) tokens.text.color else tokens.text2.color))
            }
        }
    }
}

/**
 * The workflow a chat starts in (spec-workflow.tsx's Workflow group): Spec, Quick Spec or Bug Fix,
 * where a Spec starts, and its task checks. A chat with no session yet can also stay a Chat.
 * [forceBottomSheet]: the phone's bottom sheet; otherwise a centred dialog on a tablet.
 */
@Composable
internal fun SpecStartSheet(model: AppModel, chatTabId: String, onDismiss: () -> Unit, forceBottomSheet: Boolean) {
    val tokens = LocalPpmTokens.current
    val current = remember(chatTabId) { model.pendingSpecStart(chatTabId) }
    val hasSession = remember(chatTabId) { model.tabOf(chatTabId)?.let { model.specs.sessionOf(it) } != null }
    var choice by remember(chatTabId) { mutableStateOf(current ?: SpecStartChoice(workflow = "spec")) }
    Sheet(
        title = "Workflow",
        subtitle = if (hasSession) "Turn this chat into a spec. The AI picks it up with your next message." else "Start with a workflow, or just chat.",
        onDismiss = onDismiss,
        forceBottomSheet = forceBottomSheet,
        modifier = Modifier.testTag("${LocalSpecTag.current}-start-sheet"),
        footer = {
            ActionPrimaryButton(
                icon = ProductIcon.ClipboardList,
                label = if (choice.isSpec) "Start ${SpecWorkflowCopy.label(choice.workflow)}" else "Just chat",
                onClick = { model.startSpec(chatTabId, choice) },
                modifier = Modifier.weight(1f).testTag("${LocalSpecTag.current}-start-confirm"),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (workflow in SpecStartChoice.WORKFLOWS) {
                if (workflow == "chat" && hasSession) continue
                val on = choice.workflow == workflow
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(shape)
                        .background(if (on) tokens.accentWash.color else tokens.panel.color, shape)
                        .cssBorder(if (on) tokens.accent.color else tokens.border.color, shape)
                        .ppmClickable(shape = shape, role = Role.RadioButton, onClickLabel = SpecWorkflowCopy.label(workflow)) { choice = choice.copy(workflow = workflow) }
                        .padding(12.dp)
                        .testTag("${LocalSpecTag.current}-workflow:$workflow"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(workflowIcon(workflow), 18.dp, if (on) tokens.accent.color else tokens.text2.color)
                    Column(Modifier.weight(1f)) {
                        BasicText(SpecWorkflowCopy.label(workflow), style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color))
                        BasicText(SpecWorkflowCopy.note(workflow), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
                    }
                    if (on) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                }
            }
            if (choice.workflow == "spec") {
                OptionRow("Start from") {
                    Segmented("Start from", choice.startFrom, listOf("requirements" to "Requirements", "design" to "Design"), enabled = true) {
                        choice = choice.copy(startFrom = it)
                    }
                }
            }
            if (choice.isSpec) {
                OptionRow("Task checks") {
                    Segmented("Task checks", choice.checks, listOf("standard" to "Standard", "thorough" to "Thorough"), enabled = true) {
                        choice = choice.copy(checks = it)
                    }
                }
                BasicText(
                    if (choice.checks == "thorough") {
                        "PPM runs your tests: each task must fail first, then pass. The AI can't tick a task itself."
                    } else {
                        "The AI marks each task done. You can switch to Thorough later."
                    },
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.OptionRow(label: String, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(label, Modifier.width(96.dp), style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color))
        content()
    }
}

internal fun workflowIcon(workflow: String): ProductIcon = when (workflow) {
    "spec" -> ProductIcon.ClipboardList
    "quick" -> ProductIcon.Zap
    "bugfix" -> ProductIcon.Bug
    else -> ProductIcon.MessageSquare
}
