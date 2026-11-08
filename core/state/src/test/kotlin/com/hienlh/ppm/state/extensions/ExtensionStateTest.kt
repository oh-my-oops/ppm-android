package com.hienlh.ppm.state.extensions

import okhttp3.HttpUrl.Companion.toHttpUrl

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

        val answer = ExtensionWebviewBridge.fileIconsAnswer(
            icons = listOf("App.tsx" to "vsi-file-type-reactts", "__proto__" to "vsi-default-file"),
            rules = listOf("vsi-file-type-reactts" to ".vsi-file-type-reactts { background-image: url(\"data:...\"); }\n"),
        )
        assertEquals("__ppm.fileIcons", (answer["command"] as JsonPrimitive).content)
        val ansIcons = answer["icons"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, ansIcons.size)
        val firstPair = ansIcons[0] as kotlinx.serialization.json.JsonArray
        assertEquals("App.tsx", (firstPair[0] as JsonPrimitive).content)
        assertEquals("vsi-file-type-reactts", (firstPair[1] as JsonPrimitive).content)
        val ansRules = answer["rules"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, ansRules.size)
        val firstRule = ansRules[0] as kotlinx.serialization.json.JsonArray
        assertEquals("vsi-file-type-reactts", (firstRule[0] as JsonPrimitive).content)
        assertTrue((firstRule[1] as JsonPrimitive).content.startsWith(".vsi-file-type-reactts"))
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

    @Test
    fun `QuickPickShow and InputBoxShow resolve superseded requests with null before replacing`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val api = com.hienlh.ppm.client.ApiClient("http://localhost:3210".toHttpUrl(), "tok")
        val sentClientMessages = mutableListOf<String>()
        val client = object : ExtensionClient(api, scope) {
            override fun sendRaw(json: String) {
                sentClientMessages += json
            }
        }
        try {
            val qp1 = ExtServerMessage.parse(
                """{"type":"quickpick:show","requestId":"qp-1","items":[{"label":"one"}]}""",
            )!!
            val qp2 = ExtServerMessage.parse(
                """{"type":"quickpick:show","requestId":"qp-2","items":[{"label":"two"}]}""",
            )!!
            client.handle(qp1)
            assertEquals("qp-1", client.quickPick.value?.requestId)
            assertTrue(sentClientMessages.isEmpty())

            client.handle(qp2)
            assertEquals("qp-2", client.quickPick.value?.requestId)
            assertEquals(1, sentClientMessages.size)
            assertTrue(sentClientMessages[0].contains("\"type\":\"quickpick:resolve\""))
            assertTrue(sentClientMessages[0].contains("\"requestId\":\"qp-1\""))
            assertTrue(sentClientMessages[0].contains("\"selected\":null"))

            val ib1 = ExtServerMessage.parse(
                """{"type":"inputbox:show","requestId":"ib-1","options":{"prompt":"First"}}""",
            )!!
            val ib2 = ExtServerMessage.parse(
                """{"type":"inputbox:show","requestId":"ib-2","options":{"prompt":"Second"}}""",
            )!!
            client.handle(ib1)
            assertEquals("ib-1", client.inputBox.value?.requestId)
            client.handle(ib2)
            assertEquals("ib-2", client.inputBox.value?.requestId)
            assertEquals(2, sentClientMessages.size)
            assertTrue(sentClientMessages[1].contains("\"type\":\"inputbox:resolve\""))
            assertTrue(sentClientMessages[1].contains("\"requestId\":\"ib-1\""))
            assertTrue(sentClientMessages[1].contains("\"value\":null"))
        } finally {
            client.stop()
        }
    }

    @Test
    fun `TreeRefresh removes view from treeViews and ContributionsUpdate emits only new activationErrors`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val api = com.hienlh.ppm.client.ApiClient("http://localhost:3210".toHttpUrl(), "tok")
        val client = ExtensionClient(api, scope)
        val newErrors = mutableListOf<Pair<String, String>>()
        client.onActivationError = { id, err -> newErrors += id to err }
        try {
            client.handle(
                ExtServerMessage.parse(
                    """{"type":"tree:update","viewId":"ext.tree","items":[{"id":"n1","label":"Node 1"}]}""",
                )!!,
            )
            assertEquals(1, client.treeViews.value["ext.tree"]?.size)

            client.handle(
                ExtServerMessage.parse(
                    """{"type":"tree:refresh","viewId":"ext.tree"}""",
                )!!,
            )
            assertNull(client.treeViews.value["ext.tree"])

            client.handle(
                ExtServerMessage.parse(
                    """{"type":"contributions:update","contributions":{},"activationErrors":{"ext.a":"Boom"}}""",
                )!!,
            )
            assertEquals(listOf("ext.a" to "Boom"), newErrors)

            // Repeated update with same ext.a + new ext.b only notifies ext.b
            client.handle(
                ExtServerMessage.parse(
                    """{"type":"contributions:update","contributions":{},"activationErrors":{"ext.a":"Boom","ext.b":"Crash"}}""",
                )!!,
            )
            assertEquals(listOf("ext.a" to "Boom", "ext.b" to "Crash"), newErrors)
        } finally {
            client.stop()
        }
    }

    @Test
    fun `ExternalLinkGate requires recent user gesture and rate-limits openExternal`() {
        val gate = ExternalLinkGate(gestureWindowMs = 2_000L, cooldownMs = 1_000L)
        val linkMsg = Json.parseToJsonElement("""{"command":"__ppm.openExternal","url":"https://github.com/hienlh/ppm"}""")

        // Without gesture -> rejected
        val noGesture = gate.tryConsumeOpenExternal(linkMsg, nowMs = 1_000L)
        assertNotNull(noGesture)
        assertTrue(noGesture!!.isFailure)

        // With recent gesture -> allowed once
        gate.recordUserGesture(nowMs = 2_000L)
        val firstAllowed = gate.tryConsumeOpenExternal(linkMsg, nowMs = 2_500L)
        assertNotNull(firstAllowed)
        assertTrue(firstAllowed!!.isSuccess)
        assertEquals("https://github.com/hienlh/ppm", firstAllowed.getOrNull())

        // Second within cooldownMs -> rate-limited
        val rateLimited = gate.tryConsumeOpenExternal(linkMsg, nowMs = 3_000L)
        assertNotNull(rateLimited)
        assertTrue(rateLimited!!.isFailure)

        // Expired gesture -> rejected
        val expiredGesture = gate.tryConsumeOpenExternal(linkMsg, nowMs = 5_000L)
        assertNotNull(expiredGesture)
        assertTrue(expiredGesture!!.isFailure)
    }

    @Test
    fun `setStateRequest and injectVscodeApiShim persist and seed webview state`() {
        val msg = Json.parseToJsonElement("""{"command":"__ppm.setState","state":{"repo":"/work/app","scroll":42}}""")
        val state = ExtensionWebviewBridge.setStateRequest(msg)
        assertNotNull(state)
        assertEquals("""{"repo":"/work/app","scroll":42}""", state.toString())

        val html = ExtensionWebviewBridge.injectVscodeApiShim("<html><head></head><body></body></html>", state.toString())
        assertTrue(html.contains("""var memState = {"repo":"/work/app","scroll":42};"""))
        assertTrue(html.contains("__ppm.setState"))
    }

    @Test
    fun `headerActionsForView filters view title menus by when clause and commandRunsGit matches only git-graph prefix`() {
        val contrib = ExtensionContributes.from(
            Json.parseToJsonElement(
                """
                {
                  "commands": [
                    {"command": "db.refresh", "title": "Refresh DB", "icon": "refresh"},
                    {"command": "other.add", "title": "Add Other", "icon": "plus"}
                  ],
                  "menus": {
                    "view/title": [
                      {"command": "db.refresh", "when": "view == db.connections"},
                      {"command": "other.add", "when": "view == other.tree"}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        val actions = contrib.headerActionsForView("db.connections")
        assertEquals(1, actions.size)
        assertEquals("db.refresh", actions[0].command)
        assertEquals("Refresh DB", actions[0].title)

        assertTrue(com.hienlh.ppm.state.git.GitRepoScope.commandRunsGit("git-graph.view"))
        assertFalse(com.hienlh.ppm.state.git.GitRepoScope.commandRunsGit("git.other"))
        assertFalse(com.hienlh.ppm.state.git.GitRepoScope.commandRunsGit("db.refresh"))
    }
}
