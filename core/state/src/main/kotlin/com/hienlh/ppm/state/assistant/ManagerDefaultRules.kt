package com.hienlh.ppm.state.assistant

/*
 * Default manager rules that PPM's template carries and that a project seeded before the rule
 * existed may still lack (`src/shared/manager-default-rules.ts`). The rules dialog offers the
 * missing one to the user, who adds it as their own edit. PPM never adds it by itself: a rules file
 * is the user's.
 */

/** The manager's role: it hands hands-on work to child chats. One list item, five lines, no trailing newline. */
val MANAGER_DELEGATE_RULE = listOf(
    "- Manage the work; do not do it yourself. Hand every piece of hands-on work to a child chat:",
    "  investigating, writing code and tests, screenshots, posting a comment or message the user",
    "  approved (pass the approved text on unchanged), merges, releases and upgrades the user OK'd,",
    "  cleaning up disks or machines. Your own tool use is planning, briefing child chats, light checks",
    "  of what they claim (a git log, a file, a screenshot), the project's task list and cards.",
).joinToString("\n")

/** The rule as one paragraph, without its list marker: the file's own line breaks, wrapped again, read as ragged fragments. */
val MANAGER_DELEGATE_RULE_PROSE: String = MANAGER_DELEGATE_RULE.replace(Regex("^-\\s*"), "").replace(Regex("\\s*\\n\\s*"), " ")

/**
 * The opening sentence that names the rule. A file holding it has the rule, however the user
 * reworded the rest: the dialog offers the rule to files without it, not to every wording.
 */
private const val DELEGATE_RULE_MARKER = "manage the work; do not do it yourself."

private fun squash(text: String) = text.replace(Regex("\\s+"), " ").lowercase()

fun hasManagerDelegateRule(rules: String): Boolean = squash(rules).contains(DELEGATE_RULE_MARKER)

private val HEADING = Regex("^#{1,6}\\s")
private val LIST_ITEM = Regex("^[-*]\\s")

/**
 * [rules] with the delegate rule added once, where the template has it: before the first rule of
 * the opening section. It never lands inside a later section, whose heading may change what a line
 * under it means ("Allowed without asking"): with a section before any rule, it goes just above
 * that heading; with neither, at the end. Unchanged when the rule is already there.
 */
fun withManagerDelegateRule(rules: String): String {
    if (hasManagerDelegateRule(rules)) return rules
    val body = if (rules.endsWith("\n")) rules.dropLast(1) else rules
    if (body.isBlank()) return "$MANAGER_DELEGATE_RULE\n"
    val lines = body.split("\n").toMutableList()
    val first = lines.indexOfFirst { it.isNotBlank() }
    val titled = first >= 0 && HEADING.containsMatchIn(lines[first])
    var i = if (titled) first + 1 else 0
    while (i < lines.size) {
        val line = lines[i]
        if (LIST_ITEM.containsMatchIn(line)) {
            lines.add(i, MANAGER_DELEGATE_RULE)
            return lines.joinToString("\n") + "\n"
        }
        if (HEADING.containsMatchIn(line)) {
            val insert = mutableListOf<String>()
            if (i > 0 && lines[i - 1].isNotBlank()) insert += ""
            insert += MANAGER_DELEGATE_RULE
            insert += ""
            lines.addAll(i, insert)
            return lines.joinToString("\n") + "\n"
        }
        i++
    }
    // Prose only, no rule and no section: the rule follows it as a paragraph of its own.
    if (lines.last().isNotBlank()) lines += ""
    lines += MANAGER_DELEGATE_RULE
    return lines.joinToString("\n") + "\n"
}
