package com.hienlh.ppm.app.ui.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ChatTab
import com.hienlh.ppm.app.ui.HistoryByDay
import com.hienlh.ppm.app.ui.PhoneSessionHistoryList
import com.hienlh.ppm.app.ui.TerminalBody
import com.hienlh.ppm.app.ui.TerminalMobileToolbar
import com.hienlh.ppm.app.ui.database.DatabaseQueryTabBody
import com.hienlh.ppm.app.ui.database.DatabaseSidebarPane
import com.hienlh.ppm.app.ui.database.DatabaseTableTabBody
import com.hienlh.ppm.app.ui.database.DbSqlObjectTabBody
import com.hienlh.ppm.app.ui.database.SqliteViewerTabBody
import com.hienlh.ppm.app.ui.phone.chat.PhoneAttachSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneChangedFileItem
import com.hienlh.ppm.app.ui.phone.chat.PhoneChatScreen
import com.hienlh.ppm.app.ui.phone.chat.PhoneMessagePriority
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelEffortSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneModelOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneModesSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneProviderOption
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionChangesSheet
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoData
import com.hienlh.ppm.app.ui.phone.chat.PhoneSessionInfoSheet
import com.hienlh.ppm.app.ui.phone.files.PhoneDiffScreen
import com.hienlh.ppm.app.ui.phone.files.PhoneFileTabScreen
import com.hienlh.ppm.app.ui.phone.files.PhoneFilesTreeScreen
import com.hienlh.ppm.app.ui.phone.git.PhoneSourceControlScreen
import com.hienlh.ppm.app.ui.phone.settings.PhoneSettingsScreen
import com.hienlh.ppm.app.ui.phone.settings.resolvePhoneSettingsRoute
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalActionsSheet
import com.hienlh.ppm.app.ui.phone.terminal.PhoneTerminalStatusBar
import com.hienlh.ppm.app.ui.phone.terminal.resolvePhoneTerminalTab
import com.hienlh.ppm.app.ui.phone.tools.PhoneCloudShareSheet
import com.hienlh.ppm.app.ui.phone.tools.PhoneCloudShareState
import com.hienlh.ppm.app.ui.phone.tools.PhoneLogsScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneLogsState
import com.hienlh.ppm.app.ui.phone.tools.PhoneMonitorState
import com.hienlh.ppm.app.ui.phone.tools.PhoneMonitorTab
import com.hienlh.ppm.app.ui.phone.tools.PhonePortForwardingScreen
import com.hienlh.ppm.app.ui.phone.tools.PhonePortForwardingState
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesState
import com.hienlh.ppm.app.ui.phone.tools.PhoneSystemMonitorScreen
import com.hienlh.ppm.app.ui.projects.ProjectSwitcherSheet
import com.hienlh.ppm.chat.ChatTranscriptView
import com.hienlh.ppm.client.gitCommitMessage
import com.hienlh.ppm.client.namedTunnelStatus
import com.hienlh.ppm.client.queryLogs
import com.hienlh.ppm.client.schedulesList
import com.hienlh.ppm.client.schedulesRunNow
import com.hienlh.ppm.client.schedulesUpdate
import com.hienlh.ppm.client.tunnelStart
import com.hienlh.ppm.client.tunnelStop
import com.hienlh.ppm.client.tunnelsList
import com.hienlh.ppm.client.tunnelsTransports
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.MessagePriority
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.ModelNames
import com.hienlh.ppm.protocol.ProviderInfo
import com.hienlh.ppm.state.PermissionMode
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogFilter
import com.hienlh.ppm.state.logs.LogQueryResult
import com.hienlh.ppm.state.logs.logsQueryPairs
import com.hienlh.ppm.state.logs.rangeStart
import com.hienlh.ppm.state.settings.parseSchedulesList
import com.hienlh.ppm.state.terminal.TerminalState
import com.hienlh.ppm.state.tunnels.NamedTunnelStatus
import com.hienlh.ppm.state.tunnels.TailscaleAvailability
import com.hienlh.ppm.state.tunnels.TunnelEntry
import com.hienlh.ppm.state.tunnels.TunnelsModel
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun PhoneDatabaseAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    val onNavigateToWorkspaceTab: () -> Unit = {
        model.activeTabId?.let(nav::visitTab)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("phone-database-screen"),
    ) {
        if (tab == null) {
            DatabaseSidebarPane(
                model = model,
                onNavigate = onNavigateToWorkspaceTab,
                readOnly = true,
            )
        } else {
            val readOnlyTab = remember(tab) {
                tab.withMetadata("readOnly" to JsonPrimitive(true))
            }
            when (readOnlyTab.type) {
                "database" -> DatabaseTableTabBody(model, readOnlyTab)
                "sqlite" -> SqliteViewerTabBody(model, readOnlyTab)
                "db-query" -> DatabaseQueryTabBody(model, readOnlyTab)
                "db-sql" -> DbSqlObjectTabBody(model, readOnlyTab)
                else -> DatabaseSidebarPane(
                    model = model,
                    onNavigate = onNavigateToWorkspaceTab,
                    readOnly = true,
                )
            }
        }
    }
}

@Composable
internal fun PhoneMonitorAreaHost(
    model: AppModel,
) {
    val monitorState = remember { PhoneMonitorState() }
    val wantProcesses = monitorState.activeTab != PhoneMonitorTab.OVERVIEW &&
        monitorState.activeTab != PhoneMonitorTab.PERFORMANCE

    DisposableEffect(model, wantProcesses) {
        val release = model.retainResourceMonitor(wantProcesses = wantProcesses)
        onDispose { release() }
    }

    PhoneSystemMonitorScreen(
        state = monitorState,
        snapshot = model.sharedMetricsSnapshot,
    )
}

@Composable
internal fun PhoneLogsAreaHost(
    model: AppModel,
) {
    val logsState = remember { PhoneLogsState() }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    LaunchedEffect(model.api, logsState.timeRange) {
        val api = model.api ?: return@LaunchedEffect
        try {
            val filter = LogFilter()
            val from = rangeStart(logsState.timeRange, System.currentTimeMillis())
            val pairs = logsQueryPairs(filter, logsState.timeRange, from, before = null)
            val json = api.queryLogs(pairs)
            val parsed = LogQueryResult.from(json)
            entries = parsed.entries
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep existing log entries on transient failure.
        }
    }

    DisposableEffect(model, model.events) {
        val releaseTail = model.retainLogsTail()
        val ev = model.events
        val unsubLines = ev?.onLogsLines { msg ->
            if (logsState.follow) {
                val incoming = msg["entries"].array.orEmpty().mapNotNull(LogEntry::from)
                if (incoming.isNotEmpty()) {
                    entries = (entries + incoming).takeLast(500)
                }
            }
        }
        onDispose {
            unsubLines?.invoke()
            releaseTail()
        }
    }

    PhoneLogsScreen(
        state = logsState,
        entries = entries,
    )
}

@Composable
internal fun PhonePortForwardingAreaHost(
    model: AppModel,
    nav: PhoneNav,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val portState = remember { PhonePortForwardingState() }
    var tunnels by remember { mutableStateOf<List<TunnelEntry>>(emptyList()) }
    var tailscale by remember { mutableStateOf<TailscaleAvailability?>(null) }
    var cloudflare by remember { mutableStateOf<NamedTunnelStatus?>(null) }

    suspend fun refreshTunnels(force: Boolean = false) {
        val api = model.api ?: return
        try {
            val data = api.tunnelsList(force)
            tunnels = TunnelsModel.parseList(data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(model.api) {
        val api = model.api ?: return@LaunchedEffect
        refreshTunnels()
        try {
            tailscale = TailscaleAvailability.from(api.tunnelsTransports())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            cloudflare = NamedTunnelStatus.from(api.namedTunnelStatus())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val tailscaleReady = tailscale?.available ?: true
    val tailscaleDetail = tailscale?.dnsName?.takeIf { it.isNotBlank() } ?: "tailnet-01.ts.net"
    val cloudflareReady = cloudflare?.let { !it.hostname.isNullOrBlank() } ?: true
    val cloudflareDetail = cloudflare?.hostname?.takeIf { it.isNotBlank() }
        ?: model.namedHostname?.takeIf { it.isNotBlank() }
        ?: "ppm.example.com"

    PhonePortForwardingScreen(
        state = portState,
        tunnels = tunnels,
        tailscaleReady = tailscaleReady,
        tailscaleDetail = tailscaleDetail,
        cloudflareReady = cloudflareReady,
        cloudflareDetail = cloudflareDetail,
        onStartForward = { port, transport ->
            val api = model.api ?: return@PhonePortForwardingScreen
            scope.launch {
                try {
                    api.tunnelStart(port = port, via = transport)
                    refreshTunnels(force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        },
        onStopTunnel = { entry ->
            val api = model.api
            tunnels = tunnels.filterNot { it.pid == entry.pid && it.port == entry.port }
            if (api != null) {
                scope.launch {
                    try {
                        api.tunnelStop(entry.pid)
                        refreshTunnels(force = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        },
        onOpenInTab = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: "http://localhost:${entry.port ?: 3210}"
            model.openWebPreviewTab(url = url, port = entry.port, via = entry.via ?: "cloudflare")
            model.activeTabId?.let(nav::visitTab)
        },
        onOpenInBrowser = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: "http://localhost:${entry.port ?: 3210}"
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        },
        onCopyLink = { entry ->
            val url = entry.url?.takeIf { it.isNotBlank() } ?: "http://localhost:${entry.port ?: 3210}"
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Tunnel URL", url))
            }
        },
    )
}

@Composable
internal fun PhoneSchedulesAreaHost(
    model: AppModel,
) {
    val scope = rememberCoroutineScope()
    val schedulesState = remember { PhoneSchedulesState() }

    suspend fun reloadSchedules() {
        val api = model.api ?: return
        try {
            schedulesState.schedules = parseSchedulesList(api.schedulesList(enabledOnly = false))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(model.api) {
        reloadSchedules()
    }

    PhoneSchedulesScreen(
        state = schedulesState,
        onRunOrRestart = { row ->
            val api = model.api ?: return@PhoneSchedulesScreen
            scope.launch {
                try {
                    api.schedulesRunNow(row.id)
                    reloadSchedules()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        },
        onToggleEnabled = { row, enabled ->
            schedulesState.toggleScheduleEnabled(row.id, enabled)
            val api = model.api ?: return@PhoneSchedulesScreen
            scope.launch {
                try {
                    api.schedulesUpdate(row.id, buildJsonObject { put("enabled", enabled) })
                    reloadSchedules()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        },
    )
}

@Composable
internal fun PhoneCloudShareAreaHost(
    model: AppModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val cloudState = remember(model.namedHostname, model.localUrl) {
        PhoneCloudShareState(
            initialLocalUrl = model.localUrl ?: "http://localhost:3210",
            initialTunnelActive = !model.namedHostname.isNullOrBlank(),
            initialPublicUrl = model.namedHostname?.takeIf { it.isNotBlank() }?.let { host ->
                if (host.startsWith("http://") || host.startsWith("https://")) host else "https://$host"
            } ?: "https://ppm.cloud/workstation-01",
        )
    }

    PhoneCloudShareSheet(
        state = cloudState,
        onDismiss = onDismiss,
        onCopyUrl = { url ->
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("PPM Share URL", url))
            }
        },
    )
}

@Composable
internal fun PhoneSettingsAreaHost(
    model: AppModel,
) {
    val initialRoute = remember(model.requestedSettingsCategory) {
        resolvePhoneSettingsRoute(model.requestedSettingsCategory)
    }

    PhoneSettingsScreen(
        model = model,
        initialRoute = initialRoute,
        modifier = Modifier.testTag("phone-settings-screen"),
    )
}
