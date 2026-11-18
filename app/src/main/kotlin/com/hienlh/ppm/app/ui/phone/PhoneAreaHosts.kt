package com.hienlh.ppm.app.ui.phone

import androidx.compose.runtime.Composable
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.TabBody
import com.hienlh.ppm.app.ui.phone.specs.PhoneSpecsAreaHost
import com.hienlh.ppm.app.ui.projects.ProjectSwitcherSheet
import com.hienlh.ppm.state.workspace.WorkspaceTab

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
            tab = tab,
        )
        PhoneArea.LOGS -> PhoneLogsAreaHost(
            model = model,
            tab = tab,
        )
        PhoneArea.SCHEDULES -> PhoneSchedulesAreaHost(
            model = model,
        )
        // "Open in a tab" makes a `web-preview` tab: it shows the forwarded page itself, as `TabBody` does.
        PhoneArea.PORT_FORWARDING -> if (tab?.type == "web-preview") {
            TabBody(model = model, tab = tab)
        } else {
            PhonePortForwardingAreaHost(
                model = model,
                nav = nav,
            )
        }
        PhoneArea.CLOUD_AND_SHARE -> PhoneCloudShareAreaHost(
            model = model,
            onDismiss = { nav.closeSheet(SheetKey.CloudShare) },
        )
        PhoneArea.SETTINGS -> PhoneSettingsAreaHost(
            model = model,
        )
        PhoneArea.SPECS -> PhoneSpecsAreaHost(
            model = model,
            nav = nav,
        )
        PhoneArea.ASSISTANT -> tab?.let { TabBody(model = model, tab = it) }
    }
}
