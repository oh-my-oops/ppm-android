package com.hienlh.ppm.design

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class RegexOperation { VALIDATE, REPLACE, HIGHLIGHT }
data class RegexOperationsRequest(
    val operation: RegexOperation,
    val query: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val useRegex: Boolean = false,
    val text: String = "",
    val replacement: String = "",
    val texts: List<String> = emptyList(),
)
/** Half-open UTF-16 positions, directly usable as Compose span offsets. */
data class RegexHighlight(val start: Int, val end: Int)
data class RegexOperationsResult(
    val error: String? = null,
    val content: String? = null,
    val count: Int = 0,
    val highlights: List<List<RegexHighlight>> = emptyList(),
)

/** No user-authored JavaScript: all operands are JSON data to these fixed operations. */
internal fun regexOperationsScript(request: RegexOperationsRequest): String {
    val input = buildJsonObject {
        put("operation", request.operation.name)
        put("query", request.query)
        put("caseSensitive", request.caseSensitive)
        put("wholeWord", request.wholeWord)
        put("useRegex", request.useRegex)
        put("text", request.text)
        put("replacement", request.replacement)
        put("texts", JsonArray(request.texts.map(::JsonPrimitive)))
    }
    return """
        (function(input) {
          try {
            if (input.operation === 'VALIDATE') {
              new RegExp(input.query);
              return JSON.stringify({});
            }
            let pattern = input.useRegex ? input.query : input.query.replace(/[.*+?^${'$'}{}()|[\]\\]/g, "\\${'$'}&");
            if (input.wholeWord) pattern = '\\b' + pattern + '\\b';
            // Web buildHighlightRegex deliberately adds this first capture. Preserve ${'$'}1.
            const re = new RegExp('(' + pattern + ')', input.caseSensitive ? 'g' : 'gi');
            if (input.operation === 'REPLACE') {
              const count = (input.text.match(re) || []).length;
              re.lastIndex = 0;
              return JSON.stringify({count, content: input.text.replace(re, input.replacement)});
            }
            const highlights = input.texts.map(text => {
              const ranges = [];
              re.lastIndex = 0;
              let match;
              while ((match = re.exec(text)) !== null) {
                if (match[0].length) ranges.push([match.index, match.index + match[0].length]);
                else re.lastIndex++; // Non-unicode JS regex advances one UTF-16 unit.
              }
              return ranges;
            });
            return JSON.stringify({highlights});
          } catch (error) {
            return JSON.stringify({error: String(error.message || error)});
          }
        })($input)
    """.trimIndent()
}

internal fun parseRegexOperations(json: String): RegexOperationsResult {
    val data = Json.parseToJsonElement(json).jsonObject
    return RegexOperationsResult(
        error = data["error"]?.jsonPrimitive?.contentOrNull,
        content = data["content"]?.jsonPrimitive?.contentOrNull,
        count = data["count"]?.jsonPrimitive?.int ?: 0,
        highlights = data["highlights"]?.jsonArray?.map { row -> row.jsonArray.map { range ->
            RegexHighlight(range.jsonArray[0].jsonPrimitive.int, range.jsonArray[1].jsonPrimitive.int)
        } }.orEmpty(),
    )
}
