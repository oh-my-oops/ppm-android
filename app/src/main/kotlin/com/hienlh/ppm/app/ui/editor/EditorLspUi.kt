package com.hienlh.ppm.app.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.client.ApiClient
import com.hienlh.ppm.client.SocketConnection
import com.hienlh.ppm.client.lspProjectInstall
import com.hienlh.ppm.client.openLspConnection
import com.hienlh.ppm.design.CssSides
import com.hienlh.ppm.design.FileIcon
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.design.vector
import com.hienlh.ppm.state.editor.FileProblems
import com.hienlh.ppm.state.editor.LspClientSessionState
import com.hienlh.ppm.state.editor.LspCompletionEntry
import com.hienlh.ppm.state.editor.LspDiagnostic
import com.hienlh.ppm.state.editor.LspDocumentStatus
import com.hienlh.ppm.state.editor.LspIncomingEvent
import com.hienlh.ppm.state.editor.LspLocationTarget
import com.hienlh.ppm.state.editor.LspMissingServer
import com.hienlh.ppm.state.editor.LspTextEdit
import com.hienlh.ppm.state.editor.ProblemTotals
import com.hienlh.ppm.state.editor.ProblemsStoreState
import com.hienlh.ppm.state.editor.SEVERITY_ERROR
import com.hienlh.ppm.state.editor.SEVERITY_INFO
import com.hienlh.ppm.state.editor.SEVERITY_WARNING
import com.hienlh.ppm.state.editor.lspCannotInstallNote
import com.hienlh.ppm.state.editor.lspInstallNote
import com.hienlh.ppm.state.editor.parseLspCompletions
import com.hienlh.ppm.state.editor.parseLspLocations
import com.hienlh.ppm.state.editor.parseLspMarkup
import com.hienlh.ppm.state.editor.parseLspTextEdits
import com.hienlh.ppm.state.editor.problemKey
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ── Shared Problems Store Registry ─────────────────────────────────────────

object SharedProblemsRegistry {
    private val store = ProblemsStoreState()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Synchronized
    fun publish(projectName: String, filePath: String, diagnostics: List<LspDiagnostic>) {
        store.publish(projectName, filePath, diagnostics)
        _revision.value += 1
    }

    @Synchronized
    fun clear(projectName: String, filePath: String) {
        store.clear(projectName, filePath)
        _revision.value += 1
    }

    @Synchronized
    fun problemCounts(projectName: String?): ProblemTotals =
        store.problemCounts(projectName)

    @Synchronized
    fun sortedProblems(projectName: String?, query: String = ""): List<FileProblems> =
        store.sortedProblems(projectName, query)

    @Synchronized
    fun hasAnyFiles(projectName: String?): Boolean =
        store.filesForProject(projectName).isNotEmpty()

    @Synchronized
    fun resetForTesting() {
        for (file in store.filesForProject(null).values.toList()) {
            store.clear(file.projectName, file.filePath)
        }
        _revision.value += 1
    }
}

// ── Per-Device LSP Toggle State ────────────────────────────────────────────

object DeviceLspPreference {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (!value) {
            SharedProjectLspRegistry.disconnectAll()
        }
    }
}

// ── Shared Per-Project LSP WebSocket Registry ──────────────────────────────

object SharedProjectLspRegistry {
    private val bridges = ConcurrentHashMap<Pair<Int, String>, ProjectLspBridge>()

    @Synchronized
    fun getOrCreate(
        api: ApiClient,
        projectName: String,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ): ProjectLspBridge {
        val key = System.identityHashCode(api) to projectName
        return bridges.getOrPut(key) { ProjectLspBridge(api, projectName, scope) }
    }

    @Synchronized
    fun disconnectProject(api: ApiClient, projectName: String) {
        val key = System.identityHashCode(api) to projectName
        bridges.remove(key)?.disconnect()
    }

    @Synchronized
    fun disconnectAll() {
        val all = bridges.values.toList()
        bridges.clear()
        for (bridge in all) {
            bridge.disconnect()
        }
    }

    @Synchronized
    fun activeBridgeCount(): Int = bridges.size
}

// ── Project LSP WebSocket Bridge ───────────────────────────────────────────

private val nextModelId = AtomicInteger(1)

fun allocateClientUri(): String = "inmemory://model/${nextModelId.getAndIncrement()}"

class ProjectLspBridge(
    private val api: ApiClient,
    val projectName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    val session = LspClientSessionState(projectName)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val statusFlows = ConcurrentHashMap<String, MutableStateFlow<LspDocumentStatus?>>()
    private val diagFlows = ConcurrentHashMap<String, MutableStateFlow<List<LspDiagnostic>>>()

    @Volatile
    private var outboundSender: ((String) -> Boolean)? = null

    @Volatile
    private var activeSocket: SocketConnection? = null
    private var connectionJob: Job? = null

    val isConnectedOrConnecting: Boolean
        get() = connectionJob?.isActive == true

    fun statusFlow(path: String): StateFlow<LspDocumentStatus?> =
        statusFlows.getOrPut(path) { MutableStateFlow(session.statusOf(path)) }.asStateFlow()

    fun diagnosticsFlow(path: String): StateFlow<List<LspDiagnostic>> =
        diagFlows.getOrPut(path) { MutableStateFlow(session.diagnosticsOf(path)) }.asStateFlow()

    fun ensureConnected() {
        if (!DeviceLspPreference.enabled.value) return
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            var backoffMs = 1000L
            while (isActive && DeviceLspPreference.enabled.value) {
                var socket: SocketConnection? = null
                try {
                    socket = api.openLspConnection(projectName)
                    activeSocket = socket
                    outboundSender = { frame -> socket.send(frame) }
                    for (event in socket.events) {
                        if (!DeviceLspPreference.enabled.value) break
                        when (event) {
                            is SocketConnection.Event.Opened -> {
                                backoffMs = 1000L
                            }
                            is SocketConnection.Event.Text -> {
                                handleIncomingFrame(event.text)
                            }
                            is SocketConnection.Event.Closed -> {
                                break
                            }
                            is SocketConnection.Event.Binary -> {}
                        }
                    }
                } catch (_: Throwable) {
                    // Reconnect after backoff
                } finally {
                    outboundSender = null
                    activeSocket = null
                    socket?.close()
                }
                if (!DeviceLspPreference.enabled.value) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        outboundSender = null
        val sock = activeSocket
        activeSocket = null
        sock?.close()
        val ids = pendingRequests.keys.toList()
        for (id in ids) {
            pendingRequests.remove(id)?.complete(null)
        }
    }

    fun openDocument(path: String, clientUri: String, text: String) {
        ensureConnected()
        val payload = session.openDocument(path, clientUri, 1, text)
        statusFlows.getOrPut(path) { MutableStateFlow(null) }.value = LspDocumentStatus.Opening
        outboundSender?.invoke(payload)
    }

    fun changeDocument(path: String, text: String) {
        val payload = session.changeDocumentFull(path, text) ?: return
        outboundSender?.invoke(payload)
    }

    fun saveDocument(path: String, text: String) {
        val payload = session.saveDocument(path, text) ?: return
        outboundSender?.invoke(payload)
    }

    fun closeDocument(path: String) {
        val payload = session.closeDocument(path)
        if (payload != null) outboundSender?.invoke(payload)
        statusFlows[path]?.value = null
        diagFlows[path]?.value = emptyList()
        SharedProblemsRegistry.clear(projectName, path)
    }

    suspend fun request(path: String, method: String, params: JsonObject, timeoutMs: Long = 15_000L): JsonElement? {
        val (id, payload) = session.createRequest(path, method, params)
        val deferred = CompletableDeferred<JsonElement?>()
        pendingRequests[id] = deferred
        val sent = outboundSender?.invoke(payload) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            return null
        }
        val result = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        if (pendingRequests.remove(id) != null && !deferred.isCompleted) {
            outboundSender?.invoke(session.createCancel(id))
        }
        return result
    }

    suspend fun requestCompletions(path: String, clientUri: String, line: Int, character: Int): List<LspCompletionEntry> {
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", clientUri) })
            put("position", buildJsonObject {
                put("line", line)
                put("character", character)
            })
        }
        val res = request(path, "textDocument/completion", params)
        return parseLspCompletions(res)
    }

    suspend fun requestHover(path: String, clientUri: String, line: Int, character: Int): String? {
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", clientUri) })
            put("position", buildJsonObject {
                put("line", line)
                put("character", character)
            })
        }
        val res = request(path, "textDocument/hover", params) as? JsonObject ?: return null
        return parseLspMarkup(res["contents"])
    }

    suspend fun requestDefinition(path: String, clientUri: String, line: Int, character: Int): List<LspLocationTarget> {
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", clientUri) })
            put("position", buildJsonObject {
                put("line", line)
                put("character", character)
            })
        }
        val res = request(path, "textDocument/definition", params)
        return parseLspLocations(res)
    }

    suspend fun requestFormatting(path: String, clientUri: String, tabSize: Int, insertSpaces: Boolean): List<LspTextEdit> {
        val params = buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", clientUri) })
            put("options", buildJsonObject {
                put("tabSize", tabSize)
                put("insertSpaces", insertSpaces)
            })
        }
        val res = request(path, "textDocument/formatting", params)
        return parseLspTextEdits(res)
    }

    suspend fun installServer(serverId: String) {
        try {
            api.lspProjectInstall(projectName, serverId)
        } finally {
            val retries = session.retryUnavailableDocuments()
            for (doc in retries) {
                outboundSender?.invoke(doc)
            }
        }
    }

    internal fun handleIncomingFrame(rawJson: String) {
        when (val evt = session.onServerMessage(rawJson)) {
            is LspIncomingEvent.ReopenAll -> {
                for (payload in evt.outboundPayloads) {
                    outboundSender?.invoke(payload)
                }
            }
            is LspIncomingEvent.StatusChanged -> {
                statusFlows.getOrPut(evt.path) { MutableStateFlow(null) }.value = evt.status
            }
            is LspIncomingEvent.ResyncRequested -> {
                val payload = evt.outboundPayload
                if (payload != null) {
                    outboundSender?.invoke(payload)
                }
            }
            is LspIncomingEvent.DiagnosticsPublished -> {
                diagFlows.getOrPut(evt.path) { MutableStateFlow(emptyList()) }.value = evt.diagnostics
                SharedProblemsRegistry.publish(projectName, evt.path, evt.diagnostics)
            }
            is LspIncomingEvent.ResponseReceived -> {
                pendingRequests.remove(evt.id)?.complete(evt.result)
            }
            is LspIncomingEvent.ErrorReceived -> {
                pendingRequests.remove(evt.id)?.complete(null)
            }
            is LspIncomingEvent.NoticeReceived, null -> {}
        }
    }
}

// ── LSP Status Chip & Dialog/Sheet (Port of `lsp-status.tsx`) ──────────────

@Composable
fun LspStatusChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    status: LspDocumentStatus?,
    diagnostics: List<LspDiagnostic>,
    onInstall: (suspend (String) -> Unit)? = null,
    onOpenProblems: (() -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var dialogOpen by remember { mutableStateOf(false) }

    if (!enabled) {
        Row(
            modifier = Modifier
                .heightIn(min = if (mobile) 44.dp else 22.dp)
                .clip(RoundedCornerShape(4.dp))
                .ppmClickable(
                    pressedColor = tokens.panel.color,
                    hoverColor = tokens.panel.color,
                    onClickLabel = "Turn on LSP",
                    onClick = { onToggle(true) },
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("editor-lsp-chip-off"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PpmIcon(ProductIcon.Code.vector, 12.dp, tokens.text3.color)
            BasicText("LSP off", style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color))
        }
        return
    }

    if (status == null) return

    val errors = diagnostics.count { it.effectiveSeverity == SEVERITY_ERROR }
    val warnings = diagnostics.count { it.effectiveSeverity == SEVERITY_WARNING }

    val label = when (status) {
        is LspDocumentStatus.Ready -> status.server.displayName
        is LspDocumentStatus.Opening -> "Starting…"
        is LspDocumentStatus.Unavailable -> status.server?.displayName?.ifBlank { "No server" } ?: "No server"
    }
    val missing = status is LspDocumentStatus.Unavailable && status.reason == "not-installed"
    val tint = if (missing) tokens.warning.color else tokens.text2.color

    Row(
        modifier = Modifier
            .heightIn(min = if (mobile) 44.dp else 22.dp)
            .clip(RoundedCornerShape(4.dp))
            .ppmClickable(
                pressedColor = tokens.panel.color,
                hoverColor = tokens.panel.color,
                onClickLabel = "Language server details",
                onClick = { dialogOpen = true },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("editor-lsp-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            status is LspDocumentStatus.Opening -> PpmIcon(ProductIcon.RefreshCw.vector, 12.dp, tint, spinning = true)
            missing -> PpmIcon(ProductIcon.AlertTriangle.vector, 12.dp, tint)
            else -> PpmIcon(ProductIcon.Sparkles.vector, 12.dp, tint)
        }
        BasicText(
            label,
            modifier = Modifier.widthIn(max = 96.dp),
            style = ppmText(11f, lineHeight = 14f).copy(color = tint),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (errors > 0) {
            BasicText(
                errors.toString(),
                modifier = Modifier.testTag("editor-lsp-chip-errors"),
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.error.color),
            )
        } else if (warnings > 0) {
            BasicText(
                warnings.toString(),
                modifier = Modifier.testTag("editor-lsp-chip-warnings"),
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.warning.color),
            )
        }
    }

    if (dialogOpen) {
        Dialog(onDismissRequest = { dialogOpen = false }) {
            LspStatusDetailsContent(
                status = status,
                errors = errors,
                warnings = warnings,
                onToggle = { next ->
                    onToggle(next)
                    if (!next) dialogOpen = false
                },
                onInstall = onInstall,
                onOpenProblems = if (onOpenProblems != null) {
                    {
                        dialogOpen = false
                        onOpenProblems()
                    }
                } else null,
                onClose = { dialogOpen = false },
            )
        }
    }
}

@Composable
internal fun LspStatusDetailsContent(
    status: LspDocumentStatus,
    errors: Int,
    warnings: Int,
    onToggle: (Boolean) -> Unit,
    onInstall: (suspend (String) -> Unit)? = null,
    onOpenProblems: (() -> Unit)? = null,
    onClose: () -> Unit = {},
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color)
            .padding(16.dp)
            .testTag("lsp-status-details"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Language server",
                style = ppmText(14f, FontWeight.SemiBold, lineHeight = 20f).copy(color = tokens.text.color),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onClose() }
                    .padding(4.dp)
                    .testTag("lsp-details-close"),
            ) {
                PpmIcon(ProductIcon.X.vector, 14.dp, tokens.text3.color)
            }
        }

        when (status) {
            is LspDocumentStatus.Ready -> {
                LspDetailRow("Server", status.server.displayName)
                LspDetailRow("Language", status.languageId)
                LspDetailRow("Project root", status.server.rootPath, mono = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { mod ->
                            if (onOpenProblems != null) {
                                mod
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { onOpenProblems() }
                                    .testTag("lsp-open-problems-btn")
                            } else {
                                mod
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        "Problems",
                        modifier = Modifier.width(92.dp),
                        style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                    BasicText(
                        "$errors error${if (errors == 1) "" else "s"}, $warnings warning${if (warnings == 1) "" else "s"}",
                        modifier = Modifier.weight(1f),
                        style = ppmText(12f, lineHeight = 16f).copy(
                            color = if (onOpenProblems != null) tokens.accent.color else tokens.text.color,
                        ),
                    )
                    if (onOpenProblems != null) {
                        BasicText(
                            "Open Problems",
                            style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.accent.color),
                        )
                    }
                }
                BasicText(
                    "Completions, hover, go to definition (F12), find references (Shift+F12), rename (F2), quick fix (Ctrl+.) and format (Shift+Alt+F) all come from this server.",
                    style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                )
            }
            is LspDocumentStatus.Opening -> {
                BasicText(
                    "Starting the language server. A cold start reads the project's dependencies, so the first completion in a large project can take a few seconds.",
                    style = ppmText(12f, lineHeight = 18f).copy(color = tokens.text2.color),
                )
            }
            is LspDocumentStatus.Unavailable -> {
                BasicText(
                    status.message,
                    style = ppmText(12f, lineHeight = 18f).copy(color = tokens.text.color),
                )
                val srv: LspMissingServer? = status.server
                if (srv != null) {
                    if (status.reason == "not-installed" && srv.installable && onInstall != null) {
                        BasicText(
                            lspInstallNote(srv.installWith),
                            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (mobile) 44.dp else 34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (installing) tokens.bgSolid.color else tokens.accent.color)
                                .clickable(enabled = !installing) {
                                    installing = true
                                    installError = null
                                    scope.launch {
                                        try {
                                            onInstall(srv.id)
                                        } catch (e: Throwable) {
                                            installError = e.message ?: "Failed to install ${srv.displayName}"
                                        } finally {
                                            installing = false
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("lsp-install-btn"),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                if (installing) "Installing ${srv.displayName}…" else "Install ${srv.displayName}",
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = Color.White),
                            )
                        }
                        if (installError != null) {
                            BasicText(
                                installError!!,
                                modifier = Modifier.testTag("lsp-install-error"),
                                style = ppmText(11f, lineHeight = 15f).copy(color = tokens.error.color),
                            )
                        }
                        BasicText(
                            "Or run it yourself:",
                            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    } else {
                        BasicText(
                            lspCannotInstallNote(srv.installWith),
                            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                        )
                    }

                    if (srv.installHint.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (mobile) 44.dp else 34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(tokens.bgSolid.color)
                                .cssBorder(tokens.border.color)
                                .clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("LSP install command", srv.installHint))
                                    copied = true
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("lsp-copy-cmd-btn"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                srv.installHint,
                                modifier = Modifier.weight(1f),
                                style = ppmText(11f, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                            )
                            Spacer(Modifier.width(8.dp))
                            PpmIcon(
                                if (copied) ProductIcon.Check.vector else ProductIcon.Copy.vector,
                                14.dp,
                                if (copied) tokens.success.color else tokens.text3.color,
                            )
                        }
                    }
                }
                if (status.reason == "no-language") {
                    BasicText(
                        "No language server is registered for this file type.",
                        style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                }
            }
        }

        // Per-device toggle row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Top)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    "Language server",
                    style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                )
                BasicText(
                    "On for this device. Turning it off stops the process.",
                    style = ppmText(11f, lineHeight = 15f).copy(color = tokens.text3.color),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.bgSolid.color)
                    .cssBorder(tokens.border.color)
                    .clickable { onToggle(false) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("lsp-device-toggle"),
            ) {
                BasicText("Turn Off", style = ppmText(11f, FontWeight.Medium, lineHeight = 14f).copy(color = tokens.text2.color))
            }
        }
    }
}

@Composable
private fun LspDetailRow(label: String, value: String, mono: Boolean = false) {
    val tokens = LocalPpmTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            modifier = Modifier.width(92.dp),
            style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color),
        )
        BasicText(
            value,
            modifier = Modifier.weight(1f),
            style = (if (mono) ppmText(11f, kind = FontKind.MONO, lineHeight = 16f) else ppmText(12f, lineHeight = 16f))
                .copy(color = tokens.text.color),
        )
    }
}

// ── Completion & Hover Popups in Editor ────────────────────────────────────

@Composable
fun LspCompletionPopup(
    items: List<LspCompletionEntry>,
    onSelect: (LspCompletionEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .testTag("lsp-completion-popup"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Suggestions (${items.size})",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
            )
            Box(
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(2.dp)
                    .testTag("lsp-completion-dismiss"),
            ) {
                PpmIcon(ProductIcon.X.vector, 12.dp, tokens.text3.color)
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(items.take(30)) { idx, item ->
                val detailText = item.detail.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(item) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("lsp-completion-item-$idx"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        item.kindName,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(tokens.bgSolid.color)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        style = ppmText(10f, kind = FontKind.MONO, lineHeight = 13f).copy(color = tokens.accent.color),
                    )
                    BasicText(
                        item.label,
                        modifier = Modifier.weight(1f),
                        style = ppmText(12f, FontWeight.Medium, kind = FontKind.MONO, lineHeight = 16f).copy(color = tokens.text.color),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (detailText.isNotBlank()) {
                        BasicText(
                            detailText,
                            modifier = Modifier.widthIn(max = 160.dp),
                            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ── Dock `Problems` Panel (Port of `src/web/components/problems/problems-panel.tsx`) ──

@Composable
fun LspHoverPopup(
    markdown: String?,
    onDismiss: () -> Unit,
) {
    if (markdown.isNullOrBlank()) return
    val tokens = LocalPpmTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, sides = CssSides.Bottom)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("lsp-hover-popup"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                "Hover Info",
                style = ppmText(11f, FontWeight.SemiBold, lineHeight = 14f).copy(color = tokens.text3.color),
            )
            Box(
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(2.dp)
                    .testTag("lsp-hover-dismiss"),
            ) {
                PpmIcon(ProductIcon.X.vector, 12.dp, tokens.text3.color)
            }
        }
        BasicText(
            markdown,
            style = ppmText(11f, kind = FontKind.MONO, lineHeight = 15f).copy(color = tokens.text.color),
        )
    }
}

@Composable
fun ProblemsTabBody(model: AppModel, tab: WorkspaceTab) {
    val rev by SharedProblemsRegistry.revision.collectAsState()
    val projectName = model.selectedProject
    val hasAny = remember(rev, projectName) { SharedProblemsRegistry.hasAnyFiles(projectName) }
    val totals = remember(rev, projectName) { SharedProblemsRegistry.problemCounts(projectName) }
    var query by remember { mutableStateOf("") }
    val groups = remember(rev, projectName, query) {
        SharedProblemsRegistry.sortedProblems(projectName, query)
    }

    ProblemsPanelContent(
        hasAnyOpenFileProblems = hasAny,
        totals = totals,
        groups = groups,
        query = query,
        onQueryChange = { query = it },
        onJumpToDiagnostic = { file, diagnostic ->
            model.openFileTab(
                project = file.projectName,
                path = file.filePath,
                line = (diagnostic.range.start.line + 1).toLong(),
            )
        },
    )
}

@Composable
internal fun ProblemsPanelContent(
    hasAnyOpenFileProblems: Boolean,
    totals: ProblemTotals,
    groups: List<FileProblems>,
    query: String,
    onQueryChange: (String) -> Unit,
    onJumpToDiagnostic: (FileProblems, LspDiagnostic) -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    var collapsedKeys by remember { mutableStateOf(setOf<String>()) }

    if (!hasAnyOpenFileProblems) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bgSolid.color)
                .padding(16.dp)
                .testTag("problems-empty-state"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                "No problems have been detected in the open files.",
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
            BasicText(
                "Diagnostics come from the language server for each file you open, so a file that has never been opened is not checked.",
                style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color.copy(alpha = 0.75f)),
            )
        }
        return
    }

    val shownCount = groups.sumOf { it.diagnostics.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bgSolid.color)
            .testTag("problems-panel"),
    ) {
        // Header: error/warning counts + filter box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .cssBorder(tokens.border.color, sides = CssSides.Bottom)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag("problems-total-errors"),
                ) {
                    PpmIcon(ProductIcon.CircleX.vector, 12.dp, tokens.error.color)
                    BasicText(
                        totals.errors.toString(),
                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag("problems-total-warnings"),
                ) {
                    PpmIcon(ProductIcon.AlertTriangle.vector, 12.dp, tokens.warning.color)
                    BasicText(
                        totals.warnings.toString(),
                        style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text2.color),
                    )
                }
                Spacer(Modifier.weight(1f))
                BasicText(
                    "${groups.size} file${if (groups.size == 1) "" else "s"}",
                    style = ppmText(11f, lineHeight = 14f).copy(color = tokens.text3.color),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (mobile) 44.dp else 28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tokens.panel.color)
                    .cssBorder(tokens.border.color)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    BasicText(
                        "Filter problems",
                        style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = ppmText(12f, lineHeight = 16f).copy(color = tokens.text.color),
                    cursorBrush = SolidColor(tokens.accent.color),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("problems-filter-input"),
                )
            }
        }

        if (shownCount == 0) {
            BasicText(
                "No problems match “${query.trim()}”.",
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("problems-no-match"),
                style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text3.color),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                for (file in groups) {
                    val key = problemKey(file.projectName, file.filePath)
                    val isCollapsed = key in collapsedKeys
                    val baseName = file.filePath.substringAfterLast('/')
                    val dirPath = file.filePath.substringBeforeLast('/', missingDelimiterValue = "")

                    item(key = "header:$key") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (mobile) 44.dp else 28.dp)
                                .clickable {
                                    collapsedKeys = if (isCollapsed) collapsedKeys - key else collapsedKeys + key
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("problems-file-group-${file.filePath}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PpmIcon(
                                if (isCollapsed) ProductIcon.ChevronRight.vector else ProductIcon.ChevronDown.vector,
                                12.dp,
                                tokens.text3.color,
                            )
                            FileIcon(file.filePath, size = 14.dp)
                            BasicText(
                                baseName,
                                style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (dirPath.isNotEmpty()) {
                                BasicText(
                                    dirPath,
                                    modifier = Modifier.weight(1f),
                                    style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text3.color),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tokens.panel.color)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                BasicText(
                                    file.diagnostics.size.toString(),
                                    style = ppmText(10f, lineHeight = 14f).copy(color = tokens.text2.color),
                                )
                            }
                        }
                    }

                    if (!isCollapsed) {
                        itemsIndexed(
                            items = file.diagnostics,
                            key = { idx, d -> "diag:$key:${d.range.start.line}:${d.range.start.character}:$idx" },
                        ) { idx, diagnostic ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = if (mobile) 44.dp else 24.dp)
                                    .clickable { onJumpToDiagnostic(file, diagnostic) }
                                    .padding(start = 24.dp, end = 8.dp, top = if (mobile) 8.dp else 2.dp, bottom = if (mobile) 8.dp else 2.dp)
                                    .testTag("problems-row-${file.filePath}-$idx"),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                DiagnosticSeverityIcon(diagnostic.effectiveSeverity)
                                BasicText(
                                    diagnostic.message,
                                    modifier = Modifier.weight(1f),
                                    style = ppmText(12f, lineHeight = 16f).copy(color = tokens.text2.color),
                                    maxLines = if (mobile) 4 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                BasicText(
                                    "${diagnostic.sourceLabel()}${diagnostic.range.start.line + 1}:${diagnostic.range.start.character + 1}",
                                    style = ppmText(10f, kind = FontKind.MONO, lineHeight = 14f).copy(color = tokens.text3.color),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSeverityIcon(effectiveSeverity: Int) {
    val tokens = LocalPpmTokens.current
    when (effectiveSeverity) {
        SEVERITY_WARNING -> PpmIcon(ProductIcon.AlertTriangle.vector, 12.dp, tokens.warning.color, Modifier.padding(top = 2.dp))
        SEVERITY_INFO -> PpmIcon(ProductIcon.Info.vector, 12.dp, tokens.accent.color, Modifier.padding(top = 2.dp))
        SEVERITY_ERROR -> PpmIcon(ProductIcon.CircleX.vector, 12.dp, tokens.error.color, Modifier.padding(top = 2.dp))
        else -> PpmIcon(ProductIcon.Sparkles.vector, 12.dp, tokens.text3.color, Modifier.padding(top = 2.dp))
    }
}
