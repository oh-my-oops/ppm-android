package com.hienlh.ppm.web

import com.hienlh.ppm.design.R
import com.hienlh.ppm.design.Rgba
import com.hienlh.ppm.design.ThemeTokens
import kotlin.math.roundToInt

private data class DocxFont(val name: String, val regular: Int, val italic: Int, val range: String)
private val docxFonts = listOf(
    DocxFont("cyrillic-ext", R.font.geist_web_cyrillic_ext, R.font.geist_web_cyrillic_ext_italic, "U+0460-052F,U+1C80-1C8A,U+20B4,U+2DE0-2DFF,U+A640-A69F,U+FE2E-FE2F"),
    DocxFont("cyrillic", R.font.geist_web_cyrillic, R.font.geist_web_cyrillic_italic, "U+0301,U+0400-045F,U+0490-0491,U+04B0-04B1,U+2116"),
    DocxFont("vietnamese", R.font.geist_web_vietnamese, R.font.geist_web_vietnamese_italic, "U+0102-0103,U+0110-0111,U+0128-0129,U+0168-0169,U+01A0-01A1,U+01AF-01B0,U+0300-0301,U+0303-0304,U+0308-0309,U+0323,U+0329,U+1EA0-1EF9,U+20AB"),
    DocxFont("latin-ext", R.font.geist_web_latin_ext, R.font.geist_web_latin_ext_italic, "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF"),
    DocxFont("latin", R.font.geist_web_latin, R.font.geist_web_latin_italic, "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"),
)
internal val docxFontResources = buildMap {
    for (font in docxFonts) {
        put("/fonts/${font.name}.ttf", font.regular)
        put("/fonts/${font.name}-italic.ttf", font.italic)
    }
}

/** Same docx-preview classes, including the web font's unicode subsets and variable weights. */
internal fun docxPage(safeHtml: String, tokens: ThemeTokens, dark: Boolean): String {
    val fonts = docxFonts.joinToString("\n") { font ->
        listOf(false, true).joinToString("\n") { italic ->
            "@font-face{font-family:PpmGeist;font-style:${if (italic) "italic" else "normal"};font-weight:100 900;src:url(https://$DOCX_HOST/fonts/${font.name}${if (italic) "-italic" else ""}.ttf) format('truetype');unicode-range:${font.range}}"
        }
    }
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; font-src https://$DOCX_HOST; base-uri 'none'; form-action 'none'">
<style>$fonts
*{box-sizing:border-box}html,body{margin:0;padding:0}body{background:${if (dark) tokens.bgSolid.css() else "#fff"};color:${tokens.text.css()};font-family:PpmGeist,system-ui,sans-serif;font-size:14px;line-height:1.625;overflow-wrap:break-word}
main{max-width:768px;margin:0 auto;padding:32px 24px}a{color:inherit;text-decoration:inherit}strong,b{font-weight:bolder}h1,h2,h3,p,ul,ol{margin:0}table{border-collapse:collapse;width:100%;margin:12px 0}td,th{border:1px solid ${tokens.border.css()};padding:4px 8px}th{font-weight:600;background:${tokens.panel.opacity(0.5).css()}}
img{display:block;max-width:100%;height:auto;border-radius:4px}h1{font-size:24px;line-height:32px;font-weight:700;margin:24px 0 12px}h2{font-size:20px;line-height:28px;font-weight:600;margin:20px 0 8px}h3{font-size:18px;line-height:28px;font-weight:500;margin:16px 0 8px}p,ul,ol{margin:8px 0}ul,ol{padding-left:24px}ul{list-style:disc}ol{list-style:decimal}li{margin:2px 0}
</style></head><body><main>$safeHtml</main></body></html>"""
}

private fun Rgba.css() = "rgba(${(r * 255).roundToInt()},${(g * 255).roundToInt()},${(b * 255).roundToInt()},$a)"
