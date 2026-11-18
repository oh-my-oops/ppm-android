package com.hienlh.ppm.app.ui.design

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.ChatPane
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.PrimaryButton
import com.hienlh.ppm.app.ui.SecondaryButton
import com.hienlh.ppm.app.ui.design.share.DesignShareDialog
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.DesignProviderOption
import com.hienlh.ppm.client.DesignStyleEditResult
import com.hienlh.ppm.client.DesignTweaksApplyResult
import com.hienlh.ppm.client.DesignUndoEditResult
import com.hienlh.ppm.client.designApplyStyle
import com.hienlh.ppm.client.designApplyTweaks
import com.hienlh.ppm.client.designCreate
import com.hienlh.ppm.client.designCreateComment
import com.hienlh.ppm.client.designCreateSystem
import com.hienlh.ppm.client.designDelete
import com.hienlh.ppm.client.designDeleteComment
import com.hienlh.ppm.client.designDeleteSystem
import com.hienlh.ppm.client.designGet
import com.hienlh.ppm.client.designGetSystemStale
import com.hienlh.ppm.client.designHeadPreviewGen
import com.hienlh.ppm.client.designList
import com.hienlh.ppm.client.designListComments
import com.hienlh.ppm.client.designListHistory
import com.hienlh.ppm.client.designListSystems
import com.hienlh.ppm.client.designMintPreview
import com.hienlh.ppm.client.designOpenSystemShowcase
import com.hienlh.ppm.client.designProviders
import com.hienlh.ppm.client.designReadTweaks
import com.hienlh.ppm.client.designRename
import com.hienlh.ppm.client.designReplyComment
import com.hienlh.ppm.client.designRestoreHistory
import com.hienlh.ppm.client.designStreamExport
import com.hienlh.ppm.client.designSubmitCheckResult
import com.hienlh.ppm.client.designUndoEdit
import com.hienlh.ppm.client.designUpdateComment
import com.hienlh.ppm.client.designUpdateSystem
import com.hienlh.ppm.client.filenameFromContentDisposition
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.client.origin
import com.hienlh.ppm.protocol.BridgeChildMessage
import com.hienlh.ppm.protocol.CanvasCheckReport
import com.hienlh.ppm.protocol.CheckSize
import com.hienlh.ppm.protocol.LayoutCheckReport
import com.hienlh.ppm.protocol.MAX_BRIDGE_ISSUES
import com.hienlh.ppm.protocol.RuntimeIssue
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.checkRunEnvelope
import com.hienlh.ppm.protocol.clearSelectionEnvelope
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.newBridgeNonce
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.pickerEnvelope
import com.hienlh.ppm.protocol.pinsSetEnvelope
import com.hienlh.ppm.protocol.restoreScrollEnvelope
import com.hienlh.ppm.protocol.selectParentEnvelope
import com.hienlh.ppm.protocol.slidesExtractEnvelope
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.protocol.transformCancelEnvelope
import com.hienlh.ppm.protocol.transformModeEnvelope
import com.hienlh.ppm.protocol.transformNudgeEnvelope
import com.hienlh.ppm.protocol.transformTargetEnvelope
import com.hienlh.ppm.protocol.tweakReadEnvelope
import com.hienlh.ppm.protocol.tweakResetEnvelope
import com.hienlh.ppm.protocol.tweakSetEnvelope
import com.hienlh.ppm.protocol.withRuntimeIssues
import com.hienlh.ppm.state.design.CommentAnchor
import com.hienlh.ppm.state.design.DesignComment
import com.hienlh.ppm.state.design.DesignDetail
import com.hienlh.ppm.state.design.DesignDeviceFrame
import com.hienlh.ppm.state.design.DesignKind
import com.hienlh.ppm.state.design.DesignLayoutOverride
import com.hienlh.ppm.state.design.DesignPane
import com.hienlh.ppm.state.design.DesignSnapshotInfo
import com.hienlh.ppm.state.design.DesignSummary
import com.hienlh.ppm.state.design.DesignSystemStaleInfo
import com.hienlh.ppm.state.design.DesignSystemSummary
import com.hienlh.ppm.state.design.DesignUndoStack
import com.hienlh.ppm.state.design.HandoffInput
import com.hienlh.ppm.state.design.HandoffSystemRef
import com.hienlh.ppm.state.design.PickedElement
import com.hienlh.ppm.state.design.PinRect
import com.hienlh.ppm.state.design.PreviewPhase
import com.hienlh.ppm.state.design.ProposalContext
import com.hienlh.ppm.state.design.ProposalDecision
import com.hienlh.ppm.state.design.TweakDef
import com.hienlh.ppm.state.design.TransformProposal
import com.hienlh.ppm.state.design.buildAddTweaksPrompt
import com.hienlh.ppm.state.design.buildCommentsPrompt
import com.hienlh.ppm.state.design.buildFixTweaksPrompt
import com.hienlh.ppm.state.design.buildHandoffPrompt
import com.hienlh.ppm.state.design.buildPptxArchive
import com.hienlh.ppm.state.design.canRespondToCanvasCheck
import com.hienlh.ppm.state.design.commentAnchorToJson
import com.hienlh.ppm.state.design.computeDeviceFrameScale
import com.hienlh.ppm.state.design.decideTransformProposal
import com.hienlh.ppm.state.design.fitCheckReportUnderLimit
import com.hienlh.ppm.state.design.hexForColorInput
import com.hienlh.ppm.state.design.initialDesignPane
import com.hienlh.ppm.state.design.nextAutoSplit
import com.hienlh.ppm.state.design.parseDesignComment
import com.hienlh.ppm.state.design.parseTweaks
import com.hienlh.ppm.state.design.pendingChanges
import com.hienlh.ppm.state.design.rangeNumberOf
import com.hienlh.ppm.state.design.recoverPreviewOnFailure
import com.hienlh.ppm.state.design.resolveDesignLayout
import com.hienlh.ppm.state.design.sanitizeTweakValue
import com.hienlh.ppm.state.design.shownValue
import com.hienlh.ppm.state.design.toPickedElement
import com.hienlh.ppm.state.design.toPinRect
import com.hienlh.ppm.state.design.variantDisplayName
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.web.FrameChannel
import com.hienlh.ppm.web.HtmlSurface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

// ===========================================================================
// 10b.1 · Designs Sidebar Pane (Designs list, Design Systems, Showcase, CRUD)
// ===========================================================================

@Composable
fun DesignsSidebarPane(
    model: AppModel,
    onNavigate: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val project = model.selectedProject
    val api = model.api

    var designs by remember(project) { mutableStateOf<List<DesignSummary>>(emptyList()) }
    var systems by remember(project) { mutableStateOf<List<DesignSystemSummary>>(emptyList()) }
    val staleBySystem = remember(project) { mutableStateMapOf<String, DesignSystemStaleInfo>() }
    var loading by remember(project) { mutableStateOf(false) }
    var errorMsg by remember(project) { mutableStateOf<String?>(null) }

    var showNewDesignDialog by remember { mutableStateOf(false) }
    var renamingDesign by remember { mutableStateOf<DesignSummary?>(null) }
    var deletingDesign by remember { mutableStateOf<DesignSummary?>(null) }
    var editingSystem by remember { mutableStateOf<DesignSystemSummary?>(null) }
    var showCreateSystemDialog by remember { mutableStateOf(false) }
    var deletingSystem by remember { mutableStateOf<DesignSystemSummary?>(null) }
    var systemsExpanded by remember { mutableStateOf(true) }

    fun reloadAll() {
        if (project == null || api == null) return
        scope.launch {
            loading = true
            errorMsg = null
            try {
                val rawDesigns = api.designList(project)
                val listArr = rawDesigns["designs"]?.array ?: rawDesigns.array.orEmpty()
                designs = listArr.mapNotNull { DesignSummary.fromJson(it) }

                val rawSys = runCatching { api.designListSystems(project) }.getOrNull()
                val sysArr = rawSys?.get("systems")?.array ?: rawSys?.array.orEmpty()
                val parsedSystems = sysArr.mapNotNull { DesignSystemSummary.fromJson(it) }
                systems = parsedSystems
                for (sys in parsedSystems) {
                    launch {
                        runCatching { api.designGetSystemStale(project, sys.id) }
                            .getOrNull()
                            ?.let { staleBySystem[sys.id] = DesignSystemStaleInfo.fromJson(it) }
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "Failed to load designs"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(project, api) {
        reloadAll()
    }
    // Created, renamed, deleted or restored anywhere (`design:list_changed`): read the list again.
    val events = model.events
    DisposableEffect(events, project, api) {
        val off = events?.onDesignListChanged { changed -> if (changed == project) reloadAll() }
        onDispose { off?.invoke() }
    }

    if (project == null) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                "Select a project to see its designs",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
            )
        }
        return
    }

    val mobile = LocalPpmMobile.current
    val headerButtonSize = if (mobile) 44.dp else 28.dp

    Column(Modifier.fillMaxSize().background(tokens.panel.color)) {
        // Header bar (matches designs-sidebar-panel.tsx)
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                "DESIGNS",
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(
                    color = tokens.text2.color,
                    letterSpacing = 0.3.sp,
                ),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier
                        .size(headerButtonSize)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Design settings" }
                        .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClickLabel = "Design settings") {
                            model.openSettings("design")
                            onNavigate()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.Settings, 16.dp, tokens.text3.color)
                }
                Box(
                    Modifier
                        .size(headerButtonSize)
                        .clip(RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "New design" }
                        .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, onClickLabel = "New design") {
                            showNewDesignDialog = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.Plus, 16.dp, tokens.text3.color)
                }
            }
        }

        val normalDesigns = remember(designs) { designs.filter { it.showcaseFor == null } }
        val showcaseDesigns = remember(designs) { designs.filter { it.showcaseFor != null } }

        if (errorMsg != null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                BasicText(
                    errorMsg!!,
                    Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                )
            }
        } else if (loading && designs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
                PpmIcon(ProductIcon.Loader2, 16.dp, tokens.accent.color, spinning = true)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                if (normalDesigns.isEmpty()) {
                    item(key = "empty-designs") {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(ProductIcon.Palette, 24.dp, tokens.text3.color)
                            BasicText(
                                "No designs yet. Start one and describe what you want; the AI builds it beside a live preview.",
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                            )
                            Box(
                                Modifier
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "New design") {
                                        showNewDesignDialog = true
                                    }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    "New design",
                                    style = ppmText(14f, lineHeight = 20f).copy(
                                        color = tokens.accent.color,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                )
                            }
                        }
                    }
                }

            items(normalDesigns, key = { "design:${it.slug}" }) { design ->
                DesignSidebarRow(
                    design = design,
                    isActive = model.activeTab?.id == "design:${design.slug}",
                    onOpen = {
                        model.openDesignTab(
                            slug = design.slug,
                            title = design.title,
                            projectName = project,
                            sessionId = design.sessionId,
                            providerId = design.providerId,
                            fresh = false,
                        )
                        onNavigate()
                    },
                    onRename = { renamingDesign = design },
                    onDelete = { deletingDesign = design },
                )
            }

            // Design Systems Section
            item(key = "systems-header") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .cssBorder(tokens.border.color, sides = CssSides.Top)
                        .ppmClickable(pressedColor = tokens.panel2.color) { systemsExpanded = !systemsExpanded }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(
                        if (systemsExpanded) ProductIcon.ChevronDown else ProductIcon.ChevronRight,
                        12.dp,
                        tokens.text3.color,
                    )
                    BasicText(
                        "DESIGN SYSTEMS (${systems.size})",
                        Modifier.weight(1f),
                        style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                    )
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .semantics { contentDescription = "Add Design System" }
                            .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Add Design System") {
                                showCreateSystemDialog = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(ProductIcon.Plus, 12.dp, tokens.text2.color)
                    }
                }
            }

            if (systemsExpanded) {
                items(systems, key = { "sys:${it.id}" }) { sys ->
                    val staleInfo = staleBySystem[sys.id]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.panel2.opacity(0.45).color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(ProductIcon.Layers, 13.dp, tokens.accent.color)
                            BasicText(
                                sys.label,
                                Modifier.weight(1f),
                                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BadgePill(sys.platform, tokens.text2)
                            if (staleInfo?.stale == true) {
                                BadgePill("Stale", tokens.warning)
                            }
                        }
                        if (sys.root.isNotBlank()) {
                            BasicText(
                                sys.root,
                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SmallActionChip(
                                label = if (sys.showcaseSlug != null) "Showcase" else "Build Showcase",
                                icon = ProductIcon.Sparkles,
                            ) {
                                if (api == null) return@SmallActionChip
                                scope.launch {
                                    runCatching {
                                        val res = api.designOpenSystemShowcase(project, sys.id)
                                        val designJson = res["design"] ?: res
                                        val summary = DesignSummary.fromJson(designJson)
                                        val created = res["created"].bool ?: false
                                        if (summary != null) {
                                            reloadAll()
                                            model.openDesignTab(
                                                slug = summary.slug,
                                                title = summary.title,
                                                projectName = project,
                                                sessionId = summary.sessionId,
                                                providerId = summary.providerId,
                                                fresh = created,
                                            )
                                            onNavigate()
                                        }
                                    }.onFailure {
                                        model.toast(ErrorToastMessage("Showcase failed", it.message))
                                    }
                                }
                            }
                            SmallActionChip(label = "Edit", icon = ProductIcon.Pencil) {
                                editingSystem = sys
                            }
                            SmallActionChip(label = "Delete", icon = ProductIcon.Trash2) {
                                deletingSystem = sys
                            }
                        }
                    }
                }

                if (showcaseDesigns.isNotEmpty()) {
                    item(key = "showcases-header") {
                        BasicText(
                            "SYSTEM SHOWCASES",
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                        )
                    }
                    items(showcaseDesigns, key = { "showcase:${it.slug}" }) { design ->
                        DesignSidebarRow(
                            design = design,
                            isActive = model.activeTab?.id == "design:${design.slug}",
                            onOpen = {
                                model.openDesignTab(
                                    slug = design.slug,
                                    title = design.title,
                                    projectName = project,
                                    sessionId = design.sessionId,
                                    providerId = design.providerId,
                                    fresh = false,
                                )
                                onNavigate()
                            },
                            onRename = { renamingDesign = design },
                            onDelete = { deletingDesign = design },
                        )
                    }
                }
            }
            }
        }
    }

    if (showNewDesignDialog && api != null) {
        NewDesignDialog(
            api = api,
            projectName = project,
            systems = systems,
            onDismiss = { showNewDesignDialog = false },
            onCreated = { created, chosenProvider ->
                showNewDesignDialog = false
                reloadAll()
                model.openDesignTab(
                    slug = created.slug,
                    title = created.title,
                    projectName = project,
                    sessionId = created.sessionId,
                    providerId = chosenProvider ?: created.providerId,
                    fresh = true,
                )
                onNavigate()
            },
        )
    }

    renamingDesign?.let { target ->
        RenameDesignDialog(
            design = target,
            onDismiss = { renamingDesign = null },
            onConfirm = { newTitle ->
                renamingDesign = null
                if (api != null) {
                    scope.launch {
                        runCatching { api.designRename(project, target.slug, newTitle) }
                            .onSuccess { reloadAll() }
                            .onFailure { model.toast(ErrorToastMessage("Rename failed", it.message)) }
                    }
                }
            },
        )
    }

    deletingDesign?.let { target ->
        DeleteDesignDialog(
            design = target,
            onDismiss = { deletingDesign = null },
            onConfirm = {
                deletingDesign = null
                if (api != null) {
                    scope.launch {
                        runCatching { api.designDelete(project, target.slug) }
                            .onSuccess {
                                model.closeTab("design:${target.slug}")
                                reloadAll()
                            }
                            .onFailure { model.toast(ErrorToastMessage("Delete failed", it.message)) }
                    }
                }
            },
        )
    }

    if (showCreateSystemDialog || editingSystem != null) {
        val current = editingSystem
        DesignSystemFormDialog(
            initial = current,
            onDismiss = {
                showCreateSystemDialog = false
                editingSystem = null
            },
            onSave = { label, platform, root ->
                showCreateSystemDialog = false
                editingSystem = null
                if (api != null) {
                    scope.launch {
                        runCatching {
                            if (current == null) {
                                api.designCreateSystem(project, label, platform, root)
                            } else {
                                api.designUpdateSystem(project, current.id, label, platform, root)
                            }
                        }.onSuccess { reloadAll() }
                            .onFailure { model.toast(ErrorToastMessage("System save failed", it.message)) }
                    }
                }
            },
        )
    }

    deletingSystem?.let { sys ->
        DeleteDesignSystemDialog(
            system = sys,
            onDismiss = { deletingSystem = null },
            onConfirm = { deleteFiles ->
                deletingSystem = null
                if (api != null) {
                    scope.launch {
                        runCatching { api.designDeleteSystem(project, sys.id, deleteFiles) }
                            .onSuccess { reloadAll() }
                            .onFailure { model.toast(ErrorToastMessage("Delete system failed", it.message)) }
                    }
                }
            },
        )
    }
}

@Composable
private fun DesignSidebarRow(
    design: DesignSummary,
    isActive: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isActive) tokens.accent.opacity(0.12).color else Color.Transparent)
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(
            if (design.kind == DesignKind.SLIDES) ProductIcon.Presentation else ProductIcon.Palette,
            15.dp,
            if (isActive) tokens.accent.color else tokens.text2.color,
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    design.title,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(
                        color = if (isActive) tokens.accent.color else tokens.text.color,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BadgePill(if (design.kind == DesignKind.SLIDES) "Slides" else "Page", tokens.text3)
                design.system?.let { sysId ->
                    BadgePill(sysId, tokens.accent)
                }
            }
            BasicText(
                buildString {
                    append(design.slug)
                    if (design.variants.size > 1) append(" · ${design.variants.size} variants")
                },
                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .semantics { contentDescription = "Rename ${design.title}" }
                .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Rename ${design.title}", onClick = onRename),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Pencil, 12.dp, tokens.text3.color)
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(5.dp))
                .semantics { contentDescription = "Delete ${design.title}" }
                .ppmClickable(pressedColor = tokens.panel2.color, onClickLabel = "Delete ${design.title}", onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Trash2, 12.dp, tokens.text3.color)
        }
    }
}

@Composable
private fun BadgePill(text: String, tone: Rgba) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .clip(shape)
            .background(tone.opacity(0.14).color)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = ppmText(9f, FontWeight.SemiBold, lineHeight = 12f).copy(color = tone.color))
    }
}

@Composable
private fun SmallActionChip(
    label: String,
    icon: ProductIcon,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val fg = when {
        !enabled -> tokens.text3.opacity(0.5)
        active -> tokens.accent
        else -> tokens.text2
    }
    Row(
        Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.14).color else tokens.bgSolid.color)
            .cssBorder(if (active) tokens.accent.color else tokens.border.color, shape)
            .semantics { contentDescription = label }
            .then(
                if (enabled) {
                    Modifier.ppmClickable(pressedColor = tokens.panel2.color, shape = shape, onClickLabel = label, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 11.dp, fg.color)
        BasicText(label, style = ppmText(10f, FontWeight.Medium, lineHeight = 13f).copy(color = fg.color))
    }
}

@Composable
internal fun NewDesignDialog(
    api: com.hienlh.ppm.client.ApiClient,
    projectName: String,
    systems: List<DesignSystemSummary>,
    onDismiss: () -> Unit,
    onCreated: (DesignSummary, String?) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val titleState = rememberTextFieldState("Untitled design")
    var kind by remember { mutableStateOf(DesignKind.PAGE) }
    var selectedSystem by remember { mutableStateOf<String?>(null) }
    var providers by remember { mutableStateOf(listOf(DesignProviderOption("claude", "Claude", true))) }
    var selectedProvider by remember { mutableStateOf("claude") }
    var submitting by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectName) {
        runCatching { api.designProviders(projectName) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { list ->
            providers = list
            if (list.none { it.id == selectedProvider }) {
                selectedProvider = list.first().id
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 300.dp, max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("New Design", style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color))

            // Title
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Title", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PpmTextField(titleState, "Design title", Modifier.fillMaxWidth())
                }
            }

            // Kind: Page vs Slides
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Kind", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, label) in listOf(DesignKind.PAGE to "Page", DesignKind.SLIDES to "Slides")) {
                        val active = kind == k
                        SmallActionChip(
                            label = label,
                            icon = if (k == DesignKind.SLIDES) ProductIcon.Presentation else ProductIcon.Palette,
                            active = active,
                        ) { kind = k }
                    }
                }
            }

            // Design System
            if (systems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Design System", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SmallActionChip(
                            label = "None",
                            icon = ProductIcon.Circle,
                            active = selectedSystem == null,
                        ) { selectedSystem = null }
                        for (sys in systems) {
                            SmallActionChip(
                                label = sys.label,
                                icon = ProductIcon.Layers,
                                active = selectedSystem == sys.id,
                            ) { selectedSystem = sys.id }
                        }
                    }
                }
            }

            // Provider (supportsDesignInstructions == true)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("AI Provider", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (prov in providers) {
                        SmallActionChip(
                            label = prov.name,
                            icon = ProductIcon.Sparkles,
                            active = selectedProvider == prov.id,
                        ) { selectedProvider = prov.id }
                    }
                }
            }

            if (err != null) {
                BasicText(err!!, style = ppmText(11f, lineHeight = 14f).copy(color = tokens.error.color))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton(if (submitting) "Creating…" else "Create", Modifier.weight(1f), enabled = !submitting) {
                    val title = titleState.text.toString().trim().ifEmpty { "Untitled design" }
                    submitting = true
                    err = null
                    scope.launch {
                        try {
                            val res = api.designCreate(projectName, title, kind.wire, selectedSystem)
                            val summary = DesignSummary.fromJson(res)
                                ?: DesignSummary(slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "design" }, title = title, kind = kind, system = selectedSystem)
                            onCreated(summary, selectedProvider)
                        } catch (e: Exception) {
                            err = e.message ?: "Failed to create design"
                            submitting = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDesignDialog(
    design: DesignSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val titleState = rememberTextFieldState(design.title)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("Rename Design", style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                PpmTextField(titleState, "Design title", Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("Save", Modifier.weight(1f)) {
                    val next = titleState.text.toString().trim()
                    if (next.isNotEmpty()) onConfirm(next)
                }
            }
        }
    }
}

@Composable
private fun DeleteDesignDialog(
    design: DesignSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("Delete \"${design.title}\"?", style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color))
            BasicText(
                "This removes `.ppm/designs/${design.slug}/` and its snapshots from disk.",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("Delete", Modifier.weight(1f), onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DesignSystemFormDialog(
    initial: DesignSystemSummary?,
    onDismiss: () -> Unit,
    onSave: (label: String, platform: String, root: String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val labelState = rememberTextFieldState(initial?.label ?: "")
    val rootState = rememberTextFieldState(initial?.root ?: "src/design-system")
    var platform by remember { mutableStateOf(initial?.platform ?: "web") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 300.dp, max = 400.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                if (initial == null) "Add Design System" else "Edit Design System",
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Label", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PpmTextField(labelState, "e.g. Acme UI", Modifier.fillMaxWidth())
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Platform", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in listOf("web", "mobile", "desktop")) {
                        SmallActionChip(label = p, icon = ProductIcon.Layers, active = platform == p) { platform = p }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Root folder", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PpmTextField(rootState, "src/design-system", Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("Save", Modifier.weight(1f)) {
                    val l = labelState.text.toString().trim()
                    val r = rootState.text.toString().trim()
                    if (l.isNotEmpty() && r.isNotEmpty()) onSave(l, platform, r)
                }
            }
        }
    }
}

@Composable
private fun DeleteDesignSystemDialog(
    system: DesignSystemSummary,
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var deleteFiles by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText("Delete \"${system.label}\"?", style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color))
            SmallActionChip(
                label = if (deleteFiles) "Also delete showcase files (On)" else "Also delete showcase files (Off)",
                icon = ProductIcon.Trash2,
                active = deleteFiles,
            ) { deleteFiles = !deleteFiles }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("Delete", Modifier.weight(1f)) { onConfirm(deleteFiles) }
            }
        }
    }
}

// ===========================================================================
// 10b.1 – 10b.4 · DesignTabBody (Tab-width Layout, Canvas, Tools, Check, Export)
// ===========================================================================

enum class DesignSidePanel {
    NONE,
    COMMENTS,
    TWEAKS,
    HISTORY,
    EXPORT,
}

@Composable
fun DesignTabBody(
    model: AppModel,
    tab: WorkspaceTab,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = model.api
    val slug = tab.metaString("designSlug") ?: tab.id.removePrefix("design:")
    val projectName = tab.metaString("projectName") ?: tab.projectId ?: model.selectedProject ?: ""
    val freshOnOpen = tab.metadata["fresh"]?.bool == true

    // Design detail & metadata state
    var detail by remember(projectName, slug) {
        mutableStateOf(
            DesignDetail(
                summary = DesignSummary(
                    slug = slug,
                    title = tab.title.ifBlank { slug },
                    hasIndex = !freshOnOpen,
                ),
                files = listOf("index.html"),
            ),
        )
    }
    var activeVariant by remember(projectName, slug) { mutableStateOf("index.html") }
    var deviceFrame by remember(projectName, slug) { mutableStateOf(DesignDeviceFrame.DESKTOP) }
    var zoom by remember(projectName, slug) { mutableFloatStateOf(1f) }
    var shareOpen by remember(projectName, slug) { mutableStateOf(false) }

    // Layout mode driven by TAB's own width (not window width!)
    var layoutOverride by remember(tab.id) { mutableStateOf(DesignLayoutOverride.AUTO) }
    var autoSplit by remember(tab.id) { mutableStateOf(false) }
    var singlePane by remember(tab.id) {
        mutableStateOf(initialDesignPane(fresh = freshOnOpen, hasIndex = detail.summary.hasIndex))
    }
    var splitChatFraction by remember(tab.id) { mutableFloatStateOf(0.38f) }

    // Preview capability & bridge state
    var previewUrl by remember(projectName, slug, activeVariant) { mutableStateOf<String?>(null) }
    var previewToken by remember(projectName, slug) { mutableStateOf<String?>(null) }
    var bridgeNonce by remember(projectName, slug, activeVariant) { mutableStateOf(newBridgeNonce()) }
    var previewPhase by remember(projectName, slug, activeVariant) { mutableStateOf(PreviewPhase.LOADING) }
    var hasRecoveredOnce by remember(projectName, slug, activeVariant) { mutableStateOf(false) }
    var reloadNonce by remember(projectName, slug) { mutableIntStateOf(0) }
    var frameChannel by remember(projectName, slug) { mutableStateOf<FrameChannel?>(null) }
    var canvasWidthPx by remember(projectName, slug) { mutableIntStateOf(0) }
    var canvasHeightPx by remember(projectName, slug) { mutableIntStateOf(0) }
    var savedScrollX by remember(projectName, slug) { mutableStateOf(0.0) }
    var savedScrollY by remember(projectName, slug) { mutableStateOf(0.0) }
    val fileGens = remember(projectName, slug) { mutableStateMapOf<String, String>() }

    // Runtime issues & blocked navigation
    val runtimeIssues = remember(projectName, slug, activeVariant) { mutableStateListOf<RuntimeIssue>() }
    var blockedNavHref by remember(projectName, slug, activeVariant) { mutableStateOf<String?>(null) }
    var lastCheckSummary by remember(projectName, slug, activeVariant) { mutableStateOf<String?>(null) }

    // Picker & Comments state
    var pickerEnabled by remember(projectName, slug) { mutableStateOf(false) }
    var pickedElement by remember(projectName, slug) { mutableStateOf<PickedElement?>(null) }
    var comments by remember(projectName, slug) { mutableStateOf<List<DesignComment>>(emptyList()) }
    val pinRects = remember(projectName, slug) { mutableStateMapOf<String, PinRect>() }

    // Tweaks state
    var tweakDefs by remember(projectName, slug) { mutableStateOf<List<TweakDef>>(emptyList()) }
    var tweakErrors by remember(projectName, slug) { mutableStateOf<List<String>>(emptyList()) }
    val savedTweakValues = remember(projectName, slug) { mutableStateMapOf<String, String>() }
    val liveTweakValues = remember(projectName, slug) { mutableStateMapOf<String, String>() }
    val currentTweakValues = remember(projectName, slug) { mutableStateMapOf<String, String>() }

    // Transform (Move / Resize) & Undo state
    var transformModeOn by remember(projectName, slug) { mutableStateOf(false) }
    var armedPpmId by remember(projectName, slug) { mutableStateOf<Int?>(null) }
    var armedTag by remember(projectName, slug) { mutableStateOf<String?>(null) }
    var lastParentGestureAt by remember(projectName, slug) { mutableStateOf(0L) }
    var styleEditInFlight by remember(projectName, slug) { mutableStateOf(false) }
    val undoStack = remember(projectName, slug) { DesignUndoStack() }
    var undoCount by remember(projectName, slug) { mutableIntStateOf(0) }

    // History snapshots
    var snapshots by remember(projectName, slug) { mutableStateOf<List<DesignSnapshotInfo>>(emptyList()) }
    var confirmingRestore by remember { mutableStateOf<DesignSnapshotInfo?>(null) }

    // Active tool side-panel / sheet
    var sidePanel by remember(tab.id) { mutableStateOf(DesignSidePanel.NONE) }

    // Pending canvas check continuations (requestId -> CompletableDeferred)
    val pendingCheckScreenshots = remember(projectName, slug) {
        mutableStateMapOf<String, CompletableDeferred<String?>>()
    }

    // Chat state for this design tab
    val chat = model.chat(tab.id)
    val runningSessions = model.events?.running?.collectAsState()?.value.orEmpty()
    val sessionId = chat?.session?.id ?: tab.metaString("sessionId")
    val isStreaming = sessionId != null && sessionId in runningSessions

    fun syncUndoCount() {
        undoCount = undoStack.size
    }

    fun loadDesignDetailAndTools() {
        if (api == null || projectName.isBlank()) return
        scope.launch {
            runCatching { api.designGet(projectName, slug) }.getOrNull()?.let { json ->
                DesignDetail.fromJson(json)?.let { d ->
                    detail = d
                    if (d.summary.variants.isNotEmpty() && d.summary.variants.none { it.file == activeVariant }) {
                        activeVariant = d.summary.variants.first().file
                    }
                }
            }
            runCatching { api.designListComments(projectName, slug) }.getOrNull()?.let { json ->
                val arr = json["comments"]?.array ?: json.array.orEmpty()
                comments = arr.mapIndexedNotNull { idx, item -> parseDesignComment(item, fallbackNumber = idx + 1) }
            }
            runCatching { api.designReadTweaks(projectName, slug) }.getOrNull()?.let { json ->
                val parsed = parseTweaks(json["tweaks"])
                tweakDefs = parsed.tweaks
                tweakErrors = parsed.errors
                savedTweakValues.clear()
                json["values"]?.obj?.forEach { (k, v) -> v.string?.let { savedTweakValues[k] = it } }
                json["gens"]?.obj?.forEach { (k, v) -> v.string?.let { fileGens[k] = it } }
            }
            runCatching { api.designListHistory(projectName, slug) }.getOrNull()?.let { json ->
                val arr = json["snapshots"]?.array ?: json.array.orEmpty()
                snapshots = arr.mapNotNull { DesignSnapshotInfo.fromJson(it) }
            }
        }
    }

    fun mintAndLoadPreview(resetRecovery: Boolean = false) {
        if (api == null || projectName.isBlank()) return
        if (resetRecovery) {
            hasRecoveredOnce = false
            previewToken = null
        }
        previewPhase = PreviewPhase.LOADING
        bridgeNonce = newBridgeNonce()
        runtimeIssues.clear()
        blockedNavHref = null
        scope.launch {
            val outcome = loadPreviewWithSelfHeal(
                api = api,
                projectName = projectName,
                slug = slug,
                entry = activeVariant,
                initialToken = previewToken,
                initialRecoveredOnce = hasRecoveredOnce,
                retryDelayMs = 400L,
            )
            previewToken = outcome.token
            hasRecoveredOnce = outcome.hasRecoveredOnce
            if (outcome.phase == PreviewPhase.DEAD) {
                previewPhase = PreviewPhase.DEAD
            } else {
                previewUrl = outcome.url
                reloadNonce++
                if (!outcome.gen.isNullOrBlank()) {
                    fileGens[activeVariant] = outcome.gen
                }
            }
        }
    }

    // Initial load & 10-minute capability token refresh loop
    LaunchedEffect(projectName, slug, activeVariant, api) {
        loadDesignDetailAndTools()
        mintAndLoadPreview(resetRecovery = true)
        while (isActive) {
            delay(10 * 60_000L)
            if (api != null && projectName.isNotBlank()) {
                runCatching {
                    val cap = api.designMintPreview(
                        projectName = projectName,
                        slug = slug,
                        purpose = "canvas",
                        token = previewToken,
                        entry = activeVariant,
                    )
                    previewToken = cap.token
                }
            }
        }
    }

    // Auto-check after a design chat turn finishes
    var prevStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (prevStreaming && !isStreaming) {
            loadDesignDetailAndTools()
            mintAndLoadPreview(resetRecovery = true)
            delay(600)
            if (previewPhase == PreviewPhase.READY) {
                val reqId = "post-turn-${newBridgeNonce()}"
                checkRunEnvelope(reqId, screenshot = false)?.let { frameChannel?.post(it) }
            }
        }
        prevStreaming = isStreaming
    }

    // Subscribe to GlobalEvents: file:changed (300ms debounce), design:history_changed, design:comments_changed, design:check_request
    DisposableEffect(model.events, projectName, slug, activeVariant) {
        val ev = model.events ?: return@DisposableEffect onDispose {}
        var reloadJob: Job? = null
        val prevFileChanged = ev.onFileChanged
        ev.onFileChanged = { proj, path ->
            prevFileChanged?.invoke(proj, path)
            if (proj == projectName && (path == null || path.contains("designs/$slug"))) {
                reloadJob?.cancel()
                reloadJob = scope.launch {
                    delay(300)
                    loadDesignDetailAndTools()
                    mintAndLoadPreview(resetRecovery = true)
                }
            }
        }
        val offHistory = ev.onDesignHistoryChanged { proj, s ->
            if (proj == projectName && s == slug) {
                scope.launch {
                    runCatching { api?.designListHistory(projectName, slug) }.getOrNull()?.let { json ->
                        val arr = json["snapshots"]?.array ?: json.array.orEmpty()
                        snapshots = arr.mapNotNull { DesignSnapshotInfo.fromJson(it) }
                    }
                }
            }
        }
        val offComments = ev.onDesignCommentsChanged { proj, s ->
            if (proj == projectName && s == slug) {
                scope.launch {
                    runCatching { api?.designListComments(projectName, slug) }.getOrNull()?.let { json ->
                        val arr = json["comments"]?.array ?: json.array.orEmpty()
                        comments = arr.mapIndexedNotNull { idx, item -> parseDesignComment(item, fallbackNumber = idx + 1) }
                    }
                }
            }
        }
        val offCheck = ev.onDesignCheckRequest { proj, s, reqId, wantScreenshot ->
            if (proj == projectName && s == slug) {
                val resolution = resolveDesignLayout(layoutOverride, autoSplit, singlePane)
                val isCanvasShown = resolution.mode == "split" || resolution.activePane == DesignPane.CANVAS
                // Guard: parked/hidden tab or 0x0 canvas NEVER responds to check request!
                if (canRespondToCanvasCheck(isCanvasShown, canvasWidthPx, canvasHeightPx, previewPhase)) {
                    val effectiveId = reqId ?: newBridgeNonce()
                    checkRunEnvelope(effectiveId, wantScreenshot)?.let { frameChannel?.post(it) }
                }
            }
        }
        onDispose {
            reloadJob?.cancel()
            ev.onFileChanged = prevFileChanged
            offHistory()
            offComments()
            offCheck()
        }
    }

    // Send updated comment pins whenever comments or preview readiness changes
    LaunchedEffect(comments, activeVariant, previewPhase) {
        if (previewPhase == PreviewPhase.READY) {
            val forFile = comments.filter { it.file == activeVariant }
            pinsSetEnvelope(forFile.map { it.toBridgePinTarget() })?.let { frameChannel?.post(it) }
        }
    }

    var pendingSafExport by remember { mutableStateOf<PendingDesignExport?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        val pending = pendingSafExport
        pendingSafExport = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            completeDesignSafExport(
                resolver = context.contentResolver,
                uri = uri,
                export = pending,
                api = api,
                onToast = model::toast,
            )
        }
    }

    // Handle incoming bridge child messages
    fun handleBridgeMessage(raw: kotlinx.serialization.json.JsonElement) {
        val msg = BridgeChildMessage.parseForNonce(raw, bridgeNonce) ?: return
        when (msg) {
            is BridgeChildMessage.Ready -> {
                previewPhase = PreviewPhase.READY
                hasRecoveredOnce = false
                fileGens[msg.file] = msg.gen
                fileGens.putAll(msg.cssGens)
                if (savedScrollX != 0.0 || savedScrollY != 0.0) {
                    restoreScrollEnvelope(savedScrollX, savedScrollY)?.let { frameChannel?.post(it) }
                }
                val forFile = comments.filter { it.file == activeVariant }
                if (forFile.isNotEmpty()) {
                    pinsSetEnvelope(forFile.map { it.toBridgePinTarget() })?.let { frameChannel?.post(it) }
                }
                if (tweakDefs.isNotEmpty()) {
                    tweakReadEnvelope(tweakDefs.map { it.variable })?.let { frameChannel?.post(it) }
                    // Replay unapplied pending tweak edits after reload
                    val pending = pendingChanges(tweakDefs, savedTweakValues, currentTweakValues)
                    if (pending.isNotEmpty()) {
                        tweakSetEnvelope(pending)?.let { frameChannel?.post(it) }
                    }
                }
            }
            is BridgeChildMessage.Scroll -> {
                savedScrollX = msg.x
                savedScrollY = msg.y
            }
            is BridgeChildMessage.Issue -> {
                if (runtimeIssues.size < MAX_BRIDGE_ISSUES) {
                    runtimeIssues.add(RuntimeIssue(msg.kind, msg.message, msg.source, msg.line))
                }
            }
            is BridgeChildMessage.NavigateBlocked -> {
                blockedNavHref = msg.href
            }
            is BridgeChildMessage.OpenLink -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(msg.href)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            is BridgeChildMessage.Expired -> {
                val recovery = recoverPreviewOnFailure(previewToken, hasRecoveredOnce)
                hasRecoveredOnce = recovery.hasRecoveredOnce
                previewToken = recovery.nextToken
                if (recovery.phase == PreviewPhase.RECOVERING) {
                    mintAndLoadPreview(resetRecovery = false)
                } else {
                    previewPhase = PreviewPhase.DEAD
                }
            }
            is BridgeChildMessage.Hover -> Unit
            is BridgeChildMessage.Select -> {
                val picked = msg.el.toPickedElement()
                pickedElement = picked
                if (transformModeOn && picked.ppmId != null) {
                    armedPpmId = picked.ppmId
                    armedTag = picked.tag
                    lastParentGestureAt = System.currentTimeMillis()
                    transformTargetEnvelope(picked.ppmId, picked.tag)?.let { frameChannel?.post(it) }
                }
            }
            is BridgeChildMessage.ElementMenu -> {
                pickedElement = msg.el.toPickedElement()
                sidePanel = DesignSidePanel.COMMENTS
            }
            is BridgeChildMessage.PickerExit -> {
                pickerEnabled = false
            }
            is BridgeChildMessage.PinsRects -> {
                pinRects.clear()
                for (p in msg.pins) pinRects[p.id] = p.toPinRect()
            }
            is BridgeChildMessage.TweakValues -> {
                liveTweakValues.clear()
                liveTweakValues.putAll(msg.values)
            }
            is BridgeChildMessage.TransformLive -> Unit
            is BridgeChildMessage.TransformCommit -> {
                val proposal = TransformProposal(
                    ppmId = msg.ppmId,
                    tag = msg.tag,
                    file = msg.file,
                    gen = msg.gen,
                    props = msg.props,
                )
                val ctx = ProposalContext(
                    transformModeOn = transformModeOn,
                    armedPpmId = armedPpmId,
                    armedTag = armedTag,
                    lastParentGestureAt = lastParentGestureAt,
                    now = System.currentTimeMillis(),
                    file = activeVariant,
                    gen = fileGens[activeVariant],
                    isStreaming = isStreaming,
                    inFlight = styleEditInFlight,
                )
                when (val decision = decideTransformProposal(proposal, ctx)) {
                    is ProposalDecision.Accept -> {
                        if (api != null) {
                            styleEditInFlight = true
                            scope.launch {
                                try {
                                    when (
                                        val res = api.designApplyStyle(
                                            projectName = projectName,
                                            slug = slug,
                                            file = decision.file,
                                            gen = decision.gen,
                                            ppmId = decision.ppmId,
                                            tag = decision.tag,
                                            props = decision.props,
                                        )
                                    ) {
                                        is DesignStyleEditResult.Applied -> {
                                            fileGens[decision.file] = res.gen
                                            undoStack.push(res.undoId)
                                            syncUndoCount()
                                        }
                                        is DesignStyleEditResult.Rejected -> {
                                            model.toast(ErrorToastMessage("Style edit rejected", res.message))
                                            if (res.reason == "stale" || res.reason == "element-moved") {
                                                mintAndLoadPreview(resetRecovery = true)
                                            }
                                        }
                                    }
                                } finally {
                                    styleEditInFlight = false
                                }
                            }
                        }
                    }
                    is ProposalDecision.Reject -> Unit
                }
            }
            is BridgeChildMessage.SlidesData -> {
                scope.launch {
                    try {
                        val pptx = withContext(Dispatchers.Default) {
                            buildPptxArchive(msg.doc, detail.summary.title)
                        }
                        val filename = filenameFromContentDisposition(null, "$slug.pptx")
                        pendingSafExport = PendingDesignExport.Pptx(
                            suggestedFilename = filename,
                            bytes = pptx.bytes,
                            slideCount = msg.doc.slides.size,
                            warnings = pptx.warnings,
                        )
                        createDocumentLauncher.launch(filename)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        model.toast(ErrorToastMessage("Slides export failed", e.message))
                    }
                }
            }
            is BridgeChildMessage.SlidesError -> {
                model.toast(ErrorToastMessage("Slides export failed", msg.message))
            }
            is BridgeChildMessage.CheckResult -> {
                pendingCheckScreenshots.remove(msg.requestId)?.complete(msg.report.screenshot?.dataUrl)
                val layoutWithIssues = msg.report.withRuntimeIssues(runtimeIssues.toList())
                val fullReport = CanvasCheckReport(
                    layout = layoutWithIssues,
                    file = activeVariant,
                    gen = fileGens[activeVariant],
                    frame = deviceFrame.label,
                )
                val issueCount = layoutWithIssues.findings.size
                lastCheckSummary = if (issueCount == 0) "Check clean" else "$issueCount issue(s) detected"
                if (api != null && !msg.requestId.startsWith("handoff-") && !msg.requestId.startsWith("post-turn-")) {
                    val fitted = fitCheckReportUnderLimit(fullReport.json)
                    scope.launch {
                        api.designSubmitCheckResult(projectName, slug, msg.requestId, fitted)
                    }
                }
            }
            is BridgeChildMessage.CheckError -> {
                pendingCheckScreenshots.remove(msg.requestId)?.complete(null)
                if (api != null && !msg.requestId.startsWith("handoff-") && !msg.requestId.startsWith("post-turn-")) {
                    val vp = CheckSize(canvasWidthPx.coerceAtLeast(1), canvasHeightPx.coerceAtLeast(1))
                    val fallback = LayoutCheckReport(
                        viewport = vp,
                        page = vp,
                        findings = emptyList(),
                        counts = emptyMap(),
                    ).withRuntimeIssues(runtimeIssues.toList())
                    val fullReport = CanvasCheckReport(
                        layout = fallback,
                        file = activeVariant,
                        gen = fileGens[activeVariant],
                        frame = deviceFrame.label,
                    )
                    scope.launch {
                        api.designSubmitCheckResult(projectName, slug, msg.requestId, fullReport.json)
                    }
                }
            }
        }
    }

    // "Build in new chat" handoff action
    fun triggerBuildInNewChat() {
        scope.launch {
            var screenshot: String? = null
            if (previewPhase == PreviewPhase.READY && frameChannel != null) {
                val reqId = "handoff-${newBridgeNonce()}"
                val deferred = CompletableDeferred<String?>()
                pendingCheckScreenshots[reqId] = deferred
                checkRunEnvelope(reqId, screenshot = true)?.let { frameChannel?.post(it) }
                screenshot = withTimeoutOrNull(3_000L) { deferred.await() }
                pendingCheckScreenshots.remove(reqId)
            }
            val prompt = buildHandoffPrompt(
                HandoffInput(
                    slug = slug,
                    title = detail.summary.title,
                    entryFile = activeVariant,
                    files = detail.files,
                    system = detail.summary.system?.let { sysId ->
                        HandoffSystemRef(id = sysId, label = sysId, platform = "web", root = "")
                    },
                ),
            )
            model.openBuildInNewChatTab(
                projectName = projectName,
                title = detail.summary.title,
                prompt = prompt,
                screenshotDataUrl = screenshot,
                screenshotFilename = "$slug.jpg",
            )
            if (screenshot == null) {
                model.toast(
                    ErrorToastMessage(
                        "Opened without a screenshot",
                        "The handoff chat was opened with the design file references.",
                        kind = ErrorToastMessage.Kind.INFO,
                    ),
                )
            }
        }
    }

    // The canvas toolbar's actions, shared by the tablet's bar and the phone's More sheet.
    fun togglePicker() {
        val next = !pickerEnabled
        pickerEnabled = next
        if (next) transformModeOn = false
        frameChannel?.post(pickerEnvelope(next))
    }

    fun toggleTransform() {
        val next = !transformModeOn
        transformModeOn = next
        if (next) pickerEnabled = false
        lastParentGestureAt = System.currentTimeMillis()
        transformModeEnvelope(next, scale = zoom.toDouble())?.let { frameChannel?.post(it) }
    }

    fun undoLastEdit() {
        val undoId = undoStack.pop() ?: return
        syncUndoCount()
        if (api != null) {
            scope.launch {
                when (val res = api.designUndoEdit(projectName, slug, undoId)) {
                    is DesignUndoEditResult.Undone -> {
                        fileGens[activeVariant] = res.gen
                        mintAndLoadPreview(resetRecovery = true)
                    }
                    is DesignUndoEditResult.Rejected -> {
                        model.toast(ErrorToastMessage("Cannot undo", res.message))
                    }
                }
            }
        }
    }

    // A phone has no toolbar: its items are the More sheet's rows (`DesignToolbarList`).
    val mobile = LocalPpmMobile.current
    var moreOpen by remember(tab.id) { mutableStateOf(false) }

    // Measure the TAB's own width (not window width!) via BoxWithConstraints
    BoxWithConstraints(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        val tabWidthDp = maxWidth.value.roundToInt()
        LaunchedEffect(tabWidthDp) {
            autoSplit = nextAutoSplit(tabWidthDp, autoSplit)
        }
        val resolution = resolveDesignLayout(layoutOverride, autoSplit, singlePane)

        Column(Modifier.fillMaxSize()) {
            // Top Design Header & Toolbar
            if (!mobile) DesignHeaderToolbar(
                detail = detail,
                activeVariant = activeVariant,
                onSelectVariant = { activeVariant = it },
                deviceFrame = deviceFrame,
                onSelectDeviceFrame = { deviceFrame = it },
                zoom = zoom,
                onZoomChange = { zoom = it.coerceIn(0.5f, 2.0f) },
                layoutOverride = layoutOverride,
                onSelectLayoutOverride = {
                    layoutOverride = it
                    when (it) {
                        DesignLayoutOverride.CANVAS -> singlePane = DesignPane.CANVAS
                        DesignLayoutOverride.CHAT -> singlePane = DesignPane.CHAT
                        else -> Unit
                    }
                },
                pickerEnabled = pickerEnabled,
                onTogglePicker = ::togglePicker,
                transformModeOn = transformModeOn,
                onToggleTransform = ::toggleTransform,
                undoCount = undoCount,
                isStreaming = isStreaming,
                onUndo = ::undoLastEdit,
                openCommentsCount = comments.count { !it.resolved && it.file == activeVariant },
                sidePanel = sidePanel,
                onToggleSidePanel = { target ->
                    sidePanel = if (sidePanel == target) DesignSidePanel.NONE else target
                },
                onBuildInNewChat = ::triggerBuildInNewChat,
                onReload = { mintAndLoadPreview(resetRecovery = true) },
                shareOpen = shareOpen,
                onShare = { shareOpen = true },
            )
            if (shareOpen) {
                DesignShareDialog(
                    api = api,
                    events = model.events,
                    projectName = projectName,
                    slug = slug,
                    localOrigin = api?.baseUrl?.toString().orEmpty(),
                    onDismiss = { shareOpen = false },
                    onToast = model::toast,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    onOpenSettings = { category ->
                        shareOpen = false
                        model.openSettings(category)
                    },
                )
            }

            if (mobile && moreOpen) {
                fun toggle(panel: DesignSidePanel) {
                    sidePanel = if (sidePanel == panel) DesignSidePanel.NONE else panel
                }
                DesignPhoneMoreSheet(
                    frame = deviceFrame,
                    onFrame = { deviceFrame = it },
                    variants = detail.summary.variants,
                    activeVariant = activeVariant,
                    onVariant = { activeVariant = it },
                    items = listOf(
                        DesignMoreItem("reload", "Reload canvas", ProductIcon.RefreshCw) { mintAndLoadPreview(resetRecovery = true) },
                        DesignMoreItem("history", "Version history", ProductIcon.History, active = sidePanel == DesignSidePanel.HISTORY) {
                            toggle(DesignSidePanel.HISTORY)
                        },
                        DesignMoreItem("select", "Select element", ProductIcon.MousePointerClick, active = pickerEnabled) { togglePicker() },
                        DesignMoreItem(
                            "comments",
                            "Comments",
                            ProductIcon.MessageSquarePlus,
                            active = sidePanel == DesignSidePanel.COMMENTS,
                            badge = comments.count { !it.resolved && it.file == activeVariant },
                        ) { toggle(DesignSidePanel.COMMENTS) },
                        DesignMoreItem("share", "Share for review", ProductIcon.Share2, active = shareOpen) { shareOpen = true },
                        DesignMoreItem("tweaks", "Tweaks", ProductIcon.SlidersHorizontal, active = sidePanel == DesignSidePanel.TWEAKS) {
                            toggle(DesignSidePanel.TWEAKS)
                        },
                        DesignMoreItem("move", "Move and resize", ProductIcon.Move, active = transformModeOn) { toggleTransform() },
                        DesignMoreItem("undo", "Undo canvas edit", ProductIcon.Undo2, enabled = undoCount > 0 && !isStreaming) { undoLastEdit() },
                        DesignMoreItem("export", "Export", ProductIcon.Download, active = sidePanel == DesignSidePanel.EXPORT) {
                            toggle(DesignSidePanel.EXPORT)
                        },
                        DesignMoreItem("build-in-new-chat", "Build in new chat", ProductIcon.Send) { triggerBuildInNewChat() },
                    ),
                    onDismiss = { moreOpen = false },
                )
            }

            // Main content area: Split (Canvas left + Chat right <= 50%) or Single pane
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (resolution.mode == "split") {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f - splitChatFraction).fillMaxHeight()) {
                            DesignCanvasArea(
                                model = model,
                                slug = slug,
                                activeVariant = activeVariant,
                                previewUrl = previewUrl,
                                bridgeNonce = bridgeNonce,
                                reloadNonce = reloadNonce,
                                previewPhase = previewPhase,
                                deviceFrame = deviceFrame,
                                zoom = zoom,
                                runtimeIssues = runtimeIssues,
                                blockedNavHref = blockedNavHref,
                                lastCheckSummary = lastCheckSummary,
                                pickedElement = pickedElement,
                                comments = comments.filter { it.file == activeVariant },
                                pinRects = pinRects,
                                transformModeOn = transformModeOn,
                                onDismissBlockedNav = { blockedNavHref = null },
                                onSelectParent = {
                                    lastParentGestureAt = System.currentTimeMillis()
                                    frameChannel?.post(selectParentEnvelope())
                                },
                                onClearSelection = {
                                    pickedElement = null
                                    armedPpmId = null
                                    frameChannel?.post(clearSelectionEnvelope())
                                },
                                onOpenAddComment = { sidePanel = DesignSidePanel.COMMENTS },
                                onNudge = { dx, dy ->
                                    lastParentGestureAt = System.currentTimeMillis()
                                    transformNudgeEnvelope(dx, dy)?.let { frameChannel?.post(it) }
                                },
                                onCancelTransform = {
                                    lastParentGestureAt = System.currentTimeMillis()
                                    frameChannel?.post(transformCancelEnvelope())
                                },
                                onManualReload = { mintAndLoadPreview(resetRecovery = true) },
                                onCanvasMeasured = { w, h ->
                                    canvasWidthPx = w
                                    canvasHeightPx = h
                                },
                                onBridgeMessage = ::handleBridgeMessage,
                                onChannelReady = { frameChannel = it },
                            )
                        }
                        // Resizable split divider (keeps chat pane <= 50% width)
                        Box(
                            Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .background(tokens.border.color)
                                .pointerInput(tabWidthDp) {
                                    detectHorizontalDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (size.width > 0 && tabWidthDp > 0) {
                                            val deltaFrac = -dragAmount / (tabWidthDp * density)
                                            splitChatFraction = (splitChatFraction + deltaFrac).coerceIn(0.25f, 0.50f)
                                        }
                                    }
                                },
                        )
                        Box(Modifier.weight(splitChatFraction).fillMaxHeight()) {
                            DesignChatPaneContainer(model = model, chat = chat, slug = slug)
                        }
                    }
                } else {
                    // Single pane mode
                    if (resolution.activePane == DesignPane.CANVAS) {
                        DesignCanvasArea(
                            model = model,
                            slug = slug,
                            activeVariant = activeVariant,
                            previewUrl = previewUrl,
                            bridgeNonce = bridgeNonce,
                            reloadNonce = reloadNonce,
                            previewPhase = previewPhase,
                            deviceFrame = deviceFrame,
                            zoom = zoom,
                            runtimeIssues = runtimeIssues,
                            blockedNavHref = blockedNavHref,
                            lastCheckSummary = lastCheckSummary,
                            pickedElement = pickedElement,
                            comments = comments.filter { it.file == activeVariant },
                            pinRects = pinRects,
                            transformModeOn = transformModeOn,
                            onDismissBlockedNav = { blockedNavHref = null },
                            onSelectParent = {
                                lastParentGestureAt = System.currentTimeMillis()
                                frameChannel?.post(selectParentEnvelope())
                            },
                            onClearSelection = {
                                pickedElement = null
                                armedPpmId = null
                                frameChannel?.post(clearSelectionEnvelope())
                            },
                            onOpenAddComment = { sidePanel = DesignSidePanel.COMMENTS },
                            onNudge = { dx, dy ->
                                lastParentGestureAt = System.currentTimeMillis()
                                transformNudgeEnvelope(dx, dy)?.let { frameChannel?.post(it) }
                            },
                            onCancelTransform = {
                                lastParentGestureAt = System.currentTimeMillis()
                                frameChannel?.post(transformCancelEnvelope())
                            },
                            onManualReload = { mintAndLoadPreview(resetRecovery = true) },
                            onCanvasMeasured = { w, h ->
                                canvasWidthPx = w
                                canvasHeightPx = h
                            },
                            onBridgeMessage = ::handleBridgeMessage,
                            onChannelReady = { frameChannel = it },
                        )
                    } else {
                        DesignChatPaneContainer(model = model, chat = chat, slug = slug)
                    }
                }

                // Side Panel / Sheet Overlay for Comments, Tweaks, History, Export
                if (sidePanel != DesignSidePanel.NONE) {
                    val panelBody: @Composable () -> Unit = {
                        when (sidePanel) {
                            DesignSidePanel.COMMENTS -> DesignCommentsPanel(
                                slug = slug,
                                activeVariant = activeVariant,
                                pickedElement = pickedElement,
                                comments = comments.filter { it.file == activeVariant },
                                pinRects = pinRects,
                                onClose = { sidePanel = DesignSidePanel.NONE },
                                onAddComment = { body ->
                                    val el = pickedElement ?: return@DesignCommentsPanel
                                    val anchor = CommentAnchor(
                                        file = el.file.ifBlank { activeVariant },
                                        ppmId = el.ppmId,
                                        gen = el.gen ?: fileGens[activeVariant],
                                        tag = el.tag,
                                        cssPath = el.cssPath,
                                        quote = el.quote,
                                    )
                                    if (api != null) {
                                        scope.launch {
                                            runCatching {
                                                api.designCreateComment(
                                                    projectName = projectName,
                                                    slug = slug,
                                                    file = activeVariant,
                                                    anchor = commentAnchorToJson(anchor),
                                                    body = body,
                                                )
                                            }.onSuccess { loadDesignDetailAndTools() }
                                                .onFailure { model.toast(ErrorToastMessage("Comment failed", it.message)) }
                                        }
                                    }
                                },
                                onToggleResolve = { comment ->
                                    if (api != null) {
                                        scope.launch {
                                            runCatching {
                                                api.designUpdateComment(
                                                    projectName = projectName,
                                                    slug = slug,
                                                    id = comment.id,
                                                    resolved = !comment.resolved,
                                                )
                                            }.onSuccess { loadDesignDetailAndTools() }
                                        }
                                    }
                                },
                                onDeleteComment = { comment ->
                                    if (api != null) {
                                        scope.launch {
                                            runCatching {
                                                api.designDeleteComment(projectName, slug, comment.id)
                                            }.onSuccess { loadDesignDetailAndTools() }
                                        }
                                    }
                                },
                                onReplyComment = { comment, replyBody ->
                                    if (api != null) {
                                        scope.launch {
                                            runCatching {
                                                api.designReplyComment(projectName, slug, comment.id, replyBody)
                                            }.onSuccess { loadDesignDetailAndTools() }
                                        }
                                    }
                                },
                                onSendCommentsToAi = {
                                    val prompt = buildCommentsPrompt(slug, comments, activeVariant)
                                    if (prompt.isNotBlank()) {
                                        chat?.draft?.setTextAndPlaceCursorAtEnd(prompt)
                                        sidePanel = DesignSidePanel.NONE
                                        singlePane = DesignPane.CHAT
                                        if (layoutOverride == DesignLayoutOverride.CANVAS) {
                                            layoutOverride = DesignLayoutOverride.AUTO
                                        }
                                    }
                                },
                            )
                            DesignSidePanel.TWEAKS -> DesignTweaksPanel(
                                activeVariant = activeVariant,
                                defs = tweakDefs,
                                errors = tweakErrors,
                                saved = savedTweakValues,
                                live = liveTweakValues,
                                current = currentTweakValues,
                                onClose = { sidePanel = DesignSidePanel.NONE },
                                onChangeValue = { def, rawVal ->
                                    val clean = sanitizeTweakValue(def, rawVal) ?: return@DesignTweaksPanel
                                    currentTweakValues[def.variable] = clean
                                    tweakSetEnvelope(def.variable, clean)?.let { frameChannel?.post(it) }
                                },
                                onResetAll = {
                                    currentTweakValues.clear()
                                    tweakResetEnvelope()?.let { frameChannel?.post(it) }
                                },
                                onApply = {
                                    val changes = pendingChanges(tweakDefs, savedTweakValues, currentTweakValues)
                                    if (changes.isEmpty() || api == null) return@DesignTweaksPanel
                                    scope.launch {
                                        runCatching {
                                            api.designApplyTweaks(
                                                projectName = projectName,
                                                slug = slug,
                                                entry = activeVariant,
                                                gens = fileGens.toMap(),
                                                values = changes,
                                            )
                                        }.onSuccess { res ->
                                            when (res) {
                                                is DesignTweaksApplyResult.Applied -> {
                                                    fileGens.putAll(res.gens)
                                                    savedTweakValues.putAll(changes)
                                                    undoStack.push(res.undoId)
                                                    syncUndoCount()
                                                    model.toast(
                                                        ErrorToastMessage(
                                                            "Tweaks applied",
                                                            "Saved ${changes.size} CSS variable(s) to disk.",
                                                            kind = ErrorToastMessage.Kind.INFO,
                                                        ),
                                                    )
                                                }
                                                is DesignTweaksApplyResult.Stale -> {
                                                    model.toast(ErrorToastMessage("Design changed on disk", res.message))
                                                    loadDesignDetailAndTools()
                                                    mintAndLoadPreview(resetRecovery = true)
                                                }
                                            }
                                        }.onFailure {
                                            model.toast(ErrorToastMessage("Apply tweaks failed", it.message))
                                        }
                                    }
                                },
                                onAskAiAddTweaks = {
                                    val prompt = buildAddTweaksPrompt(activeVariant)
                                    chat?.draft?.setTextAndPlaceCursorAtEnd(prompt)
                                    sidePanel = DesignSidePanel.NONE
                                    singlePane = DesignPane.CHAT
                                },
                                onAskAiFixTweaks = {
                                    val prompt = buildFixTweaksPrompt(activeVariant, tweakErrors)
                                    chat?.draft?.setTextAndPlaceCursorAtEnd(prompt)
                                    sidePanel = DesignSidePanel.NONE
                                    singlePane = DesignPane.CHAT
                                },
                            )
                            DesignSidePanel.HISTORY -> DesignHistoryPanel(
                                snapshots = snapshots,
                                isStreaming = isStreaming,
                                onClose = { sidePanel = DesignSidePanel.NONE },
                                onRestore = { snap -> confirmingRestore = snap },
                            )
                            DesignSidePanel.EXPORT -> DesignExportPanel(
                                detail = detail,
                                activeVariant = activeVariant,
                                onClose = { sidePanel = DesignSidePanel.NONE },
                                onDownloadZip = {
                                    if (api == null) return@DesignExportPanel
                                    val safeName = filenameFromContentDisposition(null, "$slug.zip")
                                    pendingSafExport = PendingDesignExport.Zip(
                                        projectName = projectName,
                                        slug = slug,
                                        suggestedFilename = safeName,
                                    )
                                    createDocumentLauncher.launch(safeName)
                                },
                                onDownloadHtml = {
                                    if (api == null) return@DesignExportPanel
                                    val fallbackHtml = activeVariant.substringAfterLast('/').ifBlank { "$slug.html" }
                                    val safeName = filenameFromContentDisposition(null, fallbackHtml)
                                    pendingSafExport = PendingDesignExport.Html(
                                        projectName = projectName,
                                        slug = slug,
                                        entry = activeVariant,
                                        suggestedFilename = safeName,
                                    )
                                    createDocumentLauncher.launch(safeName)
                                },
                                onPrintPdf = {
                                    if (api == null) return@DesignExportPanel
                                    scope.launch {
                                        runCatching {
                                            api.designMintPreview(projectName, slug, purpose = "print", entry = activeVariant)
                                        }.onSuccess { cap ->
                                            val fullUrl = api.baseUrl.newBuilder()
                                                .encodedPath(api.baseUrl.encodedPath.trimEnd('/') + "/" + cap.url.trimStart('/'))
                                                .build()
                                                .toString()
                                            printDesignUrlToPdf(
                                                context = context,
                                                url = fullUrl,
                                                jobName = "$slug - ${detail.summary.title}",
                                                docName = "$slug.pdf",
                                                onError = { msg -> model.toast(ErrorToastMessage("Print failed", msg)) },
                                            )
                                        }.onFailure { model.toast(ErrorToastMessage("Print failed", it.message)) }
                                    }
                                },
                                onOpenStandalone = {
                                    if (api == null) return@DesignExportPanel
                                    scope.launch {
                                        runCatching {
                                            api.designMintPreview(projectName, slug, purpose = "standalone", entry = activeVariant)
                                        }.onSuccess { cap ->
                                            val fullUrl = api.baseUrl.newBuilder()
                                                .encodedPath(api.baseUrl.encodedPath.trimEnd('/') + "/" + cap.url.trimStart('/'))
                                                .build()
                                                .toString()
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    },
                                                )
                                            }
                                        }.onFailure { model.toast(ErrorToastMessage("Open standalone failed", it.message)) }
                                    }
                                },
                                onExportPptx = {
                                    val reqId = newBridgeNonce()
                                    slidesExtractEnvelope(reqId)?.let { frameChannel?.post(it) }
                                },
                            )
                            DesignSidePanel.NONE -> Unit
                        }
                    }
                    when {
                        !mobile -> Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .widthIn(min = 280.dp, max = 360.dp)
                                .fillMaxHeight()
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Start),
                        ) { panelBody() }
                        // Docked under the canvas rather than in a sheet: a sheet's backdrop would dim the
                        // very colours being tweaked (`h-[45%] border-t`).
                        sidePanel == DesignSidePanel.TWEAKS -> Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(0.45f)
                                .background(tokens.panel.color)
                                .cssBorder(tokens.border.color, sides = CssSides.Top)
                                .testTag("design-phone-tweaks"),
                        ) { panelBody() }
                        else -> DesignPhonePanelSheet(
                            tag = "design-phone-panel-${sidePanel.name.lowercase()}",
                            onDismiss = { sidePanel = DesignSidePanel.NONE },
                        ) { panelBody() }
                    }
                }
            }

            // Single-pane bottom switch bar (Canvas | Chat | More): the thumb-zone bar on a phone.
            if (mobile && resolution.mode == "single") {
                DesignPhonePaneBar(
                    pane = resolution.activePane,
                    moreOpen = moreOpen,
                    onPaneChange = { pane ->
                        singlePane = pane
                        if (pane == DesignPane.CANVAS && layoutOverride == DesignLayoutOverride.CHAT) layoutOverride = DesignLayoutOverride.AUTO
                        if (pane == DesignPane.CHAT && layoutOverride == DesignLayoutOverride.CANVAS) layoutOverride = DesignLayoutOverride.AUTO
                    },
                    onMore = { moreOpen = true },
                )
            } else if (resolution.mode == "single") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, sides = CssSides.Top)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallActionChip(
                        label = "Canvas",
                        icon = ProductIcon.Palette,
                        active = resolution.activePane == DesignPane.CANVAS,
                    ) {
                        singlePane = DesignPane.CANVAS
                        if (layoutOverride == DesignLayoutOverride.CHAT) layoutOverride = DesignLayoutOverride.AUTO
                    }
                    SmallActionChip(
                        label = "Chat",
                        icon = ProductIcon.MessageSquare,
                        active = resolution.activePane == DesignPane.CHAT,
                    ) {
                        singlePane = DesignPane.CHAT
                        if (layoutOverride == DesignLayoutOverride.CANVAS) layoutOverride = DesignLayoutOverride.AUTO
                    }
                    Spacer(Modifier.weight(1f))
                    SmallActionChip(
                        label = "More",
                        icon = ProductIcon.MoreHorizontal,
                        active = sidePanel != DesignSidePanel.NONE,
                    ) {
                        sidePanel = if (sidePanel == DesignSidePanel.NONE) DesignSidePanel.EXPORT else DesignSidePanel.NONE
                    }
                }
            }
        }
    }

    confirmingRestore?.let { snap ->
        Dialog(onDismissRequest = { confirmingRestore = null }) {
            Column(
                Modifier
                    .widthIn(min = 280.dp, max = 380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicText(
                    "Restore Snapshot #${snap.turn}?",
                    style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                BasicText(
                    "This replaces the current files in `.ppm/designs/$slug/` with \"${snap.label}\" (${snap.fileCount} files).",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Cancel", Modifier.weight(1f)) { confirmingRestore = null }
                    PrimaryButton("Restore", Modifier.weight(1f), enabled = !isStreaming) {
                        val target = snap
                        confirmingRestore = null
                        if (api != null) {
                            scope.launch {
                                runCatching { api.designRestoreHistory(projectName, slug, target.id) }
                                    .onSuccess {
                                        loadDesignDetailAndTools()
                                        mintAndLoadPreview(resetRecovery = true)
                                        model.toast(
                                            ErrorToastMessage(
                                                "Restored #${target.turn}",
                                                target.label,
                                                kind = ErrorToastMessage.Kind.INFO,
                                            ),
                                        )
                                    }
                                    .onFailure { model.toast(ErrorToastMessage("Restore failed", it.message)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesignHeaderToolbar(
    detail: DesignDetail,
    activeVariant: String,
    onSelectVariant: (String) -> Unit,
    deviceFrame: DesignDeviceFrame,
    onSelectDeviceFrame: (DesignDeviceFrame) -> Unit,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
    layoutOverride: DesignLayoutOverride,
    onSelectLayoutOverride: (DesignLayoutOverride) -> Unit,
    pickerEnabled: Boolean,
    onTogglePicker: () -> Unit,
    transformModeOn: Boolean,
    onToggleTransform: () -> Unit,
    undoCount: Int,
    isStreaming: Boolean,
    onUndo: () -> Unit,
    openCommentsCount: Int,
    sidePanel: DesignSidePanel,
    onToggleSidePanel: (DesignSidePanel) -> Unit,
    onBuildInNewChat: () -> Unit,
    onReload: () -> Unit,
    shareOpen: Boolean = false,
    onShare: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(ProductIcon.Palette, 14.dp, tokens.accent.color)
        BasicText(
            detail.summary.title,
            style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
        )

        // Variant switcher when >= 2 variants
        if (detail.summary.variants.size >= 2) {
            for (v in detail.summary.variants) {
                SmallActionChip(
                    label = variantDisplayName(v),
                    icon = ProductIcon.FileCode,
                    active = activeVariant == v.file,
                ) { onSelectVariant(v.file) }
            }
        }

        // Device frames: Desktop | Tablet | Phone | Slide
        for (frame in DesignDeviceFrame.entries) {
            val icon = when (frame) {
                DesignDeviceFrame.DESKTOP -> ProductIcon.Monitor
                DesignDeviceFrame.TABLET -> ProductIcon.Tablet
                DesignDeviceFrame.PHONE -> ProductIcon.Smartphone
                DesignDeviceFrame.SLIDE -> ProductIcon.Presentation
            }
            SmallActionChip(
                label = frame.label,
                icon = icon,
                active = deviceFrame == frame,
            ) { onSelectDeviceFrame(frame) }
        }

        // Zoom controls
        SmallActionChip(label = "-", icon = ProductIcon.ZoomOut) { onZoomChange(zoom - 0.25f) }
        SmallActionChip(label = "${(zoom * 100).roundToInt()}%", icon = ProductIcon.Maximize2) { onZoomChange(1f) }
        SmallActionChip(label = "+", icon = ProductIcon.ZoomIn) { onZoomChange(zoom + 0.25f) }

        // Picker, Comments, Tweaks, Move/Resize, Undo, History
        SmallActionChip(
            label = "Picker",
            icon = ProductIcon.MousePointerClick,
            active = pickerEnabled,
            onClick = onTogglePicker,
        )
        SmallActionChip(
            label = if (openCommentsCount > 0) "Comments ($openCommentsCount)" else "Comments",
            icon = ProductIcon.MessageCircle,
            active = sidePanel == DesignSidePanel.COMMENTS,
        ) { onToggleSidePanel(DesignSidePanel.COMMENTS) }
        if (onShare != null) {
            SmallActionChip(
                label = "Share for review",
                icon = ProductIcon.Share2,
                active = shareOpen,
                onClick = onShare,
            )
        }
        SmallActionChip(
            label = "Tweaks",
            icon = ProductIcon.SlidersHorizontal,
            active = sidePanel == DesignSidePanel.TWEAKS,
        ) { onToggleSidePanel(DesignSidePanel.TWEAKS) }
        SmallActionChip(
            label = "Move / Resize",
            icon = ProductIcon.Move,
            active = transformModeOn,
            onClick = onToggleTransform,
        )
        if (undoCount > 0) {
            SmallActionChip(
                label = "Undo ($undoCount)",
                icon = ProductIcon.Undo2,
                enabled = !isStreaming,
                onClick = onUndo,
            )
        }
        SmallActionChip(
            label = "History",
            icon = ProductIcon.History,
            active = sidePanel == DesignSidePanel.HISTORY,
        ) { onToggleSidePanel(DesignSidePanel.HISTORY) }

        // Layout Override selector (Auto | Split | Canvas | Chat)
        for (ov in DesignLayoutOverride.entries) {
            val label = when (ov) {
                DesignLayoutOverride.AUTO -> "Auto"
                DesignLayoutOverride.SPLIT -> "Split"
                DesignLayoutOverride.CANVAS -> "Canvas Only"
                DesignLayoutOverride.CHAT -> "Chat Only"
            }
            SmallActionChip(
                label = label,
                icon = ProductIcon.Columns2,
                active = layoutOverride == ov,
            ) { onSelectLayoutOverride(ov) }
        }

        // Handoff & Export & Reload
        SmallActionChip(
            label = "Build in new chat",
            icon = ProductIcon.Sparkles,
            onClick = onBuildInNewChat,
        )
        SmallActionChip(
            label = "Export",
            icon = ProductIcon.Download,
            active = sidePanel == DesignSidePanel.EXPORT,
        ) { onToggleSidePanel(DesignSidePanel.EXPORT) }
        SmallActionChip(
            label = "Reload",
            icon = ProductIcon.RefreshCw,
            onClick = onReload,
        )
    }
}

@Composable
private fun DesignCanvasArea(
    model: AppModel,
    slug: String,
    activeVariant: String,
    previewUrl: String?,
    bridgeNonce: String,
    reloadNonce: Int,
    previewPhase: PreviewPhase,
    deviceFrame: DesignDeviceFrame,
    zoom: Float,
    runtimeIssues: List<RuntimeIssue>,
    blockedNavHref: String?,
    lastCheckSummary: String?,
    pickedElement: PickedElement?,
    comments: List<DesignComment>,
    pinRects: Map<String, PinRect>,
    transformModeOn: Boolean,
    onDismissBlockedNav: () -> Unit,
    onSelectParent: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenAddComment: () -> Unit,
    onNudge: (Int, Int) -> Unit,
    onCancelTransform: () -> Unit,
    onManualReload: () -> Unit,
    onCanvasMeasured: (Int, Int) -> Unit,
    onBridgeMessage: (kotlinx.serialization.json.JsonElement) -> Unit,
    onChannelReady: (FrameChannel) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        // Blocked navigation banner
        if (blockedNavHref != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.warning.opacity(0.15).color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.ShieldAlert, 13.dp, tokens.warning.color)
                BasicText(
                    "Blocked in-frame navigation to $blockedNavHref",
                    Modifier.weight(1f),
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SmallActionChip(label = "Dismiss", icon = ProductIcon.X, onClick = onDismissBlockedNav)
            }
        }

        // Runtime issues or check summary bar
        if (runtimeIssues.isNotEmpty() || lastCheckSummary != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.opacity(0.6).color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (runtimeIssues.isNotEmpty()) {
                    PpmIcon(ProductIcon.AlertTriangle, 12.dp, tokens.error.color)
                    BasicText(
                        "${runtimeIssues.size} runtime issue(s): ${runtimeIssues.last().message}",
                        Modifier.weight(1f),
                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.error.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (lastCheckSummary != null) {
                    PpmIcon(ProductIcon.CheckCircle2, 12.dp, tokens.accent.color)
                    BasicText(
                        lastCheckSummary,
                        Modifier.weight(1f),
                        style = ppmText(10f, lineHeight = 13f).copy(color = tokens.text2.color),
                    )
                }
            }
        }

        // Selected element / Move-Resize HUD bar
        if (pickedElement != null || transformModeOn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (pickedElement != null) {
                    BadgePill("<${pickedElement.tag}> ${pickedElement.selector}", tokens.accent)
                    if (pickedElement.hasParent) {
                        SmallActionChip(label = "Select Parent", icon = ProductIcon.ArrowUp, onClick = onSelectParent)
                    }
                    SmallActionChip(label = "Comment", icon = ProductIcon.MessageSquarePlus, onClick = onOpenAddComment)
                    SmallActionChip(label = "Clear", icon = ProductIcon.X, onClick = onClearSelection)
                }
                if (transformModeOn) {
                    BasicText("Nudge:", style = ppmText(10f, lineHeight = 13f).copy(color = tokens.text3.color))
                    SmallActionChip(label = "←", icon = ProductIcon.ArrowLeft) { onNudge(-4, 0) }
                    SmallActionChip(label = "↑", icon = ProductIcon.ArrowUp) { onNudge(0, -4) }
                    SmallActionChip(label = "↓", icon = ProductIcon.ArrowDown) { onNudge(0, 4) }
                    SmallActionChip(label = "→", icon = ProductIcon.ArrowRight) { onNudge(4, 0) }
                    SmallActionChip(label = "Cancel Move", icon = ProductIcon.X, onClick = onCancelTransform)
                }
            }
        }

        // Canvas viewport box
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size -> onCanvasMeasured(size.width, size.height) },
            contentAlignment = Alignment.Center,
        ) {
            val availW = constraints.maxWidth.toFloat()
            val availH = constraints.maxHeight.toFloat()
            val frameScale = computeDeviceFrameScale(availW, availH, deviceFrame) * zoom

            if (previewPhase == PreviewPhase.DEAD) {
                // Dead state after single auto-recovery failed
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.AlertCircle, 28.dp, tokens.error.color)
                    BasicText(
                        "Preview unavailable",
                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                    BasicText(
                        "The design canvas token expired or failed to respond after automatic recovery.",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                    )
                    PrimaryButton("Reload Preview", onClick = onManualReload)
                }
            } else {
                val fw = deviceFrame.widthPx
                val fh = deviceFrame.heightPx
                val frameModifier = if (fw != null && fh != null) {
                    val wDp = with(density) { (fw * frameScale).toDp() }
                    val hDp = with(density) { (fh * frameScale).toDp() }
                    Modifier
                        .size(wDp, hDp)
                        .clip(RoundedCornerShape(10.dp))
                        .cssBorder(tokens.border.color, RoundedCornerShape(10.dp))
                } else {
                    Modifier.fillMaxSize()
                }

                Box(frameModifier) {
                    val api = model.api
                    val channel = remember(slug, activeVariant, reloadNonce) {
                        FrameChannel().also(onChannelReady)
                    }
                    if (api != null && !previewUrl.isNullOrBlank()) {
                        key(reloadNonce, previewUrl, bridgeNonce) {
                            HtmlSurface(
                                src = "$previewUrl?n=${Uri.encode(bridgeNonce)}",
                                origin = api.origin,
                                channel = channel,
                                onMessage = { rawStr ->
                                    WireJson.parseOrNull(rawStr)?.let(onBridgeMessage)
                                },
                                onFrameLoad = {},
                                onCrash = onManualReload,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            BasicText(
                                "Design Canvas ($slug / $activeVariant)",
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                            )
                        }
                    }

                    // Numbered comment pins overlay
                    for ((idx, comment) in comments.withIndex()) {
                        val rect = pinRects[comment.id]
                        val xDp = if (rect != null && rect.found) rect.x.dp else (24 + (idx % 6) * 28).dp
                        val yDp = if (rect != null && rect.found) rect.y.dp else (24 + (idx / 6) * 28).dp
                        Box(
                            Modifier
                                .offset { IntOffset(xDp.roundToPx(), yDp.roundToPx()) }
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (comment.resolved) tokens.text3.color else tokens.accent.color)
                                .cssBorder(Color.White, CircleShape, width = 1.5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "#${comment.number}",
                                style = ppmText(9f, FontWeight.Bold, lineHeight = 10f).copy(color = tokens.accentFg.color),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesignChatPaneContainer(
    model: AppModel,
    chat: com.hienlh.ppm.app.ChatTab?,
    slug: String,
) {
    val tokens = LocalPpmTokens.current
    if (chat != null) {
        key(chat) {
            ChatPane(model, chat)
        }
    } else {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                "Design Chat ($slug)",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )
        }
    }
}

// ===========================================================================
// 10b.3 · Comments Panel, Tweaks Panel, History Panel & Export Panel
// ===========================================================================

@Composable
internal fun DesignCommentsPanel(
    slug: String,
    activeVariant: String,
    pickedElement: PickedElement?,
    comments: List<DesignComment>,
    pinRects: Map<String, PinRect>,
    onClose: () -> Unit,
    onAddComment: (String) -> Unit,
    onToggleResolve: (DesignComment) -> Unit,
    onDeleteComment: (DesignComment) -> Unit,
    onReplyComment: (DesignComment, String) -> Unit,
    onSendCommentsToAi: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val draftState = rememberTextFieldState("")
    var showResolved by remember { mutableStateOf(false) }
    val filtered = remember(comments, showResolved) {
        comments.filter { if (showResolved) it.resolved else !it.resolved }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Comments ($activeVariant)",
                Modifier.weight(1f),
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
            )
            SmallActionChip(
                label = if (showResolved) "Resolved" else "Open",
                icon = ProductIcon.Filter,
                active = showResolved,
            ) { showResolved = !showResolved }
            Spacer(Modifier.width(4.dp))
            SmallActionChip(label = "Close", icon = ProductIcon.X, onClick = onClose)
        }

        // Composer when an element is picked
        if (pickedElement != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "Target: <${pickedElement.tag}> ${pickedElement.selector}",
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.accent.color),
                )
                PpmTextField(draftState, "Add a review comment…", Modifier.fillMaxWidth())
                PrimaryButton("Add Comment", Modifier.fillMaxWidth()) {
                    val text = draftState.text.toString().trim()
                    if (text.isNotEmpty()) {
                        onAddComment(text)
                        draftState.setTextAndPlaceCursorAtEnd("")
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) {
                item {
                    BasicText(
                        "No ${if (showResolved) "resolved" else "open"} comments on $activeVariant.",
                        Modifier.padding(vertical = 16.dp),
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                    )
                }
            }
            items(filtered, key = { it.id }) { comment ->
                val pin = pinRects[comment.id]
                val isDetached = pin != null && !pin.found
                var replyOpen by remember(comment.id) { mutableStateOf(false) }
                val replyState = rememberTextFieldState("")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.panel2.opacity(0.45).color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BadgePill("#${comment.number}", tokens.accent)
                        BasicText(
                            "<${comment.anchor.tag}>",
                            Modifier.weight(1f),
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                        if (isDetached) {
                            BadgePill("Detached", tokens.warning)
                        }
                    }
                    BasicText(comment.body, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color))
                    for (r in comment.replies) {
                        BasicText(
                            "${if (r.author == "ai") "AI" else "You"}: ${r.body}",
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallActionChip(
                            label = if (comment.resolved) "Reopen" else "Resolve",
                            icon = ProductIcon.Check,
                        ) { onToggleResolve(comment) }
                        SmallActionChip(label = "Reply", icon = ProductIcon.MessageSquare) { replyOpen = !replyOpen }
                        SmallActionChip(label = "Delete", icon = ProductIcon.Trash2) { onDeleteComment(comment) }
                    }
                    if (replyOpen) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tokens.bgSolid.color)
                                    .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                PpmTextField(replyState, "Reply…", Modifier.fillMaxWidth())
                            }
                            SmallActionChip(label = "Send", icon = ProductIcon.Send) {
                                val rb = replyState.text.toString().trim()
                                if (rb.isNotEmpty()) {
                                    onReplyComment(comment, rb)
                                    replyState.setTextAndPlaceCursorAtEnd("")
                                    replyOpen = false
                                }
                            }
                        }
                    }
                }
            }
        }

        // Send to AI button (puts prompt into composer without auto-sending)
        PrimaryButton(
            title = "Send to AI (${comments.count { !it.resolved }} open)",
            modifier = Modifier.fillMaxWidth(),
            enabled = comments.any { !it.resolved },
            onClick = onSendCommentsToAi,
        )
    }
}

@Composable
internal fun DesignTweaksPanel(
    activeVariant: String,
    defs: List<TweakDef>,
    errors: List<String>,
    saved: Map<String, String>,
    live: Map<String, String>,
    current: Map<String, String>,
    onClose: () -> Unit,
    onChangeValue: (TweakDef, String) -> Unit,
    onResetAll: () -> Unit,
    onApply: () -> Unit,
    onAskAiAddTweaks: () -> Unit,
    onAskAiFixTweaks: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val pending = pendingChanges(defs, saved, current)

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Tweaks ($activeVariant)",
                Modifier.weight(1f),
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
            )
            SmallActionChip(label = "Reset", icon = ProductIcon.RotateCcw, enabled = current.isNotEmpty(), onClick = onResetAll)
            Spacer(Modifier.width(4.dp))
            SmallActionChip(label = "Close", icon = ProductIcon.X, onClick = onClose)
        }

        if (errors.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.warning.opacity(0.14).color)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "${errors.size} invalid tweak definition(s) in design.json",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.warning.color),
                )
                SmallActionChip(label = "Ask AI to fix tweaks", icon = ProductIcon.Sparkles, onClick = onAskAiFixTweaks)
            }
        }

        if (defs.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BasicText(
                    "No CSS variable tweaks defined in design.json",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Ask AI to add tweaks", onClick = onAskAiAddTweaks)
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (def in defs) {
                    val displayed = shownValue(def, current, live, saved)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.panel2.opacity(0.4).color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(
                                def.label,
                                Modifier.weight(1f),
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                            BasicText(
                                "${def.variable}: $displayed",
                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                            )
                        }
                        when (def) {
                            is TweakDef.Color -> {
                                val currentHex = hexForColorInput(displayed)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (preset in listOf("#6366f1", "#10b981", "#f59e0b", "#ef4444", "#0f172a", "#ffffff")) {
                                        SmallActionChip(
                                            label = preset,
                                            icon = ProductIcon.Palette,
                                            active = currentHex.equals(preset, ignoreCase = true),
                                        ) { onChangeValue(def, preset) }
                                    }
                                }
                            }
                            is TweakDef.Range -> {
                                val curNum = rangeNumberOf(displayed, def.min)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SmallActionChip(label = "-${def.step}", icon = ProductIcon.Minus) {
                                        val next = (curNum - def.step).coerceIn(def.min, def.max)
                                        onChangeValue(def, "$next${def.unit}")
                                    }
                                    SmallActionChip(label = "${def.min}${def.unit}", icon = ProductIcon.ArrowLeft) {
                                        onChangeValue(def, "${def.min}${def.unit}")
                                    }
                                    SmallActionChip(label = "${def.max}${def.unit}", icon = ProductIcon.ArrowRight) {
                                        onChangeValue(def, "${def.max}${def.unit}")
                                    }
                                    SmallActionChip(label = "+${def.step}", icon = ProductIcon.Plus) {
                                        val next = (curNum + def.step).coerceIn(def.min, def.max)
                                        onChangeValue(def, "$next${def.unit}")
                                    }
                                }
                            }
                            is TweakDef.Select -> {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    for (opt in def.options) {
                                        SmallActionChip(
                                            label = opt.label,
                                            icon = ProductIcon.Check,
                                            active = displayed == opt.value,
                                        ) { onChangeValue(def, opt.value) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            PrimaryButton(
                title = if (pending.isEmpty()) "No Pending Changes" else "Apply (${pending.size})",
                modifier = Modifier.fillMaxWidth(),
                enabled = pending.isNotEmpty(),
                onClick = onApply,
            )
        }
    }
}

@Composable
internal fun DesignHistoryPanel(
    snapshots: List<DesignSnapshotInfo>,
    isStreaming: Boolean,
    onClose: () -> Unit,
    onRestore: (DesignSnapshotInfo) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Snapshots (${snapshots.size})",
                Modifier.weight(1f),
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
            )
            SmallActionChip(label = "Close", icon = ProductIcon.X, onClick = onClose)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshots.isEmpty()) {
                item {
                    BasicText(
                        "No turn snapshots recorded yet.",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                    )
                }
            }
            items(snapshots, key = { it.id }) { snap ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.panel2.opacity(0.4).color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BadgePill("#${snap.turn}", tokens.accent)
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            snap.label,
                            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            "${snap.fileCount} file(s) · ${snap.totalBytes} B",
                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text3.color),
                        )
                    }
                    SmallActionChip(
                        label = "Restore",
                        icon = ProductIcon.RotateCcw,
                        enabled = !isStreaming,
                    ) { onRestore(snap) }
                }
            }
        }
    }
}

@Composable
internal fun DesignExportPanel(
    detail: DesignDetail,
    activeVariant: String,
    onClose: () -> Unit,
    onDownloadZip: () -> Unit,
    onDownloadHtml: () -> Unit,
    onPrintPdf: () -> Unit,
    onOpenStandalone: () -> Unit,
    onExportPptx: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Export Design",
                Modifier.weight(1f),
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
            )
            SmallActionChip(label = "Close", icon = ProductIcon.X, onClick = onClose)
        }
        PrimaryButton("Download ZIP (.zip)", Modifier.fillMaxWidth(), onClick = onDownloadZip)
        SecondaryButton("Download Standalone HTML ($activeVariant)", Modifier.fillMaxWidth(), onClick = onDownloadHtml)
        SecondaryButton("Print / Save as PDF", Modifier.fillMaxWidth(), onClick = onPrintPdf)
        SecondaryButton("Open in Browser Tab", Modifier.fillMaxWidth(), onClick = onOpenStandalone)
        if (detail.summary.kind == DesignKind.SLIDES) {
            PrimaryButton("Export Slide Deck (.pptx)", Modifier.fillMaxWidth(), onClick = onExportPptx)
        }
    }
}

internal data class PreviewSelfHealOutcome(
    val phase: PreviewPhase,
    val token: String?,
    val url: String?,
    val gen: String?,
    val hasRecoveredOnce: Boolean,
    val attempts: Int,
)

internal suspend fun loadPreviewWithSelfHeal(
    api: ApiClient,
    projectName: String,
    slug: String,
    entry: String,
    initialToken: String?,
    initialRecoveredOnce: Boolean = false,
    retryDelayMs: Long = 0L,
): PreviewSelfHealOutcome {
    var currentToken = initialToken
    var recoveredOnce = initialRecoveredOnce
    var attempts = 0
    while (true) {
        attempts++
        try {
            val cap = api.designMintPreview(
                projectName = projectName,
                slug = slug,
                purpose = "canvas",
                token = currentToken,
                entry = entry,
            )
            currentToken = cap.token
            val gen = api.designHeadPreviewGen(cap.url)
            return PreviewSelfHealOutcome(
                phase = PreviewPhase.LOADING,
                token = cap.token,
                url = cap.url,
                gen = gen,
                hasRecoveredOnce = recoveredOnce,
                attempts = attempts,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            val recovery = recoverPreviewOnFailure(currentToken, recoveredOnce)
            recoveredOnce = recovery.hasRecoveredOnce
            currentToken = recovery.nextToken
            if (recovery.phase == PreviewPhase.RECOVERING) {
                if (retryDelayMs > 0L) delay(retryDelayMs)
                continue
            }
            return PreviewSelfHealOutcome(
                phase = PreviewPhase.DEAD,
                token = null,
                url = null,
                gen = null,
                hasRecoveredOnce = recoveredOnce,
                attempts = attempts,
            )
        }
    }
}

internal sealed interface PendingDesignExport {
    val suggestedFilename: String
    val failureTitle: String

    data class Zip(
        val projectName: String,
        val slug: String,
        override val suggestedFilename: String = "$slug.zip",
    ) : PendingDesignExport {
        override val failureTitle: String = "ZIP export failed"
    }

    data class Html(
        val projectName: String,
        val slug: String,
        val entry: String,
        override val suggestedFilename: String = entry.substringAfterLast('/').ifBlank { "$slug.html" },
    ) : PendingDesignExport {
        override val failureTitle: String = "HTML export failed"
    }

    data class Pptx(
        override val suggestedFilename: String,
        val bytes: ByteArray,
        val slideCount: Int,
        val warnings: List<String> = emptyList(),
    ) : PendingDesignExport {
        override val failureTitle: String = "Slides export failed"
    }
}

internal fun writeStreamToSafUri(
    resolver: ContentResolver,
    uri: Uri,
    input: InputStream,
): Long {
    val out = resolver.openOutputStream(uri)
        ?: resolver.openOutputStream(uri, "w")
        ?: throw IOException("Could not open output stream for $uri")
    return out.use { stream -> input.copyTo(stream) }
}

internal suspend fun completeDesignSafExport(
    resolver: ContentResolver,
    uri: Uri?,
    export: PendingDesignExport,
    api: ApiClient? = null,
    onToast: (ErrorToastMessage) -> Unit,
): Boolean {
    if (uri == null) return false
    return try {
        when (export) {
            is PendingDesignExport.Zip -> {
                val client = api ?: throw IOException("Not connected to server")
                val streamed = client.designStreamExport(export.projectName, export.slug, "zip") { _, input ->
                    writeStreamToSafUri(resolver, uri, input)
                }
                val safeName = filenameFromContentDisposition("""filename="${streamed.filename}"""", export.suggestedFilename)
                onToast(
                    ErrorToastMessage(
                        "Saved $safeName",
                        "Downloaded ZIP archive (${streamed.bytesWritten} bytes).",
                        kind = ErrorToastMessage.Kind.INFO,
                    ),
                )
            }
            is PendingDesignExport.Html -> {
                val client = api ?: throw IOException("Not connected to server")
                val streamed = client.designStreamExport(export.projectName, export.slug, "html", export.entry) { _, input ->
                    writeStreamToSafUri(resolver, uri, input)
                }
                val safeName = filenameFromContentDisposition("""filename="${streamed.filename}"""", export.suggestedFilename)
                val warnNote = if (streamed.warningCount > 0) {
                    " (${streamed.warningCount} warning(s): ${streamed.warnings.joinToString(", ")})"
                } else ""
                onToast(
                    ErrorToastMessage(
                        "Saved $safeName",
                        "Downloaded standalone HTML$warnNote.",
                        kind = if (streamed.warningCount > 0) ErrorToastMessage.Kind.ERROR else ErrorToastMessage.Kind.INFO,
                    ),
                )
            }
            is PendingDesignExport.Pptx -> {
                val safeName = filenameFromContentDisposition(null, export.suggestedFilename)
                withContext(Dispatchers.IO) {
                    ByteArrayInputStream(export.bytes).use { input ->
                        writeStreamToSafUri(resolver, uri, input)
                    }
                }
                val warnNote = if (export.warnings.isNotEmpty()) {
                    " (${export.warnings.size} note(s): ${export.warnings.first()})"
                } else ""
                onToast(
                    ErrorToastMessage(
                        "Saved $safeName",
                        "Exported ${export.slideCount} slide(s)$warnNote.",
                        kind = ErrorToastMessage.Kind.INFO,
                    ),
                )
            }
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        onToast(ErrorToastMessage(export.failureTitle, e.message))
        false
    }
}

private fun printDesignUrlToPdf(
    context: Context,
    url: String,
    jobName: String,
    docName: String,
    onError: (String) -> Unit,
) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
    if (printManager == null) {
        onError("Print service is not available on this device")
        return
    }
    val webView = WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, loadedUrl: String) {
                runCatching {
                    val adapter = view.createPrintDocumentAdapter(docName)
                    printManager.print(jobName, adapter, PrintAttributes.Builder().build())
                }.onFailure {
                    onError(it.message ?: "Failed to start print job")
                }
            }
        }
    }
    webView.loadUrl(url)
}
