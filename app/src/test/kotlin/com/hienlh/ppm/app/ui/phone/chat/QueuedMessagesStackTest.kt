package com.hienlh.ppm.app.ui.phone.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hienlh.ppm.app.withComposeActivity
import com.hienlh.ppm.chat.ChatImages
import com.hienlh.ppm.chat.LocalChatImages
import com.hienlh.ppm.design.BuiltInThemes
import com.hienlh.ppm.design.ImageViewer
import com.hienlh.ppm.design.LocalImageViewer
import com.hienlh.ppm.design.PpmThemeProvider
import com.hienlh.ppm.design.ViewerImage
import com.hienlh.ppm.protocol.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The queue above the composer (`queued-messages-stack.test.tsx`): a queued message's attached
 * images are drawn as the thumbnails the sent bubble draws, never as the attachment markers' text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class QueuedMessagesStackTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = withComposeActivity(composeRule)

    private val image = "/home/u/.ppm/uploads/1c5040cb-image.png"
    private val withImage = "[Attached image (contents included in this message): $image]\n\nlook at this"

    private val images = object : ChatImages {
        override suspend fun load(path: String) = ImageBitmap(1, 1)
        override suspend fun read(path: String) = ViewerImage("1c5040cb-image.png", ByteArray(0), "image/png")
    }

    private val opened = mutableListOf<Pair<List<String>, Int>>()

    private fun show(vararg queue: QueuedMessage) {
        composeRule.setContent {
            PpmThemeProvider(theme = BuiltInThemes.auroraDark, mobile = true) {
                CompositionLocalProvider(
                    LocalChatImages provides images,
                    LocalImageViewer provides ImageViewer { shown, index -> opened += shown.map { it.name } to index },
                ) {
                    PhoneQueuedMessagesStack(phoneQueuedMessages(queue.toList()), onSendNow = {}, onEdit = {}, onRemove = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun noneShown(text: String, substring: Boolean = false) =
        composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isEmpty()

    @Test
    fun theQueueKeepsOnlyTheTypedText_andTheAttachmentsApart() {
        val message = phoneQueuedMessages(listOf(QueuedMessage("q1", withImage, 1))).single()
        assertEquals("look at this", message.text)
        assertEquals(listOf(image), message.files)
        assertEquals(0, message.unshownImages)
        val file = phoneQueuedMessages(listOf(QueuedMessage("q2", "[Attached file: /p/notes.pdf]\n\nread it", 2))).single()
        assertEquals("read it", file.text)
        assertEquals(listOf("/p/notes.pdf"), file.files)
        assertEquals(2, file.unshownImages)
    }

    @Test
    fun anAttachedImageIsAThumbnailWithItsName_besideTheTypedText_notTheMarker() {
        show(QueuedMessage("q1", withImage, 1))
        assertTrue(noneShown("Attached image", substring = true))
        composeRule.onNodeWithText("1c5040cb-image.png").assertExists()
        composeRule.onNodeWithText("look at this").assertExists()
        // Every image has a thumbnail, so no count is shown.
        assertTrue(composeRule.onAllNodesWithContentDescription("1 image").fetchSemanticsNodes().isEmpty())
        // A tap opens it in the lightbox, among this message's pictures.
        composeRule.onNodeWithText("1c5040cb-image.png").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(listOf("1c5040cb-image.png") to 0), opened)
    }

    @Test
    fun anImageAlone_isNotNoText() {
        show(QueuedMessage("q1", withImage.substringBefore("\n\n"), 1))
        composeRule.onNodeWithText("1c5040cb-image.png").assertExists()
        assertTrue(noneShown("(no text)"))
    }

    @Test
    fun aPlainFileIsNamed_andImagesWithNoPathAreCounted() {
        show(QueuedMessage("q1", "[Attached file: /p/notes.pdf]\n\nread it", 2))
        composeRule.onNodeWithText("notes.pdf").assertExists()
        composeRule.onNodeWithText("read it").assertExists()
        assertTrue(noneShown("Attached file", substring = true))
        composeRule.onNodeWithContentDescription("2 images").assertExists()
    }

    @Test
    fun terminalOutputAloneSaysSo() {
        show(QueuedMessage("q1", "```bash\nls -la\n```", 0))
        composeRule.onNodeWithText("(terminal output)").assertExists()
        assertTrue(noneShown("(no text)"))
    }
}
