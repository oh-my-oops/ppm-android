package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCommonStateTest {

    @Test
    fun `SettingsCategoryId resolves known ids and falls back to GENERAL on unknown`() {
        assertEquals(SettingsCategoryId.GENERAL, SettingsCategoryId.resolve(null))
        assertEquals(SettingsCategoryId.GENERAL, SettingsCategoryId.resolve(""))
        assertEquals(SettingsCategoryId.GENERAL, SettingsCategoryId.resolve("unknown-cat"))
        assertEquals(SettingsCategoryId.APPEARANCE, SettingsCategoryId.resolve("appearance"))
        assertEquals(SettingsCategoryId.QUERY_AUDIT, SettingsCategoryId.resolve("query-audit"))
        assertEquals(SettingsCategoryId.LANGUAGE_SERVERS, SettingsCategoryId.resolve("language-servers"))
        assertEquals(SettingsCategoryId.DATABASE_DRIVERS, SettingsCategoryId.resolve("database-drivers"))
        assertEquals(SettingsCategoryId.REMOTE_DESKTOP, SettingsCategoryId.resolve("remote-desktop"))
    }

    @Test
    fun `UiPrefs parser and patch builder roundtrip whitelisted fields`() {
        val raw = WireJson.parseOrNull(
            """
            {
              "wordWrap": false,
              "tabWrap": true,
              "keepScreenAwake": true,
              "editorTabStyle": "pill",
              "explorerSkin": "macos",
              "inlineBlame": false
            }
            """.trimIndent(),
        )
        val parsed = parseUiPrefs(raw)
        assertFalse(parsed.wordWrap)
        assertTrue(parsed.tabWrap)
        assertTrue(parsed.keepScreenAwake)
        assertEquals(EditorTabStyle.PILL, parsed.editorTabStyle)
        assertEquals(ExplorerSkinMode.MACOS, parsed.explorerSkin)
        assertFalse(parsed.inlineBlame)

        val patch = buildUiPrefsPatch(mapOf("tabWrap" to true, "editorTabStyle" to "boxed"))
        assertEquals("{\"tabWrap\":true,\"editorTabStyle\":\"boxed\"}", patch.toString())
    }

    @Test
    fun `Password and device name validators enforce server rules`() {
        assertNull(validateDeviceName("My Workstation"))
        assertNotNull(validateDeviceName("x".repeat(101)))

        assertEquals("Password is required", validatePasswordChange("   ", "   "))
        assertEquals("Password must be at least 4 characters", validatePasswordChange("abc", "abc"))
        assertEquals("Passwords do not match", validatePasswordChange("secret1", "secret2"))
        assertNull(validatePasswordChange("secret1", "secret1"))
    }

    @Test
    fun `Keybindings formatting system reserved detection and overrides`() {
        assertEquals("Ctrl+Shift+E", formatKeyCombo("Mod+Shift+E"))
        assertTrue(isSystemReservedCombo("Mod+W"))
        assertTrue(isSystemReservedCombo("Ctrl+T"))
        assertFalse(isSystemReservedCombo("Mod+Shift+P"))

        val overrides = parseKeybindingOverrides(
            WireJson.parseOrNull("""{"command-palette":"Mod+Shift+P","open-chat":"Alt+C"}"""),
        )
        val cmdAction = KEY_ACTIONS.first { it.id == "command-palette" }
        assertEquals("Mod+Shift+P", effectiveKeyCombo(cmdAction, overrides))
    }

    @Test
    fun `FileFilters global and project parsers and pattern list helpers`() {
        val global = parseGlobalFileFilters(
            WireJson.parseOrNull("""{"filesExclude":[".git","*.tmp"],"searchExclude":["node_modules"],"useIgnoreFiles":false}"""),
        )
        assertEquals(listOf(".git", "*.tmp"), global.filesExclude)
        assertEquals(listOf("node_modules"), global.searchExclude)
        assertFalse(global.useIgnoreFiles)

        val project = parseProjectFileFilters(
            WireJson.parseOrNull("""{"files":{"filesExclude":[".git","build"],"useIgnoreFiles":true}}"""),
            globalFallback = global,
        )
        assertEquals(listOf(".git", "build"), project.filesExclude)
        assertEquals(listOf("node_modules"), project.searchExclude)
        assertTrue(project.useIgnoreFiles)

        assertNull(addExcludePattern(listOf(".git"), "  .git "))
        assertEquals(listOf(".git", "dist"), addExcludePattern(listOf(".git"), " dist "))
        assertEquals(listOf(".git"), removeExcludePattern(listOf(".git", "dist"), "dist"))
    }

    @Test
    fun `QueryAudit LSP DbDrivers and Relay parsers`() {
        val audit = parseQueryAuditState(
            WireJson.parseOrNull("""{"retention_days":30,"max_size_mb":250,"size_bytes":1048576,"entry_count":42}"""),
        )
        assertEquals(30, audit.retentionDays)
        assertEquals(250, audit.maxSizeMb)
        assertEquals(42L, audit.entryCount)
        assertEquals("1.0 MB", formatAuditByteSize(audit.sizeBytes))
        assertNull(validateQueryAuditInputs("0", "100"))
        assertNull(validateQueryAuditInputs("30", "5"))
        assertEquals(30 to 100, validateQueryAuditInputs("30", "100"))

        val lsp = parseLspServersOverview(
            WireJson.parseOrNull(
                """
                {
                  "installDir": "/home/user/.ppm/lsp",
                  "servers": [
                    {"id":"ts","displayName":"TypeScript","languages":["typescript"],"installed":true,"origin":"ppm","removable":true,"alsoRemoves":["js"]}
                  ],
                  "running": [
                    {"id":"ts","rootPath":"/srv/app","ready":true}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(1, lsp.servers.size)
        assertTrue(lsp.servers[0].removable)
        assertEquals(listOf("js"), lsp.servers[0].alsoRemoves)
        assertEquals("Installed by PPM in /home/user/.ppm/lsp", lspOriginLabel("ppm", lsp.installDir))

        val drivers = parseDbDrivers(
            WireJson.parseOrNull(
                """
                [
                  {"id":"mssql","displayName":"SQL Server","dbTypes":["mssql"],"npmPackage":"mssql","version":"11.0.1","approxBytes":7340032,"status":"installing"}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(1, drivers.size)
        assertTrue(hasBusyDbDriver(drivers))
        assertEquals("7.0 MB", formatDriverBytes(drivers[0].approxBytes))

        val relay = parseRelayStatus(
            WireJson.parseOrNull("""{"status":"ready","source":"bundled","path":"/home/user/.ppm/bin/mediamtx"}"""),
        )
        assertTrue(relay.isReady)
        assertTrue(relay.canUninstall)
    }
}
