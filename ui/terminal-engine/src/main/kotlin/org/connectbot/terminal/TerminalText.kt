package org.connectbot.terminal

/** Physical rows become logical lines; preserve indentation and spaces inside soft wraps. */
internal fun terminalText(lines: List<TerminalLine>): String = buildString {
    for ((index, line) in lines.withIndex()) {
        val text = line.text.replace('\u0000', ' ')
        append(if (line.softWrapped) text else text.trimEnd())
        if (index < lines.lastIndex && !line.softWrapped) append('\n')
    }
}.trimEnd('\n')
