package com.hienlh.ppm.app.ui.phone.areas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.AiResourcesSidebarPanel
import com.hienlh.ppm.app.ui.GroupChatBody
import com.hienlh.ppm.app.ui.GroupList
import com.hienlh.ppm.app.ui.androiddevice.AndroidTabBody
import com.hienlh.ppm.app.ui.design.DesignTabBody
import com.hienlh.ppm.app.ui.design.DesignsSidebarPane
import com.hienlh.ppm.app.ui.editor.AiResourceEditorBody
import com.hienlh.ppm.app.ui.extensions.ExtensionTreeViewPanel
import com.hienlh.ppm.app.ui.extensions.ExtensionWebviewBody
import com.hienlh.ppm.app.ui.phone.PhoneArea
import com.hienlh.ppm.app.ui.phone.PhoneNav
import com.hienlh.ppm.app.ui.remotedesktop.RemoteDesktopTab
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.DrawerNavItem
import com.hienlh.ppm.design.kit.DrawerToolTileItem
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * The phone's Designs, Teams, AI Resources, Extensions, Remote Desktop and Android areas: what the
 * web's mobile drawer shows as a section (a list) or opens full screen (Remote, Android), and the
 * tab each of them opens, full screen too. A tablet shows the same composables in its sidebar,
 * its tabs and its floating windows.
 */
@Composable
internal fun PhoneFeatureAreaBody(model: AppModel, area: PhoneArea, tab: WorkspaceTab?, nav: PhoneNav) {
    // A list's pick opens or brings forward a tab; the phone then shows it (the drawer's `onNavigate`).
    val showActiveTab: () -> Unit = { model.activeTabId?.let(nav::visitTab) }
    Box(Modifier.fillMaxSize().testTag("phone-${area.id}-screen")) {
        when (area) {
            PhoneArea.DESIGNS -> if (tab?.type == "design") {
                DesignTabBody(model, tab)
            } else {
                FollowOpenedTab(model, nav)
                DesignsSidebarPane(model, onNavigate = showActiveTab)
            }
            PhoneArea.TEAMS -> if (tab?.type == "group") {
                GroupChatBody(model, tab)
            } else {
                FollowOpenedTab(model, nav)
                GroupList(model, onNavigate = showActiveTab)
            }
            PhoneArea.AI_RESOURCES -> if (tab?.type == "ai-resource") {
                AiResourceEditorBody(model, tab)
            } else {
                FollowOpenedTab(model, nav)
                AiResourcesSidebarPanel(model, onNavigate = showActiveTab)
            }
            PhoneArea.EXTENSIONS -> {
                val viewId = extensionViewOf(nav.currentTabId)
                when {
                    tab?.type == "extension" -> ExtensionWebviewBody(model, tab)
                    viewId != null -> {
                        // A tree item's command can open a webview panel: it is shown as it opens.
                        FollowOpenedTab(model, nav)
                        ExtensionTreeViewPanel(model, viewId, extensionViewName(model, viewId))
                    }
                    else -> PhoneExtensionsScreen(model)
                }
            }
            PhoneArea.REMOTE_DESKTOP -> if (tab != null) {
                RemoteDesktopTab(model, tab.id)
            } else {
                LaunchedEffect(Unit) { openPhoneFeatureItem(model, PhoneArea.REMOTE_DESKTOP.id, nav) }
            }
            PhoneArea.ANDROID -> if (tab != null) {
                AndroidTabBody(model, tab)
            } else {
                LaunchedEffect(Unit) { openPhoneFeatureItem(model, PhoneArea.ANDROID.id, nav) }
            }
            else -> Unit
        }
    }
}

/**
 * While a list is on screen, a tab that comes to the front (a dialog's "Create", an extension's
 * command) is shown: a phone shows one thing at a time, and the list is what opened it.
 */
@Composable
private fun FollowOpenedTab(model: AppModel, nav: PhoneNav) {
    val active = model.activeTabId
    val shownWith = remember { active }
    LaunchedEffect(active) {
        if (active != null && active != shownWith) nav.visitTab(active)
    }
}

/** The phone route of an extension's sidebar view: `phone:ext:<view id>`. */
internal fun extensionViewTabId(viewId: String): String =
    PhoneNav.SYNTHETIC_AREA_PREFIX + PhoneArea.EXTENSION_VIEW_PREFIX + viewId

/** The view a `phone:ext:<view id>` route shows, or null for any other route. */
internal fun extensionViewOf(tabId: String?): String? {
    val prefix = PhoneNav.SYNTHETIC_AREA_PREFIX + PhoneArea.EXTENSION_VIEW_PREFIX
    return tabId?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
}

@Composable
private fun extensionViewName(model: AppModel, viewId: String): String {
    val contributions = model.extensions?.contributions?.collectAsState()?.value
    return contributions?.views?.values?.flatten()?.firstOrNull { it.id == viewId }?.name ?: viewId
}

/**
 * Opens one of these areas from the drawer: a section's list, an extension's view, or the one
 * Remote Desktop / Android tab (brought forward when it is open: the web's mobile Remote Desktop
 * is a single sheet, and `openAndroid` focuses the open tab).
 */
internal fun openPhoneFeatureItem(model: AppModel, id: String, nav: PhoneNav) {
    if (id.startsWith(PhoneArea.EXTENSION_VIEW_PREFIX)) {
        nav.visitTab(extensionViewTabId(id.removePrefix(PhoneArea.EXTENSION_VIEW_PREFIX)))
        return
    }
    when (val area = PhoneArea.fromId(id)) {
        PhoneArea.DESIGNS, PhoneArea.TEAMS, PhoneArea.AI_RESOURCES, PhoneArea.EXTENSIONS ->
            nav.visitTab(PhoneNav.areaTabId(area))
        PhoneArea.REMOTE_DESKTOP -> {
            val open = model.openTabs.lastOrNull { it.first.type == "remote-desktop" }
            if (open != null) model.activate(open.first.id, open.second) else model.openRemoteDesktop()
            model.activeTabId?.let(nav::visitTab)
        }
        PhoneArea.ANDROID -> {
            model.openAndroid()
            model.activeTabId?.let(nav::visitTab)
        }
        else -> Unit
    }
}

/**
 * A section asked for by id (`showSidebarSection`: the palette's "Designs", a link): shown when it
 * is one of these areas. False for any other id, which the caller handles.
 */
internal fun showPhoneFeatureSection(id: String, nav: PhoneNav): Boolean {
    val handled = id.startsWith(PhoneArea.EXTENSION_VIEW_PREFIX) || id in DRAWER_SECTIONS
    if (handled) openPhoneFeatureSectionId(id, nav)
    return handled
}

private fun openPhoneFeatureSectionId(id: String, nav: PhoneNav) {
    if (id.startsWith(PhoneArea.EXTENSION_VIEW_PREFIX)) {
        nav.visitTab(extensionViewTabId(id.removePrefix(PhoneArea.EXTENSION_VIEW_PREFIX)))
    } else {
        PhoneArea.fromId(id)?.let { nav.visitTab(PhoneNav.areaTabId(it)) }
    }
}

/** The drawer sections these areas show, in the web's catalog order (`BUILTIN_SIDEBAR_TABS`). */
private val DRAWER_SECTIONS = listOf("teams", "designs", "ai-resources")

/**
 * The drawer rows of the sections the web's mobile drawer offers and the phone shows here: Teams,
 * Designs and AI Resources in the user's rail order, then every extension's sidebar view.
 */
@Composable
internal fun phoneFeatureDrawerItems(
    model: AppModel,
    activeArea: PhoneArea?,
    currentTabId: String?,
    onClick: (String) -> Unit,
): List<DrawerNavItem> {
    val currentView = extensionViewOf(currentTabId)
    val sections = model.sidebarSections.filter { it in DRAWER_SECTIONS }
    val builtIn = sections.map { id ->
        val area = PhoneArea.fromId(id)
        DrawerNavItem(
            id = id,
            label = area?.title ?: id,
            icon = when (id) {
                "teams" -> ProductIcon.Users
                "designs" -> ProductIcon.Palette
                else -> ProductIcon.Sparkles
            },
            selected = activeArea == area && currentView == null,
            onClick = { onClick(id) },
        )
    }
    val contributions = model.extensions?.contributions?.collectAsState()?.value
    val views = contributions?.sidebarViews().orEmpty().map { view ->
        val id = PhoneArea.EXTENSION_VIEW_PREFIX + view.id
        DrawerNavItem(
            id = id,
            label = view.name,
            icon = ProductIcon.Puzzle,
            selected = currentView == view.id,
            onClick = { onClick(id) },
        )
    }
    return builtIn + views
}

/**
 * The drawer's tiles for what the web's mobile drawer footer opens full screen: Remote and
 * Android while the host offers them, and the extensions manager (Settings → Extensions on the
 * web, which a phone reaches from here).
 */
@Composable
internal fun phoneFeatureDrawerTiles(model: AppModel, onClick: (String) -> Unit): List<DrawerToolTileItem> = buildList {
    if (model.remoteDesktopAvailable) {
        add(DrawerToolTileItem(PhoneArea.REMOTE_DESKTOP.id, "Remote", "Desktop", ProductIcon.MonitorSmartphone) { onClick(PhoneArea.REMOTE_DESKTOP.id) })
    }
    if (model.androidAvailable) {
        add(DrawerToolTileItem(PhoneArea.ANDROID.id, "Android", "Device", ProductIcon.Smartphone) { onClick(PhoneArea.ANDROID.id) })
    }
    add(DrawerToolTileItem(PhoneArea.EXTENSIONS.id, "Extensions", "Manage", ProductIcon.Puzzle) { onClick(PhoneArea.EXTENSIONS.id) })
}
