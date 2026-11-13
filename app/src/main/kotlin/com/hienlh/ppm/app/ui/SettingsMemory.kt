package com.hienlh.ppm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.client.installMemoryModel
import com.hienlh.ppm.client.memoryStatus
import com.hienlh.ppm.client.removeMemoryModel
import com.hienlh.ppm.client.setMemoryEmbedding
import com.hienlh.ppm.client.setMemoryEnabled
import com.hienlh.ppm.client.syncMemoryNow
import com.hienlh.ppm.client.testMemoryEmbedding
import com.hienlh.ppm.design.FontKind
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmFormat
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssBorder
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.protocol.MemoryInstallStatus
import com.hienlh.ppm.protocol.MemoryStatus
import com.hienlh.ppm.state.settings.MEMORY_EMBEDDING_SOURCES
import com.hienlh.ppm.state.settings.MEMORY_LOCAL_MODEL_NAME
import com.hienlh.ppm.state.settings.MEMORY_PROVIDERS
import com.hienlh.ppm.state.settings.MemorySettingsData
import com.hienlh.ppm.state.settings.memoryInstallProgress
import com.hienlh.ppm.state.settings.memoryProviderNote
import com.hienlh.ppm.state.settings.memoryProviderSummary
import com.hienlh.ppm.state.settings.memoryVectorCount
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Settings → Memory (`memory-settings-section.tsx` + `memory-embedding-source.tsx`): the on/off
 * switch, the folder memories live in with the active project's counts, one row per provider with
 * what was copied in, when, and the last error, Sync now, and where memory vectors come from. On a
 * phone the rows and buttons are 44 tall and Sync now spans the width under the rows. Changes from
 * any device arrive as `memory:changed` / `memory:embed-install` on `/ws/global`.
 */
@Composable
internal fun MemorySettingsSection(model: AppModel) {
    val api = model.api
    val project = model.currentProject?.name
    val scope = rememberCoroutineScope()
    val data = remember(api, project) {
        MemorySettingsData(
            object : MemorySettingsData.Source {
                private val client get() = api ?: error("Not connected")
                override suspend fun status() = client.memoryStatus(project)
                override suspend fun setEnabled(enabled: Boolean) = client.setMemoryEnabled(enabled)
                override suspend fun sync() = client.syncMemoryNow()
                override suspend fun setEmbedding(fields: Map<String, String>) =
                    client.setMemoryEmbedding(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } })
                override suspend fun install() = client.installMemoryModel()
                override suspend fun remove() { client.removeMemoryModel() }
                override suspend fun test(fields: Map<String, String>) =
                    client.testMemoryEmbedding(buildJsonObject { fields.forEach { (k, v) -> put(k, v) } })
            },
            onError = { model.toast(ErrorToastMessage(it)) },
        )
    }
    LaunchedEffect(data) { data.load() }
    val events = model.events
    DisposableEffect(events, data) {
        val offChanged = events?.onMemoryChanged { scope.launch { data.load() } }
        val offInstall = events?.onMemoryEmbedInstall { status ->
            if (data.onInstallEvent(MemoryInstallStatus.from(status))) scope.launch { data.load() }
        }
        onDispose {
            offChanged?.invoke()
            offInstall?.invoke()
        }
    }
    val state by data.state.collectAsState()
    MemorySettingsContent(
        state = state,
        onEnabledChange = { scope.launch { data.setEnabled(it) } },
        onSyncNow = { scope.launch { data.syncNow() } },
        onChooseSource = { scope.launch { data.choose(it) } },
        onInstall = { scope.launch { data.startInstall() } },
        onRemove = { scope.launch { data.removeInstall() } },
        onUrlChange = data::setUrl,
        onModelChange = data::setModel,
        onApiKeyChange = data::setApiKey,
        onSave = { scope.launch { data.save() } },
        onTest = { scope.launch { data.test() } },
    )
}

/** The pane drawn from [state]; every action is a callback, so tests drive it without a server. */
@Composable
internal fun MemorySettingsContent(
    state: MemorySettingsData.State,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onChooseSource: (String) -> Unit = {},
    onInstall: () -> Unit = {},
    onRemove: () -> Unit = {},
    onUrlChange: (String) -> Unit = {},
    onModelChange: (String) -> Unit = {},
    onApiKeyChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onTest: () -> Unit = {},
    relative: (java.time.Instant) -> String = { PpmFormat.relative(it) },
) {
    val tokens = LocalPpmTokens.current
    val status = state.status
    if (status == null) {
        if (state.loadError != null) {
            BasicText(
                "Could not load memory status: ${state.loadError}",
                Modifier.padding(16.dp).testTag("memory-load-error"),
                style = ppmText(14f, lineHeight = 20f).copy(color = tokens.error.color),
            )
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 20.dp, tokens.text2.color, spinning = true)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("memory-settings"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        EnabledRow(status.enabled, state.saving, onEnabledChange)
        WhereMemoriesLive(status)
        Separator()
        CopiedFromProviders(status, state.syncing, onSyncNow, relative)
        Separator()
        EmbeddingSource(state, onChooseSource, onInstall, onRemove, onUrlChange, onModelChange, onApiKeyChange, onSave, onTest)
    }
}

private val small = ppmText(12f, lineHeight = 16f)
private val body = ppmText(14f, lineHeight = 20f)
private val bodyMedium = ppmText(14f, FontWeight.Medium, lineHeight = 20f)

@Composable
private fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = small.copy(color = LocalPpmTokens.current.text2.color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun EnabledRow(enabled: Boolean, saving: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = LocalPpmTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("memory-enabled-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f).ppmClickable(enabled = !saving) { onChange(!enabled) }) {
            BasicText("PPM memory", style = bodyMedium.copy(color = tokens.text.color))
            Muted(
                if (enabled) {
                    "One memory store for Claude, Codex and Antigravity. PPM copies each provider's own memories in and never changes them."
                } else {
                    "Off: nothing is synced and chats get today's shared context. Every memory file is kept."
                },
            )
        }
        Box(Modifier.testTag("memory-enabled-switch")) {
            PpmSwitch(checked = enabled, onCheckedChange = onChange, enabled = !saving)
        }
    }
}

@Composable
private fun WhereMemoriesLive(status: MemoryStatus) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(8.dp)
    val counts = "This project ${status.projectCount} · Global ${status.globalCount} · Archived ${status.archivedCount}"
    val root = @Composable { m: Modifier ->
        BasicText(status.root, m, style = ppmText(12f, lineHeight = 16f, kind = FontKind.MONO).copy(color = tokens.text.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("Where memories live", style = bodyMedium.copy(color = tokens.text.color))
        val box = Modifier.fillMaxWidth().clip(shape).cssBorder(tokens.border.color, shape).padding(horizontal = 12.dp, vertical = 8.dp).testTag("memory-root")
        if (mobile) {
            Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                root(Modifier)
                Muted(counts)
            }
        } else {
            Row(box, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpmIcon(ProductIcon.Folder, 16.dp, tokens.text2.color)
                root(Modifier.weight(1f))
                Muted(counts, maxLines = 1)
            }
        }
        Muted("Plain Markdown files, one per memory. Edit them by hand if you like; PPM picks the change up on the next message.")
    }
}

@Composable
private fun CopiedFromProviders(status: MemoryStatus, syncing: Boolean, onSyncNow: () -> Unit, relative: (java.time.Instant) -> String) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(8.dp)
    // One Sync now button: beside the heading on a tablet, full width under the rows on a phone.
    val sync = @Composable { m: Modifier ->
        SettingsActionButton(
            "Sync now",
            m.testTag("memory-sync-now"),
            outline = true,
            icon = ProductIcon.RefreshCw,
            busy = syncing,
            enabled = !syncing && status.enabled,
            minHeight = if (mobile) 44.dp else 32.dp,
            onClick = onSyncNow,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                BasicText("Copied from providers", style = bodyMedium.copy(color = tokens.text.color))
                Muted("Synced before each chat message when a provider's memory changed.")
            }
            if (!mobile) sync(Modifier)
        }
        Column(Modifier.fillMaxWidth().clip(shape).cssBorder(tokens.border.color, shape)) {
            MEMORY_PROVIDERS.forEachIndexed { i, def ->
                if (i > 0) Separator()
                val provider = status.providers[def.id]
                val note = memoryProviderNote(provider, def)
                val icon = when {
                    note.error -> ProductIcon.XCircle
                    provider != null && provider.count > 0 -> ProductIcon.Check
                    else -> ProductIcon.Info
                }
                val iconColor = when {
                    note.error -> tokens.error
                    provider != null && provider.count > 0 -> tokens.success
                    else -> tokens.text2
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp).testTag("memory-provider-${def.id}"),
                    verticalAlignment = if (mobile) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PpmIcon(icon, 16.dp, iconColor.color, if (mobile) Modifier.padding(top = 2.dp) else Modifier)
                    Column(Modifier.weight(1f)) {
                        BasicText(def.label, style = body.copy(color = tokens.text.color))
                        BasicText(
                            note.text,
                            style = small.copy(color = (if (note.error) tokens.error else tokens.text2).color),
                            maxLines = if (note.error) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Muted(memoryProviderSummary(provider, relative), maxLines = 1)
                }
            }
        }
        if (mobile) sync(Modifier.fillMaxWidth())
    }
}

@Composable
private fun EmbeddingSource(
    state: MemorySettingsData.State,
    onChoose: (String) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val emb = state.status?.embedding ?: return
    val source = state.chosenSource
    val countLine = memoryVectorCount(emb.vectors, emb.total, emb.running)
    Column(Modifier.testTag("memory-embedding"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            BasicText("Search by meaning", style = bodyMedium.copy(color = tokens.text.color))
            Muted("Word search always works. Adding meaning finds a memory worded differently from the question, Vietnamese or English.")
        }
        SourcePicker(source, state.busy == MemorySettingsData.Busy.Source, onChoose)
        when (source) {
            "local" -> LocalModel(state.install, state.busy, countLine, onInstall, onRemove)
            "api" -> ApiForm(state, emb.hasApiKey, if (emb.source == "api" && emb.ready) countLine else null, onUrlChange, onModelChange, onApiKeyChange, onSave, onTest)
        }
        if (state.wordsOnly) {
            BorderedBox(Modifier.testTag("memory-words-only")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source == "off") {
                        PpmIcon(ProductIcon.Info, 16.dp, tokens.text2.color)
                    } else {
                        PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.warning.color)
                    }
                    BasicText("Searching by words only", style = body.copy(color = tokens.text.color))
                }
                if (emb.reason.isNotEmpty()) Muted(emb.reason)
            }
        }
        if (source != "off") {
            Muted("Switching to another model or source recomputes every memory's meaning in the background. Vectors from two models are never mixed.")
        }
    }
}

/** `rounded-lg border border-border px-3 py-2.5 space-y-1`. */
@Composable
private fun BorderedBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).cssBorder(LocalPpmTokens.current.border.color, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Off · Local model · API: a 3-column grid of 40 tall buttons on a phone, an inline group elsewhere. */
@Composable
private fun SourcePicker(source: String, busy: Boolean, onChoose: (String) -> Unit) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val outer = RoundedCornerShape(8.dp)
    val inner = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .then(if (mobile) Modifier.fillMaxWidth() else Modifier)
            .clip(outer)
            .background(tokens.panel.color)
            .cssBorder(tokens.border.color, outer)
            .padding(2.dp),
    ) {
        for ((id, label) in MEMORY_EMBEDDING_SOURCES) {
            val selected = source == id
            Row(
                Modifier
                    .then(if (mobile) Modifier.weight(1f).height(40.dp) else Modifier.height(28.dp))
                    .clip(inner)
                    .background(if (selected) tokens.bgSolid.color else Color.Transparent)
                    .ppmClickable(enabled = !busy, shape = inner) { onChoose(id) }
                    .padding(horizontal = if (mobile) 0.dp else 12.dp)
                    .testTag("memory-source-$id"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                val color = (if (selected) tokens.text else tokens.text2).color
                if (!mobile) {
                    when (id) {
                        "local" -> PpmIcon(ProductIcon.Cpu, 14.dp, color)
                        "api" -> PpmIcon(ProductIcon.Globe, 14.dp, color)
                    }
                }
                BasicText(label, style = ppmText(12f, if (selected) FontWeight.Medium else FontWeight.Normal, lineHeight = 16f).copy(color = color), maxLines = 1)
            }
        }
    }
}

/** A text link action (`h-11 px-2 text-xs text-muted-foreground`, `md:h-auto md:px-1`). */
@Composable
private fun LinkAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .then(if (mobile) Modifier.heightIn(min = 44.dp) else Modifier)
            .clip(shape)
            .ppmClickable(enabled = enabled, shape = shape, onClick = onClick)
            .padding(horizontal = if (mobile) 8.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = small.copy(color = LocalPpmTokens.current.text2.color))
    }
}

@Composable
private fun LocalModel(
    install: MemoryInstallStatus,
    busy: MemorySettingsData.Busy?,
    countLine: String,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    when (install.state) {
        MemoryInstallStatus.READY -> BorderedBox(Modifier.testTag("memory-local-ready")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PpmIcon(ProductIcon.Check, 16.dp, tokens.success.color)
                BasicText("$MEMORY_LOCAL_MODEL_NAME · runs on this machine", Modifier.weight(1f), style = body.copy(color = tokens.text.color))
                LinkAction("Remove", busy == null, onRemove, Modifier.testTag("memory-local-remove"))
            }
            Muted("Nothing leaves this machine. $countLine")
        }
        MemoryInstallStatus.INSTALLING -> BorderedBox(Modifier.testTag("memory-local-installing")) {
            val progress = memoryInstallProgress(install)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Installing the model…", Modifier.weight(1f), style = body.copy(color = tokens.text.color))
                if (install.totalBytes > 0) Muted("${progress.doneMb} / ${progress.totalMb} MB")
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(tokens.panel.color)) {
                Box(Modifier.fillMaxWidth(progress.percent / 100f).height(6.dp).background(tokens.accent.color))
            }
            LinkAction("Cancel", busy != MemorySettingsData.Busy.Remove, onRemove, Modifier.testTag("memory-local-cancel"))
        }
        else -> BorderedBox(Modifier.testTag("memory-local-install")) {
            val failed = install.state == MemoryInstallStatus.FAILED
            BasicText(if (failed) "The model could not be installed." else "The model is not installed.", style = body.copy(color = tokens.text.color))
            if (failed) BasicText(install.reason, style = small.copy(color = tokens.error.color))
            Muted(
                "$MEMORY_LOCAL_MODEL_NAME understands Vietnamese and English and runs on the CPU. Download ≈ 650 MB into ~/.ppm once " +
                    "(runtime 520 MB + model 130 MB). While it runs it uses about 1 GB of RAM, and it stops after 10 minutes without a search.",
            )
            SettingsActionButton(
                if (failed) "Try again" else "Install (≈ 650 MB)",
                (if (mobile) Modifier.fillMaxWidth() else Modifier).testTag("memory-local-install-button"),
                icon = ProductIcon.Download,
                busy = busy == MemorySettingsData.Busy.Install,
                enabled = busy == null,
                onClick = onInstall,
            )
        }
    }
}

@Composable
private fun ApiForm(
    state: MemorySettingsData.State,
    hasApiKey: Boolean,
    countLine: String?,
    onUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val tokens = LocalPpmTokens.current
    val busy = state.busy
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.testTag("memory-api-form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(tokens.warning.opacity(0.1).color)
                .cssBorder(tokens.warning.opacity(0.4).color, shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PpmIcon(ProductIcon.TriangleAlert, 16.dp, tokens.warning.color, Modifier.padding(top = 2.dp))
            BasicText(
                "Every memory's text is sent to this endpoint to compute its meaning, and each search query too.",
                style = small.copy(color = tokens.text.color),
            )
        }
        MemoryField("Endpoint URL", state.url, "https://api.openai.com/v1", onUrlChange, "memory-api-url")
        MemoryField("Model", state.model, "text-embedding-3-small", onModelChange, "memory-api-model")
        MemoryField("API key", state.apiKey, if (hasApiKey) "••••••••••••••••" else "Optional", onApiKeyChange, "memory-api-key", password = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = busy == null && state.apiFormComplete
            SettingsActionButton("Save", Modifier.testTag("memory-api-save"), busy = busy == MemorySettingsData.Busy.Save, enabled = ready, onClick = onSave)
            SettingsActionButton("Test", Modifier.testTag("memory-api-test"), outline = true, busy = busy == MemorySettingsData.Busy.Test, enabled = ready, onClick = onTest)
            state.testResult?.let { result ->
                if (result.ok) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PpmIcon(ProductIcon.Check, 14.dp, tokens.success.color)
                        BasicText(
                            "Works · ${"%,d".format(Locale.US, result.dims)} dimensions · ${"%,d".format(Locale.US, result.ms)} ms",
                            Modifier.testTag("memory-api-test-result"),
                            style = small.copy(color = tokens.success.color),
                        )
                    }
                } else {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PpmIcon(ProductIcon.X, 14.dp, tokens.error.color)
                        BasicText(result.error, Modifier.testTag("memory-api-test-result"), style = small.copy(color = tokens.error.color))
                    }
                }
            }
        }
        if (countLine != null) Muted(countLine)
    }
}

/** A labelled field (`space-y-1.5`, `text-xs font-medium` over the web's `Input`). */
@Composable
private fun MemoryField(label: String, value: String, placeholder: String, onChange: (String) -> Unit, tag: String, password: Boolean = false) {
    val tokens = LocalPpmTokens.current
    val mobile = LocalPpmMobile.current
    val shape = RoundedCornerShape(6.dp)
    val textStyle = if (mobile) ppmText(16f, lineHeight = 24f) else ppmText(14f, lineHeight = 20f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = ppmText(12f, FontWeight.Medium, lineHeight = 16f).copy(color = tokens.text.color))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = textStyle.copy(color = tokens.text.color),
            cursorBrush = SolidColor(tokens.accent.color),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(shape)
                .cssBorder(tokens.border.color, shape)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag(tag),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) BasicText(placeholder, style = textStyle.copy(color = tokens.text3.color), maxLines = 1)
                    inner()
                }
            },
        )
    }
}
