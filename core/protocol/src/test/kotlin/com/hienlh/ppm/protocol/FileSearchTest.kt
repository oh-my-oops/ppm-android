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
    @Test fun `the search host's matches carry line column length and the preview around the match`() {
        val answer = FileSearchAnswer.from(WireJson.parse("""{"results":[{"file":"src/a.ts","matches":[
            {"line":12,"column":40,"length":3,"before":"…const x = ","cutBefore":true,"text":"foo","after":"(bar);","replacement":"Baz"},
            {"line":13,"column":0,"length":3,"before":"","cutBefore":false,"text":"foo","after":""}]}],
            "total":2,"files":1,"limitHit":true,"incomplete":"timeout","engine":"grep","replaceUnavailable":true}"""))!!
        val first = answer.results.single().matches[0]
        assertEquals(FileSearchMatch(12, 40, 3, "…const x = ", true, "foo", "(bar);", "Baz"), first)
        assertEquals(12, first.lineNum)
        assertEquals("…const x = foo(bar);", first.content)
        assertNull(answer.results.single().matches[1].replacement)
        assertEquals(2, answer.total)
        assertEquals(1, answer.files)
        assertTrue(answer.limitHit)
        assertEquals("timeout", answer.incomplete)
        assertEquals("grep", answer.engine)
        assertTrue(answer.replaceUnavailable)
    }
    @Test fun `an old server's reply leaves the new fields at their defaults`() {
        val answer = FileSearchAnswer.from(WireJson.parse("""{"results":[{"file":"a","matches":[{"lineNum":3,"content":"x"}]}],"total":1}"""))!!
        assertNull(answer.results.single().matches.single().column)
        assertEquals(1, answer.files)
        assertFalse(answer.limitHit)
        assertNull(answer.engine)
    }
    @Test fun `a new-shape match without a column or text is malformed`() {
        for (match in listOf("""{"line":1,"length":1,"text":"a"}""", """{"line":1,"column":0,"length":1}""", """{"line":0,"column":0,"text":"a"}""")) {
            assertNull(match, FileSearchAnswer.from(WireJson.parse("""{"results":[{"file":"a","matches":[$match]}],"total":1}""")))
        }
    }
    @Test fun `replace body and report follow replace-run's shapes`() {
        val body = fileReplaceBody(listOf(FileReplaceFile("src/a.ts", listOf(FileReplaceMatch(3, 4, "old", "new")))))
        assertEquals("""{"files":[{"file":"src/a.ts","matches":[{"line":3,"column":4,"text":"old","replacement":"new"}]}]}""", body.toString())
        val report = FileReplaceAnswer.from(WireJson.parse("""{"replaced":5,"files":2,"skipped":[{"file":"b","reason":"changed"}],"failed":[{"file":"c","reason":"EACCES"}]}"""))!!
        assertEquals(FileReplaceAnswer(5, 2, listOf(FileReplaceSkip("b", "changed")), listOf(FileReplaceSkip("c", "EACCES"))), report)
        assertNull(FileReplaceAnswer.from(WireJson.parse("{}")))
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
