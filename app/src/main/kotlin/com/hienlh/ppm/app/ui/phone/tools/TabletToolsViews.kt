package com.hienlh.ppm.app.ui.phone.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.kit.ActionPrimaryButton
import com.hienlh.ppm.design.kit.Chip
import com.hienlh.ppm.design.kit.ListRow
import com.hienlh.ppm.design.kit.SectionLabel
import com.hienlh.ppm.design.ppmText

data class TabletDesignCommentItem(
    val id: String,
    val number: Int,
    val author: String,
    val text: String,
    val selector: String,
)

data class TabletDesignVersionItem(
    val version: Int,
    val label: String,
    val timestamp: String,
)

/**
 * State holder for the Tablet 7 Designs tool (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html` Section 9:
 * canvas, comment pins, share link, version switcher).
 */
@Stable
class TabletDesignWorkspaceState(
    val slug: String = "ppm-mobile-tablet-redesign",
    initialComments: List<TabletDesignCommentItem> = listOf(
        TabletDesignCommentItem(
            id = "c1",
            number = 1,
            author = "hienlh",
            text = "Keep 48dp touch target on bottom action card",
            selector = "#bottom-card",
        ),
    ),
    initialVersions: List<TabletDesignVersionItem> = listOf(
        TabletDesignVersionItem(version = 1, label = "v1 · Initial wireframe", timestamp = "10:00"),
        TabletDesignVersionItem(version = 2, label = "v2 · Token pass", timestamp = "14:20"),
    ),
) {
    var activeVersion: Int by mutableIntStateOf(initialVersions.lastOrNull()?.version ?: 1)
    var comments: List<TabletDesignCommentItem> by mutableStateOf(initialComments)
    var versions: List<TabletDesignVersionItem> by mutableStateOf(initialVersions)
    var commentMode: Boolean by mutableStateOf(false)
    var sharePopoverOpen: Boolean by mutableStateOf(false)
    var shareUrl: String by mutableStateOf("https://ppm.cloud/designs/$slug")
    var draftComment: String by mutableStateOf("")

    fun addComment(text: String, selector: String = "#canvas") {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        val nextNum = (comments.maxOfOrNull { it.number } ?: 0) + 1
        comments = comments + TabletDesignCommentItem(
            id = "c$nextNum",
            number = nextNum,
            author = "you",
            text = cleaned,
            selector = selector,
        )
        draftComment = ""
    }
}

/**
 * Tablet 7 Designs workspace composable with canvas, comment pins & drawer, share popover, and version switcher.
 */
@Composable
fun TabletDesignsToolView(
    state: TabletDesignWorkspaceState,
    onSendFeedbackToChat: (List<TabletDesignCommentItem>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("tablet-designs-tool"),
    ) {
        // Toolbar: version switcher, comment mode, share, send feedback
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(tokens.panel.color)
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = state.slug,
                    style = ppmText(13.5f, FontWeight.SemiBold, FontKind.MONO).copy(color = tokens.text.color),
                )
                for (ver in state.versions) {
                    Chip(
                        label = "v${ver.version}",
                        selected = state.activeVersion == ver.version,
                        onClick = { state.activeVersion = ver.version },
                        modifier = Modifier.testTag("tablet-design-version-${ver.version}"),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(
                    label = if (state.commentMode) "Pinning comments" else "Comment",
                    selected = state.commentMode,
                    onClick = { state.commentMode = !state.commentMode },
                    modifier = Modifier.testTag("tablet-design-comment-toggle"),
                )
                Chip(
                    label = "Share",
                    selected = state.sharePopoverOpen,
                    onClick = { state.sharePopoverOpen = !state.sharePopoverOpen },
                    modifier = Modifier.testTag("tablet-design-share-btn"),
                )
                ActionPrimaryButton(
                    label = "Send Feedback (${state.comments.size})",
                    onClick = { onSendFeedbackToChat(state.comments) },
                    modifier = Modifier.testTag("tablet-design-send-feedback"),
                )
            }
        }

        if (state.sharePopoverOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Bottom)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("tablet-design-share-bar"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = state.shareUrl,
                    style = ppmText(12.5f, FontWeight.Medium, FontKind.MONO).copy(color = tokens.accent.color),
                )
                Chip(
                    label = "Copy share link",
                    onClick = { state.sharePopoverOpen = false },
                    modifier = Modifier.testTag("tablet-design-copy-share"),
                )
            }
        }

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Canvas preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.panel.color, RoundedCornerShape(14.dp))
                    .cssBorder(tokens.borderSoft.color, RoundedCornerShape(14.dp), width = 1.dp)
                    .testTag("tablet-design-canvas"),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = "Design Canvas · v${state.activeVersion}",
                    style = ppmText(15f, FontWeight.SemiBold).copy(color = tokens.text2.color),
                )
            }

            // Right comments column (280dp)
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(tokens.panel.color)
                    .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.Start)
                    .padding(12.dp)
                    .testTag("tablet-design-comments-pane"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel(text = "Comments (${state.comments.size})")
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (cmt in state.comments) {
                        ListRow(
                            title = "#${cmt.number} · ${cmt.text}",
                            subtitle = "${cmt.author} · ${cmt.selector}",
                            subtitleMono = true,
                            compact = true,
                            modifier = Modifier.testTag("tablet-design-comment-${cmt.id}"),
                        )
                    }
                }
                PhoneBottomSearchField(
                    value = state.draftComment,
                    placeholder = "Add comment…",
                    onValueChange = { state.draftComment = it },
                    modifier = Modifier.testTag("tablet-design-comment-input"),
                )
                Chip(
                    label = "Add Pin",
                    onClick = { state.addComment(state.draftComment) },
                    modifier = Modifier.testTag("tablet-design-comment-add"),
                )
            }
        }
    }
}

data class TabletExtensionPanelEntry(
    val id: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    val panelTitle: String,
)

/**
 * Tablet 7 Extensions & Panel view (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html` Section 8).
 */
@Composable
fun TabletExtensionsToolView(
    extensions: List<TabletExtensionPanelEntry>,
    selectedExtensionId: String?,
    onSelectExtension: (String) -> Unit,
    onToggleExtension: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current
    val activeExt = extensions.firstOrNull { it.id == selectedExtensionId } ?: extensions.firstOrNull()

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("tablet-extensions-tool"),
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(tokens.panel.color)
                .cssBorder(tokens.borderSoft.color, width = 1.dp, sides = CssSides.End)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel(text = "Installed Extensions")
            for (ext in extensions) {
                ListRow(
                    title = ext.name,
                    subtitle = "${ext.id} · v${ext.version}",
                    subtitleMono = true,
                    compact = true,
                    selected = ext.id == activeExt?.id,
                    onClick = { onSelectExtension(ext.id) },
                    trailingContent = {
                        Chip(
                            label = if (ext.enabled) "Active" else "Disabled",
                            selected = ext.enabled,
                            onClick = { onToggleExtension(ext.id, !ext.enabled) },
                            modifier = Modifier.testTag("tablet-ext-toggle-${ext.id}"),
                        )
                    },
                    modifier = Modifier.testTag("tablet-ext-row-${ext.id}"),
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
                .testTag("tablet-ext-panel-host"),
        ) {
            if (activeExt != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        text = activeExt.panelTitle,
                        style = ppmText(16f, FontWeight.SemiBold).copy(color = tokens.text.color),
                    )
                    BasicText(
                        text = "Extension panel for ${activeExt.id}",
                        style = ppmText(13f, FontWeight.Normal, FontKind.MONO).copy(color = tokens.text2.color),
                    )
                }
            }
        }
    }
}

/**
 * Tablet 7 Android Device, Teams, and Jira summary workspace (`designs/ppm-mobile-tablet-redesign/tablet/7-tools/index.html`
 * Sections 6, 11, 12).
 */
@Composable
fun TabletIntegrationsToolsStrip(
    androidSerial: String = "emulator-5554",
    teamName: String = "Core Platform Team",
    jiraProjectKey: String = "PPM-142",
    onOpenAndroidTab: () -> Unit = {},
    onOpenTeamsTab: () -> Unit = {},
    onOpenJiraTab: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalPpmTokens.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.panel.color)
            .padding(12.dp)
            .testTag("tablet-integrations-tools-strip"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ListRow(
            title = "Android Device",
            subtitle = androidSerial,
            subtitleMono = true,
            compact = true,
            onClick = onOpenAndroidTab,
            modifier = Modifier.testTag("tablet-tool-android-device"),
        )
        ListRow(
            title = "Teams",
            subtitle = teamName,
            compact = true,
            onClick = onOpenTeamsTab,
            modifier = Modifier.testTag("tablet-tool-teams"),
        )
        ListRow(
            title = "Jira",
            subtitle = jiraProjectKey,
            subtitleMono = true,
            compact = true,
            onClick = onOpenJiraTab,
            modifier = Modifier.testTag("tablet-tool-jira"),
        )
    }
}
