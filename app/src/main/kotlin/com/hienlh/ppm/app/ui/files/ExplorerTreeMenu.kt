package com.hienlh.ppm.app.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.hienlh.ppm.app.ui.BottomSheet
import com.hienlh.ppm.app.ui.ContextMenuPopup
import com.hienlh.ppm.app.ui.MenuIcon
import com.hienlh.ppm.app.ui.MenuItem
import com.hienlh.ppm.app.ui.SheetLabel
import com.hienlh.ppm.app.ui.SheetRow
import com.hienlh.ppm.design.LocalPpmMobile
import com.hienlh.ppm.design.LocalPpmTokens
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.cssScrollY

/** Supply only implemented actions; the tree does not manufacture unavailable CRUD commands. */
data class ExplorerTreeAction(
    val label: String,
    val icon: ProductIcon? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
internal fun ExplorerTreeMenu(name: String, at: Offset, actions: List<ExplorerTreeAction>, onDismiss: () -> Unit) {
    if (LocalPpmMobile.current) {
        val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.6f }
        BottomSheet(onDismiss) {
            Column(Modifier.heightIn(max = maxHeight).cssScrollY()) {
                SheetLabel(name)
                actions.forEach { action ->
                    SheetRow(action.label, action.icon, action.destructive, onClick = { onDismiss(); action.onClick() })
                }
            }
        }
    } else {
        val tokens = LocalPpmTokens.current
        ContextMenuPopup(at, onDismiss) {
            actions.forEach { action ->
                MenuItem(
                    action.label,
                    onClick = { onDismiss(); action.onClick() },
                    icon = action.icon?.let { icon -> { MenuIcon(icon, if (action.destructive) tokens.error else null) } },
                    color = if (action.destructive) tokens.error else null,
                    highlight = if (action.destructive) tokens.error.opacity(0.1) else null,
                )
            }
        }
    }
}
