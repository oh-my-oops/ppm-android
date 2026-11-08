package com.hienlh.ppm.app.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.logsGithubDuplicates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.hienlh.ppm.app.ui.PpmTextField
import com.hienlh.ppm.app.ui.tablet.Edge
import com.hienlh.ppm.app.ui.tablet.edge
import com.hienlh.ppm.client.logsAnalyzeIssues
import com.hienlh.ppm.client.logsDraftReport
import com.hienlh.ppm.client.logsEnvironment
import com.hienlh.ppm.client.logsIssues
import com.hienlh.ppm.client.logsSetIssueDismissed
import com.hienlh.ppm.client.logsSetIssuesAuto
import com.hienlh.ppm.client.logsUndismissAllIssues
import com.hienlh.ppm.client.queryLogs
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.LucideIcon
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.logs.*
import com.hienlh.ppm.state.windows.WindowRuntimeState
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun LogsWindowContent(model: AppModel, win: WindowRuntimeState) {
    val initialView = win.payload["logsView"].string ?: "logs"
    LogsAppBody(
        model = model,
        initialView = initialView,
        onViewChange = { next ->
            model.windows?.setPayload(win.id, buildJsonObject { put("logsView", next) })
        },
    )
}

@Composable
fun LogsTabBody(model: AppModel, tab: WorkspaceTab) {
    val initialView = tab.metaString("logsView") ?: "logs"
    LogsAppBody(
        model = model,
        initialView = initialView,
        onViewChange = { next ->
            model.patchTabMetadata(tab.id, "logsView" to JsonPrimitive(next))
        },
    )
}

@Composable
fun LogsAppBody(
    model: AppModel,
    initialView: String = "logs",
    onViewChange: (String) -> Unit = {},
    initialResult: LogQueryResult? = null,
    initialIssues: LogIssuesResult? = null,
) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var activeView by remember(initialView) { mutableStateOf(initialView) }
    var filter by remember { mutableStateOf(DEFAULT_LOG_FILTER) }
    var range by remember { mutableStateOf("1h") }
    var result by remember { mutableStateOf(initialResult ?: LogQueryResult()) }
    var loading by remember { mutableStateOf(initialResult == null) }
    var follow by remember { mutableStateOf(true) }
    var utc by remember { mutableStateOf(false) }
    var prefsOpen by remember { mutableStateOf(false) }
    val liveBuffer = remember { LogsLiveFeedBuffer() }
    var pausedCount by remember { mutableIntStateOf(0) }
    var reloads by remember { mutableIntStateOf(0) }

    var issuesResult by remember { mutableStateOf(initialIssues ?: LogIssuesResult()) }
    var issuesLoading by remember { mutableStateOf(false) }

    // Report state
    var snippets by remember { mutableStateOf<List<ReportSnippet>>(emptyList()) }
    val titleField = remember { TextFieldState("") }
    val whatField = remember { TextFieldState("") }
    val stepsField = remember { TextFieldState("") }
    val expectedField = remember { TextFieldState("") }
    var labels by remember { mutableStateOf(listOf("bug")) }
    var redactOpts by remember {
        mutableStateOf(
            RedactOptions(
                home = true,
                email = true,
                chats = true,
                projects = model.projects.map { it.name },
            ),
        )
    }
    var envPairs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var envLoaded by remember { mutableStateOf(false) }
    var draftingAi by remember { mutableStateOf(false) }
    var bannerError by remember { mutableStateOf<String?>(null) }

    fun selectView(v: String) {
        activeView = v
        onViewChange(v)
    }

    suspend fun loadLogs(before: String? = null) {
        val api = model.api ?: run {
            loading = false
            return
        }
        if (before == null) {
            model.events?.sendIfOpen("""{"type":"logs:subscribe"}""")
            liveBuffer.beginPageRead()
        }
        loading = true
        try {
            val from = rangeStart(range, System.currentTimeMillis())
            val pairs = logsQueryPairs(filter, range, from, before = before)
            val json = api.queryLogs(pairs)
            val parsed = LogQueryResult.from(json)
            result = if (before != null) {
                val have = result.entries.take(DEDUPE_TAIL).map { it.id }.toSet()
                val older = parsed.entries.filterNot { it.id in have }
                result.copy(
                    entries = older + result.entries,
                    hasMore = parsed.hasMore,
                    titles = parsed.titles + result.titles,
                    reachMissed = parsed.reachMissed,
                )
            } else {
                liveBuffer.finishPageRead(parsed, filter)
            }
            bannerError = null
        } catch (e: CancellationException) {
            liveBuffer.cancelPageRead()
            throw e
        } catch (e: Exception) {
            liveBuffer.cancelPageRead()
            bannerError = e.message ?: "Failed to query logs"
        } finally {
            loading = false
        }
    }

    suspend fun loadIssues() {
        val api = model.api ?: return
        issuesLoading = true
        try {
            val json = api.logsIssues()
            val parsed = LogIssuesResult.from(json)
            issuesResult = parsed
            if (model.logsBadge && model.info?.minimalMode == false) {
                model.logsLikelyBugs = parsed.issues.count { !it.dismissed && it.cls == "bug" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bannerError = e.message ?: "Failed to load log issues"
        } finally {
            issuesLoading = false
        }
    }

    // Live tail via GlobalEvents: subscribe BEFORE the page read, resubscribe and re-read on global_ready.
    DisposableEffect(model, model.events) {
        model.onLogsWindowMounted()
        val ev = model.events
        var unsubLines: (() -> Unit)? = null
        var unsubIssues: (() -> Unit)? = null
        var unsubReady: (() -> Unit)? = null
        if (ev != null) {
            ev.sendIfOpen("""{"type":"logs:subscribe"}""")
            unsubReady = ev.onGlobalReady {
                ev.sendIfOpen("""{"type":"logs:subscribe"}""")
                reloads += 1
            }
            unsubLines = ev.onLogsLines { msg ->
                val incoming = msg["entries"].array.orEmpty().mapNotNull(LogEntry::from)
                val incomingTitles = msg["titles"].obj?.mapNotNull { (k, v) -> v.string?.let { k to it } }?.toMap().orEmpty()
                result = liveBuffer.onLiveBatch(result, incoming, incomingTitles, filter, follow)
                pausedCount = liveBuffer.pausedMatchingCount(result, filter)
            }
            unsubIssues = ev.onLogsIssuesChanged {
                scope.launch { loadIssues() }
            }
        }
        onDispose {
            model.onLogsWindowUnmounted()
            unsubReady?.invoke()
            unsubLines?.invoke()
            unsubIssues?.invoke()
            ev?.sendIfOpen("""{"type":"logs:unsubscribe"}""")
        }
    }

    LaunchedEffect(model.api, filter, range, reloads) {
        if (reloads > 0 || initialResult == null || filter != DEFAULT_LOG_FILTER || range != "1h") {
            loadLogs()
        }
    }

    suspend fun loadEnvironment(): List<Pair<String, String>> {
        val api = model.api ?: return envPairs
        val envJson = try {
            api.logsEnvironment()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (envJson != null) {
            val list = parseLogsEnvironment(envJson)
            envPairs = list
            envLoaded = true
            return list
        }
        return envPairs
    }

    LaunchedEffect(model.api) {
        val api = model.api ?: return@LaunchedEffect
        if (initialIssues == null) {
            launch { loadIssues() }
        }
        launch { loadEnvironment() }
    }

    fun resumeFollow() {
        follow = true
        result = liveBuffer.resumeFollow(result, filter)
        pausedCount = 0
    }

    fun draftWithAi(targetSnippets: List<ReportSnippet>, note: String = "") {
        val api = model.api ?: return
        if (targetSnippets.isEmpty() || draftingAi) return
        draftingAi = true
        scope.launch {
            try {
                val activeEnv = if (!envLoaded) loadEnvironment() else envPairs
                val snippetsJson = kotlinx.serialization.json.JsonArray(
                    targetSnippets.map { s ->
                        buildJsonObject {
                            put("label", JsonPrimitive(snippetHeading(s)))
                            put(
                                "lines",
                                kotlinx.serialization.json.JsonArray(
                                    s.rows.take(500).map { JsonPrimitive(rawLogLine(it.entry, it.count)) },
                                ),
                            )
                        }
                    },
                )
                val envJson = kotlinx.serialization.json.JsonArray(
                    activeEnv.take(20).map { (k, v) ->
                        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v)))
                    },
                )
                val res = api.logsDraftReport(
                    buildJsonObject {
                        put("snippets", snippetsJson)
                        put("environment", envJson)
                        if (note.isNotBlank()) put("note", JsonPrimitive(note))
                    },
                )
                res?.get("title")?.string?.takeIf { it.isNotBlank() }?.let { t ->
                    titleField.edit { replace(0, length, t) }
                }
                res?.get("labels")?.array?.mapNotNull { it.string }?.takeIf { it.isNotEmpty() }?.let { l ->
                    labels = l
                }
                res?.get("what")?.string?.takeIf { it.isNotBlank() }?.let { w ->
                    whatField.edit { replace(0, length, w) }
                }
                res?.get("steps")?.string?.takeIf { it.isNotBlank() }?.let { s ->
                    stepsField.edit { replace(0, length, s) }
                }
                res?.get("expected")?.string?.takeIf { it.isNotBlank() }?.let { ex ->
                    expectedField.edit { replace(0, length, ex) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bannerError = e.message ?: "AI draft failed"
            } finally {
                draftingAi = false
            }
        }
    }

    val likelyBugCount = issuesResult.issues.count { !it.dismissed && it.cls == "bug" }

    Column(
        Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("logs-app-body"),
    ) {
        // Sub-tab header
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(tokens.panel2.opacity(0.6).color)
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LogsSubTabButton(
                    id = "logs",
                    label = "Logs",
                    icon = ProductIcon.ScrollText,
                    active = activeView == "logs",
                    badge = null,
                    badgeColor = tokens.accent.color,
                    onClick = { selectView("logs") },
                )
                LogsSubTabButton(
                    id = "issues",
                    label = "Issues",
                    icon = ProductIcon.Sparkles,
                    active = activeView == "issues",
                    badge = likelyBugCount.takeIf { it > 0 }?.toString(),
                    badgeColor = tokens.error.color,
                    onClick = { selectView("issues") },
                )
                LogsSubTabButton(
                    id = "report",
                    label = "Report Bug",
                    icon = ProductIcon.Bug,
                    active = activeView == "report",
                    badge = snippets.size.takeIf { it > 0 }?.toString(),
                    badgeColor = tokens.accent.color,
                    onClick = { selectView("report") },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (activeView == "logs") {
                    val followShape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier
                            .height(26.dp)
                            .clip(followShape)
                            .background(if (follow) Color(0xFF22C55E).copy(alpha = 0.15f) else tokens.panel2.color)
                            .ppmClickable(shape = followShape) {
                                if (follow) follow = false else resumeFollow()
                            }
                            .padding(horizontal = 8.dp)
                            .testTag("logs-follow-toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (follow) Color(0xFF22C55E) else tokens.text2.color),
                        )
                        BasicText(
                            if (follow) "Live" else if (pausedCount > 0) "Paused (+$pausedCount)" else "Paused",
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                .copy(color = if (follow) Color(0xFF22C55E) else tokens.text2.color),
                        )
                    }
                    Box(
                        Modifier
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(tokens.panel2.color)
                            .ppmClickable { utc = !utc }
                            .padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            if (utc) "UTC" else localOffsetLabel(),
                            style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (prefsOpen) tokens.accent.opacity(0.12).color else Color.Transparent)
                        .ppmClickable { prefsOpen = !prefsOpen }
                        .semantics { contentDescription = "Logs preferences" }
                        .testTag("logs-prefs-toggle"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(
                        ProductIcon.Settings,
                        14.dp,
                        if (prefsOpen) tokens.accent.color else tokens.text2.color,
                    )
                }
            }
        }

        if (prefsOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.panel2.opacity(0.45).color)
                    .edge(Edge.Bottom, tokens.border.color)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("logs-prefs"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BasicText(
                        "Times",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                    val localShape = RoundedCornerShape(4.dp)
                    Box(
                        Modifier
                            .clip(localShape)
                            .background(if (!utc) tokens.accent.opacity(0.14).color else tokens.panel2.color)
                            .ppmClickable(shape = localShape) { utc = false }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        BasicText(
                            "Local (${localOffsetLabel()})",
                            style = ppmText(11f, lineHeight = 15f).copy(color = if (!utc) tokens.accent.color else tokens.text2.color),
                        )
                    }
                    Box(
                        Modifier
                            .clip(localShape)
                            .background(if (utc) tokens.accent.opacity(0.14).color else tokens.panel2.color)
                            .ppmClickable(shape = localShape) { utc = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        BasicText(
                            "UTC",
                            style = ppmText(11f, lineHeight = 15f).copy(color = if (utc) tokens.accent.color else tokens.text2.color),
                        )
                    }
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .ppmClickable { model.updateLogsBadge(!model.logsBadge) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("logs-prefs-badge-switch"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 24.dp, height = 14.dp)
                            .clip(CircleShape)
                            .background(if (model.logsBadge) tokens.accent.color else tokens.border.color)
                            .padding(2.dp),
                        contentAlignment = if (model.logsBadge) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    }
                    BasicText(
                        "Red dot on the Logs button for a likely bug",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color),
                    )
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .ppmClickable {
                            val nextAuto = !issuesResult.auto
                            scope.launch {
                                runCatching { model.api?.logsSetIssuesAuto(nextAuto) }
                                loadIssues()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("logs-prefs-auto-switch"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = 24.dp, height = 14.dp)
                            .clip(CircleShape)
                            .background(if (issuesResult.auto) tokens.accent.color else tokens.border.color)
                            .padding(2.dp),
                        contentAlignment = if (issuesResult.auto) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    }
                    BasicText(
                        "Let AI sort new errors and warnings",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color),
                    )
                }
                BasicText(
                    "Keeps ${result.files.generations} × ${byteSize(result.files.capBytes)} of ppm.log and ${plural(result.files.browserRetentionDays, "day")} of browser logs",
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
        }

        if (bannerError != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(tokens.error.opacity(0.12).color)
                    .edge(Edge.Bottom, tokens.error.opacity(0.25).color)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(
                    bannerError!!,
                    Modifier.weight(1f),
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.error.color),
                )
                Box(Modifier.size(20.dp).ppmClickable { bannerError = null }, contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.X, 12.dp, tokens.error.color)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (activeView) {
                "logs" -> LogsStreamPane(
                    result = result,
                    loading = loading,
                    filter = filter,
                    range = range,
                    utc = utc,
                    follow = follow,
                    onFilterChange = { filter = it },
                    onRangeChange = { range = it },
                    onLoadEarlier = {
                        val oldestId = result.entries.firstOrNull()?.id
                        if (oldestId != null) scope.launch { loadLogs(before = oldestId) }
                    },
                    onAddToReport = { selectedRows ->
                        val snip = ReportSnippet(
                            id = "snip-${System.currentTimeMillis()}",
                            src = filter.src,
                            rows = selectedRows,
                        )
                        snippets = snippets + snip
                        selectView("report")
                    },
                    onCopyLines = { text ->
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("PPM Logs", text))
                    },
                )
                "issues" -> LogsIssuesPane(
                    issuesResult = issuesResult,
                    loading = issuesLoading,
                    onAnalyze = {
                        scope.launch {
                            runCatching { model.api?.logsAnalyzeIssues(full = false) }
                            loadIssues()
                        }
                    },
                    onToggleAuto = { nextAuto ->
                        scope.launch {
                            runCatching { model.api?.logsSetIssuesAuto(nextAuto) }
                            loadIssues()
                        }
                    },
                    onRestoreDismissed = {
                        scope.launch {
                            runCatching { model.api?.logsUndismissAllIssues() }
                            loadIssues()
                        }
                    },
                    onDismissIssue = { id, dismissed ->
                        scope.launch {
                            runCatching { model.api?.logsSetIssueDismissed(id, dismissed) }
                            loadIssues()
                        }
                    },
                    onShowInLogs = { issue ->
                        filter = filter.copy(src = issue.src, q = "")
                        selectView("logs")
                    },
                    onDraftReport = { issue ->
                        val rows = foldRepeats(issue.lines)
                        val snip = ReportSnippet(
                            id = "issue-${issue.id}",
                            src = issue.src,
                            rows = rows,
                        )
                        snippets = listOf(snip)
                        titleField.edit { replace(0, length, issue.title) }
                        whatField.edit { replace(0, length, issue.why) }
                        selectView("report")
                        if (rows.isNotEmpty()) draftWithAi(listOf(snip), issue.title)
                    },
                )
                "report" -> LogsReportPane(
                    model = model,
                    titleField = titleField,
                    whatField = whatField,
                    stepsField = stepsField,
                    expectedField = expectedField,
                    labels = labels,
                    onToggleLabel = { lbl ->
                        labels = if (lbl in labels) labels - lbl else labels + lbl
                    },
                    snippets = snippets,
                    onRemoveSnippet = { id -> snippets = snippets.filterNot { it.id == id } },
                    redact = redactOpts,
                    onRedactChange = { redactOpts = it },
                    environment = envPairs,
                    draftingAi = draftingAi,
                    onDraftWithAi = {
                        val targetSnips = snippets.ifEmpty {
                            val rows = foldRepeats(result.entries.takeLast(80))
                            if (rows.isEmpty()) emptyList() else listOf(ReportSnippet(id = "s-auto", src = filter.src, rows = rows))
                        }
                        draftWithAi(targetSnips, titleField.text.toString())
                    },
                    onCopyMarkdown = { md ->
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("PPM Bug Report", md))
                    },
                    onOpenGithub = { url ->
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LogsSubTabButton(
    id: String,
    label: String,
    icon: ProductIcon,
    active: Boolean,
    badge: String?,
    badgeColor: Color,
    onClick: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (active) tokens.accent.opacity(0.15).color else Color.Transparent)
            .ppmClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag("logs-tab-$id"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PpmIcon(icon, 14.dp, if (active) tokens.accent.color else tokens.text2.color)
        BasicText(
            label,
            style = ppmText(12f, if (active) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 16f)
                .copy(color = if (active) tokens.accent.color else tokens.text2.color),
        )
        if (badge != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    badge,
                    style = ppmText(10f, FontWeight.Bold, lineHeight = 12f).copy(color = Color.White),
                )
            }
        }
    }
}

// ── 1. Logs Stream Pane ────────────────────────────────────────────────

@Composable
private fun LogsStreamPane(
    result: LogQueryResult,
    loading: Boolean,
    filter: LogFilter,
    range: String,
    utc: Boolean,
    follow: Boolean = true,
    onFilterChange: (LogFilter) -> Unit,
    onRangeChange: (String) -> Unit,
    onLoadEarlier: () -> Unit,
    onAddToReport: (List<LogRow>) -> Unit,
    onCopyLines: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val searchField = remember { TextFieldState(filter.q) }
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedMoreKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()

    LaunchedEffect(searchField.text.toString()) {
        val q = searchField.text.toString()
        if (q != filter.q) onFilterChange(filter.copy(q = q))
    }

    val searchCompiled = remember(filter) { compileLogSearch(filter) }
    val rows = remember(result.entries) { foldRepeats(result.entries) }
    val dividers = remember(rows, result.restarts) { restartDividers(rows, result.restarts) }
    val lastEntryId = rows.lastOrNull()?.entry?.id
    LaunchedEffect(follow, lastEntryId, rows.size) {
        if (follow && rows.isNotEmpty()) {
            val targetIdx = (rows.size - 1 + (if (result.hasMore) 1 else 0) + dividers.size).coerceAtLeast(0)
            runCatching { listState.scrollToItem(targetIdx) }
        }
    }

    Box(Modifier.fillMaxSize().testTag("logs-stream-pane")) {
        Column(Modifier.fillMaxSize()) {
            // Filter bar
            Column(
                Modifier
                    .fillMaxWidth()
                    .edge(Edge.Bottom, tokens.border.color)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Source chips
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val allSources = listOf("all" to "All") + LOG_SOURCES.map { it.id to it.label }
                    for ((srcId, label) in allSources) {
                        val active = filter.src == srcId
                        val st = if (srcId == "all") null else result.stats[srcId]
                        val chipShape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .height(26.dp)
                                .clip(chipShape)
                                .background(if (active) tokens.accent.opacity(0.16).color else tokens.panel2.opacity(0.55).color)
                                .cssBorder((if (active) tokens.accent else tokens.border).color, chipShape)
                                .ppmClickable(shape = chipShape) { onFilterChange(filter.copy(src = srcId)) }
                                .padding(horizontal = 8.dp)
                                .testTag("logs-source-$srcId"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            BasicText(
                                label,
                                style = ppmText(11f, if (active) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 15f)
                                    .copy(color = if (active) tokens.accent.color else tokens.text.color),
                            )
                            if (st != null && st.err > 0) {
                                BasicText(
                                    "${st.err}",
                                    style = ppmText(10f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 14f)
                                        .copy(color = tokens.error.color),
                                )
                            }
                        }
                    }
                }

                // Levels + Range + Search
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((lv, label) in listOf("error" to "ERR", "warn" to "WRN", "info" to "INF", "debug" to "DBG")) {
                        val on = filter.levels[lv] == true
                        val chipShape = RoundedCornerShape(5.dp)
                        Box(
                            Modifier
                                .height(26.dp)
                                .clip(chipShape)
                                .background(if (on) tokens.accent.opacity(0.15).color else Color.Transparent)
                                .cssBorder((if (on) tokens.accent else tokens.border).color, chipShape)
                                .ppmClickable(shape = chipShape) {
                                    onFilterChange(filter.copy(levels = filter.levels + (lv to !on)))
                                }
                                .padding(horizontal = 6.dp)
                                .testTag("logs-level-$lv"),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                label,
                                style = ppmText(10f, FontWeight.SemiBold, kind = FontKind.MONO, lineHeight = 14f)
                                    .copy(color = if (on) tokens.accent.color else tokens.text2.color),
                            )
                        }
                    }

                    // Range chips
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (r in LOG_RANGES) {
                            val active = range == r
                            Box(
                                Modifier
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (active) tokens.panel2.color else Color.Transparent)
                                    .ppmClickable { onRangeChange(r) }
                                    .padding(horizontal = 6.dp)
                                    .testTag("logs-range-$r"),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    r,
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f)
                                        .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                                )
                            }
                        }
                    }
                }

                // Search row
                val badRegex = searchCompiled is RegexResult.Invalid || result.badRegex
                val searchShape = RoundedCornerShape(6.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(searchShape)
                        .background(tokens.panel2.opacity(0.6).color)
                        .cssBorder((if (badRegex) tokens.error else tokens.border).color, searchShape)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PpmIcon(ProductIcon.Search, 13.dp, tokens.text2.color)
                    PpmTextField(
                        state = searchField,
                        placeholder = "Search logs…",
                        modifier = Modifier.weight(1f).testTag("logs-search-input"),
                        style = ppmText(12f, kind = FontKind.MONO, lineHeight = 16f),
                    )
                    // Case sensitive toggle
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (filter.caseSensitive) tokens.accent.opacity(0.2).color else Color.Transparent)
                            .ppmClickable { onFilterChange(filter.copy(caseSensitive = !filter.caseSensitive)) }
                            .testTag("logs-search-case"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(LucideIcon.CaseSensitive, 13.dp, if (filter.caseSensitive) tokens.accent.color else tokens.text2.color)
                    }
                    // Regex toggle
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (filter.regex) tokens.accent.opacity(0.2).color else Color.Transparent)
                            .ppmClickable { onFilterChange(filter.copy(regex = !filter.regex)) }
                            .testTag("logs-search-regex"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PpmIcon(LucideIcon.Regex, 13.dp, if (filter.regex) tokens.accent.color else tokens.text2.color)
                    }
                }
            }

            // Log rows
            if (loading && rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
                }
            } else if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    BasicText("No log lines match the current filter.", style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (result.hasMore) {
                        item(key = "load-earlier") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .edge(Edge.Bottom, tokens.border.opacity(0.5).color)
                                    .ppmClickable(onClick = onLoadEarlier)
                                    .padding(vertical = 8.dp)
                                    .testTag("logs-load-earlier"),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    "Load earlier lines…",
                                    style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accent.color),
                                )
                            }
                        }
                    }

                    items(rows, key = { it.key }) { row ->
                        val restartTs = dividers[row.key]
                        if (restartTs != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(tokens.accent.opacity(0.1).color)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .testTag("logs-restart-divider-${row.key}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PpmIcon(ProductIcon.RotateCw, 11.dp, tokens.accent.color)
                                BasicText(
                                    "PPM restarted at ${clockTime(restartTs, utc)}",
                                    style = ppmText(11f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 15f)
                                        .copy(color = tokens.accent.color),
                                )
                            }
                        }

                        val selected = row.key in selectedKeys
                        val moreExpanded = row.key in expandedMoreKeys
                        val lvBucket = levelBucket(row.entry.lv)
                        val lvColor = when (lvBucket) {
                            "error" -> tokens.error.color
                            "warn" -> Color(0xFFEAB308)
                            "debug" -> tokens.text2.opacity(0.7).color
                            else -> tokens.accent.color
                        }

                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selected) tokens.accent.opacity(0.12).color else Color.Transparent)
                                .edge(Edge.Bottom, tokens.border.opacity(0.28).color)
                                .ppmClickable {
                                    selectedKeys = if (selected) selectedKeys - row.key else selectedKeys + row.key
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("log-row-${row.key}"),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BasicText(
                                    clockTime(row.entry.ts, utc),
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                )
                                BasicText(
                                    row.entry.lv.take(3).uppercase(),
                                    style = ppmText(10f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 14f).copy(color = lvColor),
                                )
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(tokens.panel2.color)
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                ) {
                                    BasicText(
                                        row.entry.tag,
                                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.text2.color),
                                    )
                                }
                                BasicText(
                                    row.entry.msg,
                                    Modifier.weight(1f),
                                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                                    maxLines = if (moreExpanded) 12 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.count > 1) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(tokens.accent.opacity(0.18).color)
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                            .testTag("log-repeat-${row.key}"),
                                    ) {
                                        BasicText(
                                            "×${row.count}",
                                            style = ppmText(10f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 13f)
                                                .copy(color = tokens.accent.color),
                                        )
                                    }
                                }
                            }

                            if (row.entry.more.isNotEmpty()) {
                                BasicText(
                                    if (moreExpanded) "Hide ${row.entry.more.size} continuation lines"
                                    else "+${row.entry.more.size} lines",
                                    Modifier
                                        .ppmClickable {
                                            expandedMoreKeys = if (moreExpanded) expandedMoreKeys - row.key else expandedMoreKeys + row.key
                                        }
                                        .testTag("log-more-toggle-${row.key}"),
                                    style = ppmText(10f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 14f)
                                        .copy(color = tokens.accent.color),
                                )
                                if (moreExpanded) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(tokens.panel2.opacity(0.5).color)
                                            .padding(6.dp),
                                    ) {
                                        for (line in row.entry.more) {
                                            BasicText(
                                                line,
                                                style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text2.color),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Selection action bar
        if (selectedKeys.isNotEmpty()) {
            val selectedRows = rows.filter { it.key in selectedKeys }
            val barShape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .clip(barShape)
                    .background(tokens.panel2.over(tokens.bgSolid).color)
                    .cssBorder(tokens.border.color, barShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("logs-selection-bar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BasicText(
                    "${selectedRows.size} selected",
                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.accent.color)
                        .ppmClickable {
                            onAddToReport(selectedRows)
                            selectedKeys = emptySet()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("logs-selection-add-report"),
                ) {
                    BasicText("Add to report", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accentFg.color))
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.bgSolid.color)
                        .cssBorder(tokens.border.color, RoundedCornerShape(6.dp))
                        .ppmClickable {
                            val raw = selectedRows.joinToString("\n") { rawLogLine(it.entry, it.count) }
                            onCopyLines(raw)
                            selectedKeys = emptySet()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("logs-selection-copy"),
                ) {
                    BasicText("Copy", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text.color))
                }
                Box(
                    Modifier.size(22.dp).ppmClickable { selectedKeys = emptySet() },
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.X, 13.dp, tokens.text2.color)
                }
            }
        }
    }
}

// ── 2. Logs Issues Pane ────────────────────────────────────────────────

@Composable
private fun LogsIssuesPane(
    issuesResult: LogIssuesResult,
    loading: Boolean,
    onAnalyze: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onRestoreDismissed: () -> Unit,
    onDismissIssue: (String, Boolean) -> Unit,
    onShowInLogs: (LogIssue) -> Unit,
    onDraftReport: (LogIssue) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var clsFilter by remember { mutableStateOf("all") }

    val activeIssues = remember(issuesResult.issues, clsFilter) {
        issuesResult.issues.filter { !it.dismissed && (clsFilter == "all" || it.cls == clsFilter) }
    }
    val dismissedCount = remember(issuesResult.issues) { issuesResult.issues.count { it.dismissed } }

    Column(Modifier.fillMaxSize().testTag("logs-issues-pane")) {
        // AI Analyzer Header
        Column(
            Modifier
                .fillMaxWidth()
                .edge(Edge.Bottom, tokens.border.color)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "AI Log Triage (${issuesResult.model})",
                        style = ppmText(13f, FontWeight.SemiBold, lineHeight = 18f).copy(color = tokens.text.color),
                    )
                    BasicText(
                        if (issuesResult.running) "Analyzing recent errors & warnings…"
                        else if (issuesResult.unsorted > 0) "${issuesResult.unsorted} unsorted events ready to analyze"
                        else "Grouped by root cause",
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val autoShape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .height(28.dp)
                            .clip(autoShape)
                            .background(if (issuesResult.auto) tokens.accent.opacity(0.16).color else tokens.panel2.color)
                            .cssBorder((if (issuesResult.auto) tokens.accent else tokens.border).color, autoShape)
                            .ppmClickable(shape = autoShape) { onToggleAuto(!issuesResult.auto) }
                            .padding(horizontal = 8.dp)
                            .testTag("issues-auto-toggle"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            if (issuesResult.auto) "Auto: On" else "Auto: Off",
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                .copy(color = if (issuesResult.auto) tokens.accent.color else tokens.text2.color),
                        )
                    }
                    val btnShape = RoundedCornerShape(6.dp)
                    Row(
                        Modifier
                            .height(28.dp)
                            .clip(btnShape)
                            .background(tokens.accent.color)
                            .ppmClickable(enabled = !issuesResult.running, shape = btnShape, onClick = onAnalyze)
                            .padding(horizontal = 10.dp)
                            .testTag("issues-analyze-btn"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PpmIcon(
                            if (issuesResult.running) ProductIcon.Loader2 else ProductIcon.Sparkles,
                            13.dp,
                            tokens.accentFg.color,
                            spinning = issuesResult.running,
                        )
                        BasicText(
                            if (issuesResult.analyzedAt != null) "Re-analyze" else "Analyze",
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accentFg.color),
                        )
                    }
                }
            }

            // Class filter chips
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val classes = listOf(
                    "all" to "All",
                    "bug" to "Likely PPM bug",
                    "setup" to "Your setup",
                    "upstream" to "Upstream",
                    "expected" to "Expected",
                )
                for ((cId, label) in classes) {
                    val active = clsFilter == cId
                    val chipShape = RoundedCornerShape(6.dp)
                    Box(
                        Modifier
                            .clip(chipShape)
                            .background(if (active) tokens.accent.opacity(0.15).color else tokens.panel2.opacity(0.55).color)
                            .ppmClickable(shape = chipShape) { clsFilter = cId }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("issues-filter-$cId"),
                    ) {
                        BasicText(
                            label,
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                        )
                    }
                }
                if (dismissedCount > 0) {
                    BasicText(
                        "Restore $dismissedCount dismissed",
                        Modifier
                            .ppmClickable(onClick = onRestoreDismissed)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        style = ppmText(11f, lineHeight = 15f).copy(color = tokens.accent.color),
                    )
                }
            }
        }

        if (loading && activeIssues.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 18.dp, tokens.text2.color, spinning = true)
            }
        } else if (activeIssues.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                BasicText(
                    "No issues found in this category.",
                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(activeIssues, key = { it.id }) { issue ->
                    val cardShape = RoundedCornerShape(8.dp)
                    val clsColor = when (issue.cls) {
                        "bug" -> tokens.error.color
                        "setup" -> Color(0xFFEAB308)
                        "upstream" -> Color(0xFF3B82F6)
                        else -> tokens.text2.color
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(tokens.panel2.opacity(0.4).color)
                            .cssBorder(tokens.border.color, cardShape)
                            .padding(12.dp)
                            .testTag("issue-card-${issue.id}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(clsColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    BasicText(
                                        issue.cls.uppercase(),
                                        style = ppmText(10f, FontWeight.Bold, lineHeight = 13f).copy(color = clsColor),
                                    )
                                }
                                BasicText(
                                    issue.area.ifEmpty { issue.src },
                                    style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                                )
                            }
                            BasicText(
                                "${issue.count}×",
                                style = ppmText(11f, FontWeight.Bold, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text2.color),
                            )
                        }

                        BasicText(
                            issue.title,
                            style = ppmText(14f, FontWeight.SemiBold, lineHeight = 19f).copy(color = tokens.text.color),
                        )
                        BasicText(
                            issue.why,
                            style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color),
                        )

                        if (!issue.fix.isNullOrBlank()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tokens.bgSolid.color)
                                    .padding(8.dp),
                            ) {
                                BasicText(
                                    "Suggested fix: ${issue.fix}",
                                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text.color),
                                )
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val btnShape = RoundedCornerShape(6.dp)
                            Box(
                                Modifier
                                    .clip(btnShape)
                                    .background(tokens.accent.color)
                                    .ppmClickable(shape = btnShape) { onDraftReport(issue) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("issue-draft-report-${issue.id}"),
                            ) {
                                BasicText("Draft report", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accentFg.color))
                            }
                            Box(
                                Modifier
                                    .clip(btnShape)
                                    .background(tokens.bgSolid.color)
                                    .cssBorder(tokens.border.color, btnShape)
                                    .ppmClickable(shape = btnShape) { onShowInLogs(issue) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                BasicText("Show in Logs", style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.text.color))
                            }
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier
                                    .clip(btnShape)
                                    .ppmClickable(shape = btnShape) { onDismissIssue(issue.id, true) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                    .testTag("issue-dismiss-${issue.id}"),
                            ) {
                                BasicText("Dismiss", style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 3. Logs Report Pane ────────────────────────────────────────────────

@Composable
private fun LogsReportPane(
    model: AppModel,
    titleField: TextFieldState,
    whatField: TextFieldState,
    stepsField: TextFieldState,
    expectedField: TextFieldState,
    labels: List<String>,
    onToggleLabel: (String) -> Unit,
    snippets: List<ReportSnippet>,
    onRemoveSnippet: (String) -> Unit,
    redact: RedactOptions,
    onRedactChange: (RedactOptions) -> Unit,
    environment: List<Pair<String, String>>,
    draftingAi: Boolean,
    onDraftWithAi: () -> Unit,
    onCopyMarkdown: (String) -> Unit,
    onOpenGithub: (String) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    var mode by remember { mutableStateOf("write") } // "write" | "preview"
    var copied by remember { mutableStateOf(false) }
    var dupes by remember { mutableStateOf<DuplicateSearchResult?>(null) }

    val fields = ReportFields(
        title = titleField.text.toString(),
        labels = labels,
        what = whatField.text.toString(),
        steps = stepsField.text.toString(),
        expected = expectedField.text.toString(),
    )
    val prepared = remember(fields, snippets, environment, redact) {
        prepareIssueReport(fields, snippets, environment, redact)
    }
    val compiledBody = prepared.body
    val ghUrl = prepared.openUrl

    LaunchedEffect(prepared.duplicateQuery, model.api) {
        dupes = null
        val dq = prepared.duplicateQuery ?: return@LaunchedEffect
        val api = model.api ?: return@LaunchedEffect
        delay(700L)
        try {
            val res = api.logsGithubDuplicates(dq)
            dupes = DuplicateSearchResult.from(res, dq)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("logs-report-pane"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Mode & Draft with AI bar
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tokens.panel2.color)
                    .padding(2.dp),
            ) {
                for ((mId, label) in listOf("write" to "Write", "preview" to "Preview")) {
                    val active = mode == mId
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) tokens.accent.color else Color.Transparent)
                            .ppmClickable { mode = mId }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("report-mode-$mId"),
                    ) {
                        BasicText(
                            label,
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 15f)
                                .copy(color = if (active) tokens.accentFg.color else tokens.text2.color),
                        )
                    }
                }
            }

            val aiBtnShape = RoundedCornerShape(6.dp)
            Row(
                Modifier
                    .height(28.dp)
                    .clip(aiBtnShape)
                    .background(tokens.accent.opacity(0.15).color)
                    .cssBorder(tokens.accent.color, aiBtnShape)
                    .ppmClickable(enabled = !draftingAi, shape = aiBtnShape, onClick = onDraftWithAi)
                    .padding(horizontal = 10.dp)
                    .testTag("report-draft-ai-btn"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                PpmIcon(
                    if (draftingAi) ProductIcon.Loader2 else ProductIcon.Sparkles,
                    13.dp,
                    tokens.accent.color,
                    spinning = draftingAi,
                )
                BasicText(
                    if (draftingAi) "Drafting…" else "Draft with AI",
                    style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accent.color),
                )
            }
        }

        if (mode == "preview") {
            val previewShape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(previewShape)
                    .background(tokens.panel2.opacity(0.45).color)
                    .cssBorder(tokens.border.color, previewShape)
                    .padding(12.dp)
                    .testTag("report-preview-box"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "# ${prepared.redactedTitle.ifBlank { "Bug report" }}",
                    style = ppmText(15f, FontWeight.Bold, lineHeight = 20f).copy(color = tokens.text.color),
                )
                BasicText(
                    compiledBody,
                    style = ppmText(12f, kind = FontKind.MONO, lineHeight = 17f).copy(color = tokens.text.color),
                )
            }
        } else {
            // Title
            ReportFieldBox(label = "Title", state = titleField, placeholder = "Short summary of the bug", singleLine = true, tag = "report-title-input")

            // Labels
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText("Labels:", style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color))
                for (lbl in listOf("bug", "logs", "android", "terminal", "ai", "git")) {
                    val active = lbl in labels
                    val chipShape = RoundedCornerShape(5.dp)
                    Box(
                        Modifier
                            .clip(chipShape)
                            .background(if (active) tokens.accent.opacity(0.16).color else tokens.panel2.color)
                            .cssBorder((if (active) tokens.accent else tokens.border).color, chipShape)
                            .ppmClickable(shape = chipShape) { onToggleLabel(lbl) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        BasicText(
                            lbl,
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f)
                                .copy(color = if (active) tokens.accent.color else tokens.text2.color),
                        )
                    }
                }
            }

            ReportFieldBox(label = "What happened", state = whatField, placeholder = "Describe what went wrong…", singleLine = false, tag = "report-what-input")
            ReportFieldBox(label = "Steps to reproduce", state = stepsField, placeholder = "1. Open …\n2. Tap …", singleLine = false, tag = "report-steps-input")
            ReportFieldBox(label = "Expected behavior", state = expectedField, placeholder = "What should have happened instead", singleLine = false, tag = "report-expected-input")

            // Snippets
            if (snippets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("Attached log snippets (${snippets.size})", style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color))
                    for (s in snippets) {
                        val sShape = RoundedCornerShape(6.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(sShape)
                                .background(tokens.panel2.opacity(0.45).color)
                                .cssBorder(tokens.border.color, sShape)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BasicText(
                                "${snippetHeading(s)} · ${plural(snippetCount(s), "line")}",
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
                            )
                            Box(
                                Modifier.size(20.dp).ppmClickable { onRemoveSnippet(s.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                PpmIcon(ProductIcon.X, 12.dp, tokens.text2.color)
                            }
                        }
                    }
                }
            }

            // Privacy Redaction switches
            val privShape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(privShape)
                    .background(tokens.panel2.opacity(0.35).color)
                    .cssBorder(tokens.border.color, privShape)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    "Privacy & Redaction (secrets are always stripped)",
                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.text.color),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrivacyChip("Home paths (~)", redact.home) { onRedactChange(redact.copy(home = !redact.home)) }
                    PrivacyChip("Emails", redact.email) { onRedactChange(redact.copy(email = !redact.email)) }
                    PrivacyChip("Chat UUIDs", redact.chats) { onRedactChange(redact.copy(chats = !redact.chats)) }
                }
            }
        }

        // Submit / Copy actions
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val btnShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(btnShape)
                    .background(tokens.accent.color)
                    .ppmClickable(shape = btnShape) {
                        if (prepared.bodyOverLimit) {
                            onCopyMarkdown(compiledBody)
                            model.toast(ErrorToastMessage("Body copied. Paste it into the issue that just opened.", kind = ErrorToastMessage.Kind.SUCCESS))
                        } else {
                            model.toast(ErrorToastMessage("Opened the new issue on GitHub. Check it there, then press Submit.", kind = ErrorToastMessage.Kind.SUCCESS))
                        }
                        onOpenGithub(ghUrl)
                    }
                    .testTag("report-open-github-btn"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                PpmIcon(ProductIcon.ExternalLink, 14.dp, tokens.accentFg.color)
                Spacer(Modifier.width(6.dp))
                BasicText(
                    if (prepared.bodyOverLimit) "Open issue (copies body)" else "Open GitHub issue",
                    style = ppmText(12f, FontWeight.SemiBold, lineHeight = 16f).copy(color = tokens.accentFg.color),
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(btnShape)
                    .background(tokens.panel2.color)
                    .cssBorder(tokens.border.color, btnShape)
                    .ppmClickable(shape = btnShape) {
                        onCopyMarkdown(reportMarkdown(prepared.redactedTitle, compiledBody))
                        copied = true
                    }
                    .testTag("report-copy-md-btn"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                PpmIcon(if (copied) ProductIcon.Check else ProductIcon.Copy, 14.dp, tokens.text.color)
                Spacer(Modifier.width(6.dp))
                BasicText(
                    if (copied) "Copied Markdown" else "Copy as Markdown",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
            }
        }

        val currentDupes = dupes
        if (currentDupes != null) {
            val hasIssues = currentDupes.issues.isNotEmpty()
            val dupeShape = RoundedCornerShape(8.dp)
            val warnColor = Color(0xFFEAB308)
            val okColor = Color(0xFF22C55E)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(dupeShape)
                    .background(if (hasIssues) warnColor.copy(alpha = 0.12f) else tokens.panel2.opacity(0.35).color)
                    .cssBorder(if (hasIssues) warnColor.copy(alpha = 0.35f) else tokens.border.color, dupeShape)
                    .padding(10.dp)
                    .testTag("report-duplicates-box"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PpmIcon(
                    if (hasIssues) ProductIcon.TriangleAlert else ProductIcon.CheckCircle2,
                    14.dp,
                    if (hasIssues) warnColor else okColor,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasIssues) {
                        BasicText(
                            "Open issues that look like this one:",
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text.color),
                        )
                        for (issue in currentDupes.issues.take(3)) {
                            BasicText(
                                "#${issue.number} ${issue.title}",
                                modifier = Modifier
                                    .ppmClickable { onOpenGithub(issue.url) }
                                    .testTag("report-dupe-issue-${issue.number}"),
                                style = ppmText(11f, FontWeight.Medium, lineHeight = 15f).copy(color = tokens.accent.color),
                            )
                        }
                    } else {
                        BasicText(
                            "No open issue looks like this one. Searched $LOGS_ISSUE_REPO for \u201C${currentDupes.query}\u201D.",
                            style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text2.color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportFieldBox(
    label: String,
    state: TextFieldState,
    placeholder: String,
    singleLine: Boolean,
    tag: String,
) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 34.dp else 72.dp)
                .clip(shape)
                .background(tokens.panel2.opacity(0.55).color)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            PpmTextField(
                state = state,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth().testTag(tag),
                style = ppmText(12f, lineHeight = 17f),
                lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
            )
        }
    }
}

@Composable
private fun PrivacyChip(label: String, checked: Boolean, onClick: () -> Unit) {
    val tokens = LocalPpmTokens.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (checked) tokens.accent.opacity(0.15).color else tokens.bgSolid.color)
            .cssBorder((if (checked) tokens.accent else tokens.border).color, shape)
            .ppmClickable(shape = shape, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (checked) PpmIcon(ProductIcon.Check, 11.dp, tokens.accent.color)
        BasicText(
            label,
            style = ppmText(11f, lineHeight = 15f)
                .copy(color = if (checked) tokens.accent.color else tokens.text2.color),
        )
    }
}
