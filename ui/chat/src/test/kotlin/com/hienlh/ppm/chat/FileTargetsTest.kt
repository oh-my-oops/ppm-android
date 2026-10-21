package com.hienlh.ppm.chat

import com.hienlh.ppm.protocol.WireJson
import com.hienlh.ppm.protocol.array
import com.hienlh.ppm.protocol.double
import com.hienlh.ppm.protocol.get
import com.hienlh.ppm.protocol.string
import com.hienlh.ppm.state.workspace.SourceLine
import com.hienlh.ppm.state.workspace.formatSourceLocation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File references against the web's own answers, from `android/scripts/gen-file-target-cases.ts`. */
class FileTargetsTest {
    @Test
    fun `links, inline code and line suffixes are read as the web reads them`() {
        val cases = WireJson.parse(javaClass.getResource("/file-target-cases.json")!!.readText()).array!!
        for (case in cases) {
            val text = case["text"].string!!
            check("split $text", case["split"], FileTargets.splitSourceLocation(text))
            check("link $text", case["link"], FileTargets.markdownLink(text))
            check("code $text", case["code"], FileTargets.inlineCode(text))
            val tap = case["mdLink"]!!
            assertEquals("href $text", tap["href"].string, normalizeUri(text))
            check("tap $text", tap["file"], FileTargets.link(text))
            assertEquals("url $text", tap["url"].string, FileTargets.url(text))
        }
    }

    @Test
    fun `a location the palette is handed splits back as it was`() {
        for (line in listOf(null, SourceLine(7), SourceLine(7, 9))) {
            assertEquals(FileTarget("D:/repo/app.ts", line), FileTargets.splitSourceLocation(formatSourceLocation("D:/repo/app.ts", line)))
        }
    }

    @Test
    fun `decodeURIComponent refuses what JavaScript refuses`() {
        assertEquals("a b/é€😀", decodeUriComponent("a%20b%2F%C3%A9%e2%82%ac%F0%9F%98%80"))
        assertEquals("a+b", decodeUriComponent("a+b"))
        for (bad in listOf("%", "%2", "%ZZ", "%C3", "%C3%28", "%C0%AF", "%ED%A0%80", "%F4%90%80%80", "%F8%88%80%80%80", "%٣٣")) {
            assertNull(bad, decodeUriComponent(bad))
        }
    }

    private fun check(label: String, expected: JsonElement?, actual: FileTarget?) {
        if (expected == null || expected is JsonNull) {
            assertNull(label, actual)
            return
        }
        assertEquals(label, expected["path"].string, actual?.path)
        val line = expected["line"]
        if (line == null || line is JsonNull) {
            assertNull(label, actual?.line)
            return
        }
        number(label, line["start"].double!!, actual?.line?.start)
        val end = line["end"].double
        if (end == null) assertNull(label, actual?.line?.end) else number(label, end, actual?.line?.end)
    }

    /** A line past 2^53 is no line of any file: the web's double and the app's ceiling only have to agree it is huge. */
    private fun number(label: String, expected: Double, actual: Long?) {
        if (expected > 9_007_199_254_740_991.0) assertTrue(label, actual != null && actual > 9_007_199_254_740_991L)
        else assertEquals(label, expected.toLong(), actual)
    }
}
