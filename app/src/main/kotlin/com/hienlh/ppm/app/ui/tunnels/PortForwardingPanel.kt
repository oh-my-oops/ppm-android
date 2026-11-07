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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.ConfirmDialog
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.pressable
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.client.tunnelsTransports
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.design.FontKind
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun PortForwardingPanel(
    model: AppModel,
    initialTunnels: List<TunnelEntry>? = null,
    initialTailscale: TailscaleAvailability? = null,
    initialCloudflare: NamedTunnelStatus? = null,
    onNavigate: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var tunnels by remember { mutableStateOf(initialTunnels.orEmpty()) }
    var loading by remember { mutableStateOf(initialTunnels == null) }
    val portInput = remember { TextFieldState("") }
    var via by remember { mutableStateOf("cloudflare") }
    var tailscale by remember { mutableStateOf(initialTailscale) }
    var cloudflare by remember { mutableStateOf(initialCloudflare) }
    var cloudflareError by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stoppingPid by remember { mutableStateOf<Int?>(null) }
    var copiedPid by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmStop by remember { mutableStateOf<TunnelEntry?>(null) }

    suspend fun fetchTunnels(force: Boolean = false) {
        val api = model.api ?: run {
            loading = false
            return
        }
        try {
            val data = api.tunnelsList(force)
            tunnels = TunnelsModel.parseList(data)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Failed to load tunnels"
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
            } catch (_: Exception) {
                tailscale = TailscaleAvailability(available = false, reason = "Could not check Tailscale on the host")
            }
        }
        val cfJob = scope.launch {
            try {
                val res = api.namedTunnelStatus()
                cloudflare = NamedTunnelStatus.from(res)
                cloudflareError = false
            } catch (_: Exception) {
                cloudflareError = true
            }
        }
        tsJob.join()
        cfJob.join()
    }

    LaunchedEffect(model.api) {
        if (initialTunnels == null) {
            fetchTunnels()
            fetchSetup()
        }
        while (isActive) {
            delay(10_000L)
            if (model.api != null) fetchTunnels()
        }
    }

    fun handleStart() {
        val portNum = portInput.text.toString().trim().toIntOrNull()
        if (portNum == null || portNum < 1 || portNum > 65535) {
            error = "Enter a valid port (1–65535)"
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
            } catch (e: Exception) {
                error = e.message ?: "Failed to start tunnel"
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
            } catch (e: Exception) {
                error = e.message ?: "Failed to stop tunnel"
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
    val cfDomainLine = if (cloudflare?.mode == "named" && !cloudflare?.hostname.isNullOrBlank() && cloudflare?.tunnelWarning.isNullOrBlank() && cloudflare?.certState != "invalid" && cloudflare?.certState != "mismatch") {
        "<port>-${cloudflare!!.hostname}"
    } else {
        "trycloudflare.com"
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag("port-forwarding-panel"),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                "PORT FORWARDING",
                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text2.color),
            )
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(pressedColor = tokens.surfaceHover.color) {
                        scope.launch {
                            fetchTunnels(force = true)
                            fetchSetup()
                        }
                    }
                    .testTag("tunnels-refresh"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.RefreshCw, 14.dp, tokens.text2.color, spinning = loading)
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SetupRow(
                    name = "Tailscale",
                    setup = tsSetup,
                    onOpen = {
                        model.openSettings()
                        onNavigate()
                    },
                )
                SetupRow(
                    name = "Cloudflare",
                    setup = cfSetup,
                    onOpen = {
                        model.openSettings()
                        onNavigate()
                    },
                )
            }

            // Forward form
            Column(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val inputShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(inputShape)
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, inputShape),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .edge(Edge.Right, tokens.border.color)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        BasicText(
                            "localhost:",
                            style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text2.color),
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        PpmTextField(
                            state = portInput,
                            placeholder = "3000",
                            modifier = Modifier.fillMaxWidth().testTag("tunnel-port-input"),
                            style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            onKeyboardAction = { handleStart() },
                        )
                    }
                }

                // Transport radio rows
                val tsAvailable = tailscale?.available == true
                TransportRadioRow(
                    selected = via == "tailscale",
                    enabled = !starting && tsAvailable,
                    title = "Tailscale",
                    badge = "Only your devices",
                    subtitle = if (tsAvailable) tailscale!!.dnsName else (tailscale?.reason ?: "Checking Tailscale…"),
                    onClick = { if (tsAvailable) via = "tailscale" },
                )
                TransportRadioRow(
                    selected = via == "cloudflare",
                    enabled = !starting,
                    title = "Cloudflare",
                    badge = "Anyone with the link",
                    subtitle = cfDomainLine,
                    onClick = { via = "cloudflare" },
                )

                val canStart = !starting && portInput.text.isNotBlank() && (via != "tailscale" || tsAvailable)
                val btnShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .alpha(if (canStart) 1f else 0.45f)
                        .clip(btnShape)
                        .background(tokens.accent.color)
                        .ppmClickable(enabled = canStart, shape = btnShape, onClick = ::handleStart)
                        .testTag("tunnel-forward-btn"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    PpmIcon(
                        if (starting) ProductIcon.Loader2 else ProductIcon.Plus,
                        14.dp,
                        tokens.accentFg.color,
                        spinning = starting,
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        if (starting) "Starting…" else "Forward",
                        style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accentFg.color),
                    )
                }
            }

            // Error banner
            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(tokens.error.opacity(0.1).color)
                        .edge(Edge.Bottom, tokens.error.opacity(0.2).color)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        error!!,
                        Modifier.weight(1f),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                    )
                    Box(
                        Modifier
                            .size(18.dp)
                            .ppmClickable { error = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText("×", style = ppmText(14f, lineHeight = 16f).copy(color = tokens.error.color))
                    }
                }
            }

            // Tunnel list
            if (loading && tunnels.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
                }
            } else if (tunnels.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PpmIcon(ProductIcon.Globe, 32.dp, tokens.text2.opacity(0.4).color)
                    BasicText("No ports forwarded", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                    BasicText(
                        "Enter a local port above to get a link",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.opacity(0.7).color),
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in tunnels) {
                        val cardShape = RoundedCornerShape(6.dp)
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
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val dotColor = when (t.status) {
                                        "running" -> Color(0xFF22C55E)
                                        "starting" -> Color(0xFFEAB308)
                                        else -> Color(0xFFEF4444)
                                    }
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                                    BasicText(
                                        if (t.port != null) ":${t.port}" else "External tunnel",
                                        style = ppmText(12f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                                    )
                                    if (t.via != null) {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(tokens.panel2.color)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            BasicText(
                                                if (t.via == "tailscale") "Tailnet" else "Public",
                                                style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text2.color),
                                            )
                                        }
                                    }
                                    if (t.source == "external") {
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(tokens.panel2.color)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            BasicText(
                                                "Started outside PPM",
                                                style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text2.color),
                                            )
                                        }
                                    }
                                }

                                if (t.protected) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        PpmIcon(ProductIcon.Lock, 12.dp, tokens.text2.color)
                                        BasicText("Public link", style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text2.color))
                                    }
                                } else {
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable(
                                                enabled = stoppingPid != t.pid,
                                                pressedColor = tokens.error.opacity(0.15).color,
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
                                            12.dp,
                                            if (stoppingPid == t.pid) tokens.text2.color else tokens.error.color,
                                            spinning = stoppingPid == t.pid,
                                        )
                                    }
                                }
                            }

                            if (t.status == "running" && !t.url.isNullOrBlank()) {
                                val urlShape = RoundedCornerShape(4.dp)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(urlShape)
                                        .background(tokens.panel2.opacity(0.5).color)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    BasicText(
                                        t.url!!,
                                        Modifier
                                            .weight(1f)
                                            .ppmClickable {
                                                model.openWebPreviewTab(
                                                    url = t.url!!,
                                                    port = t.port,
                                                    via = t.via ?: "cloudflare",
                                                )
                                                onNavigate()
                                            }
                                            .testTag("tunnel-open-tab-${t.pid}"),
                                        style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.accent.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable {
                                                model.openWebPreviewTab(
                                                    url = t.url!!,
                                                    port = t.port,
                                                    via = t.via ?: "cloudflare",
                                                )
                                                onNavigate()
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.AppWindow, 12.dp, tokens.text2.color)
                                    }
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable { openExternalBrowser(t.url!!) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(ProductIcon.ExternalLink, 12.dp, tokens.text2.color)
                                    }
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .ppmClickable { copyUrl(t.pid, t.url!!) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        PpmIcon(
                                            if (copiedPid == t.pid) ProductIcon.Check else ProductIcon.Copy,
                                            12.dp,
                                            if (copiedPid == t.pid) Color(0xFF22C55E) else tokens.text2.color,
                                        )
                                    }
                                }
                            } else if (t.status == "starting") {
                                BasicText(
                                    "Starting tunnel…",
                                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
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
private fun SetupRow(
    name: String,
    setup: SetupState,
    onOpen: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val dotColor = when {
        setup.warn -> Color(0xFFEAB308)
        setup.done -> Color(0xFF22C55E)
        else -> tokens.text2.opacity(0.4).color
    }
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .pressable(shape = shape, onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        BasicText(
            name,
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
        )
        BasicText(
            setup.status,
            Modifier.weight(1f),
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PpmIcon(ProductIcon.Settings, 13.dp, tokens.text2.color)
    }
}

@Composable
private fun TransportRadioRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    badge: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(shape)
            .background(if (selected) tokens.accent.opacity(0.08).color else Color.Transparent)
            .cssBorder((if (selected) tokens.accent else tokens.border).color, shape)
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(14.dp)
                .clip(CircleShape)
                .cssBorder((if (selected) tokens.accent else tokens.text2).color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(tokens.accent.color))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(title, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
                BasicText(badge, style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
            }
            BasicText(
                subtitle,
                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
