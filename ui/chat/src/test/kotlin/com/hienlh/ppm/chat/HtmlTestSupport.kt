package com.hienlh.ppm.chat

/** A string as a test failure shows it: quoted, control characters escaped. */
internal fun quote(text: String) = buildString {
    append('"')
    for (char in text) {
        when {
            char == '"' || char == '\\' -> append('\\').append(char)
            char == '\n' -> append("\\n")
            char < ' ' || char in '\u007f'..' ' -> append("\\u%04x".format(char.code))
            else -> append(char)
        }
    }
    append('"')
}

/** hast-util-to-html with rehype-stringify's defaults, for the trees the sanitizer keeps. */
internal fun serializeHtml(nodes: List<SafeHtml>): String = nodes.joinToString("") { node ->
    when (node) {
        is SafeHtml.Text -> escape(node.text, "<&")
        is SafeHtml.Element -> {
            val attributes = node.properties.mapNotNull { (key, value) -> attribute(key, value) }.joinToString(" ")
            val content = serializeHtml(node.children)
            val void = node.name in VOIDS && content.isEmpty()
            "<${node.name}${if (attributes.isEmpty()) "" else " $attributes"}>$content${if (void) "" else "</${node.name}>"}"
        }
    }
}

private fun attribute(key: String, raw: HtmlValue): String? {
    val info = HtmlSchema.properties.getValue(key)
    var value: Any = raw
    val overloaded = info.has(HtmlProperty.OVERLOADED_BOOLEAN)
    val text = (raw as? HtmlValue.Text)?.value
    if (overloaded && (text == info.attribute || text == "")) {
        value = true
    } else if ((info.has(HtmlProperty.BOOLEAN) || overloaded) && (text == null || text == info.attribute || text == "")) {
        value = when (raw) {
            is HtmlValue.Text -> raw.value.isNotEmpty()
            is HtmlValue.Number -> raw.value != 0.0 && !raw.value.isNaN()
            else -> true
        }
    }
    if (value == false) return null
    if (value == true || value == HtmlValue.True) return info.attribute
    val string = when (raw) {
        is HtmlValue.Many -> {
            val items = raw.items.map(::jsString)
            if (info.has(HtmlProperty.COMMA_SEPARATED)) {
                (if (items.lastOrNull() == "") items + "" else items).joinToString(", ").trim()
            } else {
                items.joinToString(" ").trim()
            }
        }
        else -> jsString(raw)
    }
    return "${info.attribute}=\"${escape(string, "\u0000\"&'`")}\""
}

private fun escape(text: String, subset: String) = buildString {
    for (char in text) if (char in subset) append("&#x${char.code.toString(16).uppercase()};") else append(char)
}

private val VOIDS = setOf(
    "area", "base", "basefont", "bgsound", "br", "col", "command", "embed", "frame", "hr", "image", "img", "input",
    "keygen", "link", "meta", "param", "source", "track", "wbr",
)
