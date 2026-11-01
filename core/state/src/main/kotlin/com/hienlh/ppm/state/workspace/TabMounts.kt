package com.hienlh.ppm.state.workspace

/**
 * The tabs the web's tab pool mounts when it first sees them (`TabPool`, tab-pool.tsx, with `collectTabEntries` in
 * tab-pool-collect.ts and the `isActive` half of `filterMountableEntries`): the front tab of every panel there is.
 *
 * "Every panel there is" is not "every panel on screen". The open project's grid, the grids kept for the projects seen before
 * it, the panels of the floating windows (a minimized window's too) and the dock all count, and the dock's front tab counts
 * whether the dock is shown or not: a phone always restores it collapsed, a tablet may have it hidden. A tab that is behind
 * another is not here, so a saved workspace with dozens of tabs does not start them all at once. What the web does with a tab
 * once it has been in front (it stays mounted, whatever comes to the front later: `useMountedTabsStore`) is for whoever holds
 * what the tab mounts, a shell's socket say; this says only which tabs come to the front.
 *
 * The panels are read in the web's order, so a tab that is in two of them at once (a move that is still under way) belongs to the
 * first: the open project's grid, then the other projects', the windows', the dock's. A grid does not hold another project's tab
 * (the web guards a race in `openTab` while a project is switched); the windows and the dock are shared by every project and hold
 * whatever is put in them. Extension tabs, which the web leaves unmounted outside the open project, do not exist here.
 */
fun frontTabs(state: WorkspaceState): List<WorkspaceTab> = panelTabs(state).filter { it.inFront }.map { it.tab }

/**
 * Every tab of those same panels, the ones behind another tab included, read the same way: the same panels in the same order,
 * the same guard, a tab that is in two panels once. This is deliberately more than the web mounts. The web mounts only
 * [frontTabs] and a tab behind another stays unmounted until it first comes to the front, so after a reload its shell has no
 * socket, the server ends it once its 30 minutes for a shell with no socket are up (`RECONNECT_GRACE_MS`), and the next visit to
 * the tab opens a new shell without a word. Whoever keeps something alive for a tab asks for these.
 */
fun heldTabs(state: WorkspaceState): List<WorkspaceTab> = panelTabs(state).map { it.tab }

private class PanelTab(val tab: WorkspaceTab, val inFront: Boolean)

/** Each tab of the panels there are, once, with whether its panel has it in front. */
private fun panelTabs(state: WorkspaceState): List<PanelTab> {
    val seen = HashSet<String>()
    val tabs = ArrayList<PanelTab>()

    fun collect(panel: WorkspacePanel, project: String? = null) {
        for (tab in panel.tabs) {
            if (tab.id in seen) continue
            if (!tab.projectId.isNullOrEmpty() && !project.isNullOrEmpty() && tab.projectId != project) continue
            seen += tab.id
            tabs += PanelTab(tab, tab.id == panel.activeTabId)
        }
    }

    fun grid(rows: List<List<String>>, project: String?) {
        for (panelId in rows.flatten()) state.panels[panelId]?.let { collect(it, project) }
    }

    grid(state.grid, state.currentProject)
    for ((project, rows) in state.projectGrids) if (project != state.currentProject) grid(rows, project)
    for ((panelId, panel) in state.panels) if (isWindowPanelId(panelId)) collect(panel)
    state.dockPanel?.let { collect(it) }
    return tabs
}
