package com.hienlh.ppm.app.ui.phone.chat

import com.hienlh.ppm.protocol.EffortStatusReport
import com.hienlh.ppm.state.Effort
import com.hienlh.ppm.state.ModelNames

/**
 * The effort a Claude turn runs at beside the one picked in PPM (`effortInEffect` and
 * `shownEffort` in `src/shared/effort-status.ts` and `model-thinking-selector-helpers.ts`).
 * [level] is null when no effort is sent; [reason] is what overrides the pick, if anything does:
 * `env` (`CLAUDE_CODE_EFFORT_LEVEL`), `cap` (a `maxEffortLevel`) or `model`.
 */
data class ShownEffort(val pick: Effort, val level: Effort?, val reason: String?)

/** What the model chip and its sheet say about it: why the turns do not run at the pick. */
data class PhoneEffortNotice(val title: String, val detail: String)

private val LEVELS = listOf(Effort.LOW, Effort.MEDIUM, Effort.HIGH, Effort.XHIGH, Effort.MAX)

private sealed interface EnvMeaning {
    data class Level(val level: Effort) : EnvMeaning
    /** `auto` or `unset`: no effort is sent and the model's default applies. */
    data object Auto : EnvMeaning
    /** A number: a thinking budget, sent instead of an effort level. */
    data object Budget : EnvMeaning
}

/** What the CLI makes of `CLAUDE_CODE_EFFORT_LEVEL`, parsed as it parses it; null for a value it ignores. */
private fun envMeaning(raw: String?): EnvMeaning? {
    if (raw.isNullOrEmpty()) return null
    val value = raw.lowercase()
    if (value == "auto" || value == "unset") return EnvMeaning.Auto
    Effort.of(if (value == "med") "medium" else value)?.let { return EnvMeaning.Level(it) }
    // `parseInt`: a leading number, whatever follows it.
    return if (Regex("^\\s*[+-]?\\d").containsMatchIn(value)) EnvMeaning.Budget else null
}

private fun withinLimits(level: Effort, status: EffortStatusReport): Effort? {
    if (status.supported?.isEmpty() == true) return null
    val cap = Effort.of(status.cap?.value)
    val capped = if (cap != null && LEVELS.indexOf(level) > LEVELS.indexOf(cap)) cap else level
    // The CLI's own fallback for a model without Extra or Max.
    val unsupported = status.supported != null && capped.value !in status.supported!!
    return if (unsupported && (capped == Effort.XHIGH || capped == Effort.MAX)) Effort.HIGH else capped
}

private fun envLevel(env: EnvMeaning, status: EffortStatusReport): Effort? {
    if (env is EnvMeaning.Level) return withinLimits(env.level, status)
    val cap = Effort.of(status.cap?.value) ?: return null
    if (env is EnvMeaning.Budget) return withinLimits(Effort.HIGH, status)
    return if (cap == Effort.LOW) withinLimits(Effort.LOW, status) else null
}

/** The picked [effort] (High when none is) and the level the turns run at under [status]. */
fun shownEffort(effort: String?, status: EffortStatusReport?): ShownEffort {
    val pick = Effort.of(effort) ?: Effort.DEFAULT
    if (status == null) return ShownEffort(pick, pick, null)
    val env = envMeaning(status.env?.value)
    val observed = status.observed
    if (env != null) {
        // The variable outranks every pick, so the CLI's answer stays true whatever is picked since.
        return ShownEffort(pick, if (observed != null) Effort.of(observed.applied) else envLevel(env, status), "env")
    }
    // A pick made after the CLI last answered is checked against the limits it reported.
    val level = if (observed != null && observed.requested == pick.value) Effort.of(observed.applied) else withinLimits(pick, status)
    if (level == pick) return ShownEffort(pick, level, null)
    val cap = Effort.of(status.cap?.value)
    val capped = cap != null && LEVELS.indexOf(pick) > LEVELS.indexOf(cap)
    return ShownEffort(pick, level, if (capped) "cap" else "model")
}

/** Where a setting lives, for a sentence that tells the user where to change it. */
private fun place(file: String?, source: String): String = file ?: when (source) {
    "userSettings" -> "~/.claude/settings.json"
    "projectSettings" -> ".claude/settings.json"
    "localSettings" -> ".claude/settings.local.json"
    "policySettings" -> "managed settings"
    "flagSettings" -> "the settings PPM passes to Claude Code"
    else -> "the environment PPM runs in"
}

private const val USE_PPM_PICK = "Remove it there to use the effort picked in PPM."

/**
 * Why the effort the turns run at is not the one picked (`effortNotice`), naming the setting and
 * where it is; null when the pick is what runs. Same words as the web's notice.
 */
fun effortNotice(shown: ShownEffort, status: EffortStatusReport?, modelDisplay: String): PhoneEffortNotice? {
    val reason = shown.reason ?: return null
    if (status == null) return null
    val model = status.observed?.model?.let { ModelNames.displayName(it.replace(Regex("\\[[^\\]]*]$"), "")) } ?: modelDisplay
    val level = shown.level?.label
    val env = status.env
    val meaning = envMeaning(env?.value)
    if (reason == "env" && env != null && meaning != null) {
        val setting = "CLAUDE_CODE_EFFORT_LEVEL=" + env.value + " in " + place(env.file, env.source)
        val cap = Effort.of(status.cap?.value)
        return when (meaning) {
            EnvMeaning.Auto -> {
                val what = when {
                    cap != null -> "Claude Code sends " + model + "'s default effort, capped at " + cap.label + "."
                    level != null -> "Claude Code sends " + model + "'s default, " + level + "."
                    else -> "no effort is sent, so " + model + "'s default applies."
                }
                PhoneEffortNotice("Effort is left to the model", setting + " overrides the effort picked here: " + what + " " + USE_PPM_PICK)
            }
            EnvMeaning.Budget -> if (level != null) {
                PhoneEffortNotice(
                    "Effort is set to $level outside PPM",
                    setting + " overrides the effort picked here; with a maxEffortLevel set, Claude Code runs it as " + level + ". " + USE_PPM_PICK,
                )
            } else {
                PhoneEffortNotice(
                    "A thinking budget replaces the effort",
                    setting + " sets a thinking budget instead of an effort level, so the effort picked here is not sent. " + USE_PPM_PICK,
                )
            }
            is EnvMeaning.Level -> {
                val runs = when {
                    shown.level == meaning.level -> ""
                    level != null -> " $model runs it as $level."
                    else -> " $model takes no effort level, so none is sent."
                }
                PhoneEffortNotice("Effort is set to " + meaning.level.label + " outside PPM", setting + " overrides the effort picked here." + runs + " " + USE_PPM_PICK)
            }
        }
    }
    val cap = status.cap
    val capLevel = Effort.of(cap?.value)
    if (reason == "cap" && cap != null && capLevel != null) {
        val runs = if (shown.level == capLevel || level == null) "" else " $model runs it as $level."
        return PhoneEffortNotice(
            "Effort is capped at " + capLevel.label,
            "maxEffortLevel: \"" + cap.value + "\" in " + place(cap.file, cap.source) + " lowers any effort picked above it." + runs,
        )
    }
    if (level == null) {
        return PhoneEffortNotice("$model takes no effort level", "Claude Code sends no effort with $model, so the effort picked here has no effect.")
    }
    return PhoneEffortNotice("Runs at $level", shown.pick.label + " isn't available for " + model + ", so Claude Code runs it at " + level + ".")
}
