package com.hienlh.ppm.app.ui.remotedesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.client.openRemoteDesktopConnection
import com.hienlh.ppm.client.remoteDesktopCapabilities
import com.hienlh.ppm.client.remoteDesktopFfmpegInstallStart
import com.hienlh.ppm.client.remoteDesktopMintSession
import com.hienlh.ppm.client.remoteDesktopRequirementAction
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.remotedesktop.CUSTOM_FPS_DEFAULT
import com.hienlh.ppm.state.remotedesktop.CUSTOM_FPS_MAX
import com.hienlh.ppm.state.remotedesktop.CUSTOM_FPS_MIN
import com.hienlh.ppm.state.remotedesktop.CUSTOM_QUALITY_DEFAULT_PERCENT
import com.hienlh.ppm.state.remotedesktop.CUSTOM_QUALITY_MAX_MORE_PERCENT
import com.hienlh.ppm.state.remotedesktop.CUSTOM_QUALITY_MAX_PERCENT
import com.hienlh.ppm.state.remotedesktop.CUSTOM_QUALITY_MIN_PERCENT
import com.hienlh.ppm.state.remotedesktop.DEFAULT_CUSTOM_SCALE
import com.hienlh.ppm.state.remotedesktop.computeStageVideoRect
import com.hienlh.ppm.state.remotedesktop.DEFAULT_PRESET_ID
import com.hienlh.ppm.state.remotedesktop.DEFAULT_VIEW_STYLE
import com.hienlh.ppm.state.remotedesktop.IDENTITY_TRANSFORM
import com.hienlh.ppm.state.remotedesktop.QUALITY_PRESETS
import com.hienlh.ppm.state.remotedesktop.QUALITY_PRESET_ORDER
import com.hienlh.ppm.state.remotedesktop.RD_COMBOS
import com.hienlh.ppm.state.remotedesktop.RD_FRAME_AUDIO
import com.hienlh.ppm.state.remotedesktop.RD_FRAME_KEY
import com.hienlh.ppm.state.remotedesktop.RD_FUNCTION_KEYS
import com.hienlh.ppm.state.remotedesktop.RD_MODIFIERS
import com.hienlh.ppm.state.remotedesktop.RdFraction
import com.hienlh.ppm.state.remotedesktop.RdPoint
import com.hienlh.ppm.state.remotedesktop.RdRect
import com.hienlh.ppm.state.remotedesktop.RdSize
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopCapabilities
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopRequirement
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopStats
import com.hienlh.ppm.state.remotedesktop.RemoteDesktopStatsSample
import com.hienlh.ppm.state.remotedesktop.RequirementAction
import com.hienlh.ppm.state.remotedesktop.SingleTrack
import com.hienlh.ppm.state.remotedesktop.TwoFingerTrack
import com.hienlh.ppm.state.remotedesktop.VIEW_STYLE_LABELS
import com.hienlh.ppm.state.remotedesktop.VIEW_STYLE_ORDER
import com.hienlh.ppm.state.remotedesktop.WHEEL_DELTA
import com.hienlh.ppm.state.remotedesktop.WHEEL_PIXELS_PER_NOTCH
import com.hienlh.ppm.state.remotedesktop.ZoomPanTransform
import com.hienlh.ppm.state.remotedesktop.MAX_RECORDING_MS
import com.hienlh.ppm.state.remotedesktop.PointerMoveCoalescer
import com.hienlh.ppm.state.remotedesktop.SOFT_KEYBOARD_SENTINEL
import com.hienlh.ppm.state.remotedesktop.advanceSingleTouch
import com.hienlh.ppm.state.remotedesktop.advanceTwoFingerTouch
import com.hienlh.ppm.state.remotedesktop.androidKeyCodeToW3cCode
import com.hienlh.ppm.state.remotedesktop.androidMouseButtonToProtocol
import com.hienlh.ppm.state.remotedesktop.beginSingleTouch
import com.hienlh.ppm.state.remotedesktop.beginTwoFingerTouch
import com.hienlh.ppm.state.remotedesktop.canvasCssSize
import com.hienlh.ppm.state.remotedesktop.clampCustomFps
import com.hienlh.ppm.state.remotedesktop.clampCustomQualityPercent
import com.hienlh.ppm.state.remotedesktop.clampCustomScale
import com.hienlh.ppm.state.remotedesktop.codecLabel
import com.hienlh.ppm.state.remotedesktop.computeRemoteDesktopStats
import com.hienlh.ppm.state.remotedesktop.customBitrateKbps
import com.hienlh.ppm.state.remotedesktop.fitLocalMode
import com.hienlh.ppm.state.remotedesktop.formatRemoteDesktopElapsed
import com.hienlh.ppm.state.remotedesktop.fractionFromPoint
import com.hienlh.ppm.state.remotedesktop.fractionFromZoomedPoint
import com.hienlh.ppm.state.remotedesktop.isCopyChord
import com.hienlh.ppm.state.remotedesktop.isCopyKeyCombo
import com.hienlh.ppm.state.remotedesktop.isPasteKeyCombo
import com.hienlh.ppm.state.remotedesktop.letterboxedContentRect
import com.hienlh.ppm.state.remotedesktop.nextQualityChoice
import com.hienlh.ppm.state.remotedesktop.parsePresetId
import com.hienlh.ppm.state.remotedesktop.pointerJsonMessage
import com.hienlh.ppm.state.remotedesktop.resolutionChoices
import com.hienlh.ppm.state.remotedesktop.resolutionLabel
import com.hienlh.ppm.state.remotedesktop.resolveSingleTouchCancel
import com.hienlh.ppm.state.remotedesktop.resolveSingleTouchEnd
import com.hienlh.ppm.state.remotedesktop.resolveSoftKeyboardCommit
import com.hienlh.ppm.state.remotedesktop.resolveTwoFingerEnd
import com.hienlh.ppm.state.remotedesktop.scalePercentToPos
import com.hienlh.ppm.state.remotedesktop.scalePosToPercent
import com.hienlh.ppm.state.remotedesktop.shortQualityLabel
import com.hienlh.ppm.state.remotedesktop.snapScalePos
import com.hienlh.ppm.state.remotedesktop.stepCustomScale
import com.hienlh.ppm.state.remotedesktop.wheelDeltaForTouchDrag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Phase 11b Remote Desktop screen (`RemoteDesktopWindow`, `RemoteDesktopTab`, and `RemoteDesktopAppBody`).
 */
@Composable
fun RemoteDesktopWindow(
    model: AppModel,
    windowId: String,
) {
    RemoteDesktopAppBody(
        model = model,
        onClose = { model.closeWindow(windowId) },
    )
}

@Composable
fun RemoteDesktopTab(
    model: AppModel,
    tabId: String,
) {
    RemoteDesktopAppBody(
        model = model,
        onClose = { model.closeTab(tabId) },
    )
}

@Composable
fun RemoteDesktopAppBody(
    model: AppModel,
    onClose: () -> Unit = {},
    initialWarningDismissed: Boolean = false,
    initialCaps: RemoteDesktopCapabilities? = null,
) {
    var warningAccepted by remember { mutableStateOf(initialWarningDismissed || model.remoteDesktopWarningDismissed) }
    var dontShowAgain by remember { mutableStateOf(false) }

    if (!warningAccepted && !model.remoteDesktopWarningDismissed) {
        RemoteDesktopWarningGate(
            dontShowAgain = dontShowAgain,
            onToggleDontShowAgain = { dontShowAgain = it },
            onCancel = onClose,
            onContinue = {
                if (dontShowAgain) {
                    model.setRemoteDesktopWarningDismissedPref(true)
                }
                warningAccepted = true
            },
        )
        return
    }

    RemoteDesktopReadinessGate(
        model = model,
        initialCaps = initialCaps,
        onCancel = onClose,
    ) { caps ->
        RemoteDesktopViewer(
            model = model,
            initialCaps = caps,
            onClose = onClose,
        )
    }
}

// ── 1. Warning Gate ───────────────────────────────────────────────────

@Composable
fun RemoteDesktopWarningGate(
    dontShowAgain: Boolean,
    onToggleDontShowAgain: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .testTag("remote-desktop-warning-gate"),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .widthIn(max = 384.dp)
                .fillMaxWidth()
                .clip(cardShape)
                .background(Color.White.copy(alpha = 0.05f))
                .cssBorder(Color.White.copy(alpha = 0.10f), cardShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Monitor, 20.dp, tokens.accent.color)
                BasicText(
                    "Remote Desktop",
                    style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = Color.White),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF59E0B).copy(alpha = 0.20f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    BasicText(
                        "BETA",
                        style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = Color(0xFFFCD34D)),
                    )
                }
            }

            val bullets = listOf(
                "This streams the host's whole screen and lets you move its mouse and type as if you were sitting at it. Anything you do here happens on the real desktop.",
                "Anyone signed in to this PPM can do the same. Avoid opening it over a public share link you don't fully trust; a private network (Tailscale, LAN) is safer.",
                "Still beta: the host must be unlocked with an active session, UAC prompts stay hidden, and a person at the host will fight you for the cursor.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in bullets) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PpmIcon(ProductIcon.AlertTriangle, 16.dp, Color(0xFFFCD34D), Modifier.padding(top = 2.dp))
                        BasicText(
                            b,
                            style = ppmText(13f, lineHeight = 18f).copy(color = Color.White.copy(alpha = 0.82f)),
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .ppmClickable { onToggleDontShowAgain(!dontShowAgain) }
                    .testTag("remote-desktop-warning-dont-show"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (dontShowAgain) tokens.accent.color else Color.Transparent)
                        .cssBorder(
                            if (dontShowAgain) tokens.accent.color else Color.White.copy(alpha = 0.4f),
                            RoundedCornerShape(4.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (dontShowAgain) {
                        PpmIcon(ProductIcon.Check, 11.dp, tokens.accentFg.color)
                    }
                }
                BasicText(
                    "Don't show this again",
                    style = ppmText(13f, lineHeight = 18f).copy(color = Color.White.copy(alpha = 0.72f)),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val btnShape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .ppmClickable(shape = btnShape, onClick = onCancel)
                        .testTag("remote-desktop-warning-cancel"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Cancel", style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = Color.White))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(tokens.accent.color)
                        .ppmClickable(shape = btnShape, onClick = onContinue)
                        .testTag("remote-desktop-warning-continue"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Continue", style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.accentFg.color))
                }
            }
        }
    }
}

// ── 2. Readiness Gate ─────────────────────────────────────────────────

@Composable
fun RemoteDesktopReadinessGate(
    model: AppModel,
    initialCaps: RemoteDesktopCapabilities? = null,
    onCancel: () -> Unit,
    content: @Composable (RemoteDesktopCapabilities) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var caps by remember { mutableStateOf(initialCaps) }
    var failed by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var ffmpegInstalling by remember { mutableStateOf(false) }

    val ready = caps?.let { it.videoReady && it.inputReady } == true

    LaunchedEffect(model.api, accepted, ready) {
        if (accepted || ready) return@LaunchedEffect
        val api = model.api ?: return@LaunchedEffect
        while (isActive) {
            try {
                val res = api.remoteDesktopCapabilities()
                caps = RemoteDesktopCapabilities.from(res)
                failed = false
            } catch (_: Exception) {
                if (caps == null) failed = true
            }
            if (caps?.let { it.videoReady && it.inputReady } == true) break
            delay(2_000L)
        }
    }

    val currentCaps = caps
    if (accepted || (currentCaps != null && currentCaps.videoReady && currentCaps.inputReady)) {
        content(currentCaps ?: RemoteDesktopCapabilities())
        return
    }

    val pending = currentCaps?.requirements?.filter { !it.ok }.orEmpty()
    val viewOnly = currentCaps?.videoReady == true && !currentCaps.inputReady

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .testTag("remote-desktop-readiness-gate"),
        contentAlignment = Alignment.Center,
    ) {
        val cardShape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .widthIn(max = 384.dp)
                .fillMaxWidth()
                .clip(cardShape)
                .background(Color.White.copy(alpha = 0.05f))
                .cssBorder(Color.White.copy(alpha = 0.10f), cardShape)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Monitor, 20.dp, tokens.accent.color)
                BasicText(
                    "This host needs a few things",
                    style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = Color.White),
                )
            }

            if (currentCaps == null && !failed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Loader2, 16.dp, Color.White.copy(alpha = 0.7f), spinning = true)
                    BasicText(
                        "Checking the host…",
                        style = ppmText(13f, lineHeight = 18f).copy(color = Color.White.copy(alpha = 0.7f)),
                    )
                }
            }

            if (failed) {
                BasicText(
                    "Could not reach the host's remote-desktop service.",
                    style = ppmText(13f, lineHeight = 18f).copy(color = Color(0xFFFCA5A5)),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (req in currentCaps?.requirements.orEmpty()) {
                    RequirementRowView(
                        req = req,
                        ffmpegInstalling = ffmpegInstalling,
                        onInstallFfmpeg = {
                            ffmpegInstalling = true
                            scope.launch {
                                runCatching { model.api?.remoteDesktopFfmpegInstallStart() }
                            }
                        },
                        onHostAction = { action ->
                            scope.launch {
                                runCatching { model.api?.remoteDesktopRequirementAction(req.id, action) }
                            }
                        },
                        onTerminal = { cmd ->
                            model.runInTerminal(cmd)
                            onCancel()
                        },
                        onOpenLink = { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                    )
                }
            }

            if (pending.isNotEmpty()) {
                BasicText(
                    "Re-checking every few seconds — this updates by itself once fixed.",
                    style = ppmText(11f, lineHeight = 15f).copy(color = Color.White.copy(alpha = 0.50f)),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val btnShape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .ppmClickable(shape = btnShape, onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Cancel", style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = Color.White))
                }
                val canContinue = currentCaps?.videoReady == true
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(btnShape)
                        .background(if (canContinue) tokens.accent.color else tokens.accent.opacity(0.4).color)
                        .ppmClickable(enabled = canContinue, shape = btnShape) { accepted = true }
                        .testTag("remote-desktop-readiness-continue"),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (viewOnly) "Continue (view only)" else "Continue",
                        style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.accentFg.color),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequirementRowView(
    req: RemoteDesktopRequirement,
    ffmpegInstalling: Boolean,
    onInstallFfmpeg: () -> Unit,
    onHostAction: (String) -> Unit,
    onTerminal: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("remote-desktop-requirement-${req.id}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(
            icon = if (req.ok) ProductIcon.CheckCircle2 else ProductIcon.Circle,
            size = 16.dp,
            tint = if (req.ok) Color(0xFF34D399) else Color(0xFFFCD34D),
            modifier = Modifier.padding(top = 2.dp),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    req.title,
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = Color.White),
                )
                if (!req.ok) {
                    BasicText(
                        if (req.gates == "video") "required" else "for mouse & keyboard",
                        style = ppmText(11f, lineHeight = 15f).copy(color = Color.White.copy(alpha = 0.50f)),
                    )
                }
            }
            if (!req.ok) {
                BasicText(
                    req.detail,
                    style = ppmText(12f, lineHeight = 17f).copy(color = Color.White.copy(alpha = 0.70f)),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (act in req.actions) {
                        val btnShape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier
                                .heightIn(min = 40.dp)
                                .clip(btnShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .ppmClickable(shape = btnShape) {
                                    when (act) {
                                        is RequirementAction.Install -> onInstallFfmpeg()
                                        is RequirementAction.Terminal -> onTerminal(act.command)
                                        is RequirementAction.Link -> onOpenLink(act.url)
                                        is RequirementAction.Host -> onHostAction(act.action)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                if (act is RequirementAction.Install && ffmpegInstalling) "Installing ffmpeg…" else act.label,
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 3. Remote Desktop Viewer (Tablet + Phone) ─────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RemoteDesktopViewer(
    model: AppModel,
    initialCaps: RemoteDesktopCapabilities = RemoteDesktopCapabilities(),
    onClose: () -> Unit = {},
    onSendMessageOverride: ((JsonElement) -> Unit)? = null,
    initialRecorder: H264Mp4Recorder? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var caps by remember { mutableStateOf(initialCaps) }
    var currentDisplayId by remember {
        mutableStateOf(
            initialCaps.displays.firstOrNull { it.primary }?.id ?: initialCaps.displays.firstOrNull()?.id,
        )
    }

    // Connection & stream state
    var connState by remember { mutableStateOf("connecting") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var decoderStatus by remember { mutableStateOf("ready") }
    var decoderErrorMessage by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var frameSize by remember { mutableStateOf(RdSize(1920, 1080)) }

    // Quality, scale & feature toggles seeded from persisted AppModel preferences
    var qualityChoice by remember { mutableStateOf(model.remoteDesktopQuality) }
    var serverPreset by remember { mutableStateOf(DEFAULT_PRESET_ID) }
    var qualityHeld by remember { mutableDoubleStateOf(1.0) }
    var customPercent by remember { mutableIntStateOf(model.remoteDesktopCustomQualityPercent) }
    var customFps by remember { mutableIntStateOf(model.remoteDesktopCustomFps) }
    var customMore by remember { mutableStateOf(model.remoteDesktopCustomQualityMore) }
    var customQualityDialogOpen by remember { mutableStateOf(false) }

    var viewStyle by remember { mutableStateOf(model.remoteDesktopViewStyle) }
    var customScale by remember { mutableDoubleStateOf(model.remoteDesktopCustomScale) }
    var showCursor by remember { mutableStateOf(model.remoteDesktopShowCursor) }
    var codec by remember { mutableStateOf<String?>(model.remoteDesktopCodec) }
    var audioOn by remember { mutableStateOf(false) }
    var privacyOn by remember { mutableStateOf(false) }
    var privacyError by remember { mutableStateOf<String?>(null) }
    var hostModeId by remember { mutableStateOf<String?>(null) }
    var hostOriginalModeId by remember { mutableStateOf<String?>(null) }
    var resolutionError by remember { mutableStateOf<String?>(null) }
    var clipboardSync by remember { mutableStateOf(model.remoteDesktopClipboardSync) }
    var statsVisible by remember { mutableStateOf(model.remoteDesktopStatsVisible) }
    var pendingHostClipboard by remember { mutableStateOf<String?>(null) }

    // Mobile & focus controls
    var inputMode by remember { mutableStateOf("mouse") }
    var transform by remember { mutableStateOf(IDENTITY_TRANSFORM) }
    var virtualCursor by remember { mutableStateOf(RdFraction(0.5, 0.5)) }
    var keyBarOpen by remember { mutableStateOf(false) }
    var mobileSettingsOpen by remember { mutableStateOf(false) }
    var softInputValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = SOFT_KEYBOARD_SENTINEL,
                selection = TextRange(1),
            ),
        )
    }
    val stageFocusRequester = remember { FocusRequester() }
    val keyboardFocusRequester = remember { FocusRequester() }

    // Recording & Screenshot
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
    var activeRecorder by remember { mutableStateOf<H264Mp4Recorder?>(initialRecorder) }
    var recordingElapsedSec by remember { mutableIntStateOf(0) }

    val totalBytesRef = remember { AtomicLong(0L) }
    val decoder = remember {
        H264SurfaceDecoder(
            onFrameSizeChanged = { size -> frameSize = size },
            onStatusChanged = { st, err ->
                decoderStatus = st
                decoderErrorMessage = err
            },
        )
    }
    val audioPlayer = remember { OpusAudioPlayer() }
    var activeSocket by remember { mutableStateOf<SocketConnection?>(null) }
    var clipboardReadJob by remember { mutableStateOf<Job?>(null) }

    fun sendJson(obj: JsonElement) {
        onSendMessageOverride?.invoke(obj)
        activeSocket?.send(obj.toString())
    }

    val moveCoalescer = remember(onSendMessageOverride) {
        PointerMoveCoalescer(
            send = ::sendJson,
            scheduleFrame = { cb ->
                val choreographer = runCatching { Choreographer.getInstance() }.getOrNull()
                if (choreographer != null) {
                    choreographer.postFrameCallback { cb() }
                } else {
                    cb()
                }
            },
        )
    }

    fun stopActiveRecording(rec: H264Mp4Recorder) {
        if (activeRecorder === rec) {
            activeRecorder = null
        }
        model.scope.launch(Dispatchers.IO) {
            val saved = runCatching { rec.stop() }.getOrNull() ?: return@launch
            val preQFile = copyMp4ToDownloads(context, saved)
            if (preQFile != null) {
                withContext(Dispatchers.Main) {
                    runCatching {
                        android.widget.Toast.makeText(
                            context,
                            "Recording saved to ${preQFile.absolutePath}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    DisposableEffect(decoder, audioPlayer) {
        onDispose {
            moveCoalescer.cancel()
            val rec = activeRecorder
            if (rec != null) {
                stopActiveRecording(rec)
            }
            runCatching { decoder.release() }
            runCatching { audioPlayer.release() }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { stageFocusRequester.requestFocus() }
    }

    fun buildQualityMessage(choice: String): JsonElement =
        if (choice == "custom") {
            buildJsonObject {
                put("type", "quality")
                put("preset", "custom")
                put("percent", customPercent)
                put("fps", customFps)
            }
        } else {
            buildJsonObject {
                put("type", "quality")
                put("preset", choice)
            }
        }

    fun selectQuality(choice: String) {
        qualityChoice = choice
        model.setRemoteDesktopQualityPref(choice)
        sendJson(buildQualityMessage(choice))
    }

    fun commitCustomQuality(percent: Int, fps: Int) {
        customPercent = percent
        customFps = fps
        qualityChoice = "custom"
        model.setRemoteDesktopCustomQualityPref(percent, fps, customMore)
        model.setRemoteDesktopQualityPref("custom")
        sendJson(buildQualityMessage("custom"))
    }

    fun requestHostClipboard() {
        clipboardReadJob?.cancel()
        clipboardReadJob = scope.launch {
            delay(350L)
            sendJson(buildJsonObject { put("type", "clipboardRead") })
        }
    }

    fun sendClipboardText(text: String, paste: Boolean, shift: Boolean = false) {
        if (text.isEmpty()) return
        sendJson(
            buildJsonObject {
                put("type", "clipboard")
                put("text", text)
                put("paste", paste)
                put("shift", shift)
            },
        )
    }

    // Fetch capabilities once if empty
    LaunchedEffect(model.api) {
        val api = model.api ?: return@LaunchedEffect
        if (caps.displays.isEmpty() && caps.encoders.isEmpty()) {
            runCatching { api.remoteDesktopCapabilities() }.getOrNull()?.let { res ->
                val parsed = RemoteDesktopCapabilities.from(res)
                caps = parsed
                if (currentDisplayId == null) {
                    currentDisplayId = parsed.displays.firstOrNull { it.primary }?.id ?: parsed.displays.firstOrNull()?.id
                }
            }
        }
    }

    // Recording elapsed timer (`use-remote-desktop-recorder.ts:17,87-96`: auto-stops at 2 hours)
    LaunchedEffect(activeRecorder) {
        val cur = activeRecorder
        if (cur == null) {
            recordingElapsedSec = 0
            return@LaunchedEffect
        }
        while (isActive && activeRecorder === cur) {
            delay(1_000L)
            recordingElapsedSec++
            if (recordingElapsedSec * 1_000L >= MAX_RECORDING_MS) {
                stopActiveRecording(cur)
                break
            }
        }
    }

    // WebSocket session lifecycle
    DisposableEffect(model.api, generation, currentDisplayId) {
        val api = model.api
        var socket: SocketConnection? = null
        val job = if (api != null) {
            scope.launch {
                connState = "connecting"
                errorMessage = null
                privacyOn = false
                privacyError = null
                totalBytesRef.set(0L)
                decoder.reset()

                val nonce = try {
                    val res = api.remoteDesktopMintSession()
                    res["nonce"].string ?: throw IllegalStateException("Missing session nonce")
                } catch (e: Exception) {
                    connState = "error"
                    errorMessage = e.message ?: "Failed to start remote desktop session"
                    return@launch
                }

                val conn = api.openRemoteDesktopConnection()
                socket = conn
                activeSocket = conn

                val pingLoop = launch {
                    while (isActive) {
                        delay(5_000L)
                        conn.send("""{"type":"ping"}""")
                    }
                }

                try {
                    for (ev in conn.events) {
                        when (ev) {
                            SocketConnection.Event.Opened -> {
                                val authMsg = buildJsonObject {
                                    put("type", "auth")
                                    put("nonce", nonce)
                                    currentDisplayId?.let { put("displayId", it) }
                                    put("cursor", showCursor)
                                    codec?.let { put("codec", it) }
                                    put("webrtc", false)
                                }
                                conn.send(authMsg.toString())
                                conn.send(buildQualityMessage(qualityChoice).toString())
                            }
                            is SocketConnection.Event.Text -> {
                                val msg = WireJson.parseOrNull(ev.text) ?: continue
                                when (msg["type"].string) {
                                    "config" -> {
                                        parsePresetId(msg["preset"].string)?.let { serverPreset = it }
                                        val h = msg["held"].double
                                        qualityHeld = if (h != null && h > 0.0 && h < 1.0) h else 1.0
                                        codec = msg["encoder"].string
                                        decoder.configure()
                                        connState = "streaming"
                                    }
                                    "quality" -> {
                                        parsePresetId(msg["preset"].string)?.let { serverPreset = it }
                                        val h = msg["held"].double
                                        qualityHeld = if (h != null && h > 0.0 && h < 1.0) h else 1.0
                                    }
                                    "codec" -> {
                                        codec = msg["encoder"].string
                                    }
                                    "resolution" -> {
                                        hostModeId = msg["modeId"].string
                                        hostOriginalModeId = msg["original"].string
                                        resolutionError = null
                                    }
                                    "resolutionError" -> {
                                        resolutionError = msg["message"].string ?: "The host refused the mode."
                                    }
                                    "privacy" -> {
                                        privacyOn = msg["enabled"].bool == true
                                        privacyError = msg["reason"].string
                                    }
                                    "audio" -> {
                                        val on = msg["enabled"].bool == true
                                        audioOn = on
                                        if (!on) {
                                            audioPlayer.reset()
                                        } else {
                                            val ch = msg["channels"].int ?: 2
                                            val rate = msg["sampleRate"].int ?: 48_000
                                            audioPlayer.configure(ch, rate)
                                        }
                                    }
                                    "clipboard" -> {
                                        if (clipboardSync) {
                                            val text = msg["text"].string
                                            if (!text.isNullOrEmpty()) {
                                                val wrote = runCatching {
                                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                    cm?.setPrimaryClip(ClipData.newPlainText("PPM Remote Desktop", text))
                                                    cm != null
                                                }.getOrDefault(false)
                                                if (!wrote) pendingHostClipboard = text
                                            }
                                        }
                                    }
                                    "error" -> {
                                        connState = "error"
                                        errorMessage = msg["message"].string ?: "Server error"
                                    }
                                }
                            }
                            is SocketConnection.Event.Binary -> {
                                val bytes = ev.bytes.toByteArray()
                                if (bytes.isNotEmpty()) {
                                    totalBytesRef.addAndGet(bytes.size.toLong())
                                    val kind = bytes[0]
                                    if (kind == RD_FRAME_AUDIO) {
                                        audioPlayer.decodePacket(bytes, 1, bytes.size - 1)
                                    } else {
                                        val isKey = kind == RD_FRAME_KEY
                                        decoder.enqueueAccessUnit(bytes, 1, bytes.size - 1, isKey)
                                        activeRecorder?.writeAccessUnit(bytes, 1, bytes.size - 1, isKey)
                                        if (connState == "connecting") connState = "streaming"
                                    }
                                }
                            }
                            is SocketConnection.Event.Closed -> {
                                if (connState != "error") connState = "closed"
                            }
                        }
                    }
                } finally {
                    pingLoop.cancel()
                }
            }
        } else {
            null
        }

        onDispose {
            job?.cancel()
            runCatching { socket?.close() }
            if (activeSocket === socket) activeSocket = null
            runCatching { decoder.reset() }
            runCatching { audioPlayer.reset() }
        }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var softKeyboardFocused by remember { mutableStateOf(false) }
    val stageInput = remember(moveCoalescer) {
        StageInputController(
            moveCoalescer = moveCoalescer,
            onRequestStageFocus = {
                if (!keyBarOpen) {
                    runCatching { stageFocusRequester.requestFocus() }
                }
            },
        )
    }

    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, stageInput) {
        stageInput.onWindowFocusChanged(windowFocused)
    }

    val localView = LocalView.current
    DisposableEffect(localView, stageInput) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            stageInput.onWindowFocusChanged(hasFocus)
        }
        localView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { localView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    // Re-ping on resume or reconnect once if died while backgrounded; release held keys & buttons on pause
    DisposableEffect(lifecycleOwner, stageInput) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    stageInput.releaseAll()
                }
                Lifecycle.Event.ON_RESUME -> {
                    val sock = activeSocket
                    if (sock != null && connState == "streaming") {
                        sock.send("""{"type":"ping"}""")
                    } else if (connState == "closed") {
                        generation++
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun takeScreenshot() {
        val sv = surfaceViewRef ?: return
        captureSurfaceViewBitmap(sv, targetSize = frameSize) { bmp ->
            if (bmp != null) {
                model.scope.launch(Dispatchers.IO) {
                    val preQFile = saveBitmapToDownloads(context, bmp)
                    if (preQFile != null) {
                        withContext(Dispatchers.Main) {
                            runCatching {
                                android.widget.Toast.makeText(
                                    context,
                                    "Screenshot saved to ${preQFile.absolutePath}",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    fun toggleRecording() {
        val cur = activeRecorder
        if (cur != null) {
            stopActiveRecording(cur)
        } else {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US))
            val outFile = File(context.cacheDir, "ppm-remote-$stamp.mp4")
            activeRecorder = H264Mp4Recorder(outFile, frameSize.width, frameSize.height) { frameSize }
        }
    }

    val overlayMessage = when {
        decoderStatus == "error" -> decoderErrorMessage ?: "Video decoder error"
        connState == "error" -> errorMessage ?: "Connection error"
        connState == "closed" -> "Disconnected"
        connState == "connecting" && model.api != null -> "Connecting…"
        else -> null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(stageFocusRequester)
            .onFocusChanged { stageInput.onStageFocusChanged(it.isFocused) }
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val nativeCode = keyEvent.nativeKeyEvent.keyCode
                val w3c = androidKeyCodeToW3cCode(nativeCode) ?: return@onPreviewKeyEvent false
                val isDown = keyEvent.type == KeyEventType.KeyDown
                val ctrl = keyEvent.isCtrlPressed
                val meta = keyEvent.isMetaPressed
                val alt = keyEvent.isAltPressed
                if (clipboardSync && isPasteKeyCombo(w3c, ctrl = ctrl, meta = meta, alt = alt)) {
                    // Drop both KeyDown and KeyUp of Ctrl+V / Cmd+V when clipboard sync is active
                    // so KeyV up is never forwarded without a matching KeyV down (`use-remote-input-capture.ts:116-126`).
                    if (isDown) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (clipText.isNotEmpty()) {
                            sendClipboardText(clipText, paste = true, shift = keyEvent.isShiftPressed)
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (isDown && clipboardSync && isCopyKeyCombo(w3c, ctrl = ctrl, meta = meta, alt = alt)) {
                    requestHostClipboard()
                }
                sendJson(
                    buildJsonObject {
                        put("type", "key")
                        put("code", w3c)
                        put("down", isDown)
                    },
                )
                true
            }
            .testTag(if (mobile) "remote-desktop-mobile-view" else "remote-desktop-window"),
    ) {
        val densityScale = density.density.toDouble().coerceAtLeast(0.1)
        val stageVideoRect = computeStageVideoRect(
            containerWidthPx = containerSize.width.toDouble(),
            containerHeightPx = containerSize.height.toDouble(),
            frameSize = frameSize,
            viewStyle = viewStyle,
            customScale = customScale,
            mobile = mobile,
            densityScale = densityScale,
        )

        // Video Stage
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .pointerInteropFilter { motion ->
                    stageInput.onMotionEvent(
                        motion = motion,
                        containerSize = containerSize,
                        frameSize = frameSize,
                        viewStyle = viewStyle,
                        customScale = customScale,
                        mobile = mobile,
                        inputMode = inputMode,
                        densityScale = density.density.toDouble().coerceAtLeast(0.1),
                        transform = transform,
                        onTransformChange = { transform = it },
                        virtualCursor = virtualCursor,
                        onVirtualCursorChange = { virtualCursor = it },
                    )
                }
                .testTag("remote-desktop-stage"),
            contentAlignment = Alignment.Center,
        ) {
            val cssSize = if (!mobile) canvasCssSize(viewStyle, customScale, frameSize) else null
            val surfaceSizeModifier = when {
                containerSize.width > 0 && containerSize.height > 0 -> {
                    with(density) {
                        Modifier.size(
                            stageVideoRect.width.toFloat().toDp(),
                            stageVideoRect.height.toFloat().toDp(),
                        )
                    }
                }
                cssSize != null -> {
                    with(density) { Modifier.size(cssSize.width.toDp(), cssSize.height.toDp()) }
                }
                else -> {
                    val aspect = frameSize.width.coerceAtLeast(1).toFloat() / frameSize.height.coerceAtLeast(1).toFloat()
                    Modifier.aspectRatio(aspect)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale.toFloat()
                        scaleY = transform.scale.toFloat()
                        translationX = transform.panX.toFloat()
                        translationY = transform.panY.toFloat()
                    },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            if (frameSize.width > 0 && frameSize.height > 0) {
                                runCatching { holder.setFixedSize(frameSize.width, frameSize.height) }
                            }
                            holder.addCallback(
                                object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        decoder.attachSurface(holder.surface)
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        decoder.attachSurface(holder.surface)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        decoder.attachSurface(null)
                                    }
                                },
                            )
                            surfaceViewRef = this
                        }
                    },
                    update = { sv ->
                        if (frameSize.width > 0 && frameSize.height > 0) {
                            runCatching { sv.holder.setFixedSize(frameSize.width, frameSize.height) }
                        }
                    },
                    modifier = surfaceSizeModifier.testTag("remote-desktop-surface"),
                )

                // Virtual cursor marker in mobile mouse mode
                if (mobile && inputMode == "mouse") {
                    val cursorX = (stageVideoRect.left + virtualCursor.xFrac * stageVideoRect.width).roundToInt()
                    val cursorY = (stageVideoRect.top + virtualCursor.yFrac * stageVideoRect.height).roundToInt()
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(cursorX - 7, cursorY - 7) }
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(tokens.accent.color)
                            .cssBorder(Color.White, CircleShape, width = 2.dp)
                            .testTag("remote-desktop-virtual-cursor"),
                    )
                }
            }

            // Hidden soft-keyboard input field for IME text entry (Telex / CJK / predictive)
            BasicTextField(
                value = softInputValue,
                onValueChange = { next ->
                    val msgs = resolveSoftKeyboardCommit(
                        text = next.text,
                        selectionStart = next.selection.start,
                        hasActiveComposition = next.composition != null,
                    )
                    if (msgs == null) {
                        // IME composition still in flight (e.g. Telex / CJK); preserve composing buffer
                        softInputValue = next
                    } else {
                        for (m in msgs) sendJson(m)
                        softInputValue = TextFieldValue(
                            text = SOFT_KEYBOARD_SENTINEL,
                            selection = TextRange(1),
                        )
                    }
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(keyboardFocusRequester)
                    .onFocusChanged { state ->
                        if (softKeyboardFocused && !state.isFocused) {
                            stageInput.releaseAll()
                        }
                        softKeyboardFocused = state.isFocused
                    }
                    .testTag("remote-desktop-virtual-keyboard-input"),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        sendJson(buildJsonObject { put("type", "key"); put("code", "Enter"); put("down", true) })
                        sendJson(buildJsonObject { put("type", "key"); put("code", "Enter"); put("down", false) })
                    },
                ),
            )
        }

        // Stats Overlay (top-left)
        if (statsVisible) {
            RemoteDesktopStatsOverlay(
                frameSize = frameSize,
                getFrameCount = { decoder.frameCount.get() },
                getTotalBytes = { totalBytesRef.get() },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }

        // Pending Host Clipboard Notice
        if (pendingHostClipboard != null) {
            val noticeShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .clip(noticeShape)
                    .background(Color.Black.copy(alpha = 0.85f))
                    .cssBorder(Color.White.copy(alpha = 0.15f), noticeShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("remote-desktop-clipboard-notice"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(
                    "Host copied text to clipboard",
                    style = ppmText(12f, lineHeight = 16f).copy(color = Color.White),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(tokens.accent.color)
                        .ppmClickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("PPM Remote Desktop", pendingHostClipboard))
                            pendingHostClipboard = null
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    BasicText("Copy", style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.accentFg.color))
                }
                Box(
                    Modifier.size(20.dp).ppmClickable { pendingHostClipboard = null },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 12.dp, Color.White.copy(alpha = 0.7f))
                }
            }
        }

        // Tablet Top-Right Floating Toolbar vs Mobile Bottom Bar
        if (!mobile) {
            RemoteDesktopTabletToolbar(
                caps = caps,
                currentDisplayId = currentDisplayId,
                onSelectDisplay = { currentDisplayId = it },
                qualityChoice = qualityChoice,
                serverPreset = serverPreset,
                qualityHeld = qualityHeld,
                onSetQuality = ::selectQuality,
                onOpenCustomQuality = { customQualityDialogOpen = true },
                codec = codec,
                onSetCodec = { enc ->
                    codec = enc
                    model.setRemoteDesktopCodecPref(enc)
                    if (enc != null) {
                        sendJson(buildJsonObject { put("type", "codec"); put("encoder", enc) })
                    }
                },
                hostModeId = hostModeId,
                hostOriginalModeId = hostOriginalModeId,
                resolutionError = resolutionError,
                onSetHostMode = { modeId ->
                    resolutionError = null
                    sendJson(buildJsonObject { put("type", "resolution"); put("modeId", modeId) })
                },
                viewStyle = viewStyle,
                customScale = customScale,
                onSetViewStyle = { nextStyle ->
                    viewStyle = nextStyle
                    model.setRemoteDesktopViewStylePref(nextStyle)
                },
                onSetCustomScale = { nextScale ->
                    val clamped = clampCustomScale(nextScale)
                    customScale = clamped
                    model.setRemoteDesktopCustomScalePref(clamped)
                },
                showCursor = showCursor,
                onSetShowCursor = { show ->
                    showCursor = show
                    model.setRemoteDesktopShowCursorPref(show)
                    sendJson(buildJsonObject { put("type", "cursor"); put("show", show) })
                },
                audioOn = audioOn,
                onSetAudioOn = { on ->
                    audioOn = on
                    if (!on) audioPlayer.reset()
                    sendJson(buildJsonObject { put("type", "audio"); put("enabled", on) })
                },
                privacyOn = privacyOn,
                privacyError = privacyError,
                onSetPrivacyOn = { on ->
                    privacyError = null
                    sendJson(buildJsonObject { put("type", "privacy"); put("enabled", on) })
                },
                clipboardEnabled = clipboardSync,
                onSetClipboardEnabled = { enabled ->
                    clipboardSync = enabled
                    model.setRemoteDesktopClipboardSyncPref(enabled)
                },
                statsVisible = statsVisible,
                onToggleStats = {
                    val next = !statsVisible
                    statsVisible = next
                    model.setRemoteDesktopStatsVisiblePref(next)
                },
                recording = activeRecorder != null,
                recordingElapsedSec = recordingElapsedSec,
                onToggleRecording = ::toggleRecording,
                onScreenshot = ::takeScreenshot,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .testTag("remote-desktop-mobile-bottom-bar"),
            ) {
                if (mobileSettingsOpen) {
                    RemoteDesktopMobileSettingsPanel(
                        caps = caps,
                        statsVisible = statsVisible,
                        onToggleStats = {
                            val next = !statsVisible
                            statsVisible = next
                            model.setRemoteDesktopStatsVisiblePref(next)
                        },
                        showCursor = showCursor,
                        onSetShowCursor = { show ->
                            showCursor = show
                            model.setRemoteDesktopShowCursorPref(show)
                            sendJson(buildJsonObject { put("type", "cursor"); put("show", show) })
                        },
                        clipboardEnabled = clipboardSync,
                        onSetClipboardEnabled = { enabled ->
                            clipboardSync = enabled
                            model.setRemoteDesktopClipboardSyncPref(enabled)
                        },
                        audioOn = audioOn,
                        onSetAudioOn = { on ->
                            audioOn = on
                            if (!on) audioPlayer.reset()
                            sendJson(buildJsonObject { put("type", "audio"); put("enabled", on) })
                        },
                        privacyOn = privacyOn,
                        privacyError = privacyError,
                        onSetPrivacyOn = { on ->
                            privacyError = null
                            sendJson(buildJsonObject { put("type", "privacy"); put("enabled", on) })
                        },
                        hostModeId = hostModeId,
                        hostOriginalModeId = hostOriginalModeId,
                        resolutionError = resolutionError,
                        onSetHostMode = { modeId ->
                            resolutionError = null
                            sendJson(buildJsonObject { put("type", "resolution"); put("modeId", modeId) })
                        },
                        recording = activeRecorder != null,
                        onToggleRecording = ::toggleRecording,
                        onScreenshot = ::takeScreenshot,
                    )
                }
                if (keyBarOpen) {
                    RemoteDesktopMobileKeyBarView(
                        onSendMessage = ::sendJson,
                        onCopyCombo = { if (clipboardSync) requestHostClipboard() },
                    )
                }
                RemoteDesktopMobileToolbarView(
                    displayLabel = if (caps.displays.size > 1) {
                        caps.displays.firstOrNull { it.id == currentDisplayId }?.label ?: caps.displays.first().label
                    } else {
                        null
                    },
                    onNextDisplay = {
                        if (caps.displays.size > 1) {
                            val idx = caps.displays.indexOfFirst { it.id == currentDisplayId }
                            val next = caps.displays[(idx + 1) % caps.displays.size]
                            currentDisplayId = next.id
                        }
                    },
                    qualityLabel = shortQualityLabel(qualityChoice),
                    onNextQuality = { selectQuality(nextQualityChoice(qualityChoice)) },
                    mode = inputMode,
                    onToggleMode = { inputMode = if (inputMode == "mouse") "touch" else "mouse" },
                    onOpenKeyboard = {
                        keyBarOpen = !keyBarOpen
                        if (keyBarOpen) {
                            runCatching { keyboardFocusRequester.requestFocus() }
                        } else {
                            runCatching { stageFocusRequester.requestFocus() }
                        }
                    },
                    onResetZoom = { transform = IDENTITY_TRANSFORM },
                    settingsOpen = mobileSettingsOpen,
                    onToggleSettings = { mobileSettingsOpen = !mobileSettingsOpen },
                    onClose = onClose,
                )
            }
        }

        // Connection / Error Overlay
        if (overlayMessage != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.70f))
                    .testTag("remote-desktop-status-overlay"),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(ProductIcon.Monitor, 24.dp, Color.White.copy(alpha = 0.7f))
                    BasicText(
                        overlayMessage,
                        style = ppmText(13f, lineHeight = 18f).copy(color = Color.White),
                    )
                    if ((connState == "error" || connState == "closed") && decoderStatus != "error") {
                        val btnShape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .clip(btnShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .ppmClickable(shape = btnShape) { generation++ }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("remote-desktop-reconnect"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(ProductIcon.RotateCw, 14.dp, Color.White)
                            BasicText("Reconnect", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White))
                        }
                    }
                }
            }
        }
    }

    if (customQualityDialogOpen) {
        RemoteDesktopCustomQualityDialog(
            percent = customPercent,
            fps = customFps,
            more = customMore,
            frameSize = frameSize,
            onSetMore = { nextMore ->
                customMore = nextMore
                model.setRemoteDesktopCustomQualityPref(customPercent, customFps, nextMore)
                val clamped = clampCustomQualityPercent(customPercent, nextMore)
                if (clamped != customPercent) commitCustomQuality(clamped, customFps)
            },
            onCommit = ::commitCustomQuality,
            onDismiss = { customQualityDialogOpen = false },
        )
    }
}

internal class StageInputController(
    private val moveCoalescer: PointerMoveCoalescer,
    private val onRequestStageFocus: () -> Unit = {},
) {
    private var singleTrack: SingleTrack? = null
    private var twoTrack: TwoFingerTrack? = null
    private val mouseHeldButtons = linkedSetOf<String>()
    private var lastMouseFrac: RdFraction = RdFraction(0.5, 0.5)
    private var lastTouchMode: String = "mouse"
    private var lastTransform: ZoomPanTransform = IDENTITY_TRANSFORM
    private var lastVirtualCursor: RdFraction = RdFraction(0.5, 0.5)
    private var lastDensityScale: Double = 1.0
    private var stageFocused: Boolean = false
    private var windowFocused: Boolean = true

    val heldMouseButtons: Set<String>
        get() = mouseHeldButtons.toSet()

    fun onStageFocusChanged(isFocused: Boolean) {
        val wasFocused = stageFocused
        stageFocused = isFocused
        if (wasFocused && !isFocused) {
            releaseAll()
        }
    }

    fun onWindowFocusChanged(isFocused: Boolean) {
        val wasFocused = windowFocused
        windowFocused = isFocused
        if (wasFocused && !isFocused) {
            releaseAll()
        }
    }

    fun releaseAll() {
        val held = mouseHeldButtons.toList()
        mouseHeldButtons.clear()
        for (btn in held) {
            moveCoalescer.sendImmediate(
                pointerJsonMessage(lastMouseFrac.xFrac, lastMouseFrac.yFrac, btn, false),
            )
        }
        val st = singleTrack
        if (st != null) {
            val msgs = resolveSingleTouchCancel(
                track = st,
                mode = lastTouchMode,
                transform = lastTransform,
                cursor = lastVirtualCursor,
                densityScale = lastDensityScale,
            )
            moveCoalescer.sendImmediateAll(msgs)
        }
        singleTrack = null
        twoTrack = null
        moveCoalescer.sendImmediate(buildJsonObject { put("type", "releaseAll") })
    }

    private fun protocolButtonsFromBits(buttonBits: Int): List<String> {
        val out = ArrayList<String>(2)
        androidMouseButtonToProtocol(buttonBits and MotionEvent.BUTTON_PRIMARY)?.let { out += it }
        androidMouseButtonToProtocol(buttonBits and MotionEvent.BUTTON_SECONDARY)?.let {
            if (it !in out) out += it
        }
        if (out.isEmpty()) {
            androidMouseButtonToProtocol(buttonBits)?.let { out += it }
        }
        return out
    }

    fun onMotionEvent(
        motion: MotionEvent,
        containerSize: IntSize,
        frameSize: RdSize = RdSize(1920, 1080),
        viewStyle: String = DEFAULT_VIEW_STYLE,
        customScale: Double = DEFAULT_CUSTOM_SCALE,
        mobile: Boolean = false,
        inputMode: String = "mouse",
        densityScale: Double = 1.0,
        transform: ZoomPanTransform = IDENTITY_TRANSFORM,
        onTransformChange: (ZoomPanTransform) -> Unit = {},
        virtualCursor: RdFraction = RdFraction(0.5, 0.5),
        onVirtualCursorChange: (RdFraction) -> Unit = {},
    ): Boolean {
        val curDensityScale = densityScale.coerceAtLeast(0.1)
        lastTouchMode = if (mobile) inputMode else "touch"
        lastTransform = transform
        lastVirtualCursor = virtualCursor
        lastDensityScale = curDensityScale
        val videoRect = computeStageVideoRect(
            containerWidthPx = containerSize.width.toDouble(),
            containerHeightPx = containerSize.height.toDouble(),
            frameSize = frameSize,
            viewStyle = viewStyle,
            customScale = customScale,
            mobile = mobile,
            densityScale = curDensityScale,
        )
        val pointerCount = motion.pointerCount
        val isMouse = motion.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
            motion.buttonState != 0 ||
            mouseHeldButtons.isNotEmpty() ||
            motion.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            motion.actionMasked == MotionEvent.ACTION_SCROLL ||
            motion.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            motion.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE

        if (isMouse) {
            when (motion.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                    val frac = fractionFromZoomedPoint(
                        motion.x.toDouble(),
                        motion.y.toDouble(),
                        videoRect,
                        transform,
                    )
                    lastMouseFrac = frac
                    moveCoalescer.enqueueMove(frac.xFrac, frac.yFrac)
                }
                MotionEvent.ACTION_SCROLL -> {
                    val vScroll = motion.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble()
                    if (vScroll != 0.0) {
                        val dy = vScroll * WHEEL_DELTA
                        moveCoalescer.sendImmediate(
                            buildJsonObject {
                                put("type", "wheel")
                                put("dy", dy)
                            },
                        )
                    }
                }
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                    onRequestStageFocus()
                    val frac = fractionFromZoomedPoint(
                        motion.x.toDouble(),
                        motion.y.toDouble(),
                        videoRect,
                        transform,
                    )
                    lastMouseFrac = frac
                    val candidates = if (motion.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && motion.actionButton != 0) {
                        listOfNotNull(androidMouseButtonToProtocol(motion.actionButton))
                    } else {
                        val bits = if (motion.buttonState != 0) motion.buttonState else MotionEvent.BUTTON_PRIMARY
                        protocolButtonsFromBits(bits)
                    }
                    for (btn in candidates) {
                        if (mouseHeldButtons.add(btn)) {
                            moveCoalescer.sendImmediate(pointerJsonMessage(frac.xFrac, frac.yFrac, btn, true))
                        }
                    }
                }
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val frac = fractionFromZoomedPoint(
                        motion.x.toDouble(),
                        motion.y.toDouble(),
                        videoRect,
                        transform,
                    )
                    lastMouseFrac = frac
                    val toRelease = if (motion.actionButton != 0) {
                        listOfNotNull(androidMouseButtonToProtocol(motion.actionButton))
                    } else {
                        val stillHeld = protocolButtonsFromBits(motion.buttonState).toSet()
                        val diff = mouseHeldButtons.filter { it !in stillHeld }
                        diff.ifEmpty { protocolButtonsFromBits(motion.buttonState) }
                    }
                    for (btn in toRelease) {
                        if (mouseHeldButtons.remove(btn) || toRelease.size == 1) {
                            moveCoalescer.sendImmediate(pointerJsonMessage(frac.xFrac, frac.yFrac, btn, false))
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val frac = fractionFromZoomedPoint(
                        motion.x.toDouble(),
                        motion.y.toDouble(),
                        videoRect,
                        transform,
                    )
                    lastMouseFrac = frac
                    val toRelease = if (mouseHeldButtons.isNotEmpty()) {
                        mouseHeldButtons.toList()
                    } else {
                        protocolButtonsFromBits(motion.buttonState)
                    }
                    mouseHeldButtons.clear()
                    for (btn in toRelease) {
                        moveCoalescer.sendImmediate(pointerJsonMessage(frac.xFrac, frac.yFrac, btn, false))
                    }
                }
            }
            return true
        }

        when (motion.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onRequestStageFocus()
                twoTrack = null
                singleTrack = beginSingleTouch(motion.x.toDouble(), motion.y.toDouble(), videoRect)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    singleTrack = null
                    twoTrack = beginTwoFingerTouch(
                        RdPoint(motion.getX(0).toDouble(), motion.getY(0).toDouble()),
                        RdPoint(motion.getX(1).toDouble(), motion.getY(1).toDouble()),
                        transform.scale,
                    )
                } else {
                    singleTrack = null
                    twoTrack = null
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val st = singleTrack
                val tt = twoTrack
                if (st != null && pointerCount == 1) {
                    val effectiveMode = if (mobile) inputMode else "touch"
                    val r = advanceSingleTouch(
                        track = st,
                        x = motion.x.toDouble(),
                        y = motion.y.toDouble(),
                        mode = effectiveMode,
                        transform = transform,
                        cursor = virtualCursor,
                        densityScale = curDensityScale,
                    )
                    r.message?.let { msg ->
                        val obj = msg.jsonObject
                        if (obj.containsKey("button")) {
                            moveCoalescer.sendImmediate(msg)
                        } else {
                            val xf = obj["xFrac"].double ?: 0.0
                            val yf = obj["yFrac"].double ?: 0.0
                            moveCoalescer.enqueueMove(xf, yf)
                        }
                    }
                    r.cursor?.let { cur ->
                        onVirtualCursorChange(cur)
                        if (r.sendCursorMove) {
                            moveCoalescer.enqueueMove(cur.xFrac, cur.yFrac)
                        }
                    }
                } else if (tt != null && pointerCount == 2) {
                    val r = advanceTwoFingerTouch(
                        track = tt,
                        pa = RdPoint(motion.getX(0).toDouble(), motion.getY(0).toDouble()),
                        pb = RdPoint(motion.getX(1).toDouble(), motion.getY(1).toDouble()),
                        currentTransform = transform,
                        densityScale = curDensityScale,
                    )
                    r.transform?.let(onTransformChange)
                    r.wheelDeltaPx?.let { wPx ->
                        if (wPx != 0.0) {
                            val dy = wheelDeltaForTouchDrag(wPx, curDensityScale)
                            moveCoalescer.sendImmediate(
                                buildJsonObject {
                                    put("type", "wheel")
                                    put("dy", dy)
                                },
                            )
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val st = singleTrack
                val tt = twoTrack
                if (st != null && pointerCount == 1) {
                    val effectiveMode = if (mobile) inputMode else "touch"
                    val msgs = resolveSingleTouchEnd(
                        track = st,
                        x = motion.x.toDouble(),
                        y = motion.y.toDouble(),
                        mode = effectiveMode,
                        transform = transform,
                        cursor = virtualCursor,
                        densityScale = curDensityScale,
                    )
                    moveCoalescer.sendImmediateAll(msgs)
                    singleTrack = null
                } else if (tt != null) {
                    val msgs = resolveTwoFingerEnd(tt, videoRect, transform)
                    moveCoalescer.sendImmediateAll(msgs)
                    twoTrack = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                val st = singleTrack
                if (st != null) {
                    val effectiveMode = if (mobile) inputMode else "touch"
                    val msgs = resolveSingleTouchCancel(
                        track = st,
                        mode = effectiveMode,
                        transform = transform,
                        cursor = virtualCursor,
                        densityScale = curDensityScale,
                    )
                    moveCoalescer.sendImmediateAll(msgs)
                }
                singleTrack = null
                twoTrack = null
            }
        }
        return true
    }
}

// ── 4. Stats Overlay ──────────────────────────────────────────────────

@Composable
internal fun RemoteDesktopStatsOverlay(
    frameSize: RdSize,
    getFrameCount: () -> Int,
    getTotalBytes: () -> Long,
    modifier: Modifier = Modifier,
) {
    var stats by remember { mutableStateOf(RemoteDesktopStats()) }

    LaunchedEffect(Unit) {
        var prev = RemoteDesktopStatsSample(
            frameCount = getFrameCount(),
            totalBytes = getTotalBytes(),
            atMs = System.currentTimeMillis(),
        )
        while (isActive) {
            delay(500L)
            val next = RemoteDesktopStatsSample(
                frameCount = getFrameCount(),
                totalBytes = getTotalBytes(),
                atMs = System.currentTimeMillis(),
            )
            stats = computeRemoteDesktopStats(prev, next)
            prev = next
        }
    }

    val pillShape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(pillShape)
            .background(Color.Black.copy(alpha = 0.65f))
            .cssBorder(Color.White.copy(alpha = 0.12f), pillShape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("remote-desktop-stats-overlay"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "${frameSize.width}×${frameSize.height}",
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = Color.White.copy(alpha = 0.9f)),
        )
        BasicText(
            "${stats.fps.roundToInt()} fps",
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = Color.White.copy(alpha = 0.9f)),
        )
        BasicText(
            "${stats.kbps.roundToInt()} KB/s",
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = Color.White.copy(alpha = 0.9f)),
        )
    }
}

// ── 5. Tablet Floating Toolbar & Menus ────────────────────────────────

@Composable
internal fun RemoteDesktopTabletToolbar(
    caps: RemoteDesktopCapabilities,
    currentDisplayId: String?,
    onSelectDisplay: (String) -> Unit,
    qualityChoice: String,
    serverPreset: String,
    qualityHeld: Double,
    onSetQuality: (String) -> Unit,
    onOpenCustomQuality: () -> Unit,
    codec: String?,
    onSetCodec: (String?) -> Unit,
    hostModeId: String?,
    hostOriginalModeId: String?,
    resolutionError: String?,
    onSetHostMode: (String) -> Unit,
    viewStyle: String,
    customScale: Double,
    onSetViewStyle: (String) -> Unit,
    onSetCustomScale: (Double) -> Unit,
    showCursor: Boolean,
    onSetShowCursor: (Boolean) -> Unit,
    audioOn: Boolean,
    onSetAudioOn: (Boolean) -> Unit,
    privacyOn: Boolean,
    privacyError: String?,
    onSetPrivacyOn: (Boolean) -> Unit,
    clipboardEnabled: Boolean,
    onSetClipboardEnabled: (Boolean) -> Unit,
    statsVisible: Boolean,
    onToggleStats: () -> Unit,
    recording: Boolean,
    recordingElapsedSec: Int,
    onToggleRecording: () -> Unit,
    onScreenshot: () -> Unit,
    modifier: Modifier = Modifier,
    localScreenOverride: RdSize? = null,
) {
    val tokens = LocalPpmTokens.current
    val configuration = LocalConfiguration.current
    var displayMenuOpen by remember { mutableStateOf(false) }
    var settingsMenuOpen by remember { mutableStateOf(false) }

    val localScreen = localScreenOverride ?: RdSize(
        width = configuration.screenWidthDp.coerceAtLeast(1),
        height = configuration.screenHeightDp.coerceAtLeast(1),
    )
    val resolutions = remember(caps.resolutions.modes) { resolutionChoices(caps.resolutions.modes) }
    val fitLocal = remember(caps.resolutions.modes, localScreen, hostModeId) {
        fitLocalMode(caps.resolutions.modes, localScreen, hostModeId)
    }

    Row(
        modifier.testTag("remote-desktop-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (caps.displays.size > 1) {
            Box {
                ToolbarIconButton(
                    icon = ProductIcon.Monitor,
                    active = displayMenuOpen,
                    onClick = { displayMenuOpen = !displayMenuOpen },
                    tag = "remote-desktop-display-trigger",
                )
                if (displayMenuOpen) {
                    Dialog(onDismissRequest = { displayMenuOpen = false }) {
                        val menuShape = RoundedCornerShape(8.dp)
                        Column(
                            Modifier
                                .width(240.dp)
                                .clip(menuShape)
                                .background(tokens.panel.over(tokens.bgSolid).color)
                                .cssBorder(tokens.border.color, menuShape)
                                .padding(6.dp),
                        ) {
                            BasicText(
                                "Display",
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                            val activeId = currentDisplayId
                                ?: caps.displays.firstOrNull { it.primary }?.id
                                ?: caps.displays.first().id
                            for (d in caps.displays) {
                                val selected = d.id == activeId
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (selected) tokens.accent.opacity(0.16).color else Color.Transparent)
                                        .ppmClickable {
                                            onSelectDisplay(d.id)
                                            displayMenuOpen = false
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                        .testTag("remote-desktop-display-${d.id}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    BasicText(
                                        "${d.label}${if (d.primary) " (main)" else ""} · ${d.width}×${d.height}",
                                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                                    )
                                    if (selected) PpmIcon(ProductIcon.Check, 13.dp, tokens.accent.color)
                                }
                            }
                        }
                    }
                }
            }
        }

        Box {
            ToolbarIconButton(
                icon = ProductIcon.SlidersHorizontal,
                active = settingsMenuOpen,
                onClick = { settingsMenuOpen = !settingsMenuOpen },
                tag = "remote-desktop-settings-trigger",
            )
            if (settingsMenuOpen) {
                Dialog(onDismissRequest = { settingsMenuOpen = false }) {
                    val menuShape = RoundedCornerShape(8.dp)
                    Column(
                        Modifier
                            .width(270.dp)
                            .heightIn(max = 520.dp)
                            .clip(menuShape)
                            .background(tokens.panel.over(tokens.bgSolid).color)
                            .cssBorder(tokens.border.color, menuShape)
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp)
                            .testTag("remote-desktop-settings-menu"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // Scale section
                        BasicText(
                            "Scale",
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color),
                        )
                        for (style in VIEW_STYLE_ORDER) {
                            val selected = viewStyle == style
                            MenuRadioRow(
                                label = VIEW_STYLE_LABELS[style] ?: style,
                                selected = selected,
                                onClick = { onSetViewStyle(style) },
                                tag = "remote-desktop-view-style-$style",
                            )
                        }
                        if (viewStyle == "custom") {
                            CustomScaleZoomRow(
                                scale = customScale,
                                onSetScale = onSetCustomScale,
                            )
                        }

                        MenuDivider()

                        // Image quality section
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                "Image quality",
                                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                            if (qualityHeld < 1.0) {
                                BasicText(
                                    "${(qualityHeld * 100).roundToInt()}% — link limited",
                                    Modifier.testTag("remote-desktop-quality-held"),
                                    style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text2.color),
                                )
                            }
                        }
                        for (id in QUALITY_PRESET_ORDER) {
                            MenuRadioRow(
                                label = QUALITY_PRESETS[id]?.label ?: id,
                                selected = qualityChoice == id,
                                onClick = { onSetQuality(id) },
                                tag = "remote-desktop-quality-$id",
                            )
                        }
                        MenuRadioRow(
                            label = "Custom…",
                            selected = qualityChoice == "custom",
                            onClick = {
                                settingsMenuOpen = false
                                onOpenCustomQuality()
                            },
                            tag = "remote-desktop-quality-custom",
                        )

                        // Codec section
                        if (caps.encoders.size > 1) {
                            MenuDivider()
                            BasicText(
                                "Codec",
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                            MenuRadioRow(
                                label = "Auto · ${codecLabel(caps.encoders.first())}",
                                selected = codec == null,
                                onClick = { onSetCodec(null) },
                                tag = "remote-desktop-codec-auto",
                            )
                            for (enc in caps.encoders) {
                                MenuRadioRow(
                                    label = codecLabel(enc),
                                    selected = codec == enc,
                                    onClick = { onSetCodec(enc) },
                                    tag = "remote-desktop-codec-$enc",
                                )
                            }
                        }

                        // Host resolution section
                        if (caps.inputReady && resolutions.size > 1) {
                            MenuDivider()
                            BasicText(
                                "Resolution",
                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                            if (resolutionError != null) {
                                BasicText(
                                    resolutionError,
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color),
                                )
                            }
                            if (hostOriginalModeId != null && hostOriginalModeId != hostModeId) {
                                MenuRadioRow(
                                    label = "Original",
                                    selected = false,
                                    onClick = { onSetHostMode(hostOriginalModeId) },
                                    tag = "remote-desktop-resolution-original",
                                )
                            }
                            if (fitLocal != null) {
                                MenuRadioRow(
                                    label = "Fit local (${fitLocal.width} × ${fitLocal.height})",
                                    selected = false,
                                    onClick = { onSetHostMode(fitLocal.id) },
                                    tag = "remote-desktop-resolution-fit-local",
                                )
                            }
                            val activeMode = hostModeId ?: resolutions.firstOrNull { it.current }?.id
                            for (r in resolutions) {
                                MenuRadioRow(
                                    label = resolutionLabel(r),
                                    selected = r.id == activeMode,
                                    onClick = { onSetHostMode(r.id) },
                                    tag = "remote-desktop-resolution-${r.width}x${r.height}",
                                )
                            }
                        }

                        MenuDivider()

                        // Toggles
                        MenuCheckRow(
                            label = "Show remote cursor",
                            checked = showCursor,
                            onClick = { onSetShowCursor(!showCursor) },
                            tag = "remote-desktop-cursor-toggle",
                        )
                        MenuCheckRow(
                            label = "Play host audio",
                            checked = audioOn,
                            enabled = caps.audio.available || caps.audio.reason == null,
                            onClick = { onSetAudioOn(!audioOn) },
                            tag = "remote-desktop-audio-toggle",
                        )
                        MenuCheckRow(
                            label = "Privacy mode",
                            checked = privacyOn,
                            enabled = caps.privacy.available || privacyOn || caps.privacy.reason == null,
                            onClick = { onSetPrivacyOn(!privacyOn) },
                            tag = "remote-desktop-privacy-toggle",
                        )
                        MenuCheckRow(
                            label = "Sync clipboard",
                            checked = clipboardEnabled,
                            onClick = { onSetClipboardEnabled(!clipboardEnabled) },
                            tag = "remote-desktop-clipboard-toggle",
                        )

                        MenuDivider()

                        // Screenshot & Recording
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .ppmClickable {
                                    settingsMenuOpen = false
                                    onScreenshot()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("remote-desktop-screenshot"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(ProductIcon.Image, 13.dp, tokens.text.color)
                            BasicText("Save screenshot", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color))
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .ppmClickable {
                                    settingsMenuOpen = false
                                    onToggleRecording()
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("remote-desktop-record-toggle"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PpmIcon(
                                icon = if (recording) ProductIcon.Square else ProductIcon.Circle,
                                size = 13.dp,
                                tint = if (recording) tokens.error.color else tokens.text.color,
                            )
                            BasicText(
                                if (recording) {
                                    "Stop recording · ${formatRemoteDesktopElapsed(recordingElapsedSec)} (no audio)"
                                } else {
                                    "Start recording (no audio)"
                                },
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                        }
                    }
                }
            }
        }

        ToolbarIconButton(
            icon = ProductIcon.Activity,
            active = statsVisible,
            onClick = onToggleStats,
            tag = "remote-desktop-stats-trigger",
        )
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ProductIcon,
    active: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(28.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = if (active) 0.75f else 0.45f))
            .ppmClickable(shape = shape, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        PpmIcon(icon, 14.dp, Color.White.copy(alpha = if (active) 1f else 0.75f))
    }
}

@Composable
private fun MenuRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) tokens.accent.opacity(0.14).color else Color.Transparent)
            .ppmClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            PpmIcon(ProductIcon.Check, 13.dp, tokens.accent.color)
        }
    }
}

@Composable
private fun MenuCheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    tag: String,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            label,
            style = ppmText(12f, lineHeight = 16f)
                .copy(color = if (enabled) tokens.text.color else tokens.text2.opacity(0.5).color),
        )
        if (checked) {
            PpmIcon(ProductIcon.Check, 13.dp, tokens.accent.color)
        }
    }
}

@Composable
private fun MenuDivider() {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(tokens.border.color),
    )
}

@Composable
private fun CustomScaleZoomRow(
    scale: Double,
    onSetScale: (Double) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val percent = (clampCustomScale(scale) * 100.0).roundToInt()
    val pos = scalePercentToPos(percent)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("remote-desktop-custom-scale-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.panel2.color)
                .ppmClickable { onSetScale(stepCustomScale(scale, -1)) }
                .testTag("remote-desktop-scale-minus"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Minus, 12.dp, tokens.text.color)
        }

        // Piecewise slider track [5%..100%..1000%] with pivot at 1/3
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(20.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width.toFloat()).toDouble().coerceIn(0.0, 1.0)
                        val snapped = snapScalePos(frac)
                        onSetScale(scalePosToPercent(snapped) / 100.0)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.border.color),
            )
            Box(
                Modifier
                    .fillMaxWidth(pos.toFloat().coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.accent.color),
            )
        }

        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.panel2.color)
                .ppmClickable { onSetScale(stepCustomScale(scale, 1)) }
                .testTag("remote-desktop-scale-plus"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Plus, 12.dp, tokens.text.color)
        }

        BasicText(
            "$percent%",
            Modifier.width(42.dp),
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
        )
    }
}

// ── 6. Custom Quality Dialog ──────────────────────────────────────────

@Composable
fun RemoteDesktopCustomQualityDialog(
    percent: Int,
    fps: Int,
    more: Boolean,
    frameSize: RdSize,
    onSetMore: (Boolean) -> Unit,
    onCommit: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var localPercent by remember(percent) { mutableIntStateOf(percent) }
    var localFps by remember(fps) { mutableIntStateOf(fps) }
    val maxPercent = if (more) CUSTOM_QUALITY_MAX_MORE_PERCENT else CUSTOM_QUALITY_MAX_PERCENT

    LaunchedEffect(localPercent, localFps) {
        if (localPercent == percent && localFps == fps) return@LaunchedEffect
        delay(400L)
        onCommit(localPercent, localFps)
    }

    val kbps = customBitrateKbps(localPercent, frameSize.width, frameSize.height)

    Dialog(onDismissRequest = onDismiss) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.over(tokens.bgSolid).color)
                .cssBorder(tokens.border.color, shape)
                .padding(16.dp)
                .testTag("remote-desktop-custom-quality-dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(
                "Custom image quality",
                style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
            )
            BasicText(
                if (frameSize.width > 0) {
                    "$localPercent% of the base rate for ${frameSize.width}×${frameSize.height} — about ${String.format(Locale.US, "%.1f", kbps / 1000.0)} Mbit/s"
                } else {
                    "Waiting for the first frame"
                },
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            )

            // Bitrate row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SimpleRangeBar(
                    value = localPercent.coerceIn(CUSTOM_QUALITY_MIN_PERCENT, maxPercent),
                    min = CUSTOM_QUALITY_MIN_PERCENT,
                    max = maxPercent,
                    onValueChange = { localPercent = clampCustomQualityPercent(it, more) },
                    modifier = Modifier.weight(1f).testTag("remote-desktop-custom-bitrate"),
                )
                BasicText(
                    "$localPercent%",
                    Modifier.width(48.dp),
                    style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                )
                BasicText(
                    "Bitrate",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
                Row(
                    Modifier
                        .ppmClickable { onSetMore(!more) }
                        .testTag("remote-desktop-custom-more"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (more) tokens.accent.color else Color.Transparent)
                            .cssBorder((if (more) tokens.accent else tokens.border).color, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (more) PpmIcon(ProductIcon.Check, 10.dp, tokens.accentFg.color)
                    }
                    BasicText("More", style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color))
                }
            }

            // FPS row
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SimpleRangeBar(
                    value = localFps,
                    min = CUSTOM_FPS_MIN,
                    max = CUSTOM_FPS_MAX,
                    onValueChange = { localFps = clampCustomFps(it) },
                    modifier = Modifier.weight(1f).testTag("remote-desktop-custom-fps"),
                )
                BasicText(
                    "$localFps",
                    Modifier.width(48.dp),
                    style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                )
                BasicText(
                    "FPS",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val btnShape = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .clip(btnShape)
                        .background(tokens.accent.color)
                        .ppmClickable(shape = btnShape, onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    BasicText("Close", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accentFg.color))
                }
            }
        }
    }
}

@Composable
private fun SimpleRangeBar(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val span = (max - min).coerceAtLeast(1)
    val frac = ((value - min).toFloat() / span.toFloat()).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier
            .height(24.dp)
            .pointerInput(min, max) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(min + (f * span).roundToInt())
                }
            }
            .pointerInput(min, max) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onValueChange(min + (f * span).roundToInt())
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tokens.border.color),
        )
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tokens.accent.color),
        )
    }
}

// ── 7. Mobile Toolbar, Sticky Key Bar & Mobile Settings ───────────────

@Composable
fun RemoteDesktopMobileToolbarView(
    displayLabel: String?,
    onNextDisplay: () -> Unit,
    qualityLabel: String,
    onNextQuality: () -> Unit,
    mode: String,
    onToggleMode: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onResetZoom: () -> Unit,
    settingsOpen: Boolean,
    onToggleSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.90f))
            .edge(Edge.Top, Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("remote-desktop-mobile-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileToolbarButton(
            label = if (mode == "mouse") "Mouse" else "Touch",
            icon = ProductIcon.Terminal,
            active = true,
            onClick = onToggleMode,
            tag = "remote-desktop-mobile-mode",
            modifier = Modifier.weight(1f),
        )
        MobileToolbarButton(
            label = "Keyboard",
            icon = ProductIcon.Keyboard,
            onClick = onOpenKeyboard,
            tag = "remote-desktop-mobile-keyboard",
            modifier = Modifier.weight(1f),
        )
        MobileToolbarButton(
            label = "Reset zoom",
            icon = ProductIcon.Minimize2,
            onClick = onResetZoom,
            tag = "remote-desktop-mobile-reset-zoom",
            modifier = Modifier.weight(1f),
        )
        if (displayLabel != null) {
            MobileToolbarButton(
                label = if (displayLabel.length > 10) "${displayLabel.take(9)}…" else displayLabel,
                icon = ProductIcon.Monitor,
                onClick = onNextDisplay,
                tag = "remote-desktop-mobile-display",
                modifier = Modifier.weight(1f),
            )
        }
        MobileToolbarButton(
            label = qualityLabel,
            icon = ProductIcon.Activity,
            onClick = onNextQuality,
            tag = "remote-desktop-mobile-quality",
            modifier = Modifier.weight(1f),
        )
        MobileToolbarButton(
            label = "More",
            icon = ProductIcon.SlidersHorizontal,
            active = settingsOpen,
            onClick = onToggleSettings,
            tag = "remote-desktop-mobile-more",
            modifier = Modifier.weight(1f),
        )
        MobileToolbarButton(
            label = "Close",
            icon = ProductIcon.X,
            onClick = onClose,
            tag = "remote-desktop-mobile-close",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MobileToolbarButton(
    label: String,
    icon: ProductIcon,
    active: Boolean = false,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (active) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .ppmClickable(shape = shape, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PpmIcon(icon, 18.dp, Color.White.copy(alpha = if (active) 1f else 0.8f))
        Spacer(Modifier.height(2.dp))
        BasicText(
            label,
            style = ppmText(10f, lineHeight = 13f).copy(color = Color.White.copy(alpha = if (active) 1f else 0.8f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RemoteDesktopMobileKeyBarView(
    onSendMessage: (JsonElement) -> Unit,
    onCopyCombo: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    var held by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(Unit) {
        onDispose {
            for (code in held) {
                onSendMessage(
                    buildJsonObject {
                        put("type", "key")
                        put("code", code)
                        put("down", false)
                    },
                )
            }
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.90f))
            .edge(Edge.Top, Color.White.copy(alpha = 0.10f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("remote-desktop-mobile-key-bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (mod in RD_MODIFIERS) {
            val isHeld = mod.code in held
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .widthIn(min = 44.dp)
                    .clip(shape)
                    .background(if (isHeld) tokens.accent.color else Color.Transparent)
                    .ppmClickable(shape = shape) {
                        val nextDown = !isHeld
                        onSendMessage(
                            buildJsonObject {
                                put("type", "key")
                                put("code", mod.code)
                                put("down", nextDown)
                            },
                        )
                        held = if (isHeld) held - mod.code else held + mod.code
                    }
                    .padding(horizontal = 10.dp)
                    .testTag("rd-key-${mod.label}"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    mod.label,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
                        .copy(color = if (isHeld) tokens.accentFg.color else Color.White.copy(alpha = 0.85f)),
                )
            }
        }

        Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.12f)))

        for (fn in RD_FUNCTION_KEYS) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .widthIn(min = 44.dp)
                    .clip(shape)
                    .ppmClickable(shape = shape) {
                        onSendMessage(buildJsonObject { put("type", "key"); put("code", fn.code); put("down", true) })
                        onSendMessage(buildJsonObject { put("type", "key"); put("code", fn.code); put("down", false) })
                    }
                    .padding(horizontal = 10.dp)
                    .testTag("rd-key-${fn.label}"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    fn.label,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White.copy(alpha = 0.85f)),
                )
            }
        }

        Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.12f)))

        for (combo in RD_COMBOS) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .ppmClickable(shape = shape) {
                        for (c in combo.codes) {
                            onSendMessage(buildJsonObject { put("type", "key"); put("code", c); put("down", true) })
                        }
                        for (c in combo.codes.asReversed()) {
                            onSendMessage(buildJsonObject { put("type", "key"); put("code", c); put("down", false) })
                        }
                        if (isCopyChord(combo.codes)) onCopyCombo()
                    }
                    .padding(horizontal = 10.dp)
                    .testTag("rd-combo-${combo.label}"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    combo.label,
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White.copy(alpha = 0.85f)),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteDesktopMobileSettingsPanel(
    caps: RemoteDesktopCapabilities,
    statsVisible: Boolean,
    onToggleStats: () -> Unit,
    showCursor: Boolean,
    onSetShowCursor: (Boolean) -> Unit,
    clipboardEnabled: Boolean,
    onSetClipboardEnabled: (Boolean) -> Unit,
    audioOn: Boolean,
    onSetAudioOn: (Boolean) -> Unit,
    privacyOn: Boolean,
    privacyError: String?,
    onSetPrivacyOn: (Boolean) -> Unit,
    hostModeId: String?,
    hostOriginalModeId: String?,
    resolutionError: String?,
    onSetHostMode: (String) -> Unit,
    recording: Boolean,
    onToggleRecording: () -> Unit,
    onScreenshot: () -> Unit,
    localScreenOverride: RdSize? = null,
) {
    val tokens = LocalPpmTokens.current
    val configuration = LocalConfiguration.current
    val localScreen = localScreenOverride ?: RdSize(
        width = configuration.screenWidthDp.coerceAtLeast(1),
        height = configuration.screenHeightDp.coerceAtLeast(1),
    )
    val resolutions = remember(caps.resolutions.modes) { resolutionChoices(caps.resolutions.modes) }
    val fitLocal = remember(caps.resolutions.modes, localScreen, hostModeId) {
        fitLocalMode(caps.resolutions.modes, localScreen, hostModeId)
    }
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.92f))
            .cssBorder(Color.White.copy(alpha = 0.12f), shape)
            .padding(6.dp)
            .testTag("remote-desktop-mobile-settings"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MobileSettingSwitchRow(
            label = "Quality monitor",
            checked = statsVisible,
            onToggle = onToggleStats,
            tag = "remote-desktop-mobile-stats-toggle",
        )
        MobileSettingSwitchRow(
            label = "Show remote cursor",
            hint = "Restarts the stream",
            checked = showCursor,
            onToggle = { onSetShowCursor(!showCursor) },
            tag = "remote-desktop-mobile-cursor-toggle",
        )
        MobileSettingSwitchRow(
            label = "Sync clipboard",
            hint = "Off: Ctrl+V pastes the host's own clipboard",
            checked = clipboardEnabled,
            onToggle = { onSetClipboardEnabled(!clipboardEnabled) },
            tag = "remote-desktop-mobile-clipboard-toggle",
        )
        MobileSettingSwitchRow(
            label = "Play host audio",
            hint = caps.audio.reason,
            checked = audioOn,
            onToggle = { onSetAudioOn(!audioOn) },
            tag = "remote-desktop-mobile-audio-toggle",
        )
        MobileSettingSwitchRow(
            label = "Privacy mode",
            hint = privacyError ?: caps.privacy.reason ?: "Blocks the host's own keyboard and mouse",
            checked = privacyOn,
            onToggle = { onSetPrivacyOn(!privacyOn) },
            tag = "remote-desktop-mobile-privacy-toggle",
        )

        if (caps.inputReady && resolutions.size > 1) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                BasicText(
                    "RESOLUTION",
                    style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = Color.White.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (hostOriginalModeId != null && hostOriginalModeId != hostModeId) {
                        val chipShape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier
                                .heightIn(min = 36.dp)
                                .clip(chipShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .ppmClickable(shape = chipShape) { onSetHostMode(hostOriginalModeId) }
                                .padding(horizontal = 10.dp)
                                .testTag("remote-desktop-mobile-resolution-original"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText("Original", style = ppmText(11f, lineHeight = 15f).copy(color = Color.White))
                        }
                    }
                    if (fitLocal != null) {
                        val chipShape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier
                                .heightIn(min = 36.dp)
                                .clip(chipShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .ppmClickable(shape = chipShape) { onSetHostMode(fitLocal.id) }
                                .padding(horizontal = 10.dp)
                                .testTag("remote-desktop-mobile-resolution-fit-local"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText("Fit local", style = ppmText(11f, lineHeight = 15f).copy(color = Color.White))
                        }
                    }
                    val activeMode = hostModeId ?: resolutions.firstOrNull { it.current }?.id
                    for (r in resolutions) {
                        val active = r.id == activeMode
                        val chipShape = RoundedCornerShape(6.dp)
                        Box(
                            Modifier
                                .heightIn(min = 36.dp)
                                .clip(chipShape)
                                .background(Color.White.copy(alpha = if (active) 0.25f else 0.10f))
                                .ppmClickable(shape = chipShape) { onSetHostMode(r.id) }
                                .padding(horizontal = 10.dp)
                                .testTag("remote-desktop-mobile-resolution-${r.width}x${r.height}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "${r.width}×${r.height}",
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = Color.White),
                            )
                        }
                    }
                }
                if (resolutionError != null) {
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        resolutionError,
                        Modifier.testTag("remote-desktop-mobile-resolution-error"),
                        style = ppmText(10f, lineHeight = 14f).copy(color = Color(0xFFF87171)),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val btnShape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(btnShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .ppmClickable(shape = btnShape, onClick = onScreenshot)
                    .testTag("remote-desktop-mobile-screenshot"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Save screenshot", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White))
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(btnShape)
                    .background(if (recording) tokens.error.opacity(0.25).color else Color.White.copy(alpha = 0.10f))
                    .ppmClickable(shape = btnShape, onClick = onToggleRecording)
                    .testTag("remote-desktop-mobile-record"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (recording) "Stop recording (no audio)" else "Start recording (no audio)",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White),
                )
            }
        }
    }
}

@Composable
private fun MobileSettingSwitchRow(
    label: String,
    hint: String? = null,
    checked: Boolean,
    onToggle: () -> Unit,
    tag: String,
) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .ppmClickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                label,
                style = ppmText(12f, lineHeight = 16f).copy(color = Color.White.copy(alpha = 0.85f)),
            )
            if (!hint.isNullOrBlank()) {
                BasicText(
                    hint,
                    style = ppmText(10f, lineHeight = 14f).copy(color = Color.White.copy(alpha = 0.45f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .width(36.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (checked) tokens.accent.color else Color.White.copy(alpha = 0.20f))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
        }
    }
}

// ── 8. File Export Helpers ────────────────────────────────────────────

@Suppress("DEPRECATION")
private fun resolvePreQDownloadsDir(context: Context, downloadsDirOverride: File? = null): File {
    val candidates = buildList {
        if (downloadsDirOverride != null) add(downloadsDirOverride)
        runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
            .getOrNull()?.let(::add)
        runCatching { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) }
            .getOrNull()?.let(::add)
        add(context.cacheDir)
    }
    for (dir in candidates) {
        if ((dir.exists() || dir.mkdirs()) && dir.canWrite()) {
            return dir
        }
    }
    return context.cacheDir
}

internal fun saveBitmapToDownloads(
    context: Context,
    bitmap: Bitmap,
    sdkInt: Int = Build.VERSION.SDK_INT,
    downloadsDirOverride: File? = null,
): File? {
    return runCatching {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US))
        val name = "ppm-remote-$stamp.png"
        if (sdkInt >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                return null
            }
        }
        val dir = resolvePreQDownloadsDir(context, downloadsDirOverride)
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        file
    }.getOrNull()
}

internal fun copyMp4ToDownloads(
    context: Context,
    file: File,
    sdkInt: Int = Build.VERSION.SDK_INT,
    downloadsDirOverride: File? = null,
): File? {
    return runCatching {
        if (sdkInt >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp -> inp.copyTo(out) }
                }
                file.delete()
                return null
            }
        }
        val dir = resolvePreQDownloadsDir(context, downloadsDirOverride)
        val dest = File(dir, file.name)
        if (dest.canonicalPath != file.canonicalPath) {
            file.inputStream().use { inp ->
                FileOutputStream(dest).use { out ->
                    inp.copyTo(out)
                }
            }
            file.delete()
        }
        dest
    }.getOrNull()
}
