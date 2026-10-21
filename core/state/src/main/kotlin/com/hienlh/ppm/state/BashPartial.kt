package com.hienlh.ppm.state

/**
 * What a running Bash / PowerShell call has printed so far (`BashPartialEntry` in use-chat.ts), as
 * its card shows it: the last [LINES] lines, and the server's count of every line printed. The web
 * keeps the last [MAX_CHARS] characters and shows their last [LINES] lines; keeping only those
 * lines shows the same text without copying the rest again at every delta.
 */
data class BashPartial(val tail: String, val lineCount: Int) {
    /** With [content] printed after it, [lineCount] lines in all now. */
    fun plus(content: String, lineCount: Int): BashPartial =
        BashPartial(lastLines((tail + content).takeLast(MAX_CHARS)), lineCount)

    companion object {
        const val LINES = 200

        /** The most of the output the web keeps (`content.slice(-500_000)`). */
        const val MAX_CHARS = 500_000

        /** The first delta, kept whole as the web keeps it: only what is appended to is cut. */
        fun of(content: String, lineCount: Int): BashPartial = BashPartial(lastLines(content), lineCount)

        /** `text.split("\n").slice(-LINES).join("\n")`, without splitting the whole text. */
        internal fun lastLines(text: String): String {
            var newlines = 0
            var i = text.length
            while (i > 0) {
                i--
                if (text[i] == '\n' && ++newlines == LINES) return text.substring(i + 1)
            }
            return text
        }
    }
}

/** The shell tools whose output the server streams while they run. */
val SHELL_TOOLS = setOf("Bash", "PowerShell")
