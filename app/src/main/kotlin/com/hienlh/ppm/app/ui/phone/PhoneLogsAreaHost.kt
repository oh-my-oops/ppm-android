package com.hienlh.ppm.app.ui.phone

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.phone.tools.PhoneLogsScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneLogsState
import com.hienlh.ppm.client.queryLogs
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.logs.DEDUPE_TAIL
import com.hienlh.ppm.state.logs.LogEntry
import com.hienlh.ppm.state.logs.LogQueryResult
import com.hienlh.ppm.state.logs.LogRow
import com.hienlh.ppm.state.logs.LogsLiveFeedBuffer
import com.hienlh.ppm.state.logs.logsQueryPairs
import com.hienlh.ppm.state.logs.rangeStart
import com.hienlh.ppm.state.logs.rawLogLine
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The `.log` file the web's "Download as .log" saves (`logs-actions.ts` `downloadRows`): its name and its text. */
internal fun phoneLogsDownloadName(now: LocalDateTime = LocalDateTime.now()): String =
    "ppm-logs-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US))}.log"

internal fun phoneLogsDownloadText(rows: List<LogRow>): String =
    rows.joinToString("\n") { rawLogLine(it.entry, it.count) } + "\n"

/**
 * Phone Logs, read as the pre-redesign `LogsAppBody` reads them: one page from `/api/logs` with the filter the user set
 * (source, levels, search, chat), older pages on demand (`before=`), and the live tail from `/ws/global`'s `logs:lines`
 * merged through [LogsLiveFeedBuffer], which holds what arrives while paused or while a page is being read.
 */
@Composable
internal fun PhoneLogsAreaHost(
    model: AppModel,
    tab: WorkspaceTab? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialChat = tab?.metaString("logsChat")?.takeIf { it.isNotBlank() }
    val logsState = remember { PhoneLogsState(initialChat = initialChat) }
    // A "Show logs for this chat" that lands on the open Logs tab patches its `logsChat`.
    LaunchedEffect(initialChat) {
        if (initialChat != null && logsState.chat != initialChat) logsState.chat = initialChat
    }

    var result by remember { mutableStateOf(LogQueryResult()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloads by remember { mutableIntStateOf(0) }
    val liveBuffer = remember { LogsLiveFeedBuffer() }
    val filter = logsState.toLogFilter()

    suspend fun loadPage(before: String? = null) {
        val api = model.api ?: run {
            loading = false
            return
        }
        if (before == null) liveBuffer.beginPageRead()
        loading = true
        try {
            val from = rangeStart(logsState.timeRange, System.currentTimeMillis())
            val parsed = LogQueryResult.from(api.queryLogs(logsQueryPairs(filter, logsState.timeRange, from, before = before)))
            result = if (before != null) {
                val have = result.entries.take(DEDUPE_TAIL).map { it.id }.toSet()
                result.copy(
                    entries = parsed.entries.filterNot { it.id in have } + result.entries,
                    hasMore = parsed.hasMore,
                    titles = parsed.titles + result.titles,
                    restarts = (parsed.restarts + result.restarts).distinct().sorted(),
                )
            } else {
                liveBuffer.finishPageRead(parsed, filter)
            }
            loadError = null
        } catch (e: CancellationException) {
            if (before == null) liveBuffer.cancelPageRead()
            throw e
        } catch (e: Exception) {
            if (before == null) liveBuffer.cancelPageRead()
            loadError = e.message?.takeIf { it.isNotBlank() } ?: "Failed to query logs"
        } finally {
            loading = false
        }
    }

    // Live tail: subscribe before the page read; a reconnect (`global_ready`) reads the page again.
    DisposableEffect(model, model.events) {
        val releaseTail = model.retainLogsTail()
        val ev = model.events
        val unsubReady = ev?.onGlobalReady { reloads += 1 }
        val unsubLines = ev?.onLogsLines { msg ->
            val incoming = msg["entries"].array.orEmpty().mapNotNull(LogEntry::from)
            val titles = msg["titles"].obj?.mapNotNull { (k, v) -> v.string?.let { k to it } }?.toMap().orEmpty()
            result = liveBuffer.onLiveBatch(result, incoming, titles, logsState.toLogFilter(), logsState.follow)
        }
        onDispose {
            unsubReady?.invoke()
            unsubLines?.invoke()
            releaseTail()
        }
    }

    var firstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(model.api, filter, logsState.timeRange, reloads) {
        // Typing in the search field asks once it pauses, not per key.
        if (!firstLoad) delay(250)
        firstLoad = false
        loadPage()
    }

    // Follow again: what came in while paused joins the list.
    LaunchedEffect(logsState.follow) {
        if (logsState.follow) result = liveBuffer.resumeFollow(result, logsState.toLogFilter())
    }

    var pendingDownload by remember { mutableStateOf<List<LogRow>?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val rows = pendingDownload
        pendingDownload = null
        if (uri == null || rows == null) return@rememberLauncherForActivityResult
        val saved = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(phoneLogsDownloadText(rows).toByteArray()) }
                ?: error("no output stream")
        }.isSuccess
        val lines = rows.sumOf { it.count }
        model.toast(
            if (saved) {
                ErrorToastMessage("Saved the logs with $lines ${if (lines == 1) "line" else "lines"}", kind = ErrorToastMessage.Kind.SUCCESS)
            } else {
                ErrorToastMessage("Could not save the logs")
            },
        )
    }

    PhoneLogsScreen(
        state = logsState,
        entries = result.entries,
        restarts = result.restarts,
        titles = result.titles,
        chats = result.chats,
        hasMore = result.hasMore,
        loading = loading,
        error = loadError,
        onLoadOlder = {
            val oldest = result.entries.firstOrNull()?.id
            if (oldest != null && !loading) scope.launch { loadPage(before = oldest) }
        },
        onDownloadLog = { rows ->
            if (rows.isNotEmpty()) {
                pendingDownload = rows
                saveLauncher.launch(phoneLogsDownloadName())
            }
        },
    )
}
