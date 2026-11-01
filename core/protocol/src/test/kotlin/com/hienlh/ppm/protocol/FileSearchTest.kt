package com.hienlh.ppm.protocol

import org.junit.Assert.*
import org.junit.Test

class FileSearchTest {
    @Test fun `wire groups preserve path line and whitespace including empty matches`() {
        val answer = FileSearchAnswer.from(WireJson.parse("""{"results":[{"file":"a:b.kt","matches":[{"lineNum":17,"content":"  hit  ","future":true},{"lineNum":18,"content":""}]}],"total":2,"future":true}"""))!!
        assertEquals("a:b.kt", answer.results.single().file)
        assertEquals(listOf(FileSearchMatch(17, "  hit  "), FileSearchMatch(18, "")), answer.results.single().matches)
        assertEquals(2, answer.total)
    }
    @Test fun `malformed response is not mistaken for a successful empty search`() {
        for (body in listOf("{}", """{"results":[],"total":"0"}""", """{"results":[{"file":"a","matches":[{"lineNum":0,"content":"hit"}]}],"total":1}""",
            """{"results":[{"file":"a","matches":[{"lineNum":1,"content":23}]}],"total":1}""")) {
            assertNull(body, FileSearchAnswer.from(WireJson.parse(body)))
        }
        assertEquals(FileSearchAnswer(emptyList(), 0), FileSearchAnswer.from(WireJson.parse("""{"results":[],"total":0}""")))
    }
    @Test fun `minimum length uses untrimmed UTF16 web query`() {
        assertFalse(FileSearchOptions("a").eligible)
        assertTrue(FileSearchOptions("a", regex = true).eligible)
        assertTrue(FileSearchOptions("  ").eligible)
        assertTrue(FileSearchOptions("😀").eligible)
        assertFalse(FileSearchOptions("", regex = true).eligible)
    }
}
