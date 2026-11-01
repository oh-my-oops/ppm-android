package org.connectbot.terminal

import android.icu.text.IDNA
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Locale

/**
 * The part of Chrome's URL parser that the web's terminal links depend on, for `http:` and `https:`.
 *
 * xterm's web-links addon keeps a match of its regex only when `isUrl` says the platform's `new URL` leaves the text's
 * start alone: it rebuilds `protocol//[user[:password]@]host[:port]` from the parsed URL and requires the text to begin
 * with that, case aside. Text the parser would rewrite (`http://127.1/`, `http://0x7f.0.0.1/`, `http://bücher.de/`, a host
 * with `%41` in it, `http://user:@host/`, `http://[0:0:0:0:0:0:0:1]/`) is therefore no link. xterm's OSC 8 links go through
 * `new URL(uri)` and show only `http:` and `https:` ones. [isUrl] and [isHttpUrl] are those two answers.
 *
 * The web client runs in a browser, so the parser here is Chrome's, which is the URL standard's with a few differences that
 * decide what is a link: a `'` in a user name or password is percent-encoded; a `*` or a space in a host is written `%2A` or
 * `%20` rather than failing the parse; an `xn--` label in a host written in ASCII is not validated; and the IPv4 part of an
 * IPv6 address takes the IPv4 number forms (hexadecimal, octal) instead of plain decimal. A host with a character outside
 * ASCII goes through UTS 46 as Chrome calls it, with Android's copy of ICU, which can be a Unicode version behind Chrome's: a
 * character assigned since then is the one place the two may disagree. `terminal-link-cases.tsv` holds Chrome's own answers
 * for all of it. `new URL`'s case folding of the whole text is `lowercase(ROOT)` here, which differs from the browser's
 * `toLocaleLowerCase()` only in a Turkish locale.
 */
internal object WebUrl {
    /** What xterm's `isUrl` makes of [text]. */
    fun isUrl(text: String): Boolean {
        val base = parse(text)?.base ?: return false
        return text.lowercase(Locale.ROOT).startsWith(base.lowercase(Locale.ROOT))
    }

    /** Whether `new URL(text)` parses and gives `http:` or `https:`, as xterm's OSC 8 provider asks. */
    fun isHttpUrl(text: String): Boolean = parse(text) != null

    /** The `hostname` that `new URL("http://" + host + "/")` serializes, or null where it throws. */
    fun hostname(host: String): String? = parse("http://$host/")?.host

    private class Parsed(val scheme: String, val username: String, val password: String, val host: String, val port: Int?) {
        /** `protocol//[user[:password]@]host[:port]`, what the addon rebuilds from the URL object (a default port is not part of `host`). */
        val base: String
            get() {
                val credentials = when {
                    username.isNotEmpty() && password.isNotEmpty() -> "$username:$password@"
                    username.isNotEmpty() -> "$username@"
                    else -> ""
                }
                val shownPort = port?.takeIf { it != defaultPort(scheme) }?.let { ":$it" } ?: ""
                return "$scheme://$credentials$host$shownPort"
            }
    }

    private fun defaultPort(scheme: String) = if (scheme == "http") 80 else 443

    private fun parse(raw: String): Parsed? {
        val input = raw.trim { it <= ' ' }.filter { it != '\t' && it != '\n' && it != '\r' }
        val colon = input.indexOf(':')
        if (colon < 0) return null
        val scheme = input.substring(0, colon).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        var start = colon + 1
        while (start < input.length && (input[start] == '/' || input[start] == '\\')) start++
        var end = start
        while (end < input.length && input[end] !in "/\\?#") end++
        val authority = input.substring(start, end)
        val at = authority.lastIndexOf('@')
        val hostAndPort = authority.substring(at + 1)
        var username = ""
        var password = ""
        if (at >= 0) {
            val userinfo = authority.substring(0, at)
            val split = userinfo.indexOf(':')
            username = encodeUserinfo(if (split < 0) userinfo else userinfo.substring(0, split))
            password = if (split < 0) "" else encodeUserinfo(userinfo.substring(split + 1))
        }
        var insideBrackets = false
        var portStart = -1
        for ((i, c) in hostAndPort.withIndex()) {
            if (c == '[') insideBrackets = true else if (c == ']') insideBrackets = false
            if (c == ':' && !insideBrackets) {
                portStart = i
                break
            }
        }
        val hostText = if (portStart < 0) hostAndPort else hostAndPort.substring(0, portStart)
        if (hostText.isEmpty()) return null
        val port = if (portStart < 0) "" else hostAndPort.substring(portStart + 1)
        if (port.any { it !in '0'..'9' }) return null
        val digits = port.trimStart('0')
        if (digits.length > 5) return null
        val portNumber = if (port.isEmpty()) null else if (digits.isEmpty()) 0 else digits.toInt()
        if (portNumber != null && portNumber > 65535) return null
        val host = parseHost(hostText) ?: return null
        return Parsed(scheme, username, password, host, portNumber)
    }

    /** The userinfo percent-encode set: C0 controls, everything after `~`, what the URL standard lists besides, and Chrome's `'`. */
    private fun encodeUserinfo(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            if (b <= 0x1F || b >= 0x7F || b.toChar() in " \"#'<>?`{}/:;=@[\\]^|") out.append('%').append("%02X".format(b)) else out.append(b.toChar())
        }
        return out.toString()
    }

    /** The host as `new URL` serializes it, or null for a failure. */
    private fun parseHost(input: String): String? {
        if (input.startsWith("[")) {
            if (!input.endsWith("]")) return null
            return parseIpv6(input.substring(1, input.length - 1))?.let { "[$it]" }
        }
        val domain = String(percentDecode(input), Charsets.UTF_8)
        val ascii = (if (domain.all { it.code <= 0x7F }) domain.lowercase(Locale.ROOT) else toAscii(domain)) ?: return null
        if (ascii.isEmpty()) return null
        if (ascii.any { it.code <= 0x1F || it.code == 0x7F || it in "#%/:<>?@[\\]^|" }) return null
        if (endsInANumber(ascii)) return parseIpv4(ascii)
        return ascii.replace("*", "%2A").replace(" ", "%20")
    }

    private fun percentDecode(input: String): ByteArray {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val high = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) hexValue(bytes[i + 1]) else -1
            val low = if (high >= 0) hexValue(bytes[i + 2]) else -1
            if (low >= 0) {
                out.write(high * 16 + low)
                i += 3
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return out.toByteArray()
    }

    private fun hexValue(byte: Byte): Int = if (byte < 0) -1 else Character.digit(byte.toInt(), 16)

    /**
     * UTS 46 as Chrome asks ICU for it (bidi and joiner checks, non-transitional), or null where ICU reports an error. The ones
     * about a name's shape rather than its characters (empty and over-long labels, hyphens) do not fail a URL's host.
     */
    private fun toAscii(domain: String): String? {
        val info = IDNA.Info()
        val ascii = UTS46.nameToASCII(domain, StringBuilder(), info).toString()
        return ascii.takeIf { info.errors.all { it in SHAPE_ERRORS } }
    }

    private val UTS46: IDNA by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IDNA.getUTS46Instance(IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII)
    }

    private val SHAPE_ERRORS = setOf(
        IDNA.Error.EMPTY_LABEL,
        IDNA.Error.LABEL_TOO_LONG,
        IDNA.Error.DOMAIN_NAME_TOO_LONG,
        IDNA.Error.LEADING_HYPHEN,
        IDNA.Error.TRAILING_HYPHEN,
        IDNA.Error.HYPHEN_3_4,
    )

    private fun parseIpv4Number(text: String): BigInteger? {
        if (text.isEmpty()) return null
        var digits = text
        var radix = 10
        if (digits.length >= 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
            digits = digits.substring(2)
            radix = 16
        } else if (digits.length >= 2 && digits[0] == '0') {
            digits = digits.substring(1)
            radix = 8
        }
        if (digits.isEmpty()) return BigInteger.ZERO
        if (digits.any { Character.digit(it, radix) < 0 || it.code > 0x7F }) return null
        return BigInteger(digits, radix)
    }

    private fun endsInANumber(host: String): Boolean {
        val parts = host.split('.').toMutableList()
        if (parts.last().isEmpty()) {
            if (parts.size == 1) return false
            parts.removeAt(parts.size - 1)
        }
        val last = parts.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return parseIpv4Number(last) != null
    }

    private fun parseIpv4(host: String): String? {
        val parts = host.split('.').toMutableList()
        if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.size - 1)
        if (parts.size > 4) return null
        val numbers = parts.map { parseIpv4Number(it) ?: return null }
        if (numbers.dropLast(1).any { it > BigInteger.valueOf(255) }) return null
        if (numbers.last() >= BigInteger.valueOf(256).pow(5 - numbers.size)) return null
        var address = numbers.last()
        for ((i, n) in numbers.dropLast(1).withIndex()) address += n * BigInteger.valueOf(256).pow(3 - i)
        val value = address.toLong()
        return (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 0xFF).toString() }
    }

    /** The four numbers of the IPv4 part of an IPv6 address, each in the IPv4 forms and at most 255, or null. Unlike a host, no trailing dot. */
    private fun embeddedIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { parseIpv4Number(it) ?: return null }
        if (numbers.any { it > BigInteger.valueOf(255) }) return null
        return IntArray(4) { numbers[it].toInt() }
    }

    private fun parseIpv6(text: String): String? {
        val address = IntArray(8)
        var pieceIndex = 0
        var compress = -1
        var pointer = 0
        fun at(i: Int): Char = if (i < text.length) text[i] else 0.toChar()
        fun hex(c: Char) = Character.digit(c, 16).takeIf { c.code <= 0x7F && it >= 0 }
        if (at(pointer) == ':') {
            if (at(pointer + 1) != ':') return null
            pointer += 2
            pieceIndex++
            compress = pieceIndex
        }
        while (pointer < text.length) {
            if (pieceIndex == 8) return null
            if (at(pointer) == ':') {
                if (compress != -1) return null
                pointer++
                pieceIndex++
                compress = pieceIndex
                continue
            }
            // The last piece, when it has a dot in it, is the IPv4 address, whatever digits and letters it is written with.
            val rest = text.substring(pointer)
            if ('.' in rest && ':' !in rest) {
                if (pieceIndex > 6) return null
                val octets = embeddedIpv4(rest) ?: return null
                address[pieceIndex] = octets[0] * 0x100 + octets[1]
                address[pieceIndex + 1] = octets[2] * 0x100 + octets[3]
                pieceIndex += 2
                break
            }
            var value = 0
            var length = 0
            while (length < 4 && pointer < text.length && hex(at(pointer)) != null) {
                value = value * 16 + hex(at(pointer))!!
                pointer++
                length++
            }
            if (pointer < text.length && at(pointer) == ':') {
                pointer++
                if (pointer >= text.length) return null
            } else if (pointer < text.length) {
                return null
            }
            address[pieceIndex] = value
            pieceIndex++
        }
        if (compress != -1) {
            var swaps = pieceIndex - compress
            pieceIndex = 7
            while (pieceIndex != 0 && swaps > 0) {
                val swapped = address[compress + swaps - 1]
                address[compress + swaps - 1] = address[pieceIndex]
                address[pieceIndex] = swapped
                pieceIndex--
                swaps--
            }
        } else if (pieceIndex != 8) {
            return null
        }
        // Serialization: the first longest run of two or more zero pieces becomes "::".
        var bestStart = -1
        var bestLength = 0
        var i = 0
        while (i < 8) {
            if (address[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < 8 && address[j] == 0) j++
            if (j - i > bestLength) {
                bestStart = i
                bestLength = j - i
            }
            i = j
        }
        if (bestLength < 2) bestStart = -1
        val out = StringBuilder()
        var ignore0 = false
        for (piece in 0 until 8) {
            if (ignore0 && address[piece] == 0) continue
            ignore0 = false
            if (bestStart == piece) {
                out.append(if (piece == 0) "::" else ":")
                ignore0 = true
                continue
            }
            out.append(Integer.toHexString(address[piece]))
            if (piece != 7) out.append(':')
        }
        return out.toString()
    }
}
