package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.isJsSpace
import com.hienlh.ppm.protocol.jsLowercase

// shell-block-detect.ts. JavaScript's `\s` (white space and line terminators) spelled out, since
// Java's `\s` is ASCII only, and `\z` where the web's `$` ends the input: Java's `$` also matches
// before a final line terminator, NEL among them.
private const val JS_S = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

private val SHELL_LANGS = setOf("bash", "sh", "shell", "zsh", "console", "shellsession", "shell-session")
private val UNTAGGED_LANGS = setOf("", "text", "txt", "plaintext", "plain")
private const val MAX_UNTAGGED_LINES = 8

private val COMMANDS = setOf(
    "adb", "agy", "apt", "apt-get", "awk", "brew", "bun", "bunx", "cargo", "cat", "cd", "chmod", "chown", "claude",
    "cmake", "code", "codex", "cp", "curl", "cut", "deno", "df", "diff", "dig", "docker", "du", "echo", "env",
    "export", "fd", "find", "free", "gh", "git", "go", "gradle", "grep", "head", "htop", "ip", "journalctl", "jq",
    "kill", "kubectl", "less", "ln", "ls", "lsof", "make", "mkdir", "mv", "mysql", "nc", "node", "npm", "npx",
    "open", "pacman", "paru", "pgrep", "ping", "pip", "pip3", "pnpm", "podman", "ppm", "printenv", "ps", "psql",
    "pwd", "python", "python3", "readlink", "rg", "rm", "rmdir", "rsync", "rustc", "scp", "sed", "sort", "source",
    "sqlite3", "ss", "ssh", "stat", "systemctl", "tail", "tar", "tee", "touch", "tree", "uname", "uniq", "unzip",
    "uv", "wc", "wget", "which", "xargs", "xdg-open", "yarn", "yay", "zip",
)

/** `sudo`, `VAR=value` and `time` in front of the program do not decide what it is. */
private val PREFIX_RE = Regex("""^(?:(?:sudo|time|nohup)[$JS_S]+|[A-Za-z_][A-Za-z0-9_]*=(?:\$\([^)]*\)|"[^"]*"|'[^']*'|[^$JS_S]*)[$JS_S]+)+""")
private val PROMPT_RE = Regex("""^\$[$JS_S]""")
private val PATH_RE = Regex("""^(?:\.{1,2}/|~/|/)[\w.~/-]+(?:[$JS_S]|\z)""")

/** A line ending in `\` or `|`, `&&`, `||` goes on to the next one: that next line is not a command of its own. */
private val CONTINUES = Regex("""(?:\\|\||&&)[$JS_S]*\z""")

private fun jsTrim(text: String) = text.trim(::isJsSpace)

private fun looksLikeCommandLine(line: String): Boolean {
    val t = jsTrim(line)
    if (t.isEmpty() || t.startsWith("#")) return true // a blank line or a comment between commands
    if (PROMPT_RE.containsMatchIn(t)) return true
    val rest = PREFIX_RE.replaceFirst(t, "")
    if (PATH_RE.containsMatchIn(rest)) return true
    val program = rest.takeWhile { !isJsSpace(it) }
    return program in COMMANDS
}

/**
 * `isShellCodeBlock` (shell-block-detect.ts): whether a chat's code block gets Run and Run in
 * terminal. A block tagged as shell always is; an untagged (or `text`) one when it is at most
 * eight lines and every line starts like a command — a `$ ` prompt, a path to a program, or a
 * program people type, optionally after `sudo` or `VAR=value` — a line continued by a trailing
 * `\`, `|` or `&&` carrying the next one.
 */
internal fun isShellCodeBlock(language: String?, text: String): Boolean {
    val l = jsLowercase(language ?: "")
    if (l in SHELL_LANGS) return true
    if (l !in UNTAGGED_LANGS) return false
    val lines = text.trimEnd(::isJsSpace).split("\n")
    if (jsTrim(lines[0]).isEmpty() || lines.size > MAX_UNTAGGED_LINES) return false
    var continued = false
    for (line in lines) {
        if (!continued && !looksLikeCommandLine(line)) return false
        continued = CONTINUES.containsMatchIn(line)
    }
    return true
}

// JS multiline ^ recognises CR, LF and Unicode line/paragraph separators, but not Java's NEL.
private val SHELL_PROMPT = Regex("""(?:\A|(?<=[\n\r  ]))[ \t]*\$[ \t]+""")

/** run-in-terminal.ts: strip `$ ` prompts and trailing whitespace, leaving the last line awaiting Enter. */
internal fun normalizeCodeBlockCommand(code: String): String = SHELL_PROMPT.replace(code, "").trimEnd(::isJsSpace)
