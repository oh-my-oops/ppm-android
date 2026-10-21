package com.hienlh.ppm.protocol

/** JavaScript's `\s`: its white space and its line terminators. */
fun isJsSpace(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    else -> false
}

/**
 * JavaScript's `toLowerCase()`, the same on every runtime. Only a capital sigma depends on what is
 * around it, and neither runtime decides that as Unicode's Final_Sigma does: Android looks at the
 * next character alone, so `ΟΔΟΣ.md` became `οδος.md` where Chrome says `οδοσ.md`, and the JVM
 * the tests run on goes by word breaks (`ΑΣ:Β`). Everything else is the runtime's.
 */
fun jsLowercase(text: String): String {
    var sigma = text.indexOf(CAPITAL_SIGMA)
    if (sigma < 0) return text.lowercase()
    val out = StringBuilder(text.length)
    var from = 0
    while (sigma >= 0) {
        out.append(text.substring(from, sigma).lowercase())
        out.append(if (isFinalSigma(text, sigma)) FINAL_SIGMA else SMALL_SIGMA)
        from = sigma + 1
        sigma = text.indexOf(CAPITAL_SIGMA, from)
    }
    return out.append(text.substring(from).lowercase()).toString()
}

private val CAPITAL_SIGMA = Char(0x3A3)
private val FINAL_SIGMA = Char(0x3C2)
private val SMALL_SIGMA = Char(0x3C3)

/** A cased letter before it and none after, case-ignorable characters skipped on either side. */
private fun isFinalSigma(text: String, index: Int): Boolean {
    var before = index
    var cased = false
    while (before > 0) {
        val point = text.codePointBefore(before)
        before -= Character.charCount(point)
        if (isCaseIgnorable(point)) continue
        cased = isCased(point)
        break
    }
    if (!cased) return false
    var after = index + 1
    while (after < text.length) {
        val point = text.codePointAt(after)
        after += Character.charCount(point)
        if (!isCaseIgnorable(point)) return !isCased(point)
    }
    return true
}

private fun isCased(point: Int) = Character.isLowerCase(point) || Character.isUpperCase(point) || Character.isTitleCase(point)

/** Marks, format characters, modifiers, and what joins a word (`'`, `.`, `:`…). */
private fun isCaseIgnorable(point: Int): Boolean = when (Character.getType(point).toByte()) {
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.MODIFIER_LETTER, Character.MODIFIER_SYMBOL -> true
    else -> point in WORD_JOINERS
}

/** Word_Break MidLetter, MidNumLet and Single_Quote. */
private val WORD_JOINERS = setOf(
    0x27, 0x2E, 0x3A, 0xB7, 0x387, 0x55F, 0x5F4, 0x2018, 0x2019, 0x2024, 0x2027, 0xFE13, 0xFE52, 0xFE55, 0xFF07, 0xFF0E, 0xFF1A,
)
