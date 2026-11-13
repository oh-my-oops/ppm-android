package com.hienlh.ppm.app.ui.phone

import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.state.workspace.BulkClose
import com.hienlh.ppm.state.workspace.WorkspaceTab
import com.hienlh.ppm.state.workspace.bulkCloseIds

/** One entry of a tab's long-press menu in the phone's tab switcher. */
internal data class PhoneTabMenuItem(val id: String, val label: String, val icon: ProductIcon, val bulk: BulkClose? = null)

/**
 * A tab's long-press menu (the pin and close part of `mobile-nav.tsx`'s): Pin or Unpin, Close when
 * the tab can close, then each bulk close that would close some tab of [panelTabs], the tabs shown
 * beside it. Close Saved and Close All come together, offered while Close All would take any.
 */
internal fun phoneTabMenuItems(tab: WorkspaceTab, panelTabs: List<WorkspaceTab>): List<PhoneTabMenuItem> = buildList {
    add(if (tab.pinned) PhoneTabMenuItem("unpin", "Unpin", ProductIcon.PinOff) else PhoneTabMenuItem("pin", "Pin", ProductIcon.Pin))
    if (tab.closable) add(PhoneTabMenuItem("close", "Close", ProductIcon.X))
    fun offers(kind: BulkClose) = bulkCloseIds(panelTabs, tab.id, kind) { false }.isNotEmpty()
    if (offers(BulkClose.OTHERS)) add(PhoneTabMenuItem("close-others", "Close Others", ProductIcon.XSquare, BulkClose.OTHERS))
    if (offers(BulkClose.RIGHT)) add(PhoneTabMenuItem("close-right", "Close to the Right", ProductIcon.ChevronsRight, BulkClose.RIGHT))
    if (offers(BulkClose.ALL)) {
        add(PhoneTabMenuItem("close-saved", "Close Saved", ProductIcon.XSquare, BulkClose.SAVED))
        add(PhoneTabMenuItem("close-all", "Close All", ProductIcon.XSquare, BulkClose.ALL))
    }
}
