package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ChatClientMessage
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.QueuedMessage
import com.hienlh.ppm.protocol.SessionPhase
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's queue (`chat-message-queue.ts`, `queue_state` in `use-chat.ts`): a message sent
 * while a turn runs is held by the server and joins the conversation, on every device, the sender's
 * included, only when the AI starts on it.
 */
class MessageQueueTest {
    private fun running(): ChatTranscript = ChatTranscript().also {
        it.handle(frame("""{"type":"phase_changed","phase":"thinking"}"""))
        it.handle(frame("""{"type":"text","content":"Working","streamSeq":1}"""))
    }

    @Test
    fun `a message sent mid-turn is queued, draws nothing and leaves the stream alone`() {
        val chat = running()
        val message = chat.send("one more thing")
        assertTrue(message.queued)
        val json = message.json
        assertEquals("true", json["queued"]?.jsonPrimitive?.content)
        assertNull("no Send now unless asked", json["sendNow"])
        assertTrue("nothing drawn", chat.items.none { it.role == ChatItem.Role.USER })
        assertEquals(listOf(ChatBlock.Text("Working")), chat.live?.blocks)
        assertEquals(SessionPhase.THINKING, chat.phase)
    }

    @Test
    fun `Send now rides on a queued message only`() {
        assertEquals("true", running().send("go", sendNow = true).json["sendNow"]?.jsonPrimitive?.content)
        val idle = ChatTranscript().send("go", sendNow = true)
        assertFalse(idle.queued)
        assertNull(idle.json["queued"])
        assertNull(idle.json["sendNow"])
    }

    @Test
    fun `the echo of this device's queued message shows it once, after the reply it waited behind`() {
        val chat = running()
        val message = chat.send("one more thing")
        val echo = """{"type":"user_message","content":"one more thing","clientMessageId":"${message.clientMessageId}"}"""
        chat.handle(frame(echo))
        chat.handle(frame("""{"type":"text","content":"On it","streamSeq":2}"""))
        // A replay or a second echo of the same message adds nothing.
        chat.handle(frame(echo))
        assertEquals(listOf(ChatItem.Role.ASSISTANT, ChatItem.Role.USER), chat.items.map { it.role })
        assertEquals(listOf("Working", "one more thing"), chat.items.map { it.text })
        assertEquals(listOf(ChatBlock.Text("On it")), chat.live?.blocks)
    }

    @Test
    fun `the echo of a message this device drew is not drawn again`() {
        val chat = ChatTranscript()
        val message = chat.send("hello")
        chat.handle(frame("""{"type":"user_message","content":"hello","clientMessageId":"${message.clientMessageId}"}"""))
        assertEquals(listOf("hello"), chat.items.map { it.text })
    }

    @Test
    fun `two devices sending the same words are two messages when the ids differ`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"user_message","content":"yes","clientMessageId":"a"}"""))
        chat.handle(frame("""{"type":"user_message","content":"yes","clientMessageId":"b"}"""))
        assertEquals(listOf("yes", "yes"), chat.items.map { it.text })
    }

    @Test
    fun `a refused queued message gives its text back and removes nothing`() {
        val chat = running()
        val message = chat.send("later")
        chat.handle(ChatFrame.MessageRejected(message.clientMessageId, "Too long"))
        assertEquals(RejectedSend("later", null, "Too long"), chat.rejected)
        assertEquals(SessionPhase.THINKING, chat.phase)
    }

    @Test
    fun `the queue is the server's list, from queue_state and from a greeting that carries one`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"queue_state","queue":[{"id":"q1","content":"a","imageCount":2,"clientMessageId":"c1","queuedAt":"2026-11-11T01:00:00Z"},{"id":"q2","content":"b","imageCount":0,"queuedAt":"2026-11-11T01:00:01Z"}]}"""))
        assertEquals(
            listOf(QueuedMessage("q1", "a", 2, "c1", "2026-11-11T01:00:00Z"), QueuedMessage("q2", "b", 0, null, "2026-11-11T01:00:01Z")),
            chat.queue.messages,
        )
        // A model change answers with a session_state that does not carry the queue.
        chat.handle(frame("""{"type":"session_state","sessionId":"s","phase":"thinking"}"""))
        assertEquals(listOf("q1", "q2"), chat.queue.messages.map { it.id })
        chat.handle(frame("""{"type":"session_state","sessionId":"s","phase":"thinking","queue":[{"id":"q2","content":"b","imageCount":0}]}"""))
        assertEquals(listOf("q2"), chat.queue.messages.map { it.id })
        chat.handle(frame("""{"type":"queue_state","queue":[]}"""))
        assertTrue(chat.queue.messages.isEmpty())
    }

    @Test
    fun `Edit gets the text back for the composer, Remove does not, and a late ask says why`() {
        val queue = MessageQueue()
        assertEquals(ChatClientMessage.QueueTakeBack("q1"), queue.takeBack("q1", discard = false))
        assertEquals("""{"type":"queue_take_back","id":"q1"}""", queue.takeBack("q1", discard = false).json.toString())
        queue.answered(frame("""{"type":"queue_taken_back","id":"q1","content":"fix it","imageCount":0}""") as ChatFrame.QueueTakenBack)
        assertEquals(QueueTakeBackAnswer("q1", "fix it", null), queue.answer)
        queue.answerTaken()
        assertNull(queue.answer)

        queue.takeBack("q2", discard = true)
        queue.answered(ChatFrame.QueueTakenBack("q2", "drop me", null))
        assertNull("a removed message does not come back", queue.answer)

        queue.takeBack("q3", discard = true)
        queue.answered(ChatFrame.QueueTakenBack("q3", null, "The AI has already started reading it"))
        assertEquals(QueueTakeBackAnswer("q3", null, "The AI has already started reading it"), queue.answer)
    }

    @Test
    fun `an entry a Stop kept is marked paused, and one without the field is not`() {
        val chat = ChatTranscript()
        chat.handle(frame("""{"type":"queue_state","queue":[{"id":"q1","content":"a","imageCount":0,"paused":true},{"id":"q2","content":"b","imageCount":0}]}"""))
        assertEquals(listOf(true, false), chat.queue.messages.map { it.paused })
    }

    @Test
    fun `Send now is its own frame`() {
        assertEquals("""{"type":"queue_send_now"}""", ChatClientMessage.QueueSendNow.json.toString())
    }
}
