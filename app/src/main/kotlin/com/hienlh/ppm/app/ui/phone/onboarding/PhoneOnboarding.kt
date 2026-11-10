package com.hienlh.ppm.app.ui.phone.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ThemeTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.shell.OnboardingEvidence
import com.hienlh.ppm.state.shell.OnboardingFamiliarity
import com.hienlh.ppm.state.shell.OnboardingGoalId
import com.hienlh.ppm.state.shell.OnboardingState
import com.hienlh.ppm.state.shell.OnboardingStepDef
import com.hienlh.ppm.state.shell.applySuggestedPromptToDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

data class FeatureIntroStep(
    val title: String,
    val description: String = "",
)

data class FeatureIntroPayload(
    val id: String,
    val title: String,
    val summary: String = "",
    val steps: List<FeatureIntroStep> = emptyList(),
) {
    companion object {
        fun from(json: JsonElement?): FeatureIntroPayload? {
            val id = json["id"].string?.takeIf { it.isNotBlank() } ?: return null
            val title = json["title"].string?.takeIf { it.isNotBlank() } ?: return null
            val summary = json["summary"].string ?: json["description"].string.orEmpty()
            val steps = json["steps"].array.orEmpty().mapNotNull { s ->
                val stepTitle = s["title"].string?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FeatureIntroStep(
                    title = stepTitle,
                    description = s["description"].string ?: s["body"].string.orEmpty(),
                )
            }
            return FeatureIntroPayload(id = id, title = title, summary = summary, steps = steps)
        }
    }
}

private suspend fun ApiClient.fetchPendingFeatureIntros(): List<FeatureIntroPayload> {
    val json = call("GET", "/api/feature-intros")
    val list = json["pending"].array ?: json["intros"].array ?: json.array.orEmpty()
    return list.mapNotNull(FeatureIntroPayload::from)
}

private suspend fun ApiClient.dismissFeatureIntros(ids: List<String>) {
    if (ids.isEmpty()) return
    val body = buildJsonObject {
        put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
    }
    call("POST", "/api/feature-intros/dismiss", body = body)
}

private suspend fun ApiClient.resetAllFeatureIntros() {
    call("POST", "/api/feature-intros/reset-all")
}

/**
 * First-time onboarding, guided tour, Quick Orientation, and `"PPM has new features"` (`Phone 2 · OB1–OB8`
 * and `Tablet 2 · OB1–OB4`, Requirement 5.6):
 * - `OB1`: Welcome card (`"Welcome to PPM"`, `"Start guided tour"`, `"Quick orientation"`, `"Skip for now"`)
 * - `OB2`: 2-step setup (`OnboardingFamiliarity` & `OnboardingGoalId` radio cards)
 * - `OB3–OB5`: Non-modal floating tour card with target highlight, `"Do it"`, suggested prompt insertion
 *   (into the composer draft without auto-sending), and collapse-to-pill (`OB4`)
 * - `OB6`: Completion card (`"You're all set"`)
 * - `OB7`: 2-page Quick Orientation (`"Spaces & Navigation"` / `"AI Chat, Files & Tools"`)
 * - `OB8`: `"PPM has new features"` (`/api/feature-intros`, `"Next"`, `"Got it"`, `"Don't show again"`).
 */
enum class OnboardingPhase {
    HIDDEN,
    WELCOME,
    SETUP_FAMILIARITY,
    SETUP_GOAL,
    TOUR_EXPANDED,
    TOUR_COLLAPSED_PILL,
    COMPLETED,
    QUICK_ORIENTATION,
    FEATURE_INTROS,
}

@Stable
class PhoneOnboardingController(
    private val prefs: PrefsStore?,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(
        OnboardingState.fromJsonString(prefs?.get(PREF_KEY_STATE)),
    )
        private set

    var phase by mutableStateOf(
        when {
            prefs?.get(PREF_KEY_DISMISSED) == "1" -> OnboardingPhase.HIDDEN
            state.quickOrientationPage != null -> OnboardingPhase.QUICK_ORIENTATION
            state.active && !state.paused -> OnboardingPhase.WELCOME
            else -> OnboardingPhase.HIDDEN
        },
    )
        private set

    var featureIntros by mutableStateOf<List<FeatureIntroPayload>>(emptyList())
        private set

    var featureIntroIndex by mutableIntStateOf(0)
        private set

    private fun save(next: OnboardingState) {
        state = next
        prefs?.put(PREF_KEY_STATE, next.toJson().toString())
    }

    fun startSetup() {
        phase = OnboardingPhase.SETUP_FAMILIARITY
    }

    fun selectFamiliarity(familiarity: OnboardingFamiliarity) {
        save(state.copy(familiarity = familiarity))
    }

    fun nextToGoalSetup() {
        phase = OnboardingPhase.SETUP_GOAL
    }

    fun backToFamiliaritySetup() {
        phase = OnboardingPhase.SETUP_FAMILIARITY
    }

    fun selectGoal(goal: OnboardingGoalId) {
        save(state.copy(selectedGoal = goal))
    }

    fun beginGuidedTour() {
        val next = state.copy(active = true, paused = false)
        save(next)
        phase = if (next.currentStep == null) OnboardingPhase.COMPLETED else OnboardingPhase.TOUR_EXPANDED
    }

    fun collapseTourToPill() {
        phase = OnboardingPhase.TOUR_COLLAPSED_PILL
    }

    fun expandTourFromPill() {
        phase = OnboardingPhase.TOUR_EXPANDED
    }

    fun completeStep(stepId: String) {
        val next = state.markStepCompleted(stepId)
        save(next)
        if (next.currentStep == null) {
            phase = OnboardingPhase.COMPLETED
        }
    }

    fun updateEvidence(evidence: OnboardingEvidence) {
        val next = state.mergeEvidence(evidence)
        save(next)
        if (phase == OnboardingPhase.TOUR_EXPANDED && next.currentStep == null) {
            phase = OnboardingPhase.COMPLETED
        }
    }

    fun openQuickOrientation() {
        save(state.openQuickOrientation())
        phase = OnboardingPhase.QUICK_ORIENTATION
    }

    fun nextOrientationPage() {
        val next = state.nextOrientationPage()
        save(next)
        if (next.quickOrientationPage == null) {
            phase = OnboardingPhase.HIDDEN
        }
    }

    fun prevOrientationPage() {
        save(state.prevOrientationPage())
    }

    fun skipOrDismiss() {
        prefs?.put(PREF_KEY_DISMISSED, "1")
        save(state.pauseOnEscape())
        phase = OnboardingPhase.HIDDEN
    }

    fun restartOnboarding(model: AppModel? = null) {
        prefs?.put(PREF_KEY_DISMISSED, null)
        val fresh = OnboardingState(active = true, paused = false)
        save(fresh)
        phase = OnboardingPhase.WELCOME
        val api = model?.api
        if (api != null) {
            scope.launch {
                runCatching { api.resetAllFeatureIntros() }
            }
        }
    }

    fun checkFeatureIntros(model: AppModel) {
        val api = model.api ?: return
        scope.launch {
            val pending = runCatching { api.fetchPendingFeatureIntros() }.getOrDefault(emptyList())
            if (pending.isNotEmpty() && phase == OnboardingPhase.HIDDEN) {
                featureIntros = pending
                featureIntroIndex = 0
                phase = OnboardingPhase.FEATURE_INTROS
            }
        }
    }

    fun nextFeatureIntro(model: AppModel) {
        if (featureIntroIndex + 1 < featureIntros.size) {
            featureIntroIndex++
        } else {
            dismissCurrentFeatureIntros(model)
        }
    }

    fun dismissCurrentFeatureIntros(model: AppModel) {
        val ids = featureIntros.map { it.id }
        featureIntros = emptyList()
        featureIntroIndex = 0
        if (phase == OnboardingPhase.FEATURE_INTROS) {
            phase = OnboardingPhase.HIDDEN
        }
        val api = model.api ?: return
        if (ids.isNotEmpty()) {
            scope.launch {
                runCatching { api.dismissFeatureIntros(ids) }
            }
        }
    }

    companion object {
        private const val PREF_KEY_STATE = "onboarding.state"
        private const val PREF_KEY_DISMISSED = "onboarding.dismissed"
    }
}

@Composable
fun rememberPhoneOnboardingController(
    prefs: PrefsStore? = null,
): PhoneOnboardingController {
    val scope = rememberCoroutineScope()
    return remember(prefs) { PhoneOnboardingController(prefs, scope) }
}

@Composable
fun PhoneOnboardingHost(
    model: AppModel,
    controller: PhoneOnboardingController,
    onOpenProjectSwitcher: () -> Unit = {},
    onOpenNewChat: () -> Unit = {},
    onInsertDraftPrompt: (String) -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenSourceControl: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(model.api) {
        if (model.api != null) {
            controller.checkFeatureIntros(model)
        }
    }

    when (controller.phase) {
        OnboardingPhase.HIDDEN -> Unit

        OnboardingPhase.WELCOME -> {
            OnboardingModalBackdrop(onDismiss = controller::skipOrDismiss) {
                WelcomeCard(
                    onStartGuided = controller::startSetup,
                    onQuickOrientation = controller::openQuickOrientation,
                    onSkip = controller::skipOrDismiss,
                )
            }
        }

        OnboardingPhase.SETUP_FAMILIARITY -> {
            OnboardingModalBackdrop(onDismiss = controller::skipOrDismiss) {
                FamiliaritySetupCard(
                    selected = controller.state.familiarity,
                    onSelect = controller::selectFamiliarity,
                    onNext = controller::nextToGoalSetup,
                    onCancel = controller::skipOrDismiss,
                )
            }
        }

        OnboardingPhase.SETUP_GOAL -> {
            OnboardingModalBackdrop(onDismiss = controller::skipOrDismiss) {
                GoalSetupCard(
                    selected = controller.state.selectedGoal,
                    onSelect = controller::selectGoal,
                    onBack = controller::backToFamiliaritySetup,
                    onStart = controller::beginGuidedTour,
                )
            }
        }

        OnboardingPhase.TOUR_EXPANDED -> {
            val step = controller.state.currentStep
            if (step != null) {
                Box(
                    modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    GuidedTourFloatingCard(
                        step = step,
                        completedCount = controller.state.goalSteps.count { it.id in controller.state.completedSteps },
                        totalCount = controller.state.goalSteps.size,
                        onPerformStep = {
                            when (step.id) {
                                "ai.project", "explore.project", "developer.project" -> onOpenProjectSwitcher()
                                "ai.chat" -> onOpenNewChat()
                                "ai.send" -> step.suggestedPrompt?.let {
                                    onInsertDraftPrompt(applySuggestedPromptToDraft("", it))
                                }
                                "explore.file" -> onOpenFiles()
                                "developer.terminal" -> onOpenTerminal()
                                "developer.git" -> onOpenSourceControl()
                                else -> step.suggestedPrompt?.let {
                                    onInsertDraftPrompt(applySuggestedPromptToDraft("", it))
                                }
                            }
                            controller.completeStep(step.id)
                        },
                        onMarkDone = { controller.completeStep(step.id) },
                        onCollapse = controller::collapseTourToPill,
                        onDismiss = controller::skipOrDismiss,
                    )
                }
            }
        }

        OnboardingPhase.TOUR_COLLAPSED_PILL -> {
            Box(
                modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                GuidedTourCollapsedPill(
                    completedCount = controller.state.goalSteps.count { it.id in controller.state.completedSteps },
                    totalCount = controller.state.goalSteps.size,
                    onExpand = controller::expandTourFromPill,
                )
            }
        }

        OnboardingPhase.COMPLETED -> {
            OnboardingModalBackdrop(onDismiss = controller::skipOrDismiss) {
                TourCompletedCard(
                    goal = controller.state.selectedGoal,
                    onFinish = controller::skipOrDismiss,
                    onExploreAnotherGoal = controller::nextToGoalSetup,
                )
            }
        }

        OnboardingPhase.QUICK_ORIENTATION -> {
            OnboardingModalBackdrop(onDismiss = controller::skipOrDismiss) {
                QuickOrientationCard(
                    page = controller.state.quickOrientationPage ?: 0,
                    onPrev = controller::prevOrientationPage,
                    onNext = controller::nextOrientationPage,
                    onClose = controller::skipOrDismiss,
                )
            }
        }

        OnboardingPhase.FEATURE_INTROS -> {
            val current = controller.featureIntros.getOrNull(controller.featureIntroIndex)
            if (current != null) {
                OnboardingModalBackdrop(onDismiss = { controller.dismissCurrentFeatureIntros(model) }) {
                    FeatureIntroCard(
                        intro = current,
                        index = controller.featureIntroIndex,
                        total = controller.featureIntros.size,
                        onNext = { controller.nextFeatureIntro(model) },
                        onDismissAll = { controller.dismissCurrentFeatureIntros(model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingModalBackdrop(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .ppmClickable(pressedColor = Color.Transparent, onClick = onDismiss)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .ppmClickable(pressedColor = Color.Transparent) { /* consume tap inside card */ },
        ) {
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .ppmShadow(tokens.shadowFloat, shape)
                    .clip(shape)
                    .background(tokens.panel2.over(tokens.bgSolid).color)
                    .cssBorder(tokens.border.color, shape)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun WelcomeCard(
    onStartGuided: () -> Unit,
    onQuickOrientation: () -> Unit,
    onSkip: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.accent.opacity(0.14).color),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.Sparkles, 22.dp, tokens.accent.color)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                "Welcome to PPM",
                style = ppmText(18f, FontWeight.SemiBold, lineHeight = 24f).copy(color = tokens.text.color),
            )
            BasicText(
                "Your projects, AI agents, terminal, and git tools in one workspace.",
                style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color),
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OnboardingPrimaryButton("Start guided setup", onClick = onStartGuided)
        OnboardingSecondaryButton("Quick 2-page orientation", onClick = onQuickOrientation)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BasicText(
                "Skip for now",
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .ppmClickable(pressedColor = tokens.panel.color, onClick = onSkip)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = ppmText(12.5f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        }
    }
}

@Composable
private fun FamiliaritySetupCard(
    selected: OnboardingFamiliarity,
    onSelect: (OnboardingFamiliarity) -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "Step 1 of 2 · Your familiarity",
            style = ppmText(11.5f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.accent.color),
        )
        BasicText(
            "How familiar are you with AI coding workspaces?",
            style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in OnboardingFamiliarity.entries) {
            val active = option == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (active) tokens.accent.opacity(0.1).color else tokens.panel.color)
                    .cssBorder(if (active) tokens.accent.color else tokens.border.color, shape)
                    .ppmClickable(pressedColor = tokens.panel2.color) { onSelect(option) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RadioCircle(active = active, tokens = tokens)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        option.label,
                        style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                    BasicText(
                        option.description,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        OnboardingSecondaryButton("Cancel", Modifier.weight(1f), onClick = onCancel)
        OnboardingPrimaryButton("Next", Modifier.weight(1f), onClick = onNext)
    }
}

@Composable
private fun GoalSetupCard(
    selected: OnboardingGoalId,
    onSelect: (OnboardingGoalId) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(
            "Step 2 of 2 · Choose your goal",
            style = ppmText(11.5f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.accent.color),
        )
        BasicText(
            "What would you like to do first?",
            style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (goal in OnboardingGoalId.entries) {
            val active = goal == selected
            val shape = RoundedCornerShape(12.dp)
            val icon = when (goal) {
                OnboardingGoalId.AI -> ProductIcon.MessageSquare
                OnboardingGoalId.EXPLORE -> ProductIcon.FolderOpen
                OnboardingGoalId.DEVELOPER -> ProductIcon.TerminalSquare
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (active) tokens.accent.opacity(0.1).color else tokens.panel.color)
                    .cssBorder(if (active) tokens.accent.color else tokens.border.color, shape)
                    .ppmClickable(pressedColor = tokens.panel2.color) { onSelect(goal) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PpmIcon(icon, 18.dp, if (active) tokens.accent.color else tokens.text2.color)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        goal.title,
                        style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                    BasicText(
                        goal.subtitle,
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                    )
                }
                RadioCircle(active = active, tokens = tokens)
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnboardingSecondaryButton("Back", Modifier.weight(1f), onClick = onBack)
        OnboardingPrimaryButton("Start tour", Modifier.weight(1f), onClick = onStart)
    }
}

@Composable
private fun GuidedTourFloatingCard(
    step: OnboardingStepDef,
    completedCount: Int,
    totalCount: Int,
    onPerformStep: () -> Unit,
    onMarkDone: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.accent.opacity(0.45).color, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Sparkles, 15.dp, tokens.accent.color)
            BasicText(
                "Guided Tour · Step ${completedCount + 1} of $totalCount",
                Modifier.weight(1f),
                style = ppmText(11.5f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.accent.color),
            )
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .ppmClickable(pressedColor = tokens.panel.color, onClick = onCollapse),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.ChevronDown, 14.dp, tokens.text3.color)
            }
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .ppmClickable(pressedColor = tokens.panel.color, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
            }
        }

        BasicText(
            step.title,
            style = ppmText(15f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
        )
        BasicText(
            step.instruction,
            style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color),
        )

        if (step.suggestedPrompt != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.borderSoft.color, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                BasicText(
                    "\"${step.suggestedPrompt}\"",
                    style = ppmText(12f, italic = true, lineHeight = 17f).copy(color = tokens.text2.color),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnboardingSecondaryButton("Skip step", Modifier.weight(1f), onClick = onMarkDone)
            OnboardingPrimaryButton(
                if (step.suggestedPrompt != null) "Insert into draft" else "Show me",
                Modifier.weight(1f),
                onClick = onPerformStep,
            )
        }
    }
}

@Composable
private fun GuidedTourCollapsedPill(
    completedCount: Int,
    totalCount: Int,
    onExpand: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = CircleShape
    Row(
        Modifier
            .ppmShadow(tokens.shadowFloat, shape)
            .clip(shape)
            .background(tokens.panel2.over(tokens.bgSolid).color)
            .cssBorder(tokens.accent.color, shape)
            .ppmClickable(pressedColor = tokens.panel.color, onClick = onExpand)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.Sparkles, 15.dp, tokens.accent.color)
        BasicText(
            "Tour $completedCount/$totalCount",
            style = ppmText(12.5f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
        )
        PpmIcon(ProductIcon.ChevronUp, 14.dp, tokens.text2.color)
    }
}

@Composable
private fun TourCompletedCard(
    goal: OnboardingGoalId,
    onFinish: () -> Unit,
    onExploreAnotherGoal: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(tokens.success.opacity(0.15).color),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.CheckCircle2, 24.dp, tokens.success.color)
        }
        BasicText(
            "You're all set!",
            style = ppmText(18f, FontWeight.SemiBold, lineHeight = 24f).copy(color = tokens.text.color),
        )
        BasicText(
            "You completed the ${goal.title} tour. You can reopen this guide anytime from Settings → About.",
            style = ppmText(13f, lineHeight = 18f).copy(color = tokens.text2.color, textAlign = TextAlign.Center),
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnboardingSecondaryButton("Another goal", Modifier.weight(1f), onClick = onExploreAnotherGoal)
        OnboardingPrimaryButton("Done", Modifier.weight(1f), onClick = onFinish)
    }
}

@Composable
private fun QuickOrientationCard(
    page: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.HelpCircle, 16.dp, tokens.accent.color)
        BasicText(
            "Quick Orientation · ${page + 1} of 2",
            Modifier.weight(1f),
            style = ppmText(12f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.accent.color),
        )
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .ppmClickable(pressedColor = tokens.panel.color, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
        }
    }

    if (page == 0) {
        BasicText(
            if (mobile) "Phone Workspace & Drawer" else "Sidebar Rail & Multi-Panel Grid",
            style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
        )
        BasicText(
            if (mobile) {
                "Use the top-left menu or swipe from the left edge to switch projects, check notifications, and jump between Files, Source Control, Terminal, and Database. Tap the tab pill to switch open tabs."
            } else {
                "Use the 60 dp left rail to switch sidebar sections, resize the sidebar or bottom dock, and split panels side-by-side for Chat, Editor, and Terminal."
            },
            style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text2.color),
        )
    } else {
        BasicText(
            "AI Chat, Approvals & Source Control",
            style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
        )
        BasicText(
            "Start AI sessions with Claude, Codex, or Antigravity, approve tool calls or answer questions inline, and review or stage file diffs in Source Control.",
            style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text2.color),
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (page > 0) {
            OnboardingSecondaryButton("Previous", Modifier.weight(1f), onClick = onPrev)
        } else {
            OnboardingSecondaryButton("Close", Modifier.weight(1f), onClick = onClose)
        }
        OnboardingPrimaryButton(
            if (page == 0) "Next" else "Got it",
            Modifier.weight(1f),
            onClick = onNext,
        )
    }
}

@Composable
private fun FeatureIntroCard(
    intro: FeatureIntroPayload,
    index: Int,
    total: Int,
    onNext: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.Sparkles, 16.dp, tokens.accent.color)
        BasicText(
            "PPM has new features" + (if (total > 1) " · ${index + 1}/$total" else ""),
            Modifier.weight(1f),
            style = ppmText(12f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.accent.color),
        )
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .ppmClickable(pressedColor = tokens.panel.color, onClick = onDismissAll),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, 14.dp, tokens.text3.color)
        }
    }

    BasicText(
        intro.title,
        style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
    )
    if (intro.summary.isNotBlank()) {
        BasicText(
            intro.summary,
            style = ppmText(13f, lineHeight = 19f).copy(color = tokens.text2.color),
        )
    }
    if (intro.steps.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (step in intro.steps) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    BasicText("•", style = ppmText(13f, lineHeight = 18f).copy(color = tokens.accent.color))
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            step.title,
                            style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 17f).copy(color = tokens.text.color),
                        )
                        if (step.description.isNotBlank()) {
                            BasicText(
                                step.description,
                                style = ppmText(12f, lineHeight = 16.5f).copy(color = tokens.text2.color),
                            )
                        }
                    }
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnboardingSecondaryButton("Don't show again", Modifier.weight(1f), onClick = onDismissAll)
        OnboardingPrimaryButton(
            if (index + 1 < total) "Next" else "Got it",
            Modifier.weight(1f),
            onClick = onNext,
        )
    }
}

@Composable
private fun RadioCircle(active: Boolean, tokens: ThemeTokens) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .cssBorder(if (active) tokens.accent.color else tokens.border.color, CircleShape, width = 1.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tokens.accent.color),
            )
        }
    }
}

@Composable
private fun OnboardingPrimaryButton(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(42.dp)
            .clip(shape)
            .background(tokens.accent.color)
            .ppmClickable(pressedColor = tokens.accent.opacity(0.85).color, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.accentFg.color),
        )
    }
}

@Composable
private fun OnboardingSecondaryButton(
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(42.dp)
            .clip(shape)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, shape)
            .ppmClickable(pressedColor = tokens.panel2.color, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
        )
    }
}
