package com.hienlh.ppm.app.ui

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The paperclip's chooser on a tablet offers the photo picker, so several pictures go in at once. */
@RunWith(RobolectricTestRunner::class)
class ComposerAttachChooserTest {
    @Test
    @Config(sdk = [34])
    fun `the photo picker takes as many pictures as the system allows`() {
        val picker = photoPickerIntent()!!
        assertEquals(MediaStore.ACTION_PICK_IMAGES, picker.action)
        val max = picker.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 0)
        assertEquals(MediaStore.getPickImagesMaxLimit(), max)
        assertTrue("more than one picture", max > 1)
    }

    @Test
    @Config(sdk = [29])
    fun `before Android 13 there is no photo picker entry`() {
        assertNull(photoPickerIntent())
    }
}
