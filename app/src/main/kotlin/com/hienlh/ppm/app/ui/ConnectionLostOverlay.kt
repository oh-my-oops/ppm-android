package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.rememberHover
import com.hienlh.ppm.state.ConnectivityState
import kotlinx.coroutines.launch

private const val CLOUD_URL = "https://cloud.ppm.sh"

/**
 * Observes Android's [ConnectivityManager] to know whether the phone or tablet itself has an active
 * network connection (`NET_CAPABILITY_INTERNET`), so the app can distinguish device-offline from
 * server-unreachable (Requirement 5.3).
 */
@Composable
internal fun rememberDeviceOnline(): Boolean {
    val context = LocalContext.current
    val cm = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    fun checkOnline(): Boolean {
        val manager = cm ?: return true
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    var online by remember { mutableStateOf(checkOnline()) }
    DisposableEffect(cm) {
        val manager = cm ?: return@DisposableEffect onDispose {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = checkOnline()
            }

            override fun onLost(network: Network) {
                online = checkOnline()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                online = checkOnline()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
    return online
}

/**
 * The connection-lost overlay is up. Dialogs, menus and pickers are windows of their own, above
 * everything drawn in the app's, so none stays open under it: the web's sit below its layer
 * until the reload that follows closes them.
 */
val LocalConnectionLost = compositionLocalOf { false }

/** True while the overlay is up, when the window [onDismiss] closes must not show; it is closed. */
@Composable
fun closedByConnectionLost(onDismiss: () -> Unit): Boolean {
    val lost = LocalConnectionLost.current
    if (lost) LaunchedEffect(Unit) { onDismiss() }
    return lost
}

/**
 * The web's `ConnectionLostOverlay`, once the server has not answered for 15 s: over everything
 * but the toast, the content under it blurred. Through a tunnel it says the tunnel closed (or,
 * through the server's own named hostname, that the address is fine and the machine is not)
 * and offers the server's own-network address and PPM Cloud; Retry opens the server again if it
 * answers.
 *
 * When the device itself has no network (Requirement 5.3), shows `"This phone is offline"` (or
 * `"This tablet is offline"` on tablet) instead of reporting the server as unreachable.
 */
@Composable
fun ConnectionLostOverlay(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val deviceOnline = rememberDeviceOnline()
    val host = model.api?.baseUrl?.host.orEmpty()
    val named = model.namedHostname
    val snapshot = ConnectivityState.resolve(
        deviceOnline = deviceOnline,
        serverUp = false,
        outageDurationMs = ConnectivityState.OUTAGE_OVERLAY_MS,
        host = host,
        namedHostname = named,
        localUrl = model.localUrl,
        isTablet = !mobile,
        serverName = model.serverName,
    )
    val deviceOffline = !snapshot.deviceOnline
    // `isTunnelDomain` / `isNamedDomain`: reached through a cloudflared tunnel, not straight in.
    val isNamed = named != null && host == named
    val isTunnel = isNamed || host.endsWith(".trycloudflare.com")
    val localUrl = model.localUrl
    var retrying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.opacity(0.95).color)
            // Nothing under it takes a touch, as nothing under the web's fixed layer takes a click.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 384.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val iconTint = if (deviceOffline) tokens.warning else tokens.error
            Box(Modifier.clip(CircleShape).background(iconTint.opacity(0.1).color).padding(16.dp)) {
                if (deviceOffline || isTunnel) {
                    PpmIcon(ProductIcon.WifiOff, 40.dp, iconTint.color)
                } else {
                    PpmIcon(LucideIcon.ServerOff, 40.dp, iconTint.color)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    snapshot.title,
                    style = ppmText(20f, FontWeight.SemiBold, lineHeight = 28f).copy(color = tokens.text.color, textAlign = TextAlign.Center),
                )
                BasicText(
                    snapshot.body,
                    style = ppmText(14f, lineHeight = 22.75f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The own-network address skips Cloudflare, so it works when only the hostname is broken.
                if (!deviceOffline && isTunnel && localUrl != null) {
                    OverlayButton("Open on local network", primary = true, icon = ProductIcon.Home, onClick = model::openLocalNetwork)
                }
                if (!deviceOffline && isTunnel) {
                    OverlayButton("Open PPM Cloud", primary = localUrl == null) { runCatching { uriHandler.openUri(CLOUD_URL) } }
                }
                OverlayButton(
                    if (retrying) "Retrying…" else "Retry Connection",
                    primary = false,
                    icon = ProductIcon.RefreshCw,
                    spinning = retrying,
                    enabled = !retrying,
                ) {
                    retrying = true
                    scope.launch {
                        try {
                            model.retryConnection()
                        } finally {
                            retrying = false
                        }
                    }
                }
            }
            if (!deviceOffline && !isTunnel) RestartHint()
        }
    }
}

/**
 * `rounded-md px-4 py-2.5 text-sm font-medium`, full width: filled in the accent (`/90` under a
 * mouse), or outlined (`border-border`, `surface-elevated` under a mouse), faded in. Half opacity
 * while disabled, when a mouse still lights it. A touch has no colour of its own, as on the web.
 */
@Composable
private fun OverlayButton(
    label: String,
    primary: Boolean,
    icon: ProductIcon? = null,
    spinning: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(8.dp)
    val hover = rememberHover()
    val fill = if (primary) tokens.accent.opacity(1 - 0.1 * hover.lit) else tokens.panel2.opacity(hover.lit.toDouble())
    val color = (if (primary) tokens.accentFg else tokens.text).color
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(fill.color)
            .ppmClickable(enabled = enabled, pressedColor = Color.Transparent, source = hover.source, hoverWhileDisabled = true, onClick = onClick)
            .then(if (primary) Modifier else Modifier.cssBorder(tokens.border.color, shape))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { PpmIcon(it, 16.dp, color, spinning = spinning) }
        BasicText(label, style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = color))
    }
}

/** "If the server was stopped, run `ppm start` to restart it.", the command in a `bg-muted` chip. */
@Composable
private fun RestartHint() {
    val tokens = LocalPpmTokens.current
    val color = tokens.text2.color
    // `text-[11px]` keeps the paragraph's line-height, 16 / 12 of its size.
    val code = ppmText(11f, kind = FontKind.MONO, lineHeight = 11f * 16f / 12f).copy(color = color)
    val command = "ppm start"
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = remember(measurer, code, density) {
        with(density) { (measurer.measure(command, code).size.width.toDp() + 8.dp).toSp() }
    }
    BasicText(
        buildAnnotatedString {
            append("If the server was stopped, run ")
            appendInlineContent(COMMAND, command)
            append(" to restart it.")
        },
        style = ppmText(12f, lineHeight = 16f).copy(color = color, textAlign = TextAlign.Center),
        inlineContent = mapOf(
            COMMAND to InlineTextContent(Placeholder(width, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // `py-0.5` reaches past the line, as an inline box's padding does in CSS.
                    BasicText(
                        command,
                        Modifier
                            .wrapContentHeight(unbounded = true)
                            .background(tokens.panel.color, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = code,
                        // Measured to fit; a rounded-off pixel must not break the command in two.
                        softWrap = false,
                    )
                }
            },
        ),
    )
}

private const val COMMAND = "command"
