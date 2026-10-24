package com.hienlh.ppm.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor

/**
 * Server frames are parsed into a [JsonElement] first and then read field by field, so a field
 * the server adds, or a frame type this app does not know yet, never fails a decode.
 */
object WireJson {
    /** Throws [IllegalArgumentException] on text that is not JSON. */
    fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    fun parseOrNull(text: String): JsonElement? = try {
        parse(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

val JsonElement?.string: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.booleanOrNull

val JsonElement?.double: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull

/** A whole number only: `2.5` is not a long. */
val JsonElement?.long: Long?
    get() {
        val value = double ?: return null
        return if (value == floor(value) && abs(value) < 9e15) value.toLong() else null
    }

val JsonElement?.int: Int?
    get() = long?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

val JsonElement?.array: List<JsonElement>?
    get() = this as? JsonArray

val JsonElement?.obj: Map<String, JsonElement>?
    get() = this as? JsonObject

/**
 * As the web prints a tool's input, `JSON.stringify(value, null, 2)` — keys in the order they
 * arrived, as JavaScript keeps them.
 */
val JsonElement.prettyString: String
    get() = buildString { writePretty(this@prettyString, "") }

private fun StringBuilder.writePretty(value: JsonElement, indent: String) {
    val inner = "$indent  "
    when (value) {
        is JsonNull -> append("null")
        is JsonPrimitive -> when {
            value.isString -> appendJsString(value.content)
            value.booleanOrNull != null -> append(value.content)
            else -> append(jsNumber(value.doubleOrNull))
        }
        is JsonArray -> if (value.isEmpty()) {
            append("[]")
        } else {
            append("[\n")
            value.forEachIndexed { index, item ->
                append(inner)
                writePretty(item, inner)
                append(if (index < value.size - 1) ",\n" else "\n")
            }
            append(indent).append(']')
        }
        is JsonObject -> if (value.isEmpty()) {
            append("{}")
        } else {
            append("{\n")
            val entries = value.entries.toList()
            entries.forEachIndexed { index, (key, item) ->
                append(inner)
                appendJsString(key)
                append(": ")
                writePretty(item, inner)
                append(if (index < entries.size - 1) ",\n" else "\n")
            }
            append(indent).append('}')
        }
    }
}

/** A number as JavaScript prints it: no `.0` on whole numbers, exponents only past 1e21 or under 1e-6. */
fun jsNumber(value: Double?): String {
    if (value == null || !value.isFinite()) return "null"
    if (value == 0.0) return "0"
    val magnitude = abs(value)
    if (magnitude >= 1e-6 && magnitude < 1e21) {
        return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }
    // Kotlin writes 1.5E-7 and 1.0E21; JavaScript 1.5e-7 and 1e+21.
    val (mantissa, exponent) = value.toString().split('E').let { it[0] to it.getOrElse(1) { "0" } }
    val digits = mantissa.removeSuffix(".0")
    return if (exponent.startsWith("-")) "${digits}e$exponent" else "${digits}e+$exponent"
}

/** `JSON.stringify` of a string, including its escapes for lone surrogates. */
internal fun StringBuilder.appendJsString(text: String) {
    append('"')
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            c.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                append(c).append(text[index + 1])
                index++
            }
            c.isSurrogate() -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        index++
    }
    append('"')
}
