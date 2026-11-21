package com.hienlh.ppm.app.recentphoto

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** PPM asks for photo access when it opens, and again at the next launch while access is not full. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoAccessAtLaunchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a launch without photo access asks for it`() {
        assertArrayEquals(
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            RecentPhotos.permissionsToAskAtLaunch(app),
        )
    }

    @Test
    fun `partial access is asked again at the next launch`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertArrayEquals(RecentPhotos.photoPermissions(), RecentPhotos.permissionsToAskAtLaunch(app))
    }

    @Test
    fun `full access is not asked for`() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        assertNull(RecentPhotos.permissionsToAskAtLaunch(app))
    }

    @Test
    @Config(sdk = [29])
    fun `before Android 13 it asks for storage read`() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), RecentPhotos.permissionsToAskAtLaunch(app))
    }
}
