package com.hienlh.ppm.state.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspProtocolStateTest {

    @Test
    fun `UTF-16 column conversion and bottom-up edits preserve emoji and Vietnamese diacritics`() {
        val line = "const flag = \"🇻🇳 Tiếng Việt\"; // x"
        val cpIndexOfTieng = line.codePointCount(0, line.indexOf("Tiếng"))
        val utf16Col = codePointColumnToUtf16(line, cpIndexOfTieng)
        assertEquals(line.indexOf("Tiếng"), utf16Col)

        // Clamping inside a surrogate pair steps back to the high surrogate
        val insideFirstFlag = line.indexOf("🇻🇳") + 1
        assertEquals(line.indexOf("🇻🇳"), utf16ColumnToCharOffset(line, insideFirstFlag))

        // Bottom-up edit application across lines with emoji
        val doc = "const a = \"🚀\";\nconst b = a + 1;\n"
        val edits = listOf(
            LspTextEdit(
                range = LspRange(LspPosition(0, 11), LspPosition(0, 13)), // 🚀 is 2 UTF-16 chars at [11..13)
                newText = "✨",
            ),
            LspTextEdit(
                range = LspRange(LspPosition(1, 14), LspPosition(1, 15)), // '1' -> '2'
                newText = "2",
            ),
        )
        val updated = applyLspTextEdits(doc, edits)
        assertEquals("const a = \"✨\";\nconst b = a + 2;\n", updated)
    }

    @Test
    fun `LspClientSessionState handles hello reopen, ready, unavailable, resync and retry`() {
        val session = LspClientSessionState("ppm")
        val openMsg = session.openDocument(
            path = "src/index.ts",
            clientUri = "inmemory://model/1",
            version = 1,
            text = "const x = 1;\n",
        )
        assertTrue(openMsg.contains("\"t\":\"open\""))
        assertTrue(session.statusOf("src/index.ts") is LspDocumentStatus.Opening)

        // Edit increments version monotonically
        val changeMsg = session.changeDocumentFull("src/index.ts", "const x: number = 2;\n")
        assertNotNull(changeMsg)
        assertTrue(changeMsg!!.contains("\"version\":2"))

        // Server asks for resync -> client responds with full text at current version
        val resyncEvt = session.onServerMessage("""{"t":"resync","path":"src/index.ts"}""")
        val resync = resyncEvt as LspIncomingEvent.ResyncRequested
        assertNotNull(resync.outboundPayload)
        assertTrue(resync.outboundPayload!!.contains("\"version\":2"))
        assertTrue(resync.outboundPayload!!.contains("const x: number = 2;"))

        // Server reports unavailable (not-installed)
        val unavailEvt = session.onServerMessage(
            """{"t":"unavailable","path":"src/index.ts","reason":"not-installed","message":"TypeScript server not installed","server":{"id":"ts","displayName":"TypeScript","installHint":"bun add -g typescript-language-server","installable":true,"installWith":"npm"}}""",
        )
        val statusChanged = unavailEvt as LspIncomingEvent.StatusChanged
        val unavail = statusChanged.status as LspDocumentStatus.Unavailable
        assertEquals("not-installed", unavail.reason)
        assertEquals("TypeScript", unavail.server?.displayName)

        // Pressing Install triggers retryUnavailableDocuments -> re-sends open with latest version
        val retries = session.retryUnavailableDocuments()
        assertEquals(1, retries.size)
        assertTrue(retries[0].contains("\"version\":2"))
        assertTrue(session.statusOf("src/index.ts") is LspDocumentStatus.Opening)

        // Server reconnects and sends hello -> reopens all documents
        val helloEvt = session.onServerMessage("""{"t":"hello"}""")
        val reopen = helloEvt as LspIncomingEvent.ReopenAll
        assertEquals(1, reopen.outboundPayloads.size)

        // Server reports ready
        val readyEvt = session.onServerMessage(
            """{"t":"ready","path":"src/index.ts","languageId":"typescript","projectPath":"/home/user/ppm","server":{"id":"ts","displayName":"TypeScript","rootPath":"/home/user/ppm"},"capabilities":{}}""",
        )
        val readyStatus = readyEvt as LspIncomingEvent.StatusChanged
        val ready = readyStatus.status as LspDocumentStatus.Ready
        assertEquals("TypeScript", ready.server.displayName)
        assertEquals("/home/user/ppm", ready.projectPath)
    }

    @Test
    fun `publishDiagnostics and ProblemsStoreState treat missing severity consistently as Warning`() {
        val session = LspClientSessionState("ppm")
        session.openDocument("src/app.ts", "inmemory://model/7", 1, "let a = 1;")

        val diagEvt = session.onServerMessage(
            """
            {
              "t": "notification",
              "method": "textDocument/publishDiagnostics",
              "params": {
                "uri": "inmemory://model/7",
                "diagnostics": [
                  {
                    "message": "Type 'string' is not assignable to type 'number'.",
                    "severity": 1,
                    "source": "ts",
                    "code": 2322,
                    "range": {"start":{"line":4,"character":2},"end":{"line":4,"character":8}}
                  },
                  {
                    "message": "Unused variable",
                    "source": "eslint",
                    "range": {"start":{"line":1,"character":0},"end":{"line":1,"character":5}}
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        val published = diagEvt as LspIncomingEvent.DiagnosticsPublished
        assertEquals(2, published.diagnostics.size)
        // Second diagnostic has no severity -> effectiveSeverity must be Warning (2)
        assertEquals(SEVERITY_WARNING, published.diagnostics[1].effectiveSeverity)

        val store = ProblemsStoreState()
        store.publish("ppm", "src/app.ts", published.diagnostics)
        val counts = store.problemCounts("ppm")
        assertEquals(1, counts.errors)
        assertEquals(1, counts.warnings)

        // Filter by rule code "2322"
        val filteredByCode = store.sortedProblems("ppm", "2322")
        assertEquals(1, filteredByCode.size)
        assertEquals(1, filteredByCode[0].diagnostics.size)
        assertEquals("ts(2322) ", filteredByCode[0].diagnostics[0].sourceLabel())
    }

    @Test
    fun `snippet expansion, cross-file definition routing and semantic token delta decoding work`() {
        val (expanded, cursor) = expandSnippetPlaceholders("function \${1:name}(\${2:arg}) {\n\t\$0\n}")
        assertEquals("function name(arg) {\n\t\n}", expanded)
        assertEquals("function name".length, cursor)

        // Same file definition
        val same = resolveDefinitionAction(
            target = LspLocationTarget("inmemory://model/1", LspRange(LspPosition(9, 4), LspPosition(9, 10))),
            currentClientUri = "inmemory://model/1",
            projectPath = "/home/user/ppm",
            projectName = "ppm",
        )
        assertTrue(same is LspNavigationAction.SameFile)

        // Cross-file inside project (with case-insensitive Windows drive letter)
        val crossWin = resolveDefinitionAction(
            target = LspLocationTarget("file:///c:/Projects/ppm/src/utils/helper.ts", LspRange(LspPosition(19, 0), LspPosition(19, 6))),
            currentClientUri = "inmemory://model/1",
            projectPath = "C:\\Projects\\ppm",
            projectName = "ppm",
        )
        val openFile = crossWin as LspNavigationAction.OpenProjectFile
        assertEquals("src/utils/helper.ts", openFile.relativePath)
        assertEquals(20, openFile.lineNumber)

        // Outside project (e.g. node_modules outside root or system SDK) -> IgnoreOutsideProject
        val outside = resolveDefinitionAction(
            target = LspLocationTarget("file:///usr/lib/ts/lib.d.ts", LspRange(LspPosition(0, 0), LspPosition(0, 5))),
            currentClientUri = "inmemory://model/1",
            projectPath = "/home/user/ppm",
            projectName = "ppm",
        )
        assertTrue(outside is LspNavigationAction.IgnoreOutsideProject)

        // Semantic tokens decode + delta
        val legend = SemanticTokensLegend(
            tokenTypes = listOf("variable", "function", "member"),
            tokenModifiers = listOf("declaration", "readonly"),
        )
        val initialData = intArrayOf(
            0, 6, 3, 0, 3, // line 0, char 6, len 3, "variable", declaration+readonly
            1, 4, 5, 2, 0, // line 1, char 4, len 5, "member", no modifiers
        )
        val deltaApplied = applySemanticTokensDelta(
            initialData,
            listOf(SemanticTokensDeltaEdit(start = 5, deleteCount = 5, data = intArrayOf(2, 2, 7, 1, 1))),
        )
        val tokens = decodeSemanticTokens(deltaApplied, legend)
        assertEquals(2, tokens.size)
        assertEquals("variable", tokens[0].tokenType)
        assertEquals(listOf("declaration", "readonly"), tokens[0].modifiers)
        assertEquals(2, tokens[1].line)
        assertEquals("function", tokens[1].tokenType)
    }

    @Test
    fun `offsetToLspPosition and lspPositionToOffset use 0-based UTF-16 with non-ASCII before cursor`() {
        // Line 1 has both multi-byte UTF-8 ('é') and a 2-code-unit UTF-16 surrogate pair ('🚀') before `targetSymbol`
        val text = "// header\nconst msg = \"café 🚀\"; targetSymbol.call()"
        val buffer = EditorBuffer(text)
        val cursorOffset = text.indexOf("targetSymbol") + "target".length

        val lspPos = offsetToLspPosition(buffer, cursorOffset)
        assertEquals(1, lspPos.line)
        // On line 1: `const msg = "café 🚀"; target` -> '🚀' is 2 UTF-16 code units, so UTF-16 column is 29 (code point count would be 28)
        assertEquals("const msg = \"café 🚀\"; target".length, lspPos.character)
        assertEquals(29, lspPos.character)

        // Round-trip back from 0-based UTF-16 LspPosition to EditorBuffer offset
        assertEquals(cursorOffset, lspPositionToOffset(buffer, lspPos))

        // Default completion word range replaces `targetSymbol` even without explicit textEdit
        val replaceRange = defaultCompletionReplaceOffsets(buffer, cursorOffset)
        assertEquals("targetSymbol", buffer.substring(replaceRange.first, replaceRange.last))
    }
}
