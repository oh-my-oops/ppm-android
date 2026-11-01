package org.connectbot.terminal

import androidx.compose.ui.graphics.Color

/** What `terminal-link-cases.tsv` holds (written by `android/scripts/gen-terminal-link-cases.ts`), read back. */
internal object WebLinkFixtures {
    class TextCase(val text: String, val isUrl: Boolean, val oscShown: Boolean)

    /** A host on its own and the `hostname` Chrome serialized for it, null where `new URL` threw. */
    class HostCase(val host: String, val hostname: String?)

    /** A link as the addon reported it from row [y]: its range and the length, FNV-1a hash and start of its text. */
    class ExpectedLink(val y: Int, val startX: Int, val startY: Int, val endX: Int, val endY: Int, val length: Int, val hash: String, val prefix: String)

    class BufferCase(val name: String, val cols: Int, val lines: List<TerminalLine>, val links: List<ExpectedLink>) {
        fun expected(y: Int) = links.filter { it.y == y }
    }

    private const val CONTINUATION = '\ue000'
    private const val EMPTY = '\ue001'

    val texts: List<TextCase>
    val hosts: List<HostCase>
    val buffers: List<BufferCase>

    init {
        val lines = checkNotNull(javaClass.getResource("/terminal-link-cases.tsv")) { "terminal-link-cases.tsv is missing" }.readText().split('\n')
        val texts = mutableListOf<TextCase>()
        val hosts = mutableListOf<HostCase>()
        val buffers = mutableListOf<BufferCase>()
        var name = ""
        var cols = 0
        var rows = mutableListOf<Pair<Boolean, String>>()
        var links = mutableListOf<ExpectedLink>()
        fun finish() {
            if (name.isEmpty()) return
            val built = rows.mapIndexed { i, (_, cells) -> TerminalLine(i, cells(cells), softWrapped = rows.getOrNull(i + 1)?.first == true) }
            buffers += BufferCase(name, cols, built, links)
        }
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("# ") || line == "#text\ttext\tisUrl\toscShown" || line == "#host\thost\tok\thostname") continue
            val f = line.split('\t')
            when {
                f[0] == "text" -> texts += TextCase(unescape(f[1]), f[2] == "1", f[3] == "1")
                f[0] == "host" -> hosts += HostCase(unescape(f[1]), if (f[2] == "1") unescape(f[3]) else null)
                f[0] == "#buffer" -> {
                    finish()
                    name = f[1]
                    cols = f[2].toInt()
                    rows = mutableListOf()
                    links = mutableListOf()
                }
                f[0] == "row" -> rows += (f[1] == "1") to unescape(f[2])
                f[0] == "link" -> links += ExpectedLink(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7], unescape(f[8]))
                else -> error("Unknown fixture line: $line")
            }
        }
        finish()
        this.texts = texts
        this.hosts = hosts
        this.buffers = buffers
    }

    /** FNV-1a over UTF-16 units, as the generator writes it. */
    fun fnv(text: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in text) h = (h xor c.code) * 0x01000193
        return Integer.toHexString(h)
    }

    private fun unescape(field: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c != '\\') {
                out.append(c)
                i++
            } else if (field[i + 1] == '\\') {
                out.append('\\')
                i += 2
            } else {
                check(field[i + 1] == 'u') { "Bad escape in $field" }
                out.append(field.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            }
        }
        return out.toString()
    }

    /** A row as the engine holds it: a glyph, then 0 for the second half of a wide one; an empty cell is a space. */
    private fun cells(row: String): List<TerminalLine.Cell> {
        val cells = mutableListOf<TerminalLine.Cell>()
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                c == EMPTY -> cells += cell(' ')
                c == CONTINUATION -> error("A second half without a first")
                else -> {
                    val pair = c.isHighSurrogate() && i + 1 < row.length && row[i + 1].isLowSurrogate()
                    val wide = row.getOrNull(i + (if (pair) 2 else 1)) == CONTINUATION
                    cells += cell(c, if (pair) listOf(row[i + 1]) else emptyList(), if (wide) 2 else 1)
                    i += (if (pair) 1 else 0) + (if (wide) 1 else 0)
                }
            }
            i++
        }
        return cells
    }

    private fun cell(char: Char, combining: List<Char> = emptyList(), width: Int = 1) =
        TerminalLine.Cell(char, combining, fgColor = Color.White, bgColor = Color.Black, width = width)
}
