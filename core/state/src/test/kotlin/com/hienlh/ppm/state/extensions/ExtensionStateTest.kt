package com.hienlh.ppm.state.extensions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStateTest {

    @Test
    fun `parse server messages and handle unknown gracefully`() {
        val contribMsg = ExtServerMessage.parse(
            """
            {
              "type": "contributions:update",
              "contributions": {
                "commands": [{"command": "git-graph.view", "title": "View Git Graph", "icon": "git-branch", "category": "Git Graph"}],
                "views": {"sidebar": [{"id": "ext.tree", "name": "Explorer", "type": "tree", "icon": "list-ordered"}]}
              },
              "activationErrors": {"bad.ext": "Timed out"}
            }
            """.trimIndent(),
        ) as? ExtServerMessage.ContributionsUpdate
        assertNotNull(contribMsg)
        assertEquals(1, contribMsg!!.contributions.commands.size)
        assertEquals("git-graph.view", contribMsg.contributions.commands[0].command)
        assertEquals("Timed out", contribMsg.activationErrors["bad.ext"])

        val createMsg = ExtServerMessage.parse(
            """
            {
              "type": "webview:create",
              "panelId": "panel-1",
              "extensionId": "@ppm/ext-git-graph",
              "viewType": "git-graph.view",
              "title": "Git Graph",
              "projectPath": "/repo",
              "projectName": "ppm"
            }
            """.trimIndent(),
        ) as? ExtServerMessage.WebviewCreate
        assertNotNull(createMsg)
        assertEquals("panel-1", createMsg!!.panelId)
        assertEquals("git-graph.view", createMsg.viewType)
        assertEquals("ppm", createMsg.projectName)

        val unknownMsg = ExtServerMessage.parse("""{"type":"future:event","foo":"bar"}""")
        assertTrue(unknownMsg is ExtServerMessage.Unknown)
        assertNull(ExtServerMessage.parse("not json"))
    }

    @Test
    fun `client messages serialize to expected wire json`() {
        val ready = Json.parseToJsonElement(ExtClientMessages.ready())
        assertEquals(buildJsonObject { put("type", "ready") }, ready)

        val exec = Json.parseToJsonElement(
            ExtClientMessages.commandExecute("git-graph.view", listOf(JsonPrimitive("/repo"))),
        )
        assertTrue(exec.toString().contains("command:execute"))
        assertTrue(exec.toString().contains("git-graph.view"))

        val qpCancel = Json.parseToJsonElement(ExtClientMessages.quickPickResolve("req-1", null))
        assertTrue(qpCancel.toString().contains("\"selected\":null"))
    }

    @Test
    fun `coordinator tracks local dispatch TTL and recently closed suppression`() {
        val coord = ExtensionCoordinator(dispatchTtlMs = 15_000L, closedTtlMs = 5_000L)

        // Broadcast from another client without local dispatch -> false
        assertFalse(coord.takeLocalDispatch("git-graph", "/repo", nowMs = 1_000L))

        // Local dispatch -> true once, then consumed
        coord.recordCommandDispatch("git-graph.view", "/repo", recovery = false, nowMs = 1_000L)
        assertTrue(coord.takeLocalDispatch("git-graph", "/repo", nowMs = 2_000L))
        assertFalse(coord.takeLocalDispatch("git-graph", "/repo", nowMs = 2_001L))

        // Expired dispatch -> false
        coord.recordCommandDispatch("git-graph.view", "/repo", recovery = false, nowMs = 1_000L)
        assertFalse(coord.takeLocalDispatch("git-graph", "/repo", nowMs = 17_000L))

        // Recently closed suppression within 5s
        coord.recordWebviewClosed("git-graph.view", "ppm", nowMs = 10_000L)
        assertTrue(coord.isRecentlyClosed("git-graph", "ppm", nowMs = 12_000L))
        assertFalse(coord.isRecentlyClosed("git-graph", "ppm", nowMs = 16_000L))

        // Explicit command dispatch clears recently closed suppression for that slug
        coord.recordWebviewClosed("git-graph.view", "ppm", nowMs = 20_000L)
        coord.recordCommandDispatch("git-graph.view", "/repo", recovery = true, nowMs = 21_000L)
        assertFalse(coord.isRecentlyClosed("git-graph", "ppm", nowMs = 21_500L))
        assertTrue(coord.consumeRecovery("git-graph"))
        assertFalse(coord.consumeRecovery("git-graph"))
    }

    @Test
    fun `bridge injects vscode shim and host theme safely`() {
        val raw = "<!DOCTYPE html><html><head><title>Graph</title></head><body>Hi</body></html>"
        val withShim = ExtensionWebviewBridge.injectVscodeApiShim(raw)
        assertTrue(withShim.contains("window.acquireVsCodeApi"))

        val css = ExtensionWebviewBridge.hostThemeCss { name ->
            when (name) {
                "--bg-solid" -> "#141413</style><script>evil()</script>"
                "--accent" -> "#d97757"
                else -> ""
            }
        }
        assertFalse(css.contains("<"))
        assertFalse(css.contains(">"))
        assertTrue(css.contains("--bg: #141413/styleCreatescriptevil()/script;".replace("Createscript", "script")))

        val themed = ExtensionWebviewBridge.injectHostTheme(withShim, "dark", css)
        assertTrue(themed.contains("""<html data-ppm-theme="dark">"""))
        assertTrue(themed.contains("""<style id="ppm-theme-vars">"""))
    }

    @Test
    fun `bridge validates external links and file icon requests`() {
        val valid = Json.parseToJsonElement("""{"command":"__ppm.openExternal","url":"https://github.com/hienlh/ppm"}""")
        val validRes = ExtensionWebviewBridge.externalLinkRequest(valid)
        assertNotNull(validRes)
        assertTrue(validRes!!.isSuccess)
        assertEquals("https://github.com/hienlh/ppm", validRes.getOrNull())

        val invalidScheme = Json.parseToJsonElement("""{"command":"__ppm.openExternal","url":"javascript:alert(1)"}""")
        val invalidRes = ExtensionWebviewBridge.externalLinkRequest(invalidScheme)
        assertNotNull(invalidRes)
        assertTrue(invalidRes!!.isFailure)

        val other = Json.parseToJsonElement("""{"command":"loadRepoInfo"}""")
        assertNull(ExtensionWebviewBridge.externalLinkRequest(other))

        val iconsMsg = Json.parseToJsonElement("""{"command":"__ppm.fileIcons","names":["App.tsx","README.md"]}""")
        assertEquals(listOf("App.tsx", "README.md"), ExtensionWebviewBridge.fileIconsRequest(iconsMsg))
    }

    @Test
    fun `mergeTreeChildren expands target parent node`() {
        val root = listOf(
            TreeItemUI(id = "root-1", label = "Root", collapsibleState = "collapsed"),
        )
        val child = TreeItemUI(id = "child-1", label = "Child", collapsibleState = "none")
        val merged = ExtensionWebviewBridge.mergeTreeChildren(root, "root-1", listOf(child))
        assertEquals("expanded", merged[0].collapsibleState)
        assertEquals(1, merged[0].children?.size)
        assertEquals("child-1", merged[0].children!![0].id)
    }
}
