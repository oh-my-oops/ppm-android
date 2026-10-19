package com.hienlh.ppm.state

/** When Claude asks before acting (`mode-selector.tsx`), in the order Shift+Tab steps through. */
enum class PermissionMode(val value: String, val label: String, val detail: String) {
    DEFAULT("default", "Ask before edits", "Claude will ask for approval before making each edit"),
    ACCEPT_EDITS("acceptEdits", "Edit automatically", "Claude will edit files without asking first"),
    PLAN("plan", "Plan mode", "Claude will present a plan before editing"),
    BYPASS_PERMISSIONS("bypassPermissions", "Bypass permissions", "Claude will not ask before running commands"),
    ;

    /** The next one, as Shift+Tab in the message box moves. */
    val next: PermissionMode get() = entries[(ordinal + 1) % entries.size]

    companion object {
        /** What a chat starts in when nothing chose one. */
        val FALLBACK = BYPASS_PERMISSIONS

        fun of(value: String?): PermissionMode? = entries.firstOrNull { it.value == value }
    }
}

/**
 * How hard the model thinks (`model-thinking-selector-helpers.ts`). "Extra" is the SDK's
 * `xhigh`; the literal `extra` would crash the CLI, so it is never a value here.
 */
enum class Effort(val value: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "Extra"),
    MAX("max", "Max"),
    ;

    companion object {
        val DEFAULT = HIGH

        fun of(value: String?): Effort? = entries.firstOrNull { it.value == value }

        /** The label for a value from the server; one this app does not know shows as given. */
        fun label(value: String?): String = if (value.isNullOrEmpty()) "" else of(value)?.label ?: value
    }
}

/**
 * How a message sent while a turn runs is handled (`PriorityToggle`), in the order a click
 * steps through.
 */
enum class MessagePriority(val value: String, val label: String) {
    NEXT("next", "Queue"),
    LATER("later", "Later"),
    NOW("now", "Interrupt"),
    ;

    val following: MessagePriority get() = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = NEXT
    }
}

object ModelNames {
    private val claudeId = Regex("claude-([a-z]+)((?:-\\d{1,2})+)(?:-\\d{8})?", RegexOption.IGNORE_CASE)
    private val claudePrefix = Regex("^claude\\s+", RegexOption.IGNORE_CASE)

    /**
     * A readable name for a model id no list labelled (`modelDisplayName`): only Claude ids,
     * whose shape is known, are rewritten — `claude-opus-5-5` reads "Opus 5.5".
     */
    fun displayName(id: String): String {
        val match = claudeId.matchEntire(id) ?: return id
        val family = match.groupValues[1]
        val version = match.groupValues[2].drop(1).split("-").joinToString(".")
        return family.replaceFirstChar { it.uppercase() } + " " + version
    }

    /** "Claude Opus 5" → "Opus 5", so the chip stays small. */
    fun shortLabel(label: String): String = label.replace(claudePrefix, "")

    /** The model chip's text: the model, then " · <Effort>" when there is one. */
    fun chipLabel(model: String, effort: String?): String {
        val name = Effort.label(effort)
        return if (name.isEmpty()) model else "$model · $name"
    }
}
