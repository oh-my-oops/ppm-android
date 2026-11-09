package com.hienlh.ppm.protocol

import com.hienlh.ppm.protocol.generated.ChatHistoryResponse
import com.hienlh.ppm.protocol.generated.ChatSessionsPage
import com.hienlh.ppm.protocol.generated.ChatWsServerMessage
import com.hienlh.ppm.protocol.generated.GlobalWsClientMessage
import com.hienlh.ppm.protocol.generated.GlobalWsServerMessage
import com.hienlh.ppm.protocol.generated.LspServerMessage
import com.hienlh.ppm.protocol.generated.Presence
import com.hienlh.ppm.protocol.generated.ProjectInfo
import com.hienlh.ppm.protocol.generated.ProtocolJson
import com.hienlh.ppm.protocol.generated.ServerInfo
import com.hienlh.ppm.protocol.generated.ServerProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoders generated from the server's TypeScript (`scripts/gen-native-protocol.ts`), against
 * frames a real server sent (`protocol-samples.json`, from `android/scripts/capture-protocol-samples.ts`).
 */
class GeneratedProtocolTest {
    private val samples: JsonObject = ProtocolJson.parseToJsonElement(
        checkNotNull(javaClass.getResource("/protocol-samples.json")).readText(),
    ).jsonObject

    private fun frames(name: String): List<JsonObject> = (samples[name] as JsonArray).map { it.jsonObject }

    /** A REST body's `data`. */
    private fun data(name: String) = checkNotNull(samples[name]?.jsonObject?.get("data"))

    @Test fun everyRecordedChatFrameDecodesToItsOwnVariant() {
        val decoded = frames("chat").map { ProtocolJson.decodeFromJsonElement(ChatWsServerMessage.serializer(), it) }
        assertTrue(decoded.isNotEmpty())
        assertTrue("no frame may fall back to Unknown: $decoded", decoded.none { it is ChatWsServerMessage.Unknown })
        assertTrue(decoded.first() is ChatWsServerMessage.SessionState)
        assertTrue(decoded.any { it is ChatWsServerMessage.ToolUse })
        val text = decoded.filterIsInstance<ChatWsServerMessage.Text>().joinToString("") { it.content }
        assertTrue(text.isNotEmpty())
        assertTrue(decoded.last() is ChatWsServerMessage.Done)
    }

    @Test fun everyRecordedGlobalAndLspFrameDecodes() {
        val global = frames("global").map { ProtocolJson.decodeFromJsonElement(GlobalWsServerMessage.serializer(), it) }
        assertTrue(global.none { it is GlobalWsServerMessage.Unknown })
        assertTrue(global.first() is GlobalWsServerMessage.GlobalReady)
        assertTrue(global.any { it is GlobalWsServerMessage.SessionUnreadChanged })
        val lsp = frames("lsp").map { ProtocolJson.decodeFromJsonElement(LspServerMessage.serializer(), it) }
        assertTrue(lsp.none { it is LspServerMessage.Unknown })
    }

    @Test fun restResponsesDecode() {
        val info = ProtocolJson.decodeFromJsonElement(ServerInfo.serializer(), data("info"))
        assertEquals(ServerProtocol.PROTOCOL, info.protocol)
        assertTrue("chat" in info.capabilities)
        val projects = ProtocolJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(ProjectInfo.serializer()), data("projects"))
        assertTrue(projects.any { it.name == "demo" })
        val page = ProtocolJson.decodeFromJsonElement(ChatSessionsPage.serializer(), data("sessions"))
        assertTrue(page.sessions.isNotEmpty())
        val history = ProtocolJson.decodeFromJsonElement(ChatHistoryResponse.serializer(), data("history"))
        assertTrue(history.messages.isNotEmpty())
    }

    @Test fun anUnknownTypeKeepsItsFrame() {
        val raw = """{"type":"from_a_newer_server","x":1}"""
        val frame = ProtocolJson.decodeFromString(ChatWsServerMessage.serializer(), raw)
        assertTrue(frame is ChatWsServerMessage.Unknown)
        assertEquals("from_a_newer_server", (frame as ChatWsServerMessage.Unknown).raw["type"]!!.jsonPrimitive.content)
        val lsp = ProtocolJson.decodeFromString(LspServerMessage.serializer(), """{"t":"later","id":3}""")
        assertTrue(lsp is LspServerMessage.Unknown)
    }

    @Test fun anUnknownFieldIsIgnored() {
        val frame = ProtocolJson.decodeFromString(
            ChatWsServerMessage.serializer(),
            """{"type":"text","content":"hi","addedLater":{"a":[1,2]}}""",
        )
        assertEquals("hi", (frame as ChatWsServerMessage.Text).content)
    }

    @Test fun turnStopTellsAbsentFromNull() {
        fun state(extra: String) = ProtocolJson.decodeFromString(
            ChatWsServerMessage.serializer(),
            """{"type":"session_state","sessionId":"s","phase":"idle","pendingApproval":null,"sessionTitle":null$extra}""",
        ) as ChatWsServerMessage.SessionState
        assertEquals(Presence.Absent, state("").turnStop)
        assertEquals(Presence.Null, state(""","turnStop":null""").turnStop)
        val stopped = state(""","turnStop":{"message":"Stopped","subtype":"error_max_turns","at":1}""").turnStop
        assertTrue("$stopped", stopped is Presence.Value)
    }

    @Test fun clientMessagesEncodeWithTheirType() {
        assertEquals("""{"type":"ping"}""", ProtocolJson.encodeToString(GlobalWsClientMessage.serializer(), GlobalWsClientMessage.Ping))
        assertEquals(
            """{"type":"watch","projectName":"demo"}""",
            ProtocolJson.encodeToString(GlobalWsClientMessage.serializer(), GlobalWsClientMessage.Watch("demo")),
        )
    }
}
