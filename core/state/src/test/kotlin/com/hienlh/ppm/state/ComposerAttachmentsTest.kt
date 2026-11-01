package com.hienlh.ppm.state

import com.hienlh.ppm.protocol.InlineImage
import com.hienlh.ppm.state.terminal.TerminalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Case for case with `attachment-marker-split.test.ts` and `image-resize.test.ts`'s selectInlineImages. */
class ComposerAttachmentsTest {
    private class Att(
        override val serverPath: String? = null,
        override val imageData: InlineImage? = null,
        override val textContent: String? = null,
    ) : SendableAttachment

    private fun payload(chars: Int = 8) = InlineImage("x".repeat(chars), "image/png")

    @Test
    fun `takes attachments without a payload out of the running`() {
        assertEquals(1, selectInlineImages(listOf(null, payload(10), null)).size)
    }

    @Test
    fun `stops at the image count the socket accepts`() {
        assertEquals(InlineImageLimits.MAX_IMAGES, selectInlineImages(List(9) { payload(10) }).size)
    }

    @Test
    fun `skips a payload over the per-image ceiling`() {
        val out = selectInlineImages(listOf(payload(InlineImageLimits.MAX_BASE64_PER_IMAGE + 1), payload(10)))
        assertEquals(listOf(10), out.map { it.data.length })
    }

    @Test
    fun `keeps going past an attachment that does not fit the total`() {
        val per = InlineImageLimits.MAX_BASE64_PER_IMAGE
        val out = selectInlineImages(listOf(payload(per), payload(1), payload(per), payload(2)))
        assertEquals(listOf(per, 1, 2), out.map { it.data.length })
    }

    @Test
    fun `nothing to send is an empty list`() {
        assertEquals(emptyList<InlineImage>(), selectInlineImages(emptyList()))
    }

    @Test
    fun `an image whose payload is going out is announced as included`() {
        val img = payload()
        assertEquals(AttachmentMarkers(listOf("/up/a.png"), emptyList()), splitAttachmentMarkers(listOf(Att("/up/a.png", img)), listOf(img)))
    }

    @Test
    fun `an image the caps left behind keeps the plain marker`() {
        val kept = payload()
        val dropped = payload(16)
        val out = splitAttachmentMarkers(listOf(Att("/up/kept.png", kept), Att("/up/dropped.png", dropped)), listOf(kept))
        assertEquals(listOf("/up/kept.png"), out.inlineImagePaths)
        assertEquals(listOf("/up/dropped.png"), out.pathOnlyPaths)
    }

    @Test
    fun `no inline set means every attachment travels by path`() {
        val out = splitAttachmentMarkers(listOf(Att("/up/a.png", payload()), Att("/up/b.pdf")), emptyList())
        assertEquals(AttachmentMarkers(emptyList(), listOf("/up/a.png", "/up/b.pdf")), out)
    }

    @Test
    fun `a non-image attachment is always path-only`() {
        val img = payload()
        assertEquals(listOf("/up/b.pdf"), splitAttachmentMarkers(listOf(Att("/up/a.png", img), Att("/up/b.pdf")), listOf(img)).pathOnlyPaths)
    }

    @Test
    fun `an attachment with no uploaded path appears in neither list`() {
        val img = payload()
        assertEquals(AttachmentMarkers(emptyList(), emptyList()), splitAttachmentMarkers(listOf(Att(imageData = img)), listOf(img)))
    }

    @Test
    fun `identical payloads are told apart by which object was picked`() {
        val first = payload()
        val second = InlineImage(first.data, first.mediaType)
        val out = splitAttachmentMarkers(listOf(Att("/up/first.png", first), Att("/up/second.png", second)), listOf(first))
        assertEquals(listOf("/up/first.png"), out.inlineImagePaths)
        assertEquals(listOf("/up/second.png"), out.pathOnlyPaths)
    }

    @Test
    fun `nothing attached is two empty lists`() {
        assertEquals(AttachmentMarkers(emptyList(), emptyList()), splitAttachmentMarkers(emptyList(), emptyList()))
    }

    @Test
    fun `a message with nothing attached is its text`() {
        assertEquals("hi", messageWithAttachments("hi", emptyList(), emptyList()))
    }

    @Test
    fun `attachments go ahead of the text, inline images first`() {
        val img = payload()
        val message = messageWithAttachments("look", listOf(Att("/up/a.png", img), Att("/up/b.pdf")), listOf(img))
        assertEquals("[Attached image (contents included in this message): /up/a.png]\n\n[Attached file: /up/b.pdf]\n\nlook", message)
    }

    @Test
    fun `several path-only files share one marker`() {
        val message = messageWithAttachments("both", listOf(Att("/up/a.txt"), Att("/up/b.txt")), emptyList())
        assertEquals("[Attached files:\n/up/a.txt\n/up/b.txt\n]\n\nboth", message)
    }

    @Test
    fun `text carried as is comes first`() {
        val message = messageWithAttachments("why?", listOf(Att("/up/a.txt"), Att(textContent = "```\nerror\n```")), emptyList())
        assertEquals("```\nerror\n```\n\n[Attached file: /up/a.txt]\n\nwhy?", message)
    }

    @Test
    fun `a terminal selection chip is its heading and block, ahead of what was typed`() {
        val chip = TerminalText.selectionContext("\n  \n$ ls -la\ntotal 4  \n\n")!!
        assertEquals(
            "Selected text from the terminal\n```\n$ ls -la\ntotal 4\n```\n\nwhy is this slow?",
            messageWithAttachments("why is this slow?", listOf(Att(textContent = chip.text)), emptyList()),
        )
    }

    @Test
    fun `the toolbar's chip is a bash block of the command and its output, and a chip alone ends in the blank line the web leaves`() {
        val chip = TerminalText.chatDraft(command = "$ bun test", output = "12 pass")!!
        assertEquals("Terminal output", chip.label)
        assertEquals("```bash\n$ bun test\n12 pass\n```\n\n", messageWithAttachments("", listOf(Att(textContent = chip.text)), emptyList()))
    }

    @Test
    fun `text chips keep the order they were added in, and go ahead of every file`() {
        val first = TerminalText.chatDraft(selection = "one")!!.text
        val second = TerminalText.selectionContext("two")!!.text
        val message = messageWithAttachments(
            "go",
            listOf(Att("/up/a.txt"), Att(textContent = first), Att(textContent = second)),
            emptyList(),
        )
        assertEquals("$first\n\n$second\n\n[Attached file: /up/a.txt]\n\ngo", message)
    }

    @Test
    fun `a chip whose text is empty adds nothing`() {
        assertEquals("go", messageWithAttachments("go", listOf(Att(textContent = "")), emptyList()))
        assertEquals("[Attached file: /up/a.txt]\n\ngo", messageWithAttachments("go", listOf(Att(textContent = ""), Att("/up/a.txt")), emptyList()))
    }

    @Test
    fun `an image only inline, its upload failed, adds nothing to the text`() {
        val img = payload()
        val picked = selectInlineImages(listOf(img))
        assertSame(img, picked.single())
        assertEquals("", messageWithAttachments("", listOf(Att(imageData = img)), picked))
    }
}
