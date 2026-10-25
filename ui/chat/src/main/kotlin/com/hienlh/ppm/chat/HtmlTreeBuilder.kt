package com.hienlh.ppm.chat

internal enum class HtmlNamespace { HTML, SVG, MATHML }

/** A node of the tree HTML's parser builds. Comments and doctypes are not kept: nothing reads them. */
internal sealed class HtmlNode {
    var parent: HtmlParent? = null
}

internal open class HtmlParent : HtmlNode() {
    val children = ArrayList<HtmlNode>()
}

internal class HtmlText(var text: String) : HtmlNode()

internal class HtmlAttr(val name: String, var value: String)

internal class HtmlElement(val name: String, val namespace: HtmlNamespace, val attrs: List<HtmlAttr>) : HtmlParent() {
    /** A `<template>`'s contents: a fragment of their own, which the web's sanitizer never sees. */
    var content: HtmlParent? = null
}

internal sealed class HtmlToken {
    class Tag(var name: String, val end: Boolean) : HtmlToken() {
        val attrs = ArrayList<HtmlAttr>()
        var selfClosing = false
    }

    /** A run of characters of one [kind], as parse5 splits them. */
    class Chars(val kind: Int, var text: String) : HtmlToken()
    data object Comment : HtmlToken()
    data object Doctype : HtmlToken()
    data object Eof : HtmlToken()

    companion object {
        const val NONE = 0
        const val CHARACTER = 1
        const val WHITESPACE = 2
        const val NULL = 3
    }
}

/** Parses [html] the way rehype-raw and rehype-parse do: a fragment in a `<template>` context, scripting off. */
internal fun parseHtmlFragment(html: String): List<HtmlNode> {
    val builder = HtmlTreeBuilder()
    builder.tokenizer.write(html)
    return builder.root.children
}

/**
 * HTML's tree construction, ported from parse5 7 for the modes a fragment parsed in a `<template>`
 * context can reach: in template, in body, text, the table modes and select. Function names follow
 * parse5's so the two can be read side by side; tag ids are compared as names, which parse5's ids
 * stand for one to one.
 */
internal class HtmlTreeBuilder {
    private enum class Mode {
        IN_BODY, TEXT, IN_TABLE, IN_TABLE_TEXT, IN_CAPTION, IN_COLUMN_GROUP, IN_TABLE_BODY, IN_ROW, IN_CELL,
        IN_SELECT, IN_SELECT_IN_TABLE, IN_TEMPLATE,
    }

    /** An active formatting element, or the marker that scopes them ([element] null). */
    private class Entry(var element: HtmlElement?, val token: HtmlToken.Tag?)

    val tokenizer = HtmlTokenizer(this)

    /** The fake `<html>` the fragment is parsed into; its children are the fragment. */
    val root = HtmlElement("html", HtmlNamespace.HTML, emptyList())
    private val context = HtmlElement("template", HtmlNamespace.HTML, emptyList())

    private val stack = ArrayList<HtmlElement>()
    private var tmplCount = 0
    private val formatting = ArrayList<Entry>()
    private var bookmark: Entry? = null
    private val tmplModes = ArrayList<Mode>()
    private var mode = Mode.IN_TEMPLATE
    private var originalMode = Mode.IN_TEMPLATE
    private var formElement: HtmlElement? = null
    private var fosterParenting = false
    private var skipNextNewLine = false
    private var currentNotInHtml = false
    private val pendingChars = ArrayList<HtmlToken.Chars>()
    private var pendingHasNonWhitespace = false

    init {
        tmplModes.add(0, Mode.IN_TEMPLATE)
        stack.add(root)
        resetInsertionMode()
    }

    private val current get() = stack.last()
    private val currentName get() = stack.last().name

    // ---- Open elements (parse5's OpenElementStack) ----

    private fun isInTemplate(element: HtmlElement) = element.name == "template" && element.namespace == HtmlNamespace.HTML

    private fun push(element: HtmlElement) {
        stack.add(element)
        if (isInTemplate(element)) tmplCount++
        if (stack.size > 1) setContextModes(element)
    }

    private fun pop() {
        val popped = stack.removeAt(stack.size - 1)
        if (tmplCount > 0 && isInTemplate(popped)) tmplCount--
        afterPop()
    }

    private fun afterPop() = setContextModes(if (stack.size == 1) context else current)

    private fun shortenToLength(length: Int) {
        while (stack.size > length) pop()
    }

    private fun remove(element: HtmlElement) {
        val index = stack.lastIndexOf(element)
        if (index < 0) return
        if (index == stack.size - 1) pop() else stack.removeAt(index)
    }

    private fun replace(old: HtmlElement, new: HtmlElement) {
        stack[stack.lastIndexOf(old)] = new
    }

    private fun insertAfter(reference: HtmlElement, element: HtmlElement) {
        val index = stack.lastIndexOf(reference) + 1
        stack.add(index, element)
        if (index == stack.size - 1) setContextModes(element)
    }

    private fun popUntilTagNamePopped(name: String) {
        var index = stack.size - 1
        while (index >= 0 && !(stack[index].name == name && stack[index].namespace == HtmlNamespace.HTML)) index--
        shortenToLength(maxOf(index, 0))
    }

    private fun popUntilElementPopped(element: HtmlElement) = shortenToLength(maxOf(stack.lastIndexOf(element), 0))

    private fun indexOfNames(names: Set<String>): Int {
        for (i in stack.indices.reversed()) if (stack[i].name in names && stack[i].namespace == HtmlNamespace.HTML) return i
        return -1
    }

    private fun popUntilPopped(names: Set<String>) = shortenToLength(maxOf(indexOfNames(names), 0))
    private fun clearBackTo(names: Set<String>) = shortenToLength(indexOfNames(names) + 1)
    private fun clearBackToTableContext() = clearBackTo(TABLE_CONTEXT)
    private fun clearBackToTableBodyContext() = clearBackTo(TABLE_BODY_CONTEXT)
    private fun clearBackToTableRowContext() = clearBackTo(TABLE_ROW_CONTEXT)

    private fun commonAncestor(element: HtmlElement): HtmlElement? = stack.getOrNull(stack.lastIndexOf(element) - 1)

    private fun hasInDynamicScope(name: String, htmlScope: Set<String>): Boolean {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            when (element.namespace) {
                HtmlNamespace.HTML -> {
                    if (element.name == name) return true
                    if (element.name in htmlScope) return false
                }
                HtmlNamespace.SVG -> if (element.name in SCOPING_SVG) return false
                HtmlNamespace.MATHML -> if (element.name in SCOPING_MATHML) return false
            }
        }
        return true
    }

    private fun hasInScope(name: String) = hasInDynamicScope(name, SCOPING_HTML)
    private fun hasInListItemScope(name: String) = hasInDynamicScope(name, SCOPING_HTML_LIST)
    private fun hasInButtonScope(name: String) = hasInDynamicScope(name, SCOPING_HTML_BUTTON)

    private fun hasNumberedHeaderInScope(): Boolean {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            when (element.namespace) {
                HtmlNamespace.HTML -> {
                    if (element.name in NUMBERED_HEADERS) return true
                    if (element.name in SCOPING_HTML) return false
                }
                HtmlNamespace.SVG -> if (element.name in SCOPING_SVG) return false
                HtmlNamespace.MATHML -> if (element.name in SCOPING_MATHML) return false
            }
        }
        return true
    }

    private fun hasInTableScope(name: String): Boolean {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            if (element.namespace != HtmlNamespace.HTML) continue
            when (element.name) {
                name -> return true
                "table", "html" -> return false
            }
        }
        return true
    }

    private fun hasTableBodyContextInTableScope(): Boolean {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            if (element.namespace != HtmlNamespace.HTML) continue
            when (element.name) {
                "tbody", "thead", "tfoot" -> return true
                "table", "html" -> return false
            }
        }
        return true
    }

    private fun hasInSelectScope(name: String): Boolean {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            if (element.namespace != HtmlNamespace.HTML) continue
            when (element.name) {
                name -> return true
                "option", "optgroup" -> Unit
                else -> return false
            }
        }
        return true
    }

    private fun generateImpliedEndTags() {
        while (currentName in IMPLICIT_END_TAG_REQUIRED) pop()
    }

    private fun generateImpliedEndTagsThoroughly() {
        while (currentName in IMPLICIT_END_TAG_REQUIRED_THOROUGHLY) pop()
    }

    private fun generateImpliedEndTagsWithExclusion(exclusion: String) {
        while (currentName != exclusion && currentName in IMPLICIT_END_TAG_REQUIRED_THOROUGHLY) pop()
    }

    // ---- Active formatting elements (parse5's FormattingElementList, newest first) ----

    private fun insertMarker() = formatting.add(0, MARKER)

    private fun pushFormatting(element: HtmlElement, token: HtmlToken.Tag) {
        ensureNoahArkCondition(element)
        formatting.add(0, Entry(element, token))
    }

    /** At most three entries alike (name, namespace, attributes) since the last marker. */
    private fun ensureNoahArkCondition(element: HtmlElement) {
        if (formatting.size < 3) return
        val candidates = ArrayList<Pair<Int, List<HtmlAttr>>>()
        for ((i, entry) in formatting.withIndex()) {
            val other = entry.element ?: break
            if (other.name == element.name && other.namespace == element.namespace && other.attrs.size == element.attrs.size) {
                candidates.add(i to other.attrs)
            }
        }
        if (candidates.size < 3) return
        val attrs = element.attrs.associate { it.name to it.value }
        var valid = 0
        for ((index, candidateAttrs) in candidates) {
            if (candidateAttrs.all { attrs[it.name] == it.value }) {
                valid++
                if (valid >= 3) formatting.removeAt(index)
            }
        }
    }

    private fun insertFormattingAfterBookmark(element: HtmlElement, token: HtmlToken.Tag) {
        val index = formatting.indexOf(bookmark)
        formatting.add(if (index >= 0) index else maxOf(formatting.size - 1, 0), Entry(element, token))
    }

    private fun removeEntry(entry: Entry) {
        formatting.remove(entry)
    }

    private fun clearToLastMarker() {
        val index = formatting.indexOf(MARKER)
        if (index < 0) formatting.clear() else repeat(index + 1) { formatting.removeAt(0) }
    }

    private fun entryInScopeWithTagName(name: String): Entry? =
        formatting.firstOrNull { it === MARKER || it.element!!.name == name }?.takeIf { it !== MARKER }

    private fun entryOf(element: HtmlElement): Entry? = formatting.firstOrNull { it.element === element }

    // ---- Insertion ----

    private fun templateContentOrNode(): HtmlParent = current.let { if (isInTemplate(it)) it.content!! else it }

    private fun shouldFosterParentOnInsertion() = fosterParenting && currentName in TABLE_STRUCTURE

    private fun fosterParentingLocation(): Pair<HtmlParent, HtmlNode?> {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            if (element.name == "template" && element.namespace == HtmlNamespace.HTML) return element.content!! to null
            if (element.name == "table") {
                val parent = element.parent
                return if (parent != null) parent to element else stack[i - 1] to null
            }
        }
        return stack[0] to null
    }

    private fun fosterParentElement(element: HtmlNode) {
        val (parent, before) = fosterParentingLocation()
        if (before != null) parent.insertBefore(element, before) else parent.append(element)
    }

    private fun attachElementToTree(element: HtmlElement) {
        if (shouldFosterParentOnInsertion()) fosterParentElement(element) else templateContentOrNode().append(element)
    }

    private fun createElement(token: HtmlToken.Tag, namespace: HtmlNamespace) = HtmlElement(token.name, namespace, token.attrs)

    private fun appendElement(token: HtmlToken.Tag, namespace: HtmlNamespace) = attachElementToTree(createElement(token, namespace))

    private fun insertElement(token: HtmlToken.Tag, namespace: HtmlNamespace) {
        val element = createElement(token, namespace)
        attachElementToTree(element)
        push(element)
    }

    private fun insertFakeElement(name: String) {
        val element = HtmlElement(name, HtmlNamespace.HTML, emptyList())
        attachElementToTree(element)
        push(element)
    }

    private fun insertTemplate(token: HtmlToken.Tag) {
        val element = createElement(token, HtmlNamespace.HTML)
        element.content = HtmlParent()
        attachElementToTree(element)
        push(element)
    }

    private fun insertCharacters(token: HtmlToken.Chars) {
        if (shouldFosterParentOnInsertion()) {
            val (parent, before) = fosterParentingLocation()
            if (before != null) parent.insertTextBefore(token.text, before) else parent.insertText(token.text)
        } else {
            templateContentOrNode().insertText(token.text)
        }
    }

    private fun reconstructActiveFormattingElements() {
        if (formatting.isEmpty()) return
        val end = formatting.indexOfFirst { it === MARKER || it.element in stack }
        val unopened = if (end == -1) formatting.size - 1 else end - 1
        for (i in unopened downTo 0) {
            val entry = formatting[i]
            insertElement(entry.token!!, entry.element!!.namespace)
            entry.element = current
        }
    }

    private fun closeTableCell() {
        generateImpliedEndTags()
        popUntilPopped(TABLE_CELLS)
        clearToLastMarker()
        mode = Mode.IN_ROW
    }

    private fun closePElement() {
        generateImpliedEndTagsWithExclusion("p")
        popUntilTagNamePopped("p")
    }

    private fun resetInsertionMode() {
        for (i in stack.indices.reversed()) {
            when (if (i == 0) context.name else stack[i].name) {
                "tr" -> mode = Mode.IN_ROW
                "tbody", "thead", "tfoot" -> mode = Mode.IN_TABLE_BODY
                "caption" -> mode = Mode.IN_CAPTION
                "colgroup" -> mode = Mode.IN_COLUMN_GROUP
                "table" -> mode = Mode.IN_TABLE
                "body" -> mode = Mode.IN_BODY
                "select" -> mode = selectMode(i)
                "template" -> mode = tmplModes[0]
                "td", "th" -> if (i > 0) mode = Mode.IN_CELL else continue
                else -> continue
            }
            return
        }
        mode = Mode.IN_BODY
    }

    private fun selectMode(selectIndex: Int): Mode {
        for (i in selectIndex - 1 downTo 1) {
            when (stack[i].name) {
                "template" -> break
                "table" -> return Mode.IN_SELECT_IN_TABLE
            }
        }
        return Mode.IN_SELECT
    }

    private fun isSpecial(element: HtmlElement) = when (element.namespace) {
        HtmlNamespace.HTML -> element.name in SPECIAL_HTML
        HtmlNamespace.SVG -> element.name in SCOPING_SVG
        HtmlNamespace.MATHML -> element.name in SCOPING_MATHML
    }

    // ---- Foreign content ----

    private fun setContextModes(element: HtmlElement) {
        val html = element.namespace == HtmlNamespace.HTML
        currentNotInHtml = !html
        tokenizer.inForeignNode = !html && !isIntegrationPoint(element, null)
    }

    private fun isIntegrationPoint(element: HtmlElement, foreign: HtmlNamespace?): Boolean {
        val htmlPoint = when (element.namespace) {
            HtmlNamespace.MATHML -> element.name == "annotation-xml" &&
                element.attrs.firstOrNull { it.name == "encoding" }?.value?.lowercase().let { it == "text/html" || it == "application/xhtml+xml" }
            HtmlNamespace.SVG -> element.name == "foreignObject" || element.name == "desc" || element.name == "title"
            HtmlNamespace.HTML -> false
        }
        val mathTextPoint = element.namespace == HtmlNamespace.MATHML && element.name in MATHML_TEXT_POINTS
        return ((foreign == null || foreign == HtmlNamespace.HTML) && htmlPoint) ||
            ((foreign == null || foreign == HtmlNamespace.MATHML) && mathTextPoint)
    }

    private fun adjustedCurrent() = if (stack.size == 1) context else current

    private fun shouldProcessStartTagInForeignContent(token: HtmlToken.Tag): Boolean {
        if (!currentNotInHtml) return false
        val element = adjustedCurrent()
        if (token.name == "svg" && element.name == "annotation-xml" && element.namespace == HtmlNamespace.MATHML) return false
        return tokenizer.inForeignNode ||
            ((token.name == "mglyph" || token.name == "malignmark") && !isIntegrationPoint(element, HtmlNamespace.HTML))
    }

    private fun popUntilHtmlOrIntegrationPoint() {
        while (current.namespace != HtmlNamespace.HTML && !isIntegrationPoint(current, null)) pop()
    }

    private fun startTagInForeignContent(token: HtmlToken.Tag) {
        val exits = token.name in EXITS_FOREIGN_CONTENT ||
            (token.name == "font" && token.attrs.any { it.name == "color" || it.name == "size" || it.name == "face" })
        if (exits) {
            popUntilHtmlOrIntegrationPoint()
            startTagOutsideForeignContent(token)
            return
        }
        val namespace = adjustedCurrent().namespace
        // The one SVG name adjustment parsing depends on: `foreignObject` is an integration point.
        if (namespace == HtmlNamespace.SVG && token.name == "foreignobject") token.name = "foreignObject"
        if (token.selfClosing) appendElement(token, namespace) else insertElement(token, namespace)
    }

    private fun endTagInForeignContent(token: HtmlToken.Tag) {
        if (token.name == "p" || token.name == "br") {
            popUntilHtmlOrIntegrationPoint()
            endTagOutsideForeignContent(token)
            return
        }
        for (i in stack.size - 1 downTo 1) {
            val element = stack[i]
            if (element.namespace == HtmlNamespace.HTML) {
                endTagOutsideForeignContent(token)
                return
            }
            if (element.name.lowercase() == token.name) {
                token.name = element.name
                shortenToLength(i)
                return
            }
        }
    }

    // ---- Token dispatch ----

    fun process(token: HtmlToken) {
        when (token) {
            is HtmlToken.Chars -> when (token.kind) {
                HtmlToken.CHARACTER -> onCharacter(token)
                HtmlToken.WHITESPACE -> onWhitespaceCharacter(token)
                else -> onNullCharacter(token)
            }
            is HtmlToken.Tag -> if (token.end) onEndTag(token) else onStartTag(token)
            HtmlToken.Comment -> onComment(token)
            HtmlToken.Doctype -> onDoctype(token)
            HtmlToken.Eof -> onEof(token)
        }
    }

    /** parse5's `_processToken`: a start tag re-enters without resetting the pending newline skip. */
    private fun processToken(token: HtmlToken) {
        if (token is HtmlToken.Tag && !token.end) processStartTag(token) else process(token)
    }

    private fun onCharacter(token: HtmlToken.Chars) {
        skipNextNewLine = false
        if (tokenizer.inForeignNode) {
            insertCharacters(token)
            return
        }
        when (mode) {
            Mode.IN_BODY, Mode.IN_CAPTION, Mode.IN_CELL, Mode.IN_TEMPLATE -> characterInBody(token)
            Mode.TEXT, Mode.IN_SELECT, Mode.IN_SELECT_IN_TABLE -> insertCharacters(token)
            Mode.IN_TABLE, Mode.IN_TABLE_BODY, Mode.IN_ROW -> characterInTable(token)
            Mode.IN_TABLE_TEXT -> characterInTableText(token)
            Mode.IN_COLUMN_GROUP -> tokenInColumnGroup(token)
        }
    }

    private fun onNullCharacter(token: HtmlToken.Chars) {
        skipNextNewLine = false
        if (tokenizer.inForeignNode) {
            token.text = "�"
            insertCharacters(token)
            return
        }
        when (mode) {
            Mode.TEXT -> insertCharacters(token)
            Mode.IN_TABLE, Mode.IN_TABLE_BODY, Mode.IN_ROW -> characterInTable(token)
            Mode.IN_COLUMN_GROUP -> tokenInColumnGroup(token)
            else -> Unit
        }
    }

    private fun onWhitespaceCharacter(token: HtmlToken.Chars) {
        if (skipNextNewLine) {
            skipNextNewLine = false
            if (token.text[0] == '\n') {
                if (token.text.length == 1) return
                token.text = token.text.substring(1)
            }
        }
        if (tokenizer.inForeignNode) {
            insertCharacters(token)
            return
        }
        when (mode) {
            Mode.TEXT, Mode.IN_COLUMN_GROUP, Mode.IN_SELECT, Mode.IN_SELECT_IN_TABLE -> insertCharacters(token)
            Mode.IN_BODY, Mode.IN_CAPTION, Mode.IN_CELL, Mode.IN_TEMPLATE -> whitespaceCharacterInBody(token)
            Mode.IN_TABLE, Mode.IN_TABLE_BODY, Mode.IN_ROW -> characterInTable(token)
            Mode.IN_TABLE_TEXT -> pendingChars.add(token)
        }
    }

    private fun onComment(token: HtmlToken) {
        skipNextNewLine = false
        if (!currentNotInHtml && mode == Mode.IN_TABLE_TEXT) tokenInTableText(token)
    }

    private fun onDoctype(token: HtmlToken) {
        skipNextNewLine = false
        if (mode == Mode.IN_TABLE_TEXT) tokenInTableText(token)
    }

    private fun onStartTag(token: HtmlToken.Tag) {
        skipNextNewLine = false
        processStartTag(token)
    }

    private fun processStartTag(token: HtmlToken.Tag) {
        if (shouldProcessStartTagInForeignContent(token)) startTagInForeignContent(token) else startTagOutsideForeignContent(token)
    }

    private fun startTagOutsideForeignContent(token: HtmlToken.Tag) {
        when (mode) {
            Mode.IN_BODY -> startTagInBody(token)
            Mode.IN_TABLE -> startTagInTable(token)
            Mode.IN_TABLE_TEXT -> tokenInTableText(token)
            Mode.IN_CAPTION -> startTagInCaption(token)
            Mode.IN_COLUMN_GROUP -> startTagInColumnGroup(token)
            Mode.IN_TABLE_BODY -> startTagInTableBody(token)
            Mode.IN_ROW -> startTagInRow(token)
            Mode.IN_CELL -> startTagInCell(token)
            Mode.IN_SELECT -> startTagInSelect(token)
            Mode.IN_SELECT_IN_TABLE -> startTagInSelectInTable(token)
            Mode.IN_TEMPLATE -> startTagInTemplate(token)
            Mode.TEXT -> Unit
        }
    }

    private fun onEndTag(token: HtmlToken.Tag) {
        skipNextNewLine = false
        if (currentNotInHtml) endTagInForeignContent(token) else endTagOutsideForeignContent(token)
    }

    private fun endTagOutsideForeignContent(token: HtmlToken.Tag) {
        when (mode) {
            Mode.IN_BODY -> endTagInBody(token)
            Mode.TEXT -> endTagInText()
            Mode.IN_TABLE -> endTagInTable(token)
            Mode.IN_TABLE_TEXT -> tokenInTableText(token)
            Mode.IN_CAPTION -> endTagInCaption(token)
            Mode.IN_COLUMN_GROUP -> endTagInColumnGroup(token)
            Mode.IN_TABLE_BODY -> endTagInTableBody(token)
            Mode.IN_ROW -> endTagInRow(token)
            Mode.IN_CELL -> endTagInCell(token)
            Mode.IN_SELECT -> endTagInSelect(token)
            Mode.IN_SELECT_IN_TABLE -> endTagInSelectInTable(token)
            Mode.IN_TEMPLATE -> if (token.name == "template") templateEndTag()
        }
    }

    private fun onEof(token: HtmlToken) {
        when (mode) {
            Mode.TEXT -> {
                pop()
                mode = originalMode
                onEof(token)
            }
            Mode.IN_TABLE_TEXT -> tokenInTableText(token)
            Mode.IN_TEMPLATE -> eofInTemplate(token)
            else -> eofInBody(token)
        }
    }

    // ---- In body ----

    private fun modeInBody(token: HtmlToken) {
        when (token) {
            is HtmlToken.Chars -> when (token.kind) {
                HtmlToken.CHARACTER -> characterInBody(token)
                HtmlToken.WHITESPACE -> whitespaceCharacterInBody(token)
                else -> Unit
            }
            is HtmlToken.Tag -> if (token.end) endTagInBody(token) else startTagInBody(token)
            HtmlToken.Eof -> eofInBody(token)
            else -> Unit
        }
    }

    private fun whitespaceCharacterInBody(token: HtmlToken.Chars) {
        reconstructActiveFormattingElements()
        insertCharacters(token)
    }

    private fun characterInBody(token: HtmlToken.Chars) {
        reconstructActiveFormattingElements()
        insertCharacters(token)
    }

    private fun closePInButtonScope() {
        if (hasInButtonScope("p")) closePElement()
    }

    private fun startTagInBody(token: HtmlToken.Tag) {
        when (token.name) {
            "i", "s", "b", "u", "em", "tt", "big", "code", "font", "small", "strike", "strong" -> {
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
                pushFormatting(current, token)
            }
            "a" -> {
                entryInScopeWithTagName("a")?.let { entry ->
                    callAdoptionAgency(token)
                    remove(entry.element!!)
                    removeEntry(entry)
                }
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
                pushFormatting(current, token)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                closePInButtonScope()
                if (currentName in NUMBERED_HEADERS) pop()
                insertElement(token, HtmlNamespace.HTML)
            }
            "p", "dl", "ol", "ul", "div", "dir", "nav", "main", "menu", "aside", "center", "figure", "footer", "header",
            "hgroup", "dialog", "details", "address", "article", "search", "section", "summary", "fieldset", "blockquote",
            "figcaption" -> {
                closePInButtonScope()
                insertElement(token, HtmlNamespace.HTML)
            }
            "li", "dd", "dt" -> listItemStartTagInBody(token)
            "br", "img", "wbr", "area", "embed", "keygen" -> {
                reconstructActiveFormattingElements()
                appendElement(token, HtmlNamespace.HTML)
            }
            "hr" -> {
                closePInButtonScope()
                appendElement(token, HtmlNamespace.HTML)
            }
            "rb", "rtc" -> {
                if (hasInScope("ruby")) generateImpliedEndTags()
                insertElement(token, HtmlNamespace.HTML)
            }
            "rt", "rp" -> {
                if (hasInScope("ruby")) generateImpliedEndTagsWithExclusion("rtc")
                insertElement(token, HtmlNamespace.HTML)
            }
            "pre", "listing" -> {
                closePInButtonScope()
                insertElement(token, HtmlNamespace.HTML)
                skipNextNewLine = true
            }
            "xmp" -> {
                closePInButtonScope()
                reconstructActiveFormattingElements()
                switchToTextParsing(token, HtmlTokenizer.State.RAWTEXT)
            }
            "svg", "math" -> {
                reconstructActiveFormattingElements()
                val namespace = if (token.name == "svg") HtmlNamespace.SVG else HtmlNamespace.MATHML
                if (token.selfClosing) appendElement(token, namespace) else insertElement(token, namespace)
            }
            "base", "link", "meta", "style", "title", "script", "bgsound", "basefont", "template" -> startTagInHead(token)
            "form" -> {
                val inTemplate = tmplCount > 0
                if (formElement == null || inTemplate) {
                    closePInButtonScope()
                    insertElement(token, HtmlNamespace.HTML)
                    if (!inTemplate) formElement = current
                }
            }
            "nobr" -> {
                reconstructActiveFormattingElements()
                if (hasInScope("nobr")) {
                    callAdoptionAgency(token)
                    reconstructActiveFormattingElements()
                }
                insertElement(token, HtmlNamespace.HTML)
                pushFormatting(current, token)
            }
            "table" -> {
                closePInButtonScope()
                insertElement(token, HtmlNamespace.HTML)
                mode = Mode.IN_TABLE
            }
            "input" -> {
                reconstructActiveFormattingElements()
                appendElement(token, HtmlNamespace.HTML)
            }
            "param", "track", "source" -> appendElement(token, HtmlNamespace.HTML)
            "image" -> {
                token.name = "img"
                reconstructActiveFormattingElements()
                appendElement(token, HtmlNamespace.HTML)
            }
            "button" -> {
                if (hasInScope("button")) {
                    generateImpliedEndTags()
                    popUntilTagNamePopped("button")
                }
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
            }
            "applet", "object", "marquee" -> {
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
                insertMarker()
            }
            "iframe", "noembed", "noframes" -> switchToTextParsing(token, HtmlTokenizer.State.RAWTEXT)
            "select" -> {
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
                mode = if (mode == Mode.IN_TABLE || mode == Mode.IN_CAPTION || mode == Mode.IN_TABLE_BODY ||
                    mode == Mode.IN_ROW || mode == Mode.IN_CELL) Mode.IN_SELECT_IN_TABLE else Mode.IN_SELECT
            }
            "option", "optgroup" -> {
                if (currentName == "option") pop()
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
            }
            "textarea" -> {
                insertElement(token, HtmlNamespace.HTML)
                skipNextNewLine = true
                tokenizer.state = HtmlTokenizer.State.RCDATA
                originalMode = mode
                mode = Mode.TEXT
            }
            "plaintext" -> {
                closePInButtonScope()
                insertElement(token, HtmlNamespace.HTML)
                tokenizer.state = HtmlTokenizer.State.PLAINTEXT
            }
            // `<html>` and `<body>` only add attributes to elements a fragment does not have, and
            // `<frameset>` needs a body; table parts outside a table are dropped.
            "html", "body", "frameset", "col", "th", "td", "tr", "head", "frame", "tbody", "tfoot", "thead", "caption",
            "colgroup" -> Unit
            // `<noscript>` included: scripting is off, so it is an ordinary element.
            else -> {
                reconstructActiveFormattingElements()
                insertElement(token, HtmlNamespace.HTML)
            }
        }
    }

    private fun listItemStartTagInBody(token: HtmlToken.Tag) {
        for (i in stack.indices.reversed()) {
            val element = stack[i]
            val name = element.name
            if ((token.name == "li" && name == "li") || ((token.name == "dd" || token.name == "dt") && (name == "dd" || name == "dt"))) {
                generateImpliedEndTagsWithExclusion(name)
                popUntilTagNamePopped(name)
                break
            }
            if (name != "address" && name != "div" && name != "p" && isSpecial(element)) break
        }
        closePInButtonScope()
        insertElement(token, HtmlNamespace.HTML)
    }

    private fun switchToTextParsing(token: HtmlToken.Tag, state: HtmlTokenizer.State) {
        insertElement(token, HtmlNamespace.HTML)
        tokenizer.state = state
        originalMode = mode
        mode = Mode.TEXT
    }

    /** The head elements a fragment can meet: void ones, text ones and `<template>`. */
    private fun startTagInHead(token: HtmlToken.Tag) {
        when (token.name) {
            "base", "basefont", "bgsound", "link", "meta" -> appendElement(token, HtmlNamespace.HTML)
            "title" -> switchToTextParsing(token, HtmlTokenizer.State.RCDATA)
            "noframes", "style" -> switchToTextParsing(token, HtmlTokenizer.State.RAWTEXT)
            "script" -> switchToTextParsing(token, HtmlTokenizer.State.SCRIPT_DATA)
            "template" -> {
                insertTemplate(token)
                insertMarker()
                mode = Mode.IN_TEMPLATE
                tmplModes.add(0, Mode.IN_TEMPLATE)
            }
        }
    }

    private fun endTagInBody(token: HtmlToken.Tag) {
        when (val name = token.name) {
            "a", "b", "i", "s", "u", "em", "tt", "big", "code", "font", "nobr", "small", "strike", "strong" -> callAdoptionAgency(token)
            "p" -> {
                if (!hasInButtonScope("p")) insertFakeElement("p")
                closePElement()
            }
            "dl", "ul", "ol", "dir", "div", "nav", "pre", "main", "menu", "aside", "button", "center", "figure", "footer",
            "header", "hgroup", "dialog", "address", "article", "details", "search", "section", "summary", "listing",
            "fieldset", "blockquote", "figcaption" -> if (hasInScope(name)) {
                generateImpliedEndTags()
                popUntilTagNamePopped(name)
            }
            "li" -> if (hasInListItemScope("li")) {
                generateImpliedEndTagsWithExclusion("li")
                popUntilTagNamePopped("li")
            }
            "dd", "dt" -> if (hasInScope(name)) {
                generateImpliedEndTagsWithExclusion(name)
                popUntilTagNamePopped(name)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> if (hasNumberedHeaderInScope()) {
                generateImpliedEndTags()
                popUntilPopped(NUMBERED_HEADERS)
            }
            "br" -> {
                reconstructActiveFormattingElements()
                insertFakeElement("br")
                pop()
            }
            // A fragment has no body, so `</body>` and `</html>` are never in scope.
            "body", "html" -> Unit
            "form" -> formEndTagInBody()
            "applet", "object", "marquee" -> if (hasInScope(name)) {
                generateImpliedEndTags()
                popUntilTagNamePopped(name)
                clearToLastMarker()
            }
            "template" -> templateEndTag()
            else -> genericEndTagInBody(token)
        }
    }

    private fun formEndTagInBody() {
        val inTemplate = tmplCount > 0
        val form = formElement
        if (!inTemplate) formElement = null
        if ((form != null || inTemplate) && hasInScope("form")) {
            generateImpliedEndTags()
            if (inTemplate) popUntilTagNamePopped("form") else if (form != null) remove(form)
        }
    }

    private fun genericEndTagInBody(token: HtmlToken.Tag) {
        for (i in stack.size - 1 downTo 1) {
            val element = stack[i]
            if (element.name == token.name) {
                generateImpliedEndTagsWithExclusion(token.name)
                if (stack.size - 1 >= i) shortenToLength(i)
                break
            }
            if (isSpecial(element)) break
        }
    }

    private fun eofInBody(token: HtmlToken) {
        if (tmplModes.isNotEmpty()) eofInTemplate(token)
    }

    private fun endTagInText() {
        pop()
        mode = originalMode
    }

    // ---- Adoption agency ----

    private fun callAdoptionAgency(token: HtmlToken.Tag) {
        repeat(AA_OUTER_LOOP_ITER) {
            val formattingEntry = aaObtainFormattingElementEntry(token) ?: return
            val furthestBlock = aaObtainFurthestBlock(formattingEntry) ?: return
            bookmark = formattingEntry
            val lastElement = aaInnerLoop(furthestBlock, formattingEntry.element!!)
            val commonAncestor = commonAncestor(formattingEntry.element!!)
            lastElement.detach()
            if (commonAncestor != null) aaInsertLastNodeInCommonAncestor(commonAncestor, lastElement)
            aaReplaceFormattingElement(furthestBlock, formattingEntry)
        }
    }

    private fun aaObtainFormattingElementEntry(token: HtmlToken.Tag): Entry? {
        val entry = entryInScopeWithTagName(token.name)
        if (entry == null) {
            genericEndTagInBody(token)
            return null
        }
        if (entry.element !in stack) {
            removeEntry(entry)
            return null
        }
        return if (hasInScope(token.name)) entry else null
    }

    private fun aaObtainFurthestBlock(entry: Entry): HtmlElement? {
        var furthestBlock: HtmlElement? = null
        var index = stack.size - 1
        while (index >= 0) {
            val element = stack[index]
            if (element === entry.element) break
            if (isSpecial(element)) furthestBlock = element
            index--
        }
        if (furthestBlock == null) {
            shortenToLength(maxOf(index, 0))
            removeEntry(entry)
        }
        return furthestBlock
    }

    private fun aaInnerLoop(furthestBlock: HtmlElement, formattingElement: HtmlElement): HtmlElement {
        var lastElement = furthestBlock
        var next = commonAncestor(furthestBlock)
        var i = 0
        var element = next
        while (element !== formattingElement) {
            next = commonAncestor(element!!)
            val entry = entryOf(element)
            val overflow = entry != null && i >= AA_INNER_LOOP_ITER
            if (entry == null || overflow) {
                if (overflow) removeEntry(entry)
                remove(element)
            } else {
                val recreated = createElement(entry.token!!, entry.element!!.namespace)
                replace(entry.element!!, recreated)
                entry.element = recreated
                if (lastElement === furthestBlock) bookmark = entry
                lastElement.detach()
                recreated.append(lastElement)
                lastElement = recreated
            }
            i++
            element = next
        }
        return lastElement
    }

    private fun aaInsertLastNodeInCommonAncestor(commonAncestor: HtmlElement, lastElement: HtmlElement) {
        if (commonAncestor.name in TABLE_STRUCTURE) {
            fosterParentElement(lastElement)
        } else {
            (if (isInTemplate(commonAncestor)) commonAncestor.content!! else commonAncestor).append(lastElement)
        }
    }

    private fun aaReplaceFormattingElement(furthestBlock: HtmlElement, entry: Entry) {
        val element = createElement(entry.token!!, entry.element!!.namespace)
        for (child in furthestBlock.children.toList()) {
            child.detach()
            element.append(child)
        }
        furthestBlock.append(element)
        insertFormattingAfterBookmark(element, entry.token)
        removeEntry(entry)
        remove(entry.element!!)
        insertAfter(furthestBlock, element)
    }

    // ---- Tables ----

    private fun characterInTable(token: HtmlToken.Chars) {
        if (currentName in TABLE_STRUCTURE) {
            pendingChars.clear()
            pendingHasNonWhitespace = false
            originalMode = mode
            mode = Mode.IN_TABLE_TEXT
            when (token.kind) {
                HtmlToken.CHARACTER -> characterInTableText(token)
                HtmlToken.WHITESPACE -> pendingChars.add(token)
            }
        } else {
            tokenInTable(token)
        }
    }

    private fun characterInTableText(token: HtmlToken.Chars) {
        pendingChars.add(token)
        pendingHasNonWhitespace = true
    }

    private fun tokenInTableText(token: HtmlToken) {
        if (pendingHasNonWhitespace) pendingChars.forEach(::tokenInTable) else pendingChars.forEach(::insertCharacters)
        mode = originalMode
        processToken(token)
    }

    private fun tokenInTable(token: HtmlToken) {
        val saved = fosterParenting
        fosterParenting = true
        modeInBody(token)
        fosterParenting = saved
    }

    private fun startTagInTable(token: HtmlToken.Tag) {
        when (token.name) {
            "td", "th", "tr" -> {
                clearBackToTableContext()
                insertFakeElement("tbody")
                mode = Mode.IN_TABLE_BODY
                startTagInTableBody(token)
            }
            "style", "script", "template" -> startTagInHead(token)
            "col" -> {
                clearBackToTableContext()
                insertFakeElement("colgroup")
                mode = Mode.IN_COLUMN_GROUP
                startTagInColumnGroup(token)
            }
            "form" -> if (formElement == null && tmplCount == 0) {
                insertElement(token, HtmlNamespace.HTML)
                formElement = current
                pop()
            }
            "table" -> if (hasInTableScope("table")) {
                popUntilTagNamePopped("table")
                resetInsertionMode()
                processStartTag(token)
            }
            "tbody", "tfoot", "thead" -> {
                clearBackToTableContext()
                insertElement(token, HtmlNamespace.HTML)
                mode = Mode.IN_TABLE_BODY
            }
            "input" -> {
                val type = token.attrs.firstOrNull { it.name == "type" }?.value
                if (type != null && type.lowercase() == "hidden") appendElement(token, HtmlNamespace.HTML) else tokenInTable(token)
            }
            "caption" -> {
                clearBackToTableContext()
                insertMarker()
                insertElement(token, HtmlNamespace.HTML)
                mode = Mode.IN_CAPTION
            }
            "colgroup" -> {
                clearBackToTableContext()
                insertElement(token, HtmlNamespace.HTML)
                mode = Mode.IN_COLUMN_GROUP
            }
            else -> tokenInTable(token)
        }
    }

    private fun endTagInTable(token: HtmlToken.Tag) {
        when (token.name) {
            "table" -> if (hasInTableScope("table")) {
                popUntilTagNamePopped("table")
                resetInsertionMode()
            }
            "template" -> templateEndTag()
            "body", "caption", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> Unit
            else -> tokenInTable(token)
        }
    }

    private fun startTagInCaption(token: HtmlToken.Tag) {
        if (token.name in TABLE_VOID_ELEMENTS) {
            if (hasInTableScope("caption")) {
                generateImpliedEndTags()
                popUntilTagNamePopped("caption")
                clearToLastMarker()
                mode = Mode.IN_TABLE
                startTagInTable(token)
            }
        } else {
            startTagInBody(token)
        }
    }

    private fun endTagInCaption(token: HtmlToken.Tag) {
        when (token.name) {
            "caption", "table" -> if (hasInTableScope("caption")) {
                generateImpliedEndTags()
                popUntilTagNamePopped("caption")
                clearToLastMarker()
                mode = Mode.IN_TABLE
                if (token.name == "table") endTagInTable(token)
            }
            "body", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> Unit
            else -> endTagInBody(token)
        }
    }

    private fun startTagInColumnGroup(token: HtmlToken.Tag) {
        when (token.name) {
            "html" -> startTagInBody(token)
            "col" -> appendElement(token, HtmlNamespace.HTML)
            "template" -> startTagInHead(token)
            else -> tokenInColumnGroup(token)
        }
    }

    private fun endTagInColumnGroup(token: HtmlToken.Tag) {
        when (token.name) {
            "colgroup" -> if (currentName == "colgroup") {
                pop()
                mode = Mode.IN_TABLE
            }
            "template" -> templateEndTag()
            "col" -> Unit
            else -> tokenInColumnGroup(token)
        }
    }

    private fun tokenInColumnGroup(token: HtmlToken) {
        if (currentName == "colgroup") {
            pop()
            mode = Mode.IN_TABLE
            processToken(token)
        }
    }

    private fun startTagInTableBody(token: HtmlToken.Tag) {
        when (token.name) {
            "tr" -> {
                clearBackToTableBodyContext()
                insertElement(token, HtmlNamespace.HTML)
                mode = Mode.IN_ROW
            }
            "th", "td" -> {
                clearBackToTableBodyContext()
                insertFakeElement("tr")
                mode = Mode.IN_ROW
                startTagInRow(token)
            }
            "caption", "col", "colgroup", "tbody", "tfoot", "thead" -> if (hasTableBodyContextInTableScope()) {
                clearBackToTableBodyContext()
                pop()
                mode = Mode.IN_TABLE
                startTagInTable(token)
            }
            else -> startTagInTable(token)
        }
    }

    private fun endTagInTableBody(token: HtmlToken.Tag) {
        when (token.name) {
            "tbody", "tfoot", "thead" -> if (hasInTableScope(token.name)) {
                clearBackToTableBodyContext()
                pop()
                mode = Mode.IN_TABLE
            }
            "table" -> if (hasTableBodyContextInTableScope()) {
                clearBackToTableBodyContext()
                pop()
                mode = Mode.IN_TABLE
                endTagInTable(token)
            }
            "body", "caption", "col", "colgroup", "html", "td", "th", "tr" -> Unit
            else -> endTagInTable(token)
        }
    }

    private fun startTagInRow(token: HtmlToken.Tag) {
        when (token.name) {
            "th", "td" -> {
                clearBackToTableRowContext()
                insertElement(token, HtmlNamespace.HTML)
                mode = Mode.IN_CELL
                insertMarker()
            }
            "caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr" -> if (hasInTableScope("tr")) {
                clearBackToTableRowContext()
                pop()
                mode = Mode.IN_TABLE_BODY
                startTagInTableBody(token)
            }
            else -> startTagInTable(token)
        }
    }

    private fun endTagInRow(token: HtmlToken.Tag) {
        when (token.name) {
            "tr" -> if (hasInTableScope("tr")) {
                clearBackToTableRowContext()
                pop()
                mode = Mode.IN_TABLE_BODY
            }
            "table" -> if (hasInTableScope("tr")) {
                clearBackToTableRowContext()
                pop()
                mode = Mode.IN_TABLE_BODY
                endTagInTableBody(token)
            }
            "tbody", "tfoot", "thead" -> if (hasInTableScope(token.name) || hasInTableScope("tr")) {
                clearBackToTableRowContext()
                pop()
                mode = Mode.IN_TABLE_BODY
                endTagInTableBody(token)
            }
            "body", "caption", "col", "colgroup", "html", "td", "th" -> Unit
            else -> endTagInTable(token)
        }
    }

    private fun startTagInCell(token: HtmlToken.Tag) {
        if (token.name in TABLE_VOID_ELEMENTS) {
            if (hasInTableScope("td") || hasInTableScope("th")) {
                closeTableCell()
                startTagInRow(token)
            }
        } else {
            startTagInBody(token)
        }
    }

    private fun endTagInCell(token: HtmlToken.Tag) {
        when (token.name) {
            "td", "th" -> if (hasInTableScope(token.name)) {
                generateImpliedEndTags()
                popUntilTagNamePopped(token.name)
                clearToLastMarker()
                mode = Mode.IN_ROW
            }
            "table", "tbody", "tfoot", "thead", "tr" -> if (hasInTableScope(token.name)) {
                closeTableCell()
                endTagInRow(token)
            }
            "body", "caption", "col", "colgroup", "html" -> Unit
            else -> endTagInBody(token)
        }
    }

    // ---- Select ----

    private fun startTagInSelect(token: HtmlToken.Tag) {
        when (token.name) {
            "html" -> startTagInBody(token)
            "option" -> {
                if (currentName == "option") pop()
                insertElement(token, HtmlNamespace.HTML)
            }
            "optgroup" -> {
                if (currentName == "option") pop()
                if (currentName == "optgroup") pop()
                insertElement(token, HtmlNamespace.HTML)
            }
            "hr" -> {
                if (currentName == "option") pop()
                if (currentName == "optgroup") pop()
                appendElement(token, HtmlNamespace.HTML)
            }
            "input", "keygen", "textarea", "select" -> if (hasInSelectScope("select")) {
                popUntilTagNamePopped("select")
                resetInsertionMode()
                if (token.name != "select") processStartTag(token)
            }
            "script", "template" -> startTagInHead(token)
        }
    }

    private fun endTagInSelect(token: HtmlToken.Tag) {
        when (token.name) {
            "optgroup" -> {
                if (stack.size > 1 && currentName == "option" && stack[stack.size - 2].name == "optgroup") pop()
                if (currentName == "optgroup") pop()
            }
            "option" -> if (currentName == "option") pop()
            "select" -> if (hasInSelectScope("select")) {
                popUntilTagNamePopped("select")
                resetInsertionMode()
            }
            "template" -> templateEndTag()
        }
    }

    private fun startTagInSelectInTable(token: HtmlToken.Tag) {
        if (token.name in SELECT_IN_TABLE_EXITS) {
            popUntilTagNamePopped("select")
            resetInsertionMode()
            processStartTag(token)
        } else {
            startTagInSelect(token)
        }
    }

    private fun endTagInSelectInTable(token: HtmlToken.Tag) {
        if (token.name in SELECT_IN_TABLE_EXITS) {
            if (hasInTableScope(token.name)) {
                popUntilTagNamePopped("select")
                resetInsertionMode()
                onEndTag(token)
            }
        } else {
            endTagInSelect(token)
        }
    }

    // ---- Templates ----

    private fun startTagInTemplate(token: HtmlToken.Tag) {
        val next = when (token.name) {
            "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title" -> {
                startTagInHead(token)
                return
            }
            "caption", "colgroup", "tbody", "tfoot", "thead" -> Mode.IN_TABLE
            "col" -> Mode.IN_COLUMN_GROUP
            "tr" -> Mode.IN_TABLE_BODY
            "td", "th" -> Mode.IN_ROW
            else -> Mode.IN_BODY
        }
        tmplModes[0] = next
        mode = next
        when (next) {
            Mode.IN_TABLE -> startTagInTable(token)
            Mode.IN_COLUMN_GROUP -> startTagInColumnGroup(token)
            Mode.IN_TABLE_BODY -> startTagInTableBody(token)
            Mode.IN_ROW -> startTagInRow(token)
            else -> startTagInBody(token)
        }
    }

    /** parse5's `templateEndTagInHead`: `</template>` closes only a template on the stack (never the context). */
    private fun templateEndTag() {
        if (tmplCount == 0) return
        generateImpliedEndTagsThoroughly()
        popUntilTagNamePopped("template")
        clearToLastMarker()
        tmplModes.removeAt(0)
        resetInsertionMode()
    }

    private fun eofInTemplate(token: HtmlToken) {
        if (tmplCount == 0) return
        popUntilTagNamePopped("template")
        clearToLastMarker()
        tmplModes.removeAt(0)
        resetInsertionMode()
        onEof(token)
    }

    private companion object {
        const val AA_OUTER_LOOP_ITER = 8
        const val AA_INNER_LOOP_ITER = 3
        val MARKER = Entry(null, null)

        val SPECIAL_HTML = setOf(
            "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote", "body", "br",
            "button", "caption", "center", "col", "colgroup", "dd", "details", "dir", "div", "dl", "dt", "embed", "fieldset",
            "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head",
            "header", "hgroup", "hr", "html", "iframe", "img", "input", "li", "link", "listing", "main", "marquee", "menu",
            "meta", "nav", "noembed", "noframes", "noscript", "object", "ol", "p", "param", "plaintext", "pre", "script",
            "section", "select", "source", "style", "summary", "table", "tbody", "td", "template", "textarea", "tfoot", "th",
            "thead", "title", "tr", "track", "ul", "wbr", "xmp",
        )
        val SCOPING_HTML = setOf("applet", "caption", "html", "marquee", "object", "table", "td", "template", "th")
        val SCOPING_HTML_LIST = SCOPING_HTML + setOf("ol", "ul")
        val SCOPING_HTML_BUTTON = SCOPING_HTML + "button"
        val SCOPING_MATHML = setOf("annotation-xml", "mi", "mn", "mo", "ms", "mtext")
        val SCOPING_SVG = setOf("desc", "foreignObject", "title")
        val MATHML_TEXT_POINTS = setOf("mi", "mo", "mn", "ms", "mtext")
        val NUMBERED_HEADERS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val IMPLICIT_END_TAG_REQUIRED = setOf("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc")
        val IMPLICIT_END_TAG_REQUIRED_THOROUGHLY =
            IMPLICIT_END_TAG_REQUIRED + setOf("caption", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")
        val TABLE_STRUCTURE = setOf("table", "tbody", "tfoot", "thead", "tr")
        val TABLE_CONTEXT = setOf("table", "template", "html")
        val TABLE_BODY_CONTEXT = setOf("tbody", "tfoot", "thead", "template", "html")
        val TABLE_ROW_CONTEXT = setOf("tr", "template", "html")
        val TABLE_CELLS = setOf("td", "th")
        val TABLE_VOID_ELEMENTS = setOf("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")
        val SELECT_IN_TABLE_EXITS = setOf("caption", "table", "tbody", "tfoot", "thead", "tr", "td", "th")
        val EXITS_FOREIGN_CONTENT = setOf(
            "b", "big", "blockquote", "body", "br", "center", "code", "dd", "div", "dl", "dt", "em", "embed", "h1", "h2",
            "h3", "h4", "h5", "h6", "head", "hr", "i", "img", "li", "listing", "menu", "meta", "nobr", "ol", "p", "pre",
            "ruby", "s", "small", "span", "strong", "strike", "sub", "sup", "table", "tt", "u", "ul", "var",
        )
    }
}

private fun HtmlParent.append(node: HtmlNode) {
    node.parent = this
    children.add(node)
}

private fun HtmlParent.insertBefore(node: HtmlNode, reference: HtmlNode) {
    node.parent = this
    children.add(children.indexOf(reference), node)
}

private fun HtmlNode.detach() {
    parent?.children?.remove(this)
    parent = null
}

private fun HtmlParent.insertText(text: String) {
    val last = children.lastOrNull()
    if (last is HtmlText) last.text += text else append(HtmlText(text))
}

private fun HtmlParent.insertTextBefore(text: String, reference: HtmlNode) {
    val previous = children.getOrNull(children.indexOf(reference) - 1)
    if (previous is HtmlText) previous.text += text else insertBefore(HtmlText(text), reference)
}
