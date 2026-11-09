package com.hienlh.ppm.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.cloudGetAlias
import com.hienlh.ppm.client.cloudGetLoginUrl
import com.hienlh.ppm.client.cloudGetStatus
import com.hienlh.ppm.client.cloudLink
import com.hienlh.ppm.client.cloudLogin
import com.hienlh.ppm.client.cloudLogout
import com.hienlh.ppm.client.cloudPollDeviceCode
import com.hienlh.ppm.client.cloudRequestDeviceCode
import com.hienlh.ppm.client.cloudSetAlias
import com.hienlh.ppm.client.cloudUnlink
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.tunnelGetStatus
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.chromeScrollbar
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.settings.CloudDeviceCodeFlowState
import com.hienlh.ppm.state.settings.CloudStatusState
import com.hienlh.ppm.state.settings.NamedTunnelState
import com.hienlh.ppm.state.settings.PublicTunnelState
import com.hienlh.ppm.state.settings.parseCloudDeviceCodePoll
import com.hienlh.ppm.state.settings.parseCloudDeviceCodeStart
import com.hienlh.ppm.state.settings.parseCloudLoginUrl
import com.hienlh.ppm.state.settings.parseCloudStatus
import com.hienlh.ppm.state.settings.parseNamedTunnelState
import com.hienlh.ppm.state.settings.parsePublicTunnelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI

private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]{1,31}$")
private const val SLUG_HINT = "2–32 characters: lowercase letters, numbers, hyphens."

/**
 * Cloud & Share popover (`src/web/components/layout/cloud-share-popover.tsx`):
 * desktop popover (`sheet = false`) on the rail's `Cloud` button, or mobile bottom sheet (`sheet = true`).
 */
@Composable
fun CloudSharePopover(
    model: AppModel,
    onClose: () -> Unit,
    sheet: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    var cloud by remember { mutableStateOf<CloudStatusState?>(null) }
    var tunnel by remember { mutableStateOf<PublicTunnelState?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tunnelStarting by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var deviceCodeFlow by remember { mutableStateOf(CloudDeviceCodeFlowState()) }
    var namedLive by remember { mutableStateOf(false) }
    var tempUrl by remember { mutableStateOf<String?>(null) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    suspend fun reloadStatus() {
        val client = api ?: return
        try {
            val cs = parseCloudStatus(client.cloudGetStatus())
            val ts = parsePublicTunnelState(client.tunnelGetStatus())
            cloud = cs
            tunnel = ts
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    LaunchedEffect(api) {
        if (api != null) reloadStatus()
        loading = false
    }

    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2_000)
            copied = null
        }
    }

    // Device-code poll (`cloud-share-popover.tsx:125-160`)
    LaunchedEffect(api, deviceCodeFlow.deviceCode, deviceCodeFlow.status) {
        val client = api ?: return@LaunchedEffect
        if (deviceCodeFlow.status != "waiting" || deviceCodeFlow.deviceCode.isBlank()) return@LaunchedEffect
        val active = deviceCodeFlow
        val intervalMs = (active.intervalSec.coerceAtLeast(1)) * 1_000L
        val deadline = System.currentTimeMillis() + (active.expiresInSec.coerceAtLeast(10)) * 1_000L
        while (deviceCodeFlow.status == "waiting" && deviceCodeFlow.deviceCode == active.deviceCode) {
            delay(intervalMs)
            if (System.currentTimeMillis() > deadline) {
                deviceCodeFlow = CloudDeviceCodeFlowState()
                error = "Login expired. Try again."
                break
            }
            val poll = try {
                parseCloudDeviceCodePoll(client.cloudPollDeviceCode(active.cloudUrl, active.deviceCode))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            } ?: continue
            if (poll.status == "approved" && !poll.accessToken.isNullOrBlank() && !poll.email.isNullOrBlank()) {
                try {
                    client.cloudLogin(accessToken = poll.accessToken!!, email = poll.email!!, cloudUrl = active.cloudUrl)
                    deviceCodeFlow = CloudDeviceCodeFlowState()
                    cloud = parseCloudStatus(client.cloudGetStatus())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    deviceCodeFlow = CloudDeviceCodeFlowState()
                    error = t.message ?: "Failed to save login"
                }
                break
            }
        }
    }

    val publicPort = remember(tunnel?.localUrl) {
        val u = tunnel?.localUrl ?: return@remember null
        runCatching { URI(u).port.takeIf { it > 0 } }.getOrNull()
    }

    fun copyUrl(url: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("PPM URL", url))
        copied = url
    }

    val shareUrl = tunnel?.url?.takeIf { it.isNotBlank() } ?: cloud?.tunnelUrl?.takeIf { it.isNotBlank() }
    val cardShape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()

    val containerModifier = if (sheet) {
        Modifier.fillMaxWidth().padding(12.dp)
    } else {
        Modifier
            .width(288.dp)
            .heightIn(max = 520.dp)
            .then(ShadowLg.fold(Modifier as Modifier) { m, s -> m.ppmShadow(s, cardShape) })
            .chromeScrollbar(scroll, vertical = true, tokens.border.color, border = 1.dp)
            .clip(cardShape)
            .background(tokens.bgSolid.color)
            .cssBorder(tokens.border.color, cardShape)
            .verticalScroll(scroll)
            .padding(12.dp)
    }

    Column(containerModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.Cloud, 16.dp, tokens.accent.color)
                BasicText("PPM Cloud", style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text.color))
            }
            val closeHover = rememberHover()
            Box(
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = RoundedCornerShape(4.dp), onClickLabel = "Close", source = closeHover.source, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 14.dp, tokens.text.mix(tokens.text3, closeHover.lit.toDouble()).color)
            }
        }

        if (loading) {
            Row(
                Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
                BasicText("Loading...", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            }
        } else {
            val cs = cloud
            // Cloud Account Section
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    cs?.loggedIn == true -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(tokens.success.color))
                                BasicText(
                                    cs.email.orEmpty(),
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val outHover = rememberHover()
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = RoundedCornerShape(4.dp), onClickLabel = "Sign out", source = outHover.source) { confirmLogout = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(ProductIcon.LogOut, 12.dp, tokens.text.mix(tokens.text3, outHover.lit.toDouble()).color)
                            }
                        }
                    }
                    deviceCodeFlow.userCode.isNotBlank() -> {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BasicText("Open the link below and enter the code:", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color, textAlign = TextAlign.Center))
                            BasicText(
                                deviceCodeFlow.userCode,
                                style = ppmText(24f, FontWeight.Bold, FontKind.MONO, lineHeight = 32f).copy(color = tokens.accent.color, letterSpacing = 0.3.em),
                            )
                            if (deviceCodeFlow.verificationUri.isNotBlank()) {
                                BasicText(
                                    deviceCodeFlow.verificationUri,
                                    Modifier.ppmClickable(pressedColor = Color.Transparent) {
                                        runCatching { uriHandler.openUri(deviceCodeFlow.verificationUri) }
                                    },
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.accent.color),
                                )
                            }
                            if (deviceCodeFlow.status == "waiting") {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PpmIcon(ProductIcon.Loader2, 12.dp, tokens.text2.color, spinning = true)
                                    BasicText("Waiting for verification...", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                                }
                            }
                        }
                    }
                    else -> {
                        if (shareUrl != null) {
                            BasicText(
                                if (namedLive) "This link is permanent — it lives on your own domain. Sign in to PPM Cloud to keep your devices in sync."
                                else "This link changes every time PPM restarts. Sign in to get a permanent one that always points here — private to your account.",
                                style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.color),
                            )
                        }
                        val btnShape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(btnShape)
                                .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = btnShape) {
                                    val client = api ?: return@ppmClickable
                                    error = null
                                    scope.launch {
                                        try {
                                            val cloudUrl = runCatching { parseCloudLoginUrl(client.cloudGetLoginUrl()) }
                                                .getOrDefault(cs?.cloudUrl?.ifBlank { "https://ppm.bot" } ?: "https://ppm.bot")
                                            deviceCodeFlow = parseCloudDeviceCodeStart(client.cloudRequestDeviceCode(cloudUrl), cloudUrl)
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (t: Throwable) {
                                            error = t.message ?: "Failed to start login"
                                            deviceCodeFlow = CloudDeviceCodeFlowState()
                                        }
                                    }
                                }
                                .cssBorder(tokens.border.color, btnShape)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PpmIcon(ProductIcon.Cloud, 14.dp, tokens.text.color)
                            BasicText(
                                if (shareUrl != null && !namedLive) "Get a permanent link" else "Sign in to PPM Cloud",
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                        }
                    }
                }
            }

            // Device Link Section
            if (cs?.loggedIn == true) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (cs.linked) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PpmIcon(ProductIcon.Link, 12.dp, tokens.accent.color)
                                BasicText(cs.deviceName.orEmpty(), style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val unlinkHover = rememberHover()
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .ppmClickable(enabled = !unlinking, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = RoundedCornerShape(4.dp), onClickLabel = "Unlink device", source = unlinkHover.source) {
                                        confirmUnlink = true
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(
                                    if (unlinking) ProductIcon.Loader2 else ProductIcon.Unlink,
                                    12.dp,
                                    tokens.error.mix(tokens.text3, unlinkHover.lit.toDouble()).color,
                                    spinning = unlinking,
                                )
                            }
                        }
                        CloudShareAliasRow(model = model, cloudUrl = cs.cloudUrl, deviceName = cs.deviceName)
                    } else {
                        val btnShape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (linking) 0.5f else 1f)
                                .clip(btnShape)
                                .background(tokens.accent.color)
                                .ppmClickable(enabled = !linking, pressedColor = tokens.accent.opacity(0.9).color, hoverColor = tokens.accent.opacity(0.9).color, shape = btnShape) {
                                    val client = api ?: return@ppmClickable
                                    linking = true
                                    error = null
                                    scope.launch {
                                        try {
                                            if (tunnel?.active != true) {
                                                val started = client.tunnelStart()
                                                tunnel = PublicTunnelState(
                                                    active = true,
                                                    url = started["url"].string,
                                                    localUrl = tunnel?.localUrl,
                                                    enabled = true,
                                                )
                                            }
                                            client.cloudLink()
                                            cloud = parseCloudStatus(client.cloudGetStatus())
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (t: Throwable) {
                                            error = t.message ?: "Failed to link device"
                                        } finally {
                                            linking = false
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PpmIcon(if (linking) ProductIcon.Loader2 else ProductIcon.Link, 14.dp, tokens.accentFg.color, spinning = linking)
                            BasicText(if (linking) "Linking..." else "Link this machine", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accentFg.color))
                        }
                    }
                }
            }

            // Separator
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border.color))

            // Custom domain row
            CloudShareNamedTunnelRow(
                model = model,
                publicPort = publicPort,
                onNamedLive = { namedLive = it },
                onTempUrl = { tempUrl = it },
            )

            // Local Network URL
            val localUrl = tunnel?.localUrl?.takeIf { it.isNotBlank() }
            if (localUrl != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        "LOCAL NETWORK",
                        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
                    )
                    CloudShareUrlRow(url = localUrl, copied = copied == localUrl, onCopy = { copyUrl(localUrl) })
                }
            }

            // Tunnel / Share Section
            if (shareUrl == null && !tunnelStarting) {
                val btnShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(btnShape)
                        .background(tokens.accent.color)
                        .ppmClickable(pressedColor = tokens.accent.opacity(0.9).color, hoverColor = tokens.accent.opacity(0.9).color, shape = btnShape) {
                            val client = api ?: return@ppmClickable
                            tunnelStarting = true
                            error = null
                            scope.launch {
                                try {
                                    val res = client.tunnelStart()
                                    tunnel = PublicTunnelState(
                                        active = true,
                                        url = res["url"].string,
                                        localUrl = tunnel?.localUrl,
                                        enabled = true,
                                    )
                                    cloud = parseCloudStatus(client.cloudGetStatus())
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    error = t.message ?: "Failed to start tunnel"
                                } finally {
                                    tunnelStarting = false
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.Share2, 14.dp, tokens.accentFg.color)
                    BasicText("Start Sharing", style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accentFg.color))
                }
            }

            if (tunnelStarting) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PpmIcon(ProductIcon.Loader2, 20.dp, tokens.accent.color, spinning = true)
                    BasicText("Starting tunnel...", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                }
            }

            if (shareUrl != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(
                        "PUBLIC URL",
                        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrCodeBox(value = shareUrl, size = 180.dp)
                    }
                    CloudShareUrlRow(url = shareUrl, copied = copied == shareUrl, onCopy = { copyUrl(shareUrl) })
                }
            }

            val extraTemp = tempUrl
            if (!extraTemp.isNullOrBlank() && extraTemp != shareUrl) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(
                        "TEMPORARY LINK",
                        style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
                    )
                    CloudShareUrlRow(url = extraTemp, copied = copied == extraTemp, onCopy = { copyUrl(extraTemp) })
                    BasicText(
                        "Stops when you press Stop temp link, or when PPM restarts.",
                        style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.color),
                    )
                }
            }

            if (cs?.loggedIn == true && cs.linked) {
                val btnShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(btnShape)
                        .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = btnShape) {
                            runCatching { uriHandler.openUri(cs.cloudUrl.trimEnd('/') + "/dashboard") }
                        }
                        .cssBorder(tokens.border.color, btnShape)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PpmIcon(ProductIcon.ExternalLink, 12.dp, tokens.text2.color)
                    BasicText("Open Cloud Dashboard", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                }
            }

            if (error != null) {
                BasicText(error!!, style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color))
            }
        }
    }

    if (confirmUnlink) {
        ConfirmDialog(
            title = "Unlink device?",
            message = "Unlink this device from PPM Cloud? It will no longer appear on your cloud dashboard.",
            confirm = "Unlink",
            onConfirm = {
                confirmUnlink = false
                val client = api ?: return@ConfirmDialog
                unlinking = true
                scope.launch {
                    try {
                        client.cloudUnlink()
                        cloud = parseCloudStatus(client.cloudGetStatus())
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    } finally {
                        unlinking = false
                    }
                }
            },
            onDismiss = { confirmUnlink = false },
        )
    }

    if (confirmLogout) {
        val wasLinked = cloud?.linked == true
        ConfirmDialog(
            title = "Sign out?",
            message = if (wasLinked) "Sign out from PPM Cloud? This will also unlink your device." else "Sign out from PPM Cloud?",
            confirm = "Sign out",
            onConfirm = {
                confirmLogout = false
                val client = api ?: return@ConfirmDialog
                scope.launch {
                    try {
                        if (wasLinked) runCatching { client.cloudUnlink() }
                        client.cloudLogout()
                        cloud = cloud?.copy(loggedIn = false, email = null, linked = false, deviceName = null, deviceId = null)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun CloudShareUrlRow(url: String, copied: Boolean, onCopy: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val boxShape = RoundedCornerShape(4.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .weight(1f)
                .clip(boxShape)
                .background(tokens.panel2.color)
                .ppmClickable(pressedColor = tokens.panel2.color, shape = boxShape, onClick = onCopy)
                .cssBorder(tokens.border.color, boxShape)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            BasicText(
                url,
                style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val copyHover = rememberHover()
        Box(
            Modifier
                .size(28.dp)
                .clip(boxShape)
                .background(tokens.panel2.color)
                .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = boxShape, onClickLabel = "Copy URL", source = copyHover.source, onClick = onCopy)
                .cssBorder(tokens.border.color, boxShape),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(
                if (copied) ProductIcon.Check else ProductIcon.Copy,
                14.dp,
                if (copied) tokens.accent.color else tokens.text.mix(tokens.text2, copyHover.lit.toDouble()).color,
            )
        }
    }
}

@Composable
private fun CloudShareAliasRow(model: AppModel, cloudUrl: String, deviceName: String?) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val api = model.api
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var slug by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var unreadable by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val draft = remember { TextFieldState() }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val host = remember(cloudUrl) { cloudUrl.replace(Regex("^https?://"), "").trimEnd('/') }

    LaunchedEffect(api) {
        val client = api
        if (client == null) {
            loading = false
            return@LaunchedEffect
        }
        try {
            val res = client.cloudGetAlias()
            slug = res["slug"].string ?: res.obj?.get("alias")?.obj?.get("slug").string
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            unreadable = true
        }
        loading = false
    }

    if (loading) return
    if (unreadable) {
        BasicText(
            "Couldn't check this machine's permanent link. Open the cloud dashboard, or sign in again.",
            style = ppmText(11f, lineHeight = 16.5f).copy(color = tokens.text2.color),
        )
        return
    }

    fun startEdit() {
        val suggested = slug ?: deviceName.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).takeIf { SLUG_REGEX.matches(it) }.orEmpty()
        draft.edit { replace(0, length, suggested) }
        error = null
        editing = true
    }

    fun saveEdit() {
        val next = draft.text.toString().trim().lowercase()
        if (!SLUG_REGEX.matches(next)) {
            error = SLUG_HINT
            return
        }
        val client = api ?: return
        saving = true
        error = null
        scope.launch {
            try {
                val res = client.cloudSetAlias(next)
                slug = res["slug"].string ?: next
                editing = false
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = t.message ?: "Could not save that name"
            } finally {
                saving = false
            }
        }
    }

    if (editing) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText("PERMANENT LINK", style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color, letterSpacing = 0.025.em))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("$host/", style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color))
                val inShape = RoundedCornerShape(4.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .clip(inShape)
                        .background(tokens.panel2.color)
                        .cssBorder(tokens.border.color, inShape)
                        .padding(horizontal = 8.dp, vertical = if (mobile) 10.dp else 6.dp),
                ) {
                    PpmTextField(
                        state = draft,
                        placeholder = "my-mac",
                        modifier = Modifier.fillMaxWidth(),
                        style = ppmText(16f, kind = FontKind.MONO, lineHeight = 22f),
                        desktopStyle = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f),
                    )
                }
                val btnSize = if (mobile) 44.dp else 32.dp
                Box(
                    Modifier
                        .size(btnSize)
                        .alpha(if (saving) 0.5f else 1f)
                        .clip(inShape)
                        .background(tokens.panel2.color)
                        .ppmClickable(enabled = !saving, pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = inShape, onClick = ::saveEdit)
                        .cssBorder(tokens.border.color, inShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(if (saving) ProductIcon.Loader2 else ProductIcon.Check, 14.dp, tokens.accent.color, spinning = saving)
                }
                Box(
                    Modifier
                        .size(btnSize)
                        .clip(inShape)
                        .background(tokens.panel2.color)
                        .ppmClickable(pressedColor = tokens.surfaceHover.color, hoverColor = tokens.surfaceHover.color, shape = inShape) { editing = false }
                        .cssBorder(tokens.border.color, inShape),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 14.dp, tokens.text2.color)
                }
            }
            if (error != null) {
                BasicText(error!!, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color))
            }
        }
    } else if (!slug.isNullOrBlank()) {
        val fullUrl = "${cloudUrl.trimEnd('/')}/$slug"
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("PERMANENT LINK", style = ppmText(10f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text2.color, letterSpacing = 0.025.em))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText(
                    "$host/$slug",
                    Modifier.weight(1f).ppmClickable(pressedColor = Color.Transparent) { runCatching { uriHandler.openUri(fullUrl) } },
                    style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.accent.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = RoundedCornerShape(4.dp), onClick = ::startEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.Pencil, 12.dp, tokens.text3.color)
                }
            }
        }
    } else {
        BasicText(
            "Choose a permanent link name…",
            Modifier.ppmClickable(pressedColor = Color.Transparent, onClick = ::startEdit),
            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.accent.color),
        )
    }
}

@Composable
private fun CloudShareNamedTunnelRow(
    model: AppModel,
    publicPort: Int?,
    onNamedLive: (Boolean) -> Unit,
    onTempUrl: (String?) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val api = model.api
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<NamedTunnelState?>(null) }
    var tempPid by remember { mutableStateOf<Int?>(null) }
    var tempUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val client = api ?: return
        try {
            val s = parseNamedTunnelState(client.namedTunnelStatus())
            status = s
            val live = s.liveMode.ifBlank { s.mode } == "named"
            onNamedLive(live)
            val namedUrl = s.hostname?.takeIf { it.isNotBlank() }?.let { "https://$it" }
            val list = runCatching { client.tunnelsList().array.orEmpty() }.getOrDefault(emptyList())
            val found = list.firstOrNull { entry ->
                entry["source"].string == "ppm" &&
                    entry["protected"].bool != true &&
                    entry["port"].int == publicPort &&
                    !entry["url"].string.isNullOrBlank() &&
                    entry["url"].string != namedUrl
            }
            tempPid = found?.get("pid")?.int
            tempUrl = found?.get("url")?.string
            onTempUrl(tempUrl)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
        }
    }

    LaunchedEffect(api, publicPort) {
        load()
    }

    val s = status ?: return
    if (!s.authEnabled || s.hostname.isNullOrBlank()) return
    if (s.liveMode.ifBlank { s.mode } != "named") return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PpmIcon(ProductIcon.Globe, 12.dp, tokens.accent.color)
                BasicText(
                    "Your domain · ${s.hostname}",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val btnShape = RoundedCornerShape(6.dp)
            val hasTemp = !tempUrl.isNullOrBlank()
            val canAct = busy == null && publicPort != null
            Row(
                Modifier
                    .alpha(if (canAct) 1f else 0.5f)
                    .clip(btnShape)
                    .ppmClickable(enabled = canAct, pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color, shape = btnShape) {
                        val client = api ?: return@ppmClickable
                        error = null
                        if (hasTemp) {
                            val pid = tempPid ?: return@ppmClickable
                            busy = "stop"
                            scope.launch {
                                try {
                                    client.tunnelStop(pid)
                                    tempPid = null
                                    tempUrl = null
                                    onTempUrl(null)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    error = t.message ?: "Could not stop the temporary link"
                                } finally {
                                    busy = null
                                }
                            }
                        } else {
                            val port = publicPort ?: return@ppmClickable
                            busy = "add"
                            scope.launch {
                                try {
                                    val res = client.tunnelStart(port = port)
                                    val url = res["url"].string
                                    tempUrl = url
                                    onTempUrl(url)
                                    load()
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    error = t.message ?: "Could not create a temporary link"
                                } finally {
                                    busy = null
                                }
                            }
                        }
                    }
                    .cssBorder(tokens.border.color, btnShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    busy != null -> PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text.color, spinning = true)
                    hasTemp -> {
                        PpmIcon(ProductIcon.Square, 12.dp, tokens.text.color)
                        BasicText("Stop temp link", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color))
                    }
                    else -> {
                        PpmIcon(ProductIcon.Plus, 12.dp, tokens.text.color)
                        BasicText("Temp link", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color))
                    }
                }
            }
        }
        if (!s.tunnelWarning.isNullOrBlank()) {
            BasicText(s.tunnelWarning!!, style = ppmText(11f, lineHeight = 16.5f).copy(color = Rgba(0.96, 0.62, 0.04, 1.0).color))
        }
        if (error != null) {
            BasicText(error!!, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color))
        }
    }
}
