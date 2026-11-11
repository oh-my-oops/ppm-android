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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.PrefsStore
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
import com.hienlh.ppm.state.shell.OnboardingEvidence
import com.hienlh.ppm.state.shell.OnboardingFamiliarity
import com.hienlh.ppm.state.shell.OnboardingGoalId
import com.hienlh.ppm.state.shell.OnboardingState
import com.hienlh.ppm.state.shell.OnboardingStepDef
import com.hienlh.ppm.state.shell.applySuggestedPromptToDraft
import kotlinx.coroutines.CoroutineScope

/**
 * First-time onboarding, guided tour, Quick Orientation, and `"PPM has new features"` (`Phone 2 · OB1–OB8`
 * and `Tablet 2 · OB1–OB4`, Requirement 5.6):
 * - `OB1`: Welcome card (`"Welcome to PPM"`, `"Start guided tour"`, `"Quick orientation"`, `"Skip for now"`)
 * - `OB2`: 2-step setup (`OnboardingFamiliarity` & `OnboardingGoalId` radio cards)
 * - `OB3–OB5`: Non-modal floating tour card with target highlight, `"Do it"`, suggested prompt insertion
 *   (into the composer draft without auto-sending), and collapse-to-pill (`OB4`)
 * - `OB6`: Completion card (`"You're all set"`)
 * - `OB7`: 2-page Quick Orientation (`"Spaces & Navigation"` / `"AI Chat, Files & Tools"`)
 * - `OB8`: `"PPM has new features"` (`onboarding-feature-intro.tsx`): one step per feature, Turn on / Turn off /
 *   Later, asked once per install through `/api/feature-intros` ([PhoneFeatureIntros]).
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

    /** The new-features question for the connected server; null with no server. */
    var featureIntros by mutableStateOf<PhoneFeatureIntros?>(null)
        private set

    /** This device has never seen the basic tour: an install already in use is then not offered it. */
    private val neverSawTour = prefs?.get(PREF_KEY_STATE) == null && prefs?.get(PREF_KEY_DISMISSED) == null

    /** The basic tour is on screen (being set up, running, finished but not closed, or the orientation). */
    val tourInProgress: Boolean get() = phase != OnboardingPhase.HIDDEN

    /** The basic tour was finished or dismissed on this device. */
    val tourDone: Boolean get() = prefs?.get(PREF_KEY_DISMISSED) == "1" || !state.active

    /**
     * Whether the Welcome card may show: not before the server has said which kind of install this
     * is, and never as an offer to an install already in use that this device has not shown the tour.
     */
    val welcomeOffered: Boolean
        get() {
            val intros = featureIntros ?: return true
            if (!intros.settled) return false
            return !(neverSawTour && intros.view?.isExistingUser == true)
        }

    /** Whether the new-features part shows now (`useFeaturePartVisible`). */
    val featurePartVisible: Boolean
        get() {
            val intros = featureIntros ?: return false
            val tourOnScreen = tourInProgress && !(phase == OnboardingPhase.WELCOME && !welcomeOffered)
            return intros.allowed(tourInProgress = tourOnScreen, tourDone = tourDone)
        }

    /** Binds the question to [backend] (a new server is a new app open for it) and loads it. */
    fun bindFeatureIntros(backend: FeatureIntrosBackend?) {
        featureIntros = backend?.let { PhoneFeatureIntros(it, scope).also(PhoneFeatureIntros::load) }
    }

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
        // What was asked and answered is the server's; this device only starts the question afresh.
        featureIntros?.let {
            it.reset()
            it.load()
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
        controller.bindFeatureIntros(model.api?.let(FeatureIntrosBackend::api))
    }
    // Answered, put off or settled on any device: load again, and a question put off elsewhere is
    // put off here too, with no skip of this device's own.
    DisposableEffect(model.events, controller.featureIntros) {
        val intros = controller.featureIntros
        val off = model.events?.onFeatureIntrosChanged { change, id ->
            if (intros == null) return@onFeatureIntrosChanged
            if (change == "skip" && id != null) intros.skippedElsewhere(id)
            intros.load()
        }
        onDispose { off?.invoke() }
    }

    val featureIntros = controller.featureIntros
    if (controller.featurePartVisible && featureIntros != null) {
        val current = featureIntros.current
        if (current != null) {
            OnboardingModalBackdrop(onDismiss = featureIntros::close) {
                FeatureIntroCard(
                    intro = current,
                    position = featureIntros.steps.indexOf(current.id) + 1,
                    total = featureIntros.steps.size,
                    busy = featureIntros.busy,
                    error = featureIntros.error,
                    onAnswer = { on -> featureIntros.answer(current.id, on) },
                    onLater = { featureIntros.later(current.id) },
                    onClose = featureIntros::close,
                )
            }
        }
        return
    }

    when (controller.phase) {
        OnboardingPhase.HIDDEN -> Unit

        OnboardingPhase.WELCOME -> if (controller.welcomeOffered) {
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

/** One feature's step (`FeatureIntroStep` of `onboarding-feature-intro.tsx`). */
@Composable
private fun FeatureIntroCard(
    intro: FeatureIntro,
    position: Int,
    total: Int,
    busy: Boolean,
    error: String?,
    onAnswer: (on: Boolean) -> Unit,
    onLater: () -> Unit,
    onClose: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PpmIcon(ProductIcon.Sparkles, 16.dp, tokens.accent.color)
        BasicText(
            "PPM HAS NEW FEATURES" + (if (total > 1) " · " + position + " OF " + total else ""),
            Modifier.weight(1f).testTag("phone-feature-intro-counter"),
            style = ppmText(11f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.accent.color),
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .ppmClickable(pressedColor = tokens.panel.color, onClickLabel = "Close, ask me later", onClick = onClose)
                .testTag("phone-feature-intro-close"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.X, 16.dp, tokens.text3.color)
        }
    }

    BasicText(
        intro.title,
        style = ppmText(16f, FontWeight.SemiBold, lineHeight = 22f).copy(color = tokens.text.color),
    )
    BasicText(
        intro.body,
        Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        style = ppmText(14f, lineHeight = 21f).copy(color = tokens.text2.color),
    )
    if (error != null) {
        BasicText(
            error,
            Modifier.testTag("phone-feature-intro-error"),
            style = ppmText(13f, lineHeight = 18f).copy(color = tokens.error.color),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnboardingPrimaryButton("Turn on", Modifier.weight(1f).testTag("phone-feature-intro-on"), enabled = !busy) { onAnswer(true) }
            OnboardingSecondaryButton("Turn off", Modifier.weight(1f).testTag("phone-feature-intro-off"), enabled = !busy) { onAnswer(false) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .ppmClickable(enabled = !busy, pressedColor = tokens.panel.color, onClick = onLater)
                .testTag("phone-feature-intro-later"),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("Later", style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text2.color))
        }
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(42.dp)
            .clip(shape)
            .background(tokens.accent.opacity(if (enabled) 1.0 else 0.5).color)
            .ppmClickable(enabled = enabled, pressedColor = tokens.accent.opacity(0.85).color, onClick = onClick)
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
    enabled: Boolean = true,
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
            .ppmClickable(enabled = enabled, pressedColor = tokens.panel2.color, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = ppmText(13f, FontWeight.Medium, lineHeight = 18f).copy(color = tokens.text.color),
        )
    }
}
