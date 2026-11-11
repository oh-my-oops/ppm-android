package com.hienlh.ppm.app.ui.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ErrorToastMessage
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesScreen
import com.hienlh.ppm.app.ui.phone.tools.PhoneSchedulesState
import com.hienlh.ppm.client.schedulesGetLogs
import com.hienlh.ppm.client.schedulesList
import com.hienlh.ppm.client.schedulesListRuns
import com.hienlh.ppm.client.schedulesRunNow
import com.hienlh.ppm.client.schedulesUpdate
import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.int
import com.hienlh.ppm.state.settings.ScheduleLogBufferState
import com.hienlh.ppm.state.settings.ScheduleRunRow
import com.hienlh.ppm.state.settings.mergeScheduleLogs
import com.hienlh.ppm.state.settings.parseScheduleLogs
import com.hienlh.ppm.state.settings.parseScheduleRuns
import com.hienlh.ppm.state.settings.parseSchedulesList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val PHONE_SCHEDULES_POLL_MS = 10_000L
internal const val PHONE_SCHEDULE_LOG_POLL_MS = 2_000L

/**
 * Phone Schedules, wired as the web's Settings → Schedules (`schedule-row.tsx`, `schedule-runs.tsx`,
 * `schedule-logs-sheet.tsx`) and the pre-redesign `SchedulesSettingsSection`: the list from `/api/schedules`, a
 * schedule's run history from `/api/schedules/:id/runs` when it is expanded or its log is open, a script's live log
 * from `/api/schedules/:id/logs` every 2 s while it is open, Run now / Restart and the on/off switch.
 */
@Composable
internal fun PhoneSchedulesAreaHost(
    model: AppModel,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val schedulesState = remember { PhoneSchedulesState() }
    var loading by remember { mutableStateOf(true) }
    var listError by remember { mutableStateOf<String?>(null) }
    var runsById by remember { mutableStateOf<Map<Int, List<ScheduleRunRow>>>(emptyMap()) }
    var logBuffer by remember { mutableStateOf(ScheduleLogBufferState()) }

    suspend fun reloadSchedules() {
        val api = model.api ?: run {
            loading = false
            return
        }
        try {
            schedulesState.schedules = parseSchedulesList(api.schedulesList(enabledOnly = false))
            listError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listError = e.message?.takeIf { it.isNotBlank() } ?: "Could not load schedules"
        } finally {
            loading = false
        }
    }

    suspend fun reloadRuns(id: Int) {
        val api = model.api ?: return
        try {
            runsById = runsById + (id to parseScheduleRuns(api.schedulesListRuns(id, limit = 20)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (id !in runsById) runsById = runsById + (id to emptyList())
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, lifecycle) {
        if (model.api == null) {
            loading = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                reloadSchedules()
                delay(PHONE_SCHEDULES_POLL_MS)
            }
        }
    }

    // A schedule's history is read when its row opens (`ScheduleRuns`) and when its log page opens.
    val expandedId = schedulesState.expandedScheduleId
    LaunchedEffect(model.api, expandedId) {
        expandedId?.let { reloadRuns(it) }
    }
    val fullLogId = schedulesState.viewingFullLogScheduleId
    LaunchedEffect(model.api, fullLogId) {
        val api = model.api ?: return@LaunchedEffect
        val id = fullLogId ?: return@LaunchedEffect
        reloadRuns(id)
        val target = schedulesState.schedules.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (!target.kind.equals("script", ignoreCase = true)) return@LaunchedEffect
        logBuffer = ScheduleLogBufferState()
        while (isActive) {
            try {
                logBuffer = mergeScheduleLogs(logBuffer, parseScheduleLogs(api.schedulesGetLogs(id, after = logBuffer.afterSeq)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay(PHONE_SCHEDULE_LOG_POLL_MS)
        }
    }

    PhoneSchedulesScreen(
        state = schedulesState,
        runsByScheduleId = runsById,
        logsByScheduleId = fullLogId?.let { mapOf(it to logBuffer.lines) }.orEmpty(),
        loading = loading,
        error = listError,
        modifier = modifier,
        onRunOrRestart = { row ->
            val api = model.api ?: return@PhoneSchedulesScreen
            val alwaysOn = row.mode.equals("always-on", ignoreCase = true)
            scope.launch {
                try {
                    val res = api.schedulesRunNow(row.id)
                    val runId = res["runId"].int
                    val wasRunning = res["wasRunning"].bool == true
                    model.toast(
                        ErrorToastMessage(
                            when {
                                runId != null -> "Run #$runId started" + if (wasRunning) " (previous run still active)" else ""
                                alwaysOn -> "Restarting"
                                else -> "Tick started"
                            },
                            kind = ErrorToastMessage.Kind.SUCCESS,
                        ),
                    )
                    reloadRuns(row.id)
                    reloadSchedules()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.toast(ErrorToastMessage(e.message?.takeIf { it.isNotBlank() } ?: "Run failed"))
                }
            }
        },
        onToggleEnabled = { row, enabled ->
            schedulesState.toggleScheduleEnabled(row.id, enabled)
            val api = model.api ?: return@PhoneSchedulesScreen
            scope.launch {
                try {
                    api.schedulesUpdate(row.id, buildJsonObject { put("enabled", enabled) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.toast(ErrorToastMessage(e.message?.takeIf { it.isNotBlank() } ?: "Update failed"))
                }
                reloadSchedules()
            }
        },
    )
}
