package org.connectbot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The port held against the web's own answers (`terminal-link-cases.tsv`, from `android/scripts/gen-terminal-link-cases.ts`): the
 * addon's `isUrl` and the OSC 8 provider's http(s)-only rule over a few thousand candidate texts, and `LinkComputer.computeLink`
 * over every row of buffers a real xterm built, wrapped at 80, 40, 20 and 7 columns, through wide characters, the history and
 * the 2048-character limits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebLinksOracleTest {
    private fun escaped(text: String) = text.map { if (it.code in 0x20..0x7e) "$it" else "\\u%04x".format(it.code) }.joinToString("")

    private fun describe(c: WebLinkFixtures.TextCase) = escaped(c.text)

    /** The regex only ever hands `isUrl` a match, and a match starts with one of these. */
    private fun fromTheRegex(text: String) = Regex("^(https?|HTTPS?):").containsMatchIn(text)

    @Test
    fun `isUrl answers as the addon's does`() {
        val wrong = WebLinkFixtures.texts.filter { fromTheRegex(it.text) && WebUrl.isUrl(it.text) != it.isUrl }
        assertEquals("isUrl differs for ${wrong.size}: " + wrong.take(25).joinToString { "'${describe(it)}' expected ${it.isUrl}" }, 0, wrong.size)
        assertTrue(WebLinkFixtures.texts.count { fromTheRegex(it.text) && it.isUrl } > 500)
    }

    @Test
    fun `an OSC 8 uri is shown when xterm shows it`() {
        val wrong = WebLinkFixtures.texts.filter { WebUrl.isHttpUrl(it.text) != it.oscShown }
        assertEquals("isHttpUrl differs for ${wrong.size}: " + wrong.take(25).joinToString { "'${describe(it)}' expected ${it.oscShown}" }, 0, wrong.size)
        assertTrue(WebLinkFixtures.texts.count { it.oscShown } > 500)
    }

    /**
     * Code points whose UTS 46 status changed in Unicode 15.1 and 16, which Chrome's ICU has and the one of Android 14 (and
     * of the Robolectric build these tests run on) does not: the Hangul fillers, the invisible operators and deprecated format
     * characters (now ignored), turned F and the reversed one hundred (now mapped), capital sharp S (now maps to ß, not ss) and the
     * Mongolian todo soft hyphen. A device with a newer ICU follows Chrome on them; none is a character a URL has.
     */
    private val newerThanAndroidIcu = setOf(0x115F, 0x1160, 0x1806, 0x1E9E, 0x2061, 0x2062, 0x2063, 0x206A, 0x206B, 0x206C, 0x206D, 0x206E, 0x206F, 0x2132, 0x2183, 0xFFA0)

    @Test
    fun `a host is written as Chrome writes it`() {
        val (skipped, compared) = WebLinkFixtures.hosts.partition { case -> case.host.any { it.code in newerThanAndroidIcu } }
        val wrong = compared.filter { WebUrl.hostname(it.host) != it.hostname }
        assertEquals(
            "hostname differs for ${wrong.size}: " + wrong.take(25).joinToString { "'${escaped(it.host)}' expected ${it.hostname?.let(::escaped)} was ${WebUrl.hostname(it.host)?.let(::escaped)}" },
            0,
            wrong.size,
        )
        assertTrue(skipped.size < 120)
        assertTrue(compared.count { it.hostname != null } > 2000)
        assertTrue(compared.count { it.hostname == null } > 500)
    }

    @Test
    fun `what a host with a character outside ASCII comes to`() {
        // The parser writes such a host in punycode, so the text is not what the addon finds back: no link, though a valid URL.
        assertTrue(WebUrl.isHttpUrl("https://bücher.de/x"))
        assertTrue(!WebUrl.isUrl("https://bücher.de/x"))
        assertEquals("xn--bcher-kva.de", WebUrl.hostname("bücher.de"))
        // Outside the host it is nothing special.
        assertTrue(WebUrl.isHttpUrl("https://example.com/日本語"))
        assertTrue(WebUrl.isUrl("https://example.com/日本語"))
        // The addon compares in lower case, and the Kelvin sign lower-cases to a plain k: that one is a link.
        val kelvin = 0x212A.toChar()
        assertTrue(WebUrl.isUrl("https://$kelvin.com/"))
        assertEquals("k.com", WebUrl.hostname("$kelvin.com"))
        // A name with a disallowed code point (a C1 control here) makes `new URL` throw.
        assertTrue(!WebUrl.isHttpUrl("https://a" + 0x80.toChar() + "b.com/"))
    }

    @Test
    fun `the links of every row are the addon's`() {
        for (case in WebLinkFixtures.buffers) {
            val web = TerminalWebLinks(case.cols) { case.lines.getOrNull(it) }
            for (y in 1..case.lines.size) {
                val actual = web.webLinks(y).map { Triple(listOf(it.startX, it.startY, it.endX, it.endY), it.url.length, WebLinkFixtures.fnv(it.url)) }
                val expected = case.expected(y).map { Triple(listOf(it.startX, it.startY, it.endX, it.endY), it.length, it.hash) }
                assertEquals("${case.name} row $y", expected, actual)
            }
        }
    }

    @Test
    fun `the fixture holds what it should`() {
        assertEquals(13, WebLinkFixtures.buffers.size)
        assertTrue(WebLinkFixtures.buffers.sumOf { it.links.size } > 200)
        assertTrue(WebLinkFixtures.buffers.any { it.name == "wide" && it.links.isNotEmpty() })
    }
}
