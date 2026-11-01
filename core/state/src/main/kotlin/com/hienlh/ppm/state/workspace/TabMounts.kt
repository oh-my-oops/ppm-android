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
fun frontTabs(state: WorkspaceState): List<WorkspaceTab> {
    val seen = HashSet<String>()
    val front = ArrayList<WorkspaceTab>()

    fun collect(panel: WorkspacePanel, project: String? = null) {
        for (tab in panel.tabs) {
            if (tab.id in seen) continue
            if (!tab.projectId.isNullOrEmpty() && !project.isNullOrEmpty() && tab.projectId != project) continue
            seen += tab.id
            if (tab.id == panel.activeTabId) front += tab
        }
    }

    fun grid(rows: List<List<String>>, project: String?) {
        for (panelId in rows.flatten()) state.panels[panelId]?.let { collect(it, project) }
    }

    grid(state.grid, state.currentProject)
    for ((project, rows) in state.projectGrids) if (project != state.currentProject) grid(rows, project)
    for ((panelId, panel) in state.panels) if (isWindowPanelId(panelId)) collect(panel)
    state.dockPanel?.let { collect(it) }
    return front
}
