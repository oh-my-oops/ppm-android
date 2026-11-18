package com.hienlh.ppm.app.ui.jira

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.jira
import com.hienlh.ppm.app.ui.DialogButton
import com.hienlh.ppm.app.ui.DropdownBelowEnd
import com.hienlh.ppm.app.ui.FieldLabel
import com.hienlh.ppm.app.ui.FormTextArea
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.WebDialog
import com.hienlh.ppm.app.ui.tasks.IconBtn
import com.hienlh.ppm.app.ui.tasks.T
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.jira.isUnreadJiraResult
import com.hienlh.ppm.state.jira.jiraDebugPrompt
import com.hienlh.ppm.state.jira.jiraTimeAgo
import com.hienlh.ppm.state.settings.JiraResultRow
import com.hienlh.ppm.state.settings.canCancelJiraWatcherResult
import com.hienlh.ppm.state.settings.canDebugJiraWatcherResult
import com.hienlh.ppm.state.settings.canResumeJiraWatcherResult
import kotlinx.coroutines.launch

private enum class SubView { TICKETS, WATCHERS, CREDENTIALS }

/**
 * The sidebar's Jira section (`jira-panel.tsx`): the open project's tracked tickets with their
 * debug sessions, and its watchers and credentials one level down. The same panel on a phone,
 * full screen from the drawer.
 */
@Composable
fun JiraPanel(model: AppModel) {
    val tokens = LocalPpmTokens.current
    val jira = model.jira
    val scope = rememberCoroutineScope()
    var subView by remember { mutableStateOf(SubView.TICKETS) }
    var loading by remember { mutableStateOf(false) }
    var debugTarget by remember { mutableStateOf<JiraResultRow?>(null) }

    val project = model.selectedProject
    val entry = jira.projectEntry(project)
    val config = jira.configFor(entry?.id)

    LaunchedEffect(jira, model.events) { jira.bindEvents(model.events) }
    LaunchedEffect(jira, model.api) {
        jira.loadConfigs()
        jira.loadProjects()
    }
    LaunchedEffect(config?.id) {
        jira.watchedConfigId = config?.id
        config?.let { jira.loadWatchers(it.id) }
        loading = true
        jira.loadResults()
        loading = false
        jira.loadUnreadCount()
    }
    fun refresh() {
        scope.launch {
            loading = true
            jira.loadResults()
            loading = false
        }
    }

    if (project == null) {
        Box(Modifier.fillMaxWidth().height(128.dp).padding(16.dp).testTag("jira-panel"), contentAlignment = Alignment.Center) {
            T("Select a project to use Jira", 12f, tokens.text3.color)
        }
        return
    }

    if (subView != SubView.TICKETS) {
        Column(Modifier.fillMaxSize().testTag("jira-panel")) {
            Row(
                Modifier.fillMaxWidth().cssBorder(tokens.border.opacity(0.5).color, sides = CssSides.Bottom).padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconBtn(ProductIcon.ArrowLeft, "Back", size = 28.dp, tint = tokens.text.color) { subView = SubView.TICKETS }
                T(if (subView == SubView.WATCHERS) "Watchers" else "Jira Credentials", 14f, weight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (subView == SubView.WATCHERS) {
                    if (config != null) JiraWatcherList(model, config.id) else Hint("Configure Jira credentials first.")
                } else {
                    if (entry != null) JiraConfigForm(model, entry.id, config) else Hint("Project not found in database.")
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().testTag("jira-panel")) {
        Row(
            Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Bug, 16.dp, tokens.accent.color)
            BasicText(
                "JIRA",
                Modifier.weight(1f),
                style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text2.color, letterSpacing = 0.025.em),
                maxLines = 1,
            )
            if (jira.unreadCount > 0) UnreadBadge(jira.unreadCount)
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).ppmClickable(enabled = !loading, onClickLabel = "Refresh") { refresh() }, contentAlignment = Alignment.Center) {
                PpmIcon(if (loading) ProductIcon.Loader2 else ProductIcon.RefreshCw, 14.dp, tokens.text3.color, spinning = loading)
            }
            IconBtn(ProductIcon.ListFilter, "Watchers", size = 24.dp, modifier = Modifier.testTag("jira-open-watchers")) { subView = SubView.WATCHERS }
            IconBtn(ProductIcon.Settings2, "Credentials", size = 24.dp, modifier = Modifier.testTag("jira-open-credentials")) { subView = SubView.CREDENTIALS }
        }
        val results = jira.results
        when {
            config == null -> EmptyState("No Jira credentials configured", "Set up Jira") { subView = SubView.CREDENTIALS }
            results.isEmpty() && jira.watchers.isEmpty() -> EmptyState("No watchers yet", "Add Watcher") { subView = SubView.WATCHERS }
            results.isEmpty() -> EmptyState("No tickets yet. Watchers will pick up new issues.")
            else -> LazyColumn(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(results, key = { it.id }) { r ->
                    TicketCard(
                        r,
                        onDebug = { debugTarget = r },
                        onResume = { jira.resumeDebug(r.id) },
                        onCancel = { jira.cancelDebug(r.id) },
                        onOpen = { jira.openSession(r) },
                        onDelete = { jira.softDeleteResult(r.id) },
                    )
                }
            }
        }
    }
    debugTarget?.let { r -> JiraDebugPromptDialog(model, r) { debugTarget = null } }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { T(text, 12f, LocalPpmTokens.current.text3.color, maxLines = 3) }
}

@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val tokens = LocalPpmTokens.current
    Box(
        modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).background(tokens.accent.color, RoundedCornerShape(999.dp)).padding(horizontal = 4.dp).testTag("jira-unread"),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("$count", style = ppmText(10f, FontWeight.Bold).copy(color = tokens.accentFg.color))
    }
}

@Composable
private fun EmptyState(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val tokens = LocalPpmTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(message, 12f, tokens.text3.color, maxLines = 3)
        if (action != null && onAction != null) {
            DialogButton(label = action, primary = false, dark = true, enabled = true, modifier = Modifier.testTag("jira-empty-action"), icon = ProductIcon.Plus, onClick = onAction)
        }
    }
}

/** A status as its dot's colour (`DOT_COLORS`). */
@Composable
internal fun jiraStatusColor(status: String): Color {
    val tokens = LocalPpmTokens.current
    return when (status) {
        "pending" -> tokens.warning.color
        "queued" -> tokens.warning.opacity(0.8).color
        "running" -> tokens.accent.color
        "done" -> tokens.success.color
        "failed" -> tokens.error.color
        else -> tokens.text3.color
    }
}

/** One tracked ticket (`jira-ticket-card.tsx`): key, unread dot, summary, age; status and its actions. */
@Composable
private fun TicketCard(
    r: JiraResultRow,
    onDebug: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val unread = isUnreadJiraResult(r.status, r.readAt)
    val hasSession = r.sessionId != null
    val shape = RoundedCornerShape(4.dp)
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (unread) Modifier.border(1.dp, tokens.accent.opacity(0.3).color, shape) else Modifier)
            .background(tokens.panel.color)
            .ppmClickable(shape = shape) { if (hasSession) onOpen() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("jira-ticket-${r.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T(r.issueKey, 12f, weight = FontWeight.SemiBold, mono = true)
            if (unread) Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.accent.color).testTag("jira-ticket-unread-${r.id}"))
            T(r.issueSummary?.ifEmpty { null } ?: "No summary", 12f, tokens.text3.color, modifier = Modifier.weight(1f))
            T(jiraTimeAgo(r.createdAt, System.currentTimeMillis()), 10f, tokens.text3.opacity(0.6).color)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(jiraStatusColor(r.status)))
                T(r.status, 11f, tokens.text3.color)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (canResumeJiraWatcherResult(r.status, r.sessionId)) CardTextButton("Resume", ProductIcon.RotateCcw, "jira-resume-${r.id}", onResume)
                if (canDebugJiraWatcherResult(r.status, r.sessionId)) IconBtn(ProductIcon.Play, "Debug", size = 24.dp, modifier = Modifier.testTag("jira-debug-${r.id}"), onClick = onDebug)
                if (canCancelJiraWatcherResult(r.status)) IconBtn(ProductIcon.Square, "Stop debug", size = 24.dp, modifier = Modifier.testTag("jira-stop-${r.id}"), onClick = onCancel)
                if (r.status == "running") PpmIcon(ProductIcon.Loader2, 12.dp, tokens.accent.color, spinning = true)
                if (hasSession) CardTextButton("Open", ProductIcon.ExternalLink, "jira-open-${r.id}", onOpen)
                Box {
                    IconBtn(ProductIcon.MoreHorizontal, "More", size = 24.dp, modifier = Modifier.testTag("jira-more-${r.id}")) { menu = true }
                    if (menu) {
                        DropdownBelowEnd(onDismiss = { menu = false }) {
                            MenuItem("Delete", { menu = false; onDelete() }, icon = { PpmIcon(ProductIcon.Trash2, 14.dp, tokens.error.color) }, color = tokens.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTextButton(label: String, icon: ProductIcon, tag: String, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier.heightIn(min = 24.dp).clip(shape).ppmClickable(shape = shape, onClickLabel = label, onClick = onClick).padding(horizontal = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PpmIcon(icon, 12.dp, tokens.accent.color)
        T(label, 10f, tokens.accent.color, weight = FontWeight.Medium)
    }
}

/** "Start Debug: KEY" (`jira-debug-prompt-dialog.tsx`): the prompt from the watcher's template, editable. */
@Composable
internal fun JiraDebugPromptDialog(model: AppModel, r: JiraResultRow, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val jira = model.jira
    val template = jira.watchers.firstOrNull { it.id == r.watcherId }?.promptTemplate
    val prompt = remember(r.id) { TextFieldState(jiraDebugPrompt(template, r.issueKey, r.issueSummary)) }
    WebDialog(title = "Start Debug: ${r.issueKey}", onDismiss = onClose, widest = 448.dp) {
        Column(Modifier.fillMaxWidth().testTag("jira-debug-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            r.issueSummary?.let { T(it, 13f, tokens.text3.color, maxLines = 4) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Debug Prompt")
                FormTextArea(prompt, "Debug Jira issue {issue_key}: {summary}", Modifier.fillMaxWidth().height(96.dp).testTag("jira-debug-prompt"))
            }
            DialogButton(
                label = "Start Debug Session",
                primary = true,
                dark = true,
                enabled = true,
                modifier = Modifier.fillMaxWidth().testTag("jira-debug-start"),
                onClick = {
                    jira.startDebug(r.id, prompt.text.toString())
                    onClose()
                },
            )
        }
    }
}
