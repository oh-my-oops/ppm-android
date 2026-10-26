package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's releases read out of the PPM repository's list, where the server's are too. */
class AppReleaseTest {
    private fun release(tag: String, draft: Boolean = false, assets: String = APK) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":true,"body":"Notes for $tag","html_url":"https://github.com/o/r/releases/tag/$tag","assets":[$assets]}"""

    @Test
    fun `the newest android tag with an apk wins over the server's tags, drafts and asset-less releases`() {
        val list = WireJson.parse(
            "[" + listOf(
                release("v0.23.20"),
                release("android-v0.1.2", draft = true),
                release("android-v0.1.10", assets = """{"name":"notes.txt","browser_download_url":"https://x/notes.txt"}"""),
                release("android-v0.1.9"),
                release("android-v0.1.1"),
                release("android-v0.2"),
            ).joinToString(",") + "]",
        )
        val newest = AppRelease.newest(list)!!
        assertEquals("0.1.9", newest.version)
        assertEquals("https://github.com/o/r/releases/download/android-v0.1.9/ppm-android.apk", newest.apkUrl)
        assertEquals(6_404_937L, newest.apkSize)
        assertEquals("ab".repeat(32), newest.sha256)
        assertEquals("Notes for android-v0.1.9", newest.notes)
        assertEquals("https://github.com/o/r/releases/tag/android-v0.1.9", newest.page)
    }

    @Test
    fun `no release of the app is none, and a digest that is not sha-256 is left out`() {
        assertNull(AppRelease.newest(WireJson.parse("[" + release("v0.23.20") + "]")))
        assertNull(AppRelease.newest(WireJson.parse("""{"message":"API rate limit exceeded"}""")))
        val md5 = """{"name":"ppm.apk","browser_download_url":"https://x/ppm.apk","digest":"md5:00"}"""
        assertNull(AppRelease.newest(WireJson.parse("[" + release("android-v1.0.0", assets = md5) + "]"))!!.sha256)
    }

    @Test
    fun `a device gets the profile in the newest format it reads, and none without a digest`() {
        fun profile(name: String, digest: Boolean = true) =
            """{"name":"$name","size":13497,"browser_download_url":"https://x/$name"""" +
                (if (digest) ""","digest":"sha256:${"cd".repeat(32)}"""" else "") + "}"
        val assets = listOf(
            APK,
            profile("ppm-android-0.1.9.api28.dm"),
            profile("ppm-android-0.1.9.api31.dm"),
            profile("ppm-android-0.1.9.api33.dm", digest = false),
            profile("ppm-android-0.1.9.dm"),
        ).joinToString(",")
        val release = AppRelease.newest(WireJson.parse("[" + release("android-v0.1.9", assets = assets) + "]"))!!
        assertEquals(listOf(28, 31), release.profiles.map { it.minApi })
        assertNull(release.profileFor(27))
        assertEquals("https://x/ppm-android-0.1.9.api28.dm", release.profileFor(28)?.url)
        assertEquals(28, release.profileFor(30)?.minApi)
        assertEquals(31, release.profileFor(31)?.minApi)
        assertEquals(31, release.profileFor(37)?.minApi)
        assertEquals("cd".repeat(32), release.profileFor(37)?.sha256)
        assertEquals(13497L, release.profileFor(37)?.size)
    }

    @Test
    fun `versions compare as the web's compareSemver does`() {
        assertTrue(compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
        assertEquals(0, compareVersions("0.1.0", "0.1"))
        assertEquals(0, compareVersions("0.1.0-beta", "0.1.0"))
        assertTrue(compareVersions("0.1.0", "0.1.1") < 0)
    }

    private companion object {
        val APK = """{"name":"ppm-android.apk","size":6404937,"digest":"sha256:${"AB".repeat(32)}",""" +
            """"browser_download_url":"https://github.com/o/r/releases/download/android-v0.1.9/ppm-android.apk"}"""
    }
}
