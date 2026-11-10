package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.ProviderBadge
import com.hienlh.ppm.chat.RelativeTime
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ProviderLogo
import com.hienlh.ppm.design.ProviderLogos
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.SessionInfo
import java.util.Locale

/**
 * Provider option shown in the phone Model & Effort sheet
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 1 A2 & A3).
 */
data class PhoneProviderOption(
    val id: String,
    val name: String,
    val subtitle: String,
    val supportsThinkingToggle: Boolean = id.equals("claude", ignoreCase = true),
    val effortLevels: List<String> = if (id.equals("antigravity", ignoreCase = true) || id.equals("agy", ignoreCase = true)) {
        listOf("Low", "Medium", "High")
    } else {
        listOf("Low", "Medium", "High", "Max")
    },
)

/**
 * Model option shown in the phone Model & Effort sheet (60 dp rows).
 */
data class PhoneModelOption(
    val id: String,
    val name: String,
    val description: String,
    val defaultEffort: String = "Medium",
    val badge: String? = null,
)

/**
 * Permission mode option shown in the phone Modes sheet
 * (`designs/ppm-mobile-tablet-redesign/phone/1-chat/index.html`, Section 1 A4).
 * Keyboard shortcut hints (`Shift+Tab`) are omitted on touch per Note 68.
 */
data class PhonePermissionModeOption(
    val id: String,
    val title: String,
    val description: String,
    val destructive: Boolean = false,
) {
    companion object {
        val Defaults: List<PhonePermissionModeOption> = listOf(
            PhonePermissionModeOption(
                id = "default",
                title = "Ask before edits",
                description = "Reads files freely; asks before editing files or running shell commands.",
            ),
            PhonePermissionModeOption(
                id = "acceptEdits",
                title = "Edit automatically",
                description = "Applies file edits automatically; still asks before running shell commands.",
            ),
            PhonePermissionModeOption(
                id = "plan",
                title = "Plan mode",
                description = "Explores the codebase and writes a plan without changing any files.",
            ),
            PhonePermissionModeOption(
                id = "bypassPermissions",
                title = "Bypass permissions",
                description = "Runs all edits and commands without asking. Use only in trusted environments.",
                destructive = true,
            ),
        )
    }
}

/**
 * Empty new-chat welcome view on phone (Phone 1 Section 1 A1):
 * greeting, `"Quick orientation"` button, search box, `Pinned` and `Recent chats` in 64 dp
 * two-line rows, and `"Show more (N)"`.
 */
@Composable
fun PhoneNewChatWelcome(
    projectName: String,
    sessions: List<SessionInfo>,
    onOpenSession: (SessionInfo) -> Unit,
    onQuickOrientation: () -> Unit,
    modifier: Modifier = Modifier,
    initialVisibleRecentCount: Int = 4,
    startingConversation: Boolean = false,
) {
    val tokens = LocalPpmTokens.current
    val searchState = remember { TextFieldState("") }
    var showAllRecent by remember { mutableStateOf(false) }

    if (startingConversation) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PpmIcon(ProductIcon.Loader2, 18.dp, tokens.accent.color, spinning = true)
                BasicText(
                    text = "Starting conversation...",
                    style = ppmText(14f, FontWeight.Medium, lineHeight = 20f).copy(color = tokens.text2.color),
                )
            }
        }
        return
    }

    val queryText = searchState.text.toString().trim()
    val filtered = remember(sessions, queryText) {
        if (queryText.isEmpty()) {
            sessions
        } else {
            sessions.filter { it.title.contains(queryText, ignoreCase = true) }
        }
    }
    val pinned = remember(filtered) { filtered.filter { it.pinned } }
    val recentAll = remember(filtered) { filtered.filterNot { it.pinned } }
    val recentShown = if (showAllRecent) recentAll else recentAll.take(initialVisibleRecentCount)
    val hiddenCount = (recentAll.size - recentShown.size).coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Greeting + Quick orientation
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(
                text = "What would you like to build?",
                style = ppmText(20f, FontWeight.Bold, lineHeight = 26f).copy(color = tokens.text.color),
            )
            if (projectName.isNotEmpty()) {
                BasicText(
                    text = projectName,
                    style = ppmText(12.5f, FontWeight.Medium, FontKind.MONO, lineHeight = 17f)
                        .copy(color = tokens.text3.color),
                )
            }
        }

        // Quick orientation button (44 dp height)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.accentWash.color)
                .cssBorder(tokens.accentWashBorder.color, RoundedCornerShape(12.dp))
                .ppmClickable(
                    shape = RoundedCornerShape(12.dp),
                    onClickLabel = "Quick orientation",
                    onClick = onQuickOrientation,
                )
                .heightIn(min = 44.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Sparkles, 16.dp, tokens.accent.color)
            BasicText(
                text = "Quick orientation",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.accent.color),
            )
        }

        // Search input (44 dp height)
        val searchShape = RoundedCornerShape(12.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(searchShape)
                .background(tokens.panel.color)
                .cssBorder(tokens.border.color, searchShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.Search, 16.dp, tokens.text3.color)
            PpmTextField(
                state = searchState,
                placeholder = "Search chats in this project…",
                modifier = Modifier.weight(1f),
                style = ppmText(14f, lineHeight = 20f),
            )
        }

        // Pinned chats (64 dp two-line rows)
        if (pinned.isNotEmpty()) {
            WelcomeSectionHeader(title = "Pinned", icon = ProductIcon.Pin)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(14.dp)),
            ) {
                pinned.forEachIndexed { index, session ->
                    WelcomeSessionRow(
                        session = session,
                        showTopBorder = index > 0,
                        onClick = { onOpenSession(session) },
                    )
                }
            }
        }

        // Recent chats (64 dp two-line rows)
        if (recentShown.isNotEmpty()) {
            WelcomeSectionHeader(title = "Recent chats")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color, RoundedCornerShape(14.dp)),
            ) {
                recentShown.forEachIndexed { index, session ->
                    WelcomeSessionRow(
                        session = session,
                        showTopBorder = index > 0,
                        onClick = { onOpenSession(session) },
                    )
                }
                if (hiddenCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .cssBorder(tokens.borderSoft.color, sides = CssSides.Top)
                            .ppmClickable(
                                hoverColor = tokens.surfaceHover.color,
                                onClickLabel = "Show more ($hiddenCount)",
                                onClick = { showAllRecent = true },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        BasicText(
                            text = "Show more ($hiddenCount)",
                            style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.accent.color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeSectionHeader(title: String, icon: ProductIcon? = null) {
    val tokens = LocalPpmTokens.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            PpmIcon(icon, 12.dp, tokens.text3.color)
        }
        BasicText(
            text = title.uppercase(Locale.US),
            style = ppmText(11f, FontWeight.Bold, lineHeight = 14f)
                .copy(color = tokens.text3.color, letterSpacing = 0.6.sp),
        )
    }
}

@Composable
private fun WelcomeSessionRow(
    session: SessionInfo,
    showTopBorder: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val cleanTitle = PpmFormat.collapsedWhitespace(
        if (session.title.startsWith("[PPM]")) session.title.drop(7) else session.title,
    ).ifEmpty { "Untitled" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KitSpec.RowPhoneTwoLineHeight)
            .then(if (showTopBorder) Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Top) else Modifier)
            .ppmClickable(
                hoverColor = tokens.surfaceHover.color,
                onClickLabel = cleanTitle,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderBadge(session.providerId)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    text = cleanTitle,
                    modifier = Modifier.weight(1f, fill = false),
                    style = ppmText(14f, FontWeight.Medium, lineHeight = 19f).copy(color = tokens.text.color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session.tag?.let { tag ->
                    val tagColor = Rgba.hex(tag.color) ?: tokens.accent
                    BasicText(
                        text = tag.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(tagColor.opacity(0.14).color)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = ppmText(10.5f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tagColor.color),
                        maxLines = 1,
                    )
                }
            }
            val instant = session.lastActive
            if (instant != null) {
                RelativeTime(instant, ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color))
            } else {
                BasicText(
                    text = "Recent",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
        }
        PpmIcon(ProductIcon.ChevronRight, 16.dp, tokens.text3.color)
    }
}

/**
 * Stacked top-down Model & Effort bottom sheet on phone (Phone 1 Section 1 A2 & A3, Notes 66 & 67):
 * 1. `Provider` (72 dp tiles before the first message; locked notice after the first message)
 * 2. Effort override warning banner when effort overrides the model's default
 * 3. `Model` (60 dp two-line radio rows)
 * 4. `Effort` (44 dp segmented control + `Default: X · In effect: Y` helper line)
 * 5. `Thinking` toggle row (52 dp, shown for Claude only; omitted for Antigravity)
 */
@Composable
fun PhoneModelEffortSheet(
    providers: List<PhoneProviderOption>,
    selectedProviderId: String,
    models: List<PhoneModelOption>,
    selectedModelId: String,
    selectedEffort: String,
    thinkingEnabled: Boolean,
    providerLocked: Boolean,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onToggleThinking: (Boolean) -> Unit,
    onResetEffortToDefault: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val currentProvider = providers.find { it.id.equals(selectedProviderId, ignoreCase = true) }
        ?: providers.firstOrNull()
        ?: PhoneProviderOption(id = selectedProviderId, name = selectedProviderId.replaceFirstChar { it.uppercase() }, subtitle = "")
    val currentModel = models.find { it.id == selectedModelId } ?: models.firstOrNull()
    val defaultEffort = currentModel?.defaultEffort ?: "Medium"
    val effortOverridden = !selectedEffort.equals(defaultEffort, ignoreCase = true)

    Sheet(
        title = "Model & effort",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Provider section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    text = "PROVIDER",
                    style = ppmText(11f, FontWeight.Bold, lineHeight = 14f)
                        .copy(color = tokens.text3.color, letterSpacing = 0.6.sp),
                )
                if (providerLocked) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(tokens.panel.color)
                            .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ProviderBadge(currentProvider.id)
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                text = currentProvider.name,
                                style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                            )
                            BasicText(
                                text = "The provider is chosen once, before the first message.",
                                style = ppmText(11.5f, lineHeight = 16f).copy(color = tokens.text3.color),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        providers.forEach { provider ->
                            val isSelected = provider.id.equals(selectedProviderId, ignoreCase = true)
                            val tileShape = RoundedCornerShape(14.dp)
                            val bg = if (isSelected) tokens.accentWash.color else tokens.panel.color
                            val border = if (isSelected) tokens.accent.color else tokens.border.color
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(72.dp)
                                    .clip(tileShape)
                                    .background(bg)
                                    .cssBorder(border, tileShape, width = if (isSelected) 1.5.dp else 1.dp)
                                    .ppmClickable(
                                        shape = tileShape,
                                        onClickLabel = provider.name,
                                        onClick = { onSelectProvider(provider.id) },
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (ProviderLogos.has(provider.id)) {
                                        ProviderLogo(
                                            providerId = provider.id,
                                            size = 16.dp,
                                            foreground = if (isSelected) tokens.accent.color else tokens.text2.color,
                                        )
                                    } else {
                                        ProviderBadge(provider.id)
                                    }
                                    BasicText(
                                        text = provider.name,
                                        style = ppmText(
                                            size = 13f,
                                            weight = FontWeight.SemiBold,
                                            lineHeight = 17f,
                                        ).copy(color = if (isSelected) tokens.accent.color else tokens.text.color),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicText(
                                    text = provider.subtitle,
                                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Effort override notice (Note 66: placed above model list so it stays in view)
            if (effortOverridden) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.warning.opacity(0.12).color)
                        .cssBorder(tokens.warning.opacity(0.38).color, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.warning.color)
                    BasicText(
                        text = "Effort is pinned to $selectedEffort (model default is $defaultEffort).",
                        modifier = Modifier.weight(1f),
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.panel2.color)
                            .ppmClickable(
                                onClickLabel = "Use default",
                                onClick = onResetEffortToDefault,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        BasicText(
                            text = "Use default",
                            style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 15f).copy(color = tokens.warning.color),
                        )
                    }
                }
            }

            // 3. Model list (60 dp rows)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    text = "MODEL",
                    style = ppmText(11f, FontWeight.Bold, lineHeight = 14f)
                        .copy(color = tokens.text3.color, letterSpacing = 0.6.sp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(14.dp)),
                ) {
                    models.forEachIndexed { index, model ->
                        val isSelected = model.id == selectedModelId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .then(if (index > 0) Modifier.cssBorder(tokens.borderSoft.color, sides = CssSides.Top) else Modifier)
                                .background(if (isSelected) tokens.accentWash.color else Color.Transparent)
                                .ppmClickable(
                                    onClickLabel = model.name,
                                    onClick = { onSelectModel(model.id) },
                                )
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    BasicText(
                                        text = model.name,
                                        style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f)
                                            .copy(color = if (isSelected) tokens.accent.color else tokens.text.color),
                                        maxLines = 1,
                                    )
                                    model.badge?.let { badge ->
                                        BasicText(
                                            text = badge,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(tokens.panel2.color)
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = ppmText(10.5f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f)
                                                .copy(color = tokens.text3.color),
                                        )
                                    }
                                }
                                BasicText(
                                    text = model.description,
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isSelected) {
                                PpmIcon(ProductIcon.Check, 18.dp, tokens.accent.color)
                            }
                        }
                    }
                }
            }

            // 4. Effort segmented control (44 dp height) + Default / In effect helper line
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(
                    text = "EFFORT",
                    style = ppmText(11f, FontWeight.Bold, lineHeight = 14f)
                        .copy(color = tokens.text3.color, letterSpacing = 0.6.sp),
                )
                val segShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(segShape)
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, segShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    currentProvider.effortLevels.forEach { level ->
                        val active = level.equals(selectedEffort, ignoreCase = true)
                        val pillShape = RoundedCornerShape(9.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clip(pillShape)
                                .background(if (active) tokens.accent.color else Color.Transparent)
                                .ppmClickable(
                                    shape = pillShape,
                                    onClickLabel = "Effort $level",
                                    onClick = { onSelectEffort(level) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                text = level,
                                style = ppmText(
                                    size = 13f,
                                    weight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                    lineHeight = 17f,
                                ).copy(color = if (active) tokens.accentFg.color else tokens.text2.color),
                            )
                        }
                    }
                }
                BasicText(
                    text = "Default: $defaultEffort · In effect: $selectedEffort",
                    style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 16f)
                        .copy(color = tokens.text3.color),
                )
            }

            // 5. Thinking toggle (Claude only; omitted for Antigravity per A3)
            if (currentProvider.supportsThinkingToggle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.panel.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(12.dp))
                        .ppmClickable(
                            onClickLabel = "Extended thinking",
                            onClick = { onToggleThinking(!thinkingEnabled) },
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        BasicText(
                            text = "Extended thinking",
                            style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            text = "Reasons step-by-step before answering",
                            style = ppmText(11.5f, lineHeight = 15f).copy(color = tokens.text3.color),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 26.dp)
                            .clip(CircleShape)
                            .background(if (thinkingEnabled) tokens.accent.color else tokens.panel2.color)
                            .cssBorder(if (thinkingEnabled) tokens.accent.color else tokens.border.color, CircleShape)
                            .padding(3.dp),
                        contentAlignment = if (thinkingEnabled) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (thinkingEnabled) tokens.accentFg.color else tokens.text3.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Permission Modes bottom sheet on phone (Phone 1 Section 1 A4, Note 68):
 * 4 radio cards (`Ask before edits`, `Edit automatically`, `Plan mode`, `Bypass permissions` in red)
 * with descriptions always visible and keyboard shortcuts omitted.
 */
@Composable
fun PhoneModesSheet(
    selectedModeId: String,
    onSelectMode: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    modes: List<PhonePermissionModeOption> = PhonePermissionModeOption.Defaults,
) {
    val tokens = LocalPpmTokens.current
    Sheet(
        title = "Permission mode",
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            modes.forEach { mode ->
                val isSelected = mode.id == selectedModeId
                val cardShape = RoundedCornerShape(14.dp)
                val accentColor = if (mode.destructive) tokens.error else tokens.accent
                val bg = when {
                    isSelected && mode.destructive -> tokens.error.opacity(0.10).color
                    isSelected -> tokens.accentWash.color
                    else -> tokens.panel.color
                }
                val border = when {
                    isSelected -> accentColor.color
                    mode.destructive -> tokens.error.opacity(0.35).color
                    else -> tokens.border.color
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clip(cardShape)
                        .background(bg)
                        .cssBorder(border, cardShape, width = if (isSelected) 1.5.dp else 1.dp)
                        .ppmClickable(
                            shape = cardShape,
                            onClickLabel = mode.title,
                            onClick = {
                                onSelectMode(mode.id)
                                onDismiss()
                            },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .semantics { contentDescription = mode.title },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Radio indicator
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .cssBorder(
                                color = if (isSelected) accentColor.color else tokens.border.color,
                                shape = CircleShape,
                                width = 2.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.color),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        BasicText(
                            text = mode.title,
                            style = ppmText(14f, FontWeight.SemiBold, lineHeight = 19f)
                                .copy(color = if (mode.destructive) tokens.error.color else tokens.text.color),
                        )
                        BasicText(
                            text = mode.description,
                            style = ppmText(12f, lineHeight = 16.5f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Provider gate bar shown above the composer when a provider requires sign-in or setup (Phone 1 A5).
 */
@Composable
fun PhoneProviderGateBar(
    providerName: String,
    message: String,
    actionLabel: String = "Sign in",
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(tokens.warning.opacity(0.12).color)
            .cssBorder(tokens.warning.opacity(0.35).color, sides = CssSides.Top)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.warning.color)
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = providerName,
                style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
            )
            BasicText(
                text = message,
                style = ppmText(11.5f, lineHeight = 15f).copy(color = tokens.text2.color),
            )
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.warning.color)
                .ppmClickable(
                    shape = RoundedCornerShape(10.dp),
                    onClickLabel = actionLabel,
                    onClick = onAction,
                )
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = actionLabel,
                style = ppmText(12.5f, FontWeight.SemiBold, lineHeight = 16f).copy(color = Color.Black),
            )
        }
    }
}
