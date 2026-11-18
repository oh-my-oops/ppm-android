package com.hienlh.ppm.app.ui.jira

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.jira
import com.hienlh.ppm.app.ui.DialogButton
import com.hienlh.ppm.app.ui.FieldLabel
import com.hienlh.ppm.app.ui.FormInput
import com.hienlh.ppm.app.ui.FormTextArea
import com.hienlh.ppm.app.ui.InputKind
import com.hienlh.ppm.app.ui.PpmSwitch
import com.hienlh.ppm.app.ui.SelectField
import com.hienlh.ppm.app.ui.SelectOption
import com.hienlh.ppm.app.ui.WebDialog
import com.hienlh.ppm.app.ui.tasks.IconBtn
import com.hienlh.ppm.app.ui.tasks.T
import com.hienlh.ppm.app.ui.tasks.txt
import com.hienlh.ppm.client.jiraMetadata
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.state.jira.JIRA_INTERVALS
import com.hienlh.ppm.state.jira.JiraFieldOption
import com.hienlh.ppm.state.jira.JiraFilterField
import com.hienlh.ppm.state.jira.JiraTestResult
import com.hienlh.ppm.state.jira.filtersToJql
import com.hienlh.ppm.state.jira.formatJiraInterval
import com.hienlh.ppm.state.jira.jiraPulledText
import com.hienlh.ppm.state.jira.jiraTestHeading
import com.hienlh.ppm.state.jira.parseJiraAssigneeOptions
import com.hienlh.ppm.state.jira.parseJiraMetadataProjects
import com.hienlh.ppm.state.jira.parseJiraNamedOptions
import com.hienlh.ppm.state.settings.JiraConfigRow
import com.hienlh.ppm.state.settings.JiraWatcherRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private fun messageOf(e: Exception, fallback: String) = e.message?.takeIf { it.isNotBlank() } ?: fallback

/** A project's Jira site, email and API token (`jira-config-form.tsx`). */
@Composable
internal fun JiraConfigForm(model: AppModel, projectId: Int, existing: JiraConfigRow?) {
    val tokens = LocalPpmTokens.current
    val jira = model.jira
    val scope = rememberCoroutineScope()
    val baseUrl = remember(projectId) { TextFieldState(existing?.baseUrl.orEmpty()) }
    val email = remember(projectId) { TextFieldState(existing?.email.orEmpty()) }
    val token = remember(projectId) { TextFieldState() }
    // Follows the saved config when it loads or changes.
    LaunchedEffect(existing?.baseUrl, existing?.email) {
        if (existing != null) {
            baseUrl.edit { replace(0, length, existing.baseUrl) }
            email.edit { replace(0, length, existing.email) }
        }
    }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var tokenCleared by remember { mutableStateOf(false) }

    fun save() {
        val url = baseUrl.text.toString()
        val mail = email.text.toString()
        val tok = token.text.toString()
        if (url.isEmpty() || mail.isEmpty() || (tok.isEmpty() && existing?.hasToken != true)) return
        saving = true
        scope.launch {
            try {
                tokenCleared = jira.saveConfig(projectId, url, mail, tok.ifEmpty { null })
                token.edit { replace(0, length, "") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The web's form stays as it was.
            }
            saving = false
        }
    }

    Column(Modifier.fillMaxWidth().testTag("jira-config-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Base URL")
            FormInput(baseUrl, "https://mysite.atlassian.net", Modifier.fillMaxWidth().testTag("jira-config-url"), height = 36.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Email")
            FormInput(email, "you@company.com", Modifier.fillMaxWidth().testTag("jira-config-email"), height = 36.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("API Token")
                if (existing?.hasToken == true) T("(saved)", 11f, tokens.success.color)
            }
            FormInput(
                token,
                if (existing?.hasToken == true) "Enter new token to replace" else "Your Jira API token",
                Modifier.fillMaxWidth().testTag("jira-config-token"),
                kind = InputKind.Password,
                height = 36.dp,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            DialogButton("Save", primary = true, dark = true, enabled = !saving, modifier = Modifier.testTag("jira-config-save"), spinning = saving, height = 36.dp, onClick = ::save)
            if (existing != null) {
                DialogButton("Test Connection", primary = false, dark = true, enabled = !testing, modifier = Modifier.testTag("jira-config-test"), spinning = testing, height = 36.dp) {
                    testing = true
                    testResult = null
                    testError = null
                    scope.launch {
                        try {
                            testResult = jira.testConnection(projectId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            testResult = false
                            testError = messageOf(e, "Connection failed")
                        }
                        testing = false
                    }
                }
                IconBtn(ProductIcon.Trash2, "Delete credentials", size = 36.dp, tint = tokens.error.color, modifier = Modifier.testTag("jira-config-delete")) {
                    scope.launch {
                        try {
                            jira.deleteConfig(projectId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            when (testResult) {
                true -> PpmIcon(ProductIcon.CheckCircle, 16.dp, tokens.success.color)
                false -> PpmIcon(ProductIcon.AlertCircle, 16.dp, tokens.error.color)
                null -> Unit
            }
        }
        testError?.let { T(it, 12f, tokens.error.color, maxLines = 4) }
        if (existing != null && !existing.hasToken) {
            T(
                (if (tokenCleared) "The site or email changed, so the saved API token was cleared. " else "No API token is saved. ") + "Enter the API token again.",
                12f, tokens.warning.color, maxLines = 4,
            )
        }
    }
}

/** A config's watchers with their switch, edit, pull and delete (`jira-watcher-list.tsx`). */
@Composable
internal fun JiraWatcherList(model: AppModel, configId: Int) {
    val tokens = LocalPpmTokens.current
    val jira = model.jira
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JiraWatcherRow?>(null) }
    var pulling by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(configId) {
        jira.watchedConfigId = configId
        jira.loadWatchers(configId)
    }
    Column(Modifier.fillMaxWidth().testTag("jira-watcher-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Watchers", 14f, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            DialogButton("Add", primary = false, dark = true, enabled = true, modifier = Modifier.testTag("jira-watcher-add"), icon = ProductIcon.Plus, height = 36.dp) { adding = true }
        }
        if (jira.watchers.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { T("No watchers yet.", 13f, tokens.text3.color) }
        }
        for (w in jira.watchers) {
            val shape = RoundedCornerShape(6.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.border.color, shape).padding(8.dp).testTag("jira-watcher-${w.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmSwitch(checked = w.enabled, onCheckedChange = { jira.toggleWatcher(w.id, it) })
                Column(Modifier.weight(1f)) {
                    T(w.name, 13f, weight = FontWeight.Medium)
                    T(w.jql, 12f, tokens.text3.color, mono = true)
                }
                T("${if (w.mode == "notify") "notify" else "debug"} · ${formatJiraInterval(w.intervalMs)}", 12f, tokens.text3.color)
                IconBtn(ProductIcon.Pencil, "Edit watcher", size = 32.dp) { editing = w }
                Box(
                    Modifier.padding(0.dp).clip(RoundedCornerShape(6.dp)).ppmClickable(enabled = pulling != w.id, onClickLabel = "Pull now") {
                        pulling = w.id
                        scope.launch {
                            try {
                                val n = jira.pullWatcher(w.id)
                                model.toast(ErrorToastMessage(jiraPulledText(n), kind = ErrorToastMessage.Kind.SUCCESS))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                model.toast(ErrorToastMessage(messageOf(e, "Pull failed")))
                            }
                            pulling = null
                        }
                    }.padding(8.dp).testTag("jira-watcher-pull-${w.id}"),
                ) {
                    PpmIcon(if (pulling == w.id) ProductIcon.Loader2 else ProductIcon.Play, 14.dp, tokens.text3.color, spinning = pulling == w.id)
                }
                IconBtn(ProductIcon.Trash2, "Delete watcher", size = 32.dp, tint = tokens.error.color) { jira.deleteWatcher(w) }
            }
        }
    }
    if (adding) {
        WebDialog(title = "New Watcher", onDismiss = { adding = false }, widest = 448.dp) {
            JiraWatcherForm(model, configId, null) { adding = false }
        }
    }
    editing?.let { w ->
        WebDialog(title = "Edit Watcher", onDismiss = { editing = null }, widest = 448.dp) {
            JiraWatcherForm(model, configId, w) { editing = null }
        }
    }
}

/** A watcher's name, filter, interval, mode and prompt template (`jira-watcher-form.tsx`). */
@Composable
internal fun JiraWatcherForm(model: AppModel, configId: Int, existing: JiraWatcherRow?, onDone: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val jira = model.jira
    val scope = rememberCoroutineScope()
    val name = remember { TextFieldState(existing?.name.orEmpty()) }
    var jql by remember { mutableStateOf(existing?.jql.orEmpty()) }
    var intervalMs by remember { mutableIntStateOf(existing?.intervalMs ?: 120_000) }
    var mode by remember { mutableStateOf(existing?.mode ?: "debug") }
    val prompt = remember { TextFieldState(existing?.promptTemplate.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<JiraTestResult?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    val nameText = name.text.toString()

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("jira-watcher-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Name")
            FormInput(name, "Bug watcher", Modifier.fillMaxWidth().testTag("jira-watcher-name"), height = 36.dp)
        }
        JiraFilterBuilder(model, jql, { jql = it }, configId)
        DialogButton(
            "Test Filter",
            primary = false,
            dark = true,
            enabled = jql.isNotEmpty() && !testing,
            modifier = Modifier.fillMaxWidth().testTag("jira-watcher-test"),
            icon = ProductIcon.Search,
            spinning = testing,
        ) {
            testing = true
            testError = null
            test = null
            scope.launch {
                try {
                    test = jira.testJql(configId, jql)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    testError = messageOf(e, "Test failed")
                }
                testing = false
            }
        }
        testError?.let {
            T(it, 12f, tokens.error.color, maxLines = 4, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(tokens.error.opacity(0.1).color).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        test?.let { r ->
            val shape = RoundedCornerShape(6.dp)
            Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, tokens.border.color, shape).testTag("jira-watcher-test-result")) {
                T(jiraTestHeading(r), 12f, tokens.text3.color, weight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().background(tokens.panel2.color).padding(horizontal = 12.dp, vertical = 6.dp))
                if (r.issues.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { T("No tickets match this filter.", 12f, tokens.text3.color) }
                } else {
                    Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                        for (issue in r.issues) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                T(issue.key, 12f, weight = FontWeight.Medium, mono = true)
                                T(issue.summary, 12f, tokens.text3.color, modifier = Modifier.weight(1f))
                                T(issue.statusName, 10f, tokens.text3.color, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color).padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Interval")
                SelectField(
                    value = intervalMs.toString(),
                    options = JIRA_INTERVALS.map { (label, ms) -> SelectOption(ms.toString(), label) },
                    onValueChange = { intervalMs = it.toInt() },
                    modifier = Modifier.fillMaxWidth().testTag("jira-watcher-interval"),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FieldLabel("Mode")
                SelectField(
                    value = mode,
                    options = listOf(SelectOption("debug", "Debug + Notify"), SelectOption("notify", "Notify only")),
                    onValueChange = { mode = it },
                    modifier = Modifier.fillMaxWidth().testTag("jira-watcher-mode"),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FieldLabel("Prompt template (optional)")
            FormTextArea(prompt, "Debug Jira issue {issue_key}: {summary}", Modifier.fillMaxWidth().height(64.dp).testTag("jira-watcher-prompt"))
        }
        DialogButton(
            if (existing != null) "Save Changes" else "Create Watcher",
            primary = true,
            dark = true,
            enabled = !saving && nameText.isNotEmpty() && jql.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("jira-watcher-submit"),
            spinning = saving,
        ) {
            saving = true
            val template = prompt.text.toString().ifEmpty { null }
            scope.launch {
                try {
                    if (existing != null) jira.updateWatcher(existing, nameText, jql, template, intervalMs, mode)
                    else jira.createWatcher(configId, nameText, jql, template, intervalMs, mode)
                    onDone()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The web's form stays open as it was.
                }
                saving = false
            }
        }
    }
}

/**
 * A filter as JQL, built from Jira's own projects, types, priorities, statuses and people, or
 * typed raw (`jira-filter-builder.tsx`). A watcher that already has a filter opens on Raw JQL.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JiraFilterBuilder(model: AppModel, value: String, onChange: (String) -> Unit, configId: Int) {
    val tokens = LocalPpmTokens.current
    var raw by remember { mutableStateOf(value.isNotEmpty()) }
    var filters by remember { mutableStateOf<Map<JiraFilterField, List<String>>>(emptyMap()) }
    val rawJql = remember { TextFieldState(value) }
    var options by remember { mutableStateOf<Map<JiraFilterField, List<JiraFieldOption>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(configId) {
        val api = model.api ?: return@LaunchedEffect
        loading = true
        suspend fun <T> orEmpty(block: suspend () -> List<T>): List<T> = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        options = coroutineScope {
            val p = async { orEmpty { parseJiraMetadataProjects(api.jiraMetadata(configId, "projects")) } }
            val it = async { orEmpty { parseJiraNamedOptions(api.jiraMetadata(configId, "issuetype")) } }
            val pr = async { orEmpty { parseJiraNamedOptions(api.jiraMetadata(configId, "priority")) } }
            val st = async { orEmpty { parseJiraNamedOptions(api.jiraMetadata(configId, "status")) } }
            val a = async { orEmpty { parseJiraAssigneeOptions(api.jiraMetadata(configId, "assignees")) } }
            mapOf(
                JiraFilterField.PROJECT to p.await(),
                JiraFilterField.ISSUE_TYPE to it.await(),
                JiraFilterField.PRIORITY to pr.await(),
                JiraFilterField.STATUS to st.await(),
                JiraFilterField.ASSIGNEE to a.await(),
            )
        }
        loading = false
    }
    // Builder → JQL, as the builder changes.
    LaunchedEffect(filters, raw) { if (!raw) onChange(filtersToJql(filters)) }
    LaunchedEffect(rawJql, raw) {
        if (raw) snapshotFlow { rawJql.text.toString() }.collect { onChange(it) }
    }

    Column(Modifier.fillMaxWidth().testTag("jira-filter-builder"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton("Builder", primary = !raw, dark = true, enabled = true, modifier = Modifier.testTag("jira-filter-builder-mode"), height = 36.dp) { raw = false }
            DialogButton("Raw JQL", primary = raw, dark = true, enabled = true, modifier = Modifier.testTag("jira-filter-raw-mode"), height = 36.dp) { raw = true }
            if (loading) PpmIcon(ProductIcon.Loader2, 14.dp, tokens.text3.color, spinning = true)
        }
        if (raw) {
            FormTextArea(rawJql, "e.g. project = MYPROJ AND status = \"In Progress\"", Modifier.fillMaxWidth().height(80.dp).testTag("jira-filter-raw"))
        } else {
            for (field in JiraFilterField.entries) {
                val chosen = filters[field].orEmpty()
                val all = options[field].orEmpty()
                val available = all.filter { it.value !in chosen }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldLabel(field.label)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        for (v in chosen) {
                            val label = all.firstOrNull { it.value == v }?.label ?: v
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(tokens.accent.opacity(0.1).color).padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                T(label, 12f)
                                IconBtn(ProductIcon.X, "Remove $label", size = 18.dp) { filters = filters + (field to chosen - v) }
                            }
                        }
                        when {
                            available.isNotEmpty() -> SelectField(
                                value = null,
                                options = available.map { SelectOption(it.value, it.label) },
                                onValueChange = { v -> if (v !in chosen) filters = filters + (field to chosen + v) },
                                modifier = Modifier.widthIn(min = 120.dp).testTag("jira-filter-${field.name.lowercase()}"),
                                placeholder = field.placeholder,
                            )
                            all.isNotEmpty() -> BasicText("All selected", style = txt(12f, tokens.text3.color).copy(fontStyle = FontStyle.Italic))
                            else -> BasicText("Loading...", style = txt(12f, tokens.text3.color).copy(fontStyle = FontStyle.Italic))
                        }
                    }
                }
            }
        }
        BasicText(
            if (raw) rawJql.text.toString().ifEmpty { "(empty)" } else filtersToJql(filters),
            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(tokens.panel2.color).padding(horizontal = 8.dp, vertical = 4.dp).testTag("jira-filter-preview"),
            style = txt(12f, tokens.text3.color, mono = true),
        )
    }
}
