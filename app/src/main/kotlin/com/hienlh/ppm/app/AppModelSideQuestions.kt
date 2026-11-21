package com.hienlh.ppm.app

import com.hienlh.ppm.client.askSideQuestion
import com.hienlh.ppm.state.SideQuestionSend

/**
 * `/btw <question>` sent from [tab] (`chat-tab.tsx`'s `onSend`): a side question to the chat's live
 * Claude process, never a message of the chat. False keeps it in the box with a toast saying why,
 * true when it was asked (the box empties), null for any other message.
 */
internal fun AppModel.sideQuestionCommand(tab: ChatTab, text: String): Boolean? {
    val sessionId = tab.controller?.state?.value?.sessionId ?: tab.session?.id
    val extras = tab.replyTo != null || tab.turnLinks.refs.isNotEmpty() || tab.attachments.isNotEmpty()
    return when (val send = SideQuestionSend.of(text, sessionId, tab.providerId, extras)) {
        SideQuestionSend.NotOne -> null
        is SideQuestionSend.Refused -> {
            actionError(send.message)
            false
        }
        is SideQuestionSend.Ask -> {
            val project = tab.project
            val id = sessionId ?: return false
            sideQuestions.ask(tab.tabId, id, send.question) {
                (api ?: error("Not connected")).askSideQuestion(project, id, send.question)
            }
            true
        }
    }
}
