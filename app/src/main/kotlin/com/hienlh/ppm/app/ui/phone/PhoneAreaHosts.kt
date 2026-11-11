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

/**
 * Routes a [PhoneArea] (either from an active [WorkspaceTab] or a synthetic phone area ID) to its
 * dedicated redesigned phone screen composable wired to [AppModel].
 */
@Composable
internal fun PhoneWorkspaceAreaBody(
    model: AppModel,
    area: PhoneArea,
    tab: WorkspaceTab?,
    nav: PhoneNav,
    showChatHistoryInitially: Boolean = false,
) {
    when (area) {
        PhoneArea.CHAT -> PhoneChatAreaHost(
            model = model,
            tab = tab,
            nav = nav,
            showHistoryInitially = showChatHistoryInitially,
        )
        PhoneArea.PROJECTS -> ProjectSwitcherSheet(
            model = model,
            onDismiss = { nav.closeSheet(SheetKey.ProjectSwitcher) },
            asFlyoutCard = false,
        )
        PhoneArea.FILES -> PhoneFilesAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.SOURCE_CONTROL -> PhoneSourceControlAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.TERMINAL -> PhoneTerminalAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.DATABASE -> PhoneDatabaseAreaHost(
            model = model,
            tab = tab,
            nav = nav,
        )
        PhoneArea.MONITOR -> PhoneMonitorAreaHost(
            model = model,
        )
        PhoneArea.LOGS -> PhoneLogsAreaHost(
            model = model,
        )
        PhoneArea.SCHEDULES -> PhoneSchedulesAreaHost(
            model = model,
        )
        PhoneArea.PORT_FORWARDING -> PhonePortForwardingAreaHost(
            model = model,
            nav = nav,
        )
        PhoneArea.CLOUD_AND_SHARE -> PhoneCloudShareAreaHost(
            model = model,
            onDismiss = { nav.closeSheet(SheetKey.CloudShare) },
        )
        PhoneArea.SETTINGS -> PhoneSettingsAreaHost(
            model = model,
        )
    }
}
