package com.hienlh.ppm.app.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.TerminalBody
import com.hienlh.ppm.app.ui.phone.terminal.resolvePhoneTerminalTab
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * The phone's Terminal area. A real terminal tab is drawn by [TerminalBody], which already carries the redesigned phone
 * parts of `phone/terminal/` (status bar, key row, selection bar, exited bar, `⋮` actions sheet) on the live session.
 * Without a terminal tab, or without a server to attach its shell to, the area says so instead of drawing controls that
 * have no shell behind them.
 */
@Composable
internal fun PhoneTerminalAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    val gridTabs = model.openTabs.map { it.first }
    val dockTabs = model.dockTabs
    val activeDockTabId = model.layout?.dockPanel?.activeTabId
    val requested = tab?.takeIf { it.type == "terminal" }
        ?: gridTabs.lastOrNull { it.type == "terminal" }
        ?: dockTabs.lastOrNull { it.type == "terminal" }
    val resolvedTab = remember(requested, gridTabs, dockTabs, activeDockTabId, model.selectedProject) {
        requested?.let {
            resolvePhoneTerminalTab(
                requestedTab = it,
                gridTabs = gridTabs,
                dockTabs = dockTabs,
                activeDockTabId = activeDockTabId,
                currentProject = model.selectedProject,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("phone-terminal-screen"),
    ) {
        when {
            resolvedTab == null -> PhoneTerminalEmptyState(
                title = "No terminal open",
                detail = if (model.api == null) "Connect to a server to open a terminal." else "Open one to run commands on the host.",
                actionLabel = if (model.api != null) "Open a terminal" else null,
                onAction = {
                    model.openTerminal(inDock = false)
                    model.activeTabId?.let(nav::visitTab)
                },
            )
            model.api == null -> PhoneTerminalEmptyState(
                title = resolvedTab.title.ifBlank { "Terminal" },
                detail = if (model.connectionLost) "Reconnecting to the server…" else "Not connected to a server.",
                actionLabel = null,
                onAction = {},
            )
            else -> TerminalBody(model = model, tab = resolvedTab)
        }
    }
}

@Composable
private fun PhoneTerminalEmptyState(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("phone-terminal-empty"),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PpmIcon(ProductIcon.TerminalSquare, 32.dp, tokens.text3.color)
        BasicText(
            text = title,
            style = ppmText(15f, FontWeight.SemiBold).copy(color = tokens.text.color, textAlign = TextAlign.Center),
        )
        BasicText(
            text = detail,
            style = ppmText(13f, FontWeight.Normal).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
        if (actionLabel != null) {
            ActionPrimaryButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.testTag("phone-terminal-open"),
            )
        }
    }
}
