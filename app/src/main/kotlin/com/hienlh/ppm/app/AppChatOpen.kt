package com.hienlh.ppm.app

import com.hienlh.ppm.state.windows.cascadeSpawnRect
import com.hienlh.ppm.state.workspace.NewTab
import com.hienlh.ppm.state.workspace.isWindowPanelId
import com.hienlh.ppm.state.workspace.tabSessionId
import com.hienlh.ppm.state.workspace.windowIdFromPanelId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Showing a chat of another project in a floating window beside the project on screen, instead of
 * switching the whole screen to that project (`open-chat-in-window.ts`). Only a tablet has windows;
 * a phone switches project (`planChatOpen`).
 */

/** `CHAT_WINDOW_SIZE`. */
private const val CHAT_WINDOW_W = 520.0
private const val CHAT_WINDOW_H = 640.0

/**
 * Opens [sessionId] of [projectName] in a floating window: a new tab with no project placed straight
 * into a window of its own (never through a grid, where a copy in that project's hidden panel would
 * be focused instead), naming its project in `projectName` and carrying `managerWindow` as every
 * such window does. A chat already in a window is raised instead. [otherwise] runs when no window
 * can be opened (no window layer, every slot taken): the chat then opens the phone's way.
 */
internal fun AppModel.openChatInWindow(projectName: String, sessionId: String, providerId: String, title: String, otherwise: () -> Unit) {
    val windows = windows ?: return otherwise()
    onWorkspace { store ->
        for (panel in store.state.value.panels.values) {
            if (!isWindowPanelId(panel.id) || panel.tabs.none { tabSessionId(it) == sessionId }) continue
            windowIdFromPanelId(panel.id)?.let(windows::focus)
            return@onWorkspace
        }
        val ws = windows.state.value
        val rect = cascadeSpawnRect(ws.windows.values.map { it.rect }, ws.bounds, CHAT_WINDOW_W, CHAT_WINDOW_H)
        val opened = store.openTabInNewWindow(
            NewTab(
                type = "chat",
                title = title,
                projectId = null,
                metadata = buildJsonObject {
                    put("projectName", projectName)
                    put("sessionId", sessionId)
                    put("providerId", providerId)
                    put("managerWindow", true)
                },
            ),
            rect,
        )
        if (opened == null) otherwise()
    }
}
