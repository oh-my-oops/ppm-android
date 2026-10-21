package com.hienlh.ppm.protocol

import kotlinx.serialization.json.JsonElement

/** A speech model the host can install (`publicModel` in whisper-install.service.ts). */
data class SpeechModel(val id: String, val label: String, val note: String, val bytes: Long) {
    companion object {
        fun from(value: JsonElement?): SpeechModel? {
            val id = value["id"].string ?: return null
            return SpeechModel(id, value["label"].string ?: id, value["note"].string.orEmpty(), value["bytes"].long ?: 0)
        }
    }
}

/** The install running, or the last one to run (`InstallJob`). */
data class SpeechInstallJob(
    val modelId: String,
    /** `binary`, `model`, `vad` or `verify`. */
    val phase: String,
    val receivedBytes: Long,
    val totalBytes: Long,
    val done: Boolean,
    val error: String?,
) {
    /** How much of the current download has arrived, in whole percent; null before its size is known. */
    val percent: Int?
        get() = if (totalBytes > 0) Math.round(receivedBytes.toDouble() / totalBytes * 100).toInt() else null

    companion object {
        fun from(value: JsonElement?): SpeechInstallJob? {
            val modelId = value["modelId"].string ?: return null
            return SpeechInstallJob(
                modelId,
                value["phase"].string ?: "binary",
                value["receivedBytes"].long ?: 0,
                value["totalBytes"].long ?: 0,
                value["done"].bool ?: false,
                value["error"].string,
            )
        }
    }
}

/**
 * `GET /api/speech/status`: whether Whisper is installed on the host, and what it could install.
 * [binarySource] is `bundled` when PPM downloaded `whisper-cli`, `system` when it found one.
 */
data class SpeechStatus(
    val installable: Boolean,
    /** The Homebrew command a Mac installs whisper.cpp with, there being no build of it to download. */
    val installHint: String?,
    val binaryPath: String?,
    val binarySource: String?,
    val version: String,
    val model: SpeechModel?,
    val ready: Boolean,
    val models: List<SpeechModel>,
    val install: SpeechInstallJob?,
) {
    companion object {
        fun from(value: JsonElement?): SpeechStatus? {
            val ready = value["ready"].bool ?: return null
            return SpeechStatus(
                installable = value["installable"].bool ?: false,
                installHint = value["installHint"].string,
                binaryPath = value["binary"]["path"].string,
                binarySource = value["binary"]["source"].string,
                version = value["version"].string.orEmpty(),
                model = SpeechModel.from(value["model"]),
                ready = ready,
                models = value["models"].array.orEmpty().mapNotNull(SpeechModel::from),
                install = SpeechInstallJob.from(value["install"]),
            )
        }
    }
}

/**
 * The body `POST /api/speech/transcribe` takes (`voice-wav.ts`): 16-bit PCM WAV, mono, which
 * whisper.cpp reads where it reads none of what a recorder makes. The web decodes its recording
 * to get there; the app records PCM at Whisper's own rate, so all it adds is the header.
 */
object VoiceWav {
    /** `WHISPER_SAMPLE_RATE`: Whisper's own rate, and a third of what 48 kHz would upload. */
    const val SAMPLE_RATE = 16_000

    /** The first [length] bytes of [pcm], little-endian 16-bit mono samples at [sampleRate], after a 44-byte header. */
    fun fromPcm16(pcm: ByteArray, length: Int = pcm.size, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val size = length - length % 2
        val out = ByteArray(44 + size)
        fun ascii(offset: Int, text: String) = text.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        fun u32(offset: Int, value: Int) {
            for (i in 0 until 4) out[offset + i] = (value ushr (8 * i)).toByte()
        }
        fun u16(offset: Int, value: Int) {
            out[offset] = value.toByte()
            out[offset + 1] = (value ushr 8).toByte()
        }
        ascii(0, "RIFF")
        u32(4, 36 + size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        u32(16, 16) // PCM header size
        u16(20, 1) // format: PCM
        u16(22, 1) // channels: mono
        u32(24, sampleRate)
        u32(28, sampleRate * 2) // byte rate
        u16(32, 2) // block align
        u16(34, 16) // bits per sample
        ascii(36, "data")
        u32(40, size)
        pcm.copyInto(out, 44, 0, size)
        return out
    }
}
