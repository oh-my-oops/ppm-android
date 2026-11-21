package com.hienlh.ppm.app.push

import com.hienlh.ppm.client.FeedKind
import org.junit.Assert.assertEquals
import org.junit.Test

class PushNotificationTextTest {
    private fun push(kind: FeedKind, title: String, body: String, project: String? = "ppm") =
        WebPushPayload(title = title, body = body, project = project, kind = kind)

    @Test
    fun `a finished chat is titled by the chat, with PPM's line and the answer under it`() {
        val text = pushNotificationText(push(FeedKind.DONE, "Chat completed · Thawng-PC", "ppm — Fix the login bug\nAll tests pass now.\nSecond line"))
        assertEquals("Fix the login bug", text.title)
        assertEquals("Chat completed · Thawng-PC: All tests pass now.", text.text)
        assertEquals("Chat completed · Thawng-PC\nAll tests pass now.\nSecond line", text.bigText)
        assertEquals("ppm", text.subText)
    }

    @Test
    fun `a question with no detail keeps PPM's title as its text`() {
        val text = pushNotificationText(push(FeedKind.QUESTION, "AI has a question · PC", "ppm — Plan the release"))
        assertEquals("Plan the release", text.title)
        assertEquals("AI has a question · PC", text.text)
    }

    @Test
    fun `a chat with no project reads the server's Project placeholder`() {
        val text = pushNotificationText(push(FeedKind.DONE, "Chat stopped · PC", "Project — Untitled", project = null))
        assertEquals("Untitled", text.title)
        assertEquals(null, text.subText)
    }

    @Test
    fun `an approval and an unexpected body are shown as sent`() {
        val approval = push(FeedKind.APPROVAL, "Waiting for approval · PC", "ppm — Bash needs permission\nls")
        assertEquals(PushNotificationText(approval.title, approval.body, approval.body, null), pushNotificationText(approval))
        val odd = push(FeedKind.DONE, "Chat completed · PC", "something else")
        assertEquals("Chat completed · PC", pushNotificationText(odd).title)
    }
}
