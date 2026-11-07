package com.hienlh.ppm.app.ui.androiddevice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.remotedesktop.H264SurfaceDecoder
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.androidCancelOperation
import com.hienlh.ppm.client.androidCapabilities
import com.hienlh.ppm.client.androidCreateAvd
import com.hienlh.ppm.client.androidCreateSession
import com.hienlh.ppm.client.androidDeleteAvd
import com.hienlh.ppm.client.androidDeviceBootLog
import com.hienlh.ppm.client.androidDeviceProfiles
import com.hienlh.ppm.client.androidDevices
import com.hienlh.ppm.client.androidGetClipboard
import com.hienlh.ppm.client.androidInstallProjectApk
import com.hienlh.ppm.client.androidOperationStatus
import com.hienlh.ppm.client.androidProjectApks
import com.hienlh.ppm.client.androidSetClipboard
import com.hienlh.ppm.client.androidStartAvd
import com.hienlh.ppm.client.androidStopDevice
import com.hienlh.ppm.client.androidSystemImages
import com.hienlh.ppm.client.androidTakeScreenshot
import com.hienlh.ppm.client.androidUploadApk
import com.hienlh.ppm.client.androidWipeAvd
import com.hienlh.ppm.client.openAndroidSocket
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.androiddevice.ANDROID_HEARTBEAT_INTERVAL_MS
import com.hienlh.ppm.state.androiddevice.ANDROID_RECONNECT_BACKOFF_MS
import com.hienlh.ppm.state.androiddevice.AndroidCapabilities
import com.hienlh.ppm.state.androiddevice.AndroidConnState
import com.hienlh.ppm.state.androiddevice.AndroidDeviceItem
import com.hienlh.ppm.state.androiddevice.AndroidDeviceStatus
import com.hienlh.ppm.state.androiddevice.AndroidGeometry
import com.hienlh.ppm.state.androiddevice.AndroidIntPoint
import com.hienlh.ppm.state.androiddevice.AndroidKey
import com.hienlh.ppm.state.androiddevice.AndroidLogEntry
import com.hienlh.ppm.state.androiddevice.AndroidLogFilter
import com.hienlh.ppm.state.androiddevice.AndroidLogLevel
import com.hienlh.ppm.state.androiddevice.AndroidOperationStatus
import com.hienlh.ppm.state.androiddevice.AndroidQuality
import com.hienlh.ppm.state.androiddevice.AndroidServerMessage
import com.hienlh.ppm.state.androiddevice.AndroidTextInputKind
import com.hienlh.ppm.state.androiddevice.AndroidTouchPhase
import com.hienlh.ppm.state.androiddevice.AndroidTouchTracker
import com.hienlh.ppm.state.androiddevice.AvdDeviceProfile
import com.hienlh.ppm.state.androiddevice.AvdLimits
import com.hienlh.ppm.state.androiddevice.EMPTY_ANDROID_LOG_FILTER
import com.hienlh.ppm.state.androiddevice.ProjectApkItem
import com.hienlh.ppm.state.androiddevice.SystemImageRow
import com.hienlh.ppm.state.androiddevice.appendBoundedLogs
import com.hienlh.ppm.state.androiddevice.buildAndroidAuthJson
import com.hienlh.ppm.state.androiddevice.buildAndroidHeartbeatJson
import com.hienlh.ppm.state.androiddevice.buildAndroidInputResetJson
import com.hienlh.ppm.state.androiddevice.buildAndroidKeyJson
import com.hienlh.ppm.state.androiddevice.buildAndroidPasteJson
import com.hienlh.ppm.state.androiddevice.buildAndroidRequestKeyframeJson
import com.hienlh.ppm.state.androiddevice.buildAndroidRotateJson
import com.hienlh.ppm.state.androiddevice.buildAndroidSetQualityJson
import com.hienlh.ppm.state.androiddevice.buildAndroidSubscribeLogsJson
import com.hienlh.ppm.state.androiddevice.buildAndroidTakeControlJson
import com.hienlh.ppm.state.androiddevice.buildAndroidTextJson
import com.hienlh.ppm.state.androiddevice.buildAndroidTouchJson
import com.hienlh.ppm.state.androiddevice.classifyAndroidTextInput
import com.hienlh.ppm.state.androiddevice.containedRect
import com.hienlh.ppm.state.androiddevice.decodeAndroidVideoFrame
import com.hienlh.ppm.state.androiddevice.filenameFromDisposition
import com.hienlh.ppm.state.androiddevice.formatAndroidLogText
import com.hienlh.ppm.state.androiddevice.formatAndroidLogTime
import com.hienlh.ppm.state.androiddevice.matchesAndroidLogFilter
import com.hienlh.ppm.state.androiddevice.nextAndroidRotation
import com.hienlh.ppm.state.androiddevice.parseAndroidCapabilities
import com.hienlh.ppm.state.androiddevice.parseAndroidDeviceProfiles
import com.hienlh.ppm.state.androiddevice.parseAndroidDevices
import com.hienlh.ppm.state.androiddevice.parseAndroidOperationStatus
import com.hienlh.ppm.state.androiddevice.parseAndroidServerMessage
import com.hienlh.ppm.state.androiddevice.parseAndroidSessionTicket
import com.hienlh.ppm.state.androiddevice.parseAndroidSystemImages
import com.hienlh.ppm.state.androiddevice.parseProjectApks
import com.hienlh.ppm.state.androiddevice.pointerToDevice
import com.hienlh.ppm.state.androiddevice.validateAvdLimits
import com.hienlh.ppm.state.androiddevice.validateAvdName
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File

/**
 * Android Device / Emulator Controller workspace tab (`Phase 11c`).
 *
 * Mirrors `src/web/components/android/android-tab.tsx`:
 * - Resolves any pinned `avdId` from tab metadata against `/api/android/devices`.
 * - Shows [AndroidDeviceList] when no running device is selected.
 * - Shows [AndroidViewer] when an active device is selected, pinning `avdId` and device name on the tab.
 */
@Composable
fun AndroidTabBody(model: AppModel, tab: WorkspaceTab) {
    val api = model.api
    val pinnedAvdId = tab.metaString("avdId")
    var selectedDevice by remember(tab.id) { mutableStateOf<AndroidDeviceItem?>(null) }
    var resolvedInitial by remember(tab.id, pinnedAvdId) { mutableStateOf(pinnedAvdId.isNullOrEmpty()) }

    LaunchedEffect(api, pinnedAvdId) {
        if (api == null || pinnedAvdId.isNullOrEmpty()) {
            resolvedInitial = true
            return@LaunchedEffect
        }
        try {
            val list = parseAndroidDevices(api.androidDevices())
            val found = list.firstOrNull { it.avdId == pinnedAvdId && it.runtime != null }
            if (found != null) {
                selectedDevice = found
            }
        } catch (_: Throwable) {
        } finally {
            resolvedInitial = true
        }
    }

    val tokens = LocalPpmTokens.current
    if (!resolvedInitial) {
        Box(Modifier.fillMaxSize().background(tokens.bgSolid.color), contentAlignment = Alignment.Center) {
            BasicText("Loading Android devices…", style = ppmText(12f).copy(color = tokens.text3.color))
        }
        return
    }

    val current = selectedDevice
    if (current == null) {
        AndroidDeviceList(
            model = model,
            onSelectDevice = { device ->
                selectedDevice = device
                model.updateAndroidTabDevice(tab.id, device.avdId, device.name)
            },
        )
    } else {
        AndroidViewer(
            model = model,
            device = current,
            onBack = {
                selectedDevice = null
                model.updateAndroidTabDevice(tab.id, null, "Android")
            },
        )
    }
}

// ===========================================================================
// 1. Device List, Capabilities, Wipe/Delete Dialog, Create AVD Dialog
// ===========================================================================

private enum class DestructiveAvdAction { WIPE, DELETE }

@Composable
internal fun AndroidDeviceList(
    model: AppModel,
    onSelectDevice: (AndroidDeviceItem) -> Unit,
) {
    val api = model.api
    var capabilities by remember { mutableStateOf<AndroidCapabilities?>(null) }
    var devices by remember { mutableStateOf<List<AndroidDeviceItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val operations = remember { mutableStateMapOf<String, AndroidOperationStatus>() }
    var createOpen by remember { mutableStateOf(false) }
    var confirmTarget by remember { mutableStateOf<Pair<AndroidDeviceItem, DestructiveAvdAction>?>(null) }
    var bootLogTarget by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun loadAll(quiet: Boolean = false) {
        if (api == null) {
            loading = false
            error = "Not connected to a PPM server."
            return
        }
        if (!quiet) loading = true
        try {
            val capsJson = api.androidCapabilities()
            val devsJson = api.androidDevices()
            capabilities = parseAndroidCapabilities(capsJson)
            devices = parseAndroidDevices(devsJson)
            error = null
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            error = t.message ?: "Failed to load Android devices"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(api) {
        loadAll(quiet = false)
        while (isActive) {
            delay(5_000L)
            loadAll(quiet = true)
        }
    }

    fun pollOperation(avdId: String, opId: String, autoOpenOnSuccess: Boolean) {
        if (api == null) return
        scope.launch {
            while (isActive) {
                try {
                    val op = parseAndroidOperationStatus(api.androidOperationStatus(opId))
                    if (op != null) {
                        operations[avdId] = op
                        if (op.isTerminal) {
                            val list = parseAndroidDevices(api.androidDevices())
                            devices = list
                            if (op.status == "succeeded" && autoOpenOnSuccess) {
                                val started = list.firstOrNull { it.avdId == avdId && it.runtime != null }
                                if (started != null) onSelectDevice(started)
                            }
                            break
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    break
                }
                delay(1_000L)
            }
        }
    }

    AndroidDeviceListView(
        capabilities = capabilities,
        devices = devices,
        operations = operations,
        loading = loading,
        error = error,
        onRefresh = { scope.launch { loadAll(quiet = false) } },
        onOpenCreateAvd = { createOpen = true },
        onCopyFix = { cmd ->
            copyToHostClipboard(context, cmd)
            model.toast(ErrorToastMessage("Copied fix command", kind = ErrorToastMessage.Kind.SUCCESS))
        },
        onStartAvd = { device ->
            if (api != null) {
                scope.launch {
                    try {
                        val res = api.androidStartAvd(device.avdId)
                        val op = parseAndroidOperationStatus(res)
                        if (op != null) {
                            operations[device.avdId] = op
                            pollOperation(device.avdId, op.id, autoOpenOnSuccess = true)
                        } else {
                            loadAll(quiet = true)
                        }
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage("Failed to start ${device.name}", t.message))
                    }
                }
            }
        },
        onStopDevice = { device ->
            if (api != null) {
                scope.launch {
                    try {
                        api.androidStopDevice(device.avdId, device.runtime?.generation)
                        loadAll(quiet = true)
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage("Failed to stop ${device.name}", t.message))
                    }
                }
            }
        },
        onCancelOperation = { opId ->
            if (api != null) {
                scope.launch {
                    runCatching { api.androidCancelOperation(opId) }
                    loadAll(quiet = true)
                }
            }
        },
        onSelectDevice = onSelectDevice,
        onRequestWipe = { confirmTarget = it to DestructiveAvdAction.WIPE },
        onRequestDelete = { confirmTarget = it to DestructiveAvdAction.DELETE },
        onShowBootLog = { device ->
            if (api != null) {
                scope.launch {
                    try {
                        val res = api.androidDeviceBootLog(device.avdId) as? JsonObject
                        val lines = (res?.get("lines") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                        bootLogTarget = device.name to lines
                    } catch (t: Throwable) {
                        model.toast(ErrorToastMessage("Could not read boot log", t.message))
                    }
                }
            }
        },
    )

    if (createOpen && api != null) {
        AndroidAvdCreateDialog(
            api = api,
            onDismiss = { createOpen = false },
            onCreated = {
                createOpen = false
                scope.launch { loadAll(quiet = false) }
            },
        )
    }

    confirmTarget?.let { (targetDevice, action) ->
        AndroidDestructiveConfirmDialog(
            device = targetDevice,
            isDelete = action == DestructiveAvdAction.DELETE,
            onDismiss = { confirmTarget = null },
            onConfirm = {
                val chosen = targetDevice
                val act = action
                confirmTarget = null
                if (api != null) {
                    scope.launch {
                        try {
                            if (act == DestructiveAvdAction.WIPE) {
                                api.androidWipeAvd(chosen.avdId)
                                model.toast(ErrorToastMessage("Wiped ${chosen.name}", kind = ErrorToastMessage.Kind.SUCCESS))
                                loadAll(quiet = true)
                            } else {
                                val res = api.androidDeleteAvd(chosen.avdId)
                                val op = parseAndroidOperationStatus(res)
                                if (op != null && !op.isTerminal) {
                                    operations[chosen.avdId] = op
                                    pollOperation(chosen.avdId, op.id, autoOpenOnSuccess = false)
                                } else {
                                    loadAll(quiet = true)
                                }
                            }
                        } catch (t: Throwable) {
                            model.toast(ErrorToastMessage("Action failed", t.message))
                        }
                    }
                }
            },
        )
    }

    bootLogTarget?.let { (name, lines) ->
        Dialog(onDismissRequest = { bootLogTarget = null }) {
            val tokens = LocalPpmTokens.current
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText("Boot log · $name", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                    SmallActionPill("Close", onClick = { bootLogTarget = null })
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    BasicText(
                        if (lines.isEmpty()) "No emulator log output yet." else lines.joinToString("\n"),
                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AndroidDeviceListView(
    capabilities: AndroidCapabilities?,
    devices: List<AndroidDeviceItem>,
    operations: Map<String, AndroidOperationStatus>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenCreateAvd: () -> Unit,
    onCopyFix: (String) -> Unit,
    onStartAvd: (AndroidDeviceItem) -> Unit,
    onStopDevice: (AndroidDeviceItem) -> Unit,
    onCancelOperation: (String) -> Unit,
    onSelectDevice: (AndroidDeviceItem) -> Unit,
    onRequestWipe: (AndroidDeviceItem) -> Unit,
    onRequestDelete: (AndroidDeviceItem) -> Unit,
    onShowBootLog: (AndroidDeviceItem) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val unmet = remember(capabilities) { capabilities?.requirements?.filter { !it.met }.orEmpty() }
    val canCreate = capabilities?.canCreateAvd == true && !capabilities.authRequired

    Column(
        Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("android-device-list"),
    ) {
        // Header bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpmIcon(ProductIcon.Smartphone, 16.dp, tokens.text2.color)
                BasicText(
                    "Android Devices",
                    style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canCreate) {
                    SmallActionPill(
                        label = "+ Create AVD",
                        primary = true,
                        modifier = Modifier.testTag("android-create-avd-btn"),
                        onClick = onOpenCreateAvd,
                    )
                }
                SmallActionPill(
                    label = "Refresh",
                    modifier = Modifier.testTag("android-refresh-btn"),
                    onClick = onRefresh,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))

        LazyColumn(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (error != null) {
                item {
                    val shape = RoundedCornerShape(8.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.error.opacity(0.12).color)
                            .cssBorder(tokens.error.opacity(0.35).color, shape)
                            .padding(12.dp),
                    ) {
                        BasicText(error, style = ppmText(12f).copy(color = tokens.error.color))
                    }
                }
            }

            if (unmet.isNotEmpty()) {
                item {
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, shape)
                            .padding(12.dp)
                            .testTag("android-requirements-card"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PpmIcon(ProductIcon.TriangleAlert, 14.dp, tokens.warning.color)
                            BasicText(
                                "Host setup required",
                                style = ppmText(12f, FontWeight.SemiBold).copy(color = tokens.text.color),
                            )
                        }
                        for (req in unmet) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                BasicText(
                                    req.label + (if (!req.detail.isNullOrBlank()) " — ${req.detail}" else ""),
                                    style = ppmText(11f).copy(color = tokens.text2.color),
                                )
                                if (!req.fix.isNullOrBlank()) {
                                    val fixShape = RoundedCornerShape(6.dp)
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(fixShape)
                                            .background(tokens.bgSolid.color)
                                            .ppmClickable(
                                                pressedColor = tokens.panel2.color,
                                                hoverColor = tokens.panel2.color,
                                                shape = fixShape,
                                                onClick = { onCopyFix(req.fix!!) },
                                            )
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicText(
                                            req.fix!!,
                                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                                            modifier = Modifier.weight(1f),
                                        )
                                        BasicText(
                                            "Copy",
                                            style = ppmText(10f, FontWeight.Medium).copy(color = tokens.accent.color),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (capabilities != null && !capabilities.accelerationOk && capabilities.accelerationDetail.isNotBlank()) {
                item {
                    val shape = RoundedCornerShape(8.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.warning.opacity(0.1).color)
                            .cssBorder(tokens.warning.opacity(0.3).color, shape)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.TriangleAlert, 14.dp, tokens.warning.color)
                        BasicText(
                            capabilities.accelerationDetail,
                            style = ppmText(11f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }

            if (loading && devices.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        BasicText("Loading Android Virtual Devices…", style = ppmText(12f).copy(color = tokens.text3.color))
                    }
                }
            } else if (devices.isEmpty()) {
                item {
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, shape)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PpmIcon(ProductIcon.Smartphone, 24.dp, tokens.text3.color)
                        BasicText(
                            "No Android Virtual Devices configured",
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
                        )
                        BasicText(
                            "Create an AVD on the host to start an emulator.",
                            style = ppmText(11f).copy(color = tokens.text3.color, textAlign = TextAlign.Center),
                        )
                        if (canCreate) {
                            SmallActionPill("+ Create AVD", primary = true, onClick = onOpenCreateAvd)
                        }
                    }
                }
            } else {
                items(devices, key = { it.avdId }) { device ->
                    AndroidDeviceRow(
                        device = device,
                        operation = operations[device.avdId],
                        readyToStart = capabilities?.ready != false && !capabilities?.authRequired.orFalse(),
                        canMutateAvd = canCreate,
                        onStart = { onStartAvd(device) },
                        onStop = { onStopDevice(device) },
                        onCancelOp = { opId -> onCancelOperation(opId) },
                        onView = { onSelectDevice(device) },
                        onWipe = { onRequestWipe(device) },
                        onDelete = { onRequestDelete(device) },
                        onShowBootLog = { onShowBootLog(device) },
                    )
                }
            }
        }
    }
}

private fun Boolean?.orFalse(): Boolean = this ?: false

@Composable
private fun AndroidDeviceRow(
    device: AndroidDeviceItem,
    operation: AndroidOperationStatus?,
    readyToStart: Boolean,
    canMutateAvd: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancelOp: (String) -> Unit,
    onView: () -> Unit,
    onWipe: () -> Unit,
    onDelete: () -> Unit,
    onShowBootLog: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val status = device.runtime?.status ?: AndroidDeviceStatus.STOPPED
    val opRunning = operation != null && !operation.isTerminal
    var menuOpen by remember { mutableStateOf(false) }

    val dotColor = when {
        status == AndroidDeviceStatus.READY -> tokens.success.color
        status == AndroidDeviceStatus.STARTING ||
            status == AndroidDeviceStatus.BOOTING ||
            status == AndroidDeviceStatus.STOPPING ||
            opRunning -> tokens.warning.color
        status == AndroidDeviceStatus.ERROR -> tokens.error.color
        else -> tokens.text3.opacity(0.45).color
    }

    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(12.dp)
            .testTag("android-device-row-${device.avdId}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText(
                            device.name,
                            style = ppmText(13f, FontWeight.Medium).copy(color = tokens.text.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(status)
                    }
                    val metaParts = buildList {
                        device.target?.takeIf { it.isNotBlank() }?.let(::add)
                        device.abi?.takeIf { it.isNotBlank() }?.let(::add)
                        device.runtime?.serial?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                    if (metaParts.isNotEmpty()) {
                        BasicText(
                            metaParts.joinToString(" · "),
                            style = ppmText(11f).copy(color = tokens.text3.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    opRunning && operation != null -> {
                        SmallActionPill("Cancel", onClick = { onCancelOp(operation.id) })
                    }
                    status == AndroidDeviceStatus.READY -> {
                        SmallActionPill(
                            label = "View",
                            primary = true,
                            modifier = Modifier.testTag("android-view-${device.avdId}"),
                            onClick = onView,
                        )
                        SmallActionPill(
                            label = "Stop",
                            modifier = Modifier.testTag("android-stop-${device.avdId}"),
                            onClick = onStop,
                        )
                    }
                    status == AndroidDeviceStatus.STARTING || status == AndroidDeviceStatus.BOOTING -> {
                        SmallActionPill("View", onClick = onView)
                        SmallActionPill("Stop", onClick = onStop)
                    }
                    status == AndroidDeviceStatus.STOPPED || status == AndroidDeviceStatus.ERROR -> {
                        SmallActionPill(
                            label = "Start",
                            primary = readyToStart,
                            enabled = readyToStart,
                            modifier = Modifier.testTag("android-start-${device.avdId}"),
                            onClick = onStart,
                        )
                        if (canMutateAvd) {
                            SmallActionPill(
                                label = "•••",
                                modifier = Modifier.testTag("android-more-${device.avdId}"),
                                onClick = { menuOpen = !menuOpen },
                            )
                        }
                    }
                }
            }
        }

        if (menuOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.bgSolid.color)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("AVD actions:", style = ppmText(11f).copy(color = tokens.text3.color))
                SmallActionPill(
                    label = "Wipe data…",
                    modifier = Modifier.testTag("android-wipe-${device.avdId}"),
                    onClick = {
                        menuOpen = false
                        onWipe()
                    },
                )
                SmallActionPill(
                    label = "Delete AVD…",
                    danger = true,
                    modifier = Modifier.testTag("android-delete-${device.avdId}"),
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }

        if (opRunning && operation != null && operation.step.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(operation.step, style = ppmText(11f).copy(color = tokens.text2.color))
                if (operation.progress != null) {
                    BasicText("${operation.progress}%", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                }
            }
        }

        val errText = device.runtime?.lastError ?: operation?.error
        if (!errText.isNullOrBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    errText,
                    style = ppmText(11f).copy(color = tokens.error.color),
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                SmallActionPill("Boot log", onClick = onShowBootLog)
            }
        }
    }
}

@Composable
private fun StatusPill(status: AndroidDeviceStatus) {
    val tokens = LocalPpmTokens.current
    val (bg, fg, label) = when (status) {
        AndroidDeviceStatus.READY -> Triple(tokens.success.opacity(0.15).color, tokens.success.color, "Ready")
        AndroidDeviceStatus.STARTING -> Triple(tokens.warning.opacity(0.15).color, tokens.warning.color, "Starting")
        AndroidDeviceStatus.BOOTING -> Triple(tokens.warning.opacity(0.15).color, tokens.warning.color, "Booting")
        AndroidDeviceStatus.STOPPING -> Triple(tokens.warning.opacity(0.15).color, tokens.warning.color, "Stopping")
        AndroidDeviceStatus.ERROR -> Triple(tokens.error.opacity(0.15).color, tokens.error.color, "Error")
        AndroidDeviceStatus.STOPPED -> Triple(tokens.panel2.color, tokens.text3.color, "Stopped")
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = ppmText(10f, FontWeight.Medium).copy(color = fg))
    }
}

@Composable
internal fun AndroidDestructiveConfirmDialog(
    device: AndroidDeviceItem,
    isDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        AndroidDestructiveConfirmContent(
            device = device,
            isDelete = isDelete,
            onDismiss = onDismiss,
            onConfirm = onConfirm,
        )
    }
}

@Composable
internal fun AndroidDestructiveConfirmContent(
    device: AndroidDeviceItem,
    isDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var typed by remember(device.avdId, isDelete) { mutableStateOf("") }
    val matches = typed.trim() == device.avdId
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .padding(16.dp)
            .testTag("android-confirm-dialog"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            if (isDelete) "Delete AVD \"${device.name}\"" else "Wipe data for \"${device.name}\"",
            style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color),
        )
        BasicText(
            if (isDelete) {
                "This permanently deletes the AVD and all disk images. Type \"${device.avdId}\" to confirm:"
            } else {
                "This resets the emulator to factory state, erasing installed apps and data. Type \"${device.avdId}\" to confirm:"
            },
            style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
        )
        AndroidSingleLineInput(
            value = typed,
            onValueChange = { typed = it },
            placeholder = device.avdId,
            modifier = Modifier.fillMaxWidth().testTag("android-confirm-input"),
            mono = true,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallActionPill("Cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            SmallActionPill(
                label = if (isDelete) "Delete permanently" else "Wipe data",
                danger = true,
                enabled = matches,
                modifier = Modifier.testTag("android-confirm-submit"),
                onClick = { if (matches) onConfirm() },
            )
        }
    }
}

@Composable
internal fun AndroidAvdCreateDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var images by remember { mutableStateOf<List<SystemImageRow>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<AvdDeviceProfile>>(emptyList()) }
    var loadingCatalogs by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var nameTouched by remember { mutableStateOf(false) }
    var systemImageId by remember { mutableStateOf("") }
    var deviceProfileId by remember { mutableStateOf("") }
    var imageSearch by remember { mutableStateOf("") }
    var ramMb by remember { mutableStateOf(AvdLimits.RAM_DEFAULT_MB.toString()) }
    var storageMb by remember { mutableStateOf(AvdLimits.STORAGE_DEFAULT_MB.toString()) }
    var showAdvanced by remember { mutableStateOf(false) }

    var submitting by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<AndroidOperationStatus?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        loadingCatalogs = true
        try {
            val loadedImages = parseAndroidSystemImages(api.androidSystemImages())
                .sortedWith(compareByDescending<SystemImageRow> { it.installed }.thenByDescending { it.apiLevel })
            val loadedProfiles = parseAndroidDeviceProfiles(api.androidDeviceProfiles())
            images = loadedImages
            profiles = loadedProfiles
            val defImg = loadedImages.firstOrNull { it.installed } ?: loadedImages.firstOrNull()
            val defProf = loadedProfiles.firstOrNull { it.id.contains("pixel_7", ignoreCase = true) } ?: loadedProfiles.firstOrNull()
            if (defImg != null) systemImageId = defImg.id
            if (defProf != null) deviceProfileId = defProf.id
            if (defImg != null && defProf != null) {
                val base = "${defProf.id}_API_${defImg.apiLevel}".replace(Regex("[^A-Za-z0-9._-]"), "_")
                name = base.take(64)
            }
        } catch (t: Throwable) {
            error = t.message ?: "Failed to load SDK catalogs"
        } finally {
            loadingCatalogs = false
        }
    }

    val nameError = validateAvdName(name)
    val limitsError = validateAvdLimits(ramMb.toIntOrNull(), storageMb.toIntOrNull())
    val opRunning = operation != null && !operation!!.isTerminal
    val canSubmit = !loadingCatalogs && !submitting && !opRunning &&
        nameError == null && limitsError == null &&
        systemImageId.isNotBlank() && deviceProfileId.isNotBlank()

    val shape = RoundedCornerShape(12.dp)
    Dialog(onDismissRequest = { if (!opRunning) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, shape)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .testTag("android-create-avd-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Create Android Virtual Device", style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
                if (!opRunning) {
                    SmallActionPill("Close", onClick = onDismiss)
                }
            }

            if (loadingCatalogs) {
                BasicText("Loading SDK system images and hardware profiles…", style = ppmText(12f).copy(color = tokens.text3.color))
            } else {
                BasicText("AVD Name", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                AndroidSingleLineInput(
                    value = name,
                    onValueChange = {
                        nameTouched = true
                        name = it
                    },
                    placeholder = "Pixel_7_API_34",
                    modifier = Modifier.fillMaxWidth().testTag("android-avd-name-input"),
                    mono = true,
                )
                if (nameTouched && nameError != null) {
                    BasicText(nameError, style = ppmText(11f).copy(color = tokens.error.color))
                }

                BasicText("System Image (${images.size})", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                AndroidSingleLineInput(
                    value = imageSearch,
                    onValueChange = { imageSearch = it },
                    placeholder = "Filter images by API, ABI, or tag…",
                    modifier = Modifier.fillMaxWidth(),
                )
                val filteredImages = remember(images, imageSearch) {
                    val q = imageSearch.trim().lowercase()
                    if (q.isEmpty()) images.take(25)
                    else images.filter {
                        it.displayName.lowercase().contains(q) ||
                            it.id.lowercase().contains(q) ||
                            it.abi.lowercase().contains(q)
                    }.take(25)
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (img in filteredImages) {
                        val selected = img.id == systemImageId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) tokens.accent.opacity(0.15).color else Color.Transparent)
                                .clickable { systemImageId = img.id }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(
                                    img.displayName,
                                    style = ppmText(11f, FontWeight.Medium).copy(color = if (selected) tokens.accent.color else tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                BasicText(
                                    "API ${img.apiLevel} · ${img.tag} · ${img.abi}",
                                    style = ppmText(10f).copy(color = tokens.text3.color),
                                )
                            }
                            BasicText(
                                if (img.installed) "Installed" else "Download",
                                style = ppmText(10f, FontWeight.Medium).copy(
                                    color = if (img.installed) tokens.success.color else tokens.warning.color,
                                ),
                            )
                        }
                    }
                }

                BasicText("Device Profile (${profiles.size})", style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text2.color))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 110.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .verticalScroll(rememberScrollState())
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (prof in profiles.take(30)) {
                        val selected = prof.id == deviceProfileId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) tokens.accent.opacity(0.15).color else Color.Transparent)
                                .clickable { deviceProfileId = prof.id }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BasicText(
                                prof.name + (if (!prof.oem.isNullOrBlank()) " (${prof.oem})" else ""),
                                style = ppmText(11f).copy(color = if (selected) tokens.accent.color else tokens.text.color),
                            )
                        }
                    }
                }

                SmallActionPill(
                    label = if (showAdvanced) "Hide advanced" else "Advanced (RAM / Storage)",
                    onClick = { showAdvanced = !showAdvanced },
                )
                if (showAdvanced) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicText("RAM (MB)", style = ppmText(10f).copy(color = tokens.text3.color))
                            AndroidSingleLineInput(value = ramMb, onValueChange = { ramMb = it }, placeholder = "2048", mono = true)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicText("Storage (MB)", style = ppmText(10f).copy(color = tokens.text3.color))
                            AndroidSingleLineInput(value = storageMb, onValueChange = { storageMb = it }, placeholder = "6144", mono = true)
                        }
                    }
                    if (limitsError != null) {
                        BasicText(limitsError, style = ppmText(11f).copy(color = tokens.error.color))
                    }
                }
            }

            operation?.let { op ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.bgSolid.color)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BasicText(op.step.ifBlank { op.status }, style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text.color))
                        if (op.progress != null) {
                            BasicText("${op.progress}%", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                        }
                    }
                    if (op.logTail.isNotEmpty()) {
                        BasicText(
                            op.logTail.takeLast(4).joinToString("\n"),
                            style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        )
                    }
                }
            }

            if (error != null) {
                BasicText(error!!, style = ppmText(11f).copy(color = tokens.error.color))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (opRunning && operation != null) {
                    SmallActionPill(
                        label = "Cancel operation",
                        danger = true,
                        onClick = {
                            val id = operation!!.id
                            scope.launch { runCatching { api.androidCancelOperation(id) } }
                        },
                    )
                } else {
                    SmallActionPill("Cancel", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    SmallActionPill(
                        label = if (submitting) "Starting…" else "Create AVD",
                        primary = true,
                        enabled = canSubmit,
                        modifier = Modifier.testTag("android-create-avd-submit"),
                        onClick = {
                            if (!canSubmit) return@SmallActionPill
                            submitting = true
                            error = null
                            scope.launch {
                                try {
                                    val res = api.androidCreateAvd(
                                        name = name.trim(),
                                        systemImageId = systemImageId,
                                        deviceProfileId = deviceProfileId,
                                        ramMb = ramMb.toIntOrNull(),
                                        storageMb = storageMb.toIntOrNull(),
                                    )
                                    val op = parseAndroidOperationStatus(res)
                                    if (op == null) {
                                        onCreated()
                                        return@launch
                                    }
                                    operation = op
                                    submitting = false
                                    while (isActive) {
                                        delay(1_000L)
                                        val next = parseAndroidOperationStatus(api.androidOperationStatus(op.id)) ?: break
                                        operation = next
                                        if (next.isTerminal) {
                                            if (next.status == "succeeded") {
                                                onCreated()
                                            } else {
                                                error = next.error ?: "AVD creation ${next.status}."
                                            }
                                            break
                                        }
                                    }
                                } catch (t: Throwable) {
                                    error = t.message ?: "Failed to create AVD"
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 2. Android Viewer (H.264 Stream, Multi-Touch, Controls Bar, Tools Drawer)
// ===========================================================================

enum class AndroidToolTab(val label: String) {
    INSTALL("Install"),
    LOGS("Logs"),
    CLIPBOARD("Clipboard"),
}

@Composable
internal fun AndroidViewer(
    model: AppModel,
    device: AndroidDeviceItem,
    onBack: () -> Unit,
) {
    val api = model.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var connState by remember { mutableStateOf(AndroidConnState.CONNECTING) }
    var deviceStatus by remember { mutableStateOf(device.runtime?.status ?: AndroidDeviceStatus.READY) }
    var controller by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(AndroidQuality.BALANCED) }
    var geometry by remember {
        mutableStateOf(
            AndroidGeometry(
                frameWidth = 1080,
                frameHeight = 2400,
                deviceWidth = 1080,
                deviceHeight = 2400,
                rotation = 0,
                generation = 1,
            ),
        )
    }
    var streamError by remember { mutableStateOf<String?>(null) }
    var hasVideoFrame by remember { mutableStateOf(false) }
    var connectAttempt by remember { mutableIntStateOf(0) }

    var keyboardOpen by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var activeDrawerTab by remember { mutableStateOf<AndroidToolTab?>(null) }
    var tallDrawer by remember { mutableStateOf(false) }
    var takingScreenshot by remember { mutableStateOf(false) }

    // Logcat state
    val logLimit = if (model.wide) 1000 else 400
    var logEntries by remember { mutableStateOf<List<AndroidLogEntry>>(emptyList()) }
    var droppedLogs by remember { mutableIntStateOf(0) }
    var logFilter by remember { mutableStateOf(EMPTY_ANDROID_LOG_FILTER) }
    var logsPaused by remember { mutableStateOf(false) }
    val logsPausedRef by rememberUpdatedState(logsPaused)
    val logLimitRef by rememberUpdatedState(logLimit)

    val decoder = remember {
        H264SurfaceDecoder(
            onFrameSizeChanged = { _ -> },
            onStatusChanged = { st, err ->
                if (st == "error" && !err.isNullOrBlank()) streamError = err
            },
        )
    }
    val activeSocketRef = remember { arrayOfNulls<WebSocket>(1) }
    val touchTracker = remember { AndroidTouchTracker() }

    fun sendWs(json: String) {
        activeSocketRef[0]?.send(json)
    }

    DisposableEffect(decoder) {
        onDispose {
            activeSocketRef[0]?.close(1000, "viewer disposed")
            activeSocketRef[0] = null
            decoder.reset()
        }
    }

    // Stream connection + single-use session nonce + heartbeat + exponential backoff
    LaunchedEffect(api, device.avdId, connectAttempt) {
        if (api == null) {
            connState = AndroidConnState.FAILED
            streamError = "Not connected to a PPM server."
            return@LaunchedEffect
        }
        var attempt = 0
        var sessionGeneration = -1
        while (isActive) {
            connState = if (attempt == 0) AndroidConnState.CONNECTING else AndroidConnState.RECONNECTING
            streamError = null
            val ticket = try {
                parseAndroidSessionTicket(api.androidCreateSession(device.avdId))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                streamError = t.message ?: "Failed to create Android session"
                null
            }
            if (ticket == null) {
                if (attempt >= ANDROID_RECONNECT_BACKOFF_MS.size) {
                    connState = AndroidConnState.FAILED
                    break
                }
                delay(ANDROID_RECONNECT_BACKOFF_MS[attempt])
                attempt++
                continue
            }
            sessionGeneration = ticket.generation
            var closedSignal = false
            var retryableDisconnect = true

            val ws = api.openAndroidSocket(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(buildAndroidAuthJson(ticket.nonce, quality))
                        if (activeDrawerTab == AndroidToolTab.LOGS) {
                            webSocket.send(
                                buildAndroidSubscribeLogsJson(
                                    enabled = true,
                                    minLevel = logFilter.minLevel,
                                    tag = logFilter.tag,
                                    pid = logFilter.pid.toIntOrNull(),
                                ),
                            )
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = parseAndroidServerMessage(text) ?: return
                        when (msg) {
                            is AndroidServerMessage.Ready -> {
                                attempt = 0
                                sessionGeneration = msg.generation
                                connState = AndroidConnState.CONNECTED
                                controller = msg.controller
                                quality = msg.quality
                                geometry = msg.geometry
                                streamError = null
                            }
                            is AndroidServerMessage.Codec -> {
                                val configBytes = runCatching {
                                    Base64.decode(msg.configBase64, Base64.DEFAULT)
                                }.getOrNull() ?: ByteArray(0)
                                decoder.configure()
                                if (configBytes.isNotEmpty()) {
                                    decoder.decodeAccessUnit(configBytes, 0, configBytes.size, true)
                                }
                                webSocket.send(buildAndroidRequestKeyframeJson())
                            }
                            is AndroidServerMessage.Geometry -> {
                                geometry = msg.geometry
                            }
                            is AndroidServerMessage.Controller -> {
                                controller = msg.controller
                            }
                            is AndroidServerMessage.Quality -> {
                                quality = msg.quality
                            }
                            is AndroidServerMessage.Status -> {
                                deviceStatus = msg.status
                                if (!msg.message.isNullOrBlank()) streamError = msg.message
                            }
                            is AndroidServerMessage.Log -> {
                                if (!logsPausedRef) {
                                    val res = appendBoundedLogs(logEntries, msg.entries, logLimitRef)
                                    logEntries = res.entries
                                    droppedLogs += res.dropped + (msg.dropped ?: 0)
                                }
                            }
                            is AndroidServerMessage.Error -> {
                                streamError = msg.message
                                retryableDisconnect = msg.retryable
                            }
                            is AndroidServerMessage.Heartbeat -> Unit
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val packet = decodeAndroidVideoFrame(bytes.toByteArray()) ?: return
                        if (sessionGeneration != -1 && packet.header.sessionGeneration != sessionGeneration) return
                        hasVideoFrame = true
                        decoder.decodeAccessUnit(
                            packet.payload,
                            0,
                            packet.payload.size,
                            packet.header.keyframe || packet.header.codecConfig,
                        )
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        closedSignal = true
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedSignal = true
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        streamError = t.message ?: "WebSocket connection failed"
                        closedSignal = true
                    }
                },
            )
            activeSocketRef[0] = ws

            try {
                while (isActive && !closedSignal) {
                    delay(ANDROID_HEARTBEAT_INTERVAL_MS)
                    if (!closedSignal) {
                        ws.send(buildAndroidHeartbeatJson())
                    }
                }
            } finally {
                if (activeSocketRef[0] === ws) {
                    activeSocketRef[0] = null
                }
                ws.close(1000, "closing")
            }

            if (!isActive) break
            if (!retryableDisconnect || attempt >= ANDROID_RECONNECT_BACKOFF_MS.size) {
                connState = AndroidConnState.FAILED
                break
            }
            connState = AndroidConnState.RECONNECTING
            delay(ANDROID_RECONNECT_BACKOFF_MS[attempt])
            attempt++
        }
    }

    // Subscribe/unsubscribe logs when the Logs drawer tab opens/closes or filter changes
    LaunchedEffect(activeDrawerTab, logFilter.minLevel, logFilter.tag, logFilter.pid, connState) {
        if (connState != AndroidConnState.CONNECTED) return@LaunchedEffect
        val enabled = activeDrawerTab == AndroidToolTab.LOGS
        sendWs(
            buildAndroidSubscribeLogsJson(
                enabled = enabled,
                minLevel = logFilter.minLevel,
                tag = logFilter.tag,
                pid = logFilter.pid.toIntOrNull(),
            ),
        )
    }

    val tokens = LocalPpmTokens.current
    val currentGeom by rememberUpdatedState(geometry)
    val currentController by rememberUpdatedState(controller)

    Column(
        Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("android-viewer"),
    ) {
        // Top header bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallActionPill("← Devices", modifier = Modifier.testTag("android-back-btn"), onClick = onBack)
            BasicText(device.name, style = ppmText(13f, FontWeight.SemiBold).copy(color = tokens.text.color))
            ConnBadge(connState)
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (controller) tokens.accent.opacity(0.15).color else tokens.panel2.color)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                BasicText(
                    if (controller) "Controlling" else "View only",
                    style = ppmText(10f, FontWeight.Medium).copy(color = if (controller) tokens.accent.color else tokens.text3.color),
                )
            }
            if (!controller && connState == AndroidConnState.CONNECTED) {
                SmallActionPill(
                    label = "Take control",
                    primary = true,
                    modifier = Modifier.testTag("android-take-control-btn"),
                    onClick = { sendWs(buildAndroidTakeControlJson()) },
                )
            }
            SmallActionPill(
                label = if (takingScreenshot) "Saving…" else "Screenshot",
                enabled = !takingScreenshot,
                modifier = Modifier.testTag("android-screenshot-btn"),
                onClick = {
                    if (api == null || takingScreenshot) return@SmallActionPill
                    takingScreenshot = true
                    scope.launch {
                        try {
                            val shot = api.androidTakeScreenshot(device.avdId)
                            val fileName = filenameFromDisposition(shot.contentDisposition, device.avdId)
                            val savedWhere = savePngScreenshot(context, fileName, shot.pngBytes)
                            model.toast(
                                ErrorToastMessage(
                                    title = "Saved screenshot ($fileName)",
                                    description = savedWhere,
                                    kind = ErrorToastMessage.Kind.SUCCESS,
                                ),
                            )
                        } catch (t: Throwable) {
                            model.toast(ErrorToastMessage("Screenshot failed", t.message))
                        } finally {
                            takingScreenshot = false
                        }
                    }
                },
            )
            SmallActionPill(
                label = "Tools",
                primary = activeDrawerTab != null,
                modifier = Modifier.testTag("android-tools-btn"),
                onClick = {
                    activeDrawerTab = if (activeDrawerTab == null) AndroidToolTab.INSTALL else null
                },
            )
            SmallActionPill(
                label = "Reconnect",
                onClick = { connectAttempt++ },
            )
            SmallActionPill(
                label = "Stop",
                danger = true,
                onClick = {
                    if (api != null) {
                        scope.launch {
                            runCatching { api.androidStopDevice(device.avdId, device.runtime?.generation) }
                            onBack()
                        }
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))

        // Main stage: H.264 SurfaceView contained inside letterbox box
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF09090B)),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx().toDouble() }
            val boxH = with(density) { maxHeight.toPx().toDouble() }
            val rect = containedRect(boxW, boxH, geometry.frameWidth, geometry.frameHeight)
            val surfW = with(density) { rect.width.toFloat().toDp() }
            val surfH = with(density) { rect.height.toFloat().toDp() }

            AndroidView(
                modifier = Modifier
                    .size(width = surfW.coerceAtLeast(1.dp), height = surfH.coerceAtLeast(1.dp))
                    .testTag("android-video-surface"),
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    decoder.attachSurface(holder.surface)
                                    sendWs(buildAndroidRequestKeyframeJson())
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    decoder.attachSurface(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    if (touchTracker.clear()) {
                                        sendWs(buildAndroidInputResetJson())
                                    }
                                    decoder.attachSurface(null)
                                }
                            },
                        )
                        setOnTouchListener { v, event ->
                            if (!currentController) return@setOnTouchListener false
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            val geom = currentGeom
                            val vw = v.width.toDouble().coerceAtLeast(1.0)
                            val vh = v.height.toDouble().coerceAtLeast(1.0)

                            fun mapPointer(idx: Int): AndroidIntPoint? = pointerToDevice(
                                boxWidth = vw,
                                boxHeight = vh,
                                offsetX = event.getX(idx).toDouble(),
                                offsetY = event.getY(idx).toDouble(),
                                geometry = geom,
                            )

                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                    val idx = event.actionIndex
                                    val pid = event.getPointerId(idx)
                                    val pt = mapPointer(idx) ?: return@setOnTouchListener true
                                    val contacts = touchTracker.onDown(pid, pt)
                                    if (contacts != null) {
                                        sendWs(buildAndroidTouchJson(AndroidTouchPhase.DOWN, contacts, geom.generation))
                                    }
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    val updates = ArrayList<Pair<Int, AndroidIntPoint>>(event.pointerCount)
                                    for (i in 0 until event.pointerCount) {
                                        val pid = event.getPointerId(i)
                                        val pt = mapPointer(i) ?: continue
                                        updates.add(pid to pt)
                                    }
                                    val contacts = touchTracker.onMove(updates)
                                    if (contacts != null) {
                                        sendWs(buildAndroidTouchJson(AndroidTouchPhase.MOVE, contacts, geom.generation))
                                    }
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                                    val idx = event.actionIndex
                                    val pid = event.getPointerId(idx)
                                    val pt = mapPointer(idx)
                                    val contacts = touchTracker.onUpOrCancel(pid, pt)
                                    if (contacts != null) {
                                        sendWs(buildAndroidTouchJson(AndroidTouchPhase.UP, contacts, geom.generation))
                                    }
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    val idx = event.actionIndex
                                    val pid = event.getPointerId(idx)
                                    val pt = mapPointer(idx)
                                    val contacts = touchTracker.onUpOrCancel(pid, pt)
                                    if (contacts != null) {
                                        sendWs(buildAndroidTouchJson(AndroidTouchPhase.CANCEL, contacts, geom.generation))
                                    }
                                    touchTracker.clear()
                                    sendWs(buildAndroidInputResetJson())
                                }
                            }
                            true
                        }
                    }
                },
            )

            // Status / loading / error overlay
            if (!hasVideoFrame || connState != AndroidConnState.CONNECTED || streamError != null) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val statusLabel = when {
                        streamError != null -> streamError!!
                        connState == AndroidConnState.CONNECTING -> "Connecting to ${device.name}…"
                        connState == AndroidConnState.RECONNECTING -> "Reconnecting stream…"
                        connState == AndroidConnState.FAILED -> "Connection failed"
                        deviceStatus == AndroidDeviceStatus.BOOTING -> "Android is booting…"
                        else -> "Waiting for H.264 keyframe…"
                    }
                    BasicText(
                        statusLabel,
                        style = ppmText(12f, FontWeight.Medium).copy(color = Color.White, textAlign = TextAlign.Center),
                    )
                    if (connState == AndroidConnState.FAILED || streamError != null) {
                        SmallActionPill("Retry now", primary = true, onClick = { connectAttempt++ })
                    }
                }
            }
        }

        // Optional soft/hardware keyboard input strip
        if (keyboardOpen) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel.color)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("android-keyboard-bar"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AndroidSingleLineInput(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        placeholder = "Type ASCII or Unicode/Vietnamese text to send…",
                        modifier = Modifier.weight(1f).testTag("android-keyboard-input"),
                    )
                    SmallActionPill(
                        label = "Send",
                        primary = true,
                        enabled = controller && keyboardText.isNotEmpty(),
                        modifier = Modifier.testTag("android-keyboard-send"),
                        onClick = {
                            if (!controller || keyboardText.isEmpty()) return@SmallActionPill
                            val payload = when (classifyAndroidTextInput(keyboardText)) {
                                AndroidTextInputKind.ASCII_TEXT -> buildAndroidTextJson(keyboardText)
                                AndroidTextInputKind.UNICODE_PASTE -> buildAndroidPasteJson(keyboardText)
                            }
                            sendWs(payload)
                            keyboardText = ""
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SmallActionPill("Enter", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.ENTER)) }
                    SmallActionPill("Bksp", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.BACKSPACE)) }
                    SmallActionPill("Tab", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.TAB)) }
                    SmallActionPill("Esc", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.ESCAPE)) }
                    SmallActionPill("◀", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.DPAD_LEFT)) }
                    SmallActionPill("▲", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.DPAD_UP)) }
                    SmallActionPill("▼", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.DPAD_DOWN)) }
                    SmallActionPill("▶", enabled = controller) { sendWs(buildAndroidKeyJson(AndroidKey.DPAD_RIGHT)) }
                }
            }
        }

        // Bottom Android Navigation & Hardware Controls (`android-controls.tsx`)
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
        AndroidControlsBar(
            controller = controller && connState == AndroidConnState.CONNECTED,
            quality = quality,
            keyboardOpen = keyboardOpen,
            onKey = { key -> sendWs(buildAndroidKeyJson(key)) },
            onRotate = {
                val next = nextAndroidRotation(geometry.rotation)
                sendWs(buildAndroidRotateJson(next))
            },
            onQuality = { q ->
                quality = q
                sendWs(buildAndroidSetQualityJson(q))
            },
            onToggleKeyboard = { keyboardOpen = !keyboardOpen },
        )

        // Collapsible Bottom Tools Drawer (`android-tools-drawer.tsx`)
        activeDrawerTab?.let { tab ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))
            AndroidToolsDrawer(
                model = model,
                deviceId = device.avdId,
                activeTab = tab,
                tall = tallDrawer,
                onSelectTab = { activeDrawerTab = it },
                onToggleTall = { tallDrawer = !tallDrawer },
                onClose = { activeDrawerTab = null },
                logEntries = logEntries,
                droppedLogs = droppedLogs,
                logFilter = logFilter,
                logsPaused = logsPaused,
                onLogFilterChange = { logFilter = it },
                onToggleLogsPaused = { logsPaused = !logsPaused },
                onClearLogs = {
                    logEntries = emptyList()
                    droppedLogs = 0
                },
            )
        }
    }
}

@Composable
internal fun AndroidControlsBar(
    controller: Boolean,
    quality: AndroidQuality,
    keyboardOpen: Boolean,
    onKey: (AndroidKey) -> Unit,
    onRotate: () -> Unit,
    onQuality: (AndroidQuality) -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var moreOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("android-controls-bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallActionPill("Back", enabled = controller, modifier = Modifier.testTag("android-key-back")) {
                onKey(AndroidKey.BACK)
            }
            SmallActionPill("Home", enabled = controller, modifier = Modifier.testTag("android-key-home")) {
                onKey(AndroidKey.HOME)
            }
            SmallActionPill("Recent", enabled = controller, modifier = Modifier.testTag("android-key-recent")) {
                onKey(AndroidKey.APP_SWITCH)
            }
            SmallActionPill(
                label = "Keyboard",
                primary = keyboardOpen,
                enabled = controller,
                modifier = Modifier.testTag("android-key-keyboard"),
                onClick = onToggleKeyboard,
            )
            SmallActionPill("Rotate", enabled = controller, modifier = Modifier.testTag("android-key-rotate"), onClick = onRotate)
            SmallActionPill(
                label = "More",
                primary = moreOpen,
                enabled = controller,
                modifier = Modifier.testTag("android-key-more"),
                onClick = { moreOpen = !moreOpen },
            )
            Spacer(Modifier.width(4.dp))
            for (preset in AndroidQuality.entries) {
                SmallActionPill(
                    label = preset.label,
                    primary = quality == preset,
                    modifier = Modifier.testTag("android-quality-${preset.wire}"),
                    onClick = { onQuality(preset) },
                )
            }
        }
        if (moreOpen) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("Hardware:", style = ppmText(11f).copy(color = tokens.text3.color))
                SmallActionPill("Volume +", enabled = controller) { onKey(AndroidKey.VOLUME_UP) }
                SmallActionPill("Volume −", enabled = controller) { onKey(AndroidKey.VOLUME_DOWN) }
                SmallActionPill("Power", enabled = controller) { onKey(AndroidKey.POWER) }
            }
        }
    }
}

// ===========================================================================
// 3. Tools Drawer (APK Install, Live Logcat, Device Clipboard)
// ===========================================================================

@Composable
internal fun AndroidToolsDrawer(
    model: AppModel,
    deviceId: String,
    activeTab: AndroidToolTab,
    tall: Boolean,
    onSelectTab: (AndroidToolTab) -> Unit,
    onToggleTall: () -> Unit,
    onClose: () -> Unit,
    logEntries: List<AndroidLogEntry>,
    droppedLogs: Int,
    logFilter: AndroidLogFilter,
    logsPaused: Boolean,
    onLogFilterChange: (AndroidLogFilter) -> Unit,
    onToggleLogsPaused: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val drawerHeight = if (tall) 340.dp else 220.dp

    Column(
        Modifier
            .fillMaxWidth()
            .height(drawerHeight)
            .background(tokens.panel.color)
            .testTag("android-tools-drawer"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (tab in AndroidToolTab.entries) {
                    SmallActionPill(
                        label = tab.label,
                        primary = activeTab == tab,
                        modifier = Modifier.testTag("android-drawer-tab-${tab.name.lowercase()}"),
                        onClick = { onSelectTab(tab) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallActionPill(if (tall) "Compact" else "Expand", onClick = onToggleTall)
                SmallActionPill("Close", onClick = onClose)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                AndroidToolTab.INSTALL -> AndroidApkPanel(model = model, deviceId = deviceId)
                AndroidToolTab.LOGS -> AndroidLogcatPanel(
                    model = model,
                    entries = logEntries,
                    dropped = droppedLogs,
                    filter = logFilter,
                    paused = logsPaused,
                    onFilterChange = onLogFilterChange,
                    onTogglePaused = onToggleLogsPaused,
                    onClear = onClearLogs,
                )
                AndroidToolTab.CLIPBOARD -> AndroidClipboardPanel(model = model, deviceId = deviceId)
            }
        }
    }
}

@Composable
private fun AndroidApkPanel(
    model: AppModel,
    deviceId: String,
) {
    val api = model.api
    val project = model.selectedProject
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = LocalPpmTokens.current

    var projectApks by remember { mutableStateOf<List<ProjectApkItem>>(emptyList()) }
    var loadingApks by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var uploadingBytes by remember { mutableLongStateOf(0L) }
    var uploadTotalBytes by remember { mutableLongStateOf(0L) }
    var uploadJob by remember { mutableStateOf<Job?>(null) }

    suspend fun loadApks() {
        if (api == null || project.isNullOrBlank()) return
        loadingApks = true
        try {
            projectApks = parseProjectApks(api.androidProjectApks(project))
        } catch (_: Throwable) {
        } finally {
            loadingApks = false
        }
    }

    LaunchedEffect(api, project) {
        loadApks()
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || api == null) return@rememberLauncherForActivityResult
        val (fileName, length) = queryDocumentMeta(context, uri)
        errorMessage = null
        statusMessage = "Uploading $fileName…"
        uploadingBytes = 0L
        uploadTotalBytes = length
        uploadJob = scope.launch {
            try {
                api.androidUploadApk(
                    deviceId = deviceId,
                    fileName = fileName,
                    contentLength = length,
                    openStream = {
                        context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Cannot open selected APK")
                    },
                    onProgress = { loaded, total ->
                        uploadingBytes = loaded
                        uploadTotalBytes = total
                    },
                )
                statusMessage = "Installed $fileName"
                model.toast(ErrorToastMessage("Installed $fileName", kind = ErrorToastMessage.Kind.SUCCESS))
            } catch (ce: CancellationException) {
                statusMessage = "Upload cancelled"
                throw ce
            } catch (t: Throwable) {
                errorMessage = t.message ?: "APK install failed"
                statusMessage = null
            } finally {
                uploadJob = null
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallActionPill(
                label = "Choose .apk file…",
                primary = true,
                enabled = uploadJob == null,
                modifier = Modifier.testTag("android-choose-apk-btn"),
                onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
            )
            if (uploadJob != null) {
                val pct = if (uploadTotalBytes > 0L) ((uploadingBytes * 100L) / uploadTotalBytes).toInt() else 0
                BasicText("Uploading ($pct%)…", style = ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text2.color))
                SmallActionPill("Cancel", danger = true, onClick = { uploadJob?.cancel() })
            }
            if (!project.isNullOrBlank()) {
                Spacer(Modifier.weight(1f))
                SmallActionPill("Refresh project APKs", onClick = { scope.launch { loadApks() } })
            }
        }

        if (statusMessage != null) {
            BasicText(statusMessage!!, style = ppmText(11f).copy(color = tokens.success.color))
        }
        if (errorMessage != null) {
            BasicText(errorMessage!!, style = ppmText(11f).copy(color = tokens.error.color))
        }

        if (!project.isNullOrBlank()) {
            BasicText(
                "Project APKs ($project)",
                style = ppmText(11f, FontWeight.SemiBold).copy(color = tokens.text2.color),
            )
            if (loadingApks) {
                BasicText("Scanning project for built APKs…", style = ppmText(11f).copy(color = tokens.text3.color))
            } else if (projectApks.isEmpty()) {
                BasicText("No .apk files found in $project.", style = ppmText(11f).copy(color = tokens.text3.color))
            } else {
                for (apk in projectApks) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.bgSolid.color)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(apk.fileName, style = ppmText(11f, FontWeight.Medium).copy(color = tokens.text.color))
                            BasicText(apk.relativePath, style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.text3.color))
                        }
                        SmallActionPill(
                            label = "Install",
                            primary = true,
                            onClick = {
                                if (api == null) return@SmallActionPill
                                errorMessage = null
                                statusMessage = "Installing ${apk.fileName}…"
                                scope.launch {
                                    try {
                                        api.androidInstallProjectApk(deviceId, project, apk.relativePath)
                                        statusMessage = "Installed ${apk.fileName}"
                                    } catch (t: Throwable) {
                                        errorMessage = t.message ?: "Failed to install ${apk.fileName}"
                                        statusMessage = null
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidLogcatPanel(
    model: AppModel,
    entries: List<AndroidLogEntry>,
    dropped: Int,
    filter: AndroidLogFilter,
    paused: Boolean,
    onFilterChange: (AndroidLogFilter) -> Unit,
    onTogglePaused: () -> Unit,
    onClear: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val filtered = remember(entries, filter) {
        entries.filter { matchesAndroidLogFilter(it, filter) }
    }
    var autoFollow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size, autoFollow, paused) {
        if (autoFollow && !paused && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (lvl in AndroidLogLevel.entries) {
                SmallActionPill(
                    label = lvl.wire,
                    primary = filter.minLevel == lvl,
                    onClick = { onFilterChange(filter.copy(minLevel = lvl)) },
                )
            }
            AndroidSingleLineInput(
                value = filter.tag,
                onValueChange = { onFilterChange(filter.copy(tag = it)) },
                placeholder = "Tag",
                modifier = Modifier.width(96.dp),
                mono = true,
            )
            AndroidSingleLineInput(
                value = filter.pid,
                onValueChange = { onFilterChange(filter.copy(pid = it)) },
                placeholder = "PID",
                modifier = Modifier.width(68.dp),
                mono = true,
            )
            AndroidSingleLineInput(
                value = filter.search,
                onValueChange = { onFilterChange(filter.copy(search = it)) },
                placeholder = "Search message…",
                modifier = Modifier.width(140.dp),
            )
            SmallActionPill(if (paused) "Resume" else "Pause", primary = paused, onClick = onTogglePaused)
            SmallActionPill(if (autoFollow) "Following" else "Follow", primary = autoFollow, onClick = { autoFollow = !autoFollow })
            SmallActionPill(
                label = "Copy",
                onClick = {
                    copyToHostClipboard(context, formatAndroidLogText(filtered))
                    model.toast(ErrorToastMessage("Copied ${filtered.size} log lines", kind = ErrorToastMessage.Kind.SUCCESS))
                },
            )
            SmallActionPill("Clear", onClick = onClear)
            if (dropped > 0) {
                BasicText("+$dropped dropped", style = ppmText(10f, kind = FontKind.MONO).copy(color = tokens.warning.color))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.bgSolid.color)
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filtered, key = { it.seq }) { entry ->
                val lvlColor = when (entry.level) {
                    AndroidLogLevel.E, AndroidLogLevel.F -> tokens.error.color
                    AndroidLogLevel.W -> tokens.warning.color
                    AndroidLogLevel.I -> tokens.accent.color
                    else -> tokens.text2.color
                }
                BasicText(
                    "${formatAndroidLogTime(entry.ts)} ${entry.level.wire}/${entry.tag}(${entry.pid}): ${entry.message}",
                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = lvlColor),
                )
            }
        }
    }
}

@Composable
private fun AndroidClipboardPanel(
    model: AppModel,
    deviceId: String,
) {
    val api = model.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = LocalPpmTokens.current
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallActionPill(
                label = "Read from device",
                enabled = !busy,
                modifier = Modifier.testTag("android-clipboard-read"),
                onClick = {
                    if (api == null || busy) return@SmallActionPill
                    busy = true
                    scope.launch {
                        try {
                            val res = api.androidGetClipboard(deviceId) as? JsonObject
                            val readText = res?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                            text = readText
                            copyToHostClipboard(context, readText)
                            status = "Read ${readText.length} chars from device"
                        } catch (t: Throwable) {
                            status = t.message ?: "Failed to read clipboard"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            SmallActionPill(
                label = "Send to device",
                primary = true,
                enabled = !busy,
                modifier = Modifier.testTag("android-clipboard-send"),
                onClick = {
                    if (api == null || busy) return@SmallActionPill
                    busy = true
                    scope.launch {
                        try {
                            api.androidSetClipboard(deviceId, text)
                            status = "Sent ${text.length} chars to device"
                        } catch (t: Throwable) {
                            status = t.message ?: "Failed to write clipboard"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            if (status != null) {
                BasicText(status!!, style = ppmText(11f).copy(color = tokens.text3.color))
            }
        }

        AndroidSingleLineInput(
            value = text,
            onValueChange = { text = it },
            placeholder = "Device clipboard contents…",
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("android-clipboard-input"),
        )
    }
}

// ===========================================================================
// Shared UI Atoms & Platform Helpers
// ===========================================================================

@Composable
private fun ConnBadge(state: AndroidConnState) {
    val tokens = LocalPpmTokens.current
    val (bg, fg, label) = when (state) {
        AndroidConnState.CONNECTED -> Triple(tokens.success.opacity(0.15).color, tokens.success.color, "Connected")
        AndroidConnState.CONNECTING -> Triple(tokens.warning.opacity(0.15).color, tokens.warning.color, "Connecting")
        AndroidConnState.RECONNECTING -> Triple(tokens.warning.opacity(0.15).color, tokens.warning.color, "Reconnecting")
        AndroidConnState.FAILED -> Triple(tokens.error.opacity(0.15).color, tokens.error.color, "Failed")
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(label, style = ppmText(10f, FontWeight.Medium).copy(color = fg))
    }
}

@Composable
private fun SmallActionPill(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val bg = when {
        !enabled -> tokens.panel2.opacity(0.45).color
        danger -> tokens.error.opacity(0.16).color
        primary -> tokens.accent.color
        else -> tokens.panel2.color
    }
    val fg = when {
        !enabled -> tokens.text3.opacity(0.5).color
        danger -> tokens.error.color
        primary -> tokens.accentFg.color
        else -> tokens.text.color
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(
                if (enabled) {
                    Modifier.ppmClickable(
                        pressedColor = tokens.panel2.color,
                        hoverColor = bg,
                        shape = shape,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = ppmText(11f, FontWeight.Medium).copy(color = fg))
    }
}

@Composable
private fun AndroidSingleLineInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    val textStyle = if (mono) {
        ppmText(11f, kind = FontKind.MONO).copy(color = tokens.text.color)
    } else {
        ppmText(12f).copy(color = tokens.text.color)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(tokens.accent.color),
        modifier = modifier
            .clip(shape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = textStyle.copy(color = tokens.text3.color))
                }
                inner()
            }
        },
    )
}

private fun copyToHostClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("PPM Android", text))
    }
}

private fun queryDocumentMeta(context: Context, uri: Uri): Pair<String, Long> {
    var name = "app.apk"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    }
    return name to size
}

private fun savePngScreenshot(context: Context, fileName: String, bytes: ByteArray): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                return "Downloads/$fileName"
            }
        }
    }
    val outFile = File(context.cacheDir, fileName)
    outFile.writeBytes(bytes)
    return outFile.absolutePath
}
