package com.hienlh.ppm.state.settings

import com.hienlh.ppm.protocol.WireJson
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    fun `12a vsix upload extracts JSON themes and imported themes parse server PpmTheme shape`() {
        val vsixBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("extension/package.json"))
                zos.write(
                    """
                    {
                      "name": "dracula-theme",
                      "contributes": {
                        "themes": [
                          { "label": "Dracula Official", "uiTheme": "vs-dark", "path": "./themes/dracula.json" }
                        ]
                      }
                    }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("extension/themes/dracula.json"))
                zos.write(
                    """{"name":"Dracula","type":"dark","colors":{"editor.background":"#282a36","focusBorder":"#bd93f9"}}"""
                        .toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()
            }
        }.toByteArray()

        val requests = buildThemeFileUploadRequests("dracula.vsix", vsixBytes, nameHint = null)
        assertEquals(1, requests.size)
        assertEquals("upload", requests[0].source)
        assertEquals("Dracula Official", requests[0].name)
        assertTrue(requests[0].value.trimStart().startsWith("{"))
        assertTrue(requests[0].value.contains("#282a36"))

        val serverThemesJson = WireJson.parseOrNull(
            """
            [
              {
                "id": "custom-9f1a",
                "name": "Dracula Official",
                "style": "custom",
                "mode": "dark",
                "tokens": {
                  "bg": "#282a36",
                  "bgSolid": "#282a36",
                  "panel": "#21222c",
                  "text": "#f8f8f2",
                  "accent": "#bd93f9"
                },
                "swatch": ["#282a36", "#bd93f9", "#ff79c6"]
              }
            ]
            """.trimIndent(),
        )
        val themes = parseImportedThemes(serverThemesJson)
        assertEquals(1, themes.size)
        assertEquals("custom-9f1a", themes[0].id)
        assertEquals("Dracula Official", themes[0].name)
        assertEquals("custom", themes[0].style)
        assertEquals("dark", themes[0].mode)
        assertEquals("#282a36", themes[0].tokens["bg"])
        assertEquals(listOf("#282a36", "#bd93f9", "#ff79c6"), themes[0].swatch)
    }

    @Test
    fun `QueryAudit LSP DbDrivers and Relay parsers match server wire format`() {
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
                    {
                      "id": "ts",
                      "displayName": "TypeScript",
                      "languages": ["typescript"],
                      "installed": false,
                      "installHint": "bun add -g typescript-language-server",
                      "installable": true,
                      "installWith": "bun",
                      "removable": false
                    },
                    {
                      "id": "json",
                      "displayName": "JSON",
                      "languages": ["json"],
                      "installed": true,
                      "installHint": "bun add -g vscode-langservers-extracted",
                      "installable": true,
                      "origin": "ppm",
                      "removable": true,
                      "alsoRemoves": ["HTML", "CSS"]
                    }
                  ],
                  "running": [
                    {"key":"ts /srv/app","serverId":"ts","rootPath":"/srv/app","state":"ready","subscribers":1}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(2, lsp.servers.size)
        assertTrue(lsp.servers[0].installable)
        assertEquals("bun", lsp.servers[0].installWith)
        assertTrue(lsp.servers[1].removable)
        assertEquals(listOf("HTML", "CSS"), lsp.servers[1].alsoRemoves)
        assertEquals(1, lsp.running.size)
        assertEquals("ts", lsp.running[0].serverId)
        assertEquals("/srv/app", lsp.running[0].rootPath)
        assertEquals("Installed", lspOriginLabel("ppm", lsp.installDir))
        assertEquals("On PATH", lspOriginLabel("path", lsp.installDir))

        val drivers = parseDbDrivers(
            WireJson.parseOrNull(
                """
                [
                  {
                    "id": "mssql",
                    "displayName": "Microsoft SQL Server",
                    "engines": ["mssql"],
                    "usedFor": "SQL Server connections",
                    "package": "mssql",
                    "version": "11.0.1",
                    "license": "MIT",
                    "homepage": "https://github.com/tediousjs/node-mssql",
                    "state": "installed",
                    "installed": {
                      "version": "11.0.1",
                      "installedAt": "2026-11-08T00:00:00.000Z",
                      "bytes": 7340032,
                      "sha256": "abc123"
                    },
                    "installing": true,
                    "removing": false
                  }
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(1, drivers.size)
        assertEquals(listOf("mssql"), drivers[0].engines)
        assertEquals("mssql", drivers[0].pkg)
        assertEquals("installed", drivers[0].state)
        assertEquals(7340032L, drivers[0].installed?.bytes)
        assertTrue(hasBusyDbDriver(drivers))
        assertEquals("7.0 MB", formatDriverBytes(drivers[0].installed!!.bytes))

        val relay = parseRelayStatus(
            WireJson.parseOrNull(
                """
                {
                  "installed": true,
                  "path": "/home/user/.ppm/mediamtx/bin/mediamtx",
                  "source": "bundled",
                  "pinnedVersion": "v1.11.3",
                  "available": true,
                  "job": {
                    "receivedBytes": 5000000,
                    "totalBytes": 10000000,
                    "done": false,
                    "error": null
                  }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(relay.installed)
        assertTrue(relay.isReady)
        assertTrue(relay.canUninstall)
        assertTrue(relay.isDownloading)
        assertEquals(50, relay.progressPercent)
        assertEquals("v1.11.3", relay.pinnedVersion)
    }
}
