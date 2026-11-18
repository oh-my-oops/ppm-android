package com.hienlh.ppm.app.ui.phone.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.settings.SessionTraceLimitsForm
import com.hienlh.ppm.client.QueryAuditUsage
import com.hienlh.ppm.client.StorageBackups
import com.hienlh.ppm.client.StorageOverview
import com.hienlh.ppm.client.StorageSessionTrace
import com.hienlh.ppm.client.queryAuditUsage
import com.hienlh.ppm.client.storageOverview
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.PpmIcon
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.ppmClickable
import com.hienlh.ppm.design.ppmText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** `formatBytes` of `src/web/lib/format-bytes.ts`. */
internal fun storageBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}

/** The Query Audit block's own `formatSize`: KB below a megabyte, MB with one decimal above. */
internal fun queryAuditSize(bytes: Long): String =
    if (bytes < 1024 * 1024) String.format(Locale.US, "%.0f KB", bytes / 1024.0) else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

/** `traceUsage` of `storage-settings-view.ts`: "X of Y", how full the meter is, and whether the trace is over its cap. */
internal data class TraceUsage(val label: String, val fraction: Float, val over: Boolean)

internal fun traceUsage(sizeBytes: Long, maxSizeMb: Long): TraceUsage {
    val capBytes = maxSizeMb * 1024 * 1024
    val raw = if (capBytes > 0) sizeBytes.toDouble() / capBytes else 0.0
    return TraceUsage("${storageBytes(sizeBytes)} of ${storageBytes(capBytes)}", raw.coerceIn(0.0, 1.0).toFloat(), raw > 1)
}

/** `formatRelativeDate` of `src/web/lib/format-date.ts`. */
internal fun storageRelativeDate(atMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
    val minutes = Math.floorDiv(nowMs - atMs, 60_000L)
    if (minutes < 1) return "just now"
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    val days = hours / 24
    if (days < 7) return "${days}d ago"
    return java.text.SimpleDateFormat("MMM d", locale).format(Date(atMs))
}

private fun count(value: Long): String = NumberFormat.getIntegerInstance().format(value)

/** The Backups "Newest" fact: the file, how long ago, and when (`NewestBackup` of `storage-settings-section.tsx`). */
internal fun storageNewestBackup(backups: StorageBackups, nowMs: Long = System.currentTimeMillis()): String {
    val newest = backups.newest ?: return "None yet"
    val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(newest.mtimeMs))
    return "${newest.name} · ${storageRelativeDate(newest.mtimeMs, nowMs)} ($at)"
}

/**
 * Settings → Storage on a phone (`storage-settings-section.tsx`): what PPM's own files take on the
 * server, the session trace's and the Query Audit log's limits, and clearing that log.
 */
@Composable
fun PhoneStoragePage(model: AppModel, onBack: () -> Unit) {
    val api = model.api
    var overview by remember { mutableStateOf<StorageOverview?>(null) }
    var overviewError by remember { mutableStateOf<String?>(null) }
    var audit by remember { mutableStateOf<QueryAuditUsage?>(null) }
    var auditError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }

    LaunchedEffect(api, reloads) {
        val client = api ?: return@LaunchedEffect
        loading = true
        // Independent reads, as on the web: the Query Audit block keeps working if the overview fails.
        coroutineScope {
            val o = async { runCatchingNotCancel { client.storageOverview() } }
            val a = async { runCatchingNotCancel { client.queryAuditUsage() } }
            o.await().onSuccess { overview = it; overviewError = null }.onFailure { overviewError = it.message ?: "Request failed" }
            a.await().onSuccess { audit = it; auditError = null }.onFailure { auditError = it.message ?: "Request failed" }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(LocalPpmTokens.current.bgSolid.color)) {
        PhoneSettingsSubPageHeader(
            title = "Storage",
            onBack = onBack,
            trailingAction = {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .ppmClickable(onClickLabel = "Refresh sizes", enabled = !loading) { reloads++ }
                        .testTag("phone-storage-refresh"),
                    contentAlignment = Alignment.Center,
                ) {
                    PpmIcon(ProductIcon.RefreshCw, 18.dp, LocalPpmTokens.current.text2.color, spinning = loading)
                }
            },
        )
        PhoneStorageContent(
            overview,
            overviewError,
            audit,
            auditError,
            editTrace = { trace ->
                SessionTraceLimitsForm(model, trace) { saved -> overview = overview?.copy(sessionTrace = saved) }
            },
            editAudit = { usage -> PhoneQueryAuditLimits(model, usage) { audit = it } },
        )
    }
}

private suspend fun <T> runCatchingNotCancel(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

@Composable
fun PhoneStorageContent(
    overview: StorageOverview?,
    overviewError: String?,
    audit: QueryAuditUsage?,
    auditError: String?,
    modifier: Modifier = Modifier,
    /** The session trace's limits as fields; null shows them as text. */
    editTrace: (@Composable (StorageSessionTrace) -> Unit)? = null,
    /** The Query Audit log's limits as fields, with Clear; null shows them as text. */
    editAudit: (@Composable (QueryAuditUsage) -> Unit)? = null,
) {
    val tokens = LocalPpmTokens.current
    val note = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color)
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("phone-storage-page"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BasicText("Space PPM's own files take on this machine.", style = note)
        when {
            overview != null -> {
                StorageCard("App database", "Settings, projects, sessions and history (ppm.db, with its write-ahead log).", "phone-storage-app-db") {
                    StorageFact("Size", storageBytes(overview.appDbSizeBytes))
                    StorageFact("File", overview.appDbPath)
                }
                val b = overview.backups
                StorageCard("Backups", "Verified snapshots of ${b.dbStem}.db, kept by an automatic schedule.", "phone-storage-backups") {
                    StorageFact("Snapshots", "${count(b.count)} · ${storageBytes(b.totalBytes)}")
                    StorageFact("Newest", storageNewestBackup(b))
                    if (b.others.isNotEmpty()) {
                        StorageFact("Other databases", b.others.joinToString(", ") { "${it.dbStem}.db: ${count(it.count)} · ${storageBytes(it.totalBytes)}" })
                    }
                    StorageFact("Folder", b.dir)
                }
                StorageCard("Session trace", "Every chat turn's inputs and events, kept for debugging.", "phone-storage-trace") {
                    SessionTraceFacts(overview.sessionTrace, editTrace)
                }
            }
            overviewError != null -> BasicText(
                "Could not load storage details: $overviewError",
                Modifier.testTag("phone-storage-error"),
                style = ppmText(12f, lineHeight = 17f).copy(color = tokens.error.color),
            )
            else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
            }
        }
        StorageCard("Query Audit", "SQL history from the editor, the data grid and the CLI.", "phone-storage-query-audit") {
            when {
                audit != null -> {
                    BasicText(
                        "Every statement run from the SQL editor, the data grid and the ppm db CLI is recorded, together with a " +
                            "sample of the result. Entries are pruned once they pass either limit below — whichever comes first.",
                        style = note,
                    )
                    if (editAudit != null) {
                        editAudit(audit)
                    } else {
                        StorageFact("Keep for", "${count(audit.retentionDays)} days")
                        StorageFact("Max size", "${count(audit.maxSizeMb)} MB")
                        BasicText("${count(audit.entryCount)} entries · ${queryAuditSize(audit.sizeBytes)} on disk", style = note)
                    }
                }
                auditError != null -> BasicText(
                    "Could not load the Query Audit log: $auditError",
                    style = ppmText(12f, lineHeight = 17f).copy(color = tokens.error.color),
                )
                else -> PpmIcon(ProductIcon.Loader2, 16.dp, tokens.text3.color, spinning = true)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SessionTraceFacts(trace: StorageSessionTrace, edit: (@Composable (StorageSessionTrace) -> Unit)?) {
    val tokens = LocalPpmTokens.current
    val usage = traceUsage(trace.sizeBytes, trace.maxSizeMb)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                }
                .testTag("phone-storage-trace-meter"),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(usage.fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background((if (usage.over) tokens.error else tokens.accent).color),
            )
        }
        BasicText(trace.path, style = ppmText(11f).copy(color = tokens.text3.color))
    }
    if (edit != null) {
        edit(trace)
        return
    }
    StorageFact("Keep for", "${count(trace.retentionDays)} days")
    StorageFact("Max size", "${count(trace.maxSizeMb)} MB")
    BasicText("Older events are pruned once either limit is passed.", style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text2.color))
}

@Composable
private fun StorageCard(title: String, description: String, tag: String, content: @Composable () -> Unit) {
    val tokens = LocalPpmTokens.current
    PhoneSectionCard(Modifier.testTag(tag)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(title, style = ppmText(14f, FontWeight.SemiBold).copy(color = tokens.text.color))
            BasicText(description, style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text3.color))
            content()
        }
    }
}

/** A label above its value; the value wraps anywhere because paths are one long word. */
@Composable
private fun StorageFact(label: String, value: String) {
    val tokens = LocalPpmTokens.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = ppmText(11f).copy(color = tokens.text3.color))
        BasicText(value, style = ppmText(12f, lineHeight = 17f).copy(color = tokens.text.color))
    }
}
