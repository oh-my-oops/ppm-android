package com.hienlh.ppm.state.terminal

import com.hienlh.ppm.state.terminal.TerminalText.BufferLine
import com.hienlh.ppm.state.terminal.TerminalText.ChatText
import com.hienlh.ppm.state.terminal.TerminalText.KeyInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class TerminalTextTest {
    @Test
    fun `URLs reverse discovery order after deduplication including repeats on later lines`() {
        assertEquals(listOf("https://third.test", "http://second.test", "https://first.test"), TerminalText.bufferUrls(listOf(
            "https://first.test http://second.test",
            "https://third.test https://first.test). https://third.test",
        )))
        assertEquals(emptyList<String>(), TerminalText.bufferUrls(emptyList()))
    }

    @Test
    fun `URL punctuation follows the web rather than balancing path parentheses`() {
        val text = "(https://host.test/a(b)).,;:'\"]}> https://host.test/a(b)c?q=x,y&x=%29#part! " +
            "<http://localhost:8080/> `https://last.test/path?`"
        assertEquals(listOf("https://host.test/a(b", "https://host.test/a(b)c?q=x,y&x=%29#part!", "http://localhost:8080/", "https://last.test/path?"), TerminalText.extractUrls(text))
        assertEquals(listOf("https://a.test", "https://b.test", "https://c.test"), TerminalText.extractUrls("https://a.test\"https://b.test'https://c.test<"))
    }

    @Test
    fun `URL boundaries and whitespace are JavaScript's including Unicode`() {
        assertEquals(listOf("https://例え.test/道😀", "http://b.test", "https://c.test", "https://d.test"), TerminalText.extractUrls(
            "HTTPS://ignored.test ftp://ignored.test xhttps://ignored.test _http://ignored.test " +
                "éhttps://例え.test/道😀\u00a0http://b.test\u2028https://c.test\ufeffhttps://d.test",
        ))
        // These are NOT JavaScript whitespace, even though some JVM character classes include them.
        assertEquals(listOf("https://a.test/\u0085x\u001cy"), TerminalText.extractUrls("https://a.test/\u0085x\u001cy"))
    }

    @Test
    fun `wrapped rows reconstruct a URL across both its scheme and its Unicode path`() {
        assertEquals(listOf("https://例え.test/道😀?code=abc", "http://older.test"), TerminalText.bufferUrlsFromRows(listOf(
            BufferLine("http://older.test"), BufferLine("open htt"),
            BufferLine("ps://例え.test/道\uD83D", true), BufferLine("\uDE00?code=abc).", true),
            BufferLine("ignored https://future.test"),
        ), cursorRow = 3))
        assertEquals(emptyList<String>(), TerminalText.bufferUrlsFromRows(listOf(BufferLine("htt"), BufferLine("ps://not-a-url.test"))))
        assertEquals(listOf("https://a.test", "http://b.test"), TerminalText.bufferUrlsFromRows(listOf(
            BufferLine("http://b.test"), BufferLine(""), BufferLine("https://a.test", true),
        )))
    }

    @Test
    fun `scan is bounded to cursor and preceding 5000 rows without looking outside the window`() {
        val rows = MutableList(5003) { BufferLine("") }
        rows[0] = BufferLine("https://excluded.test")
        rows[1] = BufferLine("https://boundary.test")
        rows[5001] = BufferLine("https://cursor.test")
        rows[5002] = BufferLine("https://future.test", true)
        assertEquals(listOf("https://cursor.test", "https://boundary.test"), TerminalText.bufferUrlsFromRows(rows, 5001))
        assertEquals(listOf("https://future.test", "https://cursor.test"), TerminalText.bufferUrls(rows.map { it.text }))
        rows[0] = BufferLine("https://")
        rows[1] = BufferLine("outside-the-window.test", true)
        assertEquals(listOf("https://cursor.test"), TerminalText.bufferUrlsFromRows(rows, 5001))
        assertEquals(emptyList<String>(), TerminalText.bufferUrlsFromRows(rows, -1))
        assertEquals(emptyList<String>(), TerminalText.bufferUrlsFromRows(emptyList()))
    }

    @Test
    fun `selection context is the exact web template preserving indentation and inner blank lines`() {
        assertEquals(ChatText("Terminal selection", "Selected text from the terminal\n```\n$ bun test\n  12 pass\n```"),
            TerminalText.selectionContext("\n  \n$ bun test\n  12 pass\n\n"))
        assertEquals("Selected text from the terminal\n```\n  道😀\r\n\r\n\tend\n```",
            TerminalText.selectionContext(" \r\n\t\r\n  道😀\r\n\r\n\tend \u00a0\ufeff")?.text)
    }

    @Test
    fun `selection fence outruns every backtick run including inline runs`() {
        assertEquals("Selected text from the terminal\n````\n```ts\nx\n```\n````", TerminalText.selectionContext("```ts\nx\n```")?.text)
        assertEquals("Selected text from the terminal\n``````\ninline ````` fence\n``````", TerminalText.selectionContext("inline ````` fence")?.text)
        for (blank in listOf("", " \n\t\n", "\u00a0\ufeff\u2028")) assertNull(TerminalText.selectionContext(blank))
        // Only ASCII space/tab blank lines are removed at the START, matching the web regex.
        assertEquals("Selected text from the terminal\n```\n\u00a0\ntext\n```", TerminalText.selectionContext("\u00a0\ntext")?.text)
        assertEquals("Selected text from the terminal\n```\ntext\u001c\n```", TerminalText.selectionContext("text\u001c")?.text)
    }

    @Test
    fun `toolbar draft prefers a verbatim selection and uses its own fixed bash fence`() {
        val selection = "\n  ```😀\n\n"
        assertEquals(ChatText("Terminal selection", "```bash\n$selection\n```"),
            TerminalText.chatDraft(selection, command = "$ ignored", output = "ignored"))
        assertNull(TerminalText.chatDraft(" \n", command = "$ test", output = "fallback is suppressed"))
    }

    @Test
    fun `toolbar last command draft includes the prompt only when output exists`() {
        assertEquals(ChatText("Terminal output", "```bash\n$ bun test\n  12 pass\n```"),
            TerminalText.chatDraft(command = "$ bun test", output = "  12 pass"))
        assertEquals(ChatText("Terminal output", "```bash\nstartup 😀\n```"), TerminalText.chatDraft(output = "startup 😀"))
        assertNull(TerminalText.chatDraft(command = "$ bun test"))
        assertNull(TerminalText.chatDraft(output = "\u00a0\ufeff\u2029"))
        assertEquals("```bash\n\u001c\n```", TerminalText.chatDraft(output = "\u001c")?.text)
    }

    @Test
    fun `Ctrl maps both letter cases to all 26 control codes then disarms`() {
        for (letter in 'a'..'z') {
            val expected = KeyInput((letter.code - 'a'.code + 1).toChar().toString(), false)
            assertEquals(expected, TerminalText.mobileKey(letter.toString(), true))
            assertEquals(expected, TerminalText.mobileKey(letter.uppercase(), true))
        }
        assertEquals(KeyInput("C", false), TerminalText.mobileKey("C", false))
    }

    @Test
    fun `Ctrl swallows unsupported single units and keeps armed on multi-unit input`() {
        for (single in listOf("1", "@", " ", "é", "道", "\t", "\u001b", "\u0003", "\u0000")) {
            assertEquals(single, KeyInput(null, false), TerminalText.mobileKey(single, true))
            assertEquals(single, KeyInput(single, false), TerminalText.mobileKey(single, false))
        }
        for (multiple in listOf("", "\u001b[A", "\u001b[B", "\u001b[D", "\u001b[C", "paste", "😀", "e\u0301")) {
            assertEquals(KeyInput(multiple, true), TerminalText.mobileKey(multiple, true))
        }
        assertEquals(KeyInput("\u000b", false), TerminalText.mobileKey("K", true))
        assertEquals(KeyInput("\t", false), TerminalText.mobileKey("İ", true))
    }

    @Test
    fun `loopback callback allows HTTP and HTTPS localhost IPv4 range and IPv6`() {
        for (url in listOf("http://localhost:1455/auth/callback?code=a", " \nHTTPS://LOCALHOST/callback\ufeff", "http://127.2.3.4:8080/", "https://[::1]:8443/", "http://[0:0:0:0:0:0:0:1]/")) {
            assertTrue(url, TerminalText.isDeliverableCallback(url))
        }
        for (url in listOf("", "/callback", "localhost:1455/callback", "ftp://localhost/callback", "http://localhost.evil.test/", "http://localhost@evil.test/", "http://128.0.0.1/", "http://[::ffff:127.0.0.1]/", "http://localhost:65536/", "http://localhost:bad/")) {
            assertFalse(url, TerminalText.isDeliverableCallback(url))
        }
    }

    @Test
    fun `browser numeric loopback forms and port zero agree with the web URL parser`() {
        for (host in listOf("127.1", "2130706433", "0x7f000001", "0177.0.0.1", "127.0.0.1.", "local%68ost")) {
            assertTrue(host, TerminalText.isDeliverableCallback("http://$host:0/cb"))
            assertEquals(host, "0", TerminalText.loopbackRedirectPort(authorize("http://$host:0/cb")))
        }
        for (host in listOf("127.999.0.1", "127.0.0.256", "127.0.0.09", "4294967296", "localhost.", "localhost.evil.test", "[::ffff:127.0.0.1]")) {
            assertFalse(host, TerminalText.isDeliverableCallback("http://$host/"))
            assertNull(host, TerminalText.loopbackRedirectPort(authorize("http://$host/")))
        }
        assertEquals("80", TerminalText.loopbackRedirectPort(authorize("http://localhost:/")))
        assertEquals("1455", TerminalText.loopbackRedirectPort(authorize("http://localhost:0001455/")))
        assertNull(TerminalText.loopbackRedirectPort(authorize("file://localhost/cb")))
        assertNull(TerminalText.loopbackRedirectPort(authorize("custom://localhost:wrong/cb")))
        assertEquals("80", TerminalText.loopbackRedirectPort(authorize("wss://localhost/")))
    }

    @Test
    fun `OAuth redirects decode query values and use explicit or default ports`() {
        for ((target, port) in listOf("http://localhost:1455/auth/callback?code=a&state=😀" to "1455", "https://[::1]:8443/cb" to "8443", "http://127.0.0.2/" to "80", "https://localhost/" to "443", "https://localhost:443/" to "443", "http://localhost:80/" to "80")) {
            assertEquals(target, port, TerminalText.loopbackRedirectPort(authorize(target)))
        }
        assertEquals("9000", TerminalText.loopbackRedirectPort("https://auth.test/?redirectUri=http%3A%2F%2Flocalhost%3A9000%2Fcb"))
        assertEquals("80", TerminalText.loopbackRedirectPort(authorize("http://localhost/cb?q=hello world")))
    }

    @Test
    fun `OAuth redirect query precedence is nullish not blank fallback and first duplicate wins`() {
        assertEquals("1455", TerminalText.loopbackRedirectPort(authorize("http://localhost:1455/") + "&redirectUri=http://localhost:9000/&redirect_uri=http://localhost:8000/"))
        assertNull(TerminalText.loopbackRedirectPort("https://auth.test/?redirect_uri=&redirectUri=http://localhost:9000/"))
        assertNull(TerminalText.loopbackRedirectPort(authorize("https://remote.test/") + "&redirectUri=http://localhost:9000/"))
        assertNull(TerminalText.loopbackRedirectPort("https://auth.test/?Redirect_uri=http://localhost:9000/"))
    }

    @Test
    fun `OAuth malformed remote and relative redirects yield no loopback port`() {
        for (url in listOf("not a URL", "https://auth.test/", "https://auth.test/#redirect_uri=http://localhost/", authorize(""), authorize("/callback"), authorize("http://remote.test:1455/"), authorize("http://localhost:99999/"), authorize("http://127.999.0.1/"))) {
            assertNull(url, TerminalText.loopbackRedirectPort(url))
        }
        // Only callback DELIVERY restricts the scheme; the web's redirect-port detector does not.
        assertEquals("1455", TerminalText.loopbackRedirectPort(authorize("custom://localhost:1455/cb")))
        assertEquals("80", TerminalText.loopbackRedirectPort(authorize("ftp://localhost:21/cb")))
    }

    private fun authorize(target: String) = "https://auth.test/authorize?redirect_uri=" + URLEncoder.encode(target, "UTF-8")
}
