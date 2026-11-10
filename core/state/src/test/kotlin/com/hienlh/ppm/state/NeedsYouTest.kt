package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ApprovalRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedsYouTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun `NeedsYou pill is shown only when a waiting card is off screen`() {
        val bash = ApprovalRequest("r1", "Bash", json("""{"command":"rm -rf build"}"""))
        assertEquals(NeedsYouState.None, resolveNeedsYouState(null, atBottom = false))
        assertFalse(resolveNeedsYouState(bash, atBottom = true).visible)

        val offScreen = resolveNeedsYouState(bash, atBottom = false)
        assertTrue(offScreen.visible)
        assertEquals(WaitingCardKind.TOOL_APPROVAL, offScreen.kind)
        assertEquals("Waiting for approval · Bash", offScreen.label)
        assertTrue(offScreen.warning)
    }

    @Test
    fun `NeedsYou labels match Phone 1 section 4 waiting cards`() {
        val question = ApprovalRequest("r2", "AskUserQuestion", json("""{"questions":[]}"""))
        val db = ApprovalRequest("r3", "ppm:db_execute", json("""{"connectionId":1,"sql":"UPDATE t SET x=1"}"""))
        val create = ApprovalRequest("r4", "ppm:create_session", json("""{"title":"Sub"}"""))
        val elevate = ApprovalRequest("r5", "ppm:send_message", json("""{"recipientPermissionMode":"bypassPermissions"}"""))
        val rate = ApprovalRequest("r6", "ppm:rate_limit", json("""{"turns":20}"""))

        assertEquals("AI has a question", needsYouPillLabel(question))
        assertEquals("Approve a database change", needsYouPillLabel(db))
        assertEquals("The AI wants to create a session", needsYouPillLabel(create))
        assertEquals("The AI wants to message a session with more rights", needsYouPillLabel(elevate))
        assertEquals("These sessions reached their message limit", needsYouPillLabel(rate))
    }

    @Test
    fun `ChatTranscript needsYouState updates as approval arrives and resolves`() {
        val transcript = ChatTranscript()
        assertFalse(transcript.needsYouState(atBottom = false).visible)

        transcript.handle(frame("""{"type":"approval_request","requestId":"req-1","tool":"AskUserQuestion","input":{}}"""))
        val waiting = transcript.needsYouState(atBottom = false)
        assertTrue(waiting.visible)
        assertEquals("AI has a question", waiting.label)
        assertFalse(waiting.warning)
        assertTrue(transcript.needsYouState(atBottom = true, cardKeyboardFocused = true).hidesComposerOnKeyboard)

        assertFalse(transcript.needsYouState(atBottom = true).visible)

        transcript.handle(frame("""{"type":"approval_resolved","requestId":"req-1","approved":true}"""))
        assertEquals(NeedsYouState.None, transcript.needsYouState(atBottom = false))
    }
}
