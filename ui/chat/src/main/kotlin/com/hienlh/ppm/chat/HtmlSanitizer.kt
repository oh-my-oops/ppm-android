package com.hienlh.ppm.chat

import java.math.BigDecimal
import java.math.BigInteger

/** One entry of the schema's allow-list: a property, and when [values] is not empty, the only values it may take. */
internal class HtmlAllowed(val property: String, val values: List<Any> = emptyList())

/** How hast-util-to-html and hastscript treat a property (property-information's `Info`). */
internal class HtmlProperty(val property: String, val attribute: String, val flags: Int) {
    fun has(flag: Int) = flags and flag != 0

    companion object {
        const val BOOLEAN = 1
        const val OVERLOADED_BOOLEAN = 2
        const val NUMBER = 4
        const val SPACE_SEPARATED = 8
        const val COMMA_SEPARATED = 16
        const val COMMA_OR_SPACE_SEPARATED = 32
    }
}

/** A hast property value: hastscript never makes `false`, and lists hold only texts and numbers. */
internal sealed interface HtmlValue {
    data class Text(val value: String) : HtmlValue
    data class Number(val value: Double) : HtmlValue
    data object True : HtmlValue
    data class Many(val items: List<HtmlValue>) : HtmlValue
}

/** What the web keeps of raw HTML: elements the schema allows, with only the properties it allows, and text. */
internal sealed interface SafeHtml {
    data class Text(val text: String) : SafeHtml
    data class Element(val name: String, val properties: Map<String, HtmlValue>, val children: List<SafeHtml>) : SafeHtml
}

/** rehype-sanitize with the web's `markdownSanitizeSchema`, over the tree hast-util-from-parse5 builds from [nodes]. */
internal fun sanitizeHtml(nodes: List<HtmlNode>): List<SafeHtml> = HtmlSanitizer().children(nodes)

private class HtmlSanitizer {
    /** Names of the elements being sanitized, outermost first, kept or not. */
    private val stack = ArrayList<String>()

    fun children(nodes: List<HtmlNode>): List<SafeHtml> {
        val results = ArrayList<SafeHtml>()
        for (node in nodes) {
            when (node) {
                is HtmlText -> results.add(SafeHtml.Text(node.text))
                is HtmlElement -> element(node)?.let(results::addAll)
                else -> Unit
            }
        }
        return results
    }

    private fun element(node: HtmlElement): List<SafeHtml>? {
        val hast = hastElement(node)
        val name = hast.name
        stack.add(name)
        // hastscript moves a `template`'s children aside as its content, which nothing sanitizes. That
        // includes an SVG or MathML `template`, or `<template#x>`: there the web throws instead
        // (hast-util-from-parse5 reads a content parse5 never made), so the app drops them like hastscript.
        val content = if (name == "template") emptyList() else children(node.children)
        val properties = properties(name, hast.properties)
        stack.removeAt(stack.size - 1)
        var safe = name.isNotEmpty() && name != "*" && name in HtmlSchema.tagNames
        if (safe) HtmlSchema.ancestors[name]?.let { ancestors -> safe = ancestors.any { it in stack } }
        if (!safe) return if (name in HtmlSchema.strip) null else content
        return listOf(SafeHtml.Element(name, properties, content))
    }

    private fun properties(tagName: String, properties: Map<String, HtmlValue>): Map<String, HtmlValue> {
        val specific = HtmlSchema.attributes[tagName]
        val defaults = HtmlSchema.attributes["*"]
        val result = LinkedHashMap<String, HtmlValue>()
        for ((key, unsafe) in properties) {
            val safe = propertyValue(findDefinition(specific, key), key, unsafe)
                ?: propertyValue(findDefinition(defaults, key), key, unsafe)
            if (safe != null) result[key] = safe
        }
        HtmlSchema.required[tagName]?.forEach { (key, value) -> if (key !in result) result[key] = value }
        return result
    }

    private fun findDefinition(definitions: List<HtmlAllowed>?, key: String): HtmlAllowed? {
        var dataDefault: HtmlAllowed? = null
        definitions?.forEach { entry ->
            if (entry.property == key) return entry
            if (entry.property == "data*") dataDefault = entry
        }
        return if (key.length > 4 && key.substring(0, 4).lowercase() == "data") dataDefault else null
    }

    private fun propertyValue(definition: HtmlAllowed?, key: String, value: HtmlValue): HtmlValue? = when {
        definition == null -> null
        value is HtmlValue.Many -> HtmlValue.Many(value.items.mapNotNull { primitive(definition, key, it) })
        else -> primitive(definition, key, value)
    }

    private fun primitive(definition: HtmlAllowed, key: String, value: HtmlValue): HtmlValue? {
        if (value is HtmlValue.Many) return null
        if (!safeProtocol(key, jsString(value))) return null
        if (definition.values.isNotEmpty()) {
            val ok = definition.values.any { allowed ->
                when (allowed) {
                    is Regex -> allowed.containsMatchIn(jsString(value))
                    is String -> value is HtmlValue.Text && value.value == allowed
                    true -> value == HtmlValue.True
                    else -> false
                }
            }
            if (!ok) return null
        }
        return if (key in HtmlSchema.clobber) HtmlValue.Text(HtmlSchema.CLOBBER_PREFIX + jsString(value)) else value
    }

    private fun safeProtocol(key: String, url: String): Boolean {
        val protocols = HtmlSchema.protocols[key]
        if (protocols.isNullOrEmpty()) return true
        val colon = url.indexOf(':')
        val questionMark = url.indexOf('?')
        val numberSign = url.indexOf('#')
        val slash = url.indexOf('/')
        if (colon < 0 || (slash > -1 && colon > slash) || (questionMark > -1 && colon > questionMark) ||
            (numberSign > -1 && colon > numberSign)
        ) return true
        return protocols.any { colon == it.length && url.startsWith(it) }
    }
}

private class HastElement(val name: String, val properties: Map<String, HtmlValue>)

/** The element hast-util-from-parse5 makes of [node]: hastscript reads its name as a selector and types its attributes. */
private fun hastElement(node: HtmlElement): HastElement {
    val svg = node.namespace == HtmlNamespace.SVG
    val propertyOf = if (svg) HtmlSchema.svgPropertyOf else HtmlSchema.propertyOf
    val infos = if (svg) HtmlSchema.svgProperties else HtmlSchema.properties
    val properties = LinkedHashMap<String, HtmlValue>()
    // `hast-util-parse-selector`: `b#id.class` names a `b`.
    var tagName: String? = null
    var previous: Char? = null
    var start = 0
    val selector = node.name
    while (start < selector.length) {
        var match = start
        while (match < selector.length && selector[match] != '#' && selector[match] != '.') match++
        val part = selector.substring(start, match)
        if (part.isNotEmpty()) {
            when (previous) {
                null -> tagName = part
                '#' -> properties["id"] = HtmlValue.Text(part)
                else -> {
                    val classes = (properties["className"] as? HtmlValue.Many)?.items.orEmpty()
                    properties["className"] = HtmlValue.Many(classes + HtmlValue.Text(part))
                }
            }
            start += part.length
        }
        if (match < selector.length) {
            previous = selector[match]
            start++
        }
    }
    for (attr in node.attrs) {
        // Attributes no allowed property can come from are dropped by the sanitizer whatever they hold.
        val info = infos[propertyOf[attr.name] ?: continue]!!
        var result = typedValue(info, attr.value)
        val classes = properties["className"]
        if (info.property == "className" && classes is HtmlValue.Many) result = HtmlValue.Many(classes.items + (result as HtmlValue.Many).items)
        properties[info.property] = result
    }
    return HastElement((tagName ?: "div").lowercase(), properties)
}

/** hastscript's `addProperty` for an attribute's text. */
private fun typedValue(info: HtmlProperty, value: String): HtmlValue {
    val list = when {
        info.has(HtmlProperty.SPACE_SEPARATED) -> parseSpaces(value)
        info.has(HtmlProperty.COMMA_SEPARATED) -> parseCommas(value)
        info.has(HtmlProperty.COMMA_OR_SPACE_SEPARATED) -> parseSpaces(parseCommas(value).joinToString(" "))
        else -> return parsePrimitive(info, value)
    }
    return HtmlValue.Many(list.map { parsePrimitive(info, it) })
}

private fun parsePrimitive(info: HtmlProperty, value: String): HtmlValue {
    if (info.has(HtmlProperty.NUMBER) && value.isNotEmpty()) {
        val number = jsNumber(value)
        if (!number.isNaN()) return HtmlValue.Number(number)
    }
    if ((info.has(HtmlProperty.BOOLEAN) || info.has(HtmlProperty.OVERLOADED_BOOLEAN)) &&
        (value.isEmpty() || value.lowercase() == info.property.lowercase())
    ) return HtmlValue.True
    return HtmlValue.Text(value)
}

/** space-separated-tokens' `parse`. */
private fun parseSpaces(value: String): List<String> {
    val input = value.jsTrim()
    return if (input.isEmpty()) emptyList() else input.split(ASCII_WHITESPACE_RUN)
}

/** comma-separated-tokens' `parse`: an empty last token is dropped, empty inner ones are kept. */
private fun parseCommas(value: String): List<String> {
    val tokens = ArrayList<String>()
    var start = 0
    while (true) {
        val index = value.indexOf(',', start)
        val end = index == -1
        val token = value.substring(start, if (end) value.length else index).jsTrim()
        if (token.isNotEmpty() || !end) tokens.add(token)
        if (end) return tokens
        start = index + 1
    }
}

private val ASCII_WHITESPACE_RUN = Regex("[ \t\n\r\u000C]+")

/** JavaScript's `String.prototype.trim`: its whitespace is not Kotlin's (U+FEFF yes, U+001C–U+001F no). */
internal fun String.jsTrim(): String = trim { it.isJsWhitespace() }

private fun Char.isJsWhitespace() = this == '\t' || this == '\n' || this == '\u000B' || this == '\u000C' || this == '\r' ||
    this == '﻿' || this == ' ' || this == ' ' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

/** JavaScript's `String(value)` for a property value. */
internal fun jsString(value: HtmlValue): String = when (value) {
    is HtmlValue.Text -> value.value
    is HtmlValue.Number -> jsNumberToString(value.value)
    HtmlValue.True -> "true"
    is HtmlValue.Many -> value.items.joinToString(",") { jsString(it) }
}

private val DECIMAL_LITERAL = Regex("[+-]?(?:Infinity|(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")
private val NON_DECIMAL_LITERAL = Regex("0([xXoObB])([0-9a-fA-F]+)")

/** JavaScript's `Number(text)`: NaN when the whole text is not one number, 0 when it is only whitespace. */
internal fun jsNumber(text: String): Double {
    val input = text.jsTrim()
    if (input.isEmpty()) return 0.0
    if (DECIMAL_LITERAL.matches(input)) {
        val negative = input.startsWith('-')
        return when (input.trimStart('+', '-')) {
            "Infinity" -> if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            else -> input.toDouble()
        }
    }
    val match = NON_DECIMAL_LITERAL.matchEntire(input) ?: return Double.NaN
    val radix = when (match.groupValues[1].lowercase()) {
        "x" -> 16
        "o" -> 8
        else -> 2
    }
    val digits = match.groupValues[2]
    if (digits.any { Character.digit(it, radix) < 0 }) return Double.NaN
    return BigInteger(digits, radix).toDouble()
}

/** JavaScript's `Number.prototype.toString()`: the shortest digits, in exponent form below 1e-6 or from 1e21. */
internal fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value == 0.0) return "0"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (value < 0) return "-" + jsNumberToString(-value)
    val decimal = BigDecimal(value.toString()).stripTrailingZeros()
    val digits = decimal.unscaledValue().toString()
    val k = digits.length
    val n = k - decimal.scale()
    return when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val exponent = n - 1
            val sign = if (exponent < 0) "-" else "+"
            val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
            "${mantissa}e$sign${kotlin.math.abs(exponent)}"
        }
    }
}
