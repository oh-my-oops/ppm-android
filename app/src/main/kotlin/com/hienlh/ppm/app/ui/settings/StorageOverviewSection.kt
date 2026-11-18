package com.hienlh.ppm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.SettingsButton
import com.hienlh.ppm.app.ui.SettingsSectionTitle
import com.hienlh.ppm.app.ui.SettingsTextInput
import com.hienlh.ppm.app.ui.phone.settings.storageBytes
import com.hienlh.ppm.app.ui.phone.settings.storageNewestBackup
import com.hienlh.ppm.app.ui.phone.settings.traceUsage
import com.hienlh.ppm.client.StorageOverview
import com.hienlh.ppm.client.StorageSessionTrace
import com.hienlh.ppm.client.storageOverview
import com.hienlh.ppm.client.updateSessionTraceLimits
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import com.hienlh.ppm.state.settings.SessionTraceLimitsCheck
import com.hienlh.ppm.state.settings.checkSessionTraceLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/*
 * Settings → Storage on a tablet, above the Query Audit block (`storage-settings-section.tsx`):
 * what the app database, its backups and the session trace take, and the session trace's two
 * limits, which are changed here.
 */

private fun count(value: Long): String = NumberFormat.getIntegerInstance().format(value)

@Composable
fun StorageOverviewSection(model: AppModel) {
    val api = model.api ?: return
    val tokens = LocalPpmTokens.current
    var overview by remember(api) { mutableStateOf<StorageOverview?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, reloads) {
        loading = true
        try {
            overview = api.storageOverview()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Request failed"
        } finally {
            loading = false
        }
    }
    val note = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color)
    Column(Modifier.fillMaxWidth().testTag("storage-overview"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("Space PPM's own files take on this machine.", Modifier.weight(1f), note)
            Box(
                Modifier
                    .size(32.dp)
                    .ppmClickable(enabled = !loading, shape = RoundedCornerShape(6.dp), onClickLabel = "Refresh sizes") { reloads++ }
                    .semantics { contentDescription = "Refresh sizes" },
                contentAlignment = Alignment.Center,
            ) { PpmIcon(ProductIcon.RefreshCw, 14.dp, tokens.text2.color, spinning = loading) }
        }
        val current = overview
        when {
            current != null -> {
                Block("App database", "Settings, projects, sessions and history (ppm.db, with its write-ahead log).") {
                    Fact("Size", storageBytes(current.appDbSizeBytes))
                    Fact("File", current.appDbPath)
                }
                val b = current.backups
                Block("Backups", "Verified snapshots of ${b.dbStem}.db, kept by an automatic schedule.") {
                    Fact("Snapshots", "${count(b.count)} · ${storageBytes(b.totalBytes)}")
                    Fact("Newest", storageNewestBackup(b))
                    if (b.others.isNotEmpty()) {
                        Fact("Other databases", b.others.joinToString(", ") { "${it.dbStem}.db: ${count(it.count)} · ${storageBytes(it.totalBytes)}" })
                    }
                    Fact("Folder", b.dir)
                }
                Block("Session trace", "Every chat turn's inputs and events, kept for debugging.") {
                    SessionTraceBlock(model, current.sessionTrace) { overview = current.copy(sessionTrace = it) }
                }
            }
            error != null -> BasicText("Could not load storage details: $error", style = ppmText(12f).copy(color = tokens.error.color))
            else -> BasicText("Loading…", style = note)
        }
    }
}

@Composable
private fun Block(title: String, description: String, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionTitle(title)
        BasicText(description, style = ppmText(11f, lineHeight = 16f).copy(color = tokens.text3.color))
        content()
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val tokens = LocalPpmTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(label, Modifier.fillMaxWidth(0.25f), ppmText(12f).copy(color = tokens.text3.color))
        BasicText(value, Modifier.weight(1f), ppmText(12f, lineHeight = 17f).copy(color = tokens.text.color))
    }
}

/** `StorageSessionTraceBlock`: the size against the cap, how far back it reaches, and the two limits. */
@Composable
private fun SessionTraceBlock(model: AppModel, trace: StorageSessionTrace, onSaved: (StorageSessionTrace) -> Unit) {
    val tokens = LocalPpmTokens.current
    val usage = traceUsage(trace.sizeBytes, trace.maxSizeMb)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(usage.label, style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
            BasicText(
                trace.oldestTs?.let { "Since " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "No events yet",
                style = ppmText(12f).copy(color = tokens.text3.color),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(tokens.border.color)
                .semantics {
                    contentDescription = "Session trace size"
                    progressBarRangeInfo = ProgressBarRangeInfo(usage.fraction, 0f..1f)
                },
        ) {
            Box(Modifier.fillMaxWidth(usage.fraction).fillMaxHeight().clip(RoundedCornerShape(50)).background((if (usage.over) tokens.error else tokens.accent).color))
        }
        BasicText(trace.path, style = ppmText(11f).copy(color = tokens.text3.color))
        SessionTraceLimitsForm(model, trace, onSaved)
    }
}

/**
 * The session trace's two limits and Save (`StorageSessionTraceBlock`'s form): under the tablet's
 * size bar, and on the phone's Storage page under its own.
 */
@Composable
internal fun SessionTraceLimitsForm(model: AppModel, trace: StorageSessionTrace, onSaved: (StorageSessionTrace) -> Unit) {
    val tokens = LocalPpmTokens.current
    val scope = rememberCoroutineScope()
    var days by remember(trace.retentionDays) { mutableStateOf(trace.retentionDays.toString()) }
    var sizeMb by remember(trace.maxSizeMb) { mutableStateOf(trace.maxSizeMb.toString()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = days != trace.retentionDays.toString() || sizeMb != trace.maxSizeMb.toString()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Keep for (days)", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                SettingsTextInput(days, { days = it; error = null }, "", Modifier.fillMaxWidth().testTag("session-trace-days"), keyboardType = KeyboardType.Number)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Max size (MB)", style = ppmText(12f, FontWeight.Medium).copy(color = tokens.text.color))
                SettingsTextInput(sizeMb, { sizeMb = it; error = null }, "", Modifier.fillMaxWidth().testTag("session-trace-size"), keyboardType = KeyboardType.Number)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                error ?: "Older events are pruned once either limit is passed.",
                Modifier.weight(1f).testTag("session-trace-note"),
                ppmText(11f, lineHeight = 16f).copy(color = (if (error != null) tokens.error else tokens.text3).color),
            )
            SettingsButton(if (saving) "Saving…" else "Save", primary = true, enabled = dirty && !saving, modifier = Modifier.testTag("session-trace-save")) {
                when (val check = checkSessionTraceLimits(days, sizeMb)) {
                    is SessionTraceLimitsCheck.Problem -> error = check.error
                    is SessionTraceLimitsCheck.Ok -> {
                        error = null
                        saving = true
                        scope.launch {
                            try {
                                onSaved(model.api!!.updateSessionTraceLimits(check.retentionDays, check.maxSizeMb))
                                model.toast(ErrorToastMessage("Session trace limits updated", kind = ErrorToastMessage.Kind.SUCCESS))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not save"
                                model.toast(ErrorToastMessage("Could not save", e.message))
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            }
        }
    }
}
