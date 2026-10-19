package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.bool
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.long
import com.hienlh.ppm.protocol.obj
import com.hienlh.ppm.protocol.string
import kotlinx.serialization.json.JsonObject

/** What the web keeps on the server for every device (`/api/settings/ui-prefs`) that this app reads. */
data class UiPrefs(
    /** `recent`, `priority` or `name`; null when never chosen. */
    val projectSortMode: String? = null,
    /** When each project was last opened, epoch ms. */
    val recentOpen: Map<String, Long> = emptyMap(),
    val sidebarCollapsed: Boolean? = null,
    /** In CSS px, 200–600; null when never set or out of range, as the web reads it. */
    val sidebarWidth: Int? = null,
)

suspend fun ApiClient.uiPrefs(): UiPrefs {
    val json = call("GET", "/api/settings/ui-prefs")
    return UiPrefs(
        projectSortMode = json["projectSortMode"].string,
        recentOpen = json["recentOpen"].obj.orEmpty().mapNotNull { (name, at) -> at.long?.let { name to it } }.toMap(),
        sidebarCollapsed = json["sidebarCollapsed"].bool,
        sidebarWidth = json["sidebarWidth"].double?.takeIf { it in 200.0..600.0 }?.toInt(),
    )
}

/** Merges [changes] into the stored prefs; the server ignores keys it does not know. */
suspend fun ApiClient.putUiPrefs(changes: JsonObject) {
    call("PUT", "/api/settings/ui-prefs", body = changes)
}
