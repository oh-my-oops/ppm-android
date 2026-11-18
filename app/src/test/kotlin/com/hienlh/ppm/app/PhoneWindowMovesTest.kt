package com.hienlh.ppm.app

import com.hienlh.ppm.protocol.AgentSource
import com.hienlh.ppm.state.AgentSessionPayload
import com.hienlh.ppm.state.windows.Rect
import com.hienlh.ppm.state.windows.WindowKind
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.windows.WindowVisualState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** A floating window the phone cannot show becomes the full-screen thing a phone opens for it. */
class PhoneWindowMovesTest {
    private fun win(
        id: String,
        kind: WindowKind,
        rank: Int,
        state: WindowVisualState = WindowVisualState.Normal,
        payload: JsonObject? = null,
    ) = WindowRuntimeState(id, kind, Rect(0.0, 0.0, 400.0, 300.0), rank, rank.toDouble(), state, payload = payload)

    @Test
    fun `windows move back to front, each with what its payload names`() {
        val agent = AgentSessionPayload(projectName = "ppm", providerId = "claude", sessionId = "s1", source = AgentSource.Card("toolu_1"))
        val moves = phoneWindowMoves(
            listOf(
                win("w-logs", WindowKind.Logs, 3, payload = buildJsonObject { put("logsView", "trace"); put("logsChat", "c1") }),
                win("w-settings", WindowKind.Settings, 0, payload = buildJsonObject { put("category", "appearance") }),
                win("w-sysmon", WindowKind.SystemMonitor, 1, WindowVisualState.Maximized),
                win("w-rd", WindowKind.RemoteDesktop, 2, WindowVisualState.Snapped),
                win("w-explorer", WindowKind.Explorer, 4, payload = buildJsonObject { put("path", "/home/u") }),
                win("w-agent", WindowKind.AgentSession, 5, payload = agent.toJson()),
            ),
        )
        assertEquals(
            listOf(
                PhoneWindowMove.Settings("w-settings", "appearance"),
                PhoneWindowMove.SystemMonitor("w-sysmon", "overview"),
                PhoneWindowMove.RemoteDesktop("w-rd"),
                PhoneWindowMove.Logs("w-logs", "trace", "c1"),
                PhoneWindowMove.Explorer("w-explorer", "/home/u"),
                PhoneWindowMove.AgentSession("w-agent", agent),
            ),
            moves,
        )
    }

    @Test
    fun `a minimized window, a detached tab's window and an agent window naming no session stay`() {
        val moves = phoneWindowMoves(
            listOf(
                win("w-min", WindowKind.Settings, 0, WindowVisualState.Minimized),
                win("w-tab", WindowKind.TabHost, 1),
                win("w-agent", WindowKind.AgentSession, 2, payload = buildJsonObject { put("projectName", "ppm") }),
                win("w-logs", WindowKind.Logs, 3),
            ),
        )
        assertEquals(listOf(PhoneWindowMove.Logs("w-logs", "logs", null)), moves)
    }
}
