package com.hienlh.ppm.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** fonts.xml as Android 17's emulator image writes it, cut down to what the parser has to tell apart. */
class SystemFallbackTest {
    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- A comment with a <family> in it is no family. -->
        <familyset version="23">
            <family name="sans-serif">
                <font weight="400" style="normal">Roboto-Regular.ttf
                  <axis tag="ital" stylevalue="0" />
                  <axis tag="wght" stylevalue="400" />
                </font>
                <font weight="700" style="italic">Roboto-Regular.ttf
                  <axis tag="ital" stylevalue="1" />
                  <axis tag="wght" stylevalue="700" />
                </font>
            </family>
            <alias name="arial" to="sans-serif" />
            <family name="serif">
                <font weight="400" style="normal" postScriptName="NotoSerif">NotoSerif-Regular.ttf</font>
                <font weight="700" style="normal">NotoSerif-Bold.ttf</font>
            </family>
            <family name="monospace">
                <font weight="400" style="normal">DroidSansMono.ttf</font>
            </family>
            <family name="empty"></family>
            <family>
                <font weight="400" style="normal">NotoSansSymbols-Regular-Subsetted.ttf</font>
            </family>
            <family lang="zh-Hans">
                <font weight="400" style="normal" index="2">NotoSansCJK-Regular.ttc</font>
            </family>
            <family name="source-sans-pro">
                <font weight="400" style="normal">SourceSansPro-Regular.ttf</font>
            </family>
        </familyset>
    """.trimIndent()

    @Test
    fun `named families are read in file order, the fallback list and aliases left out`() {
        val families = namedSystemFamilies(xml.byteInputStream())
        assertEquals(listOf("sans-serif", "serif", "monospace", "source-sans-pro"), families.map { it.name })
        assertEquals(
            listOf(
                SystemFont("Roboto-Regular.ttf", 400, italic = false, index = 0, axes = "'ital' 0, 'wght' 400"),
                SystemFont("Roboto-Regular.ttf", 700, italic = true, index = 0, axes = "'ital' 1, 'wght' 700"),
            ),
            families[0].fonts,
        )
        assertEquals(SystemFont("NotoSerif-Bold.ttf", 700, italic = false, index = 0, axes = ""), families[1].fonts[1])
    }

    @Test
    fun `the stack's generic family is tried first, then the file's order`() {
        val families = namedSystemFamilies(xml.byteInputStream()).map { it.name to it.name }
        assertEquals(listOf("sans-serif", "serif", "monospace", "source-sans-pro"), chromeFallbackOrder(families, "sans-serif"))
        assertEquals(listOf("monospace", "sans-serif", "serif", "source-sans-pro"), chromeFallbackOrder(families, "monospace"))
    }
}
