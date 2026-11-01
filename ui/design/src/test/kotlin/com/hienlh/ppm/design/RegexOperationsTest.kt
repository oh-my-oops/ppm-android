package com.hienlh.ppm.design

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Execute the exact generated operations under JS, not a JVM approximation of RegExp. */
class RegexOperationsTest {
    private fun evaluate(request: RegexOperationsRequest): RegexOperationsResult {
        val process = ProcessBuilder("node", "-e", "process.stdout.write(${regexOperationsScript(request)});").redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); error("JS fixture timed out") }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
        return parseRegexOperations(output)
    }
    private fun replace(query: String, text: String, replacement: String, regex: Boolean = false, wholeWord: Boolean = false, case: Boolean = false) =
        evaluate(RegexOperationsRequest(RegexOperation.REPLACE, query, case, wholeWord, regex, text, replacement))

    @Test fun `literal metacharacters are escaped and case matching follows JS`() {
        assertEquals("x x .*", replace("A+B", "a+b A+B .*", "x").content)
        assertEquals(1, replace("A+B", "a+b A+B", "x", case = true).count)
        assertEquals("x", replace(".*+?^${'$'}{}()|[]\\", ".*+?^${'$'}{}()|[]\\", "x").content)
    }
    @Test fun `outer capture is dollar one and user captures shift one place`() {
        val result = replace("(foo)(bar)", "foobar", "${'$'}1|${'$'}2|${'$'}3|${'$'}&|${'$'}${'$'}", regex = true)
        assertEquals("foobar|foo|bar|foobar|${'$'}", result.content)
        assertEquals(1, result.count)
        assertEquals("${'$'}|foo|foo|${'$'}2", replace("foo", "foo", "${'$'}${'$'}|${'$'}&|${'$'}1|${'$'}2").content)
    }
    @Test fun `JS prefix suffix and named capture replacement tokens stay exact`() {
        assertEquals("ab[ab][cd]cd", replace("XX", "abXXcd", "[${'$'}`][${'$'}']").content)
        assertEquals("A-a", replace("(?<letter>a)", "a", "A-${'$'}<letter>", regex = true).content)
    }
    @Test fun `query is not trimmed and replacements include matches beyond result sample`() {
        assertEquals(0, replace(" foo ", "foo foo", "x").count)
        val result = replace("foo", List(8) { "foo foo" }.joinToString("\n"), "x")
        assertEquals(16, result.count)
        assertEquals(List(8) { "x x" }.joinToString("\n"), result.content)
    }
    @Test fun `whole words and empty regex matches use native JS semantics`() {
        assertEquals("x food xé x", replace("foo", "foo food fooé FOO", "x", wholeWord = true).content)
        assertEquals(4, replace("(?=)", "😀x", "-", regex = true).count)
        assertEquals("-\uD83D-\uDE00-x-", replace("(?=)", "😀x", "-", regex = true).content)
    }
    @Test fun `raw regex validation is distinct from the wrapped replacement and backreference quirk`() {
        assertNotNull(evaluate(RegexOperationsRequest(RegexOperation.VALIDATE, "[")).error)
        assertNull(evaluate(RegexOperationsRequest(RegexOperation.VALIDATE, "(?<=a)b")).error)
        assertEquals("xx", replace("(a)\\1", "aa", "x", regex = true).content)
    }
    @Test fun `highlighting preserves original text and uses half open UTF16 ranges`() {
        val result = evaluate(RegexOperationsRequest(RegexOperation.HIGHLIGHT, "(foo)", useRegex = true, texts = listOf("😀foo foo", "none")))
        assertEquals(listOf(listOf(RegexHighlight(2, 5), RegexHighlight(6, 9)), emptyList<RegexHighlight>()), result.highlights)
        assertEquals(listOf(emptyList<RegexHighlight>()), evaluate(RegexOperationsRequest(RegexOperation.HIGHLIGHT, "(?=)", useRegex = true, texts = listOf("😀"))).highlights)
    }
    @Test fun `quotes control characters and code shaped operands remain inert JSON data`() {
        val data = "\"'); throw new Error('injected'); //\n\u2028\\"
        assertEquals(data, replace("foo", "foo", data).content)
        assertEquals("safe", replace(data, data, "safe").content)
    }
}
