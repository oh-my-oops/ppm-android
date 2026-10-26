package com.hienlh.ppm.app.update

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An update's dex metadata is installed only when it holds a profile and nothing else. */
class UpdateProfileTest {
    private val file = File.createTempFile("update", ".dm")

    @After
    fun remove() {
        file.delete()
    }

    private fun zip(vararg names: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `a profile with or without its metadata is taken`() {
        zip("primary.prof", "primary.profm")
        assertTrue(isProfileOnly(file))
        zip("primary.prof")
        assertTrue(isProfileOnly(file))
    }

    @Test
    fun `verified code, anything else or no profile at all is not`() {
        zip("primary.prof", "primary.profm", "primary.vdex")
        assertFalse(isProfileOnly(file))
        zip("primary.prof", "classes.dex")
        assertFalse(isProfileOnly(file))
        zip("primary.profm")
        assertFalse(isProfileOnly(file))
        zip()
        assertFalse(isProfileOnly(file))
        file.writeText("not a zip")
        assertFalse(isProfileOnly(file))
    }
}
