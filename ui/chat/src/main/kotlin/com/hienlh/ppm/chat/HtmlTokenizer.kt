package com.hienlh.ppm.chat

/**
 * HTML's tokenizer, as parse5 7 (what rehype-raw and rehype-parse run on the web) implements it:
 * the same states in the same order, so a tag, comment or character reference ends exactly where
 * the web's ends. It hands tokens to [HtmlTreeBuilder], which switches it into text states.
 *
 * Doctypes are the one simplification: every doctype state ends the token at `>`, and the tree
 * builder ignores doctypes outside table text, so they are read as one state.
 */
internal class HtmlTokenizer(private val builder: HtmlTreeBuilder) {
    enum class State {
        DATA, RCDATA, RAWTEXT, SCRIPT_DATA, PLAINTEXT, TAG_OPEN, END_TAG_OPEN, TAG_NAME,
        RCDATA_LESS_THAN_SIGN, RCDATA_END_TAG_OPEN, RCDATA_END_TAG_NAME,
        RAWTEXT_LESS_THAN_SIGN, RAWTEXT_END_TAG_OPEN, RAWTEXT_END_TAG_NAME,
        SCRIPT_DATA_LESS_THAN_SIGN, SCRIPT_DATA_END_TAG_OPEN, SCRIPT_DATA_END_TAG_NAME,
        SCRIPT_DATA_ESCAPE_START, SCRIPT_DATA_ESCAPE_START_DASH, SCRIPT_DATA_ESCAPED, SCRIPT_DATA_ESCAPED_DASH,
        SCRIPT_DATA_ESCAPED_DASH_DASH, SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN, SCRIPT_DATA_ESCAPED_END_TAG_OPEN,
        SCRIPT_DATA_ESCAPED_END_TAG_NAME, SCRIPT_DATA_DOUBLE_ESCAPE_START, SCRIPT_DATA_DOUBLE_ESCAPED,
        SCRIPT_DATA_DOUBLE_ESCAPED_DASH, SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH,
        SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN, SCRIPT_DATA_DOUBLE_ESCAPE_END,
        BEFORE_ATTRIBUTE_NAME, ATTRIBUTE_NAME, AFTER_ATTRIBUTE_NAME, BEFORE_ATTRIBUTE_VALUE,
        ATTRIBUTE_VALUE_DOUBLE_QUOTED, ATTRIBUTE_VALUE_SINGLE_QUOTED, ATTRIBUTE_VALUE_UNQUOTED,
        AFTER_ATTRIBUTE_VALUE_QUOTED, SELF_CLOSING_START_TAG, BOGUS_COMMENT, MARKUP_DECLARATION_OPEN,
        COMMENT_START, COMMENT_START_DASH, COMMENT, COMMENT_LESS_THAN_SIGN, COMMENT_LESS_THAN_SIGN_BANG,
        COMMENT_LESS_THAN_SIGN_BANG_DASH, COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH, COMMENT_END_DASH,
        COMMENT_END, COMMENT_END_BANG, DOCTYPE, CDATA_SECTION, CDATA_SECTION_BRACKET, CDATA_SECTION_END,
        CHARACTER_REFERENCE, AMBIGUOUS_AMPERSAND,
    }

    var state = State.DATA

    /** Set by the tree builder while the adjusted current node is foreign (SVG, MathML), for `<![CDATA[`. */
    var inForeignNode = false

    /** The last start tag emitted, which ends RCDATA, RAWTEXT and script data. */
    var lastStartTagName = ""

    private var html = ""
    private var pos = -1
    private var active = false
    private var returnState = State.DATA
    private var entityStart = 0

    private var charKind = HtmlToken.NONE
    private val chars = StringBuilder()

    private var tag: HtmlToken.Tag? = null
    private val attrName = StringBuilder()
    private val attrValue = StringBuilder()
    private var attr: HtmlAttr? = null

    /** Tokenizes the whole of [input], ending with the end-of-file token. */
    fun write(input: String) {
        // The input stream: CR LF and lone CR are newlines.
        html = input.replace("\r\n", "\n").replace('\r', '\n')
        pos = -1
        active = true
        while (active) callState(consume())
    }

    private fun consume(): Int {
        pos++
        return if (pos >= html.length) EOF else html[pos].code
    }

    private fun peek(offset: Int): Int = (pos + offset).let { if (it >= html.length) EOF else html[it].code }

    private fun startsWith(pattern: String, caseSensitive: Boolean): Boolean {
        if (pos + pattern.length > html.length) return false
        if (caseSensitive) return html.startsWith(pattern, pos)
        for (i in pattern.indices) if ((html[pos + i].code or 0x20) != pattern[i].code) return false
        return true
    }

    private fun consumeSequenceIfMatch(pattern: String, caseSensitive: Boolean): Boolean {
        if (!startsWith(pattern, caseSensitive)) return false
        pos += pattern.length - 1
        return true
    }

    // ---- Tokens ----

    private fun appendChars(kind: Int, cp: Int) {
        if (charKind != kind) {
            flushChars()
            charKind = kind
        }
        chars.appendCodePoint(cp)
    }

    private fun emitCodePoint(cp: Int) =
        appendChars(if (isWhitespace(cp)) HtmlToken.WHITESPACE else if (cp == 0) HtmlToken.NULL else HtmlToken.CHARACTER, cp)

    private fun emitChars(text: String) = text.codePoints().forEach { appendChars(HtmlToken.CHARACTER, it) }

    private fun flushChars() {
        if (charKind == HtmlToken.NONE) return
        val token = HtmlToken.Chars(charKind, chars.toString())
        chars.setLength(0)
        charKind = HtmlToken.NONE
        builder.process(token)
    }

    private fun createTag(end: Boolean) {
        tag = HtmlToken.Tag("", end)
    }

    private fun createAttr(first: String) {
        finishAttrValue()
        attrName.setLength(0)
        attrName.append(first)
        attr = null
    }

    /** The name is complete: the attribute joins the tag unless it repeats a name, which parse5 drops. */
    private fun leaveAttrName() {
        val name = attrName.toString()
        val token = tag!!
        attr = if (token.attrs.any { it.name == name }) null else HtmlAttr(name, "").also { token.attrs.add(it) }
    }

    private fun finishAttrValue() {
        attr?.value = attrValue.toString()
        attrValue.setLength(0)
    }

    private fun emitCurrentTag() {
        finishAttrValue()
        attr = null
        val token = tag!!
        tag = null
        flushChars()
        if (!token.end) lastStartTagName = token.name
        builder.process(token)
    }

    private fun emitComment() {
        flushChars()
        builder.process(HtmlToken.Comment)
    }

    private fun emitDoctype() {
        flushChars()
        builder.process(HtmlToken.Doctype)
    }

    private fun emitEof() {
        flushChars()
        builder.process(HtmlToken.Eof)
        active = false
    }

    // ---- States ----

    private fun callState(cp: Int) {
        when (state) {
            State.DATA -> stateData(cp)
            State.RCDATA -> stateRcdata(cp)
            State.RAWTEXT -> stateRawtext(cp)
            State.SCRIPT_DATA -> stateScriptData(cp)
            State.PLAINTEXT -> statePlaintext(cp)
            State.TAG_OPEN -> stateTagOpen(cp)
            State.END_TAG_OPEN -> stateEndTagOpen(cp)
            State.TAG_NAME -> stateTagName(cp)
            State.RCDATA_LESS_THAN_SIGN -> stateTextLessThanSign(cp, State.RCDATA_END_TAG_OPEN, State.RCDATA)
            State.RCDATA_END_TAG_OPEN -> stateTextEndTagOpen(cp, State.RCDATA_END_TAG_NAME, State.RCDATA)
            State.RCDATA_END_TAG_NAME -> stateTextEndTagName(cp, State.RCDATA)
            State.RAWTEXT_LESS_THAN_SIGN -> stateTextLessThanSign(cp, State.RAWTEXT_END_TAG_OPEN, State.RAWTEXT)
            State.RAWTEXT_END_TAG_OPEN -> stateTextEndTagOpen(cp, State.RAWTEXT_END_TAG_NAME, State.RAWTEXT)
            State.RAWTEXT_END_TAG_NAME -> stateTextEndTagName(cp, State.RAWTEXT)
            State.SCRIPT_DATA_LESS_THAN_SIGN -> stateScriptDataLessThanSign(cp)
            State.SCRIPT_DATA_END_TAG_OPEN -> stateTextEndTagOpen(cp, State.SCRIPT_DATA_END_TAG_NAME, State.SCRIPT_DATA)
            State.SCRIPT_DATA_END_TAG_NAME -> stateTextEndTagName(cp, State.SCRIPT_DATA)
            State.SCRIPT_DATA_ESCAPE_START -> stateScriptDataEscapeStart(cp, State.SCRIPT_DATA_ESCAPE_START_DASH)
            State.SCRIPT_DATA_ESCAPE_START_DASH -> stateScriptDataEscapeStart(cp, State.SCRIPT_DATA_ESCAPED_DASH_DASH)
            State.SCRIPT_DATA_ESCAPED -> stateScriptDataEscaped(cp)
            State.SCRIPT_DATA_ESCAPED_DASH -> stateScriptDataEscapedDash(cp)
            State.SCRIPT_DATA_ESCAPED_DASH_DASH -> stateScriptDataEscapedDashDash(cp)
            State.SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN -> stateScriptDataEscapedLessThanSign(cp)
            State.SCRIPT_DATA_ESCAPED_END_TAG_OPEN -> stateTextEndTagOpen(cp, State.SCRIPT_DATA_ESCAPED_END_TAG_NAME, State.SCRIPT_DATA_ESCAPED)
            State.SCRIPT_DATA_ESCAPED_END_TAG_NAME -> stateTextEndTagName(cp, State.SCRIPT_DATA_ESCAPED)
            State.SCRIPT_DATA_DOUBLE_ESCAPE_START -> stateScriptDataDoubleEscapeStart(cp)
            State.SCRIPT_DATA_DOUBLE_ESCAPED -> stateScriptDataDoubleEscaped(cp)
            State.SCRIPT_DATA_DOUBLE_ESCAPED_DASH -> stateScriptDataDoubleEscapedDash(cp)
            State.SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH -> stateScriptDataDoubleEscapedDashDash(cp)
            State.SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN -> stateScriptDataDoubleEscapedLessThanSign(cp)
            State.SCRIPT_DATA_DOUBLE_ESCAPE_END -> stateScriptDataDoubleEscapeEnd(cp)
            State.BEFORE_ATTRIBUTE_NAME -> stateBeforeAttributeName(cp)
            State.ATTRIBUTE_NAME -> stateAttributeName(cp)
            State.AFTER_ATTRIBUTE_NAME -> stateAfterAttributeName(cp)
            State.BEFORE_ATTRIBUTE_VALUE -> stateBeforeAttributeValue(cp)
            State.ATTRIBUTE_VALUE_DOUBLE_QUOTED -> stateAttributeValueQuoted(cp, '"'.code)
            State.ATTRIBUTE_VALUE_SINGLE_QUOTED -> stateAttributeValueQuoted(cp, '\''.code)
            State.ATTRIBUTE_VALUE_UNQUOTED -> stateAttributeValueUnquoted(cp)
            State.AFTER_ATTRIBUTE_VALUE_QUOTED -> stateAfterAttributeValueQuoted(cp)
            State.SELF_CLOSING_START_TAG -> stateSelfClosingStartTag(cp)
            State.BOGUS_COMMENT -> stateBogusComment(cp)
            State.MARKUP_DECLARATION_OPEN -> stateMarkupDeclarationOpen(cp)
            State.COMMENT_START -> stateCommentStart(cp)
            State.COMMENT_START_DASH -> stateCommentStartDash(cp)
            State.COMMENT -> stateComment(cp)
            State.COMMENT_LESS_THAN_SIGN -> stateCommentLessThanSign(cp)
            State.COMMENT_LESS_THAN_SIGN_BANG -> stateCommentLessThanSignBang(cp)
            State.COMMENT_LESS_THAN_SIGN_BANG_DASH -> stateCommentLessThanSignBangDash(cp)
            State.COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH -> stateCommentEnd(cp)
            State.COMMENT_END_DASH -> stateCommentEndDash(cp)
            State.COMMENT_END -> stateCommentEnd(cp)
            State.COMMENT_END_BANG -> stateCommentEndBang(cp)
            State.DOCTYPE -> stateDoctype(cp)
            State.CDATA_SECTION -> stateCdataSection(cp)
            State.CDATA_SECTION_BRACKET -> stateCdataSectionBracket(cp)
            State.CDATA_SECTION_END -> stateCdataSectionEnd(cp)
            State.CHARACTER_REFERENCE -> stateCharacterReference()
            State.AMBIGUOUS_AMPERSAND -> stateAmbiguousAmpersand(cp)
        }
    }

    private fun stateData(cp: Int) {
        when (cp) {
            '<'.code -> state = State.TAG_OPEN
            '&'.code -> startCharacterReference()
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateRcdata(cp: Int) {
        when (cp) {
            '&'.code -> startCharacterReference()
            '<'.code -> state = State.RCDATA_LESS_THAN_SIGN
            0 -> emitChars(REPLACEMENT)
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateRawtext(cp: Int) {
        when (cp) {
            '<'.code -> state = State.RAWTEXT_LESS_THAN_SIGN
            0 -> emitChars(REPLACEMENT)
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateScriptData(cp: Int) {
        when (cp) {
            '<'.code -> state = State.SCRIPT_DATA_LESS_THAN_SIGN
            0 -> emitChars(REPLACEMENT)
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun statePlaintext(cp: Int) {
        when (cp) {
            0 -> emitChars(REPLACEMENT)
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateTagOpen(cp: Int) {
        when {
            isAsciiLetter(cp) -> {
                createTag(end = false)
                state = State.TAG_NAME
                stateTagName(cp)
            }
            cp == '!'.code -> state = State.MARKUP_DECLARATION_OPEN
            cp == '/'.code -> state = State.END_TAG_OPEN
            cp == '?'.code -> {
                state = State.BOGUS_COMMENT
                stateBogusComment(cp)
            }
            cp == EOF -> {
                emitChars("<")
                emitEof()
            }
            else -> {
                emitChars("<")
                state = State.DATA
                stateData(cp)
            }
        }
    }

    private fun stateEndTagOpen(cp: Int) {
        when {
            isAsciiLetter(cp) -> {
                createTag(end = true)
                state = State.TAG_NAME
                stateTagName(cp)
            }
            cp == '>'.code -> state = State.DATA
            cp == EOF -> {
                emitChars("</")
                emitEof()
            }
            else -> {
                state = State.BOGUS_COMMENT
                stateBogusComment(cp)
            }
        }
    }

    private fun stateTagName(cp: Int) {
        val token = tag!!
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> state = State.BEFORE_ATTRIBUTE_NAME
            '/'.code -> state = State.SELF_CLOSING_START_TAG
            '>'.code -> {
                state = State.DATA
                emitCurrentTag()
            }
            0 -> token.name += REPLACEMENT
            EOF -> emitEof()
            else -> token.name += String(Character.toChars(toAsciiLower(cp)))
        }
    }

    private fun stateTextLessThanSign(cp: Int, endTagOpen: State, text: State) {
        if (cp == '/'.code) {
            state = endTagOpen
        } else {
            emitChars("<")
            state = text
            callState(cp)
        }
    }

    private fun stateTextEndTagOpen(cp: Int, endTagName: State, text: State) {
        if (isAsciiLetter(cp)) {
            state = endTagName
            callState(cp)
        } else {
            emitChars("</")
            state = text
            callState(cp)
        }
    }

    /** An end tag in text only counts when it names the element the text is in. */
    private fun stateTextEndTagName(cp: Int, text: State) {
        if (handleSpecialEndTag()) {
            emitChars("</")
            state = text
            callState(cp)
        }
    }

    /** Ends the text when [lastStartTagName] follows; answers whether it did not. */
    private fun handleSpecialEndTag(): Boolean {
        if (!startsWith(lastStartTagName, false)) return true
        createTag(end = true)
        tag!!.name = lastStartTagName
        when (peek(lastStartTagName.length)) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> {
                pos += lastStartTagName.length
                state = State.BEFORE_ATTRIBUTE_NAME
                return false
            }
            '/'.code -> {
                pos += lastStartTagName.length
                state = State.SELF_CLOSING_START_TAG
                return false
            }
            '>'.code -> {
                pos += lastStartTagName.length
                emitCurrentTag()
                state = State.DATA
                return false
            }
            else -> return true
        }
    }

    private fun stateScriptDataLessThanSign(cp: Int) {
        when (cp) {
            '/'.code -> state = State.SCRIPT_DATA_END_TAG_OPEN
            '!'.code -> {
                state = State.SCRIPT_DATA_ESCAPE_START
                emitChars("<!")
            }
            else -> {
                emitChars("<")
                state = State.SCRIPT_DATA
                stateScriptData(cp)
            }
        }
    }

    private fun stateScriptDataEscapeStart(cp: Int, next: State) {
        if (cp == '-'.code) {
            state = next
            emitChars("-")
        } else {
            state = State.SCRIPT_DATA
            stateScriptData(cp)
        }
    }

    private fun stateScriptDataEscaped(cp: Int) {
        when (cp) {
            '-'.code -> {
                state = State.SCRIPT_DATA_ESCAPED_DASH
                emitChars("-")
            }
            '<'.code -> state = State.SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN
            0 -> emitChars(REPLACEMENT)
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateScriptDataEscapedDash(cp: Int) {
        when (cp) {
            '-'.code -> {
                state = State.SCRIPT_DATA_ESCAPED_DASH_DASH
                emitChars("-")
            }
            '<'.code -> state = State.SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN
            0 -> {
                state = State.SCRIPT_DATA_ESCAPED
                emitChars(REPLACEMENT)
            }
            EOF -> emitEof()
            else -> {
                state = State.SCRIPT_DATA_ESCAPED
                emitCodePoint(cp)
            }
        }
    }

    private fun stateScriptDataEscapedDashDash(cp: Int) {
        when (cp) {
            '-'.code -> emitChars("-")
            '<'.code -> state = State.SCRIPT_DATA_ESCAPED_LESS_THAN_SIGN
            '>'.code -> {
                state = State.SCRIPT_DATA
                emitChars(">")
            }
            0 -> {
                state = State.SCRIPT_DATA_ESCAPED
                emitChars(REPLACEMENT)
            }
            EOF -> emitEof()
            else -> {
                state = State.SCRIPT_DATA_ESCAPED
                emitCodePoint(cp)
            }
        }
    }

    private fun stateScriptDataEscapedLessThanSign(cp: Int) {
        when {
            cp == '/'.code -> state = State.SCRIPT_DATA_ESCAPED_END_TAG_OPEN
            isAsciiLetter(cp) -> {
                emitChars("<")
                state = State.SCRIPT_DATA_DOUBLE_ESCAPE_START
                stateScriptDataDoubleEscapeStart(cp)
            }
            else -> {
                emitChars("<")
                state = State.SCRIPT_DATA_ESCAPED
                stateScriptDataEscaped(cp)
            }
        }
    }

    /** `script` then whitespace, `/` or `>` switches between escaped and double-escaped script data. */
    private fun scriptWordFollows(): Boolean =
        startsWith("script", false) && peek(6).let { isWhitespace(it) || it == '/'.code || it == '>'.code }

    private fun stateScriptDataDoubleEscapeStart(cp: Int) {
        if (scriptWordFollows()) {
            emitCodePoint(cp)
            repeat(6) { emitCodePoint(consume()) }
            state = State.SCRIPT_DATA_DOUBLE_ESCAPED
        } else {
            state = State.SCRIPT_DATA_ESCAPED
            stateScriptDataEscaped(cp)
        }
    }

    private fun stateScriptDataDoubleEscaped(cp: Int) {
        when (cp) {
            '-'.code -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED_DASH
                emitChars("-")
            }
            '<'.code -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN
                emitChars("<")
            }
            0 -> emitChars(REPLACEMENT)
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateScriptDataDoubleEscapedDash(cp: Int) {
        when (cp) {
            '-'.code -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED_DASH_DASH
                emitChars("-")
            }
            '<'.code -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN
                emitChars("<")
            }
            0 -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED
                emitChars(REPLACEMENT)
            }
            EOF -> emitEof()
            else -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED
                emitCodePoint(cp)
            }
        }
    }

    private fun stateScriptDataDoubleEscapedDashDash(cp: Int) {
        when (cp) {
            '-'.code -> emitChars("-")
            '<'.code -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED_LESS_THAN_SIGN
                emitChars("<")
            }
            '>'.code -> {
                state = State.SCRIPT_DATA
                emitChars(">")
            }
            0 -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED
                emitChars(REPLACEMENT)
            }
            EOF -> emitEof()
            else -> {
                state = State.SCRIPT_DATA_DOUBLE_ESCAPED
                emitCodePoint(cp)
            }
        }
    }

    private fun stateScriptDataDoubleEscapedLessThanSign(cp: Int) {
        if (cp == '/'.code) {
            state = State.SCRIPT_DATA_DOUBLE_ESCAPE_END
            emitChars("/")
        } else {
            state = State.SCRIPT_DATA_DOUBLE_ESCAPED
            stateScriptDataDoubleEscaped(cp)
        }
    }

    private fun stateScriptDataDoubleEscapeEnd(cp: Int) {
        if (scriptWordFollows()) {
            emitCodePoint(cp)
            repeat(6) { emitCodePoint(consume()) }
            state = State.SCRIPT_DATA_ESCAPED
        } else {
            state = State.SCRIPT_DATA_DOUBLE_ESCAPED
            stateScriptDataDoubleEscaped(cp)
        }
    }

    private fun stateBeforeAttributeName(cp: Int) {
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> Unit
            '/'.code, '>'.code, EOF -> {
                state = State.AFTER_ATTRIBUTE_NAME
                stateAfterAttributeName(cp)
            }
            '='.code -> {
                createAttr("=")
                state = State.ATTRIBUTE_NAME
            }
            else -> {
                createAttr("")
                state = State.ATTRIBUTE_NAME
                stateAttributeName(cp)
            }
        }
    }

    private fun stateAttributeName(cp: Int) {
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C, '/'.code, '>'.code, EOF -> {
                leaveAttrName()
                state = State.AFTER_ATTRIBUTE_NAME
                stateAfterAttributeName(cp)
            }
            '='.code -> {
                leaveAttrName()
                state = State.BEFORE_ATTRIBUTE_VALUE
            }
            0 -> attrName.append(REPLACEMENT)
            else -> attrName.appendCodePoint(toAsciiLower(cp))
        }
    }

    private fun stateAfterAttributeName(cp: Int) {
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> Unit
            '/'.code -> state = State.SELF_CLOSING_START_TAG
            '='.code -> state = State.BEFORE_ATTRIBUTE_VALUE
            '>'.code -> {
                state = State.DATA
                emitCurrentTag()
            }
            EOF -> emitEof()
            else -> {
                createAttr("")
                state = State.ATTRIBUTE_NAME
                stateAttributeName(cp)
            }
        }
    }

    private fun stateBeforeAttributeValue(cp: Int) {
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> Unit
            '"'.code -> state = State.ATTRIBUTE_VALUE_DOUBLE_QUOTED
            '\''.code -> state = State.ATTRIBUTE_VALUE_SINGLE_QUOTED
            '>'.code -> {
                state = State.DATA
                emitCurrentTag()
            }
            else -> {
                state = State.ATTRIBUTE_VALUE_UNQUOTED
                stateAttributeValueUnquoted(cp)
            }
        }
    }

    private fun stateAttributeValueQuoted(cp: Int, quote: Int) {
        when (cp) {
            quote -> state = State.AFTER_ATTRIBUTE_VALUE_QUOTED
            '&'.code -> startCharacterReference()
            0 -> attrValue.append(REPLACEMENT)
            EOF -> emitEof()
            else -> attrValue.appendCodePoint(cp)
        }
    }

    private fun stateAttributeValueUnquoted(cp: Int) {
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> state = State.BEFORE_ATTRIBUTE_NAME
            '&'.code -> startCharacterReference()
            '>'.code -> {
                state = State.DATA
                emitCurrentTag()
            }
            0 -> attrValue.append(REPLACEMENT)
            EOF -> emitEof()
            else -> attrValue.appendCodePoint(cp)
        }
    }

    private fun stateAfterAttributeValueQuoted(cp: Int) {
        when (cp) {
            ' '.code, '\n'.code, '\t'.code, 0x0C -> state = State.BEFORE_ATTRIBUTE_NAME
            '/'.code -> state = State.SELF_CLOSING_START_TAG
            '>'.code -> {
                state = State.DATA
                emitCurrentTag()
            }
            EOF -> emitEof()
            else -> {
                state = State.BEFORE_ATTRIBUTE_NAME
                stateBeforeAttributeName(cp)
            }
        }
    }

    private fun stateSelfClosingStartTag(cp: Int) {
        when (cp) {
            '>'.code -> {
                tag!!.selfClosing = true
                state = State.DATA
                emitCurrentTag()
            }
            EOF -> emitEof()
            else -> {
                state = State.BEFORE_ATTRIBUTE_NAME
                stateBeforeAttributeName(cp)
            }
        }
    }

    // Comments: only where they end matters, as the web's sanitizer drops them.

    private fun stateBogusComment(cp: Int) {
        when (cp) {
            '>'.code -> {
                state = State.DATA
                emitComment()
            }
            EOF -> {
                emitComment()
                emitEof()
            }
        }
    }

    private fun stateMarkupDeclarationOpen(cp: Int) {
        when {
            consumeSequenceIfMatch("--", true) -> state = State.COMMENT_START
            consumeSequenceIfMatch("doctype", false) -> state = State.DOCTYPE
            consumeSequenceIfMatch("[CDATA[", true) -> state = if (inForeignNode) State.CDATA_SECTION else State.BOGUS_COMMENT
            else -> {
                state = State.BOGUS_COMMENT
                stateBogusComment(cp)
            }
        }
    }

    private fun stateCommentStart(cp: Int) {
        when (cp) {
            '-'.code -> state = State.COMMENT_START_DASH
            '>'.code -> {
                state = State.DATA
                emitComment()
            }
            else -> {
                state = State.COMMENT
                stateComment(cp)
            }
        }
    }

    private fun stateCommentStartDash(cp: Int) {
        when (cp) {
            '-'.code -> state = State.COMMENT_END
            '>'.code -> {
                state = State.DATA
                emitComment()
            }
            EOF -> {
                emitComment()
                emitEof()
            }
            else -> {
                state = State.COMMENT
                stateComment(cp)
            }
        }
    }

    private fun stateComment(cp: Int) {
        when (cp) {
            '-'.code -> state = State.COMMENT_END_DASH
            '<'.code -> state = State.COMMENT_LESS_THAN_SIGN
            EOF -> {
                emitComment()
                emitEof()
            }
        }
    }

    private fun stateCommentLessThanSign(cp: Int) {
        when (cp) {
            '!'.code -> state = State.COMMENT_LESS_THAN_SIGN_BANG
            '<'.code -> Unit
            else -> {
                state = State.COMMENT
                stateComment(cp)
            }
        }
    }

    private fun stateCommentLessThanSignBang(cp: Int) {
        if (cp == '-'.code) {
            state = State.COMMENT_LESS_THAN_SIGN_BANG_DASH
        } else {
            state = State.COMMENT
            stateComment(cp)
        }
    }

    private fun stateCommentLessThanSignBangDash(cp: Int) {
        if (cp == '-'.code) {
            state = State.COMMENT_LESS_THAN_SIGN_BANG_DASH_DASH
        } else {
            state = State.COMMENT_END_DASH
            stateCommentEndDash(cp)
        }
    }

    private fun stateCommentEndDash(cp: Int) {
        when (cp) {
            '-'.code -> state = State.COMMENT_END
            EOF -> {
                emitComment()
                emitEof()
            }
            else -> {
                state = State.COMMENT
                stateComment(cp)
            }
        }
    }

    private fun stateCommentEnd(cp: Int) {
        when (cp) {
            '>'.code -> {
                state = State.DATA
                emitComment()
            }
            '!'.code -> state = State.COMMENT_END_BANG
            '-'.code -> state = State.COMMENT_END
            EOF -> {
                emitComment()
                emitEof()
            }
            else -> {
                state = State.COMMENT
                stateComment(cp)
            }
        }
    }

    private fun stateCommentEndBang(cp: Int) {
        when (cp) {
            '-'.code -> state = State.COMMENT_END_DASH
            '>'.code -> {
                state = State.DATA
                emitComment()
            }
            EOF -> {
                emitComment()
                emitEof()
            }
            else -> {
                state = State.COMMENT
                stateComment(cp)
            }
        }
    }

    private fun stateDoctype(cp: Int) {
        when (cp) {
            '>'.code -> {
                state = State.DATA
                emitDoctype()
            }
            EOF -> {
                emitDoctype()
                emitEof()
            }
        }
    }

    private fun stateCdataSection(cp: Int) {
        when (cp) {
            ']'.code -> state = State.CDATA_SECTION_BRACKET
            EOF -> emitEof()
            else -> emitCodePoint(cp)
        }
    }

    private fun stateCdataSectionBracket(cp: Int) {
        if (cp == ']'.code) {
            state = State.CDATA_SECTION_END
        } else {
            emitChars("]")
            state = State.CDATA_SECTION
            stateCdataSection(cp)
        }
    }

    private fun stateCdataSectionEnd(cp: Int) {
        when (cp) {
            '>'.code -> state = State.DATA
            ']'.code -> emitChars("]")
            else -> {
                emitChars("]]")
                state = State.CDATA_SECTION
                stateCdataSection(cp)
            }
        }
    }

    // ---- Character references ----

    private fun startCharacterReference() {
        returnState = state
        state = State.CHARACTER_REFERENCE
        entityStart = pos
    }

    private fun inAttribute() = returnState == State.ATTRIBUTE_VALUE_DOUBLE_QUOTED ||
        returnState == State.ATTRIBUTE_VALUE_SINGLE_QUOTED || returnState == State.ATTRIBUTE_VALUE_UNQUOTED

    private fun flushReferenceCodePoint(cp: Int) {
        if (inAttribute()) attrValue.appendCodePoint(cp) else emitCodePoint(cp)
    }

    private fun stateCharacterReference() {
        val reference = decodeCharacterReference(html, entityStart + 1, inAttribute())
        if (reference == null) {
            pos = entityStart
            flushReferenceCodePoint('&'.code)
            state = if (!inAttribute() && isAsciiAlphaNumeric(peek(1))) State.AMBIGUOUS_AMPERSAND else returnState
        } else {
            pos = entityStart + reference.consumed - 1
            reference.text.codePoints().forEach(::flushReferenceCodePoint)
            state = returnState
        }
    }

    private fun stateAmbiguousAmpersand(cp: Int) {
        if (isAsciiAlphaNumeric(cp)) {
            flushReferenceCodePoint(cp)
        } else {
            state = returnState
            callState(cp)
        }
    }

    private companion object {
        const val EOF = -1
        const val REPLACEMENT = "�"

        fun isWhitespace(cp: Int) = cp == ' '.code || cp == '\n'.code || cp == '\t'.code || cp == 0x0C
        fun isAsciiLetter(cp: Int) = cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code
        fun isAsciiAlphaNumeric(cp: Int) = isAsciiLetter(cp) || cp in '0'.code..'9'.code
        fun toAsciiLower(cp: Int) = if (cp in 'A'.code..'Z'.code) cp + 32 else cp
    }
}

/** A character reference read as the web's parser reads it: [consumed] characters from its `&`. */
internal class CharacterReference(val consumed: Int, val text: String)

/**
 * Decodes the reference whose name or `#` starts at [start] (just after its `&`), as the `entities`
 * decoder parse5 uses does: the longest name in HTML's table, a legacy name (`&copy`) without its
 * `;`, except in an attribute where a name, digit or `=` follows, and numbers with HTML's fixes.
 * Null when nothing is decoded and the `&` stays text.
 */
internal fun decodeCharacterReference(input: String, start: Int, attribute: Boolean): CharacterReference? {
    if (start < input.length && input[start] == '#') return decodeNumericReference(input, start + 1)
    var legacy = -1
    var lastHasValue = false
    var i = start
    while (i < input.length) {
        val c = input[i]
        val name = input.substring(start, i + 1)
        if (name !in entityPrefixes) {
            val keep = legacy >= 0 && !(attribute && (!lastHasValue || c == '=' || c.isAsciiAlphaNumeric()))
            return if (keep) legacyReference(input, start, legacy) else null
        }
        val hasValue = if (c == ';') name.dropLast(1) in HtmlEntities.named else name in HtmlEntities.legacy
        if (hasValue) {
            if (c == ';') return CharacterReference(1 + name.length, HtmlEntities.named.getValue(name.dropLast(1)))
            legacy = name.length
        }
        lastHasValue = hasValue
        i++
    }
    return if (legacy >= 0 && (!attribute || legacy == i - start)) legacyReference(input, start, legacy) else null
}

/** Every prefix of a name in HTML's table followed by its `;`: how far the decoder walks before it gives up. */
private val entityPrefixes: Set<String> by lazy {
    HashSet<String>().apply { for (name in HtmlEntities.named.keys) for (end in 1..name.length + 1) add("$name;".substring(0, end)) }
}

private fun legacyReference(input: String, start: Int, length: Int) =
    CharacterReference(1 + length, HtmlEntities.named.getValue(input.substring(start, start + length)))

private fun decodeNumericReference(input: String, at: Int): CharacterReference? {
    var i = at
    var consumed = 2
    val hex = i < input.length && (input[i].code or 0x20) == 'x'.code
    if (hex) {
        i++
        consumed++
    }
    val digitsStart = i
    var value = 0L
    while (i < input.length) {
        val digit = asciiDigit(input[i], if (hex) 16 else 10) ?: break
        // Anything past U+10FFFF becomes U+FFFD, so the value can stop growing there.
        value = minOf(value * (if (hex) 16 else 10) + digit, 0x110000L)
        i++
    }
    if (i == digitsStart) return null
    consumed += i - digitsStart
    if (i < input.length && input[i] == ';') consumed++
    return CharacterReference(consumed, String(Character.toChars(replaceCodePoint(value.toInt()))))
}

private fun asciiDigit(c: Char, radix: Int): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> if (radix == 16) c - 'a' + 10 else null
    in 'A'..'F' -> if (radix == 16) c - 'A' + 10 else null
    else -> null
}

private fun Char.isAsciiAlphaNumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** HTML's fixes for numeric references: NUL, surrogates and too-large numbers, and Windows-1252's C1 row. */
private fun replaceCodePoint(cp: Int): Int = when {
    cp in 0xD800..0xDFFF || cp > 0x10FFFF -> 0xFFFD
    else -> WINDOWS_1252[cp] ?: cp
}

private val WINDOWS_1252 = mapOf(
    0 to 0xFFFD, 0x80 to 0x20AC, 0x82 to 0x201A, 0x83 to 0x0192, 0x84 to 0x201E, 0x85 to 0x2026, 0x86 to 0x2020,
    0x87 to 0x2021, 0x88 to 0x02C6, 0x89 to 0x2030, 0x8A to 0x0160, 0x8B to 0x2039, 0x8C to 0x0152, 0x8E to 0x017D,
    0x91 to 0x2018, 0x92 to 0x2019, 0x93 to 0x201C, 0x94 to 0x201D, 0x95 to 0x2022, 0x96 to 0x2013, 0x97 to 0x2014,
    0x98 to 0x02DC, 0x99 to 0x2122, 0x9A to 0x0161, 0x9B to 0x203A, 0x9C to 0x0153, 0x9E to 0x017E, 0x9F to 0x0178,
)
