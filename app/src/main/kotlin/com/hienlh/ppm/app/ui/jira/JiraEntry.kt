package com.hienlh.ppm.app.ui.jira

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.hienlh.ppm.app.AppModel
import com.hienlh.ppm.app.jira
import com.hienlh.ppm.design.ProductIcon
import com.hienlh.ppm.design.kit.DrawerNavItem

/**
 * The unread count the rail and the phone drawer show beside Jira (`nav-section-rail.tsx`), read
 * once when the entry appears and kept current by the Jira events after that.
 */
@Composable
fun jiraRailCount(model: AppModel): Int {
    val jira = model.jira
    LaunchedEffect(jira, model.events) { jira.bindEvents(model.events) }
    LaunchedEffect(jira, model.api) { jira.loadUnreadCount() }
    return jira.unreadCount
}

/** The phone drawer's Jira row (`mobile-drawer.tsx`), after the built-in sections and only while Jira is on. */
@Composable
fun jiraDrawerItems(model: AppModel, selected: Boolean, onClick: () -> Unit): List<DrawerNavItem> {
    if (!model.jiraEnabled) return emptyList()
    val unread = jiraRailCount(model)
    return listOf(
        DrawerNavItem(
            id = "jira",
            label = "Jira",
            icon = ProductIcon.Bug,
            selected = selected,
            countBadge = unread.takeIf { it > 0 }?.toString(),
            onClick = onClick,
        ),
    )
}
