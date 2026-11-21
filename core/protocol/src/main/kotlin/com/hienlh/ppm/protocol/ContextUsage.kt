package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What fills a chat's context window, as the chat footer's context meter shows it: the server's
 * reduction of the CLI's `get_context_usage` answer (Claude's `/context`) to the rows a person
 * reads (`src/shared/context-usage.ts`, `GET …/chat/sessions/:id/context-usage`).
 */
data class ContextUsageView(
    val model: String,
    val percentage: Int,
    val totalTokens: Long,
    val maxTokens: Long,
    /** In-window rows, largest first, the buffer and the free space last; the server orders them. */
    val rows: List<Row>,
) {
    /** One row: [kind] is `used`, `free` or `buffer`. */
    data class Row(val id: String, val name: String, val tokens: Long, val kind: String) {
        val isUsed: Boolean get() = kind == "used"
    }

    companion object {
        /** Null for anything but an object: the route's `usage: null` (no live Claude process). */
        fun from(json: JsonElement?): ContextUsageView? {
            if (json !is JsonObject) return null
            return ContextUsageView(
                model = json["model"].string.orEmpty(),
                percentage = (json["percentage"].double ?: 0.0).roundToInt().coerceIn(0, 100),
                totalTokens = (json["totalTokens"].double ?: 0.0).roundToLong(),
                maxTokens = (json["maxTokens"].double ?: 0.0).roundToLong(),
                rows = json["rows"].array.orEmpty().mapNotNull { row ->
                    val name = row["name"].string ?: return@mapNotNull null
                    Row(
                        id = row["id"].string ?: name,
                        name = name,
                        tokens = (row["tokens"].double ?: 0.0).roundToLong(),
                        kind = row["kind"].string ?: "used",
                    )
                },
            )
        }

        private fun Double.roundToLong(): Long = Math.round(this)
    }
}

/** "12.3k", "1.2M", "850" (`formatTokens`). */
fun formatContextTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, if (n >= 10_000_000) "%.0fM" else "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.ROOT, if (n >= 100_000) "%.0fk" else "%.1fk", n / 1_000.0)
    else -> n.toString()
}
