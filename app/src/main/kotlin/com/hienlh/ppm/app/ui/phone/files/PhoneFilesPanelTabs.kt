package com.hienlh.ppm.app.ui.phone.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.ui.editor.ProblemsTabBody
import com.hienlh.ppm.app.ui.osexplorer.HostExplorerTab
import com.hienlh.ppm.app.ui.osexplorer.isOsExplorerTab
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.DrawerToolTileItem
import com.hienlh.ppm.state.workspace.WorkspaceTab

/**
 * The tabs the Files area shows in place of a file: Problems (`problems-panel.tsx`, a tab on mobile) and a tab of
 * the host's File Explorer, both in the tablet's own views, which follow the phone layout.
 */
internal fun isPhoneFilesPanelTab(tab: WorkspaceTab): Boolean =
    tab.type == "problems" || tab.type == "host-explorer" || isOsExplorerTab(tab)

/** A `search` tab opens the project's search and replace, the web's sidebar Search panel. */
internal fun isPhoneSearchTab(tab: WorkspaceTab?): Boolean = tab?.type == "search"

@Composable
internal fun PhoneFilesPanelTab(model: AppModel, tab: WorkspaceTab) {
    Box(Modifier.fillMaxSize().testTag("phone-files-panel-${tab.type}")) {
        if (tab.type == "problems") {
            ProblemsTabBody(model, tab)
        } else {
            key(tab.id) { HostExplorerTab(model, tab) }
        }
    }
}

/** The drawer's tile for the host's File Explorer (`mobile-drawer.tsx`'s Files tile): the full-screen sheet. */
internal fun fileExplorerDrawerTile(onClick: () -> Unit): DrawerToolTileItem = DrawerToolTileItem(
    id = "file-explorer",
    title = "File Explorer",
    subtitle = "Host files",
    icon = ProductIcon.FolderTree,
    onClick = onClick,
)
