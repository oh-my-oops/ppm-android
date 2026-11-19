package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.ModelOption

/** When Claude asks before acting (`mode-selector.tsx`), in the order Shift+Tab steps through. */
enum class PermissionMode(val value: String, val label: String, val shortLabel: String, val detail: String) {
    DEFAULT("default", "Ask before edits", "Ask", "Claude will ask for approval before making each edit"),
    ACCEPT_EDITS("acceptEdits", "Edit automatically", "Auto-edit", "Claude will edit files without asking first"),
    PLAN("plan", "Plan mode", "Plan", "Claude will present a plan before editing"),
    BYPASS_PERMISSIONS("bypassPermissions", "Bypass permissions", "Bypass", "Claude will not ask before running commands"),
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
            if (providerId == "antigravity") listOf(LOW, MEDIUM, HIGH, XHIGH) else entries

        /** The label for a value from the server; one this app does not know shows as given. */
        fun label(value: String?): String = if (value.isNullOrEmpty()) "" else of(value)?.label ?: value
    }
}

object ModelNames {
    private val antigravityEffortSuffix = Regex("-(low|medium|high|xhigh)$", RegexOption.IGNORE_CASE)
    private val claudeId = Regex("claude-([a-z]+)((?:-\\d{1,2})+)(?:-\\d{8})?", RegexOption.IGNORE_CASE)
    private val geminiId = Regex("gemini-(\\d+(?:\\.\\d+)?)((?:-[a-z]+)+)", RegexOption.IGNORE_CASE)
    private val claudePrefix = Regex("^claude\\s+", RegexOption.IGNORE_CASE)

    /**
     * A readable name for a model id no list labelled (`modelDisplayName`): a pinned model, an older
     * session, a list still loading. Only ids of a known shape are rewritten, by that shape rather
     * than by a list of names, so a new model needs no change here: `claude-<family>-<major>[-<minor>]`
     * (optionally a `-YYYYMMDD` snapshot, `claude-opus-5-5` → "Opus 5.5") and `gemini-<version>-<name>`,
     * each optionally carrying Antigravity's effort suffix. Anything else is shown as given.
     */
    fun displayName(id: String): String {
        val bare = id.replace(antigravityEffortSuffix, "")
        claudeId.matchEntire(bare)?.let { match ->
            val family = match.groupValues[1]
            val version = match.groupValues[2].drop(1).split("-").joinToString(".")
            return family.replaceFirstChar { it.uppercase() } + " " + version
        }
        geminiId.matchEntire(bare)?.let { match ->
            val words = match.groupValues[2].drop(1).split("-").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            return "Gemini ${match.groupValues[1]} $words"
        }
        return id
    }

    /** The "Auto" entry of a model select (`autoModelLabel`): names the model the provider picks, when its list says. */
    fun autoModelLabel(models: List<ModelOption>): String =
        models.firstOrNull { it.isDefault }?.let { "Auto (${it.label})" } ?: "Auto (default)"

    /**
     * Strip the leading "Claude " on the Claude provider so the chip stays compact
     * ("Claude Opus 5" → "Opus 5"); keep it on other providers such as Antigravity
     * where Claude and Gemini models sit side by side.
     */
    fun shortLabel(label: String, providerId: String? = null): String =
        if (!providerId.isNullOrEmpty() && providerId != "claude") label else label.replace(claudePrefix, "")

    /**
     * The model a chat runs on (`model-thinking-selector.tsx` `effectiveModel`): the one chosen, else
     * ("Auto") the one the provider picks, which its list [models] marks — none while the list is
     * still coming. On Antigravity `"pro"` is no choice, and a `-low`/`-medium`/`-high` suffix is
     * stripped so the base option in the catalog matches.
     */
    fun effectiveModel(model: String?, providerId: String, models: List<ModelOption> = emptyList()): String? {
        val chosen = if (providerId == "antigravity" && !model.isNullOrEmpty()) {
            if (model == "pro") null else model.replace(antigravityEffortSuffix, "")
        } else {
            model
        }
        return chosen?.takeIf { it.isNotEmpty() } ?: models.firstOrNull { it.isDefault }?.value
    }

    /** Effort suffix (`low`, `medium`, `high`, `xhigh`) embedded in a legacy Antigravity model id, if any. */
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
