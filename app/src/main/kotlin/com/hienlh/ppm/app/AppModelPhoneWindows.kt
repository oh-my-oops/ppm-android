package com.hienlh.ppm.app

import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState

/** What a phone opens, full screen, for a floating window it cannot show. */
internal sealed interface PhoneWindowMove {
    val windowId: String

    data class Settings(override val windowId: String, val category: String?) : PhoneWindowMove
    data class SystemMonitor(override val windowId: String, val tab: String) : PhoneWindowMove
    data class Logs(override val windowId: String, val view: String, val chat: String?) : PhoneWindowMove
    data class RemoteDesktop(override val windowId: String) : PhoneWindowMove
    data class Explorer(override val windowId: String, val path: String?) : PhoneWindowMove
    data class AgentSession(override val windowId: String, val payload: AgentSessionPayload) : PhoneWindowMove
}

/**
 * The windows a phone moves out, back to front so the one in front is shown last: every window on
 * screen but a detached tab's (its tab comes back to the grid, `redockAllWindows`). A minimized
 * window stays in the tablet's dock, put away; an agent window whose payload names no session has
 * nothing to show.
 */
internal fun phoneWindowMoves(windows: Collection<WindowRuntimeState>): List<PhoneWindowMove> =
    windows
        .filter { it.state != WindowVisualState.Minimized }
        .sortedBy { it.rank }
        .mapNotNull { win ->
            val payload = win.payload
            when (win.kind) {
                WindowKind.Settings -> PhoneWindowMove.Settings(win.id, payload?.get("category").string)
                WindowKind.SystemMonitor -> PhoneWindowMove.SystemMonitor(win.id, payload?.get("sysmonTab").string ?: "overview")
                WindowKind.Logs -> PhoneWindowMove.Logs(win.id, payload?.get("logsView").string ?: "logs", payload?.get("logsChat").string)
                WindowKind.RemoteDesktop -> PhoneWindowMove.RemoteDesktop(win.id)
                WindowKind.Explorer -> PhoneWindowMove.Explorer(win.id, payload?.get("path").string)
                WindowKind.AgentSession -> AgentSessionPayload.from(payload)?.let { PhoneWindowMove.AgentSession(win.id, it) }
                WindowKind.TabHost -> null
            }
        }

/**
 * A floating window has nowhere to show on a phone (the window layer is the tablet's alone), so
 * narrowing past the desktop layout moves each window on screen to what a phone opens for it, as
 * the `open*` calls do there: Settings, System Monitor, Logs and Remote Desktop as full-screen tabs,
 * the explorer and an agent's session as their full-screen sheets. Each moved window closes, as a
 * detached tab's window does when its tab comes back to the grid, so widening again shows the tab
 * where it now is rather than the same thing twice.
 */
internal fun AppModel.moveWindowsToPhone() {
    if (wide) return
    val store = windows ?: return
    for (move in phoneWindowMoves(store.state.value.windows.values)) {
        when (move) {
            is PhoneWindowMove.Settings -> openSettings(move.category)
            is PhoneWindowMove.SystemMonitor -> openSystemMonitor(move.tab)
            is PhoneWindowMove.Logs -> openLogs(move.view, move.chat)
            is PhoneWindowMove.RemoteDesktop -> {
                val open = openTabs.lastOrNull { it.first.type == "remote-desktop" }
                if (open != null) activate(open.first.id, open.second) else openRemoteDesktop()
            }
            is PhoneWindowMove.Explorer -> if (move.path != null) openExplorer(move.path) else openExplorer()
            is PhoneWindowMove.AgentSession -> openAgentSession(move.payload, agentFallbacks[move.payload.key])
        }
        closeWindow(move.windowId)
    }
}
