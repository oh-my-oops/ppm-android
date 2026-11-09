package com.hienlh.ppm.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hienlh.ppm.protocol.ChatFrame
import com.hienlh.ppm.protocol.generated.ChatWsServerMessage
import com.hienlh.ppm.protocol.generated.ProtocolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A `turn_events` frame (a running turn's whole buffer, sent on every reconnect) of about 10 MB,
 * decoded off the main thread the two ways the app could: the `JsonElement` tree read field by
 * field (`ChatFrame.from`, what `ChatController` does), and straight into the generated types.
 *
 * Two shapes: a few large tool bodies, and the server's 10,000-event cap of small streamed
 * events between mid-sized tool calls.
 */
@RunWith(AndroidJUnit4::class)
class DecodeBenchmark {
    @get:Rule val rule = BenchmarkRule()

    @Test fun largeBodies_tree() = tree(LARGE)
    @Test fun largeBodies_typed() = typed(LARGE)
    @Test fun manyEvents_tree() = tree(MANY)
    @Test fun manyEvents_typed() = typed(MANY)

    private fun tree(text: String) = rule.measureRepeated { ChatFrame.from(Json.parseToJsonElement(text)) }

    private fun typed(text: String) = rule.measureRepeated {
        ProtocolJson.decodeFromString(ChatWsServerMessage.serializer(), text)
    }

    companion object {
        private val LARGE: String by lazy {
            val body = "x".repeat(4000)
            val events = buildJsonArray {
                for (i in 0 until 1220) {
                    add(buildJsonObject { put("type", "text"); put("content", "Some streamed text $i ") })
                    add(buildJsonObject { put("type", "thinking"); put("content", "Thinking about $i") })
                    add(buildJsonObject {
                        put("type", "tool_use"); put("tool", "Write"); put("toolUseId", "t$i")
                        put("input", buildJsonObject { put("file_path", "/a/b/$i.kt"); put("content", body) })
                    })
                    add(buildJsonObject { put("type", "tool_result"); put("toolUseId", "t$i"); put("output", body); put("isError", false) })
                }
            }
            buildJsonObject { put("type", "turn_events"); put("events", events); put("userMessage", "go"); put("streamSeq", 12) }.toString()
        }

        private val MANY: String by lazy {
            val events = buildJsonArray {
                for (i in 0 until 10_000) {
                    when (i % 4) {
                        0 -> add(buildJsonObject { put("type", "text"); put("content", "tok$i "); put("parentToolUseId", "p"); put("arrivalSeq", i) })
                        1 -> add(buildJsonObject { put("type", "thinking"); put("content", "think$i "); put("arrivalSeq", i) })
                        2 -> add(buildJsonObject {
                            put("type", "tool_use"); put("tool", "Edit"); put("toolUseId", "t$i")
                            put("input", buildJsonObject {
                                put("file_path", "/a/b/$i.kt"); put("old_string", "o".repeat(1200)); put("new_string", "n".repeat(1200))
                                put("todos", buildJsonArray {
                                    repeat(6) { k -> add(buildJsonObject { put("content", "task $k"); put("status", "pending"); put("activeForm", "doing $k") }) }
                                })
                            })
                        })
                        else -> add(buildJsonObject { put("type", "tool_result"); put("toolUseId", "t$i"); put("output", "r".repeat(1200)); put("isError", false) })
                    }
                }
            }
            buildJsonObject { put("type", "turn_events"); put("events", events); put("streamSeq", 1) }.toString()
        }
    }
}
