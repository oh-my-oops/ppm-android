package com.hienlh.ppm.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hienlh.ppm.state.GroupChat

/**
 * What a group tab holds while it is open (group-chat-tab.tsx): its group's chat, and what the
 * tab's own state keeps while another tab is in front — the message being written, whether the
 * roster is open, how far the feed is scrolled — as the web's tab stays mounted while hidden.
 */
class GroupTab(val chat: GroupChat) {
    val draft = TextFieldState()
    var rosterOpen by mutableStateOf(false)
    val feed = LazyListState()

    /** The feed's length and who was typing when it last scrolled to its end. */
    internal var scrolledFor: Pair<Int, List<String>>? = null
}
