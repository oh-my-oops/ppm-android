package com.hienlh.ppm.chat

import com.hienlh.ppm.chat.InlineMarkdown.Inline
import com.hienlh.ppm.chat.InlineMarkdown.Kind

/** Where a picture stands in [FlatInline.text]. */
internal const val IMAGE_SLOT = ''

/** [url] is set for a link only. */
internal data class Span(val start: Int, val end: Int, val kind: Kind, val url: String? = null)

/** [InlineMarkdown.tree] flattened to test the parser by: its text, the ranges styling it, and its pictures in the order of their [IMAGE_SLOT]s. */
internal data class FlatInline(val text: String, val spans: List<Span>, val images: List<InlineMarkdown.Image>)

internal fun flatInline(source: String): FlatInline {
    val out = StringBuilder()
    val spans = mutableListOf<Span>()
    val images = mutableListOf<InlineMarkdown.Image>()
    fun render(nodes: List<Inline>) {
        for (node in nodes) {
            val start = out.length
            when (node) {
                is Inline.Text -> out.append(node.text)
                is Inline.Html -> out.append(node.html)
                is Inline.Code -> {
                    out.append(node.text)
                    if (out.length > start) spans += Span(start, out.length, Kind.CODE)
                }
                is Inline.Image -> {
                    out.append(IMAGE_SLOT)
                    images += InlineMarkdown.Image(node.url, node.alt)
                }
                is Inline.Styled -> {
                    render(node.children)
                    if (out.length > start) spans += Span(start, out.length, node.kind)
                }
                is Inline.Link -> {
                    render(node.children)
                    if (out.length > start) spans += Span(start, out.length, Kind.LINK, node.url)
                }
            }
        }
    }
    render(InlineMarkdown.tree(source))
    return FlatInline(out.toString(), spans.sortedWith(compareBy<Span> { it.start }.thenByDescending { it.end }.thenBy { it.kind }), images)
}
