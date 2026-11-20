package com.hienlh.ppm.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockActionsTest {
    // shell-block-detect.test.ts, case for case.

    @Test
    fun `takes every block tagged as shell`() {
        for (lang in listOf("bash", "sh", "shell", "zsh", "console", "BASH")) assertTrue(lang, isShellCodeBlock(lang, "anything at all"))
    }

    @Test
    fun `takes an untagged one-line command, like the one that asked for this`() {
        assertTrue(isShellCodeBlock(null, "grep '^MASTER_PASSWORD=' ~/Desktop/Nexus/nxsys-workspace/nxsys-backend-nx6620-review/.env"))
        assertTrue(isShellCodeBlock("text", "git status\n"))
        assertTrue(isShellCodeBlock(null, "$ bun test"))
    }

    @Test
    fun `takes prefixes, paths to programs, comments and continued lines`() {
        assertTrue(isShellCodeBlock(null, "sudo systemctl restart nginx"))
        assertTrue(isShellCodeBlock(null, "PPM_HOME=$(mktemp -d) bun my-script.ts"))
        assertTrue(isShellCodeBlock(null, "./scripts/test.sh image/update"))
        assertTrue(isShellCodeBlock(null, "~/bin/tool --flag"))
        assertTrue(isShellCodeBlock(null, "# check the port first\nss -ltn | grep 3210"))
        assertTrue(isShellCodeBlock(null, "docker run --rm \\\n  -p 80:80 \\\n  nginx"))
        assertTrue(isShellCodeBlock(null, "cat a.log |\n  sort"))
    }

    @Test
    fun `leaves code, config and prose alone`() {
        assertFalse(isShellCodeBlock(null, "const x = 1;\nconsole.log(x);"))
        assertFalse(isShellCodeBlock("ts", "git status"))
        assertFalse(isShellCodeBlock("json", "{\"a\": 1}"))
        assertFalse(isShellCodeBlock(null, "MASTER_PASSWORD=secret\nPORT=8080"))
        assertFalse(isShellCodeBlock(null, "The build failed because the cache was stale."))
        assertFalse(isShellCodeBlock(null, "git status\nthen look at the output"))
        assertFalse(isShellCodeBlock(null, ""))
    }

    @Test
    fun `leaves a long untagged block alone`() {
        assertFalse(isShellCodeBlock(null, List(9) { "echo hi" }.joinToString("\n")))
        // Eight lines is the most an untagged block may have.
        assertTrue(isShellCodeBlock(null, List(8) { "echo hi" }.joinToString("\n")))
    }

    @Test
    fun `an empty tag counts as untagged, and JavaScript white space separates the program`() {
        assertTrue(isShellCodeBlock("", "git status"))
        assertTrue(isShellCodeBlock(null, "git\u00a0status"))
        assertTrue(isShellCodeBlock(null, "./run.sh\u2003--fast"))
        assertFalse(isShellCodeBlock(null, "\n\ngit status"))
    }

    @Test
    fun `elapsed time reads as the web's formatElapsed`() {
        assertEquals("0s", formatElapsed(0))
        assertEquals("0s", formatElapsed(-5))
        assertEquals("0.0s", formatElapsed(42))
        assertEquals("1.2s", formatElapsed(1_200))
        // toFixed(1) rounds the double: 1.45 is just under, 1.25 exact and rounds up.
        assertEquals("1.4s", formatElapsed(1_450))
        assertEquals("1.3s", formatElapsed(1_250))
        assertEquals("9.9s", formatElapsed(9_949))
        assertEquals("10s", formatElapsed(10_000))
        assertEquals("59s", formatElapsed(59_999))
        assertEquals("1m 00s", formatElapsed(60_000))
        assertEquals("2m 05s", formatElapsed(125_400))
    }

    @Test
    fun `the status line says how the run ended`() {
        fun r(exit: Int?, timedOut: Boolean = false, stopped: Boolean = false, ms: Long = 1_200) = com.hienlh.ppm.client.ShellRunResult("", exit, timedOut, stopped, ms)
        assertEquals("Exit 0 · 1.2s", runStatusLine(r(0)))
        assertEquals("Exit 2 · 1.2s", runStatusLine(r(2)))
        assertEquals("Stopped", runStatusLine(r(null, stopped = true)))
        assertEquals("Stopped", runStatusLine(r(0, timedOut = true, stopped = true)))
        assertEquals("Stopped at the time limit · 2m 00s", runStatusLine(r(null, timedOut = true, ms = 120_000)))
        assertEquals("Could not start", runStatusLine(r(null)))
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
