package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.isJsSpace

private val SHELL_LANGUAGES = setOf("bash", "sh", "shell", "zsh")

/** `MdPre.isBash` in markdown-code-block.tsx: no case folding, trimming or extra shell aliases. */
internal fun isTerminalCodeBlock(code: String, language: String?): Boolean =
    language in SHELL_LANGUAGES || (language.isNullOrEmpty() && code.startsWith('$'))

// JS multiline ^ recognises CR, LF and Unicode line/paragraph separators, but not Java's NEL.
private val SHELL_PROMPT = Regex("""(?:\A|(?<=[\n\r\u2028\u2029]))[ \t]*\$[ \t]+""")

/** run-in-terminal.ts: strip `$ ` prompts and trailing whitespace, leaving the last line awaiting Enter. */
internal fun normalizeCodeBlockCommand(code: String): String = SHELL_PROMPT.replace(code, "").trimEnd(::isJsSpace)
