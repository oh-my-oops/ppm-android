package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/**
 * A release of this app on GitHub Releases: tagged `android-vX.Y.Z` beside the server's own
 * `vX.Y.Z` tags, with the APK as an asset and the Baseline Profile beside it ([profiles]).
 * [sha256] is the digest GitHub lists for the APK, when it lists one.
 */
data class AppRelease(
    val version: String,
    val apkUrl: String,
    val apkSize: Long?,
    val sha256: String?,
    val notes: String,
    val page: String?,
    val profiles: List<Profile> = emptyList(),
) {
    /**
     * The release's Baseline Profile as dex metadata (`ppm-android-X.Y.Z.api31.dm`), in the
     * format Android [minApi] and up reads: installed with the APK, it has Android compile the
     * code it names at once instead of when the device is next idle.
     */
    data class Profile(val minApi: Int, val url: String, val size: Long?, val sha256: String)

    /** The profile in the newest format a device on Android [api] reads, or null. */
    fun profileFor(api: Int): Profile? = profiles.filter { it.minApi <= api }.maxByOrNull { it.minApi }

    companion object {
        const val TAG_PREFIX = "android-v"

        /**
         * The newest of this app's releases in a `GET /repos/{owner}/{repo}/releases` answer:
         * published (not a draft), tagged `android-vX.Y.Z` and carrying an APK.
         */
        fun newest(releases: JsonElement?): AppRelease? =
            releases.array.orEmpty().mapNotNull(::from).maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

        fun from(release: JsonElement): AppRelease? {
            if (release["draft"].bool == true) return null
            val tag = release["tag_name"].string ?: return null
            if (!tag.startsWith(TAG_PREFIX)) return null
            val version = tag.removePrefix(TAG_PREFIX).takeIf { VERSION.matches(it) } ?: return null
            val assets = release["assets"].array.orEmpty()
            val apk = assets.firstOrNull { it["name"].string?.endsWith(".apk", ignoreCase = true) == true } ?: return null
            val url = apk["browser_download_url"].string ?: return null
            // A profile only with its digest: nothing else vouches for its bytes, where Android
            // checks the APK's signature itself.
            val profiles = assets.mapNotNull { asset ->
                val minApi = asset["name"].string?.let { PROFILE.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val profileUrl = asset["browser_download_url"].string ?: return@mapNotNull null
                Profile(minApi, profileUrl, asset["size"].long, sha256(asset) ?: return@mapNotNull null)
            }
            return AppRelease(version, url, apk["size"].long, sha256(apk), release["body"].string.orEmpty(), release["html_url"].string, profiles)
        }

        private fun sha256(asset: JsonElement): String? = asset["digest"].string
            ?.takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { SHA256.matches(it) }

        private val VERSION = Regex("""\d+\.\d+\.\d+""")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val PROFILE = Regex("""\.api(\d+)\.dm$""", RegexOption.IGNORE_CASE)
    }
}

/** The web's `compareSemver`: major, minor and patch as numbers, a missing part as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until 3) {
        val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}
