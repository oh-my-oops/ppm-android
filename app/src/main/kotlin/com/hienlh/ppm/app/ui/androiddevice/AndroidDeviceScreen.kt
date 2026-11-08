package com.hienlh.ppm.app.ui.androiddevice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.remotedesktop.H264SurfaceDecoder
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.androidCancelOperation
import com.hienlh.ppm.client.androidCapabilities
import com.hienlh.ppm.client.androidCreateAvd
import com.hienlh.ppm.client.androidCreateSession
import com.hienlh.ppm.client.androidDeleteAvd
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
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.ProjectInfo
import com.hienlh.ppm.state.androiddevice.ANDROID_HEARTBEAT_INTERVAL_MS
import com.hienlh.ppm.state.androiddevice.ANDROID_LOGCAT_MAX_ROWS
import com.hienlh.ppm.state.androiddevice.ANDROID_LOGCAT_RING_LIMIT
import com.hienlh.ppm.state.androiddevice.ANDROID_PROTOCOL_VERSION
import com.hienlh.ppm.state.androiddevice.ANDROID_RECONNECT_BACKOFF_MS
import com.hienlh.ppm.state.androiddevice.AndroidCapabilities
import com.hienlh.ppm.state.androiddevice.AndroidConnState
import com.hienlh.ppm.state.androiddevice.AndroidDeviceItem
import com.hienlh.ppm.state.androiddevice.AndroidGeometry
import com.hienlh.ppm.state.androiddevice.AndroidHardwareKey
import com.hienlh.ppm.state.androiddevice.AndroidLogEntry
import com.hienlh.ppm.state.androiddevice.AndroidLogFilter
import com.hienlh.ppm.state.androiddevice.AndroidLogLevel
import com.hienlh.ppm.state.androiddevice.AndroidQuality
import com.hienlh.ppm.state.androiddevice.AndroidServerMessage
import com.hienlh.ppm.state.androiddevice.AndroidTextInputKind
import com.hienlh.ppm.state.androiddevice.AndroidTouchTracker
import com.hienlh.ppm.state.androiddevice.AvdDeviceProfile
import com.hienlh.ppm.state.androiddevice.AvdLimits
import com.hienlh.ppm.state.androiddevice.EMPTY_ANDROID_LOG_FILTER
import com.hienlh.ppm.state.androiddevice.ProjectApkItem
import com.hienlh.ppm.state.androiddevice.SystemImageRow
import com.hienlh.ppm.state.androiddevice.appendBoundedLogs
import com.hienlh.ppm.state.androiddevice.buildAndroidAuthJson
import com.hienlh.ppm.state.androiddevice.buildAndroidHardwareKeyJson
import com.hienlh.ppm.state.androiddevice.buildAndroidHeartbeatJson
import com.hienlh.ppm.state.androiddevice.buildAndroidInputResetJson
import com.hienlh.ppm.state.androiddevice.buildAndroidKeyJson
import com.hienlh.ppm.state.androiddevice.buildAndroidLogcatJson
import com.hienlh.ppm.state.androiddevice.buildAndroidQualityJson
import com.hienlh.ppm.state.androiddevice.buildAndroidRotateJson
import com.hienlh.ppm.state.androiddevice.buildAndroidTakeControlJson
import com.hienlh.ppm.state.androiddevice.buildAndroidTextJson
import com.hienlh.ppm.state.androiddevice.buildAndroidTouchJson
import com.hienlh.ppm.state.androiddevice.buildAndroidVisibilityJson
import com.hienlh.ppm.state.androiddevice.classifyCommittedText
import com.hienlh.ppm.state.androiddevice.computeAndroidLetterbox
import com.hienlh.ppm.state.androiddevice.decodeAndroidVideoFrame
import com.hienlh.ppm.state.androiddevice.filenameFromDisposition
import com.hienlh.ppm.state.androiddevice.filterAndroidLogs
import com.hienlh.ppm.state.androiddevice.formatAndroidLogText
import com.hienlh.ppm.state.androiddevice.formatAndroidLogTime
import com.hienlh.ppm.state.androiddevice.nextAndroidRotation
import com.hienlh.ppm.state.androiddevice.parseAndroidCapabilities
import com.hienlh.ppm.state.androiddevice.parseAndroidDeviceProfiles
import com.hienlh.ppm.state.androiddevice.parseAndroidDevices
import com.hienlh.ppm.state.androiddevice.parseAndroidMintedSession
import com.hienlh.ppm.state.androiddevice.parseAndroidOperation
import com.hienlh.ppm.state.androiddevice.parseAndroidServerMessage
import com.hienlh.ppm.state.androiddevice.parseAndroidSystemImages
import com.hienlh.ppm.state.androiddevice.parseProjectApks
import com.hienlh.ppm.state.androiddevice.pointerToDevice
import com.hienlh.ppm.state.androiddevice.shouldDecodeAndroidAccessUnit
import com.hienlh.ppm.state.androiddevice.validateAvdName
import com.hienlh.ppm.state.androiddevice.validateAvdRam
import com.hienlh.ppm.state.androiddevice.validateAvdStorage
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import kotlin.math.roundToInt

private const val DEVICE_POLL_MS = 1_500L
private const val INSTALL_POLL_MS = 800L

/**
 * Android Device / Emulator Controller workspace tab (`Phase 11c`).
 *
 * Mirrors:
 * - `src/web/components/android/android-tab.tsx`
 * - `src/web/components/android/android-device-list.tsx`
 * - `src/web/components/android/android-avd-create-dialog.tsx`
 * - `src/web/components/android/android-avd-destroy-dialog.tsx`
 * - `src/web/components/android/android-viewer.tsx`
 * - `src/web/components/android/android-controls.tsx`
 * - `src/web/components/android/android-tools-drawer.tsx`
 * - `src/web/components/android/android-install-panel.tsx`
 * - `src/web/components/android/android-logcat-panel.tsx`
 * - `src/web/components/android/android-clipboard-panel.tsx`
 */
@Composable
fun AndroidTabBody(
    model: AppModel,
    tab: WorkspaceTab,
    modifier: Modifier = Modifier,
) {
    val api = model.api
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()

    val pinnedAvdId = (tab.metadata["avdId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    var capabilities by remember { mutableStateOf<AndroidCapabilities?>(null) }
    var devices by remember { mutableStateOf<List<AndroidDeviceItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyByAvd by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeDevice by remember { mutableStateOf<AndroidDeviceItem?>(null) }
    var checkingPinned by remember(pinnedAvdId) { mutableStateOf(pinnedAvdId != null) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var destroyDialog by remember { mutableStateOf<Pair<String, AndroidDeviceItem>?>(null) }
    var postActionPollJob by remember { mutableStateOf<Job?>(null) }

    suspend fun refreshAll() {
        val client = api ?: return
        try {
            val capsJson = client.androidCapabilities()
            val parsedCaps = parseAndroidCapabilities(capsJson)
            capabilities = parsedCaps
            if (parsedCaps.enabled) {
                val devJson = client.androidDevices()
                val parsedDevices = parseAndroidDevices(devJson)
                devices = parsedDevices
                if (pinnedAvdId != null && activeDevice == null && checkingPinned) {
                    activeDevice = parsedDevices.firstOrNull { it.avdId == pinnedAvdId && it.runtime != null }
                }
            } else {
                devices = emptyList()
            }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = e.message ?: "Failed to load Android devices"
        } finally {
            loading = false
            checkingPinned = false
        }
    }

    fun schedulePostActionPolls() {
        postActionPollJob?.cancel()
        postActionPollJob = scope.launch {
            repeat(4) {
                delay(DEVICE_POLL_MS)
                refreshAll()
            }
        }
    }

    LaunchedEffect(api, pinnedAvdId) {
        if (api == null) return@LaunchedEffect
        loading = true
        refreshAll()
        while (isActive) {
            delay(DEVICE_POLL_MS * 4)
            if (activeDevice == null) {
                refreshAll()
            }
        }
    }

    fun selectDevice(device: AndroidDeviceItem?) {
        activeDevice = device
        model.updateAndroidTabDevice(
            tabId = tab.id,
            avdId = device?.avdId,
            title = device?.name ?: "Android",
        )
    }

    val currentActive = activeDevice
    if (currentActive?.runtime != null && api != null) {
        AndroidLiveViewer(
            api = api,
            device = currentActive,
            projects = model.projects,
            selectedProject = model.selectedProject,
            onBackToList = {
                selectDevice(null)
                scope.launch { refreshAll() }
            },
            modifier = modifier.fillMaxSize().background(tokens.bgSolid.color),
        )
        return
    }

    Box(modifier.fillMaxSize().background(tokens.bgSolid.color)) {
        AndroidDeviceListView(
            capabilities = capabilities,
            devices = devices,
            loading = loading,
            error = error,
            busyByAvd = busyByAvd,
            onRefresh = { scope.launch { refreshAll() } },
            onOpenCreateAvd = { showCreateDialog = true },
            onConnect = { dev -> selectDevice(dev) },
            onStart = { dev ->
                val client = api ?: return@AndroidDeviceListView
                scope.launch {
                    busyByAvd = busyByAvd + (dev.avdId to "Starting…")
                    try {
                        val res = client.androidStartAvd(dev.avdId)
                        val initialOp = parseAndroidOperation(res)
                        val opId = initialOp?.id
                        if (!opId.isNullOrBlank()) {
                            while (isActive) {
                                delay(DEVICE_POLL_MS)
                                val pollJson = client.androidOperationStatus(opId)
                                val op = parseAndroidOperation(pollJson) ?: break
                                busyByAvd = busyByAvd + (dev.avdId to (op.detail ?: "Starting…"))
                                if (op.state == "succeeded") break
                                if (op.state == "failed" || op.state == "cancelled") {
                                    error = op.error ?: "the emulator failed to start"
                                    break
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = e.message ?: "Failed to start ${dev.name}"
                    } finally {
                        busyByAvd = busyByAvd - dev.avdId
                        refreshAll()
                        schedulePostActionPolls()
                    }
                }
            },
            onStop = { dev ->
                val client = api ?: return@AndroidDeviceListView
                val rt = dev.runtime ?: return@AndroidDeviceListView
                scope.launch {
                    busyByAvd = busyByAvd + (dev.avdId to "Stopping…")
                    try {
                        client.androidStopDevice(rt.deviceId, rt.generation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        error = e.message ?: "Failed to stop ${dev.name}"
                    } finally {
                        busyByAvd = busyByAvd - dev.avdId
                        refreshAll()
                        schedulePostActionPolls()
                    }
                }
            },
            onWipe = { dev -> destroyDialog = "wipe" to dev },
            onDelete = { dev -> destroyDialog = "delete" to dev },
        )

        if (showCreateDialog && api != null) {
            AndroidAvdCreateDialog(
                api = api,
                existingNames = devices.map { it.name },
                onDismiss = { showCreateDialog = false },
                onCreated = {
                    showCreateDialog = false
                    scope.launch {
                        refreshAll()
                        schedulePostActionPolls()
                    }
                },
            )
        }

        val destroy = destroyDialog
        if (destroy != null && api != null) {
            val (kind, targetDev) = destroy
            AndroidAvdDestroyDialog(
                action = kind,
                device = targetDev,
                onDismiss = { destroyDialog = null },
                onConfirm = { confirmName ->
                    val client = api
                    destroyDialog = null
                    scope.launch {
                        busyByAvd = busyByAvd + (targetDev.avdId to if (kind == "delete") "Deleting…" else "Wiping…")
                        try {
                            if (kind == "delete") {
                                client.androidDeleteAvd(targetDev.avdId, confirmName)
                            } else {
                                client.androidWipeAvd(targetDev.avdId, confirmName)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            error = e.message ?: "Failed to $kind ${targetDev.name}"
                        } finally {
                            busyByAvd = busyByAvd - targetDev.avdId
                            refreshAll()
                            schedulePostActionPolls()
                        }
                    }
                },
            )
        }
    }
}

// =============================================================================
// 1. Device List & Host Diagnostics (`android-device-list.tsx`)
// =============================================================================

@Composable
internal fun AndroidDeviceListView(
    capabilities: AndroidCapabilities?,
    devices: List<AndroidDeviceItem>,
    loading: Boolean,
    error: String?,
    busyByAvd: Map<String, String>,
    onRefresh: () -> Unit,
    onOpenCreateAvd: () -> Unit,
    onConnect: (AndroidDeviceItem) -> Unit,
    onStart: (AndroidDeviceItem) -> Unit,
    onStop: (AndroidDeviceItem) -> Unit,
    onWipe: (AndroidDeviceItem) -> Unit,
    onDelete: (AndroidDeviceItem) -> Unit,
) {
    val tokens = LocalPpmTokens.current

    if (capabilities == null && loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(
                "Checking this host…",
                style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text3.color),
            )
        }
        return
    }

    val unmet = capabilities?.requirements?.filter { !it.met }.orEmpty()
    val canCreateAvd = capabilities?.canCreateAvd ?: true
    val hostReady = capabilities?.ready ?: true

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Smartphone, 16.dp, tokens.text2.color)
                BasicText(
                    "Android devices",
                    style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canCreateAvd) {
                    SmallActionButton(
                        label = "New device",
                        icon = ProductIcon.Plus,
                        primary = true,
                        tag = "android-create-avd",
                        onClick = onOpenCreateAvd,
                    )
                }
                SmallActionButton(
                    label = "Refresh",
                    icon = ProductIcon.RefreshCw,
                    tag = "android-refresh",
                    onClick = onRefresh,
                )
            }
        }

        if (error != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.error.color.copy(alpha = 0.12f))
                    .border(1.dp, tokens.error.color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                BasicText(error, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
            }
        }

        if (capabilities != null && unmet.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.panel.color)
                    .border(1.dp, tokens.border.color, RoundedCornerShape(10.dp))
                    .padding(12.dp)
                    .testTag("android-capabilities-card"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    "This host is not ready yet",
                    style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                )
                for (req in capabilities.requirements) {
                    val fixCmd = req.fix
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.panel2.color)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(
                                if (req.met) ProductIcon.Check else ProductIcon.AlertTriangle,
                                13.dp,
                                if (req.met) tokens.success.color else tokens.error.color,
                            )
                            BasicText(
                                req.label,
                                style = ppmText(12f, if (!req.met) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 16f)
                                    .copy(color = tokens.text.color),
                            )
                        }
                        if (req.detail.isNotBlank()) {
                            BasicText(req.detail, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color))
                        }
                        if (!req.met && !fixCmd.isNullOrBlank()) {
                            BasicText(
                                fixCmd,
                                style = ppmText(11f, lineHeight = 15f, kind = FontKind.MONO).copy(color = tokens.accent.color),
                            )
                        }
                    }
                }
            }
        }

        if (devices.isEmpty() && unmet.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.panel.color)
                    .border(1.dp, tokens.border.color, RoundedCornerShape(10.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        "No AVDs on this host yet.",
                        style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color),
                    )
                    if (canCreateAvd) {
                        SmallActionButton(
                            label = "New device",
                            icon = ProductIcon.Plus,
                            primary = true,
                            tag = "android-empty-create-avd",
                            onClick = onOpenCreateAvd,
                        )
                    } else {
                        BasicText(
                            "Create one in Android Studio, or with avdmanager create avd.",
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    }
                }
            }
        } else {
            for (dev in devices) {
                val running = dev.runtime != null
                val busyLabel = busyByAvd[dev.avdId]
                val isBusy = busyLabel != null
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.panel.color)
                        .border(1.dp, tokens.border.color, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                        .testTag("android-device-row-${dev.avdId}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BasicText(
                                dev.name,
                                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val metaParts = buildList {
                                if (dev.apiLevel != null) add("API ${dev.apiLevel}")
                                if (!dev.abi.isNullOrBlank()) add(dev.abi)
                                if (dev.displayWidth != null && dev.displayHeight != null) {
                                    add("${dev.displayWidth}x${dev.displayHeight}")
                                }
                                if (running) {
                                    add(if (dev.runtime?.ownedByPpm == true) "running (PPM)" else "running (external)")
                                }
                            }
                            if (metaParts.isNotEmpty()) {
                                BasicText(
                                    metaParts.joinToString(" · "),
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                )
                            }
                            if (dev.lockedByAnotherProcess) {
                                BasicText(
                                    "Locked by another process — it is probably open in Android Studio.",
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color),
                                    modifier = Modifier.testTag("android-locked-${dev.avdId}"),
                                )
                            }
                            if (!dev.hardwareKeyboard) {
                                BasicText(
                                    "This AVD has no hardware keyboard, so typing will not reach it. Set hw.keyboard=yes in its config.ini.",
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                    modifier = Modifier.testTag("android-no-hw-keyboard-${dev.avdId}"),
                                )
                            }
                        }

                        if (busyLabel != null) {
                            BasicText(
                                busyLabel,
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text3.color),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (running) {
                            SmallActionButton(
                                label = "View",
                                icon = ProductIcon.Monitor,
                                primary = true,
                                tag = "android-connect-${dev.avdId}",
                                onClick = { onConnect(dev) },
                            )
                            if (dev.runtime?.ownedByPpm == true) {
                                SmallActionButton(
                                    label = "Stop",
                                    icon = ProductIcon.Square,
                                    enabled = !isBusy,
                                    tag = "android-stop-${dev.avdId}",
                                    onClick = { onStop(dev) },
                                )
                            }
                        } else {
                            SmallActionButton(
                                label = "Start",
                                icon = ProductIcon.Play,
                                primary = true,
                                enabled = !isBusy && !dev.lockedByAnotherProcess && hostReady,
                                tag = "android-start-${dev.avdId}",
                                onClick = { onStart(dev) },
                            )
                        }
                        SmallActionButton(
                            label = "Wipe data",
                            icon = ProductIcon.RotateCcw,
                            enabled = !running && !dev.lockedByAnotherProcess && !isBusy,
                            tag = "android-wipe-${dev.avdId}",
                            onClick = { onWipe(dev) },
                        )
                        SmallActionButton(
                            label = "Delete device",
                            icon = ProductIcon.Trash2,
                            danger = true,
                            enabled = !running && !dev.lockedByAnotherProcess && !isBusy && canCreateAvd,
                            tag = "android-delete-${dev.avdId}",
                            onClick = { onDelete(dev) },
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// 2. AVD Create & Destroy Dialogs (`android-avd-create-dialog.tsx`, `android-avd-destroy-dialog.tsx`)
// =============================================================================

@Composable
internal fun AndroidAvdCreateDialog(
    api: ApiClient,
    existingNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val mobile = LocalPpmMobile.current
    var images by remember { mutableStateOf<List<SystemImageRow>?>(null) }
    var profiles by remember { mutableStateOf<List<AvdDeviceProfile>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var selectedImageId by remember { mutableStateOf("") }
    var selectedProfileId by remember { mutableStateOf("") }
    var ramMbText by remember { mutableStateOf(AvdLimits.RAM_DEFAULT_MB.toString()) }
    var storageMbText by remember { mutableStateOf(AvdLimits.STORAGE_DEFAULT_MB.toString()) }
    var submitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api) {
        try {
            val imgRes = api.androidSystemImages()
            val profRes = api.androidDeviceProfiles()
            val parsedImages = parseAndroidSystemImages(imgRes)
            val parsedProfiles = parseAndroidDeviceProfiles(profRes)
            images = parsedImages
            profiles = parsedProfiles
            val usable = parsedImages.filter { it.hostCompatible }
            val newest = usable.maxByOrNull { it.apiLevel ?: 0 }
            if (newest != null && selectedImageId.isEmpty()) {
                selectedImageId = newest.id
            }
            val defaultProfile = parsedProfiles.firstOrNull { it.id == "pixel_9" } ?: parsedProfiles.firstOrNull()
            if (defaultProfile != null && selectedProfileId.isEmpty()) {
                selectedProfileId = defaultProfile.id
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loadError = e.message ?: "Failed to load SDK profiles"
        }
    }

    val content: @Composable () -> Unit = {
        AndroidAvdCreateFormContent(
            name = name,
            onNameChange = { name = it },
            existingNames = existingNames,
            images = images.orEmpty(),
            selectedImageId = selectedImageId,
            onSelectImage = { selectedImageId = it },
            profiles = profiles.orEmpty(),
            selectedProfileId = selectedProfileId,
            onSelectProfile = { selectedProfileId = it },
            ramMbText = ramMbText,
            onRamChange = { ramMbText = it },
            storageMbText = storageMbText,
            onStorageChange = { storageMbText = it },
            loading = loadError == null && (images == null || profiles == null),
            error = submitError ?: loadError,
            submitting = submitting,
            onDismiss = onDismiss,
            onSubmit = { validName, imgId, profId, ram, storage ->
                scope.launch {
                    submitting = true
                    submitError = null
                    try {
                        api.androidCreateAvd(
                            name = validName,
                            systemImage = imgId,
                            deviceProfile = profId,
                            ramMb = ram,
                            storageMb = storage,
                        )
                        onCreated(validName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        submitError = e.message ?: "Failed to create AVD"
                        submitting = false
                    }
                }
            },
        )
    }

    if (mobile) {
        BottomSheet(onDismiss = onDismiss) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                content()
            }
        }
    } else {
        val tokens = LocalPpmTokens.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.panel.color)
                    .border(1.dp, tokens.border.color, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun AndroidAvdCreateFormContent(
    name: String,
    onNameChange: (String) -> Unit,
    existingNames: List<String> = emptyList(),
    images: List<SystemImageRow>,
    selectedImageId: String,
    onSelectImage: (String) -> Unit,
    profiles: List<AvdDeviceProfile>,
    selectedProfileId: String,
    onSelectProfile: (String) -> Unit,
    ramMbText: String,
    onRamChange: (String) -> Unit,
    storageMbText: String,
    onStorageChange: (String) -> Unit,
    loading: Boolean,
    error: String?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, systemImage: String, deviceProfile: String, ramMb: Int, storageMb: Int) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val nameErr = if (name.isEmpty()) null else validateAvdName(name, existingNames)
    val ramVal = ramMbText.toIntOrNull()
    val ramErr = if (ramVal == null) {
        "RAM must be between ${AvdLimits.RAM_MIN_MB} and ${AvdLimits.RAM_MAX_MB} MB"
    } else {
        validateAvdRam(ramVal)
    }
    val storageVal = storageMbText.toIntOrNull()
    val storageErr = if (storageVal == null) {
        "Storage must be between ${AvdLimits.STORAGE_MIN_MB} and ${AvdLimits.STORAGE_MAX_MB} MB"
    } else {
        validateAvdStorage(storageVal)
    }
    val canSubmit = !loading && !submitting &&
        name.isNotEmpty() && nameErr == null &&
        ramErr == null && storageErr == null &&
        selectedImageId.isNotBlank() && selectedProfileId.isNotBlank()

    val compatibleImages = remember(images) { images.filter { it.hostCompatible } }
    val incompatibleImages = remember(images) { images.filter { !it.hostCompatible } }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("android-create-avd-dialog"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            "New Android device",
            style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
        )
        BasicText(
            "Built from a system image already installed on this host.",
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
        )

        if (loading) {
            BasicText("Reading the SDK…", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Name", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                DialogTextInput(
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = "Pixel_9_API_35",
                    tag = "avd-name-input",
                )
                if (nameErr != null) {
                    BasicText(
                        nameErr,
                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.error.color),
                        modifier = Modifier.testTag("avd-name-error"),
                    )
                } else {
                    BasicText(
                        "This becomes a folder name, so no spaces.",
                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("System image", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                if (images.isEmpty()) {
                    BasicText(
                        "No system images are installed",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color),
                    )
                } else {
                    Column(
                        Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (compatibleImages.isNotEmpty()) {
                            BasicText(
                                "Runs on this host",
                                style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                            )
                            for (img in compatibleImages) {
                                val selected = img.id == selectedImageId
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selected) tokens.accent.color.copy(alpha = 0.15f) else tokens.panel2.color)
                                        .border(
                                            1.dp,
                                            if (selected) tokens.accent.color else tokens.border.color,
                                            RoundedCornerShape(6.dp),
                                        )
                                        .clickable { onSelectImage(img.id) }
                                        .padding(8.dp)
                                        .testTag("avd-image-${img.id}"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        "API ${img.apiLevel ?: "?"} · ${img.tagDisplay} · ${img.abi}",
                                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        if (incompatibleImages.isNotEmpty()) {
                            BasicText(
                                "Wrong CPU for this host",
                                style = ppmText(10f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
                            )
                            for (img in incompatibleImages) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(tokens.panel2.color.copy(alpha = 0.5f))
                                        .border(1.dp, tokens.border.color, RoundedCornerShape(6.dp))
                                        .padding(8.dp)
                                        .testTag("avd-image-incompatible-${img.id}"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        "API ${img.apiLevel ?: "?"} · ${img.tagDisplay} · ${img.abi}",
                                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                        modifier = Modifier.weight(1f),
                                    )
                                    BasicText(
                                        "needs ${img.abi}",
                                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Device", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                Column(
                    Modifier.heightIn(max = 110.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (prof in profiles) {
                        val selected = prof.id == selectedProfileId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) tokens.accent.color.copy(alpha = 0.15f) else tokens.panel2.color)
                                .border(
                                    1.dp,
                                    if (selected) tokens.accent.color else tokens.border.color,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { onSelectProfile(prof.id) }
                                .padding(8.dp)
                                .testTag("avd-profile-${prof.id}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BasicText(
                                prof.name,
                                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                            if (prof.oem.isNotBlank()) {
                                BasicText(
                                    prof.oem,
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("RAM (MB)", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                    DialogTextInput(
                        value = ramMbText,
                        onValueChange = onRamChange,
                        placeholder = "2048",
                        numeric = true,
                        tag = "avd-ram-input",
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText("Storage (MB)", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color))
                    DialogTextInput(
                        value = storageMbText,
                        onValueChange = onStorageChange,
                        placeholder = "6144",
                        numeric = true,
                        tag = "avd-storage-input",
                    )
                }
            }
            if (ramErr != null || storageErr != null) {
                BasicText(
                    ramErr ?: storageErr.orEmpty(),
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.error.color),
                    modifier = Modifier.testTag("avd-limits-error"),
                )
            }
        }

        if (error != null) {
            BasicText(error, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallActionButton(label = "Cancel", tag = "avd-create-cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            SmallActionButton(
                label = if (submitting) "Creating…" else "Create device",
                primary = true,
                enabled = canSubmit,
                tag = "avd-create-submit",
                onClick = {
                    if (canSubmit && ramVal != null && storageVal != null) {
                        onSubmit(name, selectedImageId, selectedProfileId, ramVal, storageVal)
                    }
                },
            )
        }
    }
}

@Composable
internal fun AndroidAvdDestroyDialog(
    action: String,
    device: AndroidDeviceItem,
    onDismiss: () -> Unit,
    onConfirm: (confirmName: String) -> Unit,
) {
    val mobile = LocalPpmMobile.current
    val tokens = LocalPpmTokens.current
    var typedName by remember(action, device.avdId) { mutableStateOf("") }
    val isDelete = action == "delete"
    val matches = typedName == device.name

    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("android-destroy-dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                if (isDelete) "Delete this device" else "Wipe this device",
                style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
            )
            BasicText(
                if (isDelete) {
                    "${device.name} and everything in it will be removed from disk. The AVD, its settings and its installed apps are gone for good — this is not a recycle bin."
                } else {
                    "Everything inside ${device.name} goes back to factory: installed apps, accounts, files and saved snapshots. The device itself and its settings stay, and the next start is a cold boot."
                },
                style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
            )
            BasicText(
                "Type ${device.name} to confirm",
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            )
            DialogTextInput(
                value = typedName,
                onValueChange = { typedName = it },
                placeholder = device.name,
                tag = "avd-destroy-confirm-input",
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallActionButton(label = "Cancel", tag = "avd-destroy-cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                SmallActionButton(
                    label = if (isDelete) "Delete device" else "Wipe data",
                    danger = true,
                    enabled = matches,
                    tag = "avd-destroy-confirm",
                    onClick = { if (matches) onConfirm(device.name) },
                )
            }
        }
    }

    if (mobile) {
        BottomSheet(onDismiss = onDismiss) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                content()
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.panel.color)
                    .border(1.dp, tokens.border.color, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                content()
            }
        }
    }
}

// =============================================================================
// 3. Live Emulator Viewer (`android-viewer.tsx`, `use-android-session.ts`, `android-controls.tsx`)
// =============================================================================

private enum class AndroidDrawerTool(val wire: String, val label: String, val icon: ProductIcon) {
    INSTALL("install", "Install", ProductIcon.Smartphone),
    LOGS("logs", "Logs", ProductIcon.ScrollText),
    CLIPBOARD("clipboard", "Clipboard", ProductIcon.Clipboard),
}

@Composable
internal fun AndroidLiveViewer(
    api: ApiClient,
    device: AndroidDeviceItem,
    projects: List<ProjectInfo>,
    selectedProject: String?,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val deviceId = device.runtime?.deviceId ?: device.avdId

    var connState by remember { mutableStateOf(AndroidConnState.CONNECTING) }
    var geometry by remember { mutableStateOf(AndroidGeometry()) }
    var sessionGeneration by remember { mutableIntStateOf(1) }
    var controller by remember { mutableStateOf(false) }
    var controllerReason by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf(AndroidQuality.BALANCED) }
    var encoder by remember { mutableStateOf<String?>(null) }
    var hasPicture by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var toolBanner by remember { mutableStateOf<String?>(null) }
    var reconnectTick by remember { mutableIntStateOf(0) }
    var shooting by remember { mutableStateOf(false) }

    var activeTool by remember { mutableStateOf<AndroidDrawerTool?>(null) }
    var logEntries by remember { mutableStateOf<List<AndroidLogEntry>>(emptyList()) }
    var logPaused by remember { mutableStateOf(false) }
    val currentLogPaused by rememberUpdatedState(logPaused)

    var keyboardVisible by remember { mutableStateOf(false) }
    var imeText by remember { mutableStateOf(TextFieldValue("")) }
    val imeFocusRequester = remember { FocusRequester() }
    val stageFocusRequester = remember { FocusRequester() }

    val decoder = remember {
        H264SurfaceDecoder(
            onFrameSizeChanged = { _ -> },
            onStatusChanged = { _, _ -> },
        )
    }

    var activeWs by remember { mutableStateOf<WebSocket?>(null) }
    val touchTracker = remember { AndroidTouchTracker() }
    val currentQuality by rememberUpdatedState(quality)
    val currentActiveTool by rememberUpdatedState(activeTool)

    fun sendWs(jsonText: String) {
        activeWs?.send(jsonText)
    }

    DisposableEffect(Unit) {
        onDispose {
            activeWs?.close(1000, "viewer disposed")
            activeWs = null
            decoder.attachSurface(null)
            decoder.reset()
        }
    }

    // Lifecycle visibility observer (`visibility{visible}` on ON_STOP / ON_START)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (touchTracker.reset()) {
                        sendWs(buildAndroidInputResetJson())
                    }
                    sendWs(buildAndroidVisibilityJson(false))
                }
                Lifecycle.Event.ON_START -> {
                    sendWs(buildAndroidVisibilityJson(true))
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Blur soft keyboard if lease is lost
    LaunchedEffect(controller) {
        if (!controller && keyboardVisible) {
            keyboardVisible = false
            keyboardController?.hide()
        }
    }

    // Subscribe/unsubscribe logcat when Logs drawer opens/closes
    LaunchedEffect(activeTool, activeWs, connState) {
        if (connState == AndroidConnState.LIVE) {
            sendWs(buildAndroidLogcatJson(activeTool == AndroidDrawerTool.LOGS))
        }
    }

    // Main WebSocket connection & immediate-close reconnect loop (`use-android-session.ts`)
    LaunchedEffect(api, deviceId, reconnectTick) {
        var attempt = 0
        var fatalError = false

        while (isActive && !fatalError) {
            connState = if (attempt == 0) AndroidConnState.CONNECTING else AndroidConnState.RECONNECTING
            errorMessage = null

            val minted = try {
                parseAndroidMintedSession(api.androidCreateSession(deviceId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = e.message ?: "Could not connect."
                connState = AndroidConnState.FAILED
                break
            }

            if (minted == null) {
                errorMessage = "Could not mint session nonce."
                connState = AndroidConnState.FAILED
                break
            }

            val closedSignal = CompletableDeferred<Unit>()
            var configuredCodec: String? = null
            var localHasPicture = false

            val ws = api.openAndroidSocket(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(buildAndroidAuthJson(minted.nonce, currentQuality))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = parseAndroidServerMessage(text) ?: return
                        scope.launch {
                            when (msg) {
                                is AndroidServerMessage.Ready -> {
                                    attempt = 0
                                    connState = AndroidConnState.LIVE
                                    errorMessage = null
                                    sessionGeneration = msg.sessionGeneration
                                    geometry = msg.geometry
                                    controller = msg.controller
                                    controllerReason = null
                                    encoder = msg.encoder
                                    if (msg.codec != null && msg.codec != configuredCodec) {
                                        configuredCodec = msg.codec
                                        decoder.configure()
                                    }
                                    if (currentActiveTool == AndroidDrawerTool.LOGS) {
                                        webSocket.send(buildAndroidLogcatJson(true))
                                    }
                                }
                                is AndroidServerMessage.Codec -> {
                                    if (msg.codec != configuredCodec) {
                                        configuredCodec = msg.codec
                                        decoder.configure()
                                    }
                                }
                                is AndroidServerMessage.Geometry -> {
                                    geometry = msg.geometry
                                    decoder.reset()
                                    localHasPicture = false
                                    hasPicture = false
                                }
                                is AndroidServerMessage.Controller -> {
                                    controller = msg.controller
                                    controllerReason = msg.reason
                                }
                                is AndroidServerMessage.Quality -> {
                                    quality = msg.quality
                                }
                                is AndroidServerMessage.Status -> {}
                                is AndroidServerMessage.Log -> {
                                    if (!currentLogPaused) {
                                        logEntries = appendBoundedLogs(logEntries, msg.entries, ANDROID_LOGCAT_RING_LIMIT)
                                    }
                                }
                                is AndroidServerMessage.Error -> {
                                    errorMessage = msg.message
                                    if (msg.fatal) {
                                        fatalError = true
                                        connState = AndroidConnState.FAILED
                                        closedSignal.complete(Unit)
                                    }
                                }
                                is AndroidServerMessage.Heartbeat -> {}
                                is AndroidServerMessage.Unknown -> {}
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val frame = decodeAndroidVideoFrame(bytes.toByteArray()) ?: return
                        if (frame.header.version != ANDROID_PROTOCOL_VERSION) return
                        if (!shouldDecodeAndroidAccessUnit(localHasPicture, frame.header.keyframe)) return
                        if (!localHasPicture) {
                            localHasPicture = true
                            scope.launch { hasPicture = true }
                        }
                        decoder.enqueueAccessUnit(frame.payload, 0, frame.payload.size, frame.header.keyframe)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedSignal.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        scope.launch {
                            if (errorMessage == null) errorMessage = t.message ?: "Connection lost"
                        }
                        closedSignal.complete(Unit)
                    }
                },
            )
            activeWs = ws

            try {
                coroutineScope {
                    val heartbeatJob = launch {
                        while (isActive) {
                            delay(ANDROID_HEARTBEAT_INTERVAL_MS)
                            ws.send(buildAndroidHeartbeatJson())
                        }
                    }
                    closedSignal.await()
                    heartbeatJob.cancel()
                }
            } finally {
                ws.close(1000, "closing")
                if (activeWs === ws) activeWs = null
            }

            hasPicture = false
            decoder.reset()

            if (fatalError || !isActive) break
            attempt += 1
            if (attempt > ANDROID_RECONNECT_BACKOFF_MS.size) {
                connState = AndroidConnState.FAILED
                break
            }
            connState = AndroidConnState.RECONNECTING
            val delayMs = ANDROID_RECONNECT_BACKOFF_MS[(attempt - 1).coerceAtMost(ANDROID_RECONNECT_BACKOFF_MS.lastIndex)]
            delay(delayMs)
        }
    }

    Column(modifier.fillMaxSize()) {
        // 1. Viewer Header (`android-viewer.tsx:75-102`)
        Row(
            Modifier
                .fillMaxWidth()
                .background(tokens.panel.color)
                .border(1.dp, tokens.border.color)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SmallActionButton(
                label = "All devices",
                icon = ProductIcon.ArrowLeft,
                tag = "android-back-to-list",
                onClick = onBackToList,
            )
            BasicText(
                device.name,
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            SmallActionButton(
                label = "Save a screenshot",
                icon = ProductIcon.Image,
                enabled = !shooting,
                tag = "android-screenshot",
                onClick = {
                    scope.launch {
                        shooting = true
                        toolBanner = null
                        try {
                            val shot = api.androidTakeScreenshot(deviceId)
                            val fileName = filenameFromDisposition(shot.contentDisposition, "${device.name}.png")
                            val msg = savePngToDevice(context, fileName, shot.pngBytes)
                            toolBanner = msg
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            toolBanner = e.message ?: "Screenshot failed"
                        } finally {
                            shooting = false
                        }
                    }
                },
            )
            for (tool in AndroidDrawerTool.entries) {
                SmallActionButton(
                    label = tool.label,
                    icon = tool.icon,
                    primary = activeTool == tool,
                    tag = "android-tool-${tool.wire}",
                    onClick = { activeTool = if (activeTool == tool) null else tool },
                )
            }
        }

        // Tool banner or non-hardware-keyboard notice
        if (toolBanner != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    toolBanner.orEmpty(),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "Dismiss",
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.accent.color),
                    modifier = Modifier.clickable { toolBanner = null }.padding(4.dp),
                )
            }
        }

        if (!device.hardwareKeyboard) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("android-viewer-no-hw-keyboard"),
            ) {
                BasicText(
                    "This AVD has no hardware keyboard, so typing will not reach it. Set hw.keyboard=yes in its config.ini.",
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
        }

        // Take-control banner (`android-viewer.tsx:107-115`)
        AndroidTakeControlBanner(
            visible = !controller && connState == AndroidConnState.LIVE,
            controllerReason = controllerReason,
            onTakeControl = { sendWs(buildAndroidTakeControlJson()) },
        )

        // 2. Video Stage (`android-viewer.tsx:117-156`)
        val currentGeometry by rememberUpdatedState(geometry)
        val currentController by rememberUpdatedState(controller)

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .focusRequester(stageFocusRequester)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (!currentController || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val w3cKey = when (ev.key) {
                        Key.Enter, Key.NumPadEnter -> "Enter"
                        Key.Backspace -> "Backspace"
                        Key.Delete -> "Delete"
                        Key.Tab -> "Tab"
                        Key.Escape -> "Escape"
                        Key.DirectionUp -> "ArrowUp"
                        Key.DirectionDown -> "ArrowDown"
                        Key.DirectionLeft -> "ArrowLeft"
                        Key.DirectionRight -> "ArrowRight"
                        Key.MoveHome -> "Home"
                        Key.MoveEnd -> "End"
                        Key.PageUp -> "PageUp"
                        Key.PageDown -> "PageDown"
                        else -> null
                    }
                    if (w3cKey != null) {
                        sendWs(buildAndroidKeyJson(currentGeometry.generation, w3cKey, "press"))
                        true
                    } else {
                        false
                    }
                },
        ) {
            val stageW = constraints.maxWidth.toFloat()
            val stageH = constraints.maxHeight.toFloat()
            val displayRect = remember(stageW, stageH, geometry.width, geometry.height) {
                computeAndroidLetterbox(stageW, stageH, geometry.width, geometry.height)
            }
            val currentRect by rememberUpdatedState(displayRect)

            val surfaceW = with(density) { displayRect.width.coerceAtLeast(1f).toDp() }
            val surfaceH = with(density) { displayRect.height.coerceAtLeast(1f).toDp() }
            val offsetX = displayRect.left.roundToInt()
            val offsetY = displayRect.top.roundToInt()

            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.setFixedSize(geometry.width.coerceAtLeast(1), geometry.height.coerceAtLeast(1))
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
                    }
                },
                update = { view ->
                    view.holder.setFixedSize(geometry.width.coerceAtLeast(1), geometry.height.coerceAtLeast(1))
                },
                modifier = Modifier
                    .offset { IntOffset(offsetX, offsetY) }
                    .size(surfaceW, surfaceH)
                    .testTag("android-video-surface"),
            )

            // Multitouch pointer overlay (`use-android-touch.ts`)
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("android-stage")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (!currentController) continue
                                val geo = currentGeometry
                                val rect = currentRect
                                for (change in event.changes) {
                                    val pid = change.id.value
                                    val devPt = pointerToDevice(change.position.x, change.position.y, rect, geo)
                                    if (!change.previousPressed && change.pressed) {
                                        if (devPt != null) {
                                            val touches = touchTracker.onDown(pid, devPt)
                                            if (!touches.isNullOrEmpty()) {
                                                sendWs(buildAndroidTouchJson(geo.generation, touches))
                                            }
                                            change.consume()
                                        }
                                    } else if (change.previousPressed && change.pressed) {
                                        val touches = touchTracker.onMove(pid, devPt)
                                        if (!touches.isNullOrEmpty()) {
                                            sendWs(buildAndroidTouchJson(geo.generation, touches))
                                            change.consume()
                                        }
                                    } else if (change.previousPressed && !change.pressed) {
                                        val touches = touchTracker.onUpOrCancel(pid, devPt)
                                        if (!touches.isNullOrEmpty()) {
                                            sendWs(buildAndroidTouchJson(geo.generation, touches))
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        }
                    },
            )

            // Waiting / Failed overlay (`android-viewer.tsx:132-155`)
            if (!hasPicture) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (connState == AndroidConnState.FAILED) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BasicText(
                                errorMessage ?: "Could not connect.",
                                style = ppmText(13f, lineHeight = 18f).copy(color = Color.White.copy(alpha = 0.85f)),
                            )
                            SmallActionButton(
                                label = "Try again",
                                icon = ProductIcon.RefreshCw,
                                primary = true,
                                tag = "android-try-again",
                                onClick = { reconnectTick++ },
                            )
                        }
                    } else {
                        val connecting = connState == AndroidConnState.CONNECTING || connState == AndroidConnState.RECONNECTING
                        BasicText(
                            if (connecting) "Connecting to ${device.name}…" else "Waiting for the first frame…",
                            style = ppmText(13f, lineHeight = 18f).copy(color = Color.White.copy(alpha = 0.75f)),
                            modifier = Modifier.testTag("android-waiting-overlay"),
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(tokens.error.color.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    BasicText(
                        errorMessage.orEmpty(),
                        style = ppmText(12f, lineHeight = 16f).copy(color = Color.White),
                    )
                }
            }
        }

        // Hidden / live IME text capture (`use-android-keyboard.ts`)
        if (keyboardVisible && controller) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .border(1.dp, tokens.border.color)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = imeText,
                    onValueChange = { next ->
                        val prev = imeText
                        imeText = next
                        val kind = classifyCommittedText(
                            previous = prev.text,
                            current = next.text,
                            hasComposition = next.composition != null,
                        )
                        when (kind) {
                            is AndroidTextInputKind.None -> {}
                            is AndroidTextInputKind.Backspace -> {
                                repeat(kind.count) {
                                    sendWs(buildAndroidKeyJson(geometry.generation, "Backspace", "press"))
                                }
                                imeText = TextFieldValue("")
                            }
                            is AndroidTextInputKind.Enter -> {
                                sendWs(buildAndroidKeyJson(geometry.generation, "Enter", "press"))
                                imeText = TextFieldValue("")
                            }
                            is AndroidTextInputKind.Commit -> {
                                sendWs(buildAndroidTextJson(kind.text))
                                imeText = TextFieldValue("")
                            }
                        }
                    },
                    textStyle = ppmText(13f, lineHeight = 18f).copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.bgSolid.color)
                        .border(1.dp, tokens.border.color, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .focusRequester(imeFocusRequester)
                        .testTag("android-ime-input"),
                    decorationBox = { inner ->
                        Box {
                            if (imeText.text.isEmpty()) {
                                BasicText(
                                    "Type to send to ${device.name}…",
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                )
                            }
                            inner()
                        }
                    },
                )
                SmallActionButton(
                    label = "Enter",
                    tag = "android-ime-enter",
                    onClick = { sendWs(buildAndroidKeyJson(geometry.generation, "Enter", "press")) },
                )
                SmallActionButton(
                    label = "Backspace",
                    tag = "android-ime-backspace",
                    onClick = { sendWs(buildAndroidKeyJson(geometry.generation, "Backspace", "press")) },
                )
            }
            LaunchedEffect(Unit) {
                runCatching { imeFocusRequester.requestFocus() }
                keyboardController?.show()
            }
        }

        // 3. Tools Drawer (`android-tools-drawer.tsx`)
        val openTool = activeTool
        if (openTool != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(tokens.panel.color)
                    .border(1.dp, tokens.border.color)
                    .testTag("android-tools-drawer"),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(tokens.panel2.color)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in AndroidDrawerTool.entries) {
                        SmallActionButton(
                            label = t.label,
                            icon = t.icon,
                            primary = openTool == t,
                            tag = "android-drawer-tab-${t.wire}",
                            onClick = { activeTool = t },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SmallActionButton(
                        label = "Hide the tools",
                        icon = ProductIcon.ChevronDown,
                        tag = "android-tools-close",
                        onClick = { activeTool = null },
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (openTool) {
                        AndroidDrawerTool.INSTALL -> AndroidInstallPanel(
                            api = api,
                            deviceId = deviceId,
                            deviceName = device.name,
                            projects = projects,
                            initialProject = selectedProject,
                        )
                        AndroidDrawerTool.LOGS -> AndroidLogcatPanel(
                            entries = logEntries,
                            paused = logPaused,
                            onTogglePause = { logPaused = !logPaused },
                            onClear = { logEntries = emptyList() },
                        )
                        AndroidDrawerTool.CLIPBOARD -> AndroidClipboardPanel(
                            api = api,
                            deviceId = deviceId,
                        )
                    }
                }
            }
        }

        // 4. Bottom Hardware & Quality Controls Bar (`android-controls.tsx`)
        AndroidControlsBar(
            controller = controller,
            rotation = geometry.rotation,
            quality = quality,
            keyboardOpen = keyboardVisible,
            onHardwareKey = { key -> sendWs(buildAndroidHardwareKeyJson(key)) },
            onToggleKeyboard = {
                keyboardVisible = !keyboardVisible
                if (!keyboardVisible) keyboardController?.hide()
            },
            onRotate = { sendWs(buildAndroidRotateJson(nextAndroidRotation(geometry.rotation))) },
            onQualityChange = { q ->
                quality = q
                sendWs(buildAndroidQualityJson(q))
            },
        )
    }
}

@Composable
internal fun AndroidTakeControlBanner(
    visible: Boolean,
    controllerReason: String?,
    onTakeControl: () -> Unit,
) {
    if (!visible) return
    val tokens = LocalPpmTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .border(1.dp, tokens.border.color)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("android-take-control-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText(
            controllerReason ?: "Someone else is controlling this device.",
            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
            modifier = Modifier.weight(1f).testTag("android-controller-reason"),
        )
        SmallActionButton(
            label = "Take control",
            primary = true,
            tag = "android-take-control",
            onClick = onTakeControl,
        )
    }
}

@Composable
internal fun AndroidControlsBar(
    controller: Boolean,
    rotation: Int,
    quality: AndroidQuality,
    keyboardOpen: Boolean,
    onHardwareKey: (AndroidHardwareKey) -> Unit,
    onToggleKeyboard: () -> Unit,
    onRotate: () -> Unit,
    onQualityChange: (AndroidQuality) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var extrasOpen by remember { mutableStateOf(false) }
    var qualityMenuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .border(1.dp, tokens.border.color)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("android-controls-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SmallActionButton(
            label = "Back",
            icon = ProductIcon.ArrowLeft,
            enabled = controller,
            tag = "android-hw-back",
            onClick = { onHardwareKey(AndroidHardwareKey.BACK) },
        )
        SmallActionButton(
            label = "Home",
            icon = ProductIcon.Circle,
            enabled = controller,
            tag = "android-hw-home",
            onClick = { onHardwareKey(AndroidHardwareKey.HOME) },
        )
        SmallActionButton(
            label = "Recent apps",
            icon = ProductIcon.Square,
            enabled = controller,
            tag = "android-hw-recent",
            onClick = { onHardwareKey(AndroidHardwareKey.RECENT) },
        )

        SmallActionButton(
            label = "Keyboard",
            icon = ProductIcon.Terminal,
            primary = keyboardOpen,
            enabled = controller,
            tag = "android-toggle-keyboard",
            onClick = onToggleKeyboard,
        )
        SmallActionButton(
            label = "Rotate (now ${rotation}°)",
            icon = ProductIcon.RotateCcw,
            enabled = controller,
            tag = "android-rotate",
            onClick = onRotate,
        )
        SmallActionButton(
            label = "More keys",
            icon = ProductIcon.MoreHorizontal,
            primary = extrasOpen,
            enabled = controller,
            tag = "android-more-keys",
            onClick = { extrasOpen = !extrasOpen },
        )
        if (extrasOpen) {
            SmallActionButton(
                label = "Volume up",
                enabled = controller,
                tag = "android-hw-volume-up",
                onClick = { onHardwareKey(AndroidHardwareKey.VOLUME_UP) },
            )
            SmallActionButton(
                label = "Volume down",
                enabled = controller,
                tag = "android-hw-volume-down",
                onClick = { onHardwareKey(AndroidHardwareKey.VOLUME_DOWN) },
            )
            SmallActionButton(
                label = "Power",
                enabled = controller,
                tag = "android-hw-power",
                onClick = { onHardwareKey(AndroidHardwareKey.POWER) },
            )
        }

        Spacer(Modifier.width(8.dp))

        // Quality select (`android-controls.tsx:100-113`)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SmallActionButton(
                label = quality.label,
                enabled = controller,
                tag = "android-quality-select",
                onClick = { qualityMenuOpen = !qualityMenuOpen },
            )
            if (qualityMenuOpen) {
                for (q in AndroidQuality.entries) {
                    SmallActionButton(
                        label = q.label,
                        primary = quality == q,
                        enabled = controller,
                        tag = "android-quality-${q.wire}",
                        onClick = {
                            qualityMenuOpen = false
                            onQualityChange(q)
                        },
                    )
                }
            }
        }
    }
}

// =============================================================================
// 4. Install Panel (`android-install-panel.tsx`)
// =============================================================================

private sealed interface InstallPhase {
    data object Idle : InstallPhase
    data class Uploading(val fraction: Float, val filename: String) : InstallPhase
    data class Installing(val operationId: String, val detail: String, val filename: String) : InstallPhase
    data class Done(val filename: String) : InstallPhase
    data class Failed(val message: String) : InstallPhase
}

@Composable
internal fun AndroidInstallPanel(
    api: ApiClient,
    deviceId: String,
    deviceName: String,
    projects: List<ProjectInfo>,
    initialProject: String?,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedProject by remember { mutableStateOf(initialProject.orEmpty()) }
    var apks by remember { mutableStateOf<List<ProjectApkItem>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var allowDowngrade by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<InstallPhase>(InstallPhase.Idle) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val busy = phase is InstallPhase.Uploading || phase is InstallPhase.Installing

    suspend fun watchOperation(operationId: String, filename: String) {
        while (true) {
            delay(INSTALL_POLL_MS)
            val current = phase
            if (current !is InstallPhase.Installing || current.operationId != operationId) return
            val op = try {
                parseAndroidOperation(api.androidOperationStatus(operationId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                phase = InstallPhase.Failed(e.message ?: "the install failed")
                return
            }
            if (op == null) {
                phase = InstallPhase.Failed("the install failed")
                return
            }
            when (op.state) {
                "succeeded" -> {
                    phase = InstallPhase.Done(filename)
                    return
                }
                "failed" -> {
                    phase = InstallPhase.Failed(op.error ?: "the install failed")
                    return
                }
                "cancelled" -> {
                    phase = InstallPhase.Idle
                    return
                }
                else -> {
                    phase = InstallPhase.Installing(operationId, op.detail ?: "installing…", filename)
                }
            }
        }
    }

    suspend fun scanProject(name: String) {
        if (name.isBlank()) {
            apks = null
            return
        }
        scanning = true
        try {
            apks = parseProjectApks(api.androidProjectApks(name))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            apks = emptyList()
        } finally {
            scanning = false
        }
    }

    LaunchedEffect(selectedProject) {
        scanProject(selectedProject)
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, size) = queryUriMetadata(context, uri)
        activeJob?.cancel()
        activeJob = scope.launch {
            phase = InstallPhase.Uploading(0f, name)
            try {
                val res = api.androidUploadApk(
                    deviceId = deviceId,
                    fileName = name,
                    contentLength = size,
                    allowDowngrade = allowDowngrade,
                    openStream = {
                        context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Cannot open APK file")
                    },
                    onProgress = { loaded, total ->
                        val frac = if (total > 0L) (loaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                        phase = InstallPhase.Uploading(frac, name)
                    },
                )
                val op = parseAndroidOperation(res)
                val opId = op?.id
                if (!opId.isNullOrBlank()) {
                    phase = InstallPhase.Installing(opId, "installing…", name)
                    watchOperation(opId, name)
                } else {
                    phase = InstallPhase.Done(name)
                }
            } catch (_: CancellationException) {
                phase = InstallPhase.Idle
            } catch (e: Throwable) {
                phase = InstallPhase.Failed(e.message ?: "APK upload failed")
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(
            "Installing on $deviceName.",
            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallActionButton(
                label = "Choose an APK",
                icon = ProductIcon.Upload,
                primary = true,
                enabled = !busy,
                tag = "android-choose-apk",
                onClick = { fileLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy) { allowDowngrade = !allowDowngrade }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("android-allow-downgrade"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PpmIcon(
                    if (allowDowngrade) ProductIcon.Check else ProductIcon.Square,
                    14.dp,
                    if (allowDowngrade) tokens.accent.color else tokens.text3.color,
                )
                BasicText(
                    "Allow downgrade",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
        }

        when (val p = phase) {
            is InstallPhase.Idle -> {}
            is InstallPhase.Uploading -> {
                val pct = (p.fraction * 100).roundToInt()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(p.filename, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
                        BasicText("Uploading — $pct%", style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color))
                    }
                    SmallActionButton(
                        label = "Cancel",
                        danger = true,
                        tag = "android-install-cancel",
                        onClick = {
                            activeJob?.cancel()
                            phase = InstallPhase.Idle
                        },
                    )
                }
            }
            is InstallPhase.Installing -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText(p.filename, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
                        BasicText(p.detail, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color))
                    }
                    SmallActionButton(
                        label = "Cancel",
                        danger = true,
                        tag = "android-install-cancel",
                        onClick = {
                            val opId = p.operationId
                            activeJob?.cancel()
                            phase = InstallPhase.Idle
                            if (opId.isNotBlank()) {
                                scope.launch {
                                    runCatching { api.androidCancelOperation(opId) }
                                }
                            }
                        },
                    )
                }
            }
            is InstallPhase.Done -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.success.color.copy(alpha = 0.12f))
                        .padding(8.dp)
                        .testTag("android-install-done"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        "${p.filename} installed",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.success.color),
                    )
                    SmallActionButton(
                        label = "Dismiss",
                        tag = "android-install-dismiss",
                        onClick = { phase = InstallPhase.Idle },
                    )
                }
            }
            is InstallPhase.Failed -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.error.color.copy(alpha = 0.12f))
                        .padding(8.dp)
                        .testTag("android-install-failed"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BasicText(
                        p.message,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                        modifier = Modifier.weight(1f),
                    )
                    SmallActionButton(
                        label = "Dismiss",
                        tag = "android-install-dismiss",
                        onClick = { phase = InstallPhase.Idle },
                    )
                }
            }
        }

        // From a project section (`android-install-panel.tsx:170-220`)
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    "From a project",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
                )
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (proj in projects) {
                        SmallActionButton(
                            label = proj.name,
                            primary = selectedProject == proj.name,
                            tag = "android-apk-project-${proj.name}",
                            onClick = { selectedProject = proj.name },
                        )
                    }
                }
                if (selectedProject.isNotBlank()) {
                    SmallActionButton(
                        label = "Rescan",
                        icon = ProductIcon.RefreshCw,
                        enabled = !scanning,
                        tag = "android-apk-rescan",
                        onClick = { scope.launch { scanProject(selectedProject) } },
                    )
                }
            }

            if (scanning) {
                BasicText("Looking for APKs…", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            } else if (apks != null && apks.orEmpty().isEmpty()) {
                BasicText(
                    "No .apk files in that project yet — build one and press rescan.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            } else {
                for (apk in apks.orEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.panel2.color)
                            .border(1.dp, tokens.border.color, RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy) {
                                val fname = apk.fileName
                                activeJob?.cancel()
                                activeJob = scope.launch {
                                    phase = InstallPhase.Installing("", "starting…", fname)
                                    try {
                                        val res = api.androidInstallProjectApk(
                                            deviceId = deviceId,
                                            project = selectedProject,
                                            path = apk.path,
                                            downgrade = allowDowngrade,
                                        )
                                        val op = parseAndroidOperation(res)
                                        val opId = op?.id
                                        if (!opId.isNullOrBlank()) {
                                            phase = InstallPhase.Installing(opId, "installing…", fname)
                                            watchOperation(opId, fname)
                                        } else {
                                            phase = InstallPhase.Done(fname)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        phase = InstallPhase.Failed(e.message ?: "the install failed")
                                    }
                                }
                            }
                            .padding(8.dp)
                            .testTag("android-project-apk-${apk.path}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                apk.path,
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f, kind = FontKind.MONO).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                formatApkBytes(apk.bytes),
                                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// 5. Logcat Panel (`android-logcat-panel.tsx`)
// =============================================================================

@Composable
internal fun AndroidLogcatPanel(
    entries: List<AndroidLogEntry>,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val context = LocalContext.current
    var filter by remember { mutableStateOf<AndroidLogFilter>(EMPTY_ANDROID_LOG_FILTER) }
    var following by remember { mutableStateOf(true) }
    var levelMenuOpen by remember { mutableStateOf(false) }

    val visible by remember(entries, filter) {
        derivedStateOf {
            val matched = filterAndroidLogs(entries, filter)
            if (matched.size > ANDROID_LOGCAT_MAX_ROWS) {
                matched.subList(matched.size - ANDROID_LOGCAT_MAX_ROWS, matched.size)
            } else {
                matched
            }
        }
    }

    val listState = rememberLazyListState()
    val lastVisibleId = visible.lastOrNull()?.id

    // Follow scroll keyed on the newest visible entry's monotonic `id` (never `visible.size`,
    // which stops changing once the ring / row cap is full).
    LaunchedEffect(lastVisibleId, following) {
        if (following && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
    }

    // Suspend following when the reader scrolls up
    LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward) {
        if (listState.isScrollInProgress && listState.canScrollForward) {
            following = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.weight(1f)) {
                DialogTextInput(
                    value = filter.text,
                    onValueChange = { filter = filter.copy(text = it) },
                    placeholder = "Filter tag or message",
                    tag = "android-log-search",
                )
            }
            SmallActionButton(
                label = filter.minimum.displayLabel,
                tag = "android-log-level-select",
                onClick = { levelMenuOpen = !levelMenuOpen },
            )
            if (levelMenuOpen) {
                for (lvl in AndroidLogLevel.entries) {
                    SmallActionButton(
                        label = lvl.displayLabel,
                        primary = filter.minimum == lvl,
                        tag = "android-log-level-${lvl.wire}",
                        onClick = {
                            levelMenuOpen = false
                            filter = filter.copy(minimum = lvl)
                        },
                    )
                }
            }
            SmallActionButton(
                label = if (paused) "Resume" else "Pause the view",
                primary = paused,
                tag = "android-log-pause",
                onClick = onTogglePause,
            )
            SmallActionButton(
                label = "Clear the view",
                tag = "android-log-clear",
                onClick = onClear,
            )
            SmallActionButton(
                label = "Copy what is shown",
                tag = "android-log-copy",
                onClick = {
                    val text = formatAndroidLogText(visible)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Android Logcat", text))
                },
            )
        }

        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BasicText(
                    if (entries.isEmpty()) "Waiting for the device to log something…" else "Nothing matches that filter.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("android-logcat-list"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    items = visible,
                    key = { it.id },
                ) { entry ->
                    val lvlColor = when (entry.level) {
                        AndroidLogLevel.ERROR, AndroidLogLevel.FATAL -> tokens.error.color
                        AndroidLogLevel.WARN -> tokens.warning.color
                        AndroidLogLevel.INFO -> tokens.text.color
                        else -> tokens.text3.color
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicText(
                            formatAndroidLogTime(entry.timestamp),
                            style = ppmText(11f, lineHeight = 15f, kind = FontKind.MONO).copy(color = tokens.text3.color),
                        )
                        BasicText(
                            entry.level.shortLabel,
                            style = ppmText(11f, FontWeight.Bold, lineHeight = 15f, kind = FontKind.MONO).copy(color = lvlColor),
                        )
                        if (entry.tag.isNotBlank()) {
                            BasicText(
                                entry.tag,
                                style = ppmText(11f, lineHeight = 15f, kind = FontKind.MONO).copy(color = tokens.text2.color),
                                modifier = Modifier.clickable { filter = filter.copy(text = entry.tag) },
                            )
                        }
                        BasicText(
                            entry.message,
                            style = ppmText(11f, lineHeight = 15f, kind = FontKind.MONO).copy(color = lvlColor),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(tokens.panel2.color)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicText(
                if (visible.size == entries.size) "${entries.size} lines" else "${visible.size} of ${entries.size} lines",
                style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
            )
            if (paused) {
                BasicText(
                    "paused",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.warning.color),
                )
            }
            Spacer(Modifier.weight(1f))
            if (!following) {
                SmallActionButton(
                    label = "Follow",
                    tag = "android-log-follow",
                    onClick = { following = true },
                )
            }
        }
    }
}

// =============================================================================
// 6. Clipboard Panel (`android-clipboard-panel.tsx`)
// =============================================================================

@Composable
internal fun AndroidClipboardPanel(
    api: ApiClient,
    deviceId: String,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()

    var fromDevice by remember { mutableStateOf<String?>(null) }
    var toDevice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // From the device
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    "From the device",
                    style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
                )
                SmallActionButton(
                    label = "Read",
                    icon = ProductIcon.Clipboard,
                    enabled = busy == null,
                    tag = "android-clipboard-read",
                    onClick = {
                        scope.launch {
                            busy = "read"
                            error = null
                            try {
                                val res = api.androidGetClipboard(deviceId) as? JsonObject
                                fromDevice = (res?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                error = e.message ?: "Failed to read device clipboard"
                            } finally {
                                busy = null
                            }
                        }
                    },
                )
            }
            val readValue = fromDevice
            if (readValue != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.panel2.color)
                        .border(1.dp, tokens.border.color, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                        .testTag("android-clipboard-from-text"),
                ) {
                    BasicText(
                        readValue.ifEmpty { "(the device's clipboard is empty)" },
                        style = ppmText(12f, lineHeight = 16f, kind = FontKind.MONO).copy(
                            color = if (readValue.isEmpty()) tokens.text3.color else tokens.text.color,
                        ),
                    )
                }
            }
        }

        // To the device
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(
                "To the device",
                style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
            )
            DialogTextInput(
                value = toDevice,
                onValueChange = {
                    toDevice = it
                    sent = false
                },
                placeholder = "Text to put on the device's clipboard",
                tag = "android-clipboard-input",
            )
            SmallActionButton(
                label = if (sent) "Sent" else "Send to device",
                icon = if (sent) ProductIcon.Check else ProductIcon.Upload,
                primary = true,
                enabled = busy == null && toDevice.isNotEmpty(),
                tag = "android-clipboard-write",
                onClick = {
                    scope.launch {
                        busy = "write"
                        error = null
                        try {
                            api.androidSetClipboard(deviceId, toDevice)
                            sent = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            error = e.message ?: "Failed to write device clipboard"
                        } finally {
                            busy = null
                        }
                    }
                },
            )
            BasicText(
                "Paste it in the guest with a long press, or with the keyboard's paste key.",
                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
            )
        }

        if (error != null) {
            BasicText(
                error.orEmpty(),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
            )
        }
    }
}

// =============================================================================
// 7. Shared UI primitives & helpers
// =============================================================================

@Composable
private fun SmallActionButton(
    label: String,
    icon: ProductIcon? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val bg: Color = when {
        !enabled -> tokens.panel2.color.copy(alpha = 0.5f)
        danger -> tokens.error.color.copy(alpha = 0.16f)
        primary -> tokens.accent.color
        else -> tokens.panel2.color
    }
    val fg: Color = when {
        !enabled -> tokens.text3.color.copy(alpha = 0.5f)
        danger -> tokens.error.color
        primary -> Color.White
        else -> tokens.text.color
    }
    val borderColor: Color = when {
        danger -> tokens.error.color.copy(alpha = 0.4f)
        primary -> tokens.accent.color
        else -> tokens.border.color
    }
    Row(
        Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            PpmIcon(icon, 13.dp, fg)
        }
        BasicText(
            label,
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = fg),
            maxLines = 1,
        )
    }
}

@Composable
private fun DialogTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
    tag: String? = null,
) {
    val tokens = LocalPpmTokens.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
        cursorBrush = SolidColor(tokens.accent.color),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(tokens.panel2.color)
            .border(1.dp, tokens.border.color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    BasicText(placeholder, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
                }
                inner()
            }
        },
    )
}

private fun formatApkBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun queryUriMetadata(context: Context, uri: Uri): Pair<String, Long> {
    var name = "upload.apk"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) {
                    val n = cursor.getString(nameIdx)
                    if (!n.isNullOrBlank()) name = n
                }
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
    }
    return name to size
}

internal suspend fun savePngToDevice(
    context: Context,
    fileName: String,
    bytes: ByteArray,
): String = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused $fileName")
        resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "Saved $fileName to Downloads"
    } else {
        val dir = File(context.cacheDir, "android_screenshots").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        "Prepared $fileName in cache"
    }
}
