package com.hienlh.ppm.app.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatSpecBinding
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.newSpec
import com.hienlh.ppm.app.openSpecChat
import com.hienlh.ppm.app.openSpecInChat
import com.hienlh.ppm.app.specs
import com.hienlh.ppm.app.startSpec
import com.hienlh.ppm.app.startSpecHere
import com.hienlh.ppm.app.ui.specs.EmptyNote
import com.hienlh.ppm.app.ui.specs.LocalSpecTag
import com.hienlh.ppm.app.ui.specs.PollWhileShown
import com.hienlh.ppm.app.ui.specs.SPECS_POLL_MS
import com.hienlh.ppm.app.ui.specs.Segmented
import com.hienlh.ppm.app.ui.specs.SpecDetail
import com.hienlh.ppm.app.ui.specs.SpecListRow
import com.hienlh.ppm.app.ui.specs.SpecStartSheet
import com.hienlh.ppm.app.ui.specs.errorText
import com.hienlh.ppm.app.ui.specs.workflowIcon
import com.hienlh.ppm.client.SpecSummary
import com.hienlh.ppm.client.spec
import com.hienlh.ppm.client.specBySession
import com.hienlh.ppm.client.specs
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.state.SpecStartChoice
import com.hienlh.ppm.state.SpecWorkflowCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The test tag the tablet's spec views start theirs with (`spec-approve`, `spec-run-all`…). */
internal const val TABLET_SPEC_TAG = "spec"

/** A chat tab at least this wide shows its spec beside the conversation (`@3xl/spec`, 48rem); narrower, above it. */
internal val SPEC_PANE_BESIDE_MIN_WIDTH = 768.dp

/**
 * The sidebar's Specs (`specs-sidebar-panel.tsx`): the project's specs, newest first, with where
 * each stands, its tasks done, its checks and whether it waits for you. A tap opens the spec's chat
 * with the spec beside it; a spec with no chat yet gets one. `+` starts a new chat as a Spec.
 */
@Composable
internal fun SpecsSidebarPanel(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val project = model.selectedProject
    var list by remember(project) { mutableStateOf<List<SpecSummary>?>(null) }
    var error by remember(project) { mutableStateOf<String?>(null) }
    PollWhileShown(model.api, project, periodMs = SPECS_POLL_MS) {
        val api = model.api ?: return@PollWhileShown
        if (project == null) return@PollWhileShown
        try {
            list = api.specs(project)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
            if (list == null) list = emptyList()
        }
    }
    CompositionLocalProvider(LocalSpecTag provides TABLET_SPEC_TAG) {
        Column(Modifier.fillMaxSize().testTag("specs-panel")) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "SPECS",
                    Modifier.weight(1f),
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                )
                if (project != null) {
                    val shape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .size(36.dp)
                            .testTag("specs-new")
                            .clip(shape)
                            .ppmClickable(shape = shape, role = Role.Button, onClickLabel = "New spec") { model.newSpec() }
                            .semantics { contentDescription = "New spec" },
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.Plus, 16.dp, tokens.text3.color)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val shown = list
                when {
                    project == null -> EmptyNote("Choose a project to see its specs.")
                    shown == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                    }
                    shown.isEmpty() -> EmptyNote(error?.let { "Could not load the specs: $it" } ?: "No specs yet. Start a chat and pick Spec, or press +.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.slug }) { spec ->
                            SpecListRow(spec) { model.openSpecInChat(project, spec.slug, spec.title, spec.sessionId) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Keeps [AppSpecs.bound][com.hienlh.ppm.app.AppSpecs.bound] for this chat (`useSessionSpec`): the
 * spec its session drives, with its title and checks once read. Asked again while unbound, since
 * the chat's AI can bind it mid-way (`spec_create`) and this app hears no `spec:changed`.
 */
@Composable
internal fun ChatSpecBindingEffect(model: AppModel, tab: ChatTab, sessionId: String?) {
    val specs = model.specs
    val tabId = tab.tabId
    val api = model.api
    val project = tab.project
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val eligible = sessionId != null && project.isNotEmpty() && !tabId.startsWith("design:")
    DisposableEffect(tabId) {
        onDispose { specs.bound.remove(tabId) }
    }
    LaunchedEffect(api, project, sessionId, eligible, specs.bindings, lifecycle) {
        if (!eligible || api == null || sessionId == null) {
            specs.bound.remove(tabId)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val binding = try {
                    api.specBySession(project, sessionId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (binding != null) {
                    val known = specs.bound[tabId]?.takeIf { it.slug == binding.slug }
                    specs.bound[tabId] = known ?: ChatSpecBinding(binding.slug)
                    if (known?.title == null) {
                        try {
                            val view = api.spec(project, binding.slug)
                            specs.bound[tabId] = ChatSpecBinding(binding.slug, view.title, view.checks)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    return@repeatOnLifecycle
                }
                specs.bound.remove(tabId)
                delay(SPECS_POLL_MS)
            }
        }
    }
}

/** Where a chat's spec pane goes in a chat area [width] wide: beside the chat, or above it. */
internal fun specPaneBeside(width: Dp): Boolean = width >= SPEC_PANE_BESIDE_MIN_WIDTH

/** The pane's width beside the chat (`w-[44%] min-w-[340px] max-w-[760px]`). */
internal fun specPaneWidth(width: Dp): Dp = (width * 0.44f).coerceIn(340.dp, 760.dp)

/**
 * A chat tab with its spec (`chat-tab.tsx`'s `@container/spec`): [chat] keeps its place in the
 * composition whether the pane shows or not, so hiding the spec never resets the conversation.
 * The pane sits beside the chat when the tab is wide enough for both, above it otherwise.
 */
@Composable
internal fun ChatWithSpecPane(
    model: AppModel,
    tab: ChatTab,
    modifier: Modifier = Modifier,
    /** False on a phone's layout, whose Specs area shows the spec instead. */
    enabled: Boolean = true,
    chat: @Composable (Modifier) -> Unit,
) {
    val specs = model.specs
    val binding = specs.bound[tab.tabId]
    val slug = binding?.slug
    // Opening a spec from the sidebar or a workflow shows its pane, even one hidden before.
    if (enabled && slug != null && specs.paneRequested(slug)) {
        LaunchedEffect(slug) {
            if (specs.takePaneRequest(slug)) specs.setPaneOpen(tab.tabId, true)
        }
    }
    val open = enabled && slug != null && specs.paneOpen(tab.tabId)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val beside = specPaneBeside(maxWidth)
        val paneWidth = specPaneWidth(maxWidth)
        val paneHeight = maxHeight * 0.45f
        val chatModifier = when {
            !open -> Modifier
            beside -> Modifier.padding(end = paneWidth)
            else -> Modifier.padding(top = paneHeight)
        }
        chat(chatModifier)
        if (open && slug != null) {
            val tokens = LocalPpmTokens.current
            val place = if (beside) {
                Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(min = paneWidth, max = paneWidth)
                    .fillMaxHeight()
                    .cssBorder(tokens.border.color, sides = CssSides.Start)
            } else {
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(paneHeight)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            }
            Box(
                place
                    .background(tokens.panel.color)
                    .testTag(if (beside) "spec-pane-beside" else "spec-pane-above"),
            ) {
                CompositionLocalProvider(LocalSpecTag provides TABLET_SPEC_TAG) {
                    SpecDetail(
                        model = model,
                        project = tab.project,
                        slug = slug,
                        onOpenChat = { sessionId, title -> model.openSpecChat(tab.project, sessionId, title) },
                        inOwnChat = true,
                        onClose = { specs.setPaneOpen(tab.tabId, false) },
                        onLoaded = { view ->
                            val now = specs.bound[tab.tabId]
                            if (now?.slug == view.slug && (now.title != view.title || now.checks != view.checks)) {
                                specs.bound[tab.tabId] = ChatSpecBinding(view.slug, view.title, view.checks)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** The workflow a chat with no session will start in, read from its tab so a pick shows at once. */
@Composable
private fun pendingWorkflow(model: AppModel, tab: ChatTab): SpecStartChoice? {
    if (tab.session != null) return null
    val metadata = model.layout?.panelOf(tab.tabId)?.tab(tab.tabId)?.metadata ?: return null
    return SpecStartChoice.from(metadata["specStart"]).takeIf { it.isSpec }
}

/** Whether a chat offers Spec mode at all: not a design's chat, and not on a server in minimal mode. */
internal fun offersSpecWorkflow(model: AppModel, tab: ChatTab): Boolean =
    model.info?.minimalMode != true && !tab.tabId.startsWith("design:")

/**
 * The composer's workflow chip (`SpecWorkflowChip`), shown only while a workflow is on: a chat bound
 * to a spec shows "Spec" and shows or hides its pane; a new chat shows the workflow it will start
 * in and opens the workflow dialog. Choosing one is in the Modes menu.
 */
@Composable
internal fun SpecWorkflowChip(model: AppModel, tab: ChatTab, narrow: Boolean) {
    if (!offersSpecWorkflow(model, tab)) return
    val binding = model.specs.bound[tab.tabId]
    SpecWorkflowChipView(
        binding = binding,
        pending = if (binding == null) pendingWorkflow(model, tab) else null,
        narrow = narrow,
        onClick = { if (binding != null) model.specs.togglePane(tab.tabId) else model.specs.choosingFor = tab.tabId },
    )
}

/** What [SpecWorkflowChip] draws: the bound spec, else the [pending] workflow, else nothing. */
@Composable
internal fun SpecWorkflowChipView(binding: ChatSpecBinding?, pending: SpecStartChoice?, narrow: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val face = when {
        binding != null -> ChipFace(
            ProductIcon.ClipboardList,
            "Spec",
            binding.checks == "thorough",
            "Spec: ${binding.title ?: "this chat's spec"} — show or hide it",
        )
        pending != null && pending.isSpec -> ChipFace(
            workflowIcon(pending.workflow),
            SpecWorkflowCopy.label(pending.workflow),
            pending.checks == "thorough",
            "Workflow: ${SpecWorkflowCopy.label(pending.workflow)} · ${if (pending.checks == "thorough") "Thorough" else "Standard"}",
        )
        else -> return
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .height(30.dp)
            .widthIn(max = 160.dp)
            .testTag("composer-spec-chip")
            .clip(shape)
            .background(tokens.accentWash.color, shape)
            .ppmClickable(shape = shape, role = Role.Button, onClickLabel = face.title, onClick = onClick)
            .semantics { contentDescription = face.title }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(face.icon, 16.dp, tokens.accent.color)
        // `max-md:hidden` on the workflow's name; the bound chip always says "Spec".
        if (!narrow || binding != null) {
            BasicText(face.label, style = ppmText(11.5f, lineHeight = 15f).copy(color = tokens.accent.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (face.thorough) PpmIcon(ProductIcon.FlaskConical, 12.dp, tokens.success.color)
        if (binding == null) PpmIcon(ProductIcon.ChevronDown, 12.dp, tokens.text3.color)
    }
}

private data class ChipFace(val icon: ProductIcon, val label: String, val thorough: Boolean, val title: String)

/**
 * The Modes menu's Workflow group (`SpecWorkflowMenuSection`): a new chat picks the workflow its
 * first message starts in, a chat with no spec can start one bound to itself, and a chat bound to a
 * spec shows or hides it. A workflow goes with a mode, never instead of one.
 */
@Composable
internal fun SpecWorkflowMenuSection(model: AppModel, tab: ChatTab, onDone: () -> Unit) {
    if (!offersSpecWorkflow(model, tab)) return
    val tokens = LocalPpmTokens.current
    val binding = model.specs.bound[tab.tabId]
    val locked = tab.session != null
    val pending = pendingWorkflow(model, tab)
    var checks by remember(tab.tabId) { mutableStateOf(pending?.checks ?: "standard") }
    Column(Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Top).testTag("modes-workflow")) {
        BasicText(
            "Workflow",
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
        )
        if (binding != null) {
            MenuRow(
                icon = ProductIcon.ClipboardList,
                title = "Spec: ${binding.title ?: "this chat's spec"}",
                note = "${if (binding.checks == "thorough") "Thorough — PPM runs the tests" else "Standard"} · " +
                    if (model.specs.paneOpen(tab.tabId)) "Hide the spec" else "Show the spec",
                selected = false,
                enabled = true,
                tag = "modes-workflow-bound",
            ) {
                model.specs.togglePane(tab.tabId)
                onDone()
            }
            return@Column
        }
        if (locked) {
            BasicText(
                "Turn this chat into a spec. The AI picks it up with your next message.",
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
            )
        }
        val current = if (locked) "chat" else pending?.workflow ?: "chat"
        for (workflow in SpecStartChoice.WORKFLOWS) {
            MenuRow(
                icon = workflowIcon(workflow),
                title = SpecWorkflowCopy.label(workflow),
                note = SpecWorkflowCopy.note(workflow),
                selected = current == workflow,
                enabled = !(locked && workflow == "chat"),
                tag = "modes-workflow:$workflow",
            ) {
                val base = pending ?: SpecStartChoice()
                model.startSpecHere(tab, base.copy(workflow = workflow, checks = checks))
                onDone()
            }
        }
        if (locked || current != "chat") {
            Row(
                Modifier.fillMaxWidth().cssBorder(tokens.borderSoft.color, sides = CssSides.Top).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Task checks", Modifier.weight(1f), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                Segmented("Task checks", checks, listOf("standard" to "Standard", "thorough" to "Thorough"), enabled = true) { next ->
                    checks = next
                    // A new chat's choice is kept at once; a chat with a session uses it when a workflow is picked.
                    if (!locked && pending != null) model.startSpec(tab.tabId, pending.copy(checks = next))
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ProductIcon,
    title: String,
    note: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .testTag(tag)
            .background(if (selected) tokens.panel2.color else Color.Transparent)
            .ppmClickable(
                enabled = enabled,
                pressedColor = tokens.surfaceHover.color,
                hoverColor = tokens.surfaceHover.color,
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PpmIcon(icon, 16.dp, (if (enabled) tokens.text2 else tokens.text3).color, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                title,
                style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = (if (enabled) tokens.text else tokens.text3).color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(note, style = ppmText(12f, lineHeight = 16.5f).copy(color = tokens.text3.color))
        }
        if (selected) PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color, Modifier.padding(top = 2.dp))
    }
}

/**
 * The tablet's workflow dialog, which a chat's chip asks for (`AppSpecs.choosingFor`): Spec, Quick
 * Spec or Bug Fix with where a Spec starts and its task checks, as a centred card.
 */
@Composable
internal fun TabletSpecStartDialog(model: AppModel) {
    val chatTabId = model.specs.choosingFor ?: return
    CompositionLocalProvider(LocalSpecTag provides TABLET_SPEC_TAG) {
        SpecStartSheet(model, chatTabId, onDismiss = { model.specs.choosingFor = null }, forceBottomSheet = false)
    }
}
