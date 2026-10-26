package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AppRelease
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The app's releases asked of GitHub, and an APK downloaded only when its bytes are the release's. */
class AppReleasesTest {
    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Test
    fun `the list is asked for as GitHub's API wants, and its newest app release read`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body("""[{"tag_name":"android-v0.1.1","draft":false,"assets":[{"name":"ppm.apk","browser_download_url":"https://x/ppm.apk"}]}]""")
                .build(),
        )
        server.start()
        try {
            val release = AppReleases.newest(server.url("/repos/o/r/releases?per_page=100").toString())
            assertEquals("0.1.1", release?.version)
            val request = server.takeRequest()
            assertEquals("application/vnd.github+json", request.headers["Accept"])
            assertEquals("/repos/o/r/releases?per_page=100", request.target)
        } finally {
            server.close()
        }
    }

    @Test
    fun `an apk whose bytes match the digest is kept, with its progress reported`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body(Buffer().write(apk)).build())
        server.start()
        val file = File.createTempFile("update", ".apk")
        try {
            val progress = mutableListOf<Float>()
            AppReleases.download(release(server.url("/ppm.apk").toString(), sha256), file) { progress += it }
            assertArrayEquals(apk, file.readBytes())
            assertEquals(1f, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
        } finally {
            file.delete()
            server.close()
        }
    }

    @Test
    fun `an apk whose bytes are not the release's is refused and removed`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body(Buffer().write(apk)).build())
        server.start()
        val file = File.createTempFile("update", ".apk")
        try {
            AppReleases.download(release(server.url("/ppm.apk").toString(), "0".repeat(64)), file) {}
            fail("a download that does not match its digest must throw")
        } catch (error: IOException) {
            assertEquals("The download does not match the release.", error.message)
            assertFalse(file.exists())
        } finally {
            server.close()
        }
    }

    private fun release(url: String, sha256: String) = AppRelease("0.1.1", url, apk.size.toLong(), sha256, "", null)
}
