package com.hienlh.ppm.app.ui.jira

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.jira
import com.hienlh.ppm.app.ui.DialogButton
import com.hienlh.ppm.app.ui.FieldLabel
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.WebDialog
import com.hienlh.ppm.app.ui.tasks.IconBtn
import com.hienlh.ppm.app.ui.tasks.T
import com.hienlh.ppm.client.jiraGetTicket
import com.hienlh.ppm.client.jiraTicketTransitions
import com.hienlh.ppm.client.jiraTransitionTicket
import com.hienlh.ppm.client.jiraUpdateTicketSummary
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.cssFlex
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.state.jira.JiraIssueView
import com.hienlh.ppm.state.jira.JiraTransitionView
import com.hienlh.ppm.state.jira.isUnreadJiraResult
import com.hienlh.ppm.state.jira.jiraTimeAgo
import com.hienlh.ppm.state.jira.jiraUpdatedLabel
import com.hienlh.ppm.state.jira.parseJiraIssue
import com.hienlh.ppm.state.jira.parseJiraTransitions
import com.hienlh.ppm.state.settings.JiraResultRow
import com.hienlh.ppm.state.settings.JiraWatcherRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val STATUS_FILTERS = listOf(
    SelectOption("all", "All"),
    SelectOption("pending", "Pending"),
    SelectOption("queued", "Queued"),
    SelectOption("running", "Running"),
    SelectOption("done", "Done"),
    SelectOption("failed", "Failed"),
)

/**
 * Every watcher's tickets with a watcher and status filter (`jira-results-panel.tsx`). A finished
 * ticket opens its debug chat; any other opens the ticket itself. [watchers] are the ones a row's
 * site is found through, the Settings section's own list where it has one.
 */
@Composable
internal fun JiraResultsPanel(model: AppModel, watchers: List<JiraWatcherRow> = model.jira.watchers) {
    val tokens = LocalPpmTokens.current
    val jira = model.jira
    val scope = rememberCoroutineScope()
    var filterWatcher by remember { mutableStateOf("all") }
    var filterStatus by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var debugTarget by remember { mutableStateOf<JiraResultRow?>(null) }

    suspend fun load() {
        loading = true
        jira.loadResults(filterWatcher.toIntOrNull(), filterStatus.takeIf { it != "all" })
        loading = false
    }
    LaunchedEffect(jira, model.events) { jira.bindEvents(model.events) }
    LaunchedEffect(jira, model.api) {
        jira.loadConfigs()
        jira.loadUnreadCount()
    }
    LaunchedEffect(filterWatcher, filterStatus, model.api) { load() }

    fun rowClick(r: JiraResultRow) {
        if (r.status == "done" && r.readAt == null) jira.markRead(r.id)
        if (r.status == "done" && r.sessionId != null) {
            jira.openSession(r)
            return
        }
        val configId = watchers.firstOrNull { it.id == r.watcherId }?.configId ?: return
        if (jira.configs.any { it.id == configId }) selected = configId to r.issueKey
    }

    Column(Modifier.fillMaxWidth().testTag("jira-results-panel")) {
        Row(
            Modifier.fillMaxWidth().cssBorder(tokens.border.color, sides = CssSides.Bottom).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectField(
                value = filterWatcher,
                options = listOf(SelectOption("all", "All watchers")) + watchers.map { SelectOption(it.id.toString(), it.name) },
                onValueChange = { filterWatcher = it },
                modifier = Modifier.width(128.dp).testTag("jira-results-watcher"),
                placeholder = "Watcher",
            )
            SelectField(
                value = filterStatus,
                options = STATUS_FILTERS,
                onValueChange = { filterStatus = it },
                modifier = Modifier.width(112.dp).testTag("jira-results-status"),
                placeholder = "Status",
            )
            if (jira.unreadCount > 0) UnreadBadge(jira.unreadCount)
            Box(Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).ppmClickable(enabled = !loading, onClickLabel = "Refresh") { scope.launch { load() } }
                    .testTag("jira-results-refresh"),
                contentAlignment = Alignment.Center,
            ) {
                PpmIcon(if (loading) ProductIcon.Loader2 else ProductIcon.RefreshCw, 14.dp, tokens.text3.color, spinning = loading)
            }
        }
        if (jira.results.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                T("No results yet. Configure a watcher to start.", 13f, tokens.text3.color, maxLines = 3)
            }
        } else {
            // A plain column: the panel sits inside Settings' own scroll.
            for (r in jira.results) {
                ResultRow(
                    r,
                    onClick = { rowClick(r) },
                    onDebug = { debugTarget = r },
                    onDelete = { jira.softDeleteResult(r.id) },
                )
            }
        }
    }
    debugTarget?.let { r -> JiraDebugPromptDialog(model, r) { debugTarget = null } }
    selected?.let { (configId, key) ->
        JiraTicketDetail(model, configId, key, jira.configs.firstOrNull { it.id == configId }?.baseUrl) { selected = null }
    }
}

@Composable
private fun ResultRow(r: JiraResultRow, onClick: () -> Unit, onDebug: () -> Unit, onDelete: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val unread = isUnreadJiraResult(r.status, r.readAt)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(if (unread) tokens.accent.opacity(0.05).color else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (unread) Modifier.cssBorder(tokens.accent.color, width = 2.dp, sides = CssSides.Start) else Modifier)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .ppmClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("jira-result-${r.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusBadge(r.status)
        T(r.issueKey, 12f, weight = FontWeight.Medium, mono = true)
        T(r.issueSummary.orEmpty(), 12f, tokens.text3.color, modifier = Modifier.weight(1f), weight = if (unread) FontWeight.Medium else FontWeight.Normal)
        when (r.status) {
            "queued" -> PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color)
            "running" -> PpmIcon(ProductIcon.Loader2, 14.dp, tokens.accent.color, spinning = true)
            "pending" -> IconBtn(ProductIcon.Play, "Debug", size = 32.dp, tint = tokens.accent.color, modifier = Modifier.testTag("jira-result-play-${r.id}"), onClick = onDebug)
        }
        T(jiraTimeAgo(r.createdAt, System.currentTimeMillis()), 10f, tokens.text3.color)
        IconBtn(ProductIcon.Trash2, "Delete result", size = 32.dp, modifier = Modifier.testTag("jira-result-delete-${r.id}"), onClick = onDelete)
    }
}

/** `jira-status-badge.tsx`: the status word in its colour on a faint wash of it. */
@Composable
private fun StatusBadge(status: String) {
    val color = jiraStatusColor(status)
    val shape = RoundedCornerShape(6.dp)
    T(
        status, 10f, color, weight = FontWeight.Medium,
        modifier = Modifier.clip(shape).background(color.copy(alpha = color.alpha * 0.15f)).border(1.dp, color.copy(alpha = color.alpha * 0.3f), shape)
            .padding(horizontal = 6.dp),
    )
}

/**
 * One Jira issue (`jira-ticket-detail.tsx`): the summary edited in place, the status with the
 * transitions Jira offers, priority, assignee, description and when it last changed.
 */
@Composable
internal fun JiraTicketDetail(model: AppModel, configId: Int, issueKey: String, baseUrl: String?, onClose: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var issue by remember { mutableStateOf<JiraIssueView?>(null) }
    var transitions by remember { mutableStateOf<List<JiraTransitionView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val summary = remember { TextFieldState() }

    LaunchedEffect(configId, issueKey) {
        val api = model.api ?: return@LaunchedEffect
        loading = true
        try {
            coroutineScope {
                val i = async { parseJiraIssue(api.jiraGetTicket(configId, issueKey)) }
                val t = async { parseJiraTransitions(api.jiraTicketTransitions(configId, issueKey)) }
                issue = i.await()
                transitions = t.await()
                issue?.let { iss -> summary.edit { replace(0, length, iss.summary) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        loading = false
    }

    fun saveSummary() {
        val iss = issue ?: return
        val text = summary.text.toString()
        if (text == iss.summary) {
            editing = false
            return
        }
        val api = model.api ?: return
        saving = true
        scope.launch {
            try {
                api.jiraUpdateTicketSummary(configId, issueKey, text)
                issue = iss.copy(summary = text)
                editing = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            saving = false
        }
    }

    fun transition(id: String) {
        val api = model.api ?: return
        saving = true
        scope.launch {
            try {
                api.jiraTransitionTicket(configId, issueKey, id)
                issue = parseJiraIssue(api.jiraGetTicket(configId, issueKey))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            saving = false
        }
    }

    WebDialog(title = null, onDismiss = onClose, tallest = dialogTallest()) {
        Column(Modifier.cssFlex().fillMaxWidth().verticalScroll(rememberScrollState()).testTag("jira-ticket-detail"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(issueKey, 18f, weight = FontWeight.SemiBold)
                if (baseUrl != null) {
                    IconBtn(ProductIcon.ExternalLink, "Open in Jira", size = 28.dp, modifier = Modifier.testTag("jira-ticket-external")) {
                        runCatching { uriHandler.openUri("$baseUrl/browse/$issueKey") }
                    }
                }
            }
            val iss = issue
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 24.dp, tokens.text3.color, spinning = true)
                }
                iss == null -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    T("Failed to load issue.", 13f, tokens.text3.color)
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label("Summary")
                        if (editing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                FormInput(summary, "", Modifier.weight(1f).testTag("jira-ticket-summary-input"), height = 36.dp, onSubmit = ::saveSummary)
                                DialogButton("Save", primary = true, dark = true, enabled = !saving, modifier = Modifier.testTag("jira-ticket-summary-save"), spinning = saving, height = 36.dp, onClick = ::saveSummary)
                            }
                        } else {
                            T(
                                iss.summary, 14f, maxLines = 6,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).ppmClickable(onClickLabel = "Edit summary") { editing = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp).testTag("jira-ticket-summary"),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Label("Status")
                            T(iss.statusName, 14f, weight = FontWeight.Medium, modifier = Modifier.testTag("jira-ticket-status"))
                        }
                        if (transitions.isNotEmpty()) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Label("Transition to")
                                SelectField(
                                    value = null,
                                    options = transitions.map { SelectOption(it.id, it.label) },
                                    onValueChange = ::transition,
                                    modifier = Modifier.fillMaxWidth().testTag("jira-ticket-transition"),
                                    enabled = !saving,
                                    placeholder = "Change status...",
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Label("Priority")
                            T(iss.priorityName ?: "None", 14f)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Label("Assignee")
                            T(iss.assigneeName ?: "Unassigned", 14f)
                        }
                    }
                    iss.description?.takeIf { it.isNotEmpty() }?.let { d ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Label("Description")
                            Box(
                                Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(4.dp)).background(tokens.panel2.opacity(0.6).color)
                                    .verticalScroll(rememberScrollState()).padding(8.dp),
                            ) { T(d, 12f, tokens.text3.color, maxLines = Int.MAX_VALUE) }
                        }
                    }
                    T("Updated: ${jiraUpdatedLabel(iss.updated)}", 12f, tokens.text3.color)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) = T(text, 12f, LocalPpmTokens.current.text3.color)
