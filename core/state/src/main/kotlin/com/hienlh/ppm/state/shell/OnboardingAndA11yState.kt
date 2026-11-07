package com.hienlh.ppm.state.shell

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.string
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
    /** Tag attached via Modifier.testTag("onboarding-<targetTag>") on the target Composable. */
    val targetTag: String,
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
        targetTag = "onboarding-project",
    ),
    OnboardingStepDef(
        id = "ai.chat",
        goal = OnboardingGoalId.AI,
        title = "Open an AI Chat tab",
        instruction = "Start a new chat session in the current project.",
        targetTag = "onboarding-chat",
    ),
    OnboardingStepDef(
        id = "ai.send",
        goal = OnboardingGoalId.AI,
        title = "Draft a prompt in the composer",
        instruction = "Try inserting a starter prompt into the composer box and review it before sending.",
        targetTag = "onboarding-send",
        suggestedPrompt = "Summarize the architecture of this repository and list the main entry points.",
    ),
    OnboardingStepDef(
        id = "ai.history",
        goal = OnboardingGoalId.AI,
        title = "Browse chat history",
        instruction = "Check your previous AI sessions in the Chats list.",
        targetTag = "onboarding-history",
    ),
    // Goal: EXPLORE
    OnboardingStepDef(
        id = "explore.project",
        goal = OnboardingGoalId.EXPLORE,
        title = "Select a project",
        instruction = "Pick a project workspace to inspect its files.",
        targetTag = "onboarding-project",
    ),
    OnboardingStepDef(
        id = "explore.file",
        goal = OnboardingGoalId.EXPLORE,
        title = "Open a file from Explorer",
        instruction = "Browse the project file tree and tap any source file to view or edit it.",
        targetTag = "onboarding-file",
    ),
    OnboardingStepDef(
        id = "explore.search",
        goal = OnboardingGoalId.EXPLORE,
        title = "Search across files",
        instruction = "Use project search or Command Palette to find symbols or text.",
        targetTag = "onboarding-search",
    ),
    // Goal: DEVELOPER
    OnboardingStepDef(
        id = "developer.project",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Select a project",
        instruction = "Open your development repository.",
        targetTag = "onboarding-project",
    ),
    OnboardingStepDef(
        id = "developer.terminal",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Open a Terminal session",
        instruction = "Launch an interactive shell in your project directory.",
        targetTag = "onboarding-terminal",
    ),
    OnboardingStepDef(
        id = "developer.git",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Inspect Source Control",
        instruction = "Review working tree changes, stage hunks, or view Git Graph.",
        targetTag = "onboarding-git",
    ),
    OnboardingStepDef(
        id = "developer.run",
        goal = OnboardingGoalId.DEVELOPER,
        title = "Preview or run your app",
        instruction = "Use Web Preview, Port Forwarding, or ask AI to run tests.",
        targetTag = "onboarding-run",
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
// 13b.2 & 13b.3 · Mobile Ergonomics, Contrast Ratio & Accessibility Helpers
// ===========================================================================

object MobileErgonomicsSpec {
    const val MIN_TOUCH_TARGET_DP = 44
    const val PRIMARY_ACTION_BOTTOM_ZONE_FRACTION = 0.333f // bottom 1/3 of screen
    const val WCAG_AA_NORMAL_CONTRAST = 4.5
    const val WCAG_AA_LARGE_CONTRAST = 3.0
}

/**
 * Returns true if an interactive control's height/width meets the 44 dp mobile minimum.
 */
fun meetsMobileTouchTarget(widthDp: Int, heightDp: Int): Boolean =
    widthDp >= MobileErgonomicsSpec.MIN_TOUCH_TARGET_DP &&
        heightDp >= MobileErgonomicsSpec.MIN_TOUCH_TARGET_DP

/**
 * Returns true if a primary mobile action bar/button at [centerYgerPx] in a viewport of
 * [screenHeightPx] resides in the bottom 1/3 ergonomic thumb zone.
 */
fun isInBottomThumbZone(centerYPx: Int, screenHeightPx: Int): Boolean {
    if (screenHeightPx <= 0) return false
    val thresholdY = screenHeightPx * (1f - MobileErgonomicsSpec.PRIMARY_ACTION_BOTTOM_ZONE_FRACTION)
    return centerYPx >= thresholdY
}

/**
 * Computes WCAG 2.1 relative luminance for an sRGB `(r, g, b)` color (`0..255` each).
 */
fun relativeLuminance(r: Int, g: Int, b: Int): Double {
    fun channel(c: Int): Double {
        val s = (c.coerceIn(0, 255)) / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/**
 * Computes WCAG 2.1 contrast ratio (`1.0..21.0`) between two sRGB colors `0xRRGGBB`.
 */
fun wcagContrastRatio(fgRgb: Int, bgRgb: Int): Double {
    val l1 = relativeLuminance((fgRgb shr 16) and 0xFF, (fgRgb shr 8) and 0xFF, fgRgb and 0xFF)
    val l2 = relativeLuminance((bgRgb shr 16) and 0xFF, (bgRgb shr 8) and 0xFF, bgRgb and 0xFF)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

enum class SurfaceFontRole {
    READING, // Chat prose, Settings, dialogs -> scales with system font size
    CODE_FIXED, // Code blocks, Editor, Terminal, DB Grid -> keeps dedicated zoom/font size
}

fun effectiveFontScaleForRole(role: SurfaceFontRole, systemFontScale: Float): Float = when (role) {
    SurfaceFontRole.READING -> systemFontScale.coerceIn(0.85f, 2.0f)
    SurfaceFontRole.CODE_FIXED -> 1.0f
}

fun formatTurnCompleteAnnouncement(
    projectName: String?,
    sessionTitle: String?,
    hasPendingApproval: Boolean = false,
): String {
    val titlePart = sessionTitle?.takeIf { it.isNotBlank() } ?: "Chat"
    val projPart = projectName?.takeIf { it.isNotBlank() }?.let { " in $it" }.orEmpty()
    return if (hasPendingApproval) {
        "AI waiting for approval in $titlePart$projPart"
    } else {
        "AI response finished in $titlePart$projPart"
    }
}

// ===========================================================================
// Phase 14 · Release Compatibility, ApplicationExitInfo Crash Report & SDK 37
// ===========================================================================

enum class ProtocolCompatStatus {
    COMPATIBLE,
    SERVER_UPDATE_RECOMMENDED,
    APP_UPDATE_REQUIRED,
}

data class ProtocolCompatibilityResult(
    val status: ProtocolCompatStatus,
    val appProtocolVersion: Int,
    val serverProtocolVersion: Int?,
    val missingCapabilities: List<String>,
    val bannerMessage: String?,
)

fun checkProtocolCompatibility(
    appProtocolVersion: Int,
    serverProtocolVersion: Int?,
    serverCapabilities: Set<String> = emptySet(),
    requiredCapabilities: Set<String> = emptySet(),
): ProtocolCompatibilityResult {
    val missing = requiredCapabilities.filter { it !in serverCapabilities }
    if (serverProtocolVersion == null) {
        return ProtocolCompatibilityResult(
            status = if (missing.isEmpty()) ProtocolCompatStatus.COMPATIBLE else ProtocolCompatStatus.SERVER_UPDATE_RECOMMENDED,
            appProtocolVersion = appProtocolVersion,
            serverProtocolVersion = null,
            missingCapabilities = missing,
            bannerMessage = if (missing.isEmpty()) null else "Update PPM on this machine to enable: ${missing.joinToString(", ")}",
        )
    }
    return when {
        serverProtocolVersion < appProtocolVersion || missing.isNotEmpty() -> ProtocolCompatibilityResult(
            status = ProtocolCompatStatus.SERVER_UPDATE_RECOMMENDED,
            appProtocolVersion = appProtocolVersion,
            serverProtocolVersion = serverProtocolVersion,
            missingCapabilities = missing,
            bannerMessage = "Update PPM on this machine (server protocol v$serverProtocolVersion, app expects v$appProtocolVersion).",
        )
        serverProtocolVersion > appProtocolVersion + 1 -> ProtocolCompatibilityResult(
            status = ProtocolCompatStatus.APP_UPDATE_REQUIRED,
            appProtocolVersion = appProtocolVersion,
            serverProtocolVersion = serverProtocolVersion,
            missingCapabilities = emptyList(),
            bannerMessage = "A newer version of the PPM Android app is recommended for this server.",
        )
        else -> ProtocolCompatibilityResult(
            status = ProtocolCompatStatus.COMPATIBLE,
            appProtocolVersion = appProtocolVersion,
            serverProtocolVersion = serverProtocolVersion,
            missingCapabilities = emptyList(),
            bannerMessage = null,
        )
    }
}

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
        appendLine("- **Device**: `$deviceModel`")
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
