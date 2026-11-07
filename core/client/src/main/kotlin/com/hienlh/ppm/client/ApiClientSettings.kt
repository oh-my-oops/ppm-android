package com.hienlh.ppm.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Settings REST client (`Phase 12a`: General, Appearance, Keyboard Shortcuts, File Filters,
 * Query Audit Log, Language Servers, Database Drivers, Server Upgrade).
 */

/** Stored UI preferences (`GET /api/settings/ui-prefs`). */
suspend fun ApiClient.settingsUiPrefs(): JsonElement =
    call("GET", "/api/settings/ui-prefs")

/** Merge-patch UI preferences (`PUT /api/settings/ui-prefs`). */
suspend fun ApiClient.settingsPutUiPrefs(patch: JsonObject): JsonElement =
    call("PUT", "/api/settings/ui-prefs", body = patch)

/** Update the server's device name (`PUT /api/settings/device-name`). */
suspend fun ApiClient.settingsPutDeviceName(deviceName: String): JsonElement =
    call(
        "PUT",
        "/api/settings/device-name",
        body = buildJsonObject { put("device_name", deviceName) },
    )

/** Change the server access password (`PUT /api/settings/auth/password`). */
suspend fun ApiClient.settingsChangePassword(password: String, confirm: String): JsonElement =
    call(
        "PUT",
        "/api/settings/auth/password",
        body = buildJsonObject {
            put("password", password)
            put("confirm", confirm)
        },
    )

/** List imported custom themes (`GET /api/settings/themes`). */
suspend fun ApiClient.settingsListThemes(): JsonElement =
    call("GET", "/api/settings/themes")

/** Import custom theme(s) (`POST /api/settings/themes` with `source`: `json`|`url`|`vsix`|`upload`). */
suspend fun ApiClient.settingsImportTheme(
    source: String,
    value: String,
    name: String? = null,
): JsonElement =
    call(
        "POST",
        "/api/settings/themes",
        body = buildJsonObject {
            put("source", source)
            put("value", value)
            if (!name.isNullOrBlank()) put("name", name)
        },
    )

/** Rename an imported custom theme (`PATCH /api/settings/themes/:id`). */
suspend fun ApiClient.settingsRenameTheme(id: String, name: String): JsonElement =
    call(
        "PATCH",
        "/api/settings/themes/${ApiClient.escape(id)}",
        body = buildJsonObject { put("name", name) },
    )

/** Delete an imported custom theme (`DELETE /api/settings/themes/:id`). */
suspend fun ApiClient.settingsDeleteTheme(id: String): JsonElement =
    call("DELETE", "/api/settings/themes/${ApiClient.escape(id)}")

/** User keybinding overrides (`GET /api/settings/keybindings`). */
suspend fun ApiClient.settingsKeybindings(): JsonElement =
    call("GET", "/api/settings/keybindings")

/** Save or reset keybinding overrides (`PUT /api/settings/keybindings`, `null` value resets to default). */
suspend fun ApiClient.settingsPutKeybindings(patch: Map<String, String?>): JsonElement =
    call(
        "PUT",
        "/api/settings/keybindings",
        body = buildJsonObject {
            for ((actionId, combo) in patch) {
                if (combo == null) put(actionId, JsonNull) else put(actionId, combo)
            }
        },
    )

/** Global file filter settings (`GET /api/settings/files`). */
suspend fun ApiClient.settingsGlobalFiles(): JsonElement =
    call("GET", "/api/settings/files")

/** Update global file filter settings (`PATCH /api/settings/files`). */
suspend fun ApiClient.settingsPatchGlobalFiles(
    filesExclude: List<String>? = null,
    searchExclude: List<String>? = null,
    useIgnoreFiles: Boolean? = null,
): JsonElement =
    call(
        "PATCH",
        "/api/settings/files",
        body = buildJsonObject {
            if (filesExclude != null) {
                put("filesExclude", JsonArray(filesExclude.map(::JsonPrimitive)))
            }
            if (searchExclude != null) {
                put("searchExclude", JsonArray(searchExclude.map(::JsonPrimitive)))
            }
            if (useIgnoreFiles != null) {
                put("useIgnoreFiles", useIgnoreFiles)
            }
        },
    )

/** Per-project settings (`GET /api/projects/:name/settings`). */
suspend fun ApiClient.settingsProjectFiles(projectName: String): JsonElement =
    call("GET", "/api/projects/${ApiClient.escape(projectName)}/settings")

/** Update per-project file filter settings (`PATCH /api/projects/:name/settings`). */
suspend fun ApiClient.settingsPatchProjectFiles(
    projectName: String,
    filesExclude: List<String>? = null,
    searchExclude: List<String>? = null,
    useIgnoreFiles: Boolean? = null,
): JsonElement =
    call(
        "PATCH",
        "/api/projects/${ApiClient.escape(projectName)}/settings",
        body = buildJsonObject {
            put(
                "files",
                buildJsonObject {
                    if (filesExclude != null) {
                        put("filesExclude", JsonArray(filesExclude.map(::JsonPrimitive)))
                    }
                    if (searchExclude != null) {
                        put("searchExclude", JsonArray(searchExclude.map(::JsonPrimitive)))
                    }
                    if (useIgnoreFiles != null) {
                        put("useIgnoreFiles", useIgnoreFiles)
                    }
                },
            )
        },
    )

/** Query audit log settings and disk usage (`GET /api/settings/query-audit`). */
suspend fun ApiClient.settingsQueryAudit(): JsonElement =
    call("GET", "/api/settings/query-audit")

/** Update query audit log retention and size cap (`PUT /api/settings/query-audit`). */
suspend fun ApiClient.settingsPutQueryAudit(
    retentionDays: Int? = null,
    maxSizeMb: Int? = null,
): JsonElement =
    call(
        "PUT",
        "/api/settings/query-audit",
        body = buildJsonObject {
            if (retentionDays != null) put("retention_days", retentionDays)
            if (maxSizeMb != null) put("max_size_mb", maxSizeMb)
        },
    )

/** Wipe all recorded SQL query audit logs (`DELETE /api/settings/query-audit/logs`). */
suspend fun ApiClient.settingsClearQueryAuditLogs(): JsonElement =
    call("DELETE", "/api/settings/query-audit/logs")

/** Machine-wide LSP servers and availability (`GET /api/lsp/servers`). */
suspend fun ApiClient.lspGlobalServers(): JsonElement =
    call("GET", "/api/lsp/servers")

/** Install a language server on the host (`POST /api/lsp/install`). */
suspend fun ApiClient.lspGlobalInstall(serverId: String): JsonElement =
    call(
        "POST",
        "/api/lsp/install",
        body = buildJsonObject { put("serverId", serverId) },
    )

/** Uninstall a PPM-installed language server (`POST /api/lsp/uninstall`). */
suspend fun ApiClient.lspGlobalUninstall(serverId: String): JsonElement =
    call(
        "POST",
        "/api/lsp/uninstall",
        body = buildJsonObject { put("serverId", serverId) },
    )

/** Database drivers catalog and status (`GET /api/db/drivers`). */
suspend fun ApiClient.dbListDrivers(): JsonElement =
    call("GET", "/api/db/drivers")

/** Install a database driver (`POST /api/db/drivers/:id/install`). */
suspend fun ApiClient.dbInstallDriver(driverId: String): JsonElement =
    call("POST", "/api/db/drivers/${ApiClient.escape(driverId)}/install")

/** Uninstall a database driver (`DELETE /api/db/drivers/:id`). */
suspend fun ApiClient.dbUninstallDriver(driverId: String): JsonElement =
    call("DELETE", "/api/db/drivers/${ApiClient.escape(driverId)}")

