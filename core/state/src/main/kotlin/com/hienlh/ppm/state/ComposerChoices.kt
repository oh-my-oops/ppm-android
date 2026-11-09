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

        /** The effort choices offered in the picker (`model-thinking-selector.tsx` `effortOptions`). */
        fun optionsFor(providerId: String): List<Effort> =
            if (providerId == "antigravity") listOf(LOW, MEDIUM, HIGH) else entries

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
    private val antigravityLabels = mapOf(
        "gemini-4-argon" to "Gemini 4 Argon",
        "gemini-4-argon-medium" to "Gemini 4 Argon",
        "gemini-4-argon-high" to "Gemini 4 Argon",
        "gemini-4-argon-low" to "Gemini 4 Argon",
        "gemini-3.8-flash" to "Gemini 3.8 Flash",
        "gemini-3.8-flash-high" to "Gemini 3.8 Flash",
        "gemini-3.8-flash-medium" to "Gemini 3.8 Flash",
        "gemini-3.8-flash-low" to "Gemini 3.8 Flash",
        "claude-opus-5-5-low" to "Claude Opus 5.5",
        "claude-opus-5-5-medium" to "Claude Opus 5.5",
        "claude-opus-5-5-high" to "Claude Opus 5.5",
        "claude-sonnet-5-5-low" to "Claude Sonnet 5.5",
        "claude-sonnet-5-5-medium" to "Claude Sonnet 5.5",
        "claude-sonnet-5-5-high" to "Claude Sonnet 5.5",
        "gpt-oss-120b" to "GPT-OSS 120B",
        "gpt-oss-120b-medium" to "GPT-OSS 120B",
    )
    private val antigravityEffortSuffix = Regex("-(low|medium|high)$", RegexOption.IGNORE_CASE)
    private val claudeId = Regex("claude-([a-z]+)((?:-\\d{1,2})+)(?:-\\d{8})?", RegexOption.IGNORE_CASE)
    private val claudePrefix = Regex("^claude\\s+", RegexOption.IGNORE_CASE)

    /**
     * A readable name for a model id no list labelled (`modelDisplayName`): Antigravity's
     * known ids first, then Claude ids (`claude-opus-5-5` → "Opus 5.5").
     */
    fun displayName(id: String): String {
        antigravityLabels[id]?.let { return it }
        val match = claudeId.matchEntire(id) ?: return id
        val family = match.groupValues[1]
        val version = match.groupValues[2].drop(1).split("-").joinToString(".")
        return family.replaceFirstChar { it.uppercase() } + " " + version
    }

    /**
     * Strip the leading "Claude " on the Claude provider so the chip stays compact
     * ("Claude Opus 5" → "Opus 5"); keep it on other providers such as Antigravity
     * where Claude and Gemini models sit side by side.
     */
    fun shortLabel(label: String, providerId: String? = null): String =
        if (!providerId.isNullOrEmpty() && providerId != "claude") label else label.replace(claudePrefix, "")

    /**
     * Antigravity's effective base model (`model-thinking-selector.tsx` `effectiveModel`):
     * `null`, `""` or `"pro"` defaults to `"gemini-4-argon"`, and a `-low`/`-medium`/`-high`
     * suffix is stripped so the base option in the catalog matches.
     */
    fun effectiveModel(model: String?, providerId: String): String? =
        if (providerId == "antigravity") {
            if (model.isNullOrEmpty() || model == "pro") "gemini-4-argon" else model.replace(antigravityEffortSuffix, "")
        } else {
            model
        }

    /** Effort suffix (`low`, `medium`, `high`) embedded in a legacy Antigravity model id, if any. */
    fun suffixEffort(model: String?, providerId: String): String? =
        if (providerId == "antigravity" && model != null) {
            antigravityEffortSuffix.find(model)?.groupValues?.get(1)?.lowercase()
        } else {
            null
        }

    /** The model chip's text: the model, then " · <Effort>" when there is one. */
    fun chipLabel(model: String, effort: String?): String {
        val name = Effort.label(effort)
        return if (name.isEmpty()) model else "$model · $name"
    }
}
