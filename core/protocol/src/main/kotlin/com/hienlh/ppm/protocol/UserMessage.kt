package com.hienlh.ppm.protocol

import com.hienlh.ppm.protocol.generated.TurnReference

/**
 * A stored user message taken apart (`user-message-parse.ts`). What lands in the transcript is
 * not what was typed: the composer and the runtime wrap it in attachment markers, slash-command
 * tags, an agent prefix, a quoted reply and context tags. The bubble and the composer's recall
 * both need it undone, the same way.
 */
data class ParsedUserMessage(
    val replyTo: ReplyReference?,
    val files: List<String>,
    /** The body with the command's arguments folded in, for the bubble. */
    val text: String,
    /** The body alone, the arguments kept apart, for putting back in the composer. */
    val body: String,
    val tags: List<SystemTag>,
    val command: SlashCommand?,
    val terminalBlocks: List<String>,
    val idePath: String?,
    val agent: String?,
    /** Other sessions' turns the message links to, as chips (`turnRefs`). */
    val turnRefs: List<TurnReference>? = null,
    /** Set when another PPM session's AI sent the message: it is drawn as theirs, not the person's. */
    val fromSession: FromSessionReference? = null,
    /** Set when one of the chat's session schedules sent it: drawn as one line, not the person's bubble. */
    val scheduleRun: ScheduleRunReference? = null,
    /** The PPM tasks the message is about (`taskRefs`), as chips. */
    val taskRefs: List<TaskReference>? = null,
) {
    data class SystemTag(val name: String, val label: String, val content: String) {
        /** The text of `<[tag]>…</[tag]>` inside, such as a task notification's `status`; null when absent or blank. */
        fun field(tag: String): String? =
            Regex("<$tag>([\\s\\S]*?)</$tag>").find(content)?.groupValues?.get(1)?.jsTrim()?.ifEmpty { null }
    }

    /**
     * What the user typed (`typedText`): the body, a command coming back as `/name args` above it.
     * Attachments, terminal blocks and context are not in it.
     */
    val typedText: String
        get() {
            val command = command ?: return body
            // Stored with and without the leading slash.
            val typed = "/${command.name.trimStart('/')}" + (command.args?.let { " $it" } ?: "")
            return if (body.isNotEmpty()) "$typed\n\n$body" else typed
        }

    /** Put there by the runtime, not typed: drawn as context, with no action bar, and never quoted. */
    val isSystemContext: Boolean get() = tags.any { it.name in SYSTEM_CONTEXT_TAGS }

    data class SlashCommand(val name: String, val args: String? = null)

    companion object {
        /** Outermost wrapper first, as the web takes it apart. */
        fun parse(content: String): ParsedUserMessage {
            val decoded = ChatMessageBlocks.decode(content)
            val (idePath, afterIde) = ideOpenedFile(decoded.content)
            val (blocks, afterBlocks) = terminalBlocks(afterIde)
            val (files, attachmentsText) = attachments(afterBlocks)
            // Command output tags go, their text stays.
            val (cleanText, tags) = systemTags(COMMAND_OUTPUT.replace(attachmentsText, "$1"))
            var (command, afterCommand) = commandTags(cleanText)
            // A scheduled slash command as Claude Code records it: its run block rides in the command's args.
            var scheduleRun = decoded.scheduleRun
            if (scheduleRun == null && command?.args != null && decoded.fromSession == null) {
                scheduleRun = scheduleRunInCommandRecord(decoded.content)
                if (scheduleRun != null) command = command.copy(args = ChatScheduleRun.decodeInCommandArgs(command.args!!).args.ifEmpty { null })
            }
            val (agent, body) = agentTag(afterCommand)
            val args = command?.args
            val text = if (args != null) (if (body.isNotEmpty()) "$args\n\n$body" else args) else body
            return ParsedUserMessage(decoded.replyTo, files, text, body, tags, command, blocks, idePath, agent, decoded.turnRefs, decoded.fromSession, scheduleRun, decoded.taskRefs)
        }

        /**
         * The run block a scheduled slash command carries in its args, read from the message exactly
         * as stored, and only from Claude's own record of a command: stripping another tag first could
         * join a sentinel typed in two halves around it.
         */
        private fun scheduleRunInCommandRecord(raw: String): ScheduleRunReference? {
            if (!COMMAND_RECORD_START.containsMatchIn(raw)) return null
            val args = COMMAND_ARGS.find(raw)?.groupValues?.get(1)?.jsTrim()?.ifEmpty { null } ?: return null
            return ChatScheduleRun.decodeInCommandArgs(args).scheduleRun
        }

        /**
         * What the user typed, for the composer's recall (`toComposerDraft`): attachments,
         * terminal blocks and context are dropped, a command comes back as `/name args`, and the
         * agent is returned for its chip.
         */
        fun composerDraft(content: String): Pair<String?, String> {
            val parsed = parse(content)
            return parsed.agent to parsed.typedText
        }

        private val SYSTEM_CONTEXT_TAGS = setOf("task-notification", "environment_details", "local-command-caveat")

        private val TAG_LABELS = mapOf(
            "system-reminder" to "Context", "claudeMd" to "CLAUDE.md", "gitStatus" to "Git Status", "currentDate" to "Date",
            "fast_mode_info" to "Fast Mode", "available-deferred-tools" to "Tools", "task-notification" to "Task Result",
            "environment_details" to "Environment", "local-command-caveat" to "System",
        )

        private val SYSTEM_TAG = Regex(
            """<(system-reminder|available-deferred-tools|antml:[\w-]+|fast_mode_info|claudeMd|gitStatus|currentDate|task-notification|environment_details|local-command-caveat)[^>]*>([\s\S]*?)</\1>""",
        )
        private val COMMAND_OUTPUT = Regex("""<local-command-(?:stdout|stderr)>([\s\S]*?)</local-command-(?:stdout|stderr)>""")
        private val COMMAND_NAME = Regex("""<command-name>([\s\S]*?)</command-name>""")
        private val COMMAND_ARGS = Regex("""<command-args>([\s\S]*?)</command-args>""")
        private val COMMAND_MESSAGE = Regex("""<command-message>[\s\S]*?</command-message>""")
        private val COMMAND_RECORD_START = Regex("""^<command-(?:message|name)>""")
        private val AGENT_PREFIX = Regex("""^Use the (\S+) agent to\s?""")
        private val IDE_TAG = Regex("""<ide_opened_file>([\s\S]*?)</ide_opened_file>""")
        private val IDE_PATH = Regex("""opened the file (.+?) in the IDE""")
        private val INLINE_IMAGE = Regex("""^\[Attached image \(contents included in this message\): (.+?)]\n*""")
        private val SINGLE_FILE = Regex("""^\[Attached file: (.+?)]\n*""")
        private val MULTIPLE_FILES = Regex("""^\[Attached files:\n([\s\S]+?)]\n*""")
        private val TERMINAL_FENCE = Regex("""^```(?:bash|sh|shell|zsh)\n([\s\S]*?)\n```\s*(?:\n\n?)?""")

        private fun systemTags(text: String): Pair<String, List<SystemTag>> {
            val tags = SYSTEM_TAG.findAll(text).map { match ->
                val name = match.groupValues[1]
                SystemTag(name, TAG_LABELS[name] ?: name.removePrefix("antml:").replace('-', ' '), match.groupValues[2].jsTrim())
            }.toList()
            return SYSTEM_TAG.replace(text, "").jsTrim() to tags
        }

        private fun commandTags(text: String): Pair<SlashCommand?, String> {
            val name = COMMAND_NAME.find(text)?.groupValues?.get(1) ?: return null to text
            val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.jsTrim()?.ifEmpty { null }
            val clean = COMMAND_ARGS.replace(COMMAND_MESSAGE.replace(COMMAND_NAME.replace(text, ""), ""), "").jsTrim()
            return SlashCommand(name.jsTrim(), args) to clean
        }

        private fun agentTag(text: String): Pair<String?, String> {
            val match = AGENT_PREFIX.find(text) ?: return null to text
            return match.groupValues[1] to text.substring(match.range.last + 1)
        }

        private fun ideOpenedFile(text: String): Pair<String?, String> {
            val inner = IDE_TAG.find(text)?.groupValues?.get(1) ?: return null to text
            return IDE_PATH.find(inner)?.groupValues?.get(1)?.jsTrim() to IDE_TAG.replace(text, "").jsTrim()
        }

        /** Inline images first, then one file marker or a list. A message may pair the two. */
        internal fun attachments(content: String): Pair<List<String>, String> {
            val files = mutableListOf<String>()
            var rest = content
            while (true) {
                val match = INLINE_IMAGE.find(rest) ?: break
                files += match.groupValues[1]
                rest = rest.substring(match.range.last + 1)
            }
            if (files.isNotEmpty()) {
                val (tail, text) = attachments(rest)
                return files + tail to text
            }
            SINGLE_FILE.find(rest)?.let { return listOf(it.groupValues[1]) to rest.substring(it.range.last + 1) }
            MULTIPLE_FILES.find(rest)?.let { match ->
                val list = match.groupValues[1].split("\n").map { it.jsTrim() }.filter { it.isNotEmpty() }
                return list to rest.substring(match.range.last + 1)
            }
            return emptyList<String>() to rest
        }

        private fun terminalBlocks(text: String): Pair<List<String>, String> {
            val blocks = mutableListOf<String>()
            var remaining = text
            while (true) {
                val match = TERMINAL_FENCE.find(remaining) ?: break
                blocks += match.groupValues[1]
                remaining = remaining.substring(match.range.last + 1)
            }
            return blocks to remaining.jsTrim()
        }
    }
}

/** `String.prototype.trim`: white space, line ends and the byte-order mark, but not the C0 separators Kotlin counts. */
fun String.jsTrim(): String = trim { it == '\uFEFF' || (it.isWhitespace() && it !in '\u001C'..'\u001F') }
