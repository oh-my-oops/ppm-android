package com.hienlh.ppm.client

import com.hienlh.ppm.protocol.AppRelease
import com.hienlh.ppm.protocol.WireJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * This app's releases on GitHub ([AppRelease]), read without signing in: GitHub allows an
 * address 60 such requests an hour, and the app asks once a day.
 */
object AppReleases {
    /** The PPM repository, whose releases carry the app's APK beside the server's. */
    const val LIST_URL = "https://api.github.com/repos/hienlh/ppm/releases?per_page=100"

    /** The newest release of the app in the list at [url]; throws [IOException] when GitHub cannot be asked. */
    suspend fun newest(url: String = LIST_URL): AppRelease? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val text = PpmHttp.getClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}.")
            withContext(Dispatchers.IO) { response.body.string() }
        }
        return AppRelease.newest(WireJson.parseOrNull(text))
    }

    /**
     * Writes [release]'s APK to [file], telling [onProgress] the share done when the size is
     * known; throws [IOException] when the download fails or its bytes are not the ones GitHub's
     * digest names, leaving no file behind.
     */
    suspend fun download(release: AppRelease, file: File, onProgress: suspend (Float) -> Unit) =
        download(release.apkUrl, release.apkSize, release.sha256, file, onProgress)

    /** Writes [profile] to [file], as [download] writes the APK. */
    suspend fun download(profile: AppRelease.Profile, file: File) = download(profile.url, profile.size, profile.sha256, file) {}

    private suspend fun download(url: String, size: Long?, sha256: String?, file: File, onProgress: suspend (Float) -> Unit) {
        val request = Request.Builder().url(url).build()
        try {
            PpmHttp.client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("The download answered ${response.code}.")
                withContext(Dispatchers.IO) {
                    val total = response.body.contentLength().takeIf { it > 0 } ?: size
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    file.parentFile?.mkdirs()
                    response.body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                if (total != null) onProgress((done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    if (sha256 != null && got != sha256) throw IOException("The download does not match the release.")
                }
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}
