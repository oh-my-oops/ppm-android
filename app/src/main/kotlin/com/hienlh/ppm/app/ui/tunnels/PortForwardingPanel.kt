package com.hienlh.ppm.app.ui.tunnels

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.ConfirmDialog
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.phone.OpenOnTabletNotice
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.client.tunnelsTransports
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
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.tunnels.NamedTunnelStatus
import com.hienlh.ppm.state.tunnels.SetupState
import com.hienlh.ppm.state.tunnels.TailscaleAvailability
import com.hienlh.ppm.state.tunnels.TunnelEntry
import com.hienlh.ppm.state.tunnels.TunnelsModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortForwardingPanel(
    model: AppModel,
    initialTunnels: List<TunnelEntry>? = null,
    initialTailscale: TailscaleAvailability? = null,
    initialCloudflare: NamedTunnelStatus? = null,
    onNavigate: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tunnels by remember { mutableStateOf(initialTunnels.orEmpty()) }
    var loading by remember { mutableStateOf(initialTunnels == null) }
    val portInput = remember { TextFieldState("") }
    var via by remember { mutableStateOf(if (initialTailscale?.available == true) "tailscale" else "cloudflare") }
    var tailscale by remember { mutableStateOf(initialTailscale) }
    var cloudflare by remember { mutableStateOf(initialCloudflare) }
    var cloudflareError by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stoppingPid by remember { mutableStateOf<Int?>(null) }
    var copiedPid by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf<TunnelEntry?>(null) }

    LaunchedEffect(portInput.text) {
        error = null
    }

    suspend fun fetchTunnels(force: Boolean = false) {
        val api = model.api ?: run {
            loading = false
            return
        }
        try {
            val data = api.tunnelsList(force)
            tunnels = TunnelsModel.parseList(data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the last known list: a failed poll is not shown as an inline error (port-forwarding-panel.tsx:85-87).
        } finally {
            loading = false
        }
    }

    suspend fun fetchSetup() {
        val api = model.api ?: return
        val tsJob = scope.launch {
            try {
                val res = api.tunnelsTransports()
                val ts = TailscaleAvailability.from(res)
                tailscale = ts
                if (ts.available && initialTailscale == null) {
                    via = "tailscale"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (tailscale == null) {
                    tailscale = TailscaleAvailability(available = false, reason = "Could not check Tailscale on the host")
                }
            }
        }
        val cfJob = scope.launch {
            try {
                val res = api.namedTunnelStatus()
                cloudflare = NamedTunnelStatus.from(res)
                cloudflareError = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (cloudflare == null) cloudflareError = true
            }
        }
        tsJob.join()
        cfJob.join()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, lifecycle) {
        if (model.api == null) {
            loading = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (initialTunnels == null) {
                fetchTunnels()
                fetchSetup()
            }
            while (isActive) {
                delay(10_000L)
                if (model.api != null) {
                    fetchTunnels()
                    fetchSetup()
                }
            }
        }
    }

    fun handleStart() {
        val portNum = portInput.text.toString().trim().toIntOrNull()
        if (portNum == null || portNum < 1 || portNum > 65535) {
            error = "The port must be a number from 1 to 65535."
            return
        }
        val api = model.api ?: return
        starting = true
        error = null
        scope.launch {
            try {
                val result = api.tunnelStart(portNum, via)
                portInput.edit { replace(0, length, "") }
                fetchTunnels()
                val url = result?.get("url").string
                val viaResult = result?.get("via").string ?: via
                val p = result?.get("port").int ?: portNum
                if (!url.isNullOrBlank()) {
                    model.openWebPreviewTab(url = url, port = p, via = viaResult)
                    onNavigate()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "Could not forward port $portNum"
            } finally {
                starting = false
            }
        }
    }

    fun handleStop(pid: Int) {
        val api = model.api ?: return
        stoppingPid = pid
        confirmStop = null
        scope.launch {
            try {
                api.tunnelStop(pid)
                fetchTunnels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.toast(ErrorToastMessage("Could not stop the forward (pid $pid)", e.message))
            } finally {
                stoppingPid = null
            }
        }
    }

    fun copyUrl(pid: Int, url: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Forwarded URL", url))
        copiedPid = pid
        scope.launch {
            delay(1500L)
            if (copiedPid == pid) copiedPid = null
        }
    }

    fun openExternalBrowser(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    val tsSetup = TunnelsModel.tailscaleSetup(tailscale)
    val cfSetup = TunnelsModel.cloudflareSetup(cloudflare, cloudflareError)
    val controlHeight = if (mobile) 44.dp else 32.dp
    val iconBtnSize = if (mobile) 44.dp else 32.dp

    Column(
        Modifier
            .fillMaxSize()
            .testTag("port-forwarding-panel"),
    ) {
        // Header (matches SidebarHeader icon={Globe} title="Port Forwarding")
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Globe, 16.dp, tokens.accent.color)
            BasicText(
                "PORT FORWARDING",
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(
                    color = tokens.text2.color,
                    letterSpacing = 0.3.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .size(if (mobile) 44.dp else 24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(pressedColor = tokens.panel2.color, hoverColor = tokens.panel2.color) {
                        scope.launch {
                            fetchTunnels(force = true)
                            fetchSetup()
                        }
                    }
                    .testTag("tunnels-refresh"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.RefreshCw, 14.dp, tokens.text3.color, spinning = loading)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Setup status rows
            Column(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SetupRow(
                    name = "Tailscale",
                    setup = tsSetup,
                    mobile = mobile,
                    onOpen = {
                        model.openSettings("remote-access")
                        onNavigate()
                    },
                )
                SetupRow(
                    name = "Cloudflare",
                    setup = cfSetup,
                    mobile = mobile,
                    onOpen = {
                        model.openSettings("remote-access")
                        onNavigate()
                    },
                )
                if (mobile && (!tsSetup.done || !cfSetup.done)) {
                    OpenOnTabletNotice(
                        text = "Set up Tailscale or Cloudflare on a tablet.",
                        modifier = Modifier.testTag("tunnels-phone-tablet-notice"),
                    )
                }
            }

            // Forward form
            Column(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    "Port on this machine",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )

                val tsAvailable = tailscale?.available == true
                val canStart = !starting && portInput.text.isNotBlank() && (via != "tailscale" || tsAvailable)
                val inputShape = RoundedCornerShape(6.dp)
                val btnShape = RoundedCornerShape(6.dp)

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .height(controlHeight)
                            .clip(inputShape)
                            .background(tokens.bgSolid.color)
                            .cssBorder(tokens.border.color, inputShape)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            "localhost:",
                            style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                        )
                        Box(
                            Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            PpmTextField(
                                state = portInput,
                                placeholder = "3000",
                                modifier = Modifier.fillMaxWidth().testTag("tunnel-port-input"),
                                style = ppmText(14f, lineHeight = 20f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                onKeyboardAction = { handleStart() },
                            )
                        }
                    }

                    Box(
                        Modifier
                            .height(controlHeight)
                            .alpha(if (canStart) 1f else 0.5f)
                            .clip(btnShape)
                            .background(tokens.accent.color)
                            .ppmClickable(enabled = canStart, shape = btnShape, onClick = ::handleStart)
                            .padding(horizontal = 12.dp)
                            .testTag("tunnel-forward-btn"),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (starting) {
                            PpmIcon(ProductIcon.Loader2, 16.dp, tokens.accentFg.color, spinning = true)
                        } else {
                            BasicText(
                                "Forward",
                                style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.accentFg.color),
                            )
                        }
                    }
                }

                // 2-column transport radio grid
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransportOptionButton(
                        label = "Tailscale",
                        selected = via == "tailscale",
                        enabled = !starting && tsAvailable,
                        mobile = mobile,
                        modifier = Modifier.weight(1f),
                        onClick = { if (tsAvailable) via = "tailscale" },
                    )
                    TransportOptionButton(
                        label = "Cloudflare",
                        selected = via == "cloudflare",
                        enabled = !starting,
                        mobile = mobile,
                        modifier = Modifier.weight(1f),
                        onClick = { via = "cloudflare" },
                    )
                }

                BasicText(
                    if (via == "tailscale") {
                        "Private: only devices in your tailnet can open it."
                    } else {
                        "Public: anyone with the link can open it."
                    },
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )

                if (error != null) {
                    BasicText(
                        error!!,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                    )
                }
            }

            // Forwarded list section
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText(
                    "Forwarded",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
                )

                if (loading && tunnels.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text2.color, spinning = true)
                    }
                } else if (tunnels.isEmpty()) {
                    val emptyShape = RoundedCornerShape(8.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(emptyShape)
                            .cssBorder(tokens.border.color, emptyShape)
                            .padding(horizontal = 12.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BasicText(
                            "Nothing is forwarded yet.",
                            style = ppmText(if (mobile) 14f else 12f, lineHeight = 18f).copy(
                                color = tokens.text2.color,
                                textAlign = TextAlign.Center,
                            ),
                        )
                        BasicText(
                            "A cloudflared tunnel started outside PPM shows up here too.",
                            style = ppmText(12f, lineHeight = 16f).copy(
                                color = tokens.text2.color,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (t in tunnels) {
                            val cardShape = RoundedCornerShape(8.dp)
                            val isPrivate = t.via == "tailscale"
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(cardShape)
                                    .background(tokens.bgSolid.color)
                                    .cssBorder(tokens.border.color, cardShape)
                                    .padding(10.dp)
                                    .testTag("tunnel-row-${t.pid}"),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FlowRow(
                                        Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        itemVerticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        BasicText(
                                            if (t.port != null) ":${t.port}" else "—",
                                            style = ppmText(14f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 20f).copy(color = tokens.text.color),
                                        )
                                        TunnelChip(
                                            label = if (isPrivate) "TAILNET" else "PUBLIC",
                                            bg = if (isPrivate) tokens.accent.opacity(0.1).color else tokens.panel2.color,
                                            fg = if (isPrivate) tokens.accent.color else tokens.text2.color,
                                        )
                                        if (t.source == "external") {
                                            TunnelChip(
                                                label = "STARTED OUTSIDE PPM",
                                                bg = tokens.panel2.color,
                                                fg = tokens.text2.color,
                                            )
                                        }
                                    }

                                    if (t.protected) {
                                        Row(
                                            Modifier
                                                .heightIn(min = controlHeight)
                                                .clip(RoundedCornerShape(6.dp))
                                                .ppmClickable(pressedColor = tokens.surfaceHover.color) {
                                                    model.openSettings("remote-access")
                                                    onNavigate()
                                                }
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            PpmIcon(ProductIcon.Lock, 14.dp, tokens.text2.color)
                                            BasicText("Public link", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                                        }
                                    } else {
                                        Box(
                                            Modifier
                                                .size(iconBtnSize)
                                                .clip(RoundedCornerShape(6.dp))
                                                .ppmClickable(
                                                    enabled = stoppingPid != t.pid,
                                                    pressedColor = tokens.error.opacity(0.15).color,
                                                    hoverColor = tokens.error.opacity(0.1).color,
                                                ) {
                                                    if (t.source == "external") {
                                                        confirmStop = t
                                                    } else {
                                                        handleStop(t.pid)
                                                    }
                                                }
                                                .testTag("tunnel-stop-${t.pid}"),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            PpmIcon(
                                                if (stoppingPid == t.pid) ProductIcon.Loader2 else ProductIcon.Square,
                                                16.dp,
                                                tokens.text2.color,
                                                spinning = stoppingPid == t.pid,
                                            )
                                        }
                                    }
                                }

                                val hasUrl = !t.url.isNullOrBlank()
                                val urlShape = RoundedCornerShape(6.dp)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(urlShape)
                                        .background(tokens.panel2.opacity(0.75).color)
                                        .padding(start = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        if (hasUrl) t.url!! else "Address not known",
                                        Modifier
                                            .weight(1f)
                                            .then(
                                                if (hasUrl) {
                                                    Modifier
                                                        .ppmClickable {
                                                            model.openWebPreviewTab(
                                                                url = t.url!!,
                                                                port = t.port,
                                                                via = t.via ?: "cloudflare",
                                                            )
                                                            onNavigate()
                                                        }
                                                        .testTag("tunnel-open-tab-${t.pid}")
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .padding(vertical = if (hasUrl) 0.dp else 8.dp),
                                        style = ppmText(12f, lineHeight = 16f).copy(
                                            color = if (hasUrl) tokens.text.color else tokens.text2.color,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (hasUrl) {
                                        Box(
                                            Modifier
                                                .size(iconBtnSize)
                                                .clip(RoundedCornerShape(6.dp))
                                                .ppmClickable(hoverColor = tokens.surfaceHover.color) {
                                                    model.openWebPreviewTab(
                                                        url = t.url!!,
                                                        port = t.port,
                                                        via = t.via ?: "cloudflare",
                                                    )
                                                    onNavigate()
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            PpmIcon(ProductIcon.AppWindow, 16.dp, tokens.text2.color)
                                        }
                                        Box(
                                            Modifier
                                                .size(iconBtnSize)
                                                .clip(RoundedCornerShape(6.dp))
                                                .ppmClickable(hoverColor = tokens.surfaceHover.color) { openExternalBrowser(t.url!!) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            PpmIcon(ProductIcon.ExternalLink, 16.dp, tokens.text2.color)
                                        }
                                        Box(
                                            Modifier
                                                .size(iconBtnSize)
                                                .clip(RoundedCornerShape(6.dp))
                                                .ppmClickable(hoverColor = tokens.surfaceHover.color) { copyUrl(t.pid, t.url!!) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            PpmIcon(
                                                if (copiedPid == t.pid) ProductIcon.Check else ProductIcon.Copy,
                                                16.dp,
                                                if (copiedPid == t.pid) Color(0xFF22C55E) else tokens.text2.color,
                                            )
                                        }
                                    }
                                }

                                BasicText(
                                    "pid ${t.pid}",
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val pendingStop = confirmStop
    if (pendingStop != null) {
        ConfirmDialog(
            title = "Stop tunnel?",
            message = "This tunnel${if (pendingStop.port != null) " on port ${pendingStop.port}" else ""} (PID ${pendingStop.pid}) was started outside PPM. Stopping it will terminate the process.",
            confirm = "Stop tunnel",
            destructive = true,
            onConfirm = { handleStop(pendingStop.pid) },
            onDismiss = { confirmStop = null },
        )
    }
}

@Composable
private fun TunnelChip(
    label: String,
    bg: Color,
    fg: Color,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        BasicText(
            label,
            style = ppmText(10f, FontWeight.Medium, lineHeight = 14f).copy(
                color = fg,
                letterSpacing = 0.25.sp,
            ),
        )
    }
}

@Composable
private fun SetupRow(
    name: String,
    setup: SetupState,
    mobile: Boolean,
    onOpen: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val btnShape = RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(
                name,
                style = ppmText(if (mobile) 14f else 12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
            )
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (setup.warn) {
                    PpmIcon(ProductIcon.AlertTriangle, 14.dp, tokens.warning.color, Modifier.padding(top = 1.dp))
                }
                BasicText(
                    setup.status,
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        }
        if (!mobile) {
            Box(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(btnShape)
                    .then(
                        if (setup.done) {
                            Modifier
                        } else {
                            Modifier
                                .background(tokens.bgSolid.color)
                                .cssBorder(tokens.border.color, btnShape)
                        },
                    )
                    .ppmClickable(
                        pressedColor = tokens.surfaceHover.color,
                        hoverColor = tokens.surfaceHover.color,
                        shape = btnShape,
                        onClick = onOpen,
                    )
                    .padding(horizontal = 12.dp)
                    .testTag("tunnel-setup-${name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    if (setup.done) "Manage" else "Set up",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
        }
    }
}

@Composable
private fun TransportOptionButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    mobile: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .height(if (mobile) 44.dp else 32.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) tokens.accent.opacity(0.1).color else Color.Transparent)
            .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
            .ppmClickable(
                enabled = enabled,
                hoverColor = if (selected) tokens.accent.opacity(0.12).color else tokens.surfaceHover.color,
                shape = shape,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(
                if (mobile) 14f else 12f,
                if (selected) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 16f,
            ).copy(color = if (selected) tokens.accent.color else tokens.text.color),
        )
    }
}
