package com.hienlh.ppm.app.update

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Whether [file] is dex metadata holding a Baseline Profile and nothing else. A `.dm` may also
 * carry a vdex, the APK's code already verified, which Android may take instead of verifying the
 * code itself: nothing but GitHub's digest vouches for an update's `.dm`, where Android checks the
 * APK's own signature, so one is only used to say what to compile.
 */
internal fun isProfileOnly(file: File): Boolean = try {
    ZipFile(file).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        "primary.prof" in names && names.all { it == "primary.prof" || it == "primary.profm" }
    }
} catch (_: IOException) {
    false
}
