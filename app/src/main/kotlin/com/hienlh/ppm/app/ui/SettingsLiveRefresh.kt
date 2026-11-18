package com.hienlh.ppm.app.ui

import com.hienlh.ppm.app.AppModel

/**
 * The `settings:changed` sections each Settings category shows (`useSettingsChanged` in its
 * section): a change to one from another device, the CLI or the Assistant makes the open pane
 * read its values again.
 */
internal fun settingsSectionsShown(category: SettingsCategory): List<String> = when (category) {
    SettingsCategory.Ai, SettingsCategory.Tools -> listOf("ai")
    SettingsCategory.Files -> listOf("files")
    SettingsCategory.Notifications -> listOf("notifications", "telegram", "clawbot")
    SettingsCategory.Mcp, SettingsCategory.AiResources -> listOf("mcp")
    SettingsCategory.Accounts -> listOf("accounts")
    SettingsCategory.Proxy -> listOf("proxy")
    SettingsCategory.QueryAudit -> listOf("query-audit", "session-trace")
    SettingsCategory.Shortcuts -> listOf("keybindings")
    else -> emptyList()
}

/**
 * Moves whenever what [category]'s pane shows changed elsewhere; the pane is drawn again under it,
 * so it reads its values afresh (an edit still unsaved there is dropped, as a reload drops it).
 */
internal fun settingsLiveRevision(model: AppModel, category: SettingsCategory): Int =
    model.liveSync.revision(settingsSectionsShown(category)) +
        if (category == SettingsCategory.Extensions) model.liveSync.extensionsRevision else 0

/** The phone's full pages: Storage shows the session trace, which has no tablet category. */
internal fun phoneSettingsLiveRevision(model: AppModel, category: SettingsCategory?, storage: Boolean): Int =
    (category?.let { settingsLiveRevision(model, it) } ?: 0) + if (storage) model.liveSync.revision(listOf("session-trace")) else 0
