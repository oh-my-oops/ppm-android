package com.hienlh.ppm.app.ui.phone.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssScrollY
import com.hienlh.ppm.design.kit.KitSpec
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.SectionLabel
import com.hienlh.ppm.design.kit.Sheet
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmShadow
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.terminal.MobileKey
import com.hienlh.ppm.state.terminal.TerminalState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phone 4 Terminal models, state derivations, and composables
 * (`designs/ppm-mobile-tablet-redesign/phone/4-git-terminal/index.html` Section 5 & Section 7,
 * Requirements 12.1 & 12.2).
 */

private val TERMINAL_ID_NUMBER = Regex("""^terminal:(\d+)""")

/**
 * Extracts the monotonic terminal creation index from [tab]'s metadata (`terminalIndex`) or
 * `terminal:<n>` identifier so the phone can identify the newest terminal (`Phone 4` Note 51).
 */
fun phoneTerminalIndex(tab: WorkspaceTab): Int {
    val metaIndex = (tab.metadata["terminalIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
    if (metaIndex != null && metaIndex > 0) return metaIndex
    return TERMINAL_ID_NUMBER.find(tab.id)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

/**
 * Returns all open terminal tabs belonging to [currentProject] across the phone's grid panels
 * ([gridTabs]) and the shared dock ([dockTabs]), preserving each tab without duplicates.
 *
 * When the AI calls `run_in_terminal` or the user taps a command card's `"Type in terminal"`
 * (`AppModel.runInTerminal` / `answerAiTabOpen`), the new terminal is opened in the dock while
 * the user's own terminal tab remains in the grid or dock (`Requirement 12.2`, `Phone 4` Note 51).
 */
fun collectPhoneProjectTerminals(
    gridTabs: List<WorkspaceTab>,
    dockTabs: List<WorkspaceTab>,
    currentProject: String? = null,
): List<WorkspaceTab> {
    fun belongsToProject(tab: WorkspaceTab): Boolean {
        if (tab.type != "terminal") return false
        if (currentProject.isNullOrEmpty()) return true
        val proj = tab.projectId?.takeIf { it.isNotEmpty() } ?: tab.metadata["projectName"].string
        return proj.isNullOrEmpty() || proj == currentProject
    }

    val seen = LinkedHashMap<String, WorkspaceTab>()
    for (tab in gridTabs) {
        if (belongsToProject(tab)) seen[tab.id] = tab
    }
    for (tab in dockTabs) {
        if (belongsToProject(tab)) seen[tab.id] = tab
    }
    return seen.values.toList()
}

/**
 * Resolves which terminal tab the phone Terminal screen shows (`Requirement 12.2`, `Phone 4`
 * Notes 38 & 51: `"One terminal on a phone: the screen shows the newest"`):
 *
 * 1. If any terminal tab for the project has a pending command waiting to be typed (`pendingCommand`
 *    from `run_in_terminal` / `"Type in terminal"`), the newest such terminal wins immediately.
 * 2. If [activeDockTabId] points to a dock terminal that was opened after [requestedTab] (higher
 *    [phoneTerminalIndex] or AI-attached `sessionId`), that newest terminal is shown while the
 *    user's previous terminal keeps running on the server.
 * 3. Otherwise, if any terminal tab in [dockTabs] is newer than [requestedTab] (higher
 *    [phoneTerminalIndex]), the newest one is shown; when [requestedTab] was explicitly chosen
 *    from the switcher and no newer dock terminal exists, [requestedTab] is kept.
 */
fun resolvePhoneTerminalTab(
    requestedTab: WorkspaceTab,
    gridTabs: List<WorkspaceTab>,
    dockTabs: List<WorkspaceTab>,
    activeDockTabId: String? = null,
    currentProject: String? = requestedTab.projectId ?: requestedTab.metadata["projectName"].string,
): WorkspaceTab {
    val allTerminals = collectPhoneProjectTerminals(gridTabs, dockTabs, currentProject)
    if (allTerminals.isEmpty()) return requestedTab

    // 1. Any terminal holding a pendingCommand from AI / "Type in terminal" takes front immediately.
    val withPendingCommand = allTerminals.filter {
        !it.metadata["pendingCommand"].string.isNullOrBlank()
    }
    if (withPendingCommand.isNotEmpty()) {
        return withPendingCommand.maxByOrNull(::phoneTerminalIndex) ?: withPendingCommand.last()
    }

    val requestedIdx = phoneTerminalIndex(requestedTab)

    // 2. If the active dock tab is a terminal opened more recently (e.g. AI's `run_in_terminal`), show it.
    if (activeDockTabId != null) {
        val activeDock = dockTabs.firstOrNull { it.id == activeDockTabId && it.type == "terminal" }
        if (activeDock != null && phoneTerminalIndex(activeDock) > requestedIdx) {
            return activeDock
        }
    }

    // 3. If a dock terminal is newer than requestedTab, show the newest terminal.
    val newestDock = dockTabs
        .filter { it.type == "terminal" }
        .maxByOrNull(::phoneTerminalIndex)
    if (newestDock != null && phoneTerminalIndex(newestDock) > requestedIdx) {
        return newestDock
    }

    return allTerminals.firstOrNull { it.id == requestedTab.id } ?: allTerminals.last()
}

/**
 * Whether the phone Terminal header should fold down to a single 24 dp status line
 * (`Phone 4 · TL1 · Turned sideways (915 × 412) · keyboard up`, Note 40, Requirement 12.1):
 * true when rotated sideways (`widthDp > heightDp`), the soft keyboard is up (`imeVisible`), and
 * the user has not manually tapped `"Show bar ▾"` (`!manuallyExpanded`).
 */
fun shouldFoldPhoneTerminalBarInLandscape(
    widthDp: Float,
    heightDp: Float,
    imeVisible: Boolean,
    manuallyExpanded: Boolean = false,
): Boolean = widthDp > heightDp && imeVisible && !manuallyExpanded

/**
 * Connection status displayed in the Phone 4 Terminal header and banners.
 */
enum class PhoneTerminalConnectionStatus(val label: String) {
    Connected("Connected"),
    Reconnecting("Reconnecting…"),
    Disconnected("Disconnected"),
    ProcessExited("Process exited"),
    TerminalError("Terminal error"),
}

/**
 * Derives the [PhoneTerminalConnectionStatus] from [TerminalState].
 */
fun resolvePhoneTerminalConnectionStatus(state: TerminalState): PhoneTerminalConnectionStatus = when {
    state.exited -> PhoneTerminalConnectionStatus.ProcessExited
    state.reconnecting -> PhoneTerminalConnectionStatus.Reconnecting
    !state.connected && state.error != null -> PhoneTerminalConnectionStatus.TerminalError
    state.connected -> PhoneTerminalConnectionStatus.Connected
    else -> PhoneTerminalConnectionStatus.Disconnected
}

/**
 * Which bottom bar the phone Terminal renders (`Phone 4` Notes 31 & 39):
 * - [KeyRow]: the 48 dp extra-key row (`Esc`, `Tab`, `Ctrl`, arrows, `^C`, `Paste`), which stays
 *   visible both above the soft keyboard and when the keyboard is down.
 * - [ExitedRestartBar]: when the shell process has exited (`TM8 · Process exited & Restart`).
 * - [ErrorRestartBar]: when the terminal has a fatal error and is not reconnecting (`Terminal error`).
 */
enum class PhoneTerminalBottomBarMode {
    KeyRow,
    ExitedRestartBar,
    ErrorRestartBar,
}

fun resolvePhoneTerminalBottomBarMode(state: TerminalState): PhoneTerminalBottomBarMode = when {
    state.exited -> PhoneTerminalBottomBarMode.ExitedRestartBar
    !state.connected && !state.reconnecting && state.error != null -> PhoneTerminalBottomBarMode.ErrorRestartBar
    else -> PhoneTerminalBottomBarMode.KeyRow
}

/**
 * Specification for a row in the `⋮` Terminal actions bottom sheet (`Phone 4 · TM5`, Note 36).
 */
data class PhoneTerminalActionRowSpec(
    val id: String,
    val title: String,
    val icon: ProductIcon,
    val hint: String? = null,
    val isToggle: Boolean = false,
    val checked: Boolean = false,
    val badgeCount: Int? = null,
    val highlightAccent: Boolean = false,
)

/**
 * Builds the 6 action rows for the `⋮` Terminal actions sheet (`Phone 4 · TM5`, Note 36):
 * - `Copy` with hint `"selection"` when [hasSelection] is true, else `"last output"`
 * - `Paste`
 * - `Select text` with a toggle switch reflecting [selectMode]
 * - `Links` with a count badge ([linkCount])
 * - `Send to chat` with hint `"selection"` when [hasSelection] is true, else `"last command + output"`
 * - `Restart`
 */
fun buildPhoneTerminalActionRows(
    hasSelection: Boolean,
    selectMode: Boolean,
    linkCount: Int,
    exited: Boolean = false,
): List<PhoneTerminalActionRowSpec> = listOf(
    PhoneTerminalActionRowSpec(
        id = "copy",
        title = "Copy",
        icon = ProductIcon.Copy,
        hint = if (hasSelection) "selection" else "last output",
    ),
    PhoneTerminalActionRowSpec(
        id = "paste",
        title = "Paste",
        icon = ProductIcon.ClipboardPaste,
    ),
    PhoneTerminalActionRowSpec(
        id = "select_text",
        title = "Select text",
        icon = ProductIcon.TextSelect,
        isToggle = true,
        checked = selectMode,
    ),
    PhoneTerminalActionRowSpec(
        id = "links",
        title = "Links",
        icon = ProductIcon.Link,
        badgeCount = linkCount,
    ),
    PhoneTerminalActionRowSpec(
        id = "send_to_chat",
        title = "Send to chat",
        icon = ProductIcon.MessageSquare,
        hint = if (hasSelection) "selection" else "last command + output",
    ),
    PhoneTerminalActionRowSpec(
        id = "restart",
        title = "Restart",
        icon = ProductIcon.RotateCcw,
        highlightAccent = exited,
    ),
)

/**
 * Formats the subtitle of the `⋮` Terminal actions sheet (`Phone 4 · TM5`):
 * `"session <id> · <cwd>"` (or whichever part is available).
 */
fun formatPhoneTerminalSheetSubtitle(sessionId: String?, cwd: String?): String {
    val cleanSession = sessionId?.takeIf { it.isNotBlank() && it != "new" }?.let { "session $it" }
    val cleanCwd = cwd?.takeIf { it.isNotBlank() }
    return listOfNotNull(cleanSession, cleanCwd).joinToString(" · ").ifEmpty { "Active shell" }
}

/**
 * Item in the Phone 4 Terminal key row (`Phone 4` Notes 31, 32, 35).
 */
sealed interface PhoneTerminalKeyItem {
    data class KeyButton(
        val id: String,
        val label: String,
        val mobileKey: MobileKey? = null,
        val icon: ProductIcon? = null,
        val highlighted: Boolean = false,
        val repeats: Boolean = false,
    ) : PhoneTerminalKeyItem

    data object Divider : PhoneTerminalKeyItem
}

/**
 * Builds the ordered list of items in the Phone 4 key row (`Requirement 12.1`, `Phone 4` Notes 31, 32, 35):
 * - In [selectMode] (`TM4` Note 35): `Select text` (highlighted) and `Copy` lead the key row before
 *   a vertical divider.
 * - Standard keys (`TM1` Note 31): `Esc`, `Tab`, `Ctrl` (sticky, highlighted when [ctrlArmed]),
 *   `↑`, `↓`, `←`, `→` (all 4 arrows repeat when held), `^C`, and `Paste`.
 */
fun buildPhoneTerminalKeyItems(
    selectMode: Boolean,
    ctrlArmed: Boolean,
): List<PhoneTerminalKeyItem> = buildList {
    if (selectMode) {
        add(
            PhoneTerminalKeyItem.KeyButton(
                id = "select_text",
                label = "Select text",
                icon = ProductIcon.TextSelect,
                highlighted = true,
            ),
        )
        add(
            PhoneTerminalKeyItem.KeyButton(
                id = "copy",
                label = "Copy",
                icon = ProductIcon.Copy,
            ),
        )
        add(PhoneTerminalKeyItem.Divider)
    }
    add(PhoneTerminalKeyItem.KeyButton(id = "esc", label = MobileKey.Esc.label, mobileKey = MobileKey.Esc))
    add(PhoneTerminalKeyItem.KeyButton(id = "tab", label = MobileKey.Tab.label, mobileKey = MobileKey.Tab))
    add(
        PhoneTerminalKeyItem.KeyButton(
            id = "ctrl",
            label = MobileKey.Ctrl.label,
            mobileKey = MobileKey.Ctrl,
            highlighted = ctrlArmed,
        ),
    )
    add(PhoneTerminalKeyItem.KeyButton(id = "up", label = MobileKey.Up.label, mobileKey = MobileKey.Up, repeats = true))
    add(PhoneTerminalKeyItem.KeyButton(id = "down", label = MobileKey.Down.label, mobileKey = MobileKey.Down, repeats = true))
    add(PhoneTerminalKeyItem.KeyButton(id = "left", label = MobileKey.Left.label, mobileKey = MobileKey.Left, repeats = true))
    add(PhoneTerminalKeyItem.KeyButton(id = "right", label = MobileKey.Right.label, mobileKey = MobileKey.Right, repeats = true))
    add(PhoneTerminalKeyItem.KeyButton(id = "ctrl_c", label = MobileKey.CtrlC.label, mobileKey = MobileKey.CtrlC))
    add(PhoneTerminalKeyItem.KeyButton(id = "paste", label = "Paste"))
}

// ───────────────────────── Phone Terminal Composables ─────────────────────────

/**
 * Compact header/status bar for the Phone Terminal screen:
 * - When [foldedInLandscape] is true (`TL1 · Turned sideways (915 × 412) · keyboard up`, Note 40),
 *   renders a single 24 dp status line (`● Connected · Terminal · <cwd>` + `Show bar ▾` + `⋮`) so
 *   5 lines of terminal output stay visible above the 48 dp key row and keyboard.
 * - Otherwise renders the 36 dp status strip with status dot, connection label, working directory,
 *   session badge, optional `Hide bar ▴` (when expanded in landscape), and `⋮` button opening
 *   the Terminal actions sheet (`TM5`).
 */
@Composable
fun PhoneTerminalStatusBar(
    state: TerminalState,
    cwd: String?,
    foldedInLandscape: Boolean,
    canFoldInLandscape: Boolean = false,
    onShowFullBar: () -> Unit = {},
    onFoldBar: () -> Unit = {},
    onOpenActionsSheet: () -> Unit,
    onRetryConnection: () -> Unit = {},
    onRestart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val status = resolvePhoneTerminalConnectionStatus(state)
    val dotColor = when (status) {
        PhoneTerminalConnectionStatus.Connected -> tokens.success.color
        PhoneTerminalConnectionStatus.Reconnecting -> tokens.warning.color
        PhoneTerminalConnectionStatus.Disconnected,
        PhoneTerminalConnectionStatus.ProcessExited,
        PhoneTerminalConnectionStatus.TerminalError -> tokens.error.color
    }
    val displayCwd = cwd?.takeIf { it.isNotBlank() }

    if (foldedInLandscape) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(tokens.panel2.color)
                .cssBorder(tokens.border.color, width = 1.dp, sides = CssSides.Bottom)
                .padding(horizontal = 10.dp)
                .testTag("phone-terminal-landscape-folded-bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape),
            )
            BasicText(
                text = buildString {
                    append(status.label)
                    append(" · Terminal")
                    if (displayCwd != null) {
                        append(" · ")
                        append(displayCwd)
                    }
                },
                modifier = Modifier.weight(1f),
                style = ppmText(11f, FontWeight.Medium, FontKind.MONO, lineHeight = 14f)
                    .copy(color = tokens.text2.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Show bar",
                        onClick = onShowFullBar,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("phone-terminal-show-bar"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Show bar ▾",
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f)
                        .copy(color = tokens.accent.color),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .ppmClickable(
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Terminal actions",
                        onClick = onOpenActionsSheet,
                    )
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .semantics { contentDescription = "Terminal actions" }
                    .testTag("phone-terminal-more-button"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(ProductIcon.MoreVertical, 14.dp, tokens.text2.color)
            }
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, width = 1.dp, sides = CssSides.Bottom)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            .testTag("phone-terminal-status-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        BasicText(
            text = status.label,
            style = ppmText(12f, FontWeight.Medium, lineHeight = 16f)
                .copy(color = tokens.text2.color),
            maxLines = 1,
        )
        if (displayCwd != null) {
            BasicText(
                text = "· $displayCwd",
                modifier = Modifier.weight(1f, fill = false),
                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                    .copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.reconnecting || (!state.connected && !state.exited)) {
            val retryShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .clip(retryShape)
                    .background(tokens.panel2.color, retryShape)
                    .cssBorder(tokens.border.color, retryShape, width = 1.dp)
                    .ppmClickable(
                        shape = retryShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Retry now",
                        onClick = onRetryConnection,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("phone-terminal-retry-button"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Retry now",
                    style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 15f)
                        .copy(color = tokens.text.color),
                )
            }
        }
        if (state.exited) {
            val restartShape = RoundedCornerShape(6.dp)
            Box(
                modifier = Modifier
                    .clip(restartShape)
                    .background(tokens.panel2.color, restartShape)
                    .cssBorder(tokens.border.color, restartShape, width = 1.dp)
                    .ppmClickable(
                        shape = restartShape,
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Restart",
                        onClick = onRestart,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Restart",
                    style = ppmText(11.5f, FontWeight.SemiBold, lineHeight = 15f)
                        .copy(color = tokens.text.color),
                )
            }
        }
        if (canFoldInLandscape) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .ppmClickable(
                        hoverColor = tokens.surfaceHover.color,
                        onClickLabel = "Hide bar",
                        onClick = onFoldBar,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("phone-terminal-hide-bar"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Hide bar ▴",
                    style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f)
                        .copy(color = tokens.accent.color),
                )
            }
        }
        state.sessionId?.takeIf { it.isNotBlank() && it != "new" }?.let { sid ->
            BasicText(
                text = sid,
                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                    .copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val moreShape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(moreShape)
                .ppmClickable(
                    shape = moreShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Terminal actions",
                    onClick = onOpenActionsSheet,
                )
                .semantics { contentDescription = "Terminal actions" }
                .testTag("phone-terminal-more-button"),
            contentAlignment = Alignment.Center,
        ) {
            PpmIcon(ProductIcon.MoreVertical, 18.dp, tokens.text2.color)
        }
    }
}

/**
 * Floating selection action bar for Phone & Tablet Terminal (`Phone 4 · TM4`, Note 34 & `Tablet 4`
 * Note 83): 44 dp buttons for `"Add to current chat"` (primary), `"Add to new chat"`, and `"Copy"`.
 */
@Composable
fun PhoneTerminalSelectionActionsBar(
    onAddToCurrentChat: () -> Unit,
    onAddToNewChat: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val barShape = RoundedCornerShape(12.dp)
    val btnShape = RoundedCornerShape(8.dp)

    FlowRow(
        modifier = modifier
            .ppmShadow(tokens.shadowFloat, barShape)
            .clip(barShape)
            .background(tokens.panel2.color, barShape)
            .cssBorder(tokens.border.color, barShape, width = 1.dp)
            .padding(4.dp)
            .testTag("terminal-selection-actions"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Primary: Add to current chat
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(btnShape)
                .background(tokens.accent.color, btnShape)
                .ppmClickable(
                    shape = btnShape,
                    onClickLabel = "Add to current chat",
                    onClick = onAddToCurrentChat,
                )
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(ProductIcon.MessageSquare, 16.dp, tokens.accentFg.color)
            BasicText(
                text = "Add to current chat",
                style = ppmText(13f, FontWeight.SemiBold, lineHeight = 17f)
                    .copy(color = tokens.accentFg.color),
            )
        }

        // Secondary: Add to new chat
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(btnShape)
                .background(tokens.panel.color, btnShape)
                .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                .ppmClickable(
                    shape = btnShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Add to new chat",
                    onClick = onAddToNewChat,
                )
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(ProductIcon.MessageSquarePlus, 16.dp, tokens.text.color)
            BasicText(
                text = "Add to new chat",
                style = ppmText(13f, FontWeight.Medium, lineHeight = 17f)
                    .copy(color = tokens.text.color),
            )
        }

        // Copy button (Note 83)
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(btnShape)
                .background(tokens.panel.color, btnShape)
                .cssBorder(tokens.border.color, btnShape, width = 1.dp)
                .ppmClickable(
                    shape = btnShape,
                    hoverColor = tokens.surfaceHover.color,
                    onClickLabel = "Copy",
                    onClick = onCopy,
                )
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PpmIcon(ProductIcon.Copy, 16.dp, tokens.text.color)
            BasicText(
                text = "Copy",
                style = ppmText(13f, FontWeight.Medium, lineHeight = 17f)
                    .copy(color = tokens.text.color),
            )
        }
    }
}

/**
 * Bottom bar rendered when the shell has exited or encountered a terminal error (`Phone 4 · TM8`,
 * Note 39: `"The key row gives way to Restart once the shell has ended"`).
 */
@Composable
fun PhoneTerminalExitedBottomBar(
    isError: Boolean,
    errorMessage: String? = null,
    cwd: String? = null,
    sessionId: String? = null,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val buttonShape = RoundedCornerShape(12.dp)
    val titleText = if (isError) "Terminal error" else "Process exited"
    val subText = if (isError && !errorMessage.isNullOrBlank()) {
        errorMessage
    } else {
        formatPhoneTerminalSheetSubtitle(sessionId, cwd)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.panel2.color)
            .cssBorder(tokens.border.color, width = 1.dp, sides = CssSides.Top)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("phone-terminal-exited-bar"),
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
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(tokens.error.color, CircleShape),
                )
                BasicText(
                    text = titleText,
                    style = ppmText(13.5f, FontWeight.SemiBold, lineHeight = 18f)
                        .copy(color = tokens.text.color),
                    maxLines = 1,
                )
            }
            BasicText(
                text = subText,
                style = ppmText(11.5f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                    .copy(color = tokens.text3.color),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .height(KitSpec.ActionPrimaryHeight)
                .clip(buttonShape)
                .background(tokens.accent.color, buttonShape)
                .ppmClickable(
                    shape = buttonShape,
                    onClickLabel = "Restart",
                    onClick = onRestart,
                )
                .padding(horizontal = 18.dp)
                .testTag("phone-terminal-restart-button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.RotateCcw, 18.dp, tokens.accentFg.color)
            BasicText(
                text = "Restart",
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 18f)
                    .copy(color = tokens.accentFg.color),
            )
        }
    }
}

/**
 * `⋮` Terminal actions bottom sheet (`Phone 4 · TM5 · Terminal actions sheet`, Note 36).
 *
 * Shows the 6 terminal actions (`Copy`, `Paste`, `Select text` with toggle switch, `Links` with
 * count badge, `Send to chat`, `Restart`), plus a terminal switcher list when more than one
 * terminal is running for the project (`Requirement 12.2`, Note 51).
 */
@Composable
fun PhoneTerminalActionsSheet(
    sessionId: String?,
    cwd: String?,
    hasSelection: Boolean,
    selectMode: Boolean,
    linkCount: Int,
    exited: Boolean,
    projectTerminals: List<WorkspaceTab> = emptyList(),
    activeTerminalId: String? = null,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpenLinks: () -> Unit,
    onSendToChat: () -> Unit,
    onRestart: () -> Unit,
    onSelectTerminal: (WorkspaceTab) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val rows = buildPhoneTerminalActionRows(
        hasSelection = hasSelection,
        selectMode = selectMode,
        linkCount = linkCount,
        exited = exited,
    )

    Sheet(
        title = "Terminal",
        subtitle = formatPhoneTerminalSheetSubtitle(sessionId, cwd),
        onDismiss = onDismiss,
        forceBottomSheet = true,
        modifier = Modifier.testTag("phone-terminal-actions-sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cssScrollY()
                .padding(vertical = 6.dp),
        ) {
            for (row in rows) {
                ListRow(
                    title = row.title,
                    leadingContent = {
                        PpmIcon(
                            icon = row.icon,
                            size = 20.dp,
                            tint = if (row.highlightAccent) tokens.accent.color else tokens.text2.color,
                        )
                    },
                    trailingContent = {
                        when {
                            row.isToggle -> {
                                PhoneTerminalSwitchPill(checked = row.checked)
                            }
                            row.badgeCount != null -> {
                                val badgeShape = RoundedCornerShape(999.dp)
                                Box(
                                    modifier = Modifier
                                        .clip(badgeShape)
                                        .background(tokens.panel.color, badgeShape)
                                        .cssBorder(tokens.border.color, badgeShape, width = 1.dp)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BasicText(
                                        text = row.badgeCount.toString(),
                                        style = ppmText(11.5f, FontWeight.SemiBold, FontKind.MONO, lineHeight = 15f)
                                            .copy(color = tokens.text2.color),
                                    )
                                }
                            }
                            !row.hint.isNullOrEmpty() -> {
                                BasicText(
                                    text = row.hint,
                                    style = ppmText(12f, FontWeight.Normal, FontKind.MONO, lineHeight = 15f)
                                        .copy(color = tokens.text3.color),
                                )
                            }
                        }
                    },
                    onClick = {
                        when (row.id) {
                            "copy" -> {
                                onDismiss()
                                onCopy()
                            }
                            "paste" -> {
                                onDismiss()
                                onPaste()
                            }
                            "select_text" -> {
                                onToggleSelect()
                                onDismiss()
                            }
                            "links" -> {
                                onDismiss()
                                onOpenLinks()
                            }
                            "send_to_chat" -> {
                                onDismiss()
                                onSendToChat()
                            }
                            "restart" -> {
                                onDismiss()
                                onRestart()
                            }
                        }
                    },
                )
            }

            if (projectTerminals.size > 1) {
                SectionLabel("Running terminals (${projectTerminals.size})")
                for (termTab in projectTerminals) {
                    val isSelected = termTab.id == activeTerminalId
                    val termCwd = termTab.metadata["cwd"].string
                    val termSid = termTab.metadata["sessionId"].string
                    ListRow(
                        title = termTab.title.ifBlank { "Terminal" },
                        subtitle = formatPhoneTerminalSheetSubtitle(termSid, termCwd),
                        subtitleMono = true,
                        selected = isSelected,
                        leadingContent = {
                            PpmIcon(
                                icon = ProductIcon.TerminalSquare,
                                size = 18.dp,
                                tint = if (isSelected) tokens.accent.color else tokens.text2.color,
                            )
                        },
                        trailingContent = {
                            if (isSelected) {
                                PpmIcon(ProductIcon.Check, 16.dp, tokens.accent.color)
                            }
                        },
                        onClick = {
                            onDismiss()
                            onSelectTerminal(termTab)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneTerminalSwitchPill(checked: Boolean) {
    val tokens = LocalPpmTokens.current
    val trackShape = RoundedCornerShape(999.dp)
    val trackColor = if (checked) tokens.accent.color else tokens.panel.color
    val borderColor = if (checked) tokens.accent.color else tokens.border.color
    val thumbColor = if (checked) tokens.accentFg.color else tokens.text3.color

    Box(
        modifier = Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(trackShape)
            .background(trackColor, trackShape)
            .cssBorder(borderColor, trackShape, width = 1.dp)
            .padding(horizontal = 3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(thumbColor, CircleShape),
        )
    }
}
