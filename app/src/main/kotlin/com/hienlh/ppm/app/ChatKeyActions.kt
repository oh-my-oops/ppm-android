package com.hienlh.ppm.app

import com.hienlh.ppm.state.KeyPress
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The chat keys that fire from the composer too (`dispatchChatKeys` in `use-global-keybindings.ts`),
 * where focus sits for most of a chat: the view mode (Ctrl+O), thinking (Alt+T) and the model
 * picker (Alt+P) of the chat in front, and reopening the chat closed last (Alt+Shift+T). Typed
 * into another field, Alt+T and Alt+P stay that field's own.
 */
class ChatKeyActions(private val model: AppModel) {
    /** A chat tab's id whose composer should open its model picker; the composer listens. */
    val modelPicker = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** True when [press] was one of these keys and is spent. */
    fun dispatch(press: KeyPress, inField: Boolean): Boolean {
        val matches = { id: String -> model.keyBindings.matches(press, id) }
        if (matches("chat-view-mode")) {
            model.chatPrefs.cycleViewMode()
            return true
        }
        for (id in listOf("chat-toggle-thinking", "chat-model-picker")) {
            if (!matches(id)) continue
            val tab = model.activeTabId?.let(model::chat)
            // The composer of the chat in front holds the keys; another field keeps them.
            if (inField && (tab == null || TextInputFocus.field !== tab.draft)) return false
            if (tab == null) return true
            if (id == "chat-model-picker") modelPicker.tryEmit(tab.tabId) else toggleThinking(tab)
            return true
        }
        if (matches("reopen-closed-chat")) {
            model.closedChats.takeLast()?.let { model.openLinkedSession(it.projectName, it.sessionId, it.providerId, it.title ?: "") }
            return true
        }
        return false
    }

    /** `onThinkingChange(!thinking)` and its toast; nothing while a turn runs. */
    private fun toggleThinking(tab: ChatTab) {
        if (tab.providerId == "antigravity") return
        val chat = tab.controller ?: return
        val state = chat.state.value
        if (state.isRunning) return
        chat.setThinking(!state.thinking)
        model.toast(
            ErrorToastMessage(
                "Thinking ${if (state.thinking) "off" else "on"} for the next message",
                kind = ErrorToastMessage.Kind.PLAIN,
                durationMillis = 1500,
            ),
        )
    }
}
