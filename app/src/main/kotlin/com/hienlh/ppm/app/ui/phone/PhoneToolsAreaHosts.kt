package com.hienlh.ppm.app.ui.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.database.DatabaseQueryTabBody
import com.hienlh.ppm.app.ui.database.DatabaseSidebarPane
import com.hienlh.ppm.app.ui.database.DatabaseTableTabBody
import com.hienlh.ppm.app.ui.database.DbSqlObjectTabBody
import com.hienlh.ppm.app.ui.database.SqliteViewerTabBody
import com.hienlh.ppm.app.ui.phone.settings.PhoneSettingsScreen
import com.hienlh.ppm.app.ui.phone.settings.resolvePhoneSettingsRoute
import com.hienlh.ppm.app.ui.phone.tools.PhoneMonitorState
import com.hienlh.ppm.app.ui.phone.tools.PhoneMonitorTab
import com.hienlh.ppm.app.ui.phone.tools.PhoneSystemMonitorScreen
import com.hienlh.ppm.client.systemServiceDetails
import com.hienlh.ppm.client.systemServices
import com.hienlh.ppm.state.sysmon.ServiceDetails
import com.hienlh.ppm.state.sysmon.ServicesSnapshot
import com.hienlh.ppm.state.workspace.WorkspaceTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun PhoneDatabaseAreaHost(
    model: AppModel,
    tab: WorkspaceTab?,
    nav: PhoneNav,
) {
    val onNavigateToWorkspaceTab: () -> Unit = {
        model.activeTabId?.let(nav::visitTab)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("phone-database-screen"),
    ) {
        if (tab == null) {
            DatabaseSidebarPane(
                model = model,
                onNavigate = onNavigateToWorkspaceTab,
                readOnly = true,
            )
        } else {
            val readOnlyTab = remember(tab) {
                tab.withMetadata("readOnly" to JsonPrimitive(true))
            }
            when (readOnlyTab.type) {
                "database" -> DatabaseTableTabBody(model, readOnlyTab)
                "sqlite" -> SqliteViewerTabBody(model, readOnlyTab)
                "db-query" -> DatabaseQueryTabBody(model, readOnlyTab)
                "db-sql" -> DbSqlObjectTabBody(model, readOnlyTab)
                else -> DatabaseSidebarPane(
                    model = model,
                    onNavigate = onNavigateToWorkspaceTab,
                    readOnly = true,
                )
            }
        }
    }
}

/** How often the Services tab re-reads `/api/system/services`, as the pre-redesign `ServicesPanel`. */
internal const val PHONE_SERVICES_POLL_MS = 3_000L

@Composable
internal fun PhoneMonitorAreaHost(
    model: AppModel,
    tab: WorkspaceTab? = null,
) {
    val monitorState = remember(tab?.id) {
        PhoneMonitorState(
            initialTab = PhoneMonitorTab.entries.firstOrNull { it.id == tab?.metaString("sysmonTab") } ?: PhoneMonitorTab.OVERVIEW,
        )
    }
    val wantProcesses = monitorState.activeTab != PhoneMonitorTab.OVERVIEW &&
        monitorState.activeTab != PhoneMonitorTab.PERFORMANCE

    DisposableEffect(model, wantProcesses) {
        val release = model.retainResourceMonitor(wantProcesses = wantProcesses)
        onDispose { release() }
    }

    var services by remember { mutableStateOf<ServicesSnapshot?>(null) }
    var servicesError by remember { mutableStateOf<String?>(null) }
    val onServicesTab = monitorState.activeTab == PhoneMonitorTab.SERVICES
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model.api, onServicesTab, lifecycle) {
        val api = model.api ?: return@LaunchedEffect
        if (!onServicesTab) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    services = ServicesSnapshot.from(api.systemServices())
                    servicesError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    servicesError = e.message?.takeIf { it.isNotBlank() } ?: "Could not list services"
                }
                delay(PHONE_SERVICES_POLL_MS)
            }
        }
    }

    var serviceDetails by remember { mutableStateOf<ServiceDetails?>(null) }
    val detailTarget = monitorState.selectedServiceForDetail
    LaunchedEffect(model.api, detailTarget?.scope, detailTarget?.unit) {
        serviceDetails = null
        val api = model.api ?: return@LaunchedEffect
        val target = detailTarget ?: return@LaunchedEffect
        serviceDetails = try {
            ServiceDetails.from(api.systemServiceDetails(target.scope, target.unit))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    PhoneSystemMonitorScreen(
        state = monitorState,
        snapshot = model.sharedMetricsSnapshot,
        servicesSnapshot = services,
        serviceDetails = serviceDetails,
        servicesError = servicesError,
    )
}

@Composable
internal fun PhoneSettingsAreaHost(
    model: AppModel,
) {
    val initialRoute = remember(model.requestedSettingsCategory) {
        resolvePhoneSettingsRoute(model.requestedSettingsCategory)
    }

    PhoneSettingsScreen(
        model = model,
        initialRoute = initialRoute,
        modifier = Modifier.testTag("phone-settings-screen"),
    )
}
