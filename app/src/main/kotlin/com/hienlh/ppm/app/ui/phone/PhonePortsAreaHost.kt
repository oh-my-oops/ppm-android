package com.hienlh.ppm.app.ui.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.phone.tools.PhonePortForwardingScreen
import com.hienlh.ppm.app.ui.phone.tools.PhonePortForwardingState
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.client.tunnelsTransports
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.tunnels.NamedTunnelStatus
import com.hienlh.ppm.state.tunnels.SetupState
import com.hienlh.ppm.state.tunnels.TailscaleAvailability
import com.hienlh.ppm.state.tunnels.TunnelEntry
import com.hienlh.ppm.state.tunnels.TunnelsModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How often the forwards and the transports are read again, as the pre-redesign `PortForwardingPanel`. */
internal const val PHONE_PORTS_POLL_MS = 10_000L

/**
 * Phone Port Forwarding, wired as `PortForwardingPanel`: `/api/tunnels` for the forwards, `/api/tunnels/transports`
 * and `/api/tunnel/named/status` for what each transport can do. A host that could not be asked shows that, never a
 * made-up address; a forward with no address offers nothing to open or copy.
 */
@Composable
internal fun PhonePortForwardingAreaHost(
    model: AppModel,
    nav: PhoneNav,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val portState = remember { PhonePortForwardingState(initialTransport = "cloudflare") }
    var tunnels by remember { mutableStateOf<List<TunnelEntry>>(emptyList()) }
    var tailscale by remember { mutableStateOf<TailscaleAvailability?>(null) }
    var cloudflare by remember { mutableStateOf<NamedTunnelStatus?>(null) }
    var cloudflareError by remember { mutableStateOf(false) }
    var transportChosen by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var stoppingPid by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(portState.portInput) { error = null }

    suspend fun fetchTunnels(force: Boolean = false) {
        val api = model.api ?: return
        try {
            tunnels = TunnelsModel.parseList(api.tunnelsList(force))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the last known list: a failed poll is not shown as an inline error (port-forwarding-panel.tsx).
        }
    }

    suspend fun fetchSetup() {
        val api = model.api ?: return
        try {
            val ts = TailscaleAvailability.from(api.tunnelsTransports())
            tailscale = ts
            // As the panel: Tailscale is the default once the host says it can carry a forward.
            if (ts.available && !transportChosen) portState.transport = "tailscale"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (tailscale == null) tailscale = TailscaleAvailability(available = false, reason = "Could not check Tailscale on the host")
        }
        try {
            cloudflare = NamedTunnelStatus.from(api.namedTunnelStatus())
            cloudflareError = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (cloudflare == null) cloudflareError = true
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, lifecycle) {
        if (model.api == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                fetchTunnels()
                fetchSetup()
                delay(PHONE_PORTS_POLL_MS)
            }
        }
    }

    // Before the host answers, the rows say "Checking…"; a failure is said as such by TunnelsModel.
    val tailscaleSetup: SetupState? = tailscale?.let(TunnelsModel::tailscaleSetup)
    val cloudflareSetup: SetupState? = if (cloudflare != null || cloudflareError) {
        TunnelsModel.cloudflareSetup(cloudflare, cloudflareError)
    } else {
        null
    }

    fun openInTab(entry: TunnelEntry) {
        val url = entry.url?.takeIf { it.isNotBlank() } ?: return
        model.openWebPreviewTab(url = url, port = entry.port, via = entry.via ?: "cloudflare")
        model.activeTabId?.let(nav::visitTab)
    }

    PhonePortForwardingScreen(
        state = portState,
        tunnels = tunnels,
        tailscaleSetup = tailscaleSetup,
        cloudflareSetup = cloudflareSetup,
        starting = starting,
        error = error,
        stoppingPid = stoppingPid,
        onStartForward = { port, transport ->
            val api = model.api ?: return@PhonePortForwardingScreen
            starting = true
            error = null
            scope.launch {
                try {
                    val res = api.tunnelStart(port = port, via = transport)
                    portState.portInput = ""
                    fetchTunnels(force = true)
                    val url = res["url"].string
                    if (!url.isNullOrBlank()) {
                        model.openWebPreviewTab(url = url, port = res["port"].int ?: port, via = res["via"].string ?: transport)
                        model.activeTabId?.let(nav::visitTab)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Could not forward port $port"
                } finally {
                    starting = false
                }
            }
        },
        onStopTunnel = { entry ->
            val api = model.api ?: return@PhonePortForwardingScreen
            stoppingPid = entry.pid
            scope.launch {
                try {
                    api.tunnelStop(entry.pid)
                    fetchTunnels(force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.toast(ErrorToastMessage("Could not stop the forward (pid ${entry.pid})", e.message))
                } finally {
                    stoppingPid = null
                }
            }
        },
        onSelectTransport = { transport ->
            transportChosen = true
            portState.transport = transport
        },
        onOpenInTab = ::openInTab,
        onOpenInBrowser = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: return@PhonePortForwardingScreen
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { model.toast(ErrorToastMessage("No browser is available to open this link")) }
        },
        // Set up and Manage open Settings → Remote Access, which a phone opens too.
        onOpenSetup = {
            model.openSettings("remote-access")
            model.activeTabId?.let(nav::visitTab)
        },
        onCopyLink = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: return@PhonePortForwardingScreen
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Forwarded URL", url))
            model.toast(ErrorToastMessage("Link copied", kind = ErrorToastMessage.Kind.SUCCESS))
        },
    )
}
