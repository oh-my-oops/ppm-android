package com.hienlh.ppm.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockActionsTest {
    @Test
    fun `only the web's four explicit shell languages offer the terminal action`() {
        for (language in listOf("bash", "sh", "shell", "zsh")) {
            assertTrue(language, isTerminalCodeBlock("echo hello", language))
        }
        for (language in listOf("BASH", "Shell", " bash", "sh ", "fish", "powershell", "console", "python", "mermaid")) {
            assertFalse(language, isTerminalCodeBlock("$ echo hello", language))
        }
    }

    @Test
    fun `only an unlabeled block starting with dollar has the prompt fallback`() {
        for (language in listOf(null, "")) {
            assertTrue(isTerminalCodeBlock("$ bun test", language))
            assertTrue(isTerminalCodeBlock("${'$'}HOME/bin/tool", language))
            for (code in listOf("", "echo hello", " $ bun test", "\n$ bun test", "# comment\n$ bun test")) {
                assertFalse(code, isTerminalCodeBlock(code, language))
            }
        }
    }

    @Test
    fun `prompt markers on each line and the final newline are removed without changing shell content`() {
        assertEquals("printf '道😀'\n\n  echo done", normalizeCodeBlockCommand("  $ printf '道😀'\n\n  echo done\n\t "))
        assertEquals("cd app\nbun test", normalizeCodeBlockCommand("$ cd app\n\t$\tbun test\n"))
        assertEquals("first\r\nsecond", normalizeCodeBlockCommand("$ first\r\n$ second\r\n"))
    }

    @Test
    fun `dollar variables substitutions and dollar signs inside commands are preserved`() {
        val command = "${'$'}HOME/bin/tool\nprintf '%s' ${'$'}USER\necho ${'$'}(pwd)\necho '$ value'"
        assertEquals(command, normalizeCodeBlockCommand("$ $command\n"))
        assertEquals("$", normalizeCodeBlockCommand("$"))
        assertEquals("", normalizeCodeBlockCommand("$ \n\t$\t\n"))
    }

    @Test
    fun `normalization uses JavaScript Unicode whitespace and line boundaries`() {
        assertEquals("one\u2028two\u2029three", normalizeCodeBlockCommand("$ one\u2028$ two\u2029$ three\u00a0\ufeff"))
        assertEquals("one\u0085$ two\u001c", normalizeCodeBlockCommand("$ one\u0085$ two\u001c"))
        assertEquals("", normalizeCodeBlockCommand("\u00a0\ufeff\u2028"))
    }
}
