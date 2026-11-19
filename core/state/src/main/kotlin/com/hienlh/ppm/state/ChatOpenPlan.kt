package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.isAssistantProject

/**
 * Where a chat opens when something outside its tab asks for it (`chat-open-plan.ts`): a tool card's
 * Open chat, a linked-chat chip, a turn chip, a task's or a schedule's Open chat.
 */
enum class ChatOpenPlan {
    /** A PPM Assistant session belongs to no project and opens in the Assistant. */
    ASSISTANT,

    /** A chat of the project on screen opens as a tab, as it always has. */
    HERE,

    /** A chat of another project opens in a floating window, and the screen stays on the project the user was in. */
    WINDOW,

    /** The screen switches to the chat's project: the phone's way (no windows there), and the way with no project on screen. */
    SWITCH,
}

/** `planChatOpen`: [mobile] is a layout with no floating windows (the phone). Project names are compared exactly. */
fun planChatOpen(projectName: String, activeProject: String?, mobile: Boolean): ChatOpenPlan = when {
    isAssistantProject(projectName) -> ChatOpenPlan.ASSISTANT
    projectName == activeProject -> ChatOpenPlan.HERE
    mobile || activeProject == null -> ChatOpenPlan.SWITCH
    else -> ChatOpenPlan.WINDOW
}
