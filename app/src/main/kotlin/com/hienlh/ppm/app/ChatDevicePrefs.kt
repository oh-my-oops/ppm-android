package com.hienlh.ppm.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.client.KeyValueStore
import com.hienlh.ppm.state.ChatViewMode
import com.hienlh.ppm.state.SessionArchive
import com.hienlh.ppm.state.SessionStatusFilter

/**
 * How this device shows chats, kept on it as the web keeps them in `localStorage`: the view mode
 * (`ppm.chat.viewMode`, `chat-view-mode-store.ts`), and the history list's status filter and
 * auto-archive days (`ppm.chatHistory.*`, `chat-history-filter-store.ts`).
 */
class ChatDevicePrefs(private val store: KeyValueStore) {
    var viewMode by mutableStateOf(ChatViewMode.of(store.get(VIEW_MODE_KEY)))
        private set

    var statusFilter by mutableStateOf(SessionStatusFilter.of(store.get(FILTER_KEY)) ?: SessionStatusFilter.ACTIVE)
        private set

    var autoArchiveDays by mutableStateOf(store.get(AUTO_KEY)?.toIntOrNull()?.takeIf { it in SessionArchive.AUTO_ARCHIVE_DAYS } ?: 0)
        private set

    fun chooseViewMode(mode: ChatViewMode) {
        viewMode = mode
        store.put(VIEW_MODE_KEY, mode.wire)
    }

    /** Normal → Thinking → Verbose → Normal (Ctrl+O, and View in the ··· menu). */
    fun cycleViewMode() = chooseViewMode(viewMode.next())

    fun chooseStatus(filter: SessionStatusFilter) {
        statusFilter = filter
        store.put(FILTER_KEY, filter.wire)
    }

    fun chooseAutoArchive(days: Int) {
        if (days !in SessionArchive.AUTO_ARCHIVE_DAYS) return
        autoArchiveDays = days
        store.put(AUTO_KEY, days.toString())
    }

    companion object {
        const val VIEW_MODE_KEY = "ppm.chat.viewMode"
        const val FILTER_KEY = "ppm.chatHistory.status"
        const val AUTO_KEY = "ppm.chatHistory.autoArchiveDays"
    }
}
