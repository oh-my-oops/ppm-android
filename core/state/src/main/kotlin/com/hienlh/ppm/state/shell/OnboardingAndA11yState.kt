package com.hienlh.ppm.state.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.string

// ===========================================================================
// 13b.1 · Onboarding "Learn PPM" State, Goals, Steps & Evidence Verification
// ===========================================================================

enum class OnboardingFamiliarity(val id: String, val label: String, val description: String) {
    BEGINNER("beginner", "New to AI IDEs", "Step-by-step guidance through projects, chat, and files"),
    FAMILIAR("familiar", "Familiar with IDEs", "Quick tour of PPM's AI chat, terminal, and git tools"),
    ADVANCED("advanced", "Power user", "Jump straight to shortcuts, multi-panel, and advanced workflows"),
    ;

    companion object {
        fun fromId(raw: String?): OnboardingFamiliarity =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: BEGINNER
    }
}

enum class OnboardingGoalId(val id: String, val title: String, val subtitle: String) {
    AI("ai", "Work with AI Chat", "Open a project, ask AI, and browse session history"),
    EXPLORE("explore", "Explore a Codebase", "Browse project files and search across code"),
    DEVELOPER("developer", "Develop & Ship", "Use Terminal, Source Control, and run your project"),
    ;

    companion object {
        fun fromId(raw: String?): OnboardingGoalId =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: AI
    }
}

data class OnboardingStepDef(
    val id: String,
    val goal: OnboardingGoalId,
    val title: String,
    val instruction: String,
    /** Optional sample prompt that is inserted into the composer draft (NEVER auto-sent!). */
    val suggestedPrompt: String? = null,
)

val ONBOARDING_STEPS: List<OnboardingStepDef> = listOf(
    // Goal: AI
    OnboardingStepDef(
        id = "ai.project",
        goal = OnboardingGoalId.AI,
        title = "Select or add a project",
        instruction = "Open the project switcher to pick an existing repository or register a new one.",
    ),
    OnboardingStepDef(
        id = "ai.chat",
        goal = OnboardingGoalId.AI,
        title = "Open an AI Chat tab",
        instruction = "Start a new chat session in the current project.",
    ),
    OnboardingStepDef(
        id = "ai.send",
        goal = OnboardingGoalId.AI,
        title = "Draft a prompt in the composer",
        instruction = "Try inserting a starter prompt into the composer box and review it before sending.",
        suggestedPrompt = "Summarize the architecture of this repository and list the main entry points.",
    ),
    OnboardingStepDef(
        id = "ai.history",
        goal = OnboardingGoalId.AI,
        title = "Browse chat history",
        instruction = "Check your previous AI sessions in the Chats list.",
    ),
    // Goal: EXPLORE
    OnboardingStepDef(
        id = "explore.project",
        goal = OnboardingGoalId.EXPLORE,
        title = "Select a project",
        instruction = "Pick a project workspace to inspect its files.",
    ),
    OnboardingStepDef(
        id = "explore.file",
        goal = OnboardingGoalId.EXPLORE,
        title = "Open a file from Explorer",
        instruction = "Browse the project file tree and tap any source file to view or edit it.",
    ),
    OnboardingStepDef(
        id = "explore.search",
        goal = OnboardingGoalId.EXPLORE,
        title = "Search across files",
        instruction = "Use project search or Command Palette to find symbols or text.",
    ),
    // Goal: DEVELOPER
    OnboardingStepDef(
        id = "developer.project",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Select a project",
        instruction = "Open your development repository.",
    ),
    OnboardingStepDef(
        id = "developer.terminal",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Open a Terminal session",
        instruction = "Launch an interactive shell in your project directory.",
    ),
    OnboardingStepDef(
        id = "developer.git",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Inspect Source Control",
        instruction = "Review working tree changes, stage hunks, or view Git Graph.",
    ),
    OnboardingStepDef(
        id = "developer.run",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Preview or run your app",
        instruction = "Use Web Preview, Port Forwarding, or ask AI to run tests.",
        suggestedPrompt = "What command runs the unit test suite for this project?",
    ),
)

fun stepsForGoal(goal: OnboardingGoalId): List<OnboardingStepDef> =
    ONBOARDING_STEPS.filter { it.goal == goal }

data class OnboardingEvidence(
    val hasProjectSelected: Boolean = false,
    val projectFileCount: Int = 0,
    val chatSessionCount: Int = 0,
    val hasOpenedFileTab: Boolean = false,
    val hasOpenedTerminalTab: Boolean = false,
    val hasOpenedGitTab: Boolean = false,
)

/**
 * Evaluates which onboarding step IDs are automatically satisfied by server/workspace evidence
 * (`GET /api/project/:name/files/list` and `GET /api/project/:name/chat/sessions?limit=100&offset=0`).
 */
fun evaluateOnboardingEvidence(evidence: OnboardingEvidence): Set<String> {
    val satisfied = LinkedHashSet<String>()
    if (evidence.hasProjectSelected) {
        satisfied.add("ai.project")
        satisfied.add("explore.project")
        satisfied.add("developer.project")
    }
    if (evidence.chatSessionCount > 0) {
        satisfied.add("ai.chat")
        if (evidence.chatSessionCount >= 2) {
            satisfied.add("ai.history")
        }
    }
    if (evidence.projectFileCount > 0 && evidence.hasOpenedFileTab) {
        satisfied.add("explore.file")
    }
    if (evidence.hasOpenedTerminalTab) {
        satisfied.add("developer.terminal")
    }
    if (evidence.hasOpenedGitTab) {
        satisfied.add("developer.git")
    }
    return satisfied
}

data class OnboardingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val familiarity: OnboardingFamiliarity = OnboardingFamiliarity.BEGINNER,
    val selectedGoal: OnboardingGoalId = OnboardingGoalId.AI,
    val completedSteps: Set<String> = emptySet(),
    /** Quick orientation modal page: `null` when closed, `0` or `1` for the 2-page orientation. */
    val quickOrientationPage: Int? = null,
) {
    val goalSteps: List<OnboardingStepDef>
        get() = stepsForGoal(selectedGoal)

    val currentStep: OnboardingStepDef?
        get() = goalSteps.firstOrNull { it.id !in completedSteps }

    val progressFraction: Float
        get() {
            val steps = goalSteps
            if (steps.isEmpty()) return 1f
            val done = steps.count { it.id in completedSteps }
            return (done.toFloat() / steps.size.toFloat()).coerceIn(0f, 1f)
        }

    fun markStepCompleted(stepId: String): OnboardingState =
        copy(completedSteps = completedSteps + stepId)

    fun mergeEvidence(evidence: OnboardingEvidence): OnboardingState =
        copy(completedSteps = completedSteps + evaluateOnboardingEvidence(evidence))

    fun pauseOnEscape(): OnboardingState =
        copy(paused = true, quickOrientationPage = null)

    fun resumeGuide(): OnboardingState =
        copy(active = true, paused = false)

    fun openQuickOrientation(): OnboardingState =
        copy(quickOrientationPage = 0)

    fun nextOrientationPage(): OnboardingState =
        copy(quickOrientationPage = if (quickOrientationPage == 0) 1 else null)

    fun prevOrientationPage(): OnboardingState =
        copy(quickOrientationPage = 0)

    fun toJson(): JsonObject = buildJsonObject {
        put("active", JsonPrimitive(active))
        put("paused", JsonPrimitive(paused))
        put("familiarity", JsonPrimitive(familiarity.id))
        put("selectedGoal", JsonPrimitive(selectedGoal.id))
        put(
            "completedSteps",
            buildJsonArray {
                for (id in completedSteps.sorted()) add(JsonPrimitive(id))
            },
        )
        quickOrientationPage?.let { put("quickOrientationPage", JsonPrimitive(it)) }
    }

    companion object {
        fun fromJson(el: JsonElement?): OnboardingState {
            if (el == null) return OnboardingState()
            val done = el["completedSteps"]?.array
                ?.mapNotNull { it.string }
                ?.toSet()
                .orEmpty()
            return OnboardingState(
                active = el["active"]?.bool ?: false,
                paused = el["paused"]?.bool ?: false,
                familiarity = OnboardingFamiliarity.fromId(el["familiarity"]?.string),
                selectedGoal = OnboardingGoalId.fromId(el["selectedGoal"]?.string),
                completedSteps = done,
                quickOrientationPage = el["quickOrientationPage"]?.int?.coerceIn(0, 1),
            )
        }

        fun fromJsonString(raw: String?): OnboardingState {
            if (raw.isNullOrBlank()) return OnboardingState(active = true)
            return runCatching { fromJson(Json.parseToJsonElement(raw)) }
                .getOrElse { OnboardingState(active = true) }
        }
    }
}

/**
 * Inserts a suggested onboarding prompt into the user's current chat composer draft
 * WITHOUT ever auto-sending it.
 */
fun applySuggestedPromptToDraft(existingDraft: String, suggestedPrompt: String): String {
    val trimmedPrompt = suggestedPrompt.trim()
    if (trimmedPrompt.isEmpty()) return existingDraft
    val trimmedExisting = existingDraft.trim()
    return if (trimmedExisting.isEmpty()) {
        trimmedPrompt
    } else if (trimmedExisting.contains(trimmedPrompt)) {
        existingDraft
    } else {
        "$trimmedExisting\n\n$trimmedPrompt"
    }
}

// ===========================================================================
// 14.4 · ApplicationExitInfo Crash Report & Dismissal Filter
// ===========================================================================

data class PreviousExitCrashInfo(
    val reasonCode: Int,
    val reasonName: String,
    val timestampMs: Long,
    val processName: String,
    val description: String?,
    val traceSnippet: String?,
) {
    /** Only Crash (4), Crash Native (5), and ANR (6) are actionable crash reports. */
    val isActionableCrashOrAnr: Boolean
        get() = reasonCode == 4 || reasonCode == 5 || reasonCode == 6

    fun toBugReportMarkdown(appVersionName: String, appVersionCode: Int, deviceModel: String): String = buildString {
        appendLine("### Android App Exit Report")
        appendLine("- **App Version**: `$appVersionName` (`$appVersionCode`)")
        appendLine("- **Device**: `$deviceModel`)")
        appendLine("- **Reason**: `$reasonName` (code `$reasonCode`)")
        appendLine("- **Timestamp**: `$timestampMs`")
        if (!description.isNullOrBlank()) {
            appendLine("- **Description**: ${description.trim()}")
        }
        if (!traceSnippet.isNullOrBlank()) {
            appendLine()
            appendLine("```text")
            appendLine(traceSnippet.take(4_000).trim())
            appendLine("```")
        }
    }
}

fun shouldShowCrashExit(
    info: PreviousExitCrashInfo?,
    dismissedTimestampMs: Long?,
): Boolean {
    if (info == null || !info.isActionableCrashOrAnr) return false
    return dismissedTimestampMs == null || info.timestampMs > dismissedTimestampMs
}
